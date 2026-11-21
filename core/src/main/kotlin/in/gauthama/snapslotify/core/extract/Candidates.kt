package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.extract.Patterns.regex
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.text.OcrNormalizer
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.math.abs

/** What a label word says about the date or time next to it. */
enum class LabelKind { DUE, NON_EVENT, REGISTRATION, DOORS, BOOKED_ON }

data class Label(val kind: LabelKind, val text: String)

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/** [offset] is into the normalised cleaned text (lines joined with "\n"). [box] is the box of the line it starts on. */
data class DateCandidate(
    val raw: String, val day: Int, val month: Int, val year: Int?, val startDay: Int?, val weekday: DayOfWeek?,
    val ambiguous: Boolean, val lineIndex: Int, val box: Box, val label: Label?, val offset: Int = 0,
) {
    val end: Int get() = offset + raw.length
}

data class TimeCandidate(
    val raw: String, val start: LocalTime, val end: LocalTime?, val lineIndex: Int, val box: Box, val label: Label?,
    val offset: Int = 0,
) {
    val endOffset: Int get() = offset + raw.length
}

/**
 * Candidates plus the normalised [text], each line's start offset in it, and every label word's span (for "not a label"
 * checks in the extractor).
 */
class Candidates(
    val dates: List<DateCandidate>, val times: List<TimeCandidate>, val text: String = "",
    val lineStarts: List<Int> = listOf(0), val labelSpans: List<IntRange> = emptyList(),
    /** Numbers whose stray leading characters were blanked ([LeadRepair]). */
    val repairs: List<LeadRepair.Repair> = emptyList(),
) {
    /** [start, end) of line [i] in [text]. */
    fun lineRange(i: Int): IntRange = lineStarts[i] until (lineStarts.getOrNull(i + 1)?.minus(1) ?: text.length)

    /** True if a date or time candidate covers any part of line [i] (also one that starts on the line before). */
    fun lineHasDateOrTime(i: Int): Boolean {
        val r = lineRange(i)
        return dates.any { it.offset < r.last + 1 && it.end > r.first } || times.any { it.offset < r.last + 1 && it.endOffset > r.first }
    }

    /** Line [i] without the dates, times and label words on it. */
    fun lineRest(i: Int): String {
        val r = lineRange(i)
        val chars = text.substring(r.first, r.last + 1).toCharArray()
        val spans = dates.map { it.offset until it.end } + times.map { it.offset until it.endOffset } + labelSpans
        for (s in spans) for (k in maxOf(s.first, r.first)..minOf(s.last, r.last)) chars[k - r.first] = ' '
        return String(chars)
    }
}

/**
 * Every date and time in the cleaned lines, with source line, box and nearest label. The text is normalised for OCR
 * digit confusions first ([OcrNormalizer]); raw values are taken from the normalised text.
 *
 * Nearest label, in order: a label before the value on its own line; a label on the same visual row to its left (two-column
 * key/value layouts, where ML Kit lists all labels before all values); an open label at the end of the line before.
 */
object CandidateFinder {

    /** Same row: vertical centres within this many line heights (the taller of the two lines). */
    const val ROW_TOLERANCE = 1.5

    private val LABELS: List<Pair<LabelKind, Regex>> = listOf(
        LabelKind.DUE to """payment\s+due|due\s+date|due\s+on|due|pay\s+by|pay\s+before|last\s+date|payable\s+(?:by|on)""" +
            """|renews?\s+on|renewal\s+(?:date|on)|expir(?:es|y)\s+(?:on|date)|auto-?debit\s+on""",
        LabelKind.NON_EVENT to """bill\s+date|statement\s+date|statement|billing\s+period|invoice\s+date|late\s*(?:fee|payment)|grace|disconnect\w*""",
        LabelKind.REGISTRATION to """last\s+date\s+(?:to|for)\s+regist\w*|registrations?|register\s+by|rsvp""",
        LabelKind.DOORS to """doors(?:\s+open)?|gates?\s+open""",
        LabelKind.BOOKED_ON to """booked\s+on|booking\s+date|ordered\s+on|issued\s+on""",
    ).map { (k, p) -> k to regex("""\b(?:$p)\b""") }

    private class Found(val kind: LabelKind, val text: String, val start: Int, val end: Int)

    /** [repaired]: numbers whose stray leading characters [LeadRepair.repairLines] blanked, by line. */
    fun find(lines: List<OcrLine>, repaired: Map<List<Int>, List<LeadRepair.LineRepair>> = emptyMap()): Candidates {
        val text = OcrNormalizer.normalizeNumeric(lines.joinToString("\n") { it.text })
        val lineStarts = mutableListOf(0).apply { text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        fun lineOf(offset: Int) = lineStarts.indexOfLast { it <= offset }
        fun box(i: Int) = lines[i].let { Box(it.left, it.top, it.right, it.bottom) }

        val dates = DateParser.parse(text)
        val times = TimeParser.parse(text)
        // Candidate spans per line, to decide whether a label on another line is still "open" (has no value after it)
        val spanEnds = (dates.map { it.start to it.end } + times.map { it.startOffset to it.endOffset })
            .groupBy({ lineOf(it.first) }, { it.second - lineStarts[lineOf(it.first)] })
        val labels = text.split("\n").map { line ->
            LABELS.flatMap { (k, re) -> re.findAll(line).map { Found(k, it.value, it.range.first, it.range.last + 1) } }
                .sortedBy { it.start }
        }
        fun open(li: Int) = labels[li].filter { it.start >= (spanEnds[li]?.maxOrNull() ?: -1) }

        // each line's date and time spans, as (start, end) within the line
        val spansOnLine = (dates.map { it.start to it.end } + times.map { it.startOffset to it.endOffset })
            .groupBy({ lineOf(it.first) }, { (it.first - lineStarts[lineOf(it.first)]) to (it.second - lineStarts[lineOf(it.first)]) })

        fun nearestLabel(offset: Int): Label? {
            val li = lineOf(offset)
            val col = offset - lineStarts[li]
            // a label on the line covers the value right after it, not one past another date or time ("Doors open 6:30 PM
            // Music 7:30 PM": the 7:30 is not the doors' time)
            labels[li].filter { it.end <= col }.maxByOrNull { it.end }?.let { lab ->
                val between = spansOnLine[li].orEmpty().any { (s, e) -> s >= lab.end && e <= col }
                return if (between) null else Label(lab.kind, lab.text)
            }
            val me = lines[li]
            val centre = (me.top + me.bottom) / 2.0
            lines.indices.filter { it != li && open(it).isNotEmpty() }
                .filter { j -> val o = lines[j]; (o.left + o.right) / 2.0 < me.left }
                .map { j -> j to abs((lines[j].top + lines[j].bottom) / 2.0 - centre) }
                .filter { (j, d) -> d <= ROW_TOLERANCE * maxOf(me.bottom - me.top, lines[j].bottom - lines[j].top) }
                .minByOrNull { it.second }
                ?.let { (j, _) -> return open(j).last().let { Label(it.kind, it.text) } }
            if (li == 0) return null
            return open(li - 1).lastOrNull()?.let { Label(it.kind, it.text) }
        }

        val labelSpans = labels.flatMapIndexed { li, ls -> ls.map { (lineStarts[li] + it.start) until (lineStarts[li] + it.end) } }
        return Candidates(
            dates.map { d ->
                val li = lineOf(d.start)
                DateCandidate(d.raw, d.day, d.month, d.year, d.startDay, d.weekday, d.ambiguous, li, box(li), nearestLabel(d.start), d.start)
            },
            times.map { t ->
                val li = lineOf(t.startOffset)
                TimeCandidate(t.raw, t.start, t.end, li, box(li), nearestLabel(t.startOffset), t.startOffset)
            },
            text, lineStarts, labelSpans,
            lines.flatMapIndexed { li, l -> repaired[LeadRepair.key(l)].orEmpty().map { LeadRepair.Repair(it.field, lineStarts[li] + it.start, lineStarts[li] + it.end, 0) } },
        )
    }
}
