package `in`.gauthama.snapslotify.core.pipeline

import `in`.gauthama.snapslotify.core.extract.Candidates
import `in`.gauthama.snapslotify.core.extract.ChatBubbles
import `in`.gauthama.snapslotify.core.extract.PageRoute
import `in`.gauthama.snapslotify.core.extract.Patterns.WEEKDAY
import `in`.gauthama.snapslotify.core.extract.Patterns.regex
import `in`.gauthama.snapslotify.core.extract.RouteChoice
import `in`.gauthama.snapslotify.core.text.SentStamp
import `in`.gauthama.snapslotify.core.text.TextCleaner

/**
 * A message's or post's time stamp told by its size: on a page clearly routed as a chat, an email or a social post (never
 * a poster, programme, calendar, bill or ticket, never on a close or unsure route), a time is a stamp only when all hold:
 * - it stands alone on its line (at most a date, a weekday, today / yesterday, "at", separators, Edited, a view count);
 * - no event word on its line or the lines next to it;
 * - its line is clearly smaller than the median body line ([FACTOR] x, the median taken without the lines that could be
 *   stamps, nor a large picture inside a chat);
 * - (a chat: also a message box or sender lines, so a two-column programme is never taken for one);
 * - it sits where stamps sit: a chat bubble's first or last line (a corner) or the page's header or footer band; an
 *   email's header or footer band (never its body); a post's stamp and counts row (never its caption or picture text).
 * Size alone never takes a time out. In a chat a line that holds a date is kept (a date line anchors relative days); in an
 * email or a post the line is taken out as a "size stamp", which stays the page's own date ([TextCleaner.isStamp]).
 */
object SmallStamp {

    const val FACTOR = 0.8
    const val HEADER = 0.12
    const val FOOTER = 0.88
    const val EMAIL_HEADER = 0.30
    const val EMAIL_FOOTER = 0.85
    /** A chat bubble this tall, with this many lines, is a picture (a poster shared into the chat), not a message. */
    const val IMAGE_BLOCK = 0.30
    const val IMAGE_LINES = 4
    const val NEAR_BAR = 2

    enum class Kind { CHAT, EMAIL, POST }

    private val ALONE = regex(
        """^\W*(?:(?:today|yesterday|$WEEKDAY|${SentStamp.DATE})${SentStamp.SEP}(?:at\s+)?)?${SentStamp.CLOCK}""" +
            """(?:${SentStamp.SEP}(?:${SentStamp.DATE}|${SentStamp.META}|today|yesterday))*[\W✓✔]*$""",
    )
    private val DATE_ON_LINE = regex(SentStamp.DATE)

    private val EVENT_WORD = regex(
        """\b(?:meet(?:ing|up)?|call|party|dinner|lunch|breakfast|brunch|appointment|event|session|class|webinar|show|movie|""" +
            """match|game|wedding|birthday|concert|reception|ceremony|starts?|begins?|doors|join|see\s+you|pick\s*up|interview|""" +
            """reminder|reservation|booking|visit|tickets?|venue|rsvp|invite|deadline|due)\b""",
    )

    fun kind(cleaned: TextCleaner.Result, choice: RouteChoice): Kind? {
        if (choice.how != RouteChoice.How.CLEAR) return null
        val r = choice.chosen
        // a programme, calendar or bill reading that scored at all: the type is not sure
        if (listOf(PageRoute.PROGRAMME, PageRoute.GRID, PageRoute.BILL).any { (choice.scores[it] ?: 0.0) > 0.0 }) return null
        return when {
            // a chat only with a chat's own furniture: a message box, or sender lines (a two-column programme can pass for bubbles)
            r == PageRoute.CHAT && cleaned.looksLikeChat ->
                if (choice.signals.any { it.route == PageRoute.CHAT && (it.name == "message box words" || it.name == "sender lines") }) Kind.CHAT else null
            r != PageRoute.SINGLE && r != PageRoute.SEVERAL -> null
            cleaned.looksLikeChat -> null
            cleaned.removed.any { it.rule.startsWith("email header") } -> Kind.EMAIL
            (cleaned.kept.map { it.text } + cleaned.removed.map { it.line.text }).any { SentStamp.ENGAGEMENT.containsMatchIn(it) } -> Kind.POST
            else -> null
        }
    }

    /** The page with its small stamps taken out, or null when there are none. */
    fun tidy(cleaned: TextCleaner.Result, c: Candidates, choice: RouteChoice, imageWidth: Int, imageHeight: Int, factor: Double = FACTOR): TextCleaner.Result? {
        val kind = kind(cleaned, choice) ?: return null
        val lines = cleaned.kept
        if (lines.size < 3) return null
        val bubbles = if (kind == Kind.CHAT) ChatBubbles(lines, c, imageWidth, imageHeight) else null
        val picture = bubbles?.bubbles.orEmpty().filter { b ->
            b.lines.size >= IMAGE_LINES && lines[b.lines.last()].bottom - lines[b.lines.first()].top > IMAGE_BLOCK * imageHeight
        }.flatMap { it.lines }.toSet()
        val timed = c.times.map { it.lineIndex }.toSet()
        val alone = lines.indices.filter { it in timed && it !in picture && ALONE.matches(lines[it].text.trim()) }
        if (alone.isEmpty()) return null
        val body = lines.indices.filter { it !in alone && it !in picture }.map { lines[it].bottom - lines[it].top }.sorted()
        if (body.isEmpty()) return null
        val median = body[body.size / 2].toDouble()
        val h = imageHeight.toDouble()
        val stamps = alone.filter { i ->
            val l = lines[i]
            val small = l.bottom - l.top < factor * median
            val quiet = (i - 1..i + 1).filter { it in lines.indices }.none { EVENT_WORD.containsMatchIn(lines[it].text) }
            val place = when (kind) {
                Kind.CHAT -> !DATE_ON_LINE.containsMatchIn(l.text) &&
                    (bubbles!!.bubbleOf(i)?.let { b -> bubbles.bubbles[b].lines.let { i == it.first() || i == it.last() } }
                        ?: (l.top < HEADER * h || l.bottom > FOOTER * h))
                Kind.EMAIL -> l.top < EMAIL_HEADER * h || l.bottom > EMAIL_FOOTER * h
                Kind.POST -> (i - NEAR_BAR..i + NEAR_BAR).any { j -> j != i && j in lines.indices && SentStamp.ENGAGEMENT.containsMatchIn(lines[j].text) }
            }
            small && quiet && place
        }.map { lines[it] }
        if (stamps.isEmpty()) return null
        return TextCleaner.Result(
            lines - stamps.toSet(),
            cleaned.removed + stamps.map { TextCleaner.Removed(it, "size stamp", (it.bottom - it.top) / median) },
            cleaned.looksLikeChat,
        )
    }
}
