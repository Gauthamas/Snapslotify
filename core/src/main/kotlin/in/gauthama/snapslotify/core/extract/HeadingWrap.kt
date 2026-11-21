package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.review.TitleRules
import `in`.gauthama.snapslotify.core.text.TitleJunk

/**
 * A heading wrapped over two or three lines ("Eye check-up -" / "Dr. Nair", "Physiotherapy" / "session - Dr." / "Rahman"):
 * the title search may pick one of its lines; the title is the whole heading. Lines join the heading above when the line
 * below sits under it (overlapping it across), less than [GAP] line heights below, holds no date or time, and either is in
 * type of a similar height (within [HEIGHT]) or continues the line above: that line ends with a dash, comma, colon,
 * ampersand or "with", "at", "for", "of", or the line below starts in lower case or with a title ("Dr", "Mr", "Ms",
 * "Prof"). It stops at a type change, a date or time line, a wide gap, or [MAX_LINES] lines. Only lines no smaller than the
 * page's median line are joined (small body text is not a heading), and the joined heading must pass the model title checks
 * ([TitleRules.modelTitleOk]: page words, at most 8 words, no new digits) or the picked line stands.
 *
 * A line of junk only ([TitleJunk.junkLine]) never joins.
 *
 * OCR gives no colour or weight for a line, so the type test is the line height alone.
 */
object HeadingWrap {

    const val HEIGHT = 0.15
    const val GAP = 1.0
    const val MAX_LINES = 3
    /** A line below this many times taller than the line above does not continue it, whatever the cue. */
    const val LEAD_IN = 1.6

    private val END_JOIN = Regex("""(?:[-–—,:&]|\b(?:with|at|for|of))\s*$""", RegexOption.IGNORE_CASE)
    private val START_LOWER = Regex("""^\W*\p{Ll}""")
    /** A greeting opens a message, it is never part of a heading ("Hey, it's a" / "Bday party for Tina!"). */
    private val GREETING = Regex("""^\W*(?:hi|hey|hello|dear)\b""", RegexOption.IGNORE_CASE)
    private val START_TITLE = Regex("""^\W*(?:dr|mr|mrs|ms|prof)\b\.?""", RegexOption.IGNORE_CASE)
    /** A word broken by a hyphen at the line end ("Health Check-" / "up Camp"). */
    private val HYPHEN_BREAK = Regex("""(\p{L})-\s+(\p{Ll})|(\p{Lu})-\s+(\p{Lu})""")

    private fun h(l: OcrLine) = (l.bottom - l.top).coerceAtLeast(1)

    /** [b] (just below [a]) continues the heading on [a]. */
    fun continues(a: OcrLine, ia: Int, b: OcrLine, ib: Int, c: Candidates): Boolean {
        if (c.lineHasDateOrTime(ia) || c.lineHasDateOrTime(ib) || GREETING.containsMatchIn(a.text) || GREETING.containsMatchIn(b.text)) return false
        // a line OCR made of junk only (a sticker, a logo, a pattern) is never part of the heading
        if (TitleJunk.junkLine(a) || TitleJunk.junkLine(b)) return false
        val gap = b.top - a.bottom
        val lh = maxOf(h(a), h(b))
        if (gap >= GAP * lh || gap < -minOf(h(a), h(b)) / 2) return false
        if (minOf(a.right, b.right) <= maxOf(a.left, b.left)) return false
        val similar = kotlin.math.abs(h(a) - h(b)) <= HEIGHT * lh
        val cue = END_JOIN.containsMatchIn(a.text.trim()) || START_LOWER.containsMatchIn(b.text) || START_TITLE.containsMatchIn(b.text)
        // a small lead-in line over a heading in much larger type ("TO CELEBRATE THE UNION OF" / "Meera") is not its wrap
        return similar || (cue && h(b) <= LEAD_IN * h(a))
    }

    /** The heading lines around line [i], top to bottom (at most [MAX_LINES]); just [i] when it is not part of one. */
    fun group(lines: List<OcrLine>, c: Candidates, i: Int): List<Int> {
        fun below(k: Int) = lines.indices.filter { it != k && lines[it].top >= lines[k].top + h(lines[k]) / 2 && minOf(lines[it].right, lines[k].right) > maxOf(lines[it].left, lines[k].left) }
            .minByOrNull { lines[it].top }
        fun above(k: Int) = lines.indices.filter { it != k && lines[it].top + h(lines[it]) / 2 <= lines[k].top && minOf(lines[it].right, lines[k].right) > maxOf(lines[it].left, lines[k].left) }
            .maxByOrNull { lines[it].bottom }
        val out = ArrayDeque(listOf(i))
        while (out.size < MAX_LINES) {
            val u = above(out.first()) ?: break
            if (!continues(lines[u], u, lines[out.first()], out.first(), c)) break
            out.addFirst(u)
        }
        while (out.size < MAX_LINES) {
            val d = below(out.last()) ?: break
            if (!continues(lines[out.last()], out.last(), lines[d], d, c)) break
            out.addLast(d)
        }
        return out.toList()
    }

    /**
     * The title [picked] (read from line [line]) widened to its whole heading, when its lines are no smaller than the page's
     * [median] line and it wraps: the joined lines, if they hold the picked words and pass the model title checks.
     */
    fun widen(picked: String, line: Int, lines: List<OcrLine>, c: Candidates, median: Int): String {
        if (line !in lines.indices) return picked
        val g = group(lines, c, line)
        if (g.size < 2 || g.any { h(lines[it]) < median }) return picked
        val joined = g.joinToString(" ") { lines[it].text.trim() }
            .replace(HYPHEN_BREAK) { m -> if (m.groupValues[1].isNotEmpty()) "${m.groupValues[1]}-${m.groupValues[2]}" else "${m.groupValues[3]}-${m.groupValues[4]}" }
            .replace(Regex("""\s+"""), " ").trim()
        fun norm(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        if (norm(picked) !in norm(joined) || norm(joined) == norm(picked)) return picked
        val page = lines.joinToString(" ") { it.text }
        return if (TitleRules.modelTitleOk(joined, page)) joined else picked
    }
}
