package `in`.gauthama.snapslotify.core.chat

import `in`.gauthama.snapslotify.core.layout.Layout
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.text.OcrNormalizer
import kotlin.math.abs

enum class Route { CHAT, OTHER }

/** The router's answer and every signal behind it. Counts only, so it can be logged without any OCR text. */
data class RouteDecision(
    val route: Route,
    val score: Int,
    val lines: Int,
    /** Small clock times right-aligned directly under a message line. */
    val stampedLines: Int,
    /** Message-box or presence text: "Message", "Type a message", "online", "last seen". */
    val chromeLines: Int,
    /** "Name: text" lines. Field labels such as "Due date:" do not count. */
    val senderLines: Int,
    /** Lines hugging the left side and lines hugging the right side, as bubbles do. */
    val leftLines: Int,
    val rightLines: Int,
    /** Lines of at most [SHORT_WORDS] words, as a percentage. */
    val shortPercent: Int,
    /** Different bill, ticket and poster words found. */
    val documentWords: Int,
    /** A month calendar grid ([Layout.isMonthGrid]): never a chat, whatever the points say. */
    val monthGrid: Boolean = false,
    /** Lines in the longest run of body paragraph ([paragraphRun]). */
    val paragraphLines: Int = 0,
)

/**
 * Classifies an OCR result as a chat screenshot or anything else (poster, bill, ticket). Only the chat class goes to the
 * model. A chat missed here costs only the chips, since the code result stands either way.
 *
 * Points, chat at [CHAT_SCORE] or more:
 * - time stamps under message lines: +2 for one, +3 for two or more
 * - message box or presence text: +2
 * - two or more "Name: text" lines: +2
 * - bubbles on both sides (at least two lines each): +1
 * - at least four lines, most of them short: +1
 * - bill, ticket or poster words: -2 for two or three, -3 for four or more
 * - a body paragraph, [PARAGRAPH_LINES] or more wrapped lines across the text's whole width ([paragraphRun]): -2. An
 *   email, an article or a summary is written in paragraphs; a chat in bubbles, which never span the whole screen
 */
object ChatRouter {

    const val CHAT_SCORE = 3
    const val CHROME_POINTS = 2
    private const val SHORT_WORDS = 7
    const val PARAGRAPH_LINES = 4
    const val PARAGRAPH_WIDTH = 0.85
    private const val PARAGRAPH_WORDS = 6

    private val CLOCK = Regex("""^\W*\d{1,2}[:.]\d{2}(\s*[ap]\.?m\.?)?\W*$""", RegexOption.IGNORE_CASE)
    private const val BOX_PHRASES = "message|text message|type a message|write a message|send a message|imessage|rcs message|sms message"

    /** A message-box placeholder line: "Message", "Type a message", "iMessage", ... */
    val MESSAGE_BOX = Regex("""^\W*(?:$BOX_PHRASES)\W*$""", RegexOption.IGNORE_CASE)
    private val CHROME = Regex("""^\W*(?:$BOX_PHRASES|online|typing\W*|last seen.*)\W*$""", RegexOption.IGNORE_CASE)
    private val SENDER = Regex("""^[~]?\s*(\p{L}[\p{L}' .]{1,20}):\s+\S""")
    private val LABEL_WORDS = setOf(
        "no", "number", "id", "date", "due", "amount", "total", "bill", "account", "consumer", "customer", "time", "when",
        "where", "venue", "address", "phone", "mobile", "email", "contact", "name", "seat", "gate", "pnr", "booking", "ref",
        "reference", "invoice", "policy", "plan", "period", "from", "to", "price", "fee", "rs", "inr", "note", "subject",
    )
    private val DOCUMENT = listOf(
        "invoice", "bill", "amount", "consumer", "due date", "payable", "total", "receipt", "statement", "pnr", "seat",
        "boarding", "booking id", "gstin", "account no", "policy", "premium", "tariff", "venue", "tickets", "register",
        "registration", "rsvp", "admission", "entry", "sponsored", "organised by", "organized by",
    ).map { Regex("""\b$it\b""", RegexOption.IGNORE_CASE) }

    fun classify(ocr: OcrResult): RouteDecision {
        val w = ocr.imageWidth.coerceAtLeast(1)
        val lines = ocr.lines.filter { it.text.isNotBlank() }
        val typical = lines.map { it.bottom - it.top }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        val stamped = lines.count { isStamp(it, lines, w, typical) }
        val chrome = lines.count { CHROME.matches(it.text.trim()) }
        val sender = lines.count { l ->
            SENDER.find(l.text.trim())?.groupValues?.get(1)?.lowercase()?.split(' ', '.')?.none { it in LABEL_WORDS } == true
        }
        val prose = lines.filterNot { CLOCK.matches(it.text.trim()) }
        val left = prose.count { it.left < w * 25 / 100 && it.right < w * 85 / 100 }
        val right = prose.count { it.left > w * 25 / 100 && it.right > w * 75 / 100 }
        val short = if (lines.isEmpty()) 0 else 100 * lines.count { it.text.trim().split(Regex("""\s+""")).size <= SHORT_WORDS } / lines.size
        val text = lines.joinToString("\n") { it.text }
        val document = DOCUMENT.count { it.containsMatchIn(text) }

        var score = 0
        score += when { stamped >= 2 -> 3; stamped == 1 -> 2; else -> 0 }
        if (chrome >= 1) score += CHROME_POINTS
        if (sender >= 2) score += 2
        if (left >= 2 && right >= 2) score += 1
        if (lines.size >= 4 && short >= 60) score += 1
        score -= when { document >= 4 -> 3; document >= 2 -> 2; else -> 0 }
        val paragraph = paragraphRun(lines)
        if (paragraph >= PARAGRAPH_LINES) score -= 2
        val grid = Layout.isMonthGrid(lines)
        val chat = score >= CHAT_SCORE && !grid
        return RouteDecision(if (chat) Route.CHAT else Route.OTHER, score, lines.size, stamped, chrome, sender, left, right, short, document, grid, paragraph)
    }

    /**
     * The longest run of body paragraph: lines one under the next (gap at most a line's height), in the same type (heights
     * within a quarter), left edges aligned (within a line's height), each of at least [PARAGRAPH_WORDS] words and at
     * least [PARAGRAPH_WIDTH] of the text's width (all lines, left to right). The last line of a paragraph may be short.
     */
    fun paragraphRun(lines: List<OcrLine>): Int {
        if (lines.isEmpty()) return 0
        val x0 = lines.minOf { it.left }; val frame = (lines.maxOf { it.right } - x0).coerceAtLeast(1)
        val sorted = lines.sortedBy { it.top }
        fun h(l: OcrLine) = l.bottom - l.top
        fun full(l: OcrLine) = l.right - l.left >= PARAGRAPH_WIDTH * frame && l.text.trim().split(Regex("""\s+""")).size >= PARAGRAPH_WORDS
        fun next(a: OcrLine, b: OcrLine) = b.top - a.bottom in -h(a) / 2..maxOf(h(a), h(b)) && abs(a.left - b.left) <= maxOf(h(a), h(b)) &&
            abs(h(a) - h(b)) <= maxOf(h(a), h(b)) / 4
        var best = 0
        for (i in sorted.indices) {
            if (!full(sorted[i])) continue
            var n = 1; var cur = sorted[i]
            while (true) {
                val b = sorted.drop(sorted.indexOf(cur) + 1).firstOrNull { next(cur, it) } ?: break
                n++
                if (!full(b)) break
                cur = b
            }
            best = maxOf(best, n)
        }
        return best
    }

    /**
     * A clock time (letters read for its digits allowed, "9:4S"), no taller than ordinary text, starting right of the line
     * above and ending level with it or further right. The gap allows for a page photographed a few degrees turned.
     */
    private fun isStamp(l: OcrLine, lines: List<OcrLine>, width: Int, typical: Int): Boolean {
        if (!clock(l.text) || l.bottom - l.top > typical) return false
        val tolerance = width * 3 / 100
        val height = l.bottom - l.top
        // a time with its title right under it, in clearly larger type and in its column ("11AM" / "Opening & Ribbon"): a
        // programme row, not a message's stamp (a stamp ends its message; the next message starts a bubble's gap below)
        if (lines.any { b -> b !== l && !clock(b.text) && b.top >= l.bottom - height / 2 && b.top - l.bottom <= height &&
                kotlin.math.abs(b.left - l.left) <= 1.5 * height && b.bottom - b.top >= 1.2 * height }) return false
        return lines.any { above ->
            val tilt = ((l.right - above.left).coerceAtLeast(0) * TILT_SLOPE).toInt()
            above !== l && !clock(above.text) &&
                l.top >= above.bottom - height / 2 - tilt && l.top - above.bottom <= height * 2 + tilt &&
                l.left > above.left && l.right >= above.right - tolerance
        }
    }

    private fun clock(t: String) = CLOCK.matches(OcrNormalizer.normalizeNumeric(t.trim()))

    /** Up to about 5 degrees of turn: how far a line's far end may sit lower or higher than its near end, per pixel. */
    private const val TILT_SLOPE = 0.09
}
