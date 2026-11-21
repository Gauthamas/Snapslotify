package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.extract.Patterns.WEEKDAY
import `in`.gauthama.snapslotify.core.extract.Patterns.regex
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Relative day words counted from an anchor date (a chat's message date line, an email's sent date, a post's date):
 * today / tonight / tomorrow / day after tomorrow, and "this / on / coming <weekday>", which is the next such day after
 * the anchor ("this Tuesday" sent on Thu 5 Nov 2026 is Tue 10 Nov 2026). "next <weekday>" is not counted: it stays a
 * flagged relative date with no computed date.
 */
object AnchoredDay {

    val WORD = regex("""\b(day\s+after\s+tomorrow|tomorrow|today|tonight)\b|\b(?:this|on|coming)\s+($WEEKDAY)\b""")
    private val OFFSET = mapOf("today" to 0L, "tonight" to 0L, "tomorrow" to 1L, "day after tomorrow" to 2L)
    private const val LEAP_YEAR = 2024   // a year with 29 Feb, for an anchor printed without its year

    /** An anchor line's date and whether its year was printed; null when the line holds no full date. */
    fun anchorOf(printed: String): Pair<LocalDate, Boolean>? {
        val d = Dates.findDates(printed).firstOrNull() ?: return null
        val date = runCatching { LocalDate.of(d.year ?: LEAP_YEAR, d.month, d.day) }.getOrNull() ?: return null
        return date to (d.year != null)
    }

    /** The day [m] (a match of [WORD]) names, counted from [anchor]; null for a weekday when the anchor has no year. */
    fun resolve(m: MatchResult, anchor: LocalDate, anchorHasYear: Boolean): LocalDate? {
        val rel = m.groupValues[1]
        if (rel.isNotEmpty()) return anchor.plusDays(OFFSET.getValue(rel.lowercase().replace(Regex("""\s+"""), " ")))
        if (!anchorHasYear) return null
        val wd = Patterns.weekday(m.groupValues[2]) ?: return null
        return anchor.with(TemporalAdjusters.next(wd))
    }

    /** "20 Oct 2026", or "20 Oct" when the anchor had no year. */
    fun format(day: LocalDate, withYear: Boolean): String {
        val month = day.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        return if (withYear) "${day.dayOfMonth} $month ${day.year}" else "${day.dayOfMonth} $month"
    }
}

/** Title clean-up shared by all routes. */
internal object Titles {

    /** A line that only announces ("This is a reminder", "Just a friendly reminder."): never a title. */
    val GENERIC_LINE = regex(
        """^\W*(?:this\s+is\s+(?:just\s+)?an?\s+|just\s+an?\s+|an?\s+)?(?:friendly\s+|gentle\s+|quick\s+)?(?:reminder|invitation|invite)\b\W*$""",
    )

    /**
     * Leading generic words: Reminder, Invitation, Invite, Schedule, Fwd, Re ("Schedule: Yoga Retreat" is "Yoga Retreat");
     * and an invitation's announcing lead-in over its heading ("Save the Date", "You're invited to", "Join us for the",
     * "To celebrate the union of", "Together with their families", "Welcome to").
     */
    private val GENERIC_PREFIX = regex(
        """^\W*(?:(?:re|fwd?|fw)\s*:\s*|(?:this\s+is\s+(?:just\s+)?|just\s+)?an?\s+(?:friendly\s+|gentle\s+|quick\s+)?(?:reminder|invitation)\s+(?:that|about|for|of|to)\s+(?:the\s+)?|""" +
            """(?:reminder|invitation|invite|schedule)\b\s*(?:[:\-–—|]\s*|(?:to|for)\s+)?|""" +
            """(?:save\s+the\s+date|you\s*(?:'|’)?\s*re\s+invited|you\s+are\s+(?:cordially\s+)?invited|(?:please\s+)?join\s+us|we\s+invite\s+you|""" +
            """(?:to|we)\s+celebrate\s+the\s+(?:union|wedding|marriage)\s+of|together\s+with\s+their\s+families|welcome\s+to)\b""" +
            """\s*(?:[:\-–—|!,.]\s*)*(?:(?:to|for|at)\s+)?(?:in\s+celebrating\s+)?(?:the\s+|our\s+)?)+""",
    )

    /** [title] without its leading generic words; null when nothing with three letters is left. */
    /** A capital I ending a word after a small letter is the OCR's l ("FootbalI"); a name's inner capital ("McIntosh") stays. */
    private val FINAL_I = Regex("""(?<=\p{Ll})I(?![\p{L}\p{N}])""")

    fun plain(title: String?): String? {
        if (title == null) return null
        if (GENERIC_LINE.matches(title.trim())) return null
        val rest = GENERIC_PREFIX.replace(FINAL_I.replace(title.trim(), "l"), "").trim()
        return rest.takeIf { it.count(Char::isLetter) >= 3 }
    }
}
