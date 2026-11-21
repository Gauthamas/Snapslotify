package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.chat.ChatRouter
import `in`.gauthama.snapslotify.core.extract.Patterns.regex
import `in`.gauthama.snapslotify.core.layout.Layout
import `in`.gauthama.snapslotify.core.layout.MonthGrid
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.text.OcrNormalizer
import `in`.gauthama.snapslotify.core.text.TextCleaner
import java.time.LocalDate
import kotlin.math.abs

/** The ways a page can be read. */
enum class PageRoute { GRID, PROGRAMME, BILL, CHAT, SEVERAL, SINGLE }

/** Evidence from where things sit on the page, or from the words alone. Layout counts for more. */
enum class SignalKind { LAYOUT, WORDS }

/** One piece of evidence for a route, with its points. Counts only (no OCR text), so it can be logged. */
data class RouteSignal(val route: PageRoute, val name: String, val kind: SignalKind, val points: Double, val count: Int = 0)

/**
 * The routes' scores, the evidence behind them and what was read. [how]: CLEAR (one route ahead by [RouteScore.MARGIN],
 * or none at [RouteScore.FLOOR]: the plain single-event reading), CLOSE (two routes within the margin, both read, the
 * reading that explains more lines kept: [coverage]), UNSURE (the two close readings explain the page equally: the
 * generic single-event reading, every event flagged).
 */
class RouteChoice(val scores: Map<PageRoute, Double>, val signals: List<RouteSignal>) {
    enum class How { CLEAR, CLOSE, UNSURE }

    var chosen: PageRoute = PageRoute.SINGLE
        private set
    var how: How = How.CLEAR
        private set
    /** The other route read in a close call. */
    var other: PageRoute? = null
        private set
    /** Lines explained by the chosen and the other reading, in a close call. */
    var coverage: Pair<Int, Int>? = null
        internal set

    val ranked: List<Pair<PageRoute, Double>> get() = scores.entries.sortedByDescending { it.value }.map { it.key to it.value }

    internal fun decide(route: PageRoute, how: How, other: PageRoute? = null) {
        chosen = route; this.how = how; this.other = other
    }

    /** "GRID 8.0, SINGLE 2.5, ..." and the chosen route: for logs and harnesses. */
    override fun toString() = "$chosen ($how${other?.let { " over $it" } ?: ""}) " +
        ranked.joinToString { (r, s) -> "$r ${"%.1f".format(s)}" }
}

/**
 * Scores each route from several independent signals and picks one. No single signal decides: one bill word, one
 * "Message" line or one extra date is worth less than [FLOOR] on its own, and a route needs at least one layout signal
 * (layout signals are worth more than words).
 * - Month grid: a weekday label row (6 or 7 of 7 labels, about 80%, full points; 5 fewer) and bare day numbers, look-alike
 *   letters read as digits (O 0, l 1, S 5; [MonthGrid.numeric]), full points from 80% of a month's days; the cells read
 *   cleanly ([MonthGrid.read]). The old minimum (5 labels, 7 numbers) still reaches [FLOOR].
 * - Programme: three or more rows each with a date and a title ([CodeExtractor] programme rows); lines that start with a
 *   time or date; the rows explaining most of the page's event dates; a schedule word.
 * - Bill: a bill word is needed but is worth little; a due phrase tied to a date, a money amount (a currency sign, or
 *   paise whatever the sign was read as) and an amount beside a date are the layout signals; an account / consumer
 *   number and a bill type add a little. Without a due date or an account number a page is not a bill (a ticket or a
 *   poster has a price beside its date).
 * - Chat: the cleaner's chat decision (which itself now needs a time stamp under a message, sender lines or bubbles on
 *   both sides, not a "Message" line alone), plus those signals.
 * - Several dates: two or more dates that each look like an event's (their own time after them, first on their line, or
 *   a title before them on their line); a date after "by", "before", "until" ... (a deadline) does not count.
 * - Single event: [SINGLE_BASE], the generic reading.
 */
object RouteScore {

    /** A route below this is not read (except single, the fallback). */
    const val FLOOR = 4.0

    /** The top route must lead the next one at [FLOOR] or more by this much; else both are read. */
    const val MARGIN = 1.0

    const val SINGLE_BASE = 2.5

    /** A share of labels or day numbers at or above this counts in full. */
    const val FULL_SHARE = 0.8

    /** Day numbers expected in a month grid, for [FULL_SHARE]. */
    const val MONTH_DAYS = 28

    private val CURRENCY = Regex("""(?:₹|\brs\.?|\binr\b|[${'$'}€£])\s*\d[\d,]*(?:\.\d{1,2})?""", RegexOption.IGNORE_CASE)
    /** An amount with paise or cents ("1,829.82", "989.77"), whatever the currency sign was read as; not a time ("7.30"). */
    private val DECIMAL_AMOUNT = Regex("""(?:\d{1,3}(?:,\d{2,3})+|\d{3,})\.\d{2}\b""")

    /** A number charged or debited: "649 will be charged", "debited Rs 649". */
    private val CHARGED = regex("""\b\d[\d,]*(?:\.\d{2})?\s+(?:will\s+be\s+|is\s+|was\s+)?(?:charged|debited|deducted)\b|\b(?:charged|debited|deducted)\s+(?:with\s+)?\d""")

    /** A deadline that is not a payment's: "Last date to register", "reply by". */
    private val NOT_PAYMENT = regex("""\b(?:regist\w*|enrol\w*|apply|application|submi\w*|rsvp|reply|respond|confirm)\b""")

    private val DUE_PHRASE = regex("""\b(?:due(?:\s+date)?|pay\s+(?:before|by)|payable\s+(?:by|on|before)|last\s+date|renews?\s+on|renewal\s+date|expires?\s+on|expiry\s+date)\b""")

    private val ACCOUNT = regex(
        """\b(?:account|a/c|consumer|customer|policy|card|bill|invoice|meter|connection|subscriber|crn)\s*(?:no\b\.?|number|num\b|id\b|#)""",
    )
    private val SCHEDULE = regex("""\b(?:schedule|programme|program|agenda|timetable|itinerary|line-?up)\b""")
    private val STARTS_WITH_TIME = regex("""^\W*(?:\d{1,2}[:.]\d{2}|\d{1,2}\s*[ap]\.?m\b)""")
    private val DEADLINE = regex("""\b(?:by|before|till|until|no\s+later\s+than|deadline|last\s+date)\W*(?:on\W*)?(?:\p{L}+\W*)?$""")

    /** A date right after "by", "before", "until", "deadline" ... on its line: a deadline, not the event's date. */
    fun isDeadline(c: Candidates, d: DateCandidate): Boolean {
        if (c.text.isEmpty()) return false
        val r = c.lineRange(d.lineIndex)
        return DEADLINE.containsMatchIn(c.text.substring(r.first, d.offset.coerceIn(r.first, maxOf(r.first, r.last + 1))))
    }

    fun score(cleaned: TextCleaner.Result, c: Candidates, imageWidth: Int, imageHeight: Int, today: LocalDate?): RouteChoice {
        val lines = cleaned.kept
        val signals = mutableListOf<RouteSignal>()
        fun add(r: PageRoute, name: String, kind: SignalKind, points: Double, count: Int = 0) { signals += RouteSignal(r, name, kind, points, count) }
        val text = c.text.ifEmpty { cleaned.text }
        val chatPage = cleaned.looksLikeChat
        val eligible = CodeExtractor.eligibleDates(cleaned, c, imageWidth, imageHeight, today)
        val scores = mutableMapOf<PageRoute, Double>()

        // month grid
        run {
            val labels = MonthGrid.labelCount(lines)
            val n = MonthGrid.numeric(lines).count { Layout.isDayNumber(it.text) }
            if (labels < MonthGrid.MIN_LABELS || n < Layout.GRID_DAY_NUMBERS) {
                scores[PageRoute.GRID] = 0.0; add(PageRoute.GRID, "labels", SignalKind.LAYOUT, 0.0, labels); return@run
            }
            add(PageRoute.GRID, "labels", SignalKind.LAYOUT, if (labels >= 6) 3.0 else 2.0, labels)
            add(PageRoute.GRID, "day numbers", SignalKind.LAYOUT, 2.0 + minOf(1.0, n.toDouble() / MONTH_DAYS / FULL_SHARE), n)
            if (MonthGrid.read(lines, today) != null) add(PageRoute.GRID, "cells read", SignalKind.LAYOUT, 2.0)
        }

        // chat
        run {
            if (!chatPage) { scores[PageRoute.CHAT] = 0.0; return@run }
            val d = ChatRouter.classify(OcrResult(imageWidth, imageHeight, lines + cleaned.removed.map { it.line }, 0))
            add(PageRoute.CHAT, "chat layout (two chat signals)", SignalKind.LAYOUT, 4.0)
            if (d.stampedLines > 0) add(PageRoute.CHAT, "time stamps", SignalKind.LAYOUT, if (d.stampedLines >= 2) 2.0 else 1.5, d.stampedLines)
            if (d.senderLines >= 2) add(PageRoute.CHAT, "sender lines", SignalKind.LAYOUT, 1.5, d.senderLines)
            if (d.leftLines >= 2 && d.rightLines >= 2) add(PageRoute.CHAT, "bubbles both sides", SignalKind.LAYOUT, 1.0)
            if (d.chromeLines > 0) add(PageRoute.CHAT, "message box words", SignalKind.WORDS, 0.5, d.chromeLines)
        }

        // bill
        run {
            // buttons and bars are not the message but still say what the page is ([Chrome.ROUTE_WORDS])
            val chromeWords = cleaned.removed.filter { it.rule in `in`.gauthama.snapslotify.core.text.Chrome.ROUTE_WORDS }.joinToString("\n") { it.line.text }
            val words = (if (chatPage) CodeExtractor.BILL_IN_CHAT else CodeExtractor.BILL).findAll(text + "\n" + chromeWords).map { it.value.lowercase() }.toSet()
            if (words.isEmpty()) { scores[PageRoute.BILL] = 0.0; return@run }
            add(PageRoute.BILL, "bill words", SignalKind.WORDS, if (words.size >= 2) 1.0 else 0.5, words.size)
            // a due phrase tied to a date (not a registration or reply deadline): the date's label, a due phrase on the
            // line just above a date (a form's label over its value), or a due phrase followed by a date on its line or
            // the line read next. Texts with look-alike digits fixed ([OcrNormalizer]).
            val norm = lines.map { OcrNormalizer.normalizeNumeric(it.text) }
            fun payment(i: Int) = !NOT_PAYMENT.containsMatchIn(norm[i])
            if (c.dates.any { it.label?.kind == LabelKind.DUE && payment(it.lineIndex) } ||
                c.dates.any { d -> d.lineIndex > 0 && DUE_PHRASE.containsMatchIn(norm[d.lineIndex - 1]) && payment(d.lineIndex - 1) } ||
                lines.indices.any { i ->
                    // the line read next: just below, starting near the same left edge (lines are not in reading order)
                    val l = lines[i]
                    val below = lines.indices.filter { j -> val o = lines[j]; o.top > l.top && o.top - l.bottom < 2 * (l.bottom - l.top) && abs(o.left - l.left) < 2 * (l.bottom - l.top) }
                        .sortedBy { lines[it].top }.take(2).map { norm[it] }
                    payment(i) && DUE_PHRASE.findAll(norm[i]).any { m ->
                        val rest = norm[i].substring(m.range.last + 1).trimEnd()
                        val join = if (rest.endsWith("-") || rest.endsWith("/")) "" else " "
                        Dates.findDates(rest).isNotEmpty() || below.any { Dates.findDates(rest + join + it).isNotEmpty() }
                    }
                }
            ) add(PageRoute.BILL, "due date", SignalKind.LAYOUT, 3.0)
            // a bare number ("2026", "18-19") is not money: a currency sign, paise, or charged / debited
            fun money(t: String) = CURRENCY.containsMatchIn(t) || DECIMAL_AMOUNT.containsMatchIn(t) || CHARGED.containsMatchIn(t)
            val amountLines = lines.indices.filter { money(norm[it]) }
            if (amountLines.isNotEmpty()) add(PageRoute.BILL, "money amount", SignalKind.LAYOUT, 2.0, amountLines.size)
            // the amount and a date on one line or neighbouring lines: "Rs 1,248 payable by 20-10-2026"
            if (amountLines.any { a -> c.dates.any { abs(it.lineIndex - a) <= 1 } }) add(PageRoute.BILL, "amount beside a date", SignalKind.LAYOUT, 1.0)
            if (ACCOUNT.containsMatchIn(text)) add(PageRoute.BILL, "account number", SignalKind.WORDS, 1.0)
            if (CodeExtractor.BILL_TYPE.containsMatchIn(text)) add(PageRoute.BILL, "bill type", SignalKind.WORDS, 0.5)
            // a price beside a date is also a ticket or a poster: a bill needs its due date or its account number
            if (signals.none { it.route == PageRoute.BILL && (it.name == "due date" || it.name == "account number") }) {
                scores[PageRoute.BILL] = minOf(FLOOR - 1, signals.filter { it.route == PageRoute.BILL }.sumOf { it.points })
            }
        }

        // dates that look like events' own (for several dates and programme)
        val distinct = CodeExtractor.distinctDatesOf(eligible, text)
        var eventDates = 0
        var timed = 0
        distinct.forEachIndexed { k, d ->
            val next = distinct.getOrNull(k + 1)?.offset ?: text.length
            val ownTime = c.times.any { it.label?.kind !in CodeExtractor.NOT_EVENT_TIME_KINDS && it.offset >= d.end && it.offset < next }
            if (isDeadline(c, d)) return@forEachIndexed
            val r = c.lineRange(d.lineIndex)
            val before = if (c.text.isEmpty()) "" else c.text.substring(r.first, d.offset.coerceIn(r.first, maxOf(r.first, r.last + 1)))
            val first = before.none(Char::isLetterOrDigit)
            val titled = before.count(Char::isLetter) >= 3
            if (ownTime || first || titled) { eventDates++; if (ownTime) timed++ }
        }

        // programme (also on a page that looks like a chat: a time column can pass for message stamps)
        run {
            // past rows count too: the page is a programme whether or not its sessions are over
            val rows = CodeExtractor.programmeRows(lines, c, CodeExtractor.eligibleDates(cleaned, c, imageWidth, imageHeight, today, dropPast = false))
            if (rows < CodeExtractor.MIN_PROGRAMME_EVENTS) { scores[PageRoute.PROGRAMME] = 0.0; add(PageRoute.PROGRAMME, "rows", SignalKind.LAYOUT, 0.0, rows); return@run }
            add(PageRoute.PROGRAMME, "rows", SignalKind.LAYOUT, 4.0 + minOf(2.0, 0.5 * (rows - CodeExtractor.MIN_PROGRAMME_EVENTS)), rows)
            val starts = lines.count { STARTS_WITH_TIME.containsMatchIn(it.text) }
            if (starts >= 3) add(PageRoute.PROGRAMME, "lines start with a time", SignalKind.LAYOUT, 1.0, starts)
            if (rows >= 0.6 * eventDates) add(PageRoute.PROGRAMME, "rows hold the dates", SignalKind.LAYOUT, 1.0, eventDates)
            if (SCHEDULE.containsMatchIn(text)) add(PageRoute.PROGRAMME, "schedule word", SignalKind.WORDS, 0.5)
        }

        // several dates (a chat's several dates are the chat reader's)
        run {
            if (chatPage || eventDates < 2) { scores[PageRoute.SEVERAL] = 0.0; add(PageRoute.SEVERAL, "event dates", SignalKind.LAYOUT, 0.0, eventDates); return@run }
            add(PageRoute.SEVERAL, "event dates", SignalKind.LAYOUT, 4.0 + 0.5 * minOf(3, eventDates - 2), eventDates)
            if (timed == eventDates) add(PageRoute.SEVERAL, "each with its own time", SignalKind.LAYOUT, 1.0, timed)
        }

        add(PageRoute.SINGLE, "generic reading", SignalKind.LAYOUT, SINGLE_BASE)
        for (r in PageRoute.entries) if (r !in scores) scores[r] = signals.filter { it.route == r }.sumOf { it.points }
        // a route with no layout signal is never read on words alone
        for (r in PageRoute.entries) if (signals.none { it.route == r && it.kind == SignalKind.LAYOUT && it.points > 0 }) scores[r] = minOf(scores[r]!!, FLOOR - 1)

        val choice = RouteChoice(scores, signals)
        val ahead = choice.ranked.filter { (r, s) -> r != PageRoute.SINGLE && s >= FLOOR }
        when {
            ahead.isEmpty() -> choice.decide(PageRoute.SINGLE, RouteChoice.How.CLEAR)
            ahead.size == 1 || ahead[0].second - ahead[1].second >= MARGIN -> choice.decide(ahead[0].first, RouteChoice.How.CLEAR)
            else -> choice.decide(ahead[0].first, RouteChoice.How.CLOSE, ahead[1].first)
        }
        return choice
    }
}
