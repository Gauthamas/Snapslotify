package `in`.gauthama.snapslotify.core.chat

/** One date phrase in a chat. [firstLine]..[lastLine] are the lines it spans (OCR can wrap a phrase over two lines). */
data class DatePhrase(val text: String, val kind: String, val firstLine: Int, val lastLine: Int)

/** The candidate list for the chat model's date-line job, and the lines it shows the model. */
data class DatePhraseListing(
    val phrases: List<DatePhrase>, val candidates: List<String>, val selectedLines: List<Int>, val headerLines: List<Int>,
)

/**
 * Candidate date phrases for the chat model's date-line job (benchmarks/chat_cases/PROMPTS.md). New code: no existing
 * rule calls it, and it changes nothing in CandidateFinder or Dates.
 *
 * Lists date phrases (weekdays, day + month, ordinal days, numeric DD/MM, relative words) and statements of today's
 * date (kind "anchor"); never clock times. The model picks one; code does the date arithmetic.
 * Lines are joined with newlines, so a phrase wrapped over two lines is still one phrase; its text has whitespace
 * collapsed. Candidates: each phrase once (ignoring case, first spelling kept), in order of first mention.
 * The chat's header date chip (a line that is only a date with a four-digit year, optionally after a weekday:
 * "Fri, 30 Oct 2026", "30 October 2026") is never listed: it says when the screen was taken, not when the event is,
 * and nothing here depends on it. Such lines are blanked before listing and reported in [DatePhraseListing.headerLines].
 */
object DatePhraseLister {

    private val WD_NAMES = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val MON_NAMES = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december")

    /** Every prefix of 3+ letters of the names ("sat", "satu", "novem"), longest first. */
    private fun prefixes(names: List<String>): String =
        names.flatMap { w -> (3..w.length).map { w.substring(0, it) } }.distinct().sortedByDescending { it.length }
            .joinToString("|", "(?:", ")")

    private val WD = prefixes(WD_NAMES)
    private val MON = prefixes(MON_NAMES)
    private val UNITS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth")
    private val ORDW = (UNITS.map { "twenty[- ]$it" } + listOf("thirty[- ]first", "thirtieth", "twentieth") +
        listOf("tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth",
            "eighteenth", "nineteenth") + UNITS).joinToString("|", "(?:", ")")
    private const val NOCLOCK = """(?!\s*(?:am|pm|a\.m|p\.m|:|\.\d|hrs|o'?clock)\b)"""
    private const val DAYN = """(?:3[01]|[12]\d|0?[1-9])"""
    private val DAYNUM = """$DAYN(?:st|nd|rd|th)?\b$NOCLOCK"""
    private const val YEAR = """(?:,?\s+20\d\d)?"""
    private val DATE =
        """(?:$WD[\s,]+)?(?:(?:the\s+)?$DAYNUM(?:\s+of)?\s+$MON\b$YEAR|$MON\s+$DAYNUM$YEAR)""" +
            """|(?:the\s+)?$ORDW\s+of\s+$MON\b$YEAR""" +
            """|(?:$WD[\s,]+)?(?:the\s+)?$DAYN(?:st|nd|rd|th)\b""" +
            """|(?<![\d/.])$DAYN[/.](?:1[0-2]|0?[1-9])(?:[/.](?:20)?\d\d)?(?![\d/]|\s*(?:am|pm)\b|:)"""
    private val REL =
        """day after tomorrow|tomorrow|today|tonight|yesterday|(?:next|this|coming)\s+(?:$WD\b|week(?:end)?)""" +
            """|in\s+(?:\d+|a|one|two|three|four|five|six|seven)\s+(?:days?|weeks?)""" +
            """|(?:first|second|third|fourth|last)\s+$WD\b|weekdays?|weekend"""
    private val INNER = """(?:$DATE|$WD\b(?:[\s,]+the\s+$DAYN(?:st|nd|rd|th))?)"""
    private val ANCHOR = """(?:today|tomorrow|yesterday)\s+(?:is|was)\s+(?:a\s+|the\s+)?$INNER""" +
        """|it['’]?s\s+already\s+(?:the\s+)?$INNER(?:\s+today)?|it['’]?s\s+(?:the\s+)?$INNER\s+today"""
    private val LISTER = Regex("""\b(?:(?<anchor>$ANCHOR)|(?<date>$DATE)|(?<rel>$REL)|(?<wd>$WD))\b""", RegexOption.IGNORE_CASE)
    private val HEADER = Regex(
        """\s*(?:$WD\.?,?\s+)?(?:$DAYN(?:st|nd|rd|th)?\s+$MON\.?,?\s+20\d\d|$MON\.?\s+$DAYN(?:st|nd|rd|th)?,?\s+20\d\d)\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val THE = Regex("""^the\s+""", RegexOption.IGNORE_CASE)
    private val WS = Regex("""\s+""")

    fun list(lines: List<String>): DatePhraseListing {
        val header = lines.indices.filter { HEADER.matches(lines[it]) }
        val text = lines.mapIndexed { i, l -> if (i in header) " ".repeat(l.length) else l }.joinToString("\n")
        val lineStart = lines.runningFold(0) { acc, l -> acc + l.length + 1 }
        fun lineAt(offset: Int) = lineStart.indexOfLast { it <= offset }.coerceAtMost(lines.size - 1)
        val phrases = LISTER.findAll(text).map { m ->
            val kind = listOf("anchor", "date", "rel", "wd").first { m.groups[it] != null }
            var t = m.value.replace(WS, " ")
            if (kind == "date") t = t.replace(THE, "")
            DatePhrase(t, kind, lineAt(m.range.first), lineAt(m.range.last))
        }.toList()
        val firstSpelling = LinkedHashMap<String, String>()
        for (p in phrases) firstSpelling.putIfAbsent(p.text.lowercase(), p.text)
        val selected = phrases.flatMap { (it.firstLine..it.lastLine).toList() }.distinct().sorted()
        return DatePhraseListing(phrases, firstSpelling.values.toList(), selected, header)
    }
}
