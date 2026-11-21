package `in`.gauthama.snapslotify.core.chat

import `in`.gauthama.snapslotify.core.resolve.DateResolver
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Turns the candidate phrase the model picked into a calendar date. Used only for the chat chips, which the user checks
 * before tapping: DateResolver and the code baseline keep their rule of never resolving relative words.
 *
 * - "saturday", "sat", "this saturday": that day on or after [today]; "coming saturday": strictly after.
 * - "next saturday": the Saturday of next week (weeks start on Monday).
 * - "weekend": the coming Saturday; "next weekend": Saturday of next week.
 * - "the 14th": that day this month, or next month if it has passed.
 * - "second monday", "last friday": of this month, or next month if it has passed.
 * - Past or vague phrases give null, so no chip: "yesterday", "next week", "weekdays", statements of today's date.
 */
object ChatDates {

    data class Dates(val start: LocalDate, val end: LocalDate?)

    private val WEEKDAYS = DayOfWeek.entries.associateBy { it.name.lowercase() }
    private const val SMALL = "a|one|two|three|four|five|six|seven"
    private val SMALL_VALUES = mapOf("a" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7)
    private val ORDINAL_WORDS = listOf(
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth", "thirteenth",
        "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth", "twentieth",
    )
    private val IN_N = Regex("""in (\d+|$SMALL) (day|week)s?""")
    private val QUALIFIED = Regex("""(this|coming|next) ([a-z]+)""")
    private val NTH = Regex("""(first|second|third|fourth|last) ([a-z]+)""")
    private val DAY_ONLY = Regex("""(?:[a-z]+[\s,]+)?(?:the )?(\d{1,2})(?:st|nd|rd|th)""")
    private val ANCHOR = Regex("""\b(is|was)\b|^it['’]?s\b""")
    private val NUMERIC = Regex("""(\d{1,2})[/.](\d{1,2})(?:[/.](\d{2}|\d{4}))?""")

    fun resolve(phrase: String, today: LocalDate): Dates? {
        val p = phrase.lowercase().replace(Regex("""\s+"""), " ").trim().removePrefix("the ")
        if (ANCHOR.containsMatchIn(p)) return null
        when (p) {
            "today", "tonight" -> return Dates(today, null)
            "tomorrow" -> return Dates(today.plusDays(1), null)
            "day after tomorrow" -> return Dates(today.plusDays(2), null)
            "weekend", "this weekend", "coming weekend" -> return Dates(onOrAfter(today, DayOfWeek.SATURDAY), null)
            "next weekend" -> return Dates(nextWeek(today, DayOfWeek.SATURDAY), null)
        }
        weekday(p)?.let { return Dates(onOrAfter(today, it), null) }
        IN_N.matchEntire(p)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: SMALL_VALUES.getValue(m.groupValues[1]).toLong()
            return Dates(today.plus(n, if (m.groupValues[2] == "day") ChronoUnit.DAYS else ChronoUnit.WEEKS), null)
        }
        QUALIFIED.matchEntire(p)?.let { m ->
            val wd = weekday(m.groupValues[2]) ?: return null
            return Dates(when (m.groupValues[1]) {
                "next" -> nextWeek(today, wd)
                "coming" -> onOrAfter(today.plusDays(1), wd)
                else -> onOrAfter(today, wd)
            }, null)
        }
        NTH.matchEntire(p)?.let { m ->
            val wd = weekday(m.groupValues[2]) ?: return null
            return Dates(nthOfMonth(today, m.groupValues[1], wd), null)
        }
        if (!hasMonth(p)) {
            DAY_ONLY.matchEntire(p)?.let { m -> return dayOfMonth(m.groupValues[1].toInt(), today)?.let { Dates(it, null) } }
        }
        NUMERIC.matchEntire(p)?.let { m -> return numeric(m, today)?.let { Dates(it, null) } }
        val explicit = DateResolver.resolve(explicitForm(p), today)
        return explicit.start?.let { Dates(it, explicit.end) }
    }

    /** Numeric dates are DD/MM, as in DateResolver. With no year, the year that puts the date nearest to [today]. */
    private fun numeric(m: MatchResult, today: LocalDate): LocalDate? {
        val (d, mo, y) = m.destructured
        fun of(year: Int) = runCatching { LocalDate.of(year, mo.toInt(), d.toInt()) }.getOrNull()
        if (y.isNotEmpty()) return of(if (y.length == 2) 2000 + y.toInt() else y.toInt())
        return (today.year - 1..today.year + 1).mapNotNull(::of).minByOrNull { Math.abs(it.toEpochDay() - today.toEpochDay()) }
    }

    /** A weekday name or a prefix of 3+ letters of one, as the lister lists them. */
    private fun weekday(w: String): DayOfWeek? =
        if (w.length < 3) null else WEEKDAYS.entries.firstOrNull { it.key.startsWith(w) }?.value

    private fun onOrAfter(d: LocalDate, wd: DayOfWeek) = d.with(TemporalAdjusters.nextOrSame(wd))

    private fun nextWeek(d: LocalDate, wd: DayOfWeek) = d.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(wd))

    private fun nthOfMonth(today: LocalDate, which: String, wd: DayOfWeek): LocalDate {
        fun inMonth(m: LocalDate) = if (which == "last") m.with(TemporalAdjusters.lastInMonth(wd)) else
            m.with(TemporalAdjusters.dayOfWeekInMonth(listOf("first", "second", "third", "fourth").indexOf(which) + 1, wd))
        val here = inMonth(today)
        return if (here < today) inMonth(today.plusMonths(1)) else here
    }

    private fun dayOfMonth(day: Int, today: LocalDate): LocalDate? {
        if (day !in 1..31) return null
        for (add in 0L..2L) {
            val m = today.plusMonths(add)
            if (day <= m.lengthOfMonth()) {
                val d = m.withDayOfMonth(day)
                if (d >= today) return d
            }
        }
        return null
    }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val MONTH_WORD = Regex("""\b(${MONTHS.joinToString("|")})[a-z]*\b""")

    private fun hasMonth(p: String) = MONTH_WORD.containsMatchIn(p)

    /** True when [phrase] means a different day depending on the day it is counted from (no month and no numeric date). */
    fun needsAnchor(phrase: String): Boolean {
        val p = phrase.lowercase().replace(Regex("""\s+"""), " ").trim()
        return !hasMonth(p) && NUMERIC.find(p) == null
    }

    /** "twelfth of november" and "novem 8" as forms the date parser knows: "12 nov", "nov 8". */
    private fun explicitForm(p: String): String {
        var s = MONTH_WORD.replace(p) { it.groupValues[1] }
        s = s.replace(" of ", " ")
        val twenties = Regex("""twenty[- ](first|second|third|fourth|fifth|sixth|seventh|eighth|ninth)""")
        s = twenties.replace(s) { (20 + ORDINAL_WORDS.indexOf(it.groupValues[1]) + 1).toString() }
        s = s.replace(Regex("""thirty[- ]first"""), "31").replace("thirtieth", "30")
        ORDINAL_WORDS.forEachIndexed { i, w -> s = s.replace(Regex("""\b$w\b"""), (i + 1).toString()) }
        return s
    }
}
