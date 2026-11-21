package `in`.gauthama.snapslotify.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.gauthama.snapslotify.ocr.AppOcr
import `in`.gauthama.snapslotify.ocr.BitmapOcrImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * EXIF orientation at 0, 90, 180 and 270 degrees. Every image is made here, never a real photo: the pixels are stored
 * sideways or upside down, as a camera stores them, and the EXIF tag says how to turn them back.
 */
@RunWith(AndroidJUnit4::class)
class PhotoRotationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "rotation_test").apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Saves [stored] as a JPEG carrying the orientation tag. */
    private fun jpeg(name: String, stored: Bitmap, orientation: Int): Uri {
        val f = File(dir, name)
        f.outputStream().use { stored.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(f.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return Uri.fromFile(f)
    }

    private fun load(uri: Uri) = ImageLoader(context.contentResolver).load(uri)

    /** 400 x 200, white, with its top-left quarter red. */
    private fun marked() = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).also { b ->
        Canvas(b).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 0f, 200f, 100f, Paint().apply { color = Color.RED })
        }
    }

    private fun isRed(b: Bitmap, x: Int, y: Int) = b.getPixel(x, y).let { Color.red(it) > 180 && Color.green(it) < 90 && Color.blue(it) < 90 }

    /** Stored top-left corner ends up at the corner the tag says, and the sides swap at 90 and 270. */
    private fun checkRedCorner(orientation: Int, w: Int, h: Int, corner: Pair<Boolean, Boolean>) {
        val out = load(jpeg("corner_$orientation.jpg", marked(), orientation))
        assertEquals(orientation, out.exifOrientation)
        assertEquals("width", w, out.bitmap.width)
        assertEquals("height", h, out.bitmap.height)
        val (right, bottom) = corner
        val x = if (right) w - w / 8 else w / 8
        val y = if (bottom) h - h / 8 else h / 8
        assertTrue("red should be at right=$right bottom=$bottom", isRed(out.bitmap, x, y))
        assertTrue("the opposite corner should not be red", !isRed(out.bitmap, w - 1 - x, h - 1 - y))
    }

    @Test fun degrees0() = checkRedCorner(ExifInterface.ORIENTATION_NORMAL, 400, 200, false to false)
    @Test fun degrees90() = checkRedCorner(ExifInterface.ORIENTATION_ROTATE_90, 200, 400, true to false)
    @Test fun degrees180() = checkRedCorner(ExifInterface.ORIENTATION_ROTATE_180, 400, 200, true to true)
    @Test fun degrees270() = checkRedCorner(ExifInterface.ORIENTATION_ROTATE_270, 200, 400, false to true)

    /** Black words on white, drawn turned the opposite way to [degrees], so the tag is what makes them readable. */
    private fun textStoredTurned(degrees: Int): Bitmap {
        val upright = Bitmap.createBitmap(900, 300, Bitmap.Config.ARGB_8888).also { b ->
            Canvas(b).apply {
                drawColor(Color.WHITE)
                drawText("MEETING", 60f, 200f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 140f })
            }
        }
        if (degrees == 0) return upright
        val m = android.graphics.Matrix().apply { postRotate(-degrees.toFloat()) }
        return Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, m, true)
    }

    /** All the way through: the turned photo reaches OCR upright. */
    private fun checkReads(degrees: Int, orientation: Int) {
        val out = load(jpeg("text_$degrees.jpg", textStoredTurned(degrees), orientation))
        val text = runBlocking { AppOcr.engine.recognize(BitmapOcrImage(out.bitmap)) }.text
        assertTrue("OCR read nothing useful at $degrees degrees", text.uppercase().contains("MEETING"))
    }

    @Test fun ocrReadsAt0() = checkReads(0, ExifInterface.ORIENTATION_NORMAL)
    @Test fun ocrReadsAt90() = checkReads(90, ExifInterface.ORIENTATION_ROTATE_90)
    @Test fun ocrReadsAt180() = checkReads(180, ExifInterface.ORIENTATION_ROTATE_180)
    @Test fun ocrReadsAt270() = checkReads(270, ExifInterface.ORIENTATION_ROTATE_270)

    /** A photo over the cap comes out within it, upright. Small enough to make on a 4 GB phone. */
    @Test fun aLargePhotoIsCappedAndStillRotated() {
        val big = Bitmap.createBitmap(4000, 3000, Bitmap.Config.RGB_565).apply { eraseColor(Color.WHITE) }
        val out = load(jpeg("large.jpg", big, ExifInterface.ORIENTATION_ROTATE_90))
        big.recycle()
        assertEquals(4000, out.originalWidth)
        assertEquals(ImageLoader.MAX_SIDE, out.bitmap.height)
        assertTrue(out.bitmap.width < out.bitmap.height)
    }
}
