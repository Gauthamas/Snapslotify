package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.chat.ChatRouter

/**
 * Chat-only tidying after [TextCleaner] (which stays a port of the notebook's cleaner G). Only for results that look like a
 * chat:
 * - a message-box placeholder line in the bottom band ("Type a message", "iMessage", ..., the router's phrases) is removed;
 * - a message time stamp read onto the end of a line ("Sure 19:30") is cut off: a trailing 24-hour clock without am/pm,
 *   after other text that does not end in at / by / from / to / till / until / a dash ("meet at 19:30" keeps its time).
 *   OCR's "o" or "O" for a leading zero counts ("o9:12"), and so does a stamp before one short word on the same row
 *   ("o9:14 lol"); a line that is only a stamp is removed. Message stamps are never event times;
 * - the lines are put in reading order (top to bottom, then left to right): OCR may list a wrapped message's second
 *   line ("pm? 17:33") far from its first ("... tomorrow 8"), which would split the time.
 */
object ChatLines {

    private const val STAMP = """(?:[0-9oO]\d|\d):\d{2}"""
    // the short word after a stamp is never a time marker ("18:00 hrs", "6:30 pm" are times, not stamps)
    private val TRAILING_STAMP = Regex(
        """^(.*\S)\s+$STAMP(?:\s+((?![ap]\.?\s?m\b|hrs?\b|h\b|ist\b|gmt\b|utc\b)[A-Za-z]{1,4}\W?))?$""", RegexOption.IGNORE_CASE,
    )
    private val ONLY_STAMP = Regex("""^\W*$STAMP\W*$""")
    private val AMPM_START = Regex("""^[ap]\.?\s?m\b""", RegexOption.IGNORE_CASE)
    private val KEEPS_TIME = Regex("""(?:\b(?:at|by|from|to|till|until)|[-–])$""", RegexOption.IGNORE_CASE)

    fun tidy(r: TextCleaner.Result, height: Int): TextCleaner.Result {
        if (!r.looksLikeChat) return r
        val removed = r.removed.toMutableList()
        val ordered = r.kept.sortedWith(compareBy({ it.top }, { it.left }))
        val kept = ordered.mapIndexedNotNull { i, l ->
            val s = l.text.trim()
            if (ChatRouter.MESSAGE_BOX.matches(s) && l.top >= (1 - TextCleaner.BOTTOM_BAND) * height) {
                removed += TextCleaner.Removed(l, "message box (bottom band)", 0.0)
                return@mapIndexedNotNull null
            }
            if (ONLY_STAMP.matches(s)) {
                removed += TextCleaner.Removed(l, "message time stamp", 0.0)
                return@mapIndexedNotNull null
            }
            val m = TRAILING_STAMP.matchEntire(s)
            // a wrapped time: "... tonight 8:30" then "pm? 10:15" on the next line is 8:30 pm, not a stamp
            val wraps = m != null && m.groupValues[2].isEmpty() && ordered.getOrNull(i + 1)?.text?.trim()?.let(AMPM_START::containsMatchIn) == true
            if (m != null && !wraps && !KEEPS_TIME.containsMatchIn(m.groupValues[1])) {
                l.copy(text = listOf(m.groupValues[1], m.groupValues[2]).filter { it.isNotEmpty() }.joinToString(" "))
            } else l
        }
        return TextCleaner.Result(kept, removed, looksLikeChat = true)
    }
}
