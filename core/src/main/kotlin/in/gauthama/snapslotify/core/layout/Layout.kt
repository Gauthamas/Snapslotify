package `in`.gauthama.snapslotify.core.layout

import `in`.gauthama.snapslotify.core.extract.Patterns.WEEKDAY
import `in`.gauthama.snapslotify.core.extract.Patterns.regex
import `in`.gauthama.snapslotify.core.ocr.OcrLine

/**
 * Position-based layout, independent of the OCR engine's line order.
 * - [blocks]: lines ordered top to bottom and grouped into blocks (bubbles) of stacked, left-aligned lines.
 * - [rows]: blocks side by side, so a two-line date block beside a two-line title-and-time block ("THU / 19 NOV" next
 *   to "Pottery Workshop / 10:30am") is one row.
 * - [isMonthGrid]: a month calendar grid (many bare day numbers and a row of weekday labels), read by [MonthGrid].
 */
object Layout {

    const val ROW_OVERLAP = 0.2
    const val BLOCK_ALIGN = 0.5
    const val BLOCK_GAP = 0.5
    const val GRID_DAY_NUMBERS = 7

    private val DAY_NUMBER = Regex("""^\W*(?:[1-9]|[12]\d|3[01])\W*$""")
    private val WEEKDAY_ONLY = regex("""^\W*$WEEKDAY\W*$""")

    /**
     * Blocks (bubbles): lines stacked on each other, top to bottom: same left edge (within [BLOCK_ALIGN] x the taller
     * line's height) and a gap of at most [gap] x the smaller line's height. Indices into [lines], each block top to bottom.
     */
    fun blocks(lines: List<OcrLine>, gap: Double = BLOCK_GAP): List<List<Int>> {
        val blocks = mutableListOf<MutableList<Int>>()
        for (i in lines.indices.sortedWith(compareBy({ lines[it].top }, { lines[it].left }))) {
            val l = lines[i]
            val h = l.bottom - l.top
            val into = blocks.lastOrNull { b ->
                val last = lines[b.last()]
                val g = l.top - last.bottom
                val hh = maxOf(h, last.bottom - last.top)
                val small = minOf(h, last.bottom - last.top)   // the gap is measured against the smaller line, so a title column does not chain
                kotlin.math.abs(l.left - last.left) <= BLOCK_ALIGN * hh && g >= -BLOCK_ALIGN * hh && g <= gap * small
            }
            if (into != null) into += i else blocks += mutableListOf(i)
        }
        return blocks
    }

    /**
     * Rows: blocks whose spans overlap vertically by at least [ROW_OVERLAP] of the shorter one, chained. Indices into
     * [lines], rows top to bottom, each row's lines left to right (then top to bottom).
     */
    fun rows(lines: List<OcrLine>): List<List<Int>> {
        val spans = blocks(lines).map { b -> Triple(b, b.minOf { lines[it].top }, b.maxOf { lines[it].bottom }) }
            .sortedBy { it.second }
        val rows = mutableListOf<Triple<MutableList<Int>, Int, Int>>()
        for ((b, top, bottom) in spans) {
            val hits = rows.filter { (_, t, bt) -> overlaps(t, bt, top, bottom) }
            if (hits.isEmpty()) {
                rows += Triple(b.toMutableList(), top, bottom)
            } else {
                val merged = (hits.flatMap { it.first } + b).toMutableList()
                rows.removeAll(hits)
                rows += Triple(merged, minOf(top, hits.minOf { it.second }), maxOf(bottom, hits.maxOf { it.third }))
            }
        }
        return rows.sortedBy { it.second }.map { (r, _, _) -> r.sortedWith(compareBy({ lines[it].left }, { lines[it].top })) }
    }

    fun isWeekdayOnly(text: String) = WEEKDAY_ONLY.matches(text.trim())

    /** At least [GRID_DAY_NUMBERS] bare day numbers and a row of weekday labels (names or single letters, [MonthGrid]). */
    fun isMonthGrid(lines: List<OcrLine>): Boolean =
        MonthGrid.numeric(lines).count { DAY_NUMBER.matches(it.text) } >= GRID_DAY_NUMBERS && MonthGrid.hasLabelRow(lines)

    /** A line that is only a day number (1 to 31). */
    fun isDayNumber(text: String): Boolean = DAY_NUMBER.matches(text)

    private fun overlaps(t1: Int, b1: Int, t2: Int, b2: Int): Boolean {
        val shorter = minOf(b1 - t1, b2 - t2).coerceAtLeast(1)
        return minOf(b1, b2) - maxOf(t1, t2) >= ROW_OVERLAP * shorter
    }
}
