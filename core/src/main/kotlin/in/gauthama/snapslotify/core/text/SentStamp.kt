package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.extract.Patterns.MONTH
import `in`.gauthama.snapslotify.core.extract.Patterns.WEEKDAY
import `in`.gauthama.snapslotify.core.extract.Patterns.regex

/**
 * Removes the sent time of an email or message ("Thu, 5 Nov 2026, 7:55 AM", "Thu 5 Nov, 11:20 AM"): a line that is only
 * a date and a clock time, in the top 30% of the image, in smaller type than the median line. It is when the message was
 * sent, not the event, so its date and time must not become the event's (nor hide a relative date from the verifier's
 * relative-date flag). Only on a screen (a chat, an email's header lines, a status bar, a post's counts bar), and only above the
 * first body sentence: a small date-and-time line in a message's body or a post's caption, or anywhere on a poster, stays.
 *
 * A post's own date line is removed the same way, anywhere on the page, when it sits next to the post's counts or
 * actions bar: a date and/or a clock in either order, with only "at", separators, a view count, "Public" or "Edited"
 * besides ("9:49 PM · Feb 17, 2027 · 9,652 Views", "Nov 14 at 10:25 PM · Public", "DECEMBER 7, 2021" above "19 likes").
 * A line that ends in its own view count, "Public" or "Edited" needs no counts bar next to it.
 * It is when the post went up, not an event; it stays the anchor for relative day words.
 */
object SentStamp {

    const val TOP_PART = 0.30

    internal const val CLOCK = """\d{1,2}[:.]\d{2}\s*(?:[ap]\.?\s?m\.?)?"""
    private val LINE = regex(
        """^\W*(?:$WEEKDAY,?\s+)?(?:\d{1,2}\s+$MONTH\.?|$MONTH\.?\s+\d{1,2})(?:,?\s+\d{4})?\s*[,·•-]?\s*$CLOCK\W*$""",
    )

    internal const val DATE = """(?:(?:$WEEKDAY,?\s+)?(?:\d{1,2}(?:st|nd|rd|th)?\s+$MONTH\.?|$MONTH\.?\s+\d{1,2}(?:st|nd|rd|th)?)(?:(?:,\s*|\s+)\d{4})?|\d{1,2}[/.]\d{1,2}[/.]\d{2,4})"""
    // ":" is how OCR often reads the "·" between a post's time and "Public" ("Dec 11 at 4:40 PM : Public")
    internal const val SEP = """[\s,·•|\-–:]*"""
    internal const val META = """(?:[\d.,]+\s*[km]?\s+views?|public|edited)"""

    /** A post's own date line: a date and/or a clock, either order, with only "at", separators and view count / Public / Edited. */
    /** A clock alone on its line ("5:45 PM"). */
    private val CLOCK_ONLY = regex("""^\W*$CLOCK\W*$""")

    private val POST_LINE = regex("""^\W*(?:$DATE$SEP(?:at\s+)?(?:$CLOCK)?|$CLOCK$SEP(?:$DATE)?)(?:$SEP$META)*\W*$""")

    /** A post's counts or actions bar: "21 Reposts 7 Quotes 773 Likes", "27 comments 30 shares", "1 Reply Copy link". */
    internal val ENGAGEMENT = regex(
        """\b\d[\d.,]*\s*[km]?\s+(?:likes?|reposts?|retweets?|quotes?|comments?|shares?|views?|boosts?|favou?rites?|reactions?|replies|upvotes?)\b|\breply\b.*\bcopy\s+link\b""",
    )

    /** A view count, "Public" or "Edited" on the line itself marks it as the post's own date line wherever it sits. */
    private val OWN_META = regex("""$META\W*$""")

    /** An email's own header line: its recipients ("to me") or a header field ("From:", "Subject:"). */
    private val EMAIL_FIELD = regex("""^\W*(?:to\s+(?:me|you)\b|(?:from|to|cc|bcc|subject|sent|date)\s*:)""")

    /** What the cleaner took out that shows the page is an app's screen. */
    private val SCREEN = listOf("status bar", "email header", "icon button or bar", "bubble timestamp", "message stamp")

    /** A word of a body sentence; a line with [SENTENCE_WORDS] of them that ends a sentence (or with twice as many) is body text. */
    private val WORD = Regex("""\p{L}{2,}""")
    private const val SENTENCE_WORDS = 4

    /** Lines within this many lines of a post's counts or actions bar are its own date line ([POST_LINE]). */
    private const val NEAR_BAR = 2

    fun tidy(r: TextCleaner.Result, height: Int): TextCleaner.Result {
        if (r.kept.size < 2) return r
        val heights = r.kept.map { it.bottom - it.top }.sorted()
        val median = heights[heights.size / 2]
        val order = r.kept.sortedBy { it.top }
        fun wrappedTime(i: Int): Boolean {
            val l = order[i]; val h = (l.bottom - l.top).coerceAtLeast(1)
            if (i == 0 || !CLOCK_ONLY.matches(l.text.trim())) return false
            val a = order[i - 1]
            val above = a.text.trim().trimEnd { !it.isLetterOrDigit() }
            return l.top - a.bottom <= h && kotlin.math.abs(l.left - a.left) <= h &&
                `in`.gauthama.snapslotify.core.extract.Dates.findDates(above).any { it.end >= above.length }
        }
        val posts = order.indices.filter { i ->
            !wrappedTime(i) && POST_LINE.matches(order[i].text.trim()) && (OWN_META.containsMatchIn(order[i].text.trim()) ||
                (maxOf(0, i - NEAR_BAR)..minOf(order.lastIndex, i + NEAR_BAR)).any { j -> j != i && ENGAGEMENT.containsMatchIn(order[j].text) })
        }.map { order[it] }.toSet()
        val screen = r.looksLikeChat || r.removed.any { x -> SCREEN.any { x.rule.startsWith(it) } } ||
            r.kept.any { ENGAGEMENT.containsMatchIn(it.text) || EMAIL_FIELD.containsMatchIn(it.text) }
        val bodyTop = order.firstOrNull { l ->
            val n = WORD.findAll(l.text).count()
            !LINE.matches(l.text.trim()) && (n >= 2 * SENTENCE_WORDS || (n >= SENTENCE_WORDS && l.text.trim().last() in ".!?"))
        }?.top ?: Int.MAX_VALUE
        val stamps = r.kept.filter {
            (screen && LINE.matches(it.text.trim()) && it.top < TOP_PART * height && it.top < bodyTop && it.bottom - it.top < median) || it in posts
        }
        if (stamps.isEmpty() || stamps.size == r.kept.size) return r
        return TextCleaner.Result(
            r.kept - stamps.toSet(),
            r.removed + stamps.map { TextCleaner.Removed(it, "sent stamp", (it.bottom - it.top).toDouble() / median) },
            r.looksLikeChat,
        )
    }
}
