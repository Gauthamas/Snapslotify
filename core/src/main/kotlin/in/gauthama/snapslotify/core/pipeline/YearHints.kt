package `in`.gauthama.snapslotify.core.pipeline

import `in`.gauthama.snapslotify.core.extract.AnchoredDay
import `in`.gauthama.snapslotify.core.extract.Candidates
import `in`.gauthama.snapslotify.core.extract.DateCandidate
import `in`.gauthama.snapslotify.core.extract.Paragraph
import `in`.gauthama.snapslotify.core.extract.Patterns
import `in`.gauthama.snapslotify.core.resolve.YearHint
import `in`.gauthama.snapslotify.core.text.TextCleaner
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * What a page says about the year of a date written without one ([YearHint]), and the readings of "next <weekday>".
 * - Written date: an email's sent line, or on a chat a date line ("Sat, 31 Oct 2026"), with its year; the latest one.
 * - Past tense: a past-tense verb ([PAST]) in the date's own sentence (its line, and the line above when the sentence runs
 *   on from it, [Paragraph]).
 * - Ahead: the page is not a bill or a calendar grid (a bill's date is near either way; a grid has its own year).
 */
object YearHints {

    /** Past-tense verbs that say an event is over. */
    internal val PAST = Regex("""(?i)\b(?:was|were|took\s+place|happened|went|attended|enjoyed)\b""")

    /** "next Thursday": either the coming one or the one after it. */
    private val NEXT_WEEKDAY = Regex("""(?i)\bnext\s+(mon|tue|wed|thu|fri|sat|sun)[a-z]*\b""")

    fun written(cleaned: TextCleaner.Result): LocalDate? {
        val sent = cleaned.removed.filter { TextCleaner.isStamp(it.rule) }.map { it.line.text.trim() }
        val dateLines = if (!cleaned.looksLikeChat) emptyList()
        else (cleaned.kept.map { it.text.trim() } + cleaned.removed.map { it.line.text.trim() }).filter { Patterns.DATE_CHIP_LINE.matchEntire(it) != null }
        return (sent + dateLines).mapNotNull { t -> AnchoredDay.anchorOf(t)?.takeIf { it.second }?.first }.maxOrNull()
    }

    /** The hint for each date candidate, by its offset. */
    /** [now]: when the page was seen (the screenshot's date, else today). */
    fun of(cleaned: TextCleaner.Result, c: Candidates, ahead: Boolean, now: LocalDate): Map<Int, YearHint> {
        val written = written(cleaned)
        val lines = cleaned.kept
        val textLines = c.text.split("\n")
        return c.dates.associate { d: DateCandidate ->
            val li = d.lineIndex
            val sentence = textLines.getOrElse(li) { "" } + (if (Paragraph.continues(lines, li)) " " + textLines.getOrElse(li - 1) { "" } else "")
            d.offset to YearHint(ahead = ahead, now = now, written = written, past = PAST.containsMatchIn(sentence))
        }
    }

    /** Each "next <weekday>": its line, the phrase, and its two readings from [anchor] (the coming one, a week later). */
    fun nextWeekday(c: Candidates, anchor: LocalDate): List<Triple<Int, String, List<LocalDate>>> =
        NEXT_WEEKDAY.findAll(c.text).mapNotNull { m ->
            val wd = Patterns.weekday(m.groupValues[1]) ?: return@mapNotNull null
            val coming = anchor.with(TemporalAdjusters.next(wd))
            Triple(c.lineStarts.indexOfLast { it <= m.range.first }, m.value, listOf(coming, coming.plusWeeks(1)))
        }.toList()
}
