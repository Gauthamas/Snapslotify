package `in`.gauthama.snapslotify.image

import android.graphics.Bitmap
import android.graphics.Matrix
import `in`.gauthama.snapslotify.core.image.Sharpness
import kotlin.math.roundToInt

/**
 * Light pre-processing before OCR, one step at a time, for A/B runs against no pre-processing. Plain arithmetic on
 * pixels (no model, nothing invented). Each returns a new bitmap and leaves the input as it is.
 */
object ImagePrep {

    /** Contrast: brightness stretched so the 1st..99th percentile spans 0..255, every channel alike. */
    fun contrast(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val hist = LongArray(256)
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (p in row) hist[Sharpness.brightness(p)]++
        }
        val n = w.toLong() * h
        val lo = percentile(hist, n, 0.01); val hi = percentile(hist, n, 0.99)
        if (hi - lo < 16) return src.copy(Bitmap.Config.ARGB_8888, false)
        val map = IntArray(256) { ((it - lo) * 255.0 / (hi - lo)).roundToInt().coerceIn(0, 255) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (i in 0 until w) {
                val p = row[i]
                row[i] = (p and 0xFF000000.toInt()) or (map[(p shr 16) and 255] shl 16) or (map[(p shr 8) and 255] shl 8) or map[p and 255]
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    /** Mild sharpen: unsharp mask, the pixel plus [amount] times its difference from the 3 x 3 mean. */
    fun sharpen(src: Bitmap, amount: Double = 0.5): Bitmap {
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var up = IntArray(w); var mid = IntArray(w); var low = IntArray(w)
        src.getPixels(mid, 0, w, 0, 0, w, 1)
        up = mid.copyOf()
        val res = IntArray(w)
        for (y in 0 until h) {
            if (y + 1 < h) src.getPixels(low, 0, w, 0, y + 1, w, 1) else low = mid.copyOf()
            for (x in 0 until w) {
                val l = (x - 1).coerceAtLeast(0); val r = (x + 1).coerceAtMost(w - 1)
                var c = (mid[x] and 0xFF000000.toInt())
                for (shift in intArrayOf(16, 8, 0)) {
                    fun v(a: IntArray, i: Int) = (a[i] shr shift) and 255
                    val mean = (v(up, l) + v(up, x) + v(up, r) + v(mid, l) + v(mid, x) + v(mid, r) + v(low, l) + v(low, x) + v(low, r)) / 9.0
                    val s = (v(mid, x) + amount * (v(mid, x) - mean)).roundToInt().coerceIn(0, 255)
                    c = c or (s shl shift)
                }
                res[x] = c
            }
            out.setPixels(res, 0, w, 0, y, w, 1)
            val t = up; up = mid; mid = low; low = t
        }
        return out
    }

    /** Turned by [degrees] (clockwise) on white (the corners it uncovers): straightening a slightly tilted page. */
    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val turned = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees) }, true)
        val out = Bitmap.createBitmap(turned.width, turned.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(turned, 0f, 0f, null) }
        if (turned !== src) turned.recycle()
        return out
    }

    /** Enlarged by [factor] with smooth (bilinear) scaling, no detail added. */
    fun enlarge(src: Bitmap, factor: Double): Bitmap =
        Bitmap.createScaledBitmap(src, (src.width * factor).roundToInt(), (src.height * factor).roundToInt(), true)

    private fun percentile(hist: LongArray, total: Long, q: Double): Int {
        val target = (total * q).toLong()
        var seen = 0L
        for (i in hist.indices) {
            seen += hist[i]
            if (seen > target) return i
        }
        return hist.size - 1
    }
}
