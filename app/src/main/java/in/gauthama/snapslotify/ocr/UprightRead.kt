package `in`.gauthama.snapslotify.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import `in`.gauthama.snapslotify.core.ocr.OcrEngine
import `in`.gauthama.snapslotify.core.ocr.PageTurn
import kotlinx.coroutines.CancellationException

/**
 * OCR of an image, and once more from the image turned upright when that OCR shows a page photographed sideways,
 * upside down or at a slant ([PageTurn.read]: at most one re-read, with its guards). A page that is not turned costs nothing extra.
 * The bitmap passed in is not changed or recycled.
 */
object UprightRead {

    /** A tilted turn leaves its corners empty: white, as paper, not black edges for the engine to read. */
    private fun onWhite(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(b, 0f, 0f, null) }
        b.recycle()
        return out
    }

    suspend fun read(engine: OcrEngine<BitmapOcrImage>, bitmap: Bitmap): PageTurn.Read {
        val first = engine.recognize(BitmapOcrImage(bitmap))
        return PageTurn.read(first) { turn ->
            val upright = try {
                // the page is turned [turn] degrees clockwise in the photo: turn the image back
                val back = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(-turn.toFloat()) }, true)
                if (turn % 90 == 0) back else onWhite(back)
            } catch (e: OutOfMemoryError) {
                null
            }
            upright?.let {
                try {
                    engine.recognize(BitmapOcrImage(it))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } finally {
                    if (it !== bitmap) it.recycle()
                }
            }
        }
    }
}
