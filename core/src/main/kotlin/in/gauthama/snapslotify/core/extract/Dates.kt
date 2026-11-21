package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.extract.Patterns.DAY
import `in`.gauthama.snapslotify.core.extract.Patterns.MONTH
import `in`.gauthama.snapslotify.core.extract.Patterns.NUM_SEP
import `in`.gauthama.snapslotify.core.extract.Patterns.ORDINAL
import `in`.gauthama.snapslotify.core.extract.Patterns.WEEKDAY
import `in`.gauthama.snapslotify.core.extract.Patterns.YEAR4
import `in`.gauthama.snapslotify.core.extract.Patterns.regex
import java.time.DateTimeException
import java.time.LocalDate

/** One date in a string. Offsets are into that string. For a range, the day is the one that carries the month. */
/**
 * [shortNumeric]: day and month as numbers with no year ("25/3", "Thu 05/11"); the resolver takes the next one ahead.
 * [ask]: both numbers 12 or less and different ("3/4"): either could be the day. [day] and [month] are then the reading
 * in the device's usual order ([DateOrder]); the resolver keeps it only if a printed weekday fits it alone, else asks.
 */
data class FoundDate(
    val day: Int, val month: Int, val year: Int?, val raw: String, val ambiguous: Boolean, val start: Int, val end: Int,
    val shortNumeric: Boolean = false, val ask: Boolean = false,
)

/** The order of day and month the device's region writes ("3/4" is 3 April in most, March 4 in the United States). */
object DateOrder {
    /** Regions that write the month first. */
    private val MONTH_FIRST = setOf("US", "PH", "FM", "MH", "PW", "BZ", "GU", "AS", "MP", "PR", "VI", "UM")

    /** Set by tests; null: the device's own region. */
    @Volatile var override: Boolean? = null

    fun monthFirst(): Boolean = override ?: (java.util.Locale.getDefault().country.uppercase() in MONTH_FIRST)
}

/** Ports of the notebook's v3 date rules (cell 103): find_dates, is_relative_only, due_dates. */
object Dates {

    /**
     * A two-digit year after a month name, joined to it: a dash ("26-Feb-27") or an apostrophe ("26 Feb '27"); read as 20xx.
     * Not after a space alone, so "5 Dec 10 am" stays a date and a time. All-number dates take two-digit years already
     * ("20/03/27", "20.03.27").
     */
    private const val YEAR2_AFTER = """(?:-|\s*['’]\s*)"""

    private val DATE_ANY = regex(
        """(?<iso>(?<!\d)\d{4}-\d{2}-\d{2}(?!\d))""" +                                              // 2026-11-05
            """|(?<num>(?<!\d)$DAY$NUM_SEP$DAY$NUM_SEP(?:\d{4}|\d{2})(?!\d))""" +                       // 05/11/2026, 05.11.26
            """|(?<nsp>(?<!\d)$DAY\s*(?<nsS>[/.|•·-])\s*$DAY\s*\k<nsS>\s*\d{4}(?!\d))""" +                 // 06 . 12 . 2026, 06 | 12 | 2026
            """|(?<short>(?<![\d/.:-])(?<sA>\d{1,2})/(?<sB>\d{1,2})(?![\d/]|[.:]\d))""" +                    // 25/3, Thu 05/11
            """|(?<dmy>(?<!\d)(?<dmyD>$DAY)(?:\s?(?:st|nd|rd|th)(?:\s+(?:day\s+)?of\b)?[\s.•·-]*|(?:\s+of\s+|[\s.•·-]*)(?<![A-Za-z]))(?<dmyM>$MONTH)(?![A-Za-z])\.?(?:[\s,.•·-]*(?<dmyY>$YEAR4)(?!\d)|$YEAR2_AFTER(?<dmyY2>\d{2})(?![\d:.]))?)""" + // 5th Nov 2026, 26-Feb-27, 6THDECEMBER, 6 th of December, 6DEC2026
            """|(?<mdy>\b(?<mdyM>$MONTH)(?![A-Za-z])\.?(?:[\s.•·]+|(?=$DAY(?:\s?(?:st|nd|rd|th))?(?:,?\s*$YEAR4(?!\d)|,?$YEAR2_AFTER\d{2}(?![\d:.]))))(?<mdyD>$DAY)(?:\s?(?:st|nd|rd|th)(?![A-Za-z])|(?!\d)|(?=20\d{2}(?!\d)))(?!\s*(?:[:.]\d|[ap]\.?\s?m\b|hrs?\b))(?:(?:,\s*|\s+|(?<=[A-Za-z])|(?<=\d))(?<mdyY>$YEAR4)(?!\d)|,?$YEAR2_AFTER(?<mdyY2>\d{2})(?![\d:.]))?)""",       // Nov 5, 2026; Nov 5 '27
    )

    val REL_PHRASE = regex(
        """\b(?:day\s+after\s+tomorrow|today|tonight|tomorrow|yesterday|(?:this\s+coming|this|next|coming)\s+$WEEKDAY|$WEEKDAY)\b""",
    )
    private val DUE_WORD = regex("""\b(?:payment\s+due|due|pay\s+by|pay\s+before|last\s+date)\b""")
    // slice 2b: + "regist" ("Last date to register: ..." is a registration deadline, not a due date). Not in the notebook.
    private val NOT_EVENT_DATE = regex("""late\s*(?:fee|payment)|grace|disconnect|bill\s+date|statement\s+date|billing\s+period|regist""")

    /**
     * Every date in [s]. Numeric dates are DD/MM; ambiguous = numeric with day and month both <= 12.
     * Text without a day ("October 2026") is not a date. A date must exist (29 Feb without a year is allowed).
     */
    fun findDates(s: String?): List<FoundDate> {
        if (s == null) return emptyList()
        val fix = MonthRepair.repair(s)
        val out = mutableListOf<FoundDate>()
        for (m in DATE_ANY.findAll(fix.text)) {
            val g = m.groups
            // offsets and raw text are the original's; a repaired month name makes a date only with a year written
            val start = fix.original(m.range.first); var end = fix.original(m.range.last) + 1
            val repaired = fix.touches(m.range)
            var ambiguous = false
            var ask = false
            val (day, month, year) = try {
                when {
                    g["iso"] != null -> LocalDate.parse(g["iso"]!!.value).let { Triple(it.dayOfMonth, it.monthValue, it.year) }
                    g["num"] != null -> {
                        val (d, mo, y) = g["num"]!!.value.split('/', '.', '-').map { it.toInt() }
                        ambiguous = d <= 12 && mo <= 12
                        Triple(d, mo, if (y < 100) y + 2000 else y)
                    }
                    g["nsp"] != null -> {
                        val (d, mo, y) = g["nsp"]!!.value.split(Regex("[^0-9]+")).filter(String::isNotEmpty).map { it.toInt() }
                        ambiguous = d <= 12 && mo <= 12
                        Triple(d, mo, y)
                    }
                    g["short"] != null -> {
                        val sh = shortNumeric(s, start, end, g["sA"]!!.value.toInt(), g["sB"]!!.value.toInt()) ?: continue
                        ask = sh.second
                        sh.first
                    }
                    g["dmy"] != null -> Triple(g["dmyD"]!!.value.toInt(), Patterns.monthNumber(g["dmyM"]!!.value)!!,
                        g["dmyY"]?.value?.toInt() ?: g["dmyY2"]?.value?.toInt()?.plus(2000))
                    else -> {
                        val dd = g["mdyD"]!!.value.toInt()
                        val mo = Patterns.monthNumber(g["mdyM"]!!.value)!!
                        val y2 = g["mdyY2"]
                        // "January 20-22": a dash and a later day of the month is a day range ([DateParser]), not the year '22
                        if (y2 != null && fix.text.substring(g["mdyD"]!!.range.last + 1, y2.range.first).contains('-') &&
                            y2.value.toInt() in dd + 1..31
                        ) {
                            end = fix.original(g["mdyD"]!!.range.last) + 1
                            Triple(dd, mo, null)
                        } else Triple(dd, mo, g["mdyY"]?.value?.toInt() ?: y2?.value?.toInt()?.plus(2000))
                    }
                }
            } catch (e: Exception) {
                continue
            }
            if (!exists(year ?: 2024, month, day)) continue   // 2024: a leap year, so 29 Feb without a year is valid
            // a repaired month name needs the date to say it is one: a year, or a weekday right before it (which must fit)
            if (repaired && year == null && !WEEKDAY_JUST_BEFORE.containsMatchIn(s.substring(s.lastIndexOf('\n', start - 1) + 1, start))) continue
            out += FoundDate(day, month, year, s.substring(start, end), ambiguous, start, end, shortNumeric = g["short"] != null, ask = ask)
        }
        return out
    }

    private val WEEKDAY_JUST_BEFORE = regex("""\b$WEEKDAY\.?,?\s*$""")

    /** A word that introduces a date, right before the numbers (across a line break too): "due 5/5", "on 3/4". */
    private val DATE_CUE_BEFORE = regex("""\b(?:on|due|by|date|dated|till|until|from|before)\W*$""")

    private val WEEKDAY_ONLY = regex("""^$WEEKDAY\.?,?$""")

    /** A time right after the numbers: "3/4 @ 7pm", "3/4 at 7". */
    private val TIME_CUE_AFTER = regex("""^\s*(?:@|at\b)""")

    /**
     * Day and month with no year: (day, month, year) and whether to ask. "24/7" (all the time) is not a date.
     * - One number above 12: only one reading (25/3 is 25 March; 3/25 is March 25).
     * - Both 12 or less: a date only with a cue (a weekday right before, a date word before, or a time after), since
     *   "1/2" or "3/4" alone is as often a fraction or a score. Then, when both are the same ("5/5"), the one reading;
     *   when they differ, ask ([FoundDate.ask]), in the device's usual order ([DateOrder]).
     */
    private fun shortNumeric(s: String, at: Int, end: Int, a: Int, b: Int): Pair<Triple<Int, Int, Int?>, Boolean>? {
        if (a == 24 && b == 7) return null
        return when {
            a > 12 && b in 1..12 -> Triple(a, b, null) to false
            b > 12 && a in 1..12 -> Triple(b, a, null) to false
            a in 1..12 && b in 1..12 -> {
                val before = s.substring(maxOf(0, at - 24), at)
                // the whole string is the date (an event's date read again on its own, maybe with its weekday): it was
                // already chosen with its cue
                val alone = s.substring(end).isBlank() && s.substring(0, at).let { it.isBlank() || WEEKDAY_JUST_BEFORE.matchEntire(it.trim() + " ") != null || WEEKDAY_ONLY.matches(it.trim()) }
                val cue = alone || WEEKDAY_JUST_BEFORE.containsMatchIn(s.substring(s.lastIndexOf('\n', at - 1) + 1, at)) ||
                    DATE_CUE_BEFORE.containsMatchIn(before) || TIME_CUE_AFTER.containsMatchIn(s.substring(end))
                when {
                    !cue -> null
                    a == b -> Triple(a, b, null) to false
                    DateOrder.monthFirst() -> Triple(b, a, null) to true
                    else -> Triple(a, b, null) to true
                }
            }
            else -> null
        }
    }

    private fun exists(year: Int, month: Int, day: Int) = try {
        LocalDate.of(year, month, day); true
    } catch (e: DateTimeException) {
        false
    }

    /** a (from the event) matches b (from the text): same day and month; a year in a must also be in b. */
    fun sameDay(a: FoundDate, b: FoundDate) = a.day == b.day && a.month == b.month && (a.year == null || a.year == b.year)

    /**
     * True when the text's only date evidence is a relative phrase: it has one and no explicit date.
     * A line that is only a message-date chip ("Mon, 19 Oct 2026") is the sent date, not date evidence.
     */
    fun isRelativeOnly(text: String): Boolean {
        val body = text.split("\n").filterNot { Patterns.DATE_CHIP_LINE.matches(it.trim()) }.joinToString("\n")
        return REL_PHRASE.containsMatchIn(body) && findDates(body).isEmpty()
    }

    /**
     * Dates near Due / Payment due / Pay by / Pay before / Last date: after the word on its line, else on the next line.
     * A segment about late fees, grace, disconnection, bill or statement date or billing period never gives one.
     * Distinct by (day, month, year), in first-seen order.
     */
    fun dueDates(text: String): List<FoundDate> {
        val lines = text.split("\n")
        val found = mutableListOf<FoundDate>()
        for ((i, line) in lines.withIndex()) {
            val m = DUE_WORD.find(line) ?: continue
            for (cand in listOf(line.substring(m.range.last + 1), lines.getOrElse(i + 1) { "" })) {
                if (NOT_EVENT_DATE.containsMatchIn(cand)) continue
                val ds = findDates(cand)
                if (ds.isNotEmpty()) {
                    found += ds
                    break
                }
            }
        }
        val distinct = LinkedHashMap<Triple<Int, Int, Int?>, FoundDate>()
        for (d in found) distinct[Triple(d.day, d.month, d.year)] = d   // keeps first position, last value (as a dict)
        return distinct.values.toList()
    }
}
