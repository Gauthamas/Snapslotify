package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.extract.Patterns.AMPM
import `in`.gauthama.snapslotify.core.extract.Patterns.CLOCK_HM
import `in`.gauthama.snapslotify.core.extract.Patterns.DAY
import `in`.gauthama.snapslotify.core.extract.Patterns.HRS
import `in`.gauthama.snapslotify.core.extract.Patterns.ORDINAL
import `in`.gauthama.snapslotify.core.extract.Patterns.RANGE_SEP
import `in`.gauthama.snapslotify.core.extract.Patterns.TIME_ZONE
import `in`.gauthama.snapslotify.core.extract.Patterns.WEEKDAY
import `in`.gauthama.snapslotify.core.extract.Patterns.YEAR4
import `in`.gauthama.snapslotify.core.extract.Patterns.regex
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A date as a candidate: [Dates.findDates] plus a weekday right before it and a day range.
 * For "12 - 15 NOV 2026": startDay 12, day 15. For "30 Jan - 1 Feb": startDay 30, startMonth 1, day 1, month 2.
 * Offsets are into the parsed string.
 */
data class ParsedDate(
    val raw: String, val day: Int, val month: Int, val year: Int?, val startDay: Int?, val weekday: DayOfWeek?,
    val ambiguous: Boolean, val start: Int, val end: Int, val shortNumeric: Boolean = false, val ask: Boolean = false,
    /** The start's month when it differs from [month] (a range written with two months); null: [month]. */
    val startMonth: Int? = null,
    /** A list of days with gaps ("5, 12, 19 March"): every day listed, in order, the last one [day]; empty otherwise. */
    val listDays: List<Int> = emptyList(),
)

object DateParser {

    private val WEEKDAY_BEFORE = regex("""\b($WEEKDAY)\.?,?\s*$""")
    /** A weekday written before a listed day ("Wed 20, Thu 21, Fri 22 Jan"): part of the list, not of the number. */
    private const val WD_OPT = """(?:$WEEKDAY\.?,?\s*)?"""
    /**
     * Days before a date that share its month: a range "12 - 15", "20 to 22", or a list "20, 21, 22", "21 & 22",
     * "6, 7 & 8", each day maybe with its weekday or ordinal. Not after a currency ("Rs 20, 25 Jan"). Up to the line before
     * (the list wrapped). Group 1: the first day; group "sep": the last joiner (a dash or "to" makes a range).
     */
    private val DAY_RANGE_BEFORE = regex(
        """(?<![\d:.])(?<!\b(?:rs|inr|usd)\.?\s?)(?<![₹$€£]\s?)($DAY)$ORDINAL(?:\s*,\s*$WD_OPT$DAY$ORDINAL)*""" +
            """\s*(?<sep>$RANGE_SEP|,)\s*$WD_OPT$""",
    )
    private val DAY_NUMBER = regex("""(?<![\d])(\d{1,2})(?!\d)""")
    /**
     * The word right before a list with gaps: a word that is not a weekday or a date word counts things ("Rooms 3, 4, 20
     * Jan", "Seats 5, 12, 19 March"), so the numbers are not days. Punctuation ("Dates: 5, 12") or nothing is fine.
     */
    private val WORD_BEFORE = regex("""(?<!\p{L})(\p{L}{2,})\s+$""")
    private val LIST_CUE = regex("""^(?:on|from|every|and|dates?|days?|$WEEKDAY)$""")
    /**
     * Days after a month-first date that share its month: a range "Nov 5 - 7", "Jan 20 to 22", or a list "January 20,21,22",
     * "Jan 5, 12 & 19"; then maybe the year ("Nov 5 - 7, 2026"). Group "days": the days after the first; group "y": the year.
     */
    private const val DAY_NOT_TIME = """$DAY$ORDINAL(?![\d:.])(?!\s*(?:$AMPM|$HRS|noon\b))"""
    private val DAY_RANGE_AFTER = regex(
        """^\s*(?<sep>$RANGE_SEP|,)\s*(?<days>$DAY_NOT_TIME(?:\s*(?:,|&|and)\s*$DAY_NOT_TIME)*)""" +
            """(?:,?\s+(?<y>$YEAR4)(?!\d))?""",
    )
    /** A year printed right before a month-first date, on its line: "2027 January 20". */
    private val YEAR_BEFORE = regex("""(?<!\d)(20\d{2})[\s,.]*$""")
    /** Between two whole dates: a range ("30 Jan - 1 Feb", "30 January to 2 February") or a list (", ", " & ", " and "). */
    private val JOIN_RANGE = regex("""^\s*(?:-|–|—|to)\s*$""")
    private val JOIN_LIST = regex("""^\s*(?:,|&|and)\s*$""")

    fun parse(s: String): List<ParsedDate> = joinDates(s, Dates.findDates(s).map { d ->
        var start = d.start
        var end = d.end
        var startDay: Int? = null
        var day = d.day
        var year = d.year
        var lineStart = s.lastIndexOf('\n', start - 1) + 1
        var listDays = emptyList<Int>()
        // dmy range: "12 - 15 NOV 2026", "21 & 22 NOV 2026", "6, 7 & 8 Dec", "Wed 20 - Fri 22 Jan", "20, 21, 22 Jan"
        // (from the first day listed). A list (commas, &, and) is a range only when its days follow one another: "5, 12,
        // 19 March" is not 5 to 19 March, and keeps the date that carries the month.
        val from = if (lineStart > 0) s.lastIndexOf('\n', lineStart - 2) + 1 else 0
        DAY_RANGE_BEFORE.find(s.substring(from, start))?.let {
            val days = DAY_NUMBER.findAll(it.value).map { n -> n.groupValues[1].toInt() }.toList() + d.day
            val sep = it.groups["sep"]!!.value.lowercase()
            val range = sep == "-" || sep == "–" || sep == "—" || sep == "to"
            val consecutive = days.zipWithNext().all { (a, b) -> b == a + 1 }
            if ((range && days.size == 2) || (!range && consecutive)) {
                startDay = days.first()
                start = from + it.range.first
                lineStart = s.lastIndexOf('\n', start - 1) + 1
            } else if (!range && days.zipWithNext().all { (a, b) -> b > a }) {
                // a list with gaps ("5, 12, 19 March", "Fri 5, Fri 12, Fri 19 March"): each day its own date, none between;
                // not after a word that counts things (a room, a seat, a price)
                val at = from + it.range.first
                val ls = s.lastIndexOf('\n', at - 1) + 1
                val word = WORD_BEFORE.find(s.substring(ls, at))?.groupValues?.get(1)
                if (word == null || LIST_CUE.matches(word)) {
                    listDays = days
                    start = at
                    lineStart = ls
                }
            }
        }
        // mdy range or list: "Nov 5 - 7, 2026", "January 20,21,22" (a range when the days follow one another), "Jan 5, 12
        // & 19" (each day its own date); a dash or "to" joins two days only
        if (startDay == null && listDays.isEmpty() && d.year == null && d.raw.first().isLetter()) {
            DAY_RANGE_AFTER.find(s.substring(end))?.let {
                val days = listOf(d.day) + DAY_NUMBER.findAll(it.groups["days"]!!.value).map { n -> n.groupValues[1].toInt() }
                val sep = it.groups["sep"]!!.value.lowercase()
                val range = sep == "-" || sep == "–" || sep == "—" || sep == "to"
                val consecutive = days.zipWithNext().all { (a, b) -> b == a + 1 }
                val ascending = days.zipWithNext().all { (a, b) -> b > a }
                val ok = if (range) days.size == 2 else ascending
                if (ok) {
                    if (range || consecutive) startDay = d.day else listDays = days
                    day = days.last()
                    year = it.groups["y"]?.value?.toInt()
                    end += it.range.last + 1
                }
            }
        }
        // a year printed right before a month-first date: "2027 January 20,21,22"
        if (year == null && d.raw.first().isLetter()) {
            YEAR_BEFORE.find(s.substring(lineStart, start))?.let {
                year = it.groupValues[1].toInt()
                start = lineStart + it.range.first
            }
        }
        val weekdayMatch = WEEKDAY_BEFORE.find(s.substring(lineStart, start))
        if (weekdayMatch != null) start = lineStart + weekdayMatch.range.first
        ParsedDate(s.substring(start, end), day, d.month, year, startDay,
            weekdayMatch?.let { Patterns.weekday(it.groupValues[1]) }, d.ambiguous, start, end, d.shortNumeric, d.ask,
            listDays = listDays)
    })

    /**
     * Two whole dates written as one span become one range: joined by a dash or "to" ("30 Jan - 1 Feb", "Sat 30 Jan - Mon
     * 1 Feb"), the end within two months after the start; or joined by a comma, "&" or "and" when the second is the day
     * after the first ("30, 31 Jan, 1 Feb", "31 December and 1 January"). Both with a month name ("Wed, Jan 20 - Fri, Jan
     * 22" too); a year written on the
     * start must be the end's. The start keeps its weekday.
     */
    private fun joinDates(s: String, ds: List<ParsedDate>): List<ParsedDate> {
        val out = mutableListOf<ParsedDate>()
        for (b in ds) {
            val a = out.lastOrNull()
            val merged = a?.let { join(s, it, b) }
            if (merged != null) out[out.size - 1] = merged else out += b
        }
        return out
    }

    private fun join(s: String, a: ParsedDate, b: ParsedDate): ParsedDate? {
        // "Jan 30 - Feb 2": the end's day range took the start's day ("30 - Feb 2"); one range from the start's month
        if (a.end > b.start && b.start > a.start && b.startDay == a.day && a.startDay == null && a.startMonth == null &&
            a.year == null && a.raw.first().isLetter() && (b.month - a.month + 12) % 12 in 1..2
        ) {
            return ParsedDate(s.substring(a.start, b.end), b.day, b.month, b.year, a.day, a.weekday, false, a.start, b.end,
                startMonth = a.month)
        }
        if (a.end > b.start || a.shortNumeric || b.shortNumeric || b.startDay != null || a.startMonth != null) return null
        if (a.listDays.isNotEmpty() || b.listDays.isNotEmpty()) return null
        if (!a.raw.any(Char::isLetter) || !b.raw.any(Char::isLetter)) return null           // month names, not numbers
        if (a.year != null && a.year != b.year) return null
        val between = s.substring(a.end, b.start)
        val months = (b.month - a.month + 12) % 12                                          // Dec -> Jan is 1
        val firstDay = a.startDay ?: a.day
        val ok = when {
            JOIN_RANGE.matches(between) -> a.startDay == null && (months in 1..2 || (months == 0 && b.day > a.day))
            JOIN_LIST.matches(between) -> months == 1 && b.day == 1 && a.day >= lastDay(a.month)
            else -> false
        }
        if (!ok) return null
        return ParsedDate(s.substring(a.start, b.end), b.day, b.month, b.year, firstDay, a.weekday, false, a.start, b.end,
            startMonth = a.month)
    }

    /** The last day of a month; February: 28 (a 29 counts as at least that). */
    private fun lastDay(month: Int) = when (month) { 2 -> 28; 4, 6, 9, 11 -> 30; else -> 31 }
}

/** A clock time or a range; [end] is set for ranges. Offsets are into the parsed string. */
data class ParsedTime(val raw: String, val start: LocalTime, val end: LocalTime?, val startOffset: Int, val endOffset: Int)

object TimeParser {

    /** One time: "6 pm", "6:30 PM", "6.30 pm", "18:30 hrs" (with a marker), or "18:30" (colon, no marker). */
    private val SINGLE = regex(
        """(?:(?<![\d.,:])|(?<=(?<!\d)20\d{2}))(?<!\b(?:rs|inr|usd)\.?\s?)(?<![₹$€£]\s?)(?<h1>\d{1,2})(?:[:.]?(?<m1>[0-5]\d))?\s*(?:(?<ap1>$AMPM)|$HRS)""" +
            """|(?<![\d.,:])(?<h2>\d{1,2}):(?<m2>\d{2})(?!\d)(?:\s*$HRS)?""",
    )
    private val JOIN = regex("""^\s*(?:-|–|—|to|till|until)\s*$""")

    /** "6p", "6:30a": a one-letter marker joined to the number. A time only in a time's company ([parse]). */
    private val SHORT = regex("""(?<![\d.,:\p{L}])(\d{1,2})(?:[:.](\d{2}))?([ap])(?![\p{L}\d])""")
    private val TIME_WORD_BEFORE = regex("""(?:\b(?:at|from|by|till|until)|@)\s*$""")
    private val BARE_START = regex("""(?<![\d.,:])($CLOCK_HM)\s*(?:-|–|—|to)\s*$""")
    private val ZONE_AFTER = regex("""^\s*\(?$TIME_ZONE\)?(?![A-Za-z])""")

    private class Single(val time: LocalTime, val pm: Boolean?, val start: Int, val end: Int)

    fun parse(s: String): List<ParsedTime> {
        val full = SINGLE.findAll(s).mapNotNull { m ->
            val g = m.groups
            val h = (g["h1"] ?: g["h2"])!!.value.toInt()
            val min = (g["m1"] ?: g["m2"])?.value?.toInt() ?: 0
            val ap = g["ap1"]?.value?.first()?.lowercaseChar()
            toTime(h, min, ap)?.let { Single(it, ap?.let { c -> c == 'p' }, m.range.first, m.range.last + 1) }
        }.toList()
        // "6p", "9a": a time when it is joined to another time ("6p-9pm") or follows at / from / by / @ ("at 6p");
        // never alone (a flat "5a", a grade "6a")
        val short = SHORT.findAll(s).filter { m -> full.none { it.start < m.range.last + 1 && m.range.first < it.end } }.mapNotNull { m ->
            val ap = m.groupValues[3].first()
            toTime(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), ap)?.let { Single(it, ap == 'p', m.range.first, m.range.last + 1) }
        }.toList()
        val all = (full + short).sortedBy { it.start }
        val singles = all.filter { x ->
            x in full || TIME_WORD_BEFORE.containsMatchIn(s.substring(maxOf(0, x.start - 8), x.start)) ||
                all.any { y -> y !== x && JOIN.matches(s.substring(minOf(x.end, y.end).coerceAtMost(maxOf(x.start, y.start)), maxOf(x.start, y.start))) && (y.end <= x.start || x.end <= y.start) }
        }
        val out = mutableListOf<ParsedTime>()
        var i = 0
        while (i < singles.size) {
            val a = singles[i]
            val b = singles.getOrNull(i + 1)
            var start = a.start
            var startTime = a.time
            var endTime: LocalTime? = null
            var end = a.end
            if (b != null && JOIN.matches(s.substring(a.end, b.start))) {          // "10:00-11:00", "9 AM to 6 PM"
                endTime = b.time
                end = b.end
                // a start with no am/pm takes the end's ("5:30-6:30 PM"), unless that puts it after the end ("10-2 pm")
                if (a.pm == null && b.pm == true && a.time.hour in 1..11) {
                    val later = a.time.plusHours(12)
                    if (!later.isAfter(b.time)) startTime = later
                }
                i++
            } else if (a.pm != null) {                                              // "10 - 11 AM": bare start, same marker
                BARE_START.find(s.substring(0, a.start))?.let { bare ->
                    val parts = bare.groupValues[1].split(':', '.')
                    toTime(parts[0].toInt(), parts.getOrNull(1)?.toInt() ?: 0, if (a.pm) 'p' else 'a')?.let {
                        endTime = a.time
                        // the end's am/pm, unless that puts the start after the end ("10 - 2 PM" starts at 10 am)
                        startTime = if (it.isAfter(a.time)) toTime(parts[0].toInt(), parts.getOrNull(1)?.toInt() ?: 0, if (a.pm) 'a' else 'p') ?: it else it
                        start = bare.range.first
                    }
                }
            }
            ZONE_AFTER.find(s.substring(end))?.let { end += it.range.last + 1 }
            out += ParsedTime(s.substring(start, end), startTime, endTime, start, end)
            i++
        }
        return out
    }

    private fun toTime(h: Int, min: Int, ap: Char?): LocalTime? {
        if (min > 59) return null
        return when (ap) {
            null -> if (h <= 23) LocalTime.of(h, min) else null
            else -> if (h in 1..12) LocalTime.of(h % 12 + if (ap == 'p') 12 else 0, min) else null
        }
    }
}
