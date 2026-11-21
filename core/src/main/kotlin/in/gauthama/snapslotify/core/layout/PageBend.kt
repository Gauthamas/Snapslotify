package `in`.gauthama.snapslotify.core.layout

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Rows that are not level: a photo taken a little turned tilts them, a page that curls or bows bends them (a row's height
 * changes along it, faster towards a lifted edge). Every stage that compares positions (status bar and bubble stamps,
 * sent lines, wrapped times, programme rows, month grids, chat bubbles) expects level rows, so [measure] finds how the
 * rows run across the page from the text itself, and [Bend.flatten] puts them level before the pipeline reads the page.
 *
 * - Slopes: between neighbouring words of a line, and a long one-word line's own angle from the engine; slopes steeper
 *   than [MAX_TILT_DEGREES] are not a row's. Fewer than [MIN_SAMPLES] slopes: nothing is changed.
 * - The page is cut into [STRIPS] strips across; with [MIN_STRIPS] strips holding [MIN_PER_STRIP] slopes each, each
 *   strip's median slope gives the bend (straight between strips, as it is past the outer ones); with fewer, the rows
 *   are taken as straight, at the median slope of all of them (a tilt).
 * - A row's height across the page is the running sum of the slope. The page is changed only when that height moves by
 *   at least [MIN_MOVE] of the usual line height over the part of the page the text covers: a level page, a screenshot,
 *   or a tilt too small to matter is left exactly as it is.
 *
 * Boxes move up or down only, and a box that grew because its line runs slanted shrinks back to the line's height.
 * Text, order and the number of lines are unchanged, so line indices stay valid; [Bend.photographed] maps a box back to
 * where it is in the photo (for drawing on it).
 */
object PageBend {

    const val STRIPS = 8
    const val MIN_SAMPLES = 5
    const val MIN_PER_STRIP = 2
    const val MIN_STRIPS = 4
    const val MIN_MOVE = 0.25
    const val MAX_TILT_DEGREES = 15.0

    /** How the rows run: [at] (x) is how far down a row is at x, against the middle of the text. */
    class Bend internal constructor(private val dy: (Double) -> Double) {
        fun at(x: Double): Double = dy(x)

        /** The lowest and highest of the row's height over [left, right] (its ends and middle). */
        private fun span(left: Int, right: Int): Pair<Double, Double> {
            val v = listOf(at(left.toDouble()), at((left + right) / 2.0), at(right.toDouble()))
            return v.min() to v.max()
        }

        private fun level(left: Int, top: Int, right: Int, bottom: Int): Pair<Int, Int> {
            val (lo, hi) = span(left, right)
            val t = (top - lo).roundToInt(); val b = (bottom - hi).roundToInt()
            // never thinner than half the box: a box much taller than its slant (a big heading) keeps its own height
            if (b - t >= (bottom - top) / 2) return t to b
            val mid = (top + bottom) / 2.0 - at((left + right) / 2.0); val h = (bottom - top) / 4.0
            return (mid - h).roundToInt() to (mid + h).roundToInt()
        }

        fun flatten(lines: List<OcrLine>): List<OcrLine> = lines.map { l ->
            val (t, b) = level(l.left, l.top, l.right, l.bottom)
            l.copy(top = t, bottom = b, words = l.words.map { w -> val (u, v) = level(w.left, w.top, w.right, w.bottom); w.copy(top = u, bottom = v) })
        }

        /** A box of the levelled page as it is in the photo: (left, top, right, bottom). */
        fun photographed(left: Int, top: Int, right: Int, bottom: Int): IntArray {
            val (lo, hi) = span(left, right)
            return intArrayOf(left, (top + lo).roundToInt(), right, (bottom + hi).roundToInt())
        }
    }

    /** How the rows of the page run, or null when they are level enough to read as they are. */
    fun measure(lines: List<OcrLine>, imageWidth: Int): Bend? {
        if (imageWidth <= 0 || lines.isEmpty()) return null
        val all = samples(lines)
        if (all.size < MIN_SAMPLES) return null
        val strip = imageWidth.toDouble() / STRIPS
        val known = all.groupBy { (x, _) -> (x / strip).toInt().coerceIn(0, STRIPS - 1) }
            .filterValues { it.size >= MIN_PER_STRIP }.mapValues { (_, v) -> median(v.map { it.second }) }.toSortedMap()
        val tilt = median(all.map { it.second })
        val xs = known.keys.map { (it + 0.5) * strip }
        val ks = known.values.toList()
        val bent = known.size >= MIN_STRIPS
        fun slope(x: Double): Double = when {
            !bent -> tilt
            x <= xs.first() -> ks.first()
            x >= xs.last() -> ks.last()
            else -> { val j = xs.indexOfFirst { it >= x }; ks[j - 1] + (x - xs[j - 1]) / (xs[j] - xs[j - 1]) * (ks[j] - ks[j - 1]) }
        }
        // the row's height across the page, sampled finely: the running sum of the slope
        val n = 8 * STRIPS
        val step = imageWidth.toDouble() / n
        val py = DoubleArray(n + 1)
        for (i in 1..n) py[i] = py[i - 1] + step * (slope((i - 1) * step) + slope(i * step)) / 2
        val lo = lines.minOf { it.left }.toDouble(); val hi = lines.maxOf { it.right }.toDouble()
        val covered = (0..n).filter { it * step in lo..hi }.ifEmpty { return null }
        val mid = py[covered[covered.size / 2]]
        for (i in 0..n) py[i] -= mid
        val height = lines.map { it.bottom - it.top }.sorted()[lines.size / 2].coerceAtLeast(1)
        if (covered.maxOf { py[it] } - covered.minOf { py[it] } < MIN_MOVE * height) return null
        return Bend { x ->
            val t = (x / step).coerceIn(0.0, n.toDouble())
            val i = t.toInt().coerceAtMost(n - 1)
            py[i] + (t - i) * (py[i + 1] - py[i])
        }
    }

    private fun median(v: List<Double>): Double = v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** (x, slope) of the rows: between neighbouring words of a line, and a long one-word line's own angle. */
    internal fun samples(lines: List<OcrLine>): List<Pair<Double, Double>> {
        val steepest = tan(Math.toRadians(MAX_TILT_DEGREES))
        val out = mutableListOf<Pair<Double, Double>>()
        for (l in lines) {
            val ws = l.words.sortedBy { it.left }
            for ((a, b) in ws.zipWithNext()) {
                val dx = (b.left + b.right - a.left - a.right) / 2.0
                if (dx < (a.bottom - a.top)) continue
                // words of different sizes share a baseline, not a middle (a small time stamp after a message)
                val ha = a.bottom - a.top; val hb = b.bottom - b.top
                if (minOf(ha, hb) * 5 < maxOf(ha, hb) * 4) continue
                val k = (b.top + b.bottom - a.top - a.bottom) / 2.0 / dx
                if (abs(k) <= steepest) out += (a.left + a.right + b.left + b.right) / 4.0 to k
            }
            val angle = l.angle
            if (ws.size < 2 && angle != null && l.right - l.left >= 3 * (l.bottom - l.top)) {
                val k = tan(Math.toRadians(angle.toDouble()))
                if (abs(k) <= steepest) out += (l.left + l.right) / 2.0 to k
            }
        }
        return out
    }
}
