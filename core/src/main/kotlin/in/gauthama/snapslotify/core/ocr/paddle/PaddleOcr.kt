package `in`.gauthama.snapslotify.core.ocr.paddle

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One model call. The runtime sits behind this, so the same steps run on the phone and on the laptop. */
fun interface TensorRunner {
    fun run(input: FloatArray, shape: LongArray): Tensor
}

class Tensor(val data: FloatArray, val shape: LongArray)

/**
 * PP-OCRv5 mobile, from pixels to lines, using the settings in the models' own inference.yml files. No OpenCV: detection
 * post-processing and cropping are written here, for rectangles only.
 *
 * Detection (DBPostProcess): long side 960, sides rounded to multiples of 32, BGR, ImageNet mean and std. Pixels over 0.3
 * are grouped into regions, a region under a mean of 0.6 is dropped, and each region gets its smallest rotated rectangle,
 * grown by area x 1.5 / perimeter. Recognition (CTCLabelDecode): height 48, width to keep the ratio and at least 320, BGR,
 * scaled to -1..1, greedy CTC with class 0 as the blank.
 *
 * Boxes: the rotated rectangle's axis-aligned bounds in image pixels, the same left/top/right/bottom as ML Kit's line boxes.
 * Confidence: the mean probability of the characters kept by CTC, which is PaddleOCR's own score. It is a different measure
 * from ML Kit's line confidence, so the two engines' numbers are not directly comparable.
 * Blocks: Paddle has no blocks, so each line is its own block, line 0. Order: top to bottom, left to right within 10 px.
 */
class PaddleOcr(private val det: TensorRunner, private val rec: TensorRunner, private val chars: List<String>) {

    fun recognize(width: Int, height: Int, argb: IntArray): List<OcrLine> {
        val quads = detect(width, height, argb)
        val words = mutableListOf<Word>()
        for (q in quads) {
            val (text, score) = readLine(width, height, argb, q)
            if (text.isNotBlank()) words += Word(q.bounds(width, height), q.minX, q.minY, text.trim(), score)
        }
        return LineJoin.toLines(words).mapIndexed { i, l -> OcrLine(l.text, l.box[0], l.box[1], l.box[2], l.box[3], l.score, block = i, line = 0) }
    }

    /** One detected box and what was read in it. [box] is left, top, right, bottom in image pixels. */
    class Word(val box: IntArray, val x: Float, val y: Float, val text: String, val score: Float)

    object LineJoin {
        /**
         * The detector often boxes words, or even letters of spaced-out poster text, one at a time; ML Kit returns lines.
         * Boxes are joined into one line when they overlap vertically by at least half the shorter one and the gap between
         * them is at most [GAP_PER_HEIGHT] times the line height. The line's box is their union, its text their texts with
         * spaces in reading order, and its confidence their length-weighted mean.
         */
        const val GAP_PER_HEIGHT = 1.0f

        fun toLines(words: List<Word>): List<Word> {
            val rows = mutableListOf<MutableList<Word>>()
            for (w in words.sortedWith(compareBy({ it.box[1] }, { it.box[0] }))) {
                val row = rows.firstOrNull { r -> sameRow(r, w) }
                if (row == null) rows += mutableListOf(w) else row += w
            }
            val lines = rows.flatMap { r -> splitAtGaps(r.sortedBy { it.box[0] }) }.map(::join)
            return lines.sortedWith(compareBy({ it.box[1] }, { it.box[0] }))
        }

        private fun sameRow(row: List<Word>, w: Word): Boolean {
            val top = row.minOf { it.box[1] }; val bottom = row.maxOf { it.box[3] }
            val overlap = min(bottom, w.box[3]) - max(top, w.box[1])
            return overlap >= 0.5f * min(bottom - top, w.box[3] - w.box[1])
        }

        private fun splitAtGaps(row: List<Word>): List<List<Word>> {
            val out = mutableListOf(mutableListOf(row.first()))
            for (w in row.drop(1)) {
                val cur = out.last()
                val h = cur.map { it.box[3] - it.box[1] }.average().toFloat()
                if (w.box[0] - cur.maxOf { it.box[2] } <= GAP_PER_HEIGHT * h) cur += w else out += mutableListOf(w)
            }
            return out
        }

        private fun join(ws: List<Word>): Word {
            val chars = ws.sumOf { it.text.length }.coerceAtLeast(1)
            val score = ws.sumOf { (it.score * it.text.length).toDouble() }.toFloat() / chars
            val box = intArrayOf(ws.minOf { it.box[0] }, ws.minOf { it.box[1] }, ws.maxOf { it.box[2] }, ws.maxOf { it.box[3] })
            return Word(box, box[0].toFloat(), box[1].toFloat(), ws.joinToString(" ") { it.text }, score)
        }
    }

    fun detect(width: Int, height: Int, argb: IntArray): List<Quad> {
        val ratio = DET_LONG_SIDE.toFloat() / max(width, height)
        val h = max(((height * ratio) / 32f).roundToInt() * 32, 32)
        val w = max(((width * ratio) / 32f).roundToInt() * 32, 32)
        val input = FloatArray(3 * h * w)
        forEachResized(width, height, argb, w, h) { x, y, r, g, b ->
            val i = y * w + x
            input[i] = (b / 255f - 0.485f) / 0.229f
            input[h * w + i] = (g / 255f - 0.456f) / 0.224f
            input[2 * h * w + i] = (r / 255f - 0.406f) / 0.225f
        }
        val map = det.run(input, longArrayOf(1, 3, h.toLong(), w.toLong()))
        val mh = map.shape[map.shape.size - 2].toInt()
        val mw = map.shape[map.shape.size - 1].toInt()
        return regions(map.data, mw, mh).map { it.scaled(width.toFloat() / mw, height.toFloat() / mh) }
    }

    /** Pixels over the threshold, grouped 8-connected. Public for tests. */
    fun regions(p: FloatArray, w: Int, h: Int): List<Quad> {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val found = mutableListOf<Pair<Int, Quad>>()
        for (start in 0 until w * h) {
            if (seen[start] || p[start] <= DB_THRESH) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var count = 0
            var sum = 0f
            val rowMin = HashMap<Int, Int>()
            val rowMax = HashMap<Int, Int>()
            while (top > 0) {
                val cur = stack[--top]
                val x = cur % w
                val y = cur / w
                count++
                sum += p[cur]
                rowMin[y] = min(rowMin[y] ?: x, x)
                rowMax[y] = max(rowMax[y] ?: x, x)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val n = ny * w + nx
                    if (!seen[n] && p[n] > DB_THRESH) {
                        seen[n] = true
                        stack[top++] = n
                    }
                }
            }
            if (sum / count < DB_BOX_THRESH) continue
            val points = rowMin.flatMap { (y, x) -> listOf(Pt(x.toFloat(), y.toFloat()), Pt(rowMax.getValue(y) + 1f, y.toFloat()), Pt(x.toFloat(), y + 1f), Pt(rowMax.getValue(y) + 1f, y + 1f)) }
            val quad = minAreaRect(points).unclipped(UNCLIP_RATIO)
            if (min(quad.width, quad.height) < MIN_BOX_SIDE) continue
            found += count to quad
        }
        return found.sortedByDescending { it.first }.take(MAX_CANDIDATES).map { it.second }
    }

    private fun readLine(width: Int, height: Int, argb: IntArray, quad: Quad): Pair<String, Float> {
        // PaddleOCR turns a crop much taller than it is wide by 90 degrees before reading.
        val q = if (quad.height / max(quad.width, 1f) >= 1.5f) quad.turned() else quad
        val cw = min(ceil(REC_H * q.width / max(q.height, 1f)).toInt().coerceAtLeast(1), REC_MAX_W)
        val w = max(cw, REC_MIN_W)
        val input = FloatArray(3 * REC_H * w)   // padding stays 0, as in PaddleOCR
        for (y in 0 until REC_H) for (x in 0 until cw) {
            val u = (x + 0.5f) / cw
            val v = (y + 0.5f) / REC_H
            val sx = q.p[0].x + u * (q.p[1].x - q.p[0].x) + v * (q.p[3].x - q.p[0].x)
            val sy = q.p[0].y + u * (q.p[1].y - q.p[0].y) + v * (q.p[3].y - q.p[0].y)
            sample(width, height, argb, sx, sy) { r, g, b ->
                val i = y * w + x
                input[i] = (b / 255f - 0.5f) / 0.5f
                input[REC_H * w + i] = (g / 255f - 0.5f) / 0.5f
                input[2 * REC_H * w + i] = (r / 255f - 0.5f) / 0.5f
            }
        }
        return decode(rec.run(input, longArrayOf(1, 3, REC_H.toLong(), w.toLong())))
    }

    /** Greedy CTC: class 0 is the blank, then the character list, then a space when the model has one more class. */
    fun decode(t: Tensor): Pair<String, Float> {
        val steps = t.shape[1].toInt()
        val classes = t.shape[2].toInt()
        val sb = StringBuilder()
        var last = -1
        var sum = 0f
        var kept = 0
        val row = FloatArray(classes)
        for (s in 0 until steps) {
            System.arraycopy(t.data, s * classes, row, 0, classes)
            probabilities(row)
            var best = 0
            for (c in 1 until classes) if (row[c] > row[best]) best = c
            if (best != 0 && best != last) {
                sb.append(if (best - 1 < chars.size) chars[best - 1] else " ")
                sum += row[best]
                kept++
            }
            last = best
        }
        return sb.toString() to if (kept == 0) 0f else (sum / kept).coerceIn(0f, 1f)
    }

    /** The published model already ends in a softmax; a row that does not add up to 1 gets one, so confidence stays 0..1. */
    private fun probabilities(row: FloatArray) {
        if (abs(row.sum() - 1f) < 0.01f && row.all { it >= 0f }) return
        val top = row.maxOrNull() ?: return
        var total = 0f
        for (i in row.indices) {
            row[i] = exp(row[i] - top)
            total += row[i]
        }
        for (i in row.indices) row[i] /= total
    }

    companion object {
        const val DET_LONG_SIDE = 960
        const val DB_THRESH = 0.3f
        const val DB_BOX_THRESH = 0.6f
        const val UNCLIP_RATIO = 1.5f
        const val MAX_CANDIDATES = 1000
        /** DBPostProcess skips a box whose short side is under its min_size (3) plus 2. */
        const val MIN_BOX_SIDE = 5f
        const val REC_H = 48
        const val REC_MIN_W = 320
        const val REC_MAX_W = 3200
        private const val SAME_ROW_PX = 10

        /** Top to bottom; boxes within 10 px of each other's top are one row, left to right. */
        fun readingOrder(q: List<Quad>): List<Quad> {
            val byTop = q.sortedWith(compareBy({ it.minY }, { it.minX })).toMutableList()
            for (i in 1 until byTop.size) {
                var j = i
                while (j > 0 && abs(byTop[j].minY - byTop[j - 1].minY) < SAME_ROW_PX && byTop[j].minX < byTop[j - 1].minX) {
                    val t = byTop[j]; byTop[j] = byTop[j - 1]; byTop[j - 1] = t
                    j--
                }
            }
            return byTop
        }

        /** The smallest rectangle around the points: tried along each edge of their convex hull. */
        fun minAreaRect(points: List<Pt>): Quad {
            val hull = convexHull(points)
            if (hull.size < 3) {
                val x0 = points.minOf { it.x }; val x1 = points.maxOf { it.x }
                val y0 = points.minOf { it.y }; val y1 = points.maxOf { it.y }
                return Quad(listOf(Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1)))
            }
            var best: Quad? = null
            var bestArea = Float.MAX_VALUE
            for (i in hull.indices) {
                val a = hull[i]
                val b = hull[(i + 1) % hull.size]
                val len = hypot(b.x - a.x, b.y - a.y)
                if (len == 0f) continue
                val ux = (b.x - a.x) / len
                val uy = (b.y - a.y) / len
                var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
                for (p in hull) {
                    val pu = p.x * ux + p.y * uy
                    val pv = -p.x * uy + p.y * ux
                    minU = min(minU, pu); maxU = max(maxU, pu); minV = min(minV, pv); maxV = max(maxV, pv)
                }
                val area = (maxU - minU) * (maxV - minV)
                if (area < bestArea) {
                    bestArea = area
                    fun corner(pu: Float, pv: Float) = Pt(pu * ux - pv * uy, pu * uy + pv * ux)
                    best = Quad(listOf(corner(minU, minV), corner(maxU, minV), corner(maxU, maxV), corner(minU, maxV)))
                }
            }
            return best!!.ordered()
        }

        private fun convexHull(points: List<Pt>): List<Pt> {
            val s = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
            if (s.size < 3) return s
            fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
            val lower = mutableListOf<Pt>()
            for (p in s) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1)
                lower += p
            }
            val upper = mutableListOf<Pt>()
            for (p in s.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1)
                upper += p
            }
            return lower.dropLast(1) + upper.dropLast(1)
        }

        private inline fun forEachResized(sw: Int, sh: Int, argb: IntArray, w: Int, h: Int, put: (Int, Int, Float, Float, Float) -> Unit) {
            for (y in 0 until h) for (x in 0 until w) {
                val sx = (x + 0.5f) * sw / w - 0.5f
                val sy = (y + 0.5f) * sh / h - 0.5f
                sample(sw, sh, argb, sx, sy) { r, g, b -> put(x, y, r, g, b) }
            }
        }

        /** Bilinear, clamped at the edges. */
        private inline fun sample(w: Int, h: Int, argb: IntArray, x: Float, y: Float, put: (Float, Float, Float) -> Unit) {
            val cx = x.coerceIn(0f, (w - 1).toFloat())
            val cy = y.coerceIn(0f, (h - 1).toFloat())
            val x0 = cx.toInt(); val y0 = cy.toInt()
            val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val fx = cx - x0; val fy = cy - y0
            val a = argb[y0 * w + x0]; val b = argb[y0 * w + x1]; val c = argb[y1 * w + x0]; val d = argb[y1 * w + x1]
            put(mix(a, b, c, d, fx, fy, 16), mix(a, b, c, d, fx, fy, 8), mix(a, b, c, d, fx, fy, 0))
        }

        @PublishedApi
        internal fun mix(a: Int, b: Int, c: Int, d: Int, fx: Float, fy: Float, s: Int): Float {
            val top = ((a shr s) and 255) * (1 - fx) + ((b shr s) and 255) * fx
            val bottom = ((c shr s) and 255) * (1 - fx) + ((d shr s) and 255) * fx
            return top * (1 - fy) + bottom * fy
        }
    }
}

data class Pt(val x: Float, val y: Float)

/** A rotated rectangle, clockwise from the top-left corner. */
class Quad(val p: List<Pt>) {
    val width get() = hypot(p[1].x - p[0].x, p[1].y - p[0].y)
    val height get() = hypot(p[3].x - p[0].x, p[3].y - p[0].y)
    val minX get() = p.minOf { it.x }
    val minY get() = p.minOf { it.y }

    /** Grown on every side by area x ratio / perimeter, as DBPostProcess does; for a rectangle this is the same offset. */
    fun unclipped(ratio: Float): Quad {
        val w = width; val h = height
        val perimeter = 2 * (w + h)
        if (perimeter == 0f) return this
        val d = w * h * ratio / perimeter
        val ux = if (w > 0) (p[1].x - p[0].x) / w else 1f
        val uy = if (w > 0) (p[1].y - p[0].y) / w else 0f
        val vx = if (h > 0) (p[3].x - p[0].x) / h else 0f
        val vy = if (h > 0) (p[3].y - p[0].y) / h else 1f
        fun at(i: Int, su: Int, sv: Int) = Pt(p[i].x + su * d * ux + sv * d * vx, p[i].y + su * d * uy + sv * d * vy)
        return Quad(listOf(at(0, -1, -1), at(1, 1, -1), at(2, 1, 1), at(3, -1, 1)))
    }

    fun scaled(sx: Float, sy: Float) = Quad(p.map { Pt(it.x * sx, it.y * sy) })

    /** The same rectangle read a quarter turn round. */
    fun turned() = Quad(listOf(p[1], p[2], p[3], p[0]))

    /** Clockwise from the corner nearest the image's top-left, so text reads along the first edge. */
    fun ordered(): Quad {
        val start = p.indices.minByOrNull { p[it].x + p[it].y }!!
        val rotated = List(4) { p[(start + it) % 4] }
        val turn = (rotated[1].x - rotated[0].x) * (rotated[3].y - rotated[0].y) - (rotated[1].y - rotated[0].y) * (rotated[3].x - rotated[0].x)
        return Quad(if (turn >= 0) rotated else listOf(rotated[0], rotated[3], rotated[2], rotated[1]))
    }

    /** Axis-aligned left, top, right, bottom in image pixels, kept inside the image. */
    fun bounds(imageW: Int, imageH: Int): IntArray = intArrayOf(
        minX.toInt().coerceIn(0, imageW),
        minY.toInt().coerceIn(0, imageH),
        ceil(p.maxOf { it.x }).toInt().coerceIn(0, imageW),
        ceil(p.maxOf { it.y }).toInt().coerceIn(0, imageH),
    )
}
