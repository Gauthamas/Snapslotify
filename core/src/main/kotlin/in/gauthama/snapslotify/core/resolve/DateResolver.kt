package `in`.gauthama.snapslotify.core.resolve

import `in`.gauthama.snapslotify.core.extract.DateParser
import `in`.gauthama.snapslotify.core.verify.Flag
import java.time.DateTimeException
import java.time.LocalDate
import kotlin.math.abs

/**
 * [end] is set for a day range. Null start: no explicit date (relative words are never resolved). [yearInferred]: no year
 * was written, so the year was chosen ([DateResolver.resolve]); flagged or not, the page note names the years read.
 */
data class Resolved(
    val start: LocalDate?, val end: LocalDate?, val flags: List<Flag>, val yearInferred: Boolean = false,
    /** A day/month the text does not settle ("3/4"): the readings to offer, the device's usual order first; [start] is null. */
    val alternatives: List<LocalDate> = emptyList(),
    /** A list of days with gaps ("5, 12, 19 March"): every date listed, in order; [start] is the first. Empty otherwise. */
    val listed: List<LocalDate> = emptyList(),
)

/** What the page says about the year of a date written without one ([DateResolver.resolve]). The default: none. */
data class YearHint(
    /** An event's date (not a bill's): the next one on or after [now]. */
    val ahead: Boolean = false,
    /** When the page was seen: the screenshot's own date, else today (null: today). */
    val now: LocalDate? = null,
    /** When the message was written (an email's sent line, a chat's date line, with their year): the year nearest to it. */
    val written: LocalDate? = null,
    /** The date's own sentence is in the past tense: the latest one on or before the written date, else today. */
    val past: Boolean = false,
)

/**
 * Explicit dates only, numeric dates as DD/MM.
 * - No year written, a month name: the year comes from [YearHint], in this order: past tense, the latest one on or before
 *   the written date (else the screenshot's date, else today); a written date, the year nearest to it; an event, the next
 *   one on or after the screenshot's date (else today);
 *   otherwise (a bill, or no hint) the one of last, this or next year nearest to today. With a hint, a weekday written
 *   with the date that does not fit that year moves it, the same way (back for the past, else ahead), to the nearest year
 *   it fits, at most [WEEKDAY_YEARS] away. That year is taken
 *   as sure, with no flag, only when a weekday is written with the date and fits it, and the date is at most
 *   [NEAREST_YEAR_LIMIT_DAYS] from today; otherwise year_not_stated, its detail saying why (no weekday written, the
 *   weekday does not fit, or too far from today). A missing or unreadable weekday never counts as a fit.
 * - weekday_mismatch: a weekday written with the date that does not fit it.
 * - past_date: the (end) date is before [today].
 */
object DateResolver {

    /** A date with no year written, read as the nearest one, is taken as sure only this close to today (and with its weekday). */
    const val NEAREST_YEAR_LIMIT_DAYS = 120L
    const val WEEKDAY_YEARS = 11

    fun resolve(date: String?, today: LocalDate, hint: YearHint = YearHint()): Resolved {
        val d = date?.let { DateParser.parse(written(it)).firstOrNull() } ?: return Resolved(null, null, emptyList())
        val flags = mutableListOf<Flag>()
        val firstDay = d.startDay ?: d.day
        // the start's month: its own for a range written with two months ("30 Jan - 1 Feb"), else the date's
        val firstMonth = d.startMonth ?: d.month
        // day and month as numbers with no year ("25/3", "Thu 05/11"): the next one ahead, always flagged; with a weekday
        // beside it, only when the weekday fits
        if (d.year == null && d.shortNumeric && d.ask) {
            // two readings ("3/4"): a printed weekday that fits one alone settles it; otherwise ask, both offered
            val readings = listOfNotNull(nextAhead(d.month, d.day, today), nextAhead(d.day, d.month, today))
            val fits = d.weekday?.let { w -> readings.filter { it.dayOfWeek == w } }.orEmpty()
            if (fits.size == 1) return Resolved(fits.single(), null, listOf(Flag("numeric_no_year", "'${d.raw}' read as ${fits.single()} (its weekday)")))
            return Resolved(null, null, listOf(Flag("day_month_unclear", "'${d.raw}' could be ${readings.joinToString(" or ")}")), alternatives = readings)
        }
        if (d.year == null && d.shortNumeric) {
            val date = nextAhead(d.month, d.day, today) ?: return Resolved(null, null, emptyList())
            if (d.weekday != null && date.dayOfWeek != d.weekday) {
                return Resolved(null, null, listOf(Flag("weekday_mismatch", "'${d.raw}': $date is a ${date.dayOfWeek.name.lowercase()}")))
            }
            return Resolved(date, null, listOf(Flag("numeric_no_year", "'${d.raw}' read as ${date}")))
        }
        fun nearest(ref: LocalDate) = (ref.year - 1..ref.year + 1)
            .mapNotNull { y -> of(y, firstMonth, firstDay)?.let { y to it } }
            .minByOrNull { (_, x) -> abs(x.toEpochDay() - ref.toEpochDay()) }?.first
        var movedForWeekday = false
        val year = d.year ?: run {
            val now = hint.now ?: today
            val ref = hint.written ?: now
            val back = hint.past || (hint.written != null && nearest(ref)?.let { y -> of(y, firstMonth, firstDay)!! <= ref } == true)
            val base = when {
                hint.past -> (ref.year downTo ref.year - 1).firstOrNull { y -> of(y, firstMonth, firstDay)?.let { it <= ref } == true }
                hint.written != null -> nearest(ref)
                hint.ahead -> (now.year..now.year + 1).firstOrNull { y -> of(y, firstMonth, firstDay)?.let { it >= now } == true }
                else -> nearest(today)
            }
            val hinted = hint.ahead || hint.past || hint.written != null
            // a list's weekday is its first day's, not the last day's the year is found from: checked below instead
            val wd = d.weekday.takeIf { d.listDays.size < 2 }
            // a weekday that does not fit: the nearest year it fits, the same way
            val fitted = if (base != null && hinted && wd != null && of(base, firstMonth, firstDay)?.dayOfWeek != wd) {
                (1..WEEKDAY_YEARS).map { k -> if (back) base - k else base + k }.firstOrNull { y -> of(y, firstMonth, firstDay)?.dayOfWeek == wd }
                    ?.also { movedForWeekday = true }
            } else null
            fitted ?: base
                // 29 February with no year and no leap year near: the next leap year (flagged below as a year not written)
                ?: (today.year..today.year + 8).firstOrNull { y -> of(y, firstMonth, firstDay)?.let { it >= today } == true }
                ?: today.year
        }
        // A range whose start day is later than its end day ("30-2 Dec") starts in the previous month. A range written
        // with two months has its start's: the year found is the start's, and the end is in the next year when its month
        // comes before the start's ("30 Dec - 1 Jan").
        val crossMonth = d.startMonth == null && d.startDay != null && d.startDay > d.day
        val end = when {
            d.startMonth != null -> of(if (d.year == null && d.month < d.startMonth) year + 1 else year, d.month, d.day)
            d.startDay != null -> of(year, d.month, d.day)
            else -> null
        }
        val start = (if (crossMonth) end?.minusMonths(1)?.withDayOrNull(firstDay)
            else if (d.startMonth != null && d.year != null && d.month < d.startMonth) of(year - 1, firstMonth, firstDay)
            else of(year, firstMonth, firstDay))
            ?: return Resolved(null, null, flags)
        // a list with gaps: the year is the last day's (found above), every day listed in that month; the first is the start
        val listed = if (d.listDays.size >= 2) d.listDays.mapNotNull { of(year, d.month, it) } else emptyList()
        if (listed.isNotEmpty()) {
            val first = listed.first()
            if (d.weekday != null && first.dayOfWeek != d.weekday) {
                flags += Flag("weekday_mismatch", "'${d.raw}': $first is a ${first.dayOfWeek.name.lowercase()}")
            }
            val why = when {
                d.year != null -> null
                movedForWeekday -> "the nearest year its weekday fits"
                d.weekday == null -> "no weekday written"
                first.dayOfWeek != d.weekday -> "the weekday written does not fit $first"
                abs(first.toEpochDay() - today.toEpochDay()) > NEAREST_YEAR_LIMIT_DAYS -> "more than $NEAREST_YEAR_LIMIT_DAYS days from today"
                else -> null
            }
            if (why != null) flags.add(0, Flag("year_not_stated", "'${d.raw}' read as ${first.year}: $why"))
            if (listed.last() < today) flags += Flag("past_date", "${listed.last()} is before $today")
            return Resolved(first, null, flags, yearInferred = d.year == null, listed = listed)
        }
        if (d.weekday != null && start.dayOfWeek != d.weekday) {
            flags += Flag("weekday_mismatch", "'${d.raw}': $start is a ${start.dayOfWeek.name.lowercase()}")
        }
        if (d.year == null) {
            val why = when {
                movedForWeekday -> "the nearest year its weekday fits"
                d.weekday == null -> "no weekday written"
                start.dayOfWeek != d.weekday -> "the weekday written does not fit $start"
                abs(start.toEpochDay() - today.toEpochDay()) > NEAREST_YEAR_LIMIT_DAYS -> "more than $NEAREST_YEAR_LIMIT_DAYS days from today"
                else -> null
            }
            if (why != null) flags.add(0, Flag("year_not_stated", "'${d.raw}' read as ${start.year}: $why"))
        }
        if ((end ?: start) < today) flags += Flag("past_date", "$start is before $today")
        return Resolved(start, end, flags, yearInferred = d.year == null)
    }

    private const val MONTHS = """(?:january|february|march|april|may|june|july|august|september|october|november|december|""" +
        """jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)"""
    private val DAY_OF_MONTH = Regex("""\b(\d{1,2})(st|nd|rd|th)?\s+of\s+($MONTHS)\b""", RegexOption.IGNORE_CASE)
    private val SPACED_NUMERIC = Regex("""^\s*(\d{1,2})\s+(\d{1,2})\s+(\d{4})\s*$""")
    private val MONTH_THEN_DAY = Regex("""\b($MONTHS)(\d{1,2})\b""", RegexOption.IGNORE_CASE)
    private val DAY_THEN_MONTH = Regex("""\b(\d{1,2})($MONTHS)\b""", RegexOption.IGNORE_CASE)

    /**
     * The date as written, in a form the shared date parser reads. Only here, so CandidateFinder (which shares that parser)
     * finds exactly what it found before:
     * - "the 20th of November" -> "the 20th November"
     * - a string that is only three numbers, "13 12 2026" -> "13/12/2026" (still DD/MM, still flagged when both are <= 12);
     *   only the whole string, so a phone number or a longer line is never read as a date
     * - "DEC19" -> "DEC 19", and "19DEC" -> "19 DEC"
     */
    internal fun written(s: String): String {
        var t = DAY_OF_MONTH.replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]} ${m.groupValues[3]}" }
        SPACED_NUMERIC.matchEntire(t)?.let { m -> return "${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}" }
        t = MONTH_THEN_DAY.replace(t) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }
        t = DAY_THEN_MONTH.replace(t) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }
        return t
    }

    /** The next date ahead (today counts) with this month and day; null if it never exists (31 April). */
    private fun nextAhead(month: Int, day: Int, today: LocalDate): LocalDate? =
        (today.year..today.year + 8).firstNotNullOfOrNull { y -> of(y, month, day)?.takeIf { it >= today } }

    private fun LocalDate.withDayOrNull(day: Int) = try {
        withDayOfMonth(day)
    } catch (e: DateTimeException) {
        null
    }

    private fun of(y: Int, m: Int, d: Int) = try {
        LocalDate.of(y, m, d)
    } catch (e: DateTimeException) {
        null
    }
}
