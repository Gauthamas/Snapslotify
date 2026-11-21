package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.extract.Patterns.regex
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import kotlin.math.abs

/**
 * A bill's one event date, chosen by positive evidence only. Every other date on a bill is not an event, whatever its
 * label; there is no list of dates to avoid.
 *
 * Evidence for a date:
 * - a deadline word that belongs to it ([DEADLINE]: due, pay by, payable by, last date, on or before, ...), worth
 *   [WORD_POINTS]: before it on its own line with no other date between; or at the end of the line above, when the date's
 *   line has only connecting words before it ("is due" / "on 20.02.2027"); or the label just to its left on the same row
 *   (two-column layouts), when that label holds no date of its own; or, in a sentence split over two lines of one
 *   paragraph ([Paragraph]), the word at the end of the line above with at most [JOIN_WORDS] words between it and the
 *   date ("... Rs. 435, Due" / "date is 12th April"). A heading on the line above never counts for a date
 *   whose own line has its own words ("Due Date" / "Pay by 14 Jan"): the date belongs to its own line.
 * - an amount next to it ([MONEY]: on its own line or on the same visual row), worth [AMOUNT_POINTS]. Not the line
 *   above or below by reading order: in a two-column layout that is the next row.
 *
 * The date with the most evidence is the event's, when it alone has the most and has some. Otherwise ([Choice.date]
 * null) no date is guessed: [Choice.candidates] are the dates with the most evidence (all dates when none has any).
 * The same day written twice counts once, with its best evidence.
 */
object BillDue {

    const val WORD_POINTS = 2
    const val AMOUNT_POINTS = 1
    const val JOIN_WORDS = 3

    /** Words that make a date a payment deadline. */
    val DEADLINE = regex(
        """\b(?:due|pay(?:ment)?\s+(?:by|before|on)|payable\s+(?:by|on|before)|last\s+date|on\s+or\s+before|renews?\s+on|""" +
            """renewal\s+(?:date|on)|expir(?:es|y)\s+(?:on|date)|auto-?debit\s+on)\b""",
    )

    /** An amount of money: a currency mark or word with a number, or a number with paise or Indian / Western grouping. */
    val MONEY = regex("""(?:₹|\brs\.?|\binr\b)\s*[\d,]+(?:\.\d{1,2})?|\b\d{1,3}(?:,\d{2,3})+(?:\.\d{2})?\b|\b\d+\.\d{2}\b""")

    /** Only words that join a deadline to its date: "on", "by", "before", "is", ":", "-". */
    private val CONNECTOR = regex("""^[\s:,.\-–]*(?:(?:on|by|before|is)\b[\s:,.\-–]*)*$""")

    class Choice(val date: DateCandidate?, val candidates: List<DateCandidate>, val points: Map<DateCandidate, Int>)

    fun choose(lines: List<OcrLine>, c: Candidates): Choice {
        val dates = c.dates
        if (dates.isEmpty()) return Choice(null, emptyList(), emptyMap())
        val textLines = c.text.split("\n")
        fun lineText(i: Int) = textLines.getOrElse(i) { "" }
        // per line: where its dates and times start and end (offsets within the line)
        val spans = (dates.map { it.lineIndex to (it.offset - c.lineStarts[it.lineIndex] until it.offset - c.lineStarts[it.lineIndex] + it.raw.length) } +
            c.times.map { it.lineIndex to (it.offset - c.lineStarts[it.lineIndex] until it.endOffset - c.lineStarts[it.lineIndex]) })
            .groupBy({ it.first }, { it.second })
        fun hasDate(i: Int) = dates.any { it.lineIndex == i }

        fun before(d: DateCandidate): String {
            val li = d.lineIndex
            val col = d.offset - c.lineStarts[li]
            val prevEnd = spans[li].orEmpty().filter { it.last < col }.maxOfOrNull { it.last + 1 } ?: 0
            return lineText(li).substring(prevEnd.coerceAtMost(col), col)
        }

        fun word(d: DateCandidate): Boolean {
            val li = d.lineIndex
            val own = before(d)
            if (DEADLINE.containsMatchIn(own)) return true
            val bare = CONNECTOR.matches(own)
            // the line above ends with an open deadline (no date or time after the word); above on the page and in the same
            // column, not only before in reading order (ML Kit lists a two-column page's labels before its values)
            val up = lines.getOrNull(li - 1)
            val me0 = lines[li]
            val directlyAbove = up != null && up.bottom <= me0.top + (me0.bottom - me0.top) / 2 &&
                me0.top - up.bottom <= 1.5 * (me0.bottom - me0.top) && up.left < me0.right && up.right > me0.left
            if (bare && directlyAbove) {
                val above = lineText(li - 1)
                val m = DEADLINE.findAll(above).lastOrNull()
                if (m != null && spans[li - 1].orEmpty().none { it.first >= m.range.last }) return true
            }
            // a sentence split over two lines of one paragraph: read as one phrase, the deadline word a few words before the date
            if (Paragraph.continues(lines, li) && own.isNotBlank() && !bare) {
                val above = lineText(li - 1)
                val tail = above.substring(spans[li - 1].orEmpty().maxOfOrNull { it.last + 1 }?.coerceAtMost(above.length) ?: 0)
                val joined = "$tail $own"
                val m = DEADLINE.findAll(joined).lastOrNull()
                if (m != null && m.range.first < tail.length && joined.substring(m.range.last + 1).trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.size <= JOIN_WORDS) return true
            }
            // the label just to its left on the same row, holding no date of its own
            if (bare) {
                val me = lines[li]
                val centre = (me.top + me.bottom) / 2.0
                // the same row as the candidate finder counts it (ML Kit lists a two-column page's labels before its values)
                fun dy(j: Int) = abs((lines[j].top + lines[j].bottom) / 2.0 - centre)
                val left = lines.indices.filter { j ->
                    j != li && lines[j].right <= me.left + 4 &&
                        dy(j) <= CandidateFinder.ROW_TOLERANCE * maxOf(me.bottom - me.top, lines[j].bottom - lines[j].top)
                }.minWithOrNull(compareBy({ dy(it) }, { -lines[it].right }))
                if (left != null && !hasDate(left) && DEADLINE.containsMatchIn(lineText(left))) return true
            }
            return false
        }

        fun amount(d: DateCandidate): Boolean {
            val li = d.lineIndex
            if (MONEY.containsMatchIn(lineText(li))) return true
            val me = lines[li]
            val centre = (me.top + me.bottom) / 2.0
            return lines.indices.any { j ->
                j != li && abs((lines[j].top + lines[j].bottom) / 2.0 - centre) <= 0.6 * maxOf(me.bottom - me.top, lines[j].bottom - lines[j].top) &&
                    MONEY.containsMatchIn(lineText(j))
            }
        }

        val points = dates.associateWith { d -> (if (word(d)) WORD_POINTS else 0) + (if (amount(d)) AMOUNT_POINTS else 0) }
        // the same day written more than once counts once, with its best evidence and its first writing that has it
        val byDay = dates.groupBy { Triple(it.day, it.month, it.year) }.mapValues { (_, ds) -> ds.maxBy { points.getValue(it) } }
        val merged = byDay.values.groupBy { it.day to it.month }.flatMap { (_, ds) ->
            // "26 Feb" and "26 Feb 2027": one day; keep the one with its year when the evidence is equal
            val best = ds.maxOf { points.getValue(it) }
            listOf(ds.filter { points.getValue(it) == best }.sortedByDescending { it.year != null }.first())
        }
        val top = merged.maxOf { points.getValue(it) }
        val leaders = merged.filter { points.getValue(it) == top }
        return when {
            top > 0 && leaders.size == 1 -> Choice(leaders.single(), leaders, points)
            top > 0 -> Choice(null, leaders, points)
            else -> Choice(null, merged, points)
        }
    }
}
