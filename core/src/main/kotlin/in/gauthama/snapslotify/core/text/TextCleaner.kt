package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.chat.ChatRouter
import `in`.gauthama.snapslotify.core.extract.Patterns
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult

/**
 * Port of the notebook's geometry cleaner G (`geometry_clean`, notebook cell 86), used as
 * `box_text(src, reorder=False, geometry=True)`: lines keep ML Kit order, nothing is reordered.
 *
 * Removed:
 * - a bare clock ("9:41", "6:12 PM") whose box ends inside the top [STATUS_BAND] of the image (status bar);
 * - otherwise a bare clock lower than [SMALL_LINE_RATIO] x the median line height and alone in its block (bubble timestamp);
 * - a line that is exactly "Message" (chat input bar), wherever it is. The bottom band only adds to the rule name,
 *   as in the notebook.
 *
 * The chat decision ([Result.looksLikeChat]) is separate from the removals: see [looksLikeChat].
 *
 * Note: G has no "Message line trigger" for timestamps (that was the older text cleaner, clean_text_v2); the bubble
 * rule here applies to every image, as G does.
 */
object TextCleaner {

    const val STATUS_BAND = 0.04
    const val SMALL_LINE_RATIO = 0.7
    const val BOTTOM_BAND = 0.10

    private val BARE_CLOCK = Patterns.regex("""\d{1,2}:\d{2}(?:\s?[ap]\.?m\.?)?""")   // case-insensitive, Unicode classes
    private val MESSAGE_WORD = Regex("message", RegexOption.IGNORE_CASE)

    class Removed(val line: OcrLine, val rule: String, val heightRatio: Double)

    /** A line taken out as the message's or post's own date and time: it stays the page's date (the anchor). */
    fun isStamp(rule: String) = rule == "sent stamp" || rule == "size stamp" || rule == "email header (sent time)" || rule == SenderStamps.RULE

    class Result(val kept: List<OcrLine>, val removed: List<Removed>, val looksLikeChat: Boolean = false) {
        val text: String get() = kept.joinToString("\n") { it.text }
    }

    /** A chat header's presence line. */
    private val PRESENCE = Regex("""^\W*(?:online|typing\W*|last seen.*)\W*$""", RegexOption.IGNORE_CASE)

    /** The top share of the screen where a chat app shows the contact's name and presence. */
    private const val HEADER_BAND = 0.15

    /**
     * Bubbles on both sides: at least two lines hugging the left and two hugging the right, each with nothing on the other
     * side at its height. Bubbles take turns down the screen; a two-column page (dates left, times right) puts its two
     * sides on the same rows.
     */
    private fun bubbles(lines: List<OcrLine>, width: Int): Boolean {
        val w = width.coerceAtLeast(1)
        val left = lines.filter { it.left < w * 25 / 100 && it.right < w * 85 / 100 }
        val right = lines.filter { it.left > w * 25 / 100 && it.right > w * 75 / 100 }
        fun beside(a: OcrLine, others: List<OcrLine>) = others.any { minOf(a.bottom, it.bottom) > maxOf(a.top, it.top) }
        return left.count { !beside(it, right) } >= 2 && right.count { !beside(it, left) } >= 2
    }

    /**
     * Chat if [ChatRouter] says chat at its threshold, or an input bar ("Message", "iMessage", "Type a message") sits in the
     * bottom band; either way with
     * a chat layout signal: a time stamp under a message, two sender lines, bubbles on both sides ([bubbles]: not columns
     * side by side), or the input bar with a presence line ("online", "last seen", "typing") in the header band. A
     * "Message" line alone never makes a chat. A "Message" line above the bottom band (a poster word) does not count
     * towards the router's message-box points.
     */
    private fun looksLikeChat(lines: List<OcrLine>, width: Int, height: Int): Boolean {
        val d = ChatRouter.classify(OcrResult(width, height, lines, 0))
        if (d.monthGrid) return false   // a month calendar grid is never a chat
        // bands in the text's own frame, not the picture's: a phone shown inside a larger picture has its input bar and
        // header wherever the screen sits
        val top = lines.minOf { it.top }; val bottom = lines.maxOf { it.bottom }; val span = (bottom - top).coerceAtLeast(1)
        val inputBar = lines.any { ChatRouter.MESSAGE_BOX.matches(it.text.trim()) && it.top >= bottom - BOTTOM_BAND * span }
        val header = lines.any { PRESENCE.matches(it.text.trim()) && it.bottom <= top + HEADER_BAND * span }
        val layout = d.stampedLines >= 1 || d.senderLines >= 2 || bubbles(lines, width) || (inputBar && header)
        if (!layout) return false
        if (inputBar) return true
        val stray = lines.count { MESSAGE_WORD.matches(it.text.trim()) }
        val score = if (stray > 0 && d.chromeLines == stray) d.score - ChatRouter.CHROME_POINTS else d.score
        return score >= ChatRouter.CHAT_SCORE
    }

    fun clean(lines: List<OcrLine>, width: Int, height: Int): Result {
        if (lines.isEmpty()) return Result(emptyList(), emptyList())
        val median = median(lines.map { (it.bottom - it.top).toDouble() })
        val perBlock = lines.groupingBy { it.block }.eachCount()
        val kept = mutableListOf<OcrLine>()
        val removed = mutableListOf<Removed>()
        for (l in lines) {
            val s = l.text.trim()
            val ratio = (l.bottom - l.top) / median
            val rules = mutableListOf<String>()
            if (BARE_CLOCK.matches(s) && l.bottom <= STATUS_BAND * height) {
                rules += "status bar"
            } else if (BARE_CLOCK.matches(s) && ratio < SMALL_LINE_RATIO && perBlock[l.block] == 1) {
                rules += "bubble timestamp"
            }
            // a chat header's presence line ("last seen today at 10:55 AM"): its time and "today" are the app's, not a message's
            if (PRESENCE.matches(s) && l.bottom <= HEADER_BAND * height) rules += "presence line"
            if (MESSAGE_WORD.matches(s)) {
                rules += "Message (text rule)"
                if (l.top >= (1 - BOTTOM_BAND) * height) rules += "Message (bottom band)"
            }
            if (rules.isEmpty()) kept += l else removed += Removed(l, rules.joinToString(" + "), ratio)
        }
        return Result(kept, removed, looksLikeChat(lines, width, height))
    }

    /** statistics.median: the middle value, or the mean of the two middle values. */
    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
}
