package `in`.gauthama.snapslotify.core.ocr

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A page photographed sideways or upside down, told from the OCR alone: a phone held flat over a page often writes no
 * usable EXIF orientation, and the engine then reads the text but gives boxes in the turned frame, so nothing laid out
 * by position (rows, columns, month grids) lines up.
 *
 * Turned 90 or 270: most lines of three or more characters are tall and narrow, and their angles agree on one
 * direction. Turned 180: most such lines lie flat and their angles agree on 180. Tilted (a page photographed at a slant,
 * [MIN_TILT] to [MAX_TILT] degrees either way): most such lines' angles agree, within [TILT_SLACK], on that angle
 * ([tilt]); the engine then misreads letters and gives boxes far too tall, so the page is read again too. Anything
 * less clear (mixed lines, angles missing or split) is no turn.
 *
 * The app reads a turned page again from the image turned upright ([read]): the engine's own reading order is what the
 * rules rely on. [upright] (boxes turned back, lines re-sorted) is only for saved dumps and tests: its top-to-bottom
 * order splits a column read as one by the engine (a list beside a month grid).
 */
object PageTurn {

    /**
     * What to extract from: [ocr] is used for everything. [turn]: how far the image was turned (clockwise, degrees: 90,
     * 180, 270, or a tilt of [MIN_TILT] to [MAX_TILT] either way) in the photo when [ocr] is the upright re-read, else 0; boxes in [ocr] are in the upright image. [turnUnread]: the page looked
     * turned but the re-read failed a guard, so [ocr] is the first read and every event gets flagged. [rereadMs]: time
     * spent in the re-read (turning the image and the second OCR), 0 when there was none. [firstLines], [rereadLines],
     * [rereadTurn]: counts for the logs (the re-read's lines and the turn still seen in it; null without a re-read).
     * [rejected]: the re-read when a guard turned it down, for the debug dumps only; never extracted from.
     */
    data class Read(
        val ocr: OcrResult, val turn: Int = 0, val turnUnread: Boolean = false, val rereadMs: Long = 0,
        val firstLines: Int = ocr.lines.size, val rereadLines: Int? = null, val rereadTurn: Int? = null,
        val rejected: OcrResult? = null,
    )

    /**
     * [first] as read; when it looks turned, [reread] (called at most once, with the turn) reads the image turned
     * upright, or gives null when it could not. The re-read is kept only when it no longer looks turned and found at
     * least [MIN_KEPT_TEXT] of the first read's letters and digits; otherwise the first read is kept, flagged. A page that is not turned never calls [reread].
     */
    inline fun read(first: OcrResult, reread: (turn: Int) -> OcrResult?): Read {
        val turn = tilt(first.lines).takeIf { it != 0 } ?: detect(first.lines)
        if (turn == 0) return Read(first)
        val t0 = System.nanoTime()
        val second = reread(turn)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val lines = second?.lines?.size
        val still = second?.let { if (mostlyTall(it.lines)) -1 else tilt(it.lines).takeIf { t -> t != 0 } ?: detect(it.lines) }
        return if (second != null && accepts(first, second)) Read(second, turn, rereadMs = ms, firstLines = first.lines.size, rereadLines = lines, rereadTurn = still)
        else Read(first, turnUnread = true, rereadMs = ms, rereadLines = lines, rereadTurn = still, rejected = second)
    }

    /**
     * The upright re-read is no longer turned (not mostly tall and narrow, no turn or tilt by angle) and lost no text. Text is
     * counted in letters and digits, not lines: read upright, the engine joins pieces it split into lines when sideways.
     */
    fun accepts(first: OcrResult, second: OcrResult): Boolean =
        textSize(second) >= MIN_KEPT_TEXT * textSize(first) && !mostlyTall(second.lines) && detect(second.lines) == 0 &&
            tilt(second.lines) == 0

    private fun textSize(o: OcrResult) = o.lines.sumOf { l -> l.text.count(Char::isLetterOrDigit) }

    /** Most deciding lines are tall and narrow. */
    fun mostlyTall(lines: List<OcrLine>): Boolean {
        val deciding = deciding(lines)
        return deciding.size >= MIN_LINES && deciding.count(::tall) >= MIN_SHARE * deciding.size
    }

    private fun deciding(lines: List<OcrLine>) = lines.filter { l -> l.text.count(Char::isLetterOrDigit) >= MIN_CHARS }

    private fun tall(l: OcrLine) = (l.bottom - l.top) >= TALL * (l.right - l.left)

    /** Lines of at least this many letters or digits decide; single numbers and short marks say nothing. */
    const val MIN_CHARS = 3

    /** At least this many deciding lines. */
    const val MIN_LINES = 3

    /** Share of the deciding lines that must be tall (or flat, for 180) and whose angle must agree. */
    const val MIN_SHARE = 0.75

    /** Tall: height at least this many times the width (flat: the other way round). */
    const val TALL = 1.5

    /** An angle agrees when within this many degrees of 90, 180 or 270. */
    const val ANGLE_SLACK = 20.0

    /** The upright re-read must keep at least this share of the first read's letters and digits. */
    const val MIN_KEPT_TEXT = 0.95

    /** A tilt of at least this many degrees is read again upright; less is left to the layout readers (a month grid levels itself). */
    const val MIN_TILT = 15

    /**
     * Up to this, the page is turned back by its own angle; beyond it, [detect]'s quarter turn. Checked before [detect],
     * whose 90 and 270 reach down to 70: a page at 71 degrees is turned back 71, not 90.
     */
    const val MAX_TILT = 72

    /** A deciding line agrees with the tilt when its angle is within this many degrees of the median. */
    const val TILT_SLACK = 10.0

    /**
     * The page's tilt in whole degrees (clockwise; negative: rows rise to the right) when most deciding lines' angles
     * agree within [TILT_SLACK] on one between [MIN_TILT] and [MAX_TILT] either way; else 0.
     */
    fun tilt(lines: List<OcrLine>): Int {
        val a = deciding(lines).mapNotNull { it.angle?.let(::normal) }.sorted()
        if (a.size < MIN_LINES) return 0
        val m = a[a.size / 2]
        if (a.count { abs(it - m) <= TILT_SLACK } < MIN_SHARE * a.size) return 0
        val t = m.roundToInt()
        return if (abs(t) in MIN_TILT..MAX_TILT) t else 0
    }

    /** 0, 90, 180 or 270: how far the page is turned clockwise; 0 when the lines are mixed or the angle is unclear. */
    fun detect(lines: List<OcrLine>): Int {
        val deciding = deciding(lines)
        if (deciding.size < MIN_LINES) return 0
        val need = MIN_SHARE * deciding.size
        val tall = deciding.count(::tall)
        val flat = deciding.count { (it.right - it.left) >= TALL * (it.bottom - it.top) }
        fun agree(turn: Int) = deciding.count { l -> l.angle?.let { near(it.toDouble(), turn) } == true }
        return when {
            tall >= need -> listOf(90, 270).singleOrNull { agree(it) >= need } ?: 0
            flat >= need && agree(180) >= need -> 180
            else -> 0
        }
    }

    /**
     * Saved dumps and tests only: the page with its boxes turned back by [turn] degrees (counter-clockwise) and its lines
     * in top-to-bottom order. Not used for extraction in the app (see the class note).
     */
    fun upright(ocr: OcrResult, turn: Int): OcrResult {
        if (turn == 0) return ocr
        require(turn == 90 || turn == 180 || turn == 270) { "turn $turn" }
        val w = ocr.imageWidth; val h = ocr.imageHeight
        fun box(l: Int, t: Int, r: Int, b: Int): IntArray = when (turn) {
            90 -> intArrayOf(t, w - r, b, w - l)
            180 -> intArrayOf(w - r, h - b, w - l, h - t)
            else -> intArrayOf(h - b, l, h - t, r)
        }
        val lines = ocr.lines.map { l ->
            val (bl, bt, br, bb) = box(l.left, l.top, l.right, l.bottom)
            l.copy(
                left = bl, top = bt, right = br, bottom = bb,
                words = l.words.map { x -> box(x.left, x.top, x.right, x.bottom).let { (a, b, c, d) -> x.copy(left = a, top = b, right = c, bottom = d) } },
                angle = l.angle?.let { normal(it - turn) },
            )
        }
        val (uw, uh) = if (turn == 180) w to h else h to w
        return OcrResult(uw, uh, readingOrder(lines), ocr.ocrMs)
    }

    /**
     * A box of the upright page back in the frame the engine saw (for drawing on the image as shown), given the upright
     * page's size and, for a tilt, the photo's size ([imageWidth] x [imageHeight]); a tilted box comes back as the box
     * around its turned corners.
     */
    fun boxInImage(
        left: Int, top: Int, right: Int, bottom: Int, turn: Int, uprightWidth: Int, uprightHeight: Int,
        imageWidth: Int = 0, imageHeight: Int = 0,
    ): IntArray =
        when (turn) {
            90 -> intArrayOf(uprightHeight - bottom, left, uprightHeight - top, right)
            180 -> intArrayOf(uprightWidth - right, uprightHeight - bottom, uprightWidth - left, uprightHeight - top)
            270 -> intArrayOf(top, uprightWidth - right, bottom, uprightWidth - left)
            0 -> intArrayOf(left, top, right, bottom)
            else -> tiltedBack(left, top, right, bottom, turn, imageWidth, imageHeight)
        }

    /**
     * The upright image is the photo turned by -[turn] about its origin, then moved so its own corner is at (0, 0) (as
     * a bitmap created with a rotation is): undo the move, then the turn.
     */
    private fun tiltedBack(left: Int, top: Int, right: Int, bottom: Int, turn: Int, w: Int, h: Int): IntArray {
        val r = Math.toRadians(-turn.toDouble()); val c = cos(r); val s = sin(r)
        val corners = listOf(0.0 to 0.0, w.toDouble() to 0.0, 0.0 to h.toDouble(), w.toDouble() to h.toDouble())
        val dx = corners.minOf { (x, y) -> x * c - y * s }; val dy = corners.minOf { (x, y) -> x * s + y * c }
        val back = listOf(left to top, right to top, left to bottom, right to bottom).map { (x, y) ->
            val ux = x + dx; val uy = y + dy
            (ux * c + uy * s) to (-ux * s + uy * c)
        }
        return intArrayOf(back.minOf { it.first }.roundToInt(), back.minOf { it.second }.roundToInt(),
            back.maxOf { it.first }.roundToInt(), back.maxOf { it.second }.roundToInt())
    }

    /** Blocks top to bottom (ties: left to right), lines in a block top to bottom; block and line numbers follow. */
    private fun readingOrder(lines: List<OcrLine>): List<OcrLine> =
        lines.groupBy { it.block }.values
            .sortedWith(compareBy<List<OcrLine>>({ b -> b.minOf { it.top } }, { b -> b.minOf { it.left } }))
            .flatMapIndexed { bi, b -> b.sortedWith(compareBy({ it.top }, { it.left })).mapIndexed { li, l -> l.copy(block = bi, line = li) } }

    private fun near(angle: Double, turn: Int): Boolean {
        val d = abs(((angle - turn) % 360 + 540) % 360 - 180)
        return d <= ANGLE_SLACK
    }

    private fun normal(a: Float): Float = (((a % 360f) + 540f) % 360f) - 180f
}
