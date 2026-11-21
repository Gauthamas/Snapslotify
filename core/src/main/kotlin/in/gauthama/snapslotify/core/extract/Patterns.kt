package `in`.gauthama.snapslotify.core.extract

import java.time.DayOfWeek

/**
 * Small regex parts the date and time patterns are built from. Same alternations as the notebook (cells 50, 65, 103).
 * Every pattern is compiled case-insensitive with Unicode classes ((?iU)), which matches Python's str regex behaviour
 * for \d, \s, \w and \b.
 */
internal object Patterns {

    const val MONTH = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?" +
        "|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    const val WEEKDAY = "(?:mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)"
    const val DAY = """\d{1,2}"""
    const val ORDINAL = "(?:st|nd|rd|th)?"
    const val YEAR4 = """\d{4}"""
    const val NUM_SEP = "[/.-]"
    const val RANGE_SEP = """(?:-|–|—|&|to|and)"""

    /** Hour, then optional minutes after ':' or '.'. */
    const val CLOCK_HM = """\d{1,2}(?:[:.]\d{2})?"""
    /** "pm", "p.m.", and the ways OCR reads it on posters ("pmn", "pnm", "prn", "prm"; "amn", "anm", "arn", "arm"). */
    const val AMPM = """(?:[ap]\.?\s?m|[ap](?:mn|nm|rn|rm))\b\.?"""
    const val HRS = """hrs?\b"""
    const val TIME_ZONE = "(?:IST|GMT|UTC|PST|PDT|EST|EDT|CST|CDT|MST|MDT|CET|CEST|BST|SGT|JST|AEST|AEDT)"

    fun regex(pattern: String): Regex = compileUnicode(pattern) { Regex(it) }

    /**
     * Case-insensitive with Unicode classes. The JVM needs (?U) for Unicode \d \s \w \b; Android's ICU engine rejects
     * that flag (PatternSyntaxException, a crash on the phone in slice 3) but its classes are Unicode already, so the
     * pattern is then compiled with (?i) only.
     */
    internal fun compileUnicode(pattern: String, compile: (String) -> Regex): Regex = try {
        compile("(?iU)$pattern")
    } catch (e: IllegalArgumentException) {   // java.util.regex.PatternSyntaxException
        compile("(?i)$pattern")
    }

    val MONTH_NUM = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        .withIndex().associate { (i, m) -> m to i + 1 }

    fun monthNumber(name: String): Int? = MONTH_NUM[name.take(3).lowercase()]

    fun weekday(name: String): DayOfWeek? = when (name.take(3).lowercase()) {
        "mon" -> DayOfWeek.MONDAY
        "tue" -> DayOfWeek.TUESDAY
        "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY
        "sat" -> DayOfWeek.SATURDAY
        "sun" -> DayOfWeek.SUNDAY
        else -> null
    }

    /** A line that is only a chat message-date chip, e.g. "Mon, 19 Oct 2026" (notebook _DATE_CHIP_LINE). */
    val DATE_CHIP_LINE = regex("""$WEEKDAY,?\s+\d{1,2}\s+$MONTH\s+\d{4}""")
}
