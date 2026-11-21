package `in`.gauthama.snapslotify.core.image

import kotlin.math.abs

/**
 * How sharp an image is, measured on the image as OCR will see it, before OCR.
 *
 * Per direction (across and down), the steepest edges (the 99.9th percentile of the brightness step between the pixels
 * on either side) relative to the image's contrast (1st to 99th percentile of brightness); the score is the weaker
 * direction. Text edges in a sharp image are a pixel or two wide, so their steps are a large share of the contrast;
 * blur spreads them out. Taking the weaker direction catches motion blur, which smears text one way while lines running
 * the other way stay sharp (a Laplacian variance misses it).
 *
 * Tuned on the synthetic blur set (fixtures/blur_set: sharp 0.36-0.48, heavy, motion and defocus blur 0.05-0.15), one real
 * sharp camera photo (0.21) and the 220 dev images on disk (lowest 0.33). Low light with noise is not caught: noise adds
 * steep steps (a noisy blurred photo scored 0.21).
 */
object Sharpness {

    /** Below this an image is treated as blurry. */
    const val BLURRY_BELOW = 0.17

    fun isBlurry(score: Double): Boolean = score < BLURRY_BELOW

    /**
     * [row] fills its array with the brightness (0..255) of row y, [width] values; called once per row, top to bottom,
     * so the caller never holds the whole image in brightness form. Images under 3 x 3 score 1.0 (nothing to measure).
     */
    fun score(width: Int, height: Int, row: (y: Int, out: IntArray) -> Unit): Double {
        if (width < 3 || height < 3) return 1.0
        val across = LongArray(256); val down = LongArray(256); val level = LongArray(256)
        var up = IntArray(width).also { row(0, it) }
        var mid = IntArray(width).also { row(1, it) }
        var low = IntArray(width)
        for (y in 1 until height - 1) {
            row(y + 1, low)
            for (x in 1 until width - 1) {
                across[abs(mid[x + 1] - mid[x - 1]) / 2]++
                down[abs(low[x] - up[x]) / 2]++
                level[mid[x]]++
            }
            val t = up; up = mid; mid = low; low = t
        }
        val n = (width - 2).toLong() * (height - 2)
        val contrast = (percentile(level, n, 0.99) - percentile(level, n, 0.01)).coerceAtLeast(1).toDouble()
        return minOf(percentile(across, n, 0.999), percentile(down, n, 0.999)) / contrast
    }

    /** Brightness 0..255 of a packed ARGB pixel. */
    fun brightness(argb: Int): Int =
        (((argb shr 16) and 255) * 299 + ((argb shr 8) and 255) * 587 + (argb and 255) * 114) / 1000

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
