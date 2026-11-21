package `in`.gauthama.snapslotify.image

import android.graphics.Bitmap
import `in`.gauthama.snapslotify.core.image.Sharpness

/** [Sharpness] of the bitmap OCR will read, one row at a time. */
fun Bitmap.sharpness(): Double {
    val px = IntArray(width)
    return Sharpness.score(width, height) { y, out ->
        getPixels(px, 0, width, 0, y, width, 1)
        for (i in 0 until width) out[i] = Sharpness.brightness(px[i])
    }
}
