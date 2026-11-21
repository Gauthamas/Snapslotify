package `in`.gauthama.snapslotify.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import `in`.gauthama.snapslotify.core.extract.ShotDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.io.IOException
import kotlin.math.roundToInt

/** A decoded, upright, downscaled image, in memory only. */
class LoadedImage(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
    val exifOrientation: Int,
    val decodeMs: Long,
    val displayName: String?,
    /** EXIF names a camera. A screenshot has none; nor does a photo whose EXIF was stripped on the way here. */
    val cameraExif: Boolean = false,
    /** The image's own date: the gallery's taken / added date, else EXIF, else a date in the file name ([ShotDate]). */
    val shotDate: LocalDate? = null,
)

class ImageLoadException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { NO_IMAGE, UNREADABLE, OUT_OF_MEMORY }
}

/**
 * Reads a content URI (share sheet or picker) straight into a bitmap. Nothing is written to storage.
 *
 * The longest side is capped at [MAX_SIDE] px: a power-of-two sample on decode, then an exact scale.
 * EXIF orientation is applied, so the bitmap is upright. Call off the main thread.
 */
class ImageLoader(private val resolver: ContentResolver) {

    @Throws(ImageLoadException::class)
    fun load(uri: Uri?): LoadedImage {
        if (uri == null) throw ImageLoadException(ImageLoadException.Reason.NO_IMAGE)
        val start = SystemClock.elapsedRealtime()
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw ImageLoadException(ImageLoadException.Reason.UNREADABLE)
            }
            val exif = runCatching { open(uri).use { ExifInterface(it) } }.getOrNull()
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
            val cameraExif = exif?.getAttribute(ExifInterface.TAG_MAKE) != null || exif?.getAttribute(ExifInterface.TAG_MODEL) != null

            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
            val sampled = open(uri).use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw ImageLoadException(ImageLoadException.Reason.UNREADABLE)
            val bitmap = applyOrientation(capLongestSide(sampled), orientation)
            val name = displayName(uri)
            val exifDate = ShotDate.fromExif(exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif?.getAttribute(ExifInterface.TAG_DATETIME))
            val shot = ShotDate.pick(galleryDate(uri), exifDate, ShotDate.fromName(name))
            return LoadedImage(bitmap, bounds.outWidth, bounds.outHeight, orientation,
                SystemClock.elapsedRealtime() - start, name, cameraExif, shot)
        } catch (e: ImageLoadException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw ImageLoadException(ImageLoadException.Reason.OUT_OF_MEMORY, e)
        } catch (e: Exception) {   // FileNotFound, SecurityException (expired grant), IOException, bad URI
            throw ImageLoadException(ImageLoadException.Reason.UNREADABLE, e)
        }
    }

    private fun open(uri: Uri) = resolver.openInputStream(uri) ?: throw IOException("no stream")

    /** The gallery's taken date (ms), else its added date (s), in the phone's time zone; null when the provider has neither. */
    private fun galleryDate(uri: Uri): LocalDate? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val taken = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }?.takeIf { it > 0 }
            val added = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) * 1000 }?.takeIf { it > 0 }
            (taken ?: added)?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        }
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun capLongestSide(bitmap: Bitmap): Bitmap {
        val longSide = maxOf(bitmap.width, bitmap.height)
        if (longSide <= MAX_SIDE) return bitmap
        val scale = MAX_SIDE.toFloat() / longSide
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    companion object {
        /** Longest side after decoding; see docs/DECISIONS.md. 1080x2340 screenshots pass unchanged. */
        const val MAX_SIDE = 2560

        /** Most pixels ever decoded at once: 64 MB at 4 bytes a pixel, whatever the camera. */
        const val MAX_DECODE_PIXELS = 16_000_000L

        /**
         * Largest power of two that keeps the longest side at or above MAX_SIDE (the exact scale follows), and then as
         * much more as it takes to stay within MAX_DECODE_PIXELS. A very large camera photo hits the second limit first.
         */
        fun sampleSize(width: Int, height: Int): Int {
            val longSide = maxOf(width, height)
            var sample = 1
            while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2
            while ((width / sample).toLong() * (height / sample) > MAX_DECODE_PIXELS) sample *= 2
            return sample
        }
    }
}
