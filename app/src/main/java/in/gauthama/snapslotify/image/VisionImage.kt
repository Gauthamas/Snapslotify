package `in`.gauthama.snapslotify.image

import android.graphics.Bitmap
import `in`.gauthama.snapslotify.core.vision.VisionPrompt

/** The image as try harder sends it: packed RGB at [VisionPrompt.size]. */
class VisionImage(val rgb: ByteArray, val width: Int, val height: Int) {
    companion object {
        /**
         * Shrinks by halving first and then one filtered step, so every source pixel counts (a single bilinear step from a
         * 1080 x 2400 screenshot to 288 px would skip most pixels). The source bitmap is left as it is.
         */
        fun from(source: Bitmap): VisionImage {
            val (tw, th) = VisionPrompt.size(source.width, source.height)
            var b = source
            while (b.width / 2 >= tw && b.height / 2 >= th) {
                val half = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
                if (b !== source) b.recycle()
                b = half
            }
            if (b.width != tw || b.height != th) {
                val last = Bitmap.createScaledBitmap(b, tw, th, true)
                if (b !== source) b.recycle()
                b = last
            }
            val px = IntArray(tw * th)
            b.getPixels(px, 0, tw, 0, 0, tw, th)
            if (b !== source) b.recycle()
            val rgb = ByteArray(tw * th * 3)
            for (i in px.indices) {
                val p = px[i]
                rgb[3 * i] = (p shr 16).toByte()
                rgb[3 * i + 1] = (p shr 8).toByte()
                rgb[3 * i + 2] = p.toByte()
            }
            return VisionImage(rgb, tw, th)
        }
    }
}
