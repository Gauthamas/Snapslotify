package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.extract.Patterns
import kotlin.math.abs

/**
 * A date printed as a tile, its day number over its month ("12" over "JAN", the month in small type under it): the two
 * are read in OCR order, which may put the month next to another card's day or to the hour of a time ("JAN" then "4:00
 * PM" read as 4 January). A line that starts with a bare day number (1 to 31, not a clock time, no month on the line) and a
 * line just below it, starting under the day number, that starts with a month name not followed by a day of its own, are
 * joined when the day is in clearly larger type than the month ([TILE]): the month moves up beside the day ("12 JAN",
 * "26 JAN Career Fair"). A line left with no letter or digit is removed.
 */
object StackedDates {

    /** The month line starts at most this many day-line heights from the day line's left edge, and as far below it. */
    const val ALIGN = 1.0
    const val GAP = 1.0
    /** A tile: the day number is at least this many times the month's height (a body line's "1st" over "August" is not). */
    const val TILE = 1.5

    private val DAY_LEAD = Regex("""^\s*(\d[0-9oOlI]?|[oOlI]\d)(?:st|nd|rd|th)?(?![\d:.])(?!\s*[ap]\.?\s?m\b)(?=\s|$)""", RegexOption.IGNORE_CASE)
    private val MONTH_ANY = Patterns.regex("""\b${Patterns.MONTH}\b""")
    private val MONTH_LEAD = Patterns.regex("""^\s*(${Patterns.MONTH})\b\.?""")
    /** A day right after the month ("Dec 5"): the month line is a date of its own. A clock time ("NOV 9:30 am") is not a day. */
    private val DAY_AFTER = Regex("""^\s*,?\s*\d{1,2}(?:st|nd|rd|th)?(?![\d:.])(?!\s*[ap]\.?\s?m\b)""", RegexOption.IGNORE_CASE)

    fun tidy(r: TextCleaner.Result): TextCleaner.Result {
        val lines = r.kept.toMutableList()
        val used = mutableSetOf<Int>()
        var changed = false
        for (i in lines.indices) {
            val a = lines[i]
            val d = DAY_LEAD.find(a.text) ?: continue
            // OCR may read a digit of the day as a letter ("2oth"); the day's value is checked with the digits put back
            val dayValue = d.groupValues[1].map { mapOf('o' to '0', 'O' to '0', 'l' to '1', 'I' to '1')[it] ?: it }.joinToString("").toInt()
            if (dayValue !in 1..31 || MONTH_ANY.containsMatchIn(a.text)) continue
            val h = (a.bottom - a.top).coerceAtLeast(1)
            val j = lines.indices.filter { j ->
                val b = lines[j]
                j != i && j !in used && b.top >= a.top + h / 2 && b.top - a.bottom <= GAP * h && abs(b.left - a.left) <= ALIGN * h &&
                    h >= TILE * (b.bottom - b.top)
            }.minByOrNull { lines[it].top } ?: continue
            val m = MONTH_LEAD.find(lines[j].text) ?: continue
            val rest = lines[j].text.substring(m.range.last + 1)
            if (DAY_AFTER.containsMatchIn(rest)) continue
            val dayEnd = d.range.last + 1
            lines[i] = a.copy(text = a.text.substring(0, dayEnd) + " " + m.groupValues[1] + a.text.substring(dayEnd))
            lines[j] = lines[j].copy(text = rest.trim())
            used += j
            changed = true
        }
        if (!changed) return r
        val emptied = lines.filter { l -> l.text.none(Char::isLetterOrDigit) }
        return TextCleaner.Result(
            lines - emptied.toSet(),
            r.removed + emptied.map { TextCleaner.Removed(it, "month joined to the day above", 0.0) },
            r.looksLikeChat,
        )
    }
}
