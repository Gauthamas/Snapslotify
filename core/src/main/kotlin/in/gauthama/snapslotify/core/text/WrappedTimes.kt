package `in`.gauthama.snapslotify.core.text

import kotlin.math.abs

/**
 * A time wrapped over two lines, in every route: a line that ends with a clock time ("... on Sun 22 Nov at 3:00") and a
 * line just below it, starting at about the same left edge, that starts with AM or PM ("PM") are joined: the marker moves
 * up to the time ("at 3:00 PM"). A line left with no letter or digit is removed.
 */
object WrappedTimes {

    private val ENDS_WITH_CLOCK = Regex("""(?<![\d:.])\d{1,2}(?:[:.]\d{2})?\s*$""")
    private val STARTS_WITH_MARK = Regex("""^\s*([ap]\.?\s?m\b\.?)""", RegexOption.IGNORE_CASE)

    fun tidy(r: TextCleaner.Result): TextCleaner.Result {
        val lines = r.kept.toMutableList()
        var changed = false
        for (i in lines.indices) {
            val a = lines[i]
            if (!ENDS_WITH_CLOCK.containsMatchIn(a.text)) continue
            val h = (a.bottom - a.top).coerceAtLeast(1)
            val j = lines.indices.filter { j ->
                val b = lines[j]
                j != i && b.top >= a.top + h / 2 && b.top - a.bottom <= h && abs(b.left - a.left) <= 2 * h
            }.minByOrNull { lines[it].top } ?: continue
            val m = STARTS_WITH_MARK.find(lines[j].text) ?: continue
            lines[i] = a.copy(text = a.text.trimEnd() + " " + m.groupValues[1])
            lines[j] = lines[j].copy(text = lines[j].text.substring(m.range.last + 1).trim())
            changed = true
        }
        if (!changed) return r
        val emptied = lines.filter { l -> l.text.none(Char::isLetterOrDigit) }
        return TextCleaner.Result(
            lines - emptied.toSet(),
            r.removed + emptied.map { TextCleaner.Removed(it, "am/pm joined to the time above", 0.0) },
            r.looksLikeChat,
        )
    }
}
