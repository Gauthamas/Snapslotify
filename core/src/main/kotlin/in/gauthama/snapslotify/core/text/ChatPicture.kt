package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.chat.ChatRouter
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import kotlin.math.abs

/**
 * A picture inside a chat (a poster or an invite shared as an image), told from the chat's own lines: a run of three or
 * more lines ([MIN_LINES]) close together, at least one of them clearly larger than the chat's text line ([BIG] x its
 * median, time-only lines left out), that keeps a column of its own (most lines start where no message line starts) or
 * carries no message stamps (no line ending in a 24-hour clock). A small time that lines up with the chat's other stamps
 * ends the run (the picture's corner stamp); a small time inside the run's width is the picture's own text.
 *
 * It only decides one thing: beside such a picture, a time a stamp rule took out near a shown event's date is offered as
 * a time chip ([`in`.gauthama.snapslotify.core.pipeline.CodePipeline.nearDateStamps]). Nothing is put back and the page
 * is read as before. A picture's date line is kept from being a chat date line by the separator test of
 * [`in`.gauthama.snapslotify.core.extract.ChatBubbles], which needs no region.
 */
object ChatPicture {

    const val BIG = 1.25
    const val MIN_LINES = 3
    const val GAP = 6.0          // x the larger of the median and the line above: a picture's own white space, not a chat's
    const val END_BELOW = 12.0   // x the median: a line inside the picture's width may follow this far under the one above
    const val FOOT = 0.25        // of the height: a poster's footer may sit this far under its text, inside its width
    const val HEADER = 0.12      // of the height: the chat's header (name, presence), not message text
    const val ALIGN = 0.02       // of the width: two edges this close line up

    class Region(val lines: List<OcrLine>)

    private val CLOCK = Regex("""^\W*\d{1,2}[:.]\d{2}\s*(?:[ap]\.?\s?m\.?)?[\W✓✔vV]*$""", RegexOption.IGNORE_CASE)
    private val MESSAGE_STAMP = Regex("""\S.*\s\d{1,2}:\d{2}[\W✓✔vV]*$""")
    private val AMPM_END = Regex("""[ap]\.?\s?m\.?\W*$""", RegexOption.IGNORE_CASE)

    private fun h(l: OcrLine) = l.bottom - l.top

    fun find(all: List<OcrLine>, width: Int, height: Int = 0): Region? {
        if (all.size < MIN_LINES + 2) return null
        val ls = all.sortedWith(compareBy({ it.top }, { it.left }))
        // the median of text lines: a chat's many small stamps would make every message line look large
        fun median(of: List<OcrLine>) = of.filterNot { CLOCK.matches(it.text.trim()) }.map(::h).sorted().let { if (it.isEmpty()) null else it[it.size / 2].toDouble().coerceAtLeast(1.0) }
        val m = median(ls) ?: return null
        val w = width.coerceAtLeast(1).toDouble()
        // a time alone on its line, not large; a chat stamp lines up with the chat's other stamps (a picture's own time does not)
        val small = ls.filter { CLOCK.matches(it.text.trim()) && h(it) < BIG * m }
        fun corner(l: OcrLine) = l in small && small.any { o -> o !== l && (abs(o.left - l.left) <= ALIGN * w || abs(o.right - l.right) <= ALIGN * w) }
        fun barrier(l: OcrLine) = corner(l) || ChatRouter.MESSAGE_BOX.matches(l.text.trim()) || l.text.trim().equals("message", ignoreCase = true)
        fun inside(l: OcrLine, left: Int, right: Int) = (l.left + l.right) / 2 in left..right

        var best: Region? = null
        var i = 0
        while (i < ls.size) {
            if (h(ls[i]) < BIG * m || barrier(ls[i])) { i++; continue }
            val run = ArrayDeque(listOf(ls[i])); var left = ls[i].left; var right = ls[i].right
            // a line inside the picture's width may follow a wider gap (a poster's white space) than one outside it
            fun joins(l: OcrLine, gap: Int, near: OcrLine) = !barrier(l) && when {
                inside(l, left, right) -> gap <= maxOf(END_BELOW * maxOf(m, h(near).toDouble()), FOOT * height)
                h(l) >= BIG * m -> gap <= GAP * maxOf(m, h(near).toDouble())
                else -> false
            }
            var j = i + 1
            while (j < ls.size && joins(ls[j], ls[j].top - run.last().bottom, run.last())) {
                val l = ls[j]; run.addLast(l); left = minOf(left, l.left); right = maxOf(right, l.right); j++
            }
            var k = i - 1
            while (k >= 0 && joins(ls[k], run.first().top - ls[k].bottom, ls[k])) {
                val l = ls[k]; run.addFirst(l); left = minOf(left, l.left); right = maxOf(right, l.right); k--
            }
            val lines = run.toList()
            i = maxOf(j, i + 1)
            if (lines.size < MIN_LINES) continue
            // measured against the chat's own lines: the page's median is the picture's body text when the picture is large
            val outside = ls.filter { it !in lines }
            val mc = median(outside) ?: m
            val big = lines.count { h(it) >= BIG * mc }
            if (big < 1) continue
            val headerEnd = if (height > 0) HEADER * height else ls.first().top.toDouble()
            val others = outside.filter { !CLOCK.matches(it.text.trim()) && it.top > headerEnd }
            val ownAlign = lines.count { l -> others.none { abs(it.left - l.left) <= ALIGN * w } } * 2 > lines.size
            val noStamps = lines.none { MESSAGE_STAMP.matches(it.text.trim()) && !AMPM_END.containsMatchIn(it.text.trim()) }
            // a long wrapped message shares the bubble margin and carries its stamp; with one larger line both must hold,
            // with two or more, one is enough
            if (!(ownAlign && noStamps) && !(big >= 2 && (ownAlign || noStamps))) continue
            if (best == null || lines.size > best.lines.size) best = Region(lines)
        }
        return best
    }

    /** The removals stamp rules make: beside a picture, a time they took out near a shown date is offered as a chip. */
    fun isStampRule(rule: String) = rule == "bubble timestamp" || rule == "message time stamp" || rule.startsWith("message stamp") ||
        rule == "sent stamp" || rule == "size stamp"
}
