package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.extract.Patterns.WEEKDAY
import `in`.gauthama.snapslotify.core.extract.Patterns.regex
import `in`.gauthama.snapslotify.core.layout.Layout
import `in`.gauthama.snapslotify.core.layout.MonthGrid
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.text.TextCleaner
import java.time.LocalDate
import kotlin.math.abs

/**
 * One event as an extractor proposes it, before the verifier. Values are copied from the (normalised) text, except a
 * month grid's date, built from the header and the day number ([grid]), and a chat date counted from the message date
 * line above a relative day word ([messageDate], that line as printed).
 */
data class RawEvent(
    val title: String?, val date: String?, val time: String?, val location: String?, val bill: Boolean = false, val grid: Boolean = false,
    val messageDate: String? = null,
    /** A chat with more than one event candidate (by time), of which only this one is given; [moreDates]: on different days. */
    val moreTimes: Boolean = false,
    val moreDates: Boolean = false,
    /** The time was not found next to the date: a fallback (tallest time, nearest time, a time from another bubble). */
    val timeGuessed: Boolean = false,
    /** Why the time is in doubt (the time_guess flag's detail); null: the general reason, not found next to the date. */
    val timeDoubt: String? = null,
    /** A chat's time taken from a bubble other than the date's: the page note says times were read from nearby messages. */
    val timeFromNearby: Boolean = false,
    /** A bill whose due date has no single date with the most evidence ([BillDue]): no date is given; these are offered. */
    val dueCandidates: List<String> = emptyList(),
    val dueUnclear: Boolean = false,
    /** The day is only a weekday word in a chat with other dates: no date, flagged as relative. */
    val relativeDay: Boolean = false,
    /** Where the title came from ([TitleSearch]): the date's own block, a block nearby, or the plain fallback (flagged). */
    val titleSource: String? = null,
    val titleUnsure: Boolean = false,
    /** The page's own heading when it names something other than the title: offered as a title chip. */
    val titleAlternative: String? = null,
    /** The date was counted from the screenshot's own date (its label, "Thu, 5 Nov 2026"): no date line anchored it. */
    val shotDate: String? = null,
    /** [shotDate] is the current date: the screenshot had no date of its own. */
    val anchorIsToday: Boolean = false,
    /** Route flags from a close call ([CodeExtractor.route]): route_unsure, route_date_differs, route_time_differs. */
    val routeFlags: List<String> = emptyList(),
    /** A grid event: the checks its cell failed ([MonthGrid]), or grid_unread when the grid did not read and another route did. */
    val gridChecks: List<String> = emptyList(),
    /** A grid event read from the grid: the month it was read under ("November 2026", or "November" with no year printed). */
    val gridMonth: String? = null,
)

/**
 * Code-only extractor (heuristics, no model).
 * - Bill: any bill word (bill, invoice, statement, premium, renewal, due, instalment, EMI, amount due/payable, ...).
 *   One event. Date: the first candidate labelled Due (same line, same row, or line before), else the text's due date
 *   (notebook rule), else the most prominent eligible date. Title "Pay <provider> <bill type> bill".
 * - Otherwise: eligible dates = not labelled Registration / Booked on / bill-or-statement date, not a chat date chip.
 *   One distinct eligible date (or a range): one event, the most prominent date (tallest line box, then earliest);
 *   title = the tallest title-like line, merged with adjacent lines of similar height, alignment and small gap.
 *   Two or more distinct dates: one event per date, titled by the clause before it.
 * - Time: the candidate nearest the date (line distance, then character distance), not labelled Doors / Booked on.
 * - Chats (TextCleaner.Result.looksLikeChat), one event: title from the bubble that holds the date or time, else the
 *   bubble just before or after it ([ChatBubbles.title]); with no written date, today / tonight / tomorrow / day after
 *   tomorrow is counted from the message date line above it ([ChatBubbles.counted]) and flagged by the verifier.
 * - Location: the rest of a line starting with Venue / Where / Location / Address / At, or the next line.
 * No date and no time candidate: no event.
 */
object CodeExtractor {

    // Title merge (slice 2b): neighbours of similar height, small vertical gap, same left edge or same centre.
    const val MERGE_HEIGHT_RATIO = 0.7
    const val BODY_TYPE = 1.25          // a body sentence's type: at most this x the page's median line height
    const val SENTENCE_WORDS = 6
    private val SUBJECT_LINE = Regex("""(?i)^\W*(?:sub|subject)\s*[:\-]\s*""")
    private val SCREEN_RULES = listOf("status bar", "icon button or bar", "footer band", "second copy of the screen", "advert frame")
    const val MIN_TITLE_WORDS = 3
    private val LEAD_IN = Regex("""(?i)(?:\b(?:on|this|coming|next|at|for|by|from|is|are|was|will\s+be)\s*)+$""")
    const val MIN_PROGRAMME_EVENTS = 3
    const val MERGE_MAX_GAP = 0.6       // x the taller line's height
    const val MERGE_ALIGN = 0.5         // left edges or centres within this x the taller line's height

    private val NOT_EVENT_DATE = setOf(LabelKind.REGISTRATION, LabelKind.BOOKED_ON, LabelKind.NON_EVENT)
    private val NOT_EVENT_TIME = setOf(LabelKind.DOORS, LabelKind.BOOKED_ON)
    internal val BILL = regex(
        """\b(?:bills?|invoice|statement|premium|renewal|renews?|due|instal+ments?|emi|amount\s+(?:due|payable)|total\s+payable|outstanding)\b""",
    )
    /** [BILL] without "bill" and "invoice": in a chat these are often only talk about a bill ("send the invoice please"). */
    internal val BILL_IN_CHAT = regex(
        """\b(?:statement|premium|renewal|renews?|due|instal+ments?|emi|amount\s+(?:due|payable)|total\s+payable|outstanding)\b""",
    )
    internal val BILL_TYPE = regex(
        """\b(electricity|power|water|gas|broadband|internet|mobile|postpaid|landline|phone|credit\s+card|insurance|subscription|dth|rent|maintenance)\b""",
    )
    internal val AMOUNT = regex("""^\W*(?:rs\.?|inr|₹|\$|€|£)?\s*[\d,]+(?:\.\d{1,2})?\W*$""")
    private val LOCATION = regex("""^\s*(venue|where|location|address|at)\b\s*([:\-–]?)\s*(.*)$""")
    /** A next line that is only another label (a table's "Time" column), not a place. */
    private val LABEL_ONLY = regex("""^\W*(?:time|date|day|when|venue|where|location|address|timings?)\W*$""")
    // CLAUSE_SPLIT and GREETING are no longer used here; test/probe/CodeProbe.kt reads them by reflection.
    private val CLAUSE_SPLIT = regex("""[.!?;]+\s*|,\s*|\s+(?=(?:at|on|this|next|coming|tomorrow|today|tonight|from|by)\b)""")
    private val GREETING = regex("""^(?:hi+|hello|hey|dear|greetings|good\s+(?:morning|afternoon|evening))\b""")
    private val SEGMENT_SPLIT = Regex("""[.!?:;,\n]""")
    private val TRAILING_FILLER = regex("""(?:\s+(?:on|at|this|next|coming|from|by|is|are|will|be|the|for|$WEEKDAY))+\s*$""")
    private val STATUS = regex(
        """^\W*(?:(?:your|the)\s+)?(?:(?:booking|appointment|reservation|order|ticket|tickets|registration|payment|meeting|event|visit|slot)\s+)?""" +
            """(?:is\s+|has\s+been\s+|was\s+)?(?:confirmed|scheduled|rescheduled|booked|successful)\W*$""",
    )
    private val BARE_DAY = Regex("""^\W*\d{1,2}(?:st|nd|rd|th)?\W*$""")

    /** Text after a date that names a place or a time, not the event: "at Clear Vision", "from 10 am". */
    private val PLACE_FIRST = regex("""^(?:at|in|on|from|by|venue|location)\b""")

    /** A post's age: "5 days ago", "5d", "yesterday" (1). */
    private val POST_AGE = regex("""^\W*(?:(\d{1,2})\s*(?:d|days?)\s+ago|(\d{1,2})d|yesterday)\W*$""")
    private val RANGE_JOIN = regex("""^\s*(?:-|–|—|to|till|until)\s*$""")

    fun extract(
        cleaned: TextCleaner.Result, candidates: Candidates, imageHeight: Int, imageWidth: Int = 0, today: LocalDate? = null,
        shotDate: LocalDate? = null, anchorIsToday: Boolean = false,
    ): List<RawEvent> = route(cleaned, candidates, imageHeight, imageWidth, today, shotDate, anchorIsToday).events

    /** The events and the route that gave them. */
    class Routed(val events: List<RawEvent>, val choice: RouteChoice)

    /**
     * Picks the route by score ([RouteScore]) and reads the page by it. A route clearly ahead is read. Two close routes
     * are both read and the reading that explains more of the page's lines is kept, each of its events flagged where the
     * other reading gives another date or time; when both explain the page equally, the generic single-event reading is
     * used and every event is flagged (route_unsure).
     */
    fun route(
        cleaned: TextCleaner.Result, candidates: Candidates, imageHeight: Int, imageWidth: Int = 0, today: LocalDate? = null,
        shotDate: LocalDate? = null, anchorIsToday: Boolean = false,
    ): Routed {
        val choice = RouteScore.score(cleaned, candidates, imageWidth, imageHeight, today)
        if (cleaned.kept.isEmpty()) return Routed(emptyList(), choice)
        fun read(r: PageRoute): List<RawEvent> =
            if (r == PageRoute.GRID) grid(cleaned, candidates, imageHeight, imageWidth, today, shotDate, anchorIsToday, choice)
            else extractPage(cleaned, candidates, imageHeight, imageWidth, today, shotDate, anchorIsToday, route = r)
        if (choice.how != RouteChoice.How.CLOSE) return Routed(read(choice.chosen), choice)
        val first = choice.chosen
        val second = choice.other!!
        val a = read(first)
        val b = read(second)
        val ca = coverage(a, cleaned.kept)
        val cb = coverage(b, cleaned.kept)
        return when {
            ca > cb -> { choice.coverage = ca to cb; Routed(disagreements(a, b), choice) }
            cb > ca -> { choice.decide(second, RouteChoice.How.CLOSE, first); choice.coverage = cb to ca; Routed(disagreements(b, a), choice) }
            else -> {
                choice.decide(PageRoute.SINGLE, RouteChoice.How.UNSURE, null); choice.coverage = ca to cb
                Routed(read(PageRoute.SINGLE).map { it.copy(routeFlags = it.routeFlags + "route_unsure") }, choice)
            }
        }
    }

    /**
     * A month calendar grid: its cells, when it reads cleanly; a clean grid with no events in its cells (events listed
     * beside or below it) is read by the next route; a grid that does not read cleanly gives no guess of its own: the page
     * is read by the next route and every event is flagged as from a grid.
     */
    private fun grid(
        cleaned: TextCleaner.Result, candidates: Candidates, imageHeight: Int, imageWidth: Int, today: LocalDate?,
        shotDate: LocalDate?, anchorIsToday: Boolean, choice: RouteChoice,
    ): List<RawEvent> {
        val grid = MonthGrid.read(cleaned.kept, today)
        if (!grid.isNullOrEmpty()) return grid
        val next = choice.ranked.first { (r, s) -> r != PageRoute.GRID && (r == PageRoute.SINGLE || s >= RouteScore.FLOOR) }.first
        val page = extractPage(cleaned, candidates, imageHeight, imageWidth, today, shotDate, anchorIsToday, gridPage = true, route = next)
        return if (grid == null) page.map { it.copy(grid = true, gridChecks = listOf("grid_unread")) } else page
    }

    /** How many of the page's lines a reading explains: lines holding one of its dates, times or titles. */
    private fun coverage(events: List<RawEvent>, lines: List<OcrLine>): Int = lines.count { l ->
        val t = l.text.lowercase().trim()
        t.length >= 2 && events.any { e ->
            listOfNotNull(e.date, e.time).any { v -> v.lowercase().split('\n').any { p -> p.isNotBlank() && p.trim() in t } } ||
                e.title?.lowercase()?.trim()?.let { ti -> ti.length >= 2 && (t in ti || ti in t) } == true
        }
    }

    /** [kept]'s events, flagged where [other] (the close reading not kept) gives the same event another date or time. */
    private fun disagreements(kept: List<RawEvent>, other: List<RawEvent>): List<RawEvent> = kept.map { e ->
        val same = other.firstOrNull { sameTitle(it.title, e.title) }
        val flags = mutableListOf<String>()
        if (same != null) {
            if (!sameDay(e.date, same.date)) flags += "route_date_differs"
            if (!sameTime(e.time, same.time)) flags += "route_time_differs"
        } else if (other.none { sameDay(it.date, e.date) }) flags += "route_date_differs"
        if (flags.isEmpty()) e else e.copy(routeFlags = e.routeFlags + flags)
    }

    private fun norm(s: String?) = s?.lowercase()?.filter(Char::isLetterOrDigit).orEmpty()

    private fun sameTitle(a: String?, b: String?): Boolean {
        val x = norm(a); val y = norm(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || (minOf(x.length, y.length) >= 4 && (x in y || y in x)))
    }

    private fun sameDay(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        val x = Dates.findDates(a); val y = Dates.findDates(b)
        return norm(a) == norm(b) || x.any { p -> y.any { q -> Dates.sameDay(p, q) || Dates.sameDay(q, p) } }
    }

    private fun sameTime(a: String?, b: String?): Boolean = norm(a) == norm(b)

    /** The dates that may be an event's: not labelled Registration / Booked on / bill date, not a chat's date chip, not past ([pastOnPage]). */
    internal fun eligibleDates(cleaned: TextCleaner.Result, c: Candidates, imageWidth: Int, imageHeight: Int, today: LocalDate? = null, dropPast: Boolean = true): List<DateCandidate> {
        val lines = cleaned.kept
        val bubbles = if (cleaned.looksLikeChat && lines.isNotEmpty()) ChatBubbles(lines, c, imageWidth, imageHeight) else null
        val past = pastOnPage(cleaned, bubbles, today)
        return c.dates.filter { it.label?.kind !in NOT_EVENT_DATE }
            // a chat's date line is its separator ([ChatBubbles] tests it); a date written like one elsewhere is the content
            .filterNot { bubbles != null && bubbles.isMessageDateLine(it.lineIndex) }
            .filterNot { dropPast && past(it) }
    }

    /**
     * A date before the page's own date is a past mention ("Thanks for coming on 8 Dec", "Throwback to Sat 24 Dec"), not an
     * event: in a chat, a date before the message date line above it; elsewhere, before the post's or email's own date line
     * (the line [`in`.gauthama.snapslotify.core.text.SentStamp] removed). A page date printed without its year is the one
     * nearest [today]; a date without its year is the one nearest the page date; a range counts by its last day. With no
     * page date (or a page date without its year and no [today]) nothing is dropped.
     */
    internal fun pastOnPage(cleaned: TextCleaner.Result, bubbles: ChatBubbles?, today: LocalDate?): (DateCandidate) -> Boolean {
        val pageDate = pageDate(cleaned, bubbles, today)
        return { d ->
            val anchor = pageDate(d.lineIndex)
            val day = anchor?.let { a ->
                if (d.year != null) runCatching { LocalDate.of(d.year, d.month, d.day) }.getOrNull()
                else (-1..1).mapNotNull { k -> runCatching { LocalDate.of(a.year + k, d.month, d.day) }.getOrNull() }
                    .minByOrNull { abs(java.time.temporal.ChronoUnit.DAYS.between(a, it)) }
            }
            anchor != null && day != null && day.isBefore(anchor)
        }
    }

    /** A date as printed on the page (a chat's date line, a sent or post stamp); with no year, the one nearest [today]. */
    internal fun pageDay(printed: String?, today: LocalDate?): LocalDate? {
        val (d, hasYear) = printed?.let(AnchoredDay::anchorOf) ?: return null
        if (hasYear) return d
        val t = today ?: return null
        return (-1..1).mapNotNull { k -> runCatching { LocalDate.of(t.year + k, d.month, d.dayOfMonth) }.getOrNull() }
            .minByOrNull { abs(java.time.temporal.ChronoUnit.DAYS.between(t, it)) }
    }

    /**
     * The date the page itself shows for a line: in a chat, the message date line above it; elsewhere the email's or post's
     * own date (a stamp line the cleaner took out, small type included: it stays the anchor). Null: the page shows none.
     */
    internal fun pageDate(cleaned: TextCleaner.Result, bubbles: ChatBubbles?, today: LocalDate?, shot: LocalDate? = today): (Int) -> LocalDate? {
        val stamp = cleaned.removed.firstNotNullOfOrNull { r -> r.takeIf { TextCleaner.isStamp(it.rule) }?.let { pageDay(it.line.text.trim(), today) } }
        // a chat's date line, as a date or as a weekday / Yesterday counted back from the screenshot's date ([ShotDate.lineDay])
        fun chatDay(t: String) = pageDay(t, today) ?: shot?.let { ShotDate.lineDay(t, it) }
        return { line -> (if (line in cleaned.kept.indices) bubbles?.messageDateTextAbove(line) else null)?.let(::chatDay) ?: stamp }
    }

    /**
     * A closing date, not an event: "Offer ends 30 Nov", "Applications close on 2 Dec", "Register by 5 Dec", "Last date:
     * 4 Dec", "Valid till 31 Dec". The words come before the date on its own line.
     */
    private val DEADLINE_BEFORE = regex(
        """\b(?:deadline|last\s+date|closing\s+date|(?:apply|register|registration|enrol+|rsvp|reply|book|submit|order|redeem|claim)\s+(?:by|before)|""" +
            """(?:applications?|registrations?|entries|nominations?|submissions?|bookings?|enrol+ments?)\s+(?:close|closes|closing|end|ends|due)|""" +
            """(?:offer|sale|deal|discount|promo|coupon|voucher)s?\s+(?:ends?|valid|expires?)|expires?|expiry|valid\s+(?:till|until|through|up\s*to))\b[^\n]*$""",
    )

    internal fun isDeadline(c: Candidates, d: DateCandidate): Boolean {
        if (c.text.isEmpty()) return false
        val r = c.lineRange(d.lineIndex)
        return DEADLINE_BEFORE.containsMatchIn(c.text.substring(r.first, d.offset.coerceIn(r.first, maxOf(r.first, r.last + 1))))
    }

    internal fun distinctDatesOf(dates: List<DateCandidate>, text: String) = distinctDates(dates, text)

    internal fun programmeRows(lines: List<OcrLine>, c: Candidates, eligible: List<DateCandidate>): Int =
        if (lines.isEmpty()) 0 else programme(lines, c, eligible)?.size ?: 0

    internal val NOT_EVENT_TIME_KINDS: Set<LabelKind> get() = NOT_EVENT_TIME

    private fun extractPage(
        cleaned: TextCleaner.Result, candidates: Candidates, imageHeight: Int, imageWidth: Int, today: LocalDate?,
        shotDate: LocalDate?, anchorIsToday: Boolean, gridPage: Boolean = false, route: PageRoute,
    ): List<RawEvent> {
        val lines = cleaned.kept
        if (lines.isEmpty()) return emptyList()
        val c = candidates
        val text = c.text.ifEmpty { cleaned.text }
        val isChat = cleaned.looksLikeChat
        val isBill = route == PageRoute.BILL
        // an email: its header block (sender, recipients, sent time) was taken out as chrome
        val email = cleaned.removed.any { it.rule.startsWith("email header") }
        // an app's screen: its status bar, bars or header were taken out ([Chrome]); a card is a screen's, not a poster's
        val screen = email || cleaned.removed.any { r -> SCREEN_RULES.any { r.rule.startsWith(it) } }
        // an email's subject: the tallest title-like line above its sender line
        val subjects = cleaned.removed.firstOrNull { it.rule.startsWith("email header (sender)") }?.line?.top?.let { senderTop ->
            lines.indices.filter { lines[it].bottom <= senderTop && titleLike(it, lines, c) && !c.lineHasDateOrTime(it) }
                .maxByOrNull { lines[it].bottom - lines[it].top }?.let { setOf(it) }
        }.orEmpty()
        val location = location(lines.map { it.text })
        val bubbles = if (isChat) ChatBubbles(lines, c, imageWidth, imageHeight) else null
        val chat = bubbles?.takeIf { route == PageRoute.CHAT }
        val past = pastOnPage(cleaned, bubbles, today)
        val allDates = c.dates
            .filter { it.label?.kind !in NOT_EVENT_DATE }
            // on a calendar grid page, a grid day number alone on its line joined to the "Month YYYY" heading next to it
            // ("28" / "February 2027", "DECEMBER" / "6") is not a date
            .filterNot { gridPage && '\n' in it.raw && it.raw.split('\n').any { part -> BARE_DAY.matches(part) } }
            .filterNot { isChat && bubbles!!.isMessageDateLine(it.lineIndex) }
        // a closing date ("Last date for the form is 30 Nov", "Offer ends ...") is never the event's while another date is there
        val withPast = if (isBill) allDates else allDates.filterNot { isDeadline(c, it) }.ifEmpty { allDates }
        val eligible = withPast.filterNot(past)

        // a programme's past rows still count as its rows (the pipeline leaves events in the past out)
        if (route == PageRoute.PROGRAMME) programme(lines, c, withPast)?.let { return it }

        // a chat with several dates for one plan: one event, from the latest date whose bubble also holds a time or a relative day word
        // (past dates still count as dated bubbles here, so a later weekday-only plan is found among them; a past date is never the plan)
        // a chat whose dates are separate plans ("pickup from the airport on 19 Dec", "and the dentist on 22 Dec"): one event each
        val separate = chat != null && !isBill && separatePlans(c, text, distinctDates(eligible, text))
        val planned = if (chat != null && !isBill && !separate) distinctDates(withPast, text).takeIf { it.size >= 2 || it.any(past) }?.let(chat::latestPlanned)
            ?.takeUnless { it.date != null && past(it.date) } else null
        if ((route == PageRoute.SEVERAL || route == PageRoute.CHAT) && planned == null) {
            val distinct = distinctDates(eligible, text)
            if (distinct.size >= 2) {
                // a date-first list: every date starts its line
                val dateFirst = distinct.all { d -> c.text.substring(c.lineRange(d.lineIndex).first, d.offset.coerceAtLeast(c.lineRange(d.lineIndex).first)).none(Char::isLetterOrDigit) }
                return distinct.mapIndexed { k, d ->
                    val prevEnd = if (k == 0) 0 else distinct[k - 1].end
                    val nextStart = distinct.getOrNull(k + 1)?.offset ?: text.length
                    // the first time after this date and before the next one; else the nearest (flagged)
                    val after = c.times.filter { it.label?.kind !in NOT_EVENT_TIME && it.offset >= d.end && it.offset < nextStart }
                        .minByOrNull { it.offset }
                    val time = after ?: pickTime(c, d)
                    val elsewhere = time != null && (after == null || (chat != null && chat.bubbleOf(time.lineIndex) != chat.bubbleOf(d.lineIndex)))
                    val title = (if (dateFirst) titleAfter(c, lines, d, nextStart) else null)
                        ?: clauseBefore(text, afterTimes(c, prevEnd, d.offset), d.offset) ?: largestLine(lines, c)
                    event(title, d, time, location).copy(timeGuessed = elsewhere)
                }
            }
        }

        // a bill: one date, chosen by positive evidence only ([BillDue]); none when that evidence does not settle it
        val due = if (isBill) BillDue.choose(lines, c) else null
        val date = when {
            due != null -> due.date
            planned != null -> planned.date
            // a deadline ("reply by Sat 7 Nov") is not the event's date while another date is there
            else -> eligible.filterNot { RouteScore.isDeadline(c, it) || isDeadline(c, it) }.ifEmpty { eligible }.maxByOrNull { it.box.height }
        }
        if (chat != null && !isBill) {
            // a weekday with no date (noisy_13's "saturday works"): no computed date, flagged as a relative day
            val weekdayBubble = planned?.takeIf { it.date == null }?.bubble
            val (time, nearby, doubt) = chatTime(chat, c, date, weekdayBubble)
            val counted = if (date == null) chat.counted(shotDate) else null
            // a day with no time is an all-day event; only a chat with neither a day nor a time gives none
            if (date == null && time == null && counted == null) return emptyList()
            val titleBubble = chat.bubbleOf(date?.lineIndex) ?: weekdayBubble ?: chat.bubbleOf(time?.lineIndex ?: counted?.lineIndex)
            val anchorLine = date?.lineIndex ?: titleBubble?.let { chat.bubbles[it].lines.first() } ?: time?.lineIndex ?: counted?.lineIndex
            val found = anchorLine?.let { al ->
                TitleSearch.search(lines, c, chat.bubbles.map { TitleSearch.Block(it.lines, it.incoming) }, al, lines.indices.filter(chat::isMessageDateLine).toSet(),
                    anchor = date?.let { it.offset until it.end })
            }
            val title = found?.title ?: chat.title(titleBubble, date?.raw ?: counted?.word ?: time?.raw)
            val cands = chat.eventCandidates()
            val several = (planned?.events ?: 0) >= 2   // two or more bubbles with a planned day: flag the date and time
            val ev = event(title, date, time, location)
                .copy(titleSource = found?.source?.name ?: "FALLBACK", titleUnsure = found == null || found.weak,
                    moreTimes = cands.times >= 2 || several, moreDates = cands.days >= 2 || several, timeGuessed = doubt != null,
                    timeDoubt = doubt, timeFromNearby = nearby, relativeDay = weekdayBubble != null)
            return listOf(when {
                counted == null -> ev
                counted.fromShot -> ev.copy(date = counted.date, shotDate = counted.messageDate, anchorIsToday = anchorIsToday)
                else -> ev.copy(date = counted.date, messageDate = counted.messageDate)
            })
        }
        if (date == null && !isBill) anchored(cleaned, c, today, shotDate)?.let { a ->
            // "this Tuesday" in an email sent on Thu 5 Nov 2026: Tue 10 Nov 2026, flagged as counted from the message date
            val onLine = c.times.firstOrNull { it.lineIndex == a.line && it.label?.kind !in NOT_EVENT_TIME }
            val t = onLine ?: pickTime(c, null)
            val word = AnchoredDay.WORD.find(c.text)!!.range
            val found = TitleSearch.search(lines, c, pageBlocks(lines), a.line, anchor = word, subjects = subjects, screen = screen)
            val title = found?.title ?: (if (email) sentenceTitle(lines, c, a.line, word.first, word.last + 1) else null) ?: largestLine(lines, c)
            val heading = pageHeading(lines, c, screen, subjects)
            val ev = event(title, null, t, location).copy(date = a.day, timeGuessed = t != null && onLine == null,
                titleSource = found?.source?.name ?: "FALLBACK", titleUnsure = found == null || found.weak || headingDiffers(title, heading),
                titleAlternative = heading?.takeIf { headingDiffers(title, it) })
            return listOf(if (a.fromShot) ev.copy(shotDate = a.printed, anchorIsToday = anchorIsToday) else ev.copy(messageDate = a.printed))
        }
        if (due != null && due.date == null) {
            if (due.candidates.isEmpty()) return emptyList()
            return listOf(event(billTitle(lines, c, imageHeight), null, null, location, true)
                .copy(dueUnclear = true, dueCandidates = due.candidates.map { it.raw }))
        }
        val time = pickTime(c, date)
        if (date == null && time == null) return emptyList()
        val anchorLine = date?.lineIndex ?: time?.lineIndex
        val found0 = if (!isBill && anchorLine != null) {
            TitleSearch.search(lines, c, pageBlocks(lines), anchorLine, anchor = date?.let { it.offset until it.end } ?: time?.let { it.offset until it.endOffset },
                subjects = subjects, screen = screen)
        } else null
        // a heading wrapped over lines (a poster's, an appointment's or a ticket's; not an email's body): the whole heading
        val median = lines.map { it.bottom - it.top }.sorted().let { if (it.isEmpty()) 1 else it[it.size / 2].coerceAtLeast(1) }
        val found = found0?.let { f -> if (email) f else TitleSearch.Pick(HeadingWrap.widen(f.title, f.line, lines, c, median), f.source, f.score, f.weak, f.line) }
        val title = found?.title ?: when {
            isBill -> billTitle(lines, c, imageHeight)
            date != null -> (if (email) sentenceTitle(lines, c, date.lineIndex, date.offset, date.end) else null) ?:
                (if (screen) cardTitle(lines, c, date.lineIndex) else null) ?: largestLine(lines, c)
            else -> largestLine(lines, c)
        }
        // with no date, the tallest time on the screen is a guess
        val heading = if (isBill) null else pageHeading(lines, c, screen, subjects)
        // a bill with no provider read: "Pay bill" says nothing of whose bill it is
        val billUnsure = isBill && title?.trim()?.equals("Pay bill", ignoreCase = true) == true
        return listOf(event(title, date, time, location, isBill).copy(timeGuessed = date == null && time != null && !isBill,
            titleSource = if (isBill) null else found?.source?.name ?: "FALLBACK",
            titleUnsure = billUnsure || (!isBill && (found == null || found.weak || headingDiffers(title, heading))),
            titleAlternative = heading?.takeIf { headingDiffers(title, it) }))
    }

    /**
     * Outside chats, a relative day word ([AnchoredDay.WORD]) with no written date is counted from the message's own date:
     * an email's sent line (the line [SentStamp] removed), or a post's age ("5 days ago", with [today]). No anchor, no
     * date. Returns the day as text, the anchor as printed, and the word's line.
     */
    private class Anchored(val day: String, val printed: String, val line: Int, val fromShot: Boolean = false)

    private fun anchored(cleaned: TextCleaner.Result, c: Candidates, today: LocalDate?, shot: LocalDate?): Anchored? {
        val m = AnchoredDay.WORD.find(c.text) ?: return null
        val line = c.lineStarts.indexOfLast { it <= m.range.first }
        val sent = cleaned.removed.firstNotNullOfOrNull { r ->
            r.takeIf { TextCleaner.isStamp(it.rule) }?.line?.text?.trim()?.let { t -> AnchoredDay.anchorOf(t)?.let { t to it } }
        }
        if (sent != null) {
            val (printed, anchor) = sent
            val day = AnchoredDay.resolve(m, anchor.first, anchor.second) ?: return null
            return Anchored(AnchoredDay.format(day, anchor.second), printed, line)
        }
        val age = cleaned.kept.firstNotNullOfOrNull { l -> POST_AGE.matchEntire(l.text.trim())?.let { l.text.trim() to it } }
        val ageBase = shot ?: today
        if (age != null && ageBase != null) {
            val n = age.second.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toLongOrNull() ?: 1L
            val day = AnchoredDay.resolve(m, ageBase.minusDays(n), true) ?: return null
            return Anchored(AnchoredDay.format(day, true), age.first, line)
        }
        // no date line: the screenshot's own date, always flagged
        if (shot == null) return null
        val day = AnchoredDay.resolve(m, shot, true) ?: return null
        return Anchored(AnchoredDay.format(day, true), ShotDate.label(shot), line, fromShot = true)
    }

    /**
     * A chat's event time and whether it is a guess. The date's bubble (else the relative day word's bubble) is read first;
     * else the bubble just after it, then just before it; else the nearest time to the date, or with no date the tallest
     * time on the screen. Any time not in that first bubble is a guess (flagged).
     */
    /**
     * A chat's time: the first time after the date in the date's bubble (else that bubble's first time), else the first
     * time of the bubble after or before it, else [pickTime]. Returns the time, whether it came from a bubble other than
     * the date's ([RawEvent.timeFromNearby]), and why it is in doubt (null: no time_guess flag). It is in doubt only when
     * - another, different time sits near the date too (the date's bubble or the next few): either could be the event's;
     * - it is not in the date's bubble or the [NEAR_BUBBLES] bubbles after it; with no date bubble to measure from, when the
     *   chat holds more than one time;
     * - it is a range, or approximate ("around 7", "7ish", "7 or so").
     * One clear time in the date's bubble or the next few bubbles gets no flag.
     */
    private fun chatTime(chat: ChatBubbles, c: Candidates, date: DateCandidate?, dayBubble: Int? = null): Triple<TimeCandidate?, Boolean, String?> {
        fun ok(t: TimeCandidate) = t.label?.kind !in NOT_EVENT_TIME
        val k = chat.bubbleOf(date?.lineIndex) ?: dayBubble ?: if (date == null) chat.relativeWordBubble() else null
        fun doubt(t: TimeCandidate): String? {
            val b = chat.bubbleOf(t.lineIndex)
            // no message with the date to measure from: one clear time in the whole chat is taken, two or more are in doubt
            if (k == null || b == null) {
                return if (c.times.filter(::ok).map { it.start }.distinct().size > 1) "no message with the date, and more than one time in this chat" else null
            }
            if (b < k) return "'${t.raw}' is in a message before the date's"
            if (b - k > NEAR_BUBBLES) return "'${t.raw}' is more than $NEAR_BUBBLES messages after the date's"
            // other times where the event's time could be: the date's message and the next few
            val near = (k..minOf(k + NEAR_BUBBLES, chat.bubbles.lastIndex)).flatMap { chat.timesIn(it) }.filter(::ok).map { it.start }.distinct()
            if (near.size > 1) return "more than one time near the date"
            if (t.end != null) return "'${t.raw}' is a range"
            if (APPROXIMATE.containsMatchIn(c.text.substring((t.offset - 12).coerceAtLeast(0), (t.endOffset + 8).coerceAtMost(c.text.length)))) {
                return "'${t.raw}' is approximate"
            }
            return null
        }
        fun of(t: TimeCandidate?) = Triple(t, t != null && k != null && chat.bubbleOf(t.lineIndex) != k, t?.let(::doubt))
        if (k != null) {
            val own = chat.timesIn(k).filter(::ok)
            // with a date: the first time after it in its bubble, else the bubble's first time
            (own.firstOrNull { date != null && it.offset >= date.end } ?: own.firstOrNull())?.let { return of(it) }
            for (j in listOf(k + 1, k - 1)) {
                if (j in chat.bubbles.indices) chat.timesIn(j).firstOrNull(::ok)?.let { return of(it) }
            }
        }
        return of(pickTime(c, date)).let { (t, _, d) -> Triple(t, t != null, d) }
    }

    /** A chat's time counts as near its date when it is in the date's bubble or one of this many bubbles after it. */
    const val NEAR_BUBBLES = 3

    /** Words that make a time approximate: "around 7", "about 7 pm", "7ish", "7 or so", "approx 7", "~7". */
    private val APPROXIMATE = Regex("""(?i)\b(?:around|about|approx\w*|roughly|abt|arnd)\.?\s*\d|\d\s*-?ish\b|\bor so\b|~\s*\d""")

    /**
     * For a chat: its bubbles, the bubble with the date the extractor picks (else its time, else the relative day word),
     * and the message date line above it, for the chat model's input. Null when [cleaned] is not a chat.
     */
    fun chatContext(cleaned: TextCleaner.Result, candidates: Candidates, imageWidth: Int, imageHeight: Int): ChatContext? {
        if (!cleaned.looksLikeChat || cleaned.kept.isEmpty()) return null
        val lines = cleaned.kept
        val chat = ChatBubbles(lines, candidates, imageWidth, imageHeight)
        val date = chatDate(lines, candidates, chat)
        val time = pickTime(candidates, date)
        return chat.context(date?.lineIndex ?: chat.counted()?.lineIndex ?: time?.lineIndex)
    }

    /** The date a chat's single event takes: the tallest eligible date that is not a message date line. */
    private fun chatDate(lines: List<OcrLine>, c: Candidates, chat: ChatBubbles): DateCandidate? = c.dates
        .filter { it.label?.kind !in NOT_EVENT_DATE }
        .filterNot { chat.isMessageDateLine(it.lineIndex) }
        .maxByOrNull { it.box.height }

    /**
     * A programme (list of events), read by rows ([Layout.rows]), top to bottom:
     * - a row with an eligible date and a title-like line is one event: that date, the leftmost title-like line without a
     *   date or time (a weekday on its own does not count), the row's first time;
     * - a row's date is also the day heading for the rows below it: a row with a title and a time but no date takes the
     *   nearest date above.
     * - a card (a date tile beside its title, its time on the line under the title): with no other title in the row, the
     *   words on the date's own line after the date are the title ("12 JAN Cycling Rally"); with no time in the row, the
     *   card's first time below it, before the next date, on a line that is not a row of its own (no title of its own).
     *   Nothing is looked for outside the card.
     * Rows on the same date stay separate events. Used only when it finds [MIN_PROGRAMME_EVENTS] or more; else null.
     */
    private fun programme(lines: List<OcrLine>, c: Candidates, eligible: List<DateCandidate>): List<RawEvent>? {
        // two day headings side by side ("SATURDAY 6 FEB" | "SUNDAY 7 FEB"): two programmes, one per column, read apart
        val split = eligible.flatMap { a -> eligible.map { b -> a to b } }.firstOrNull { (a, b) ->
            val la = lines[a.lineIndex]; val lb = lines[b.lineIndex]
            la.right < lb.left && minOf(la.bottom, lb.bottom) - maxOf(la.top, lb.top) >= 0.5 * minOf(la.bottom - la.top, lb.bottom - lb.top)
        }?.let { (a, b) -> (lines[a.lineIndex].right + lines[b.lineIndex].left) / 2.0 }
        val out = if (split == null) programmeIn(lines, c, eligible, null) else listOf(true, false).flatMap { left ->
            val col = lines.indices.filter { ((lines[it].left + lines[it].right) / 2.0 < split) == left }.toSet()
            programmeIn(lines, c, eligible.filter { it.lineIndex in col }, col)
        }
        return out.takeIf { it.size >= MIN_PROGRAMME_EVENTS }
    }

    /** The programme's events, read by rows; [only]: the lines of one column (null: the whole page). */
    private fun programmeIn(lines: List<OcrLine>, c: Candidates, eligible: List<DateCandidate>, only: Set<Int>?): List<RawEvent> {
        fun inCol(i: Int) = only == null || i in only
        var heading: DateCandidate? = null
        // the day heading is a heading only (its own row gave no event): rows under it are its events
        var headingOnly = false
        val out = mutableListOf<RawEvent>()
        val blockOf = Layout.blocks(lines).flatMapIndexed { k, blk -> blk.map { it to k } }.toMap()
        val rows = Layout.rows(lines).map { r -> r.filter(::inCol) }.filter { it.isNotEmpty() }
        // the row's title: in its leftmost column (left edges within a line height), the topmost line (a venue sits under its title)
        fun titleIn(row: List<Int>): Int? {
            val ts = row.filter { titleLike(it, lines, c) && !c.lineHasDateOrTime(it) && !Layout.isWeekdayOnly(lines[it].text) }
            val left = ts.minOfOrNull { lines[it].left } ?: return null
            return ts.filter { lines[it].left - left <= lines[it].bottom - lines[it].top }.minWithOrNull(compareBy({ lines[it].top }, { lines[it].left }))
        }
        fun eventTime(t: TimeCandidate) = t.label?.kind !in NOT_EVENT_TIME
        val dateTops = eligible.map { lines[it.lineIndex].top }.sorted()
        // a time alone on its row, waiting for the title printed just under it ("11AM" / "Opening & Ribbon")
        var pending: TimeCandidate? = null
        for ((k, row) in rows.withIndex()) {
            val date = eligible.filter { it.lineIndex in row }.minWithOrNull(compareBy({ lines[it.lineIndex].top }, { it.offset }))
            val title = titleIn(row)
            val time = c.times.filter { it.lineIndex in row && eventTime(it) }
                .minWithOrNull(compareBy({ lines[it.lineIndex].top }, { lines[it.lineIndex].left }))
            if (date != null) { heading = date; headingOnly = true }
            val before = out.size
            // the card's time: below this row, before the next date, on a line whose row has no title of its own
            fun cardTime(): TimeCandidate? {
                val top = lines[date!!.lineIndex].top
                val next = dateTops.firstOrNull { it > top + (lines[date.lineIndex].bottom - lines[date.lineIndex].top) / 2 } ?: Int.MAX_VALUE
                val bottom = row.maxOf { lines[it].bottom }
                return c.times.filter { t ->
                    val l = lines[t.lineIndex]
                    inCol(t.lineIndex) &&
                    // not a row of its own: no title beside the time on its line's level ("6 pm" | "Carol rehearsal")
                    eventTime(t) && t.lineIndex !in row && l.top >= bottom - (l.bottom - l.top) && l.top < next &&
                        lines.indices.none { j -> val o = lines[j]
                            j != t.lineIndex && inCol(j) && titleLike(j, lines, c) && !c.lineHasDateOrTime(j) && !Layout.isWeekdayOnly(o.text) &&
                                minOf(o.bottom, l.bottom) - maxOf(o.top, l.top) >= 0.5 * minOf(o.bottom - o.top, l.bottom - l.top)
                        }
                }.minByOrNull { lines[it.lineIndex].top }
            }
            // the words on a time's line after the time, when the time leads the line and no date is on it
            fun onTimeLine(t: TimeCandidate): String? {
                val r = c.lineRange(t.lineIndex)
                if (c.text.substring(r.first, t.offset.coerceIn(r.first, r.last + 1)).any(Char::isLetter)) return null
                if (c.dates.any { it.lineIndex == t.lineIndex }) return null
                return c.lineRest(t.lineIndex).trim().trimStart { !it.isLetterOrDigit() }.trim()
                    .takeIf { it.count(Char::isLetter) >= 3 && titleLike(t.lineIndex, lines, c) && !Layout.isWeekdayOnly(it) }
            }
            // the words on the date's line after the date, when the date leads the line (a tile) and no time is on it
            fun onDateLine(): String? {
                val d = date!!
                val r = c.lineRange(d.lineIndex)
                if (c.text.substring(r.first, d.offset.coerceIn(r.first, r.last + 1)).any(Char::isLetter)) return null
                // a time on the line, or a range of days (the page's own span, "10 - 13 DECEMBER 2026"): not a card
                if (c.times.any { it.lineIndex == d.lineIndex } || RANGE.containsMatchIn(d.raw)) return null
                return c.lineRest(d.lineIndex).trim().trimStart { !it.isLetterOrDigit() }.trim()
                    .takeIf { it.count(Char::isLetter) >= 3 && titleLike(d.lineIndex, lines, c) && !Layout.isWeekdayOnly(it) }
            }
            when {
                // a title in the date's own block is a heading over the date ("CITY WEEK / 18 - 25 NOV"), not a row
                date != null && title != null && blockOf[title] != blockOf[date.lineIndex] ->
                    out += event(lines[title].text.trim(), date, time ?: cardTime(), null)
                date != null && title == null && onDateLine() != null -> out += event(onDateLine(), date, time ?: cardTime(), null)
                date == null && heading != null && title != null && time != null -> out += event(lines[title].text.trim(), heading, time, null)
                // the title on the time's own line, after the time ("5:30 pm Nativity play practice")
                date == null && heading != null && headingOnly && title == null && time != null && onTimeLine(time) != null ->
                    out += event(onTimeLine(time), heading, time, null)
                // a title just under a time alone, in its column, under a day heading
                date == null && heading != null && headingOnly && title != null && time == null && pending != null &&
                    lines[title].top - lines[pending!!.lineIndex].bottom <= (lines[pending!!.lineIndex].bottom - lines[pending!!.lineIndex].top) &&
                    kotlin.math.abs(lines[title].left - lines[pending!!.lineIndex].left) <= 2 * (lines[title].bottom - lines[title].top) ->
                    out += event(lines[title].text.trim(), heading, pending, null)
            }
            pending = if (date == null && title == null && time != null && onTimeLine(time) == null) time else null
            // a date row that gave its own event is a card, not a day heading over rows
            if (date != null && out.size > before) headingOnly = false
        }
        return out
    }

    private fun event(title: String?, date: DateCandidate?, time: TimeCandidate?, location: String?, bill: Boolean = false) =
        RawEvent(if (bill) title else Titles.plain(title), date?.raw?.let(::oneLine), time?.raw?.let { trimSentenceDot(oneLine(it)) }, location, bill)

    /** A value that wraps onto the next line in the image is one line in the event. */
    private fun oneLine(s: String) = s.replace(Regex("""\s+"""), " ").trim()

    /** "2:00 PM." at the end of a sentence -> "2:00 PM"; "6 p.m." keeps its dot. */
    private fun trimSentenceDot(s: String) =
        if (s.endsWith(".") && !Regex("""(?i)[ap]\.m\.$""").containsMatchIn(s)) s.dropLast(1) else s

    /** One candidate per distinct (day, month, year) in text order; "<date> to <date>" counts as one. */
    private fun distinctDates(dates: List<DateCandidate>, text: String): List<DateCandidate> {
        val out = mutableListOf<DateCandidate>()
        for (d in dates.sortedBy { it.offset }) {
            val prev = out.lastOrNull()
            if (prev != null && d.offset >= prev.end && RANGE_JOIN.matches(text.substring(prev.end, d.offset))) continue
            if (out.none { it.day == d.day && it.month == d.month && (it.year == null || d.year == null || it.year == d.year) }) out += d
        }
        return out
    }

    /**
     * A list that gives the date first ("Tue 9 Feb - Science Exhibition, 10 am", or "DEC 4" with the title on the next
     * line): when nothing with words comes before the date on its line, the title is the rest of the line without its
     * times and separators, else the next line when it holds no date or time. Null when the date is not first.
     */
    private fun titleAfter(c: Candidates, lines: List<OcrLine>, d: DateCandidate, nextStart: Int): String? {
        val r = c.lineRange(d.lineIndex)
        val text = c.text
        if (text.substring(r.first, d.offset.coerceIn(r.first, r.last + 1)).count(Char::isLetter) >= 3) return null
        val end = minOf(r.last + 1, nextStart).coerceAtLeast(d.end)
        val after = text.substring(d.end, end)
        val cut = StringBuilder(after)
        for (t in TimeParser.parse(after).sortedByDescending { it.startOffset }) cut.replace(t.startOffset, t.endOffset, " ")
        val rest = cut.toString().replace(Regex("""\s+"""), " ").trim().trim('-', '–', '—', ':', ',', '|', '·', '.').trim()
        if (rest.count(Char::isLetter) >= 3 && !PLACE_FIRST.containsMatchIn(rest)) return rest
        val next = d.lineIndex + 1
        val below = next.takeIf { it in lines.indices && c.lineRange(it).first < nextStart && !c.lineHasDateOrTime(it) && titleLike(it, lines, c) &&
            !Layout.isWeekdayOnly(lines[it].text) }
        // a card: the title printed just above its date line ("The Last Lighthouse" / "Friday, 19 February · 8:00 PM") when
        // it is nearer than the line below, which is then the next card's title
        val dl = lines[d.lineIndex]
        // a card with the title beside its date tile, on the same row to its right: that title first
        lines.indices.filter { i -> val o = lines[i]
            i != d.lineIndex && o.left >= dl.right - (dl.bottom - dl.top) / 2 &&
                minOf(o.bottom, dl.bottom) - maxOf(o.top, dl.top) >= 0.5 * minOf(o.bottom - o.top, dl.bottom - dl.top) &&
                titleLike(i, lines, c) && !c.lineHasDateOrTime(i) && !Layout.isWeekdayOnly(o.text)
        }.minByOrNull { lines[it].left }?.let { return lines[it].text.trim() }
        fun gap(o: OcrLine) = if (o.top >= dl.bottom) o.top - dl.bottom else dl.top - o.bottom
        val above = lines.indices.filter { it != d.lineIndex && lines[it].bottom <= dl.top + (dl.bottom - dl.top) / 2 &&
            lines[it].right > dl.left && lines[it].left < dl.right }.maxByOrNull { lines[it].bottom }
            ?.takeIf { titleLike(it, lines, c) && !c.lineHasDateOrTime(it) && !Layout.isWeekdayOnly(lines[it].text) &&
                gap(lines[it]) <= maxOf(dl.bottom - dl.top, lines[it].bottom - lines[it].top) }
        if (above != null && (below == null || gap(lines[above]) < gap(lines[below]))) {
            var first = above
            while (first - 1 in lines.indices && titleLike(first - 1, lines, c) && !c.lineHasDateOrTime(first - 1) && sameTitle(lines[first - 1], lines[first])) first--
            return (first..above).joinToString(" ") { lines[it].text.trim() }
        }
        return below?.let { lines[it].text.trim() }
    }

    /** The last clause between [from] and [to] (split at . ! ? : ; , and line ends), without trailing filler words. */
    /** [from], moved past the last time between [from] and [to] ("... at 8:15 pm and prayer meeting on 9 Feb": "and prayer meeting"). */
    private fun afterTimes(c: Candidates, from: Int, to: Int): Int =
        c.times.filter { it.offset >= from && it.endOffset <= to }.maxOfOrNull { it.endOffset } ?: from

    /**
     * Two or more dates in a chat are separate plans when each has its own words before it on its line (after any time of
     * the plan before it) and no two share a word: "Pickup from the airport on 19 Dec" and "dentist appointment on 25 Dec". A date
     * with no words of its own ("or 8 Nov at 7 pm?") or the same words again revises the plan before it: one event.
     */
    private fun separatePlans(c: Candidates, text: String, dates: List<DateCandidate>): Boolean {
        val clauses = dates.mapIndexed { k, d ->
            // its own line only, after the time of the plan before it
            val from = maxOf(c.lineRange(d.lineIndex).first, afterTimes(c, if (k == 0) 0 else dates[k - 1].end, d.offset))
            clauseBefore(text, from, d.offset)
        }
        // a deadline ("Last date for fee payment is 10 Nov", "registration closes 18 Jan") is not a plan of its own
        val words = clauses.filterNot { it != null && DEADLINE_WORDS.containsMatchIn(it) }.map { it?.let(ChatBubbles::contentSet).orEmpty() }
        if (words.size < 2) return false
        return words.all { it.isNotEmpty() } && words.indices.all { i -> words.indices.all { j -> i == j || (words[i] intersect words[j]).isEmpty() } }
    }

    /** A month name alone, maybe with its year ("DEC", "February 2027"): a date tile's or a heading's part, never a title. */
    private val MONTH_ONLY = regex("""^\W*${Patterns.MONTH}\.?(?:\s+\d{4})?\W*$""")

    private val RANGE = Regex("""\d\s*(?:[-–—]|to)\s*\d""", RegexOption.IGNORE_CASE)

    private val DEADLINE_WORDS = regex("""\b(?:deadline|last\s+date|due|closes?|closing|ends?|expires?|apply\s+by|register\s+by)\b""")

    private fun clauseBefore(text: String, from: Int, to: Int): String? =
        // a sentence wrapped over lines is read as one ("... and the blood" / "donation camp on 18 Feb"): a line break
        // before a word in lower case, or after "and", "the", "of" ..., is a space
        text.substring(from, to).replace(WRAP_LOWER, " ").replace(WRAP_AFTER, " ").split(SEGMENT_SPLIT).asReversed()
            .map { TRAILING_FILLER.replace(it, "").trim().replace(LEADING_JOIN, "").trim().trimEnd('-', '–', '—', ' ') }
            .firstOrNull { seg -> seg.count(Char::isLetter) >= 3 && TimeParser.parse(seg).isEmpty() && !STATUS.matches(seg) && !Layout.isWeekdayOnly(seg) }

    private val WRAP_LOWER = Regex("""\n(?=\p{Ll})""")
    private val WRAP_AFTER = regex("""(?<=\b(?:and|the|of|a|an|to|for|with)|&)\n""")
    /** The joining word a clause of a list starts with ("and the gardening workshop", "also dinner"): not the title's. */
    private val LEADING_JOIN = regex("""^(?:(?:and|also|plus|then|but|or)\s+)+(?:the\s+|a\s+|an\s+)?""")

    private fun pickTime(c: Candidates, date: DateCandidate?): TimeCandidate? {
        val times = c.times.filter { it.label?.kind !in NOT_EVENT_TIME }
        if (date == null) return times.maxByOrNull { it.box.height }
        return times.minWithOrNull(compareBy({ abs(it.lineIndex - date.lineIndex) }, { abs(it.offset - date.offset) }))
    }

    /**
     * A line that could be a title: at least 3 letters outside dates, times and label words; not an amount; not a status
     * line ("Booking confirmed", "Appointment scheduled").
     */
    private fun titleLike(i: Int, lines: List<OcrLine>, c: Candidates): Boolean =
        !AMOUNT.matches(lines[i].text) && !STATUS.matches(lines[i].text.trim()) && !Titles.GENERIC_LINE.matches(lines[i].text.trim()) &&
            !MONTH_ONLY.matches(lines[i].text.trim()) &&
            c.lineRest(i).count { it.isLetter() } >= 3

    /** The tallest title-like line without a date or time (else with), merged with its title neighbours. */
    private fun largestLine(lines: List<OcrLine>, c: Candidates): String? {
        val pool = lines.indices.filter { titleLike(it, lines, c) && !c.lineHasDateOrTime(it) }
            .ifEmpty { lines.indices.filter { titleLike(it, lines, c) } }
        val best = pool.maxByOrNull { lines[it].bottom - lines[it].top } ?: return null
        fun ok(j: Int) = j in lines.indices && titleLike(j, lines, c) && !c.lineHasDateOrTime(j)
        var first = best
        var last = best
        while (ok(first - 1) && sameTitle(lines[first - 1], lines[first])) first--
        while (ok(last + 1) && sameTitle(lines[last], lines[last + 1])) last++
        return (first..last).joinToString(" ") { lines[it].text.trim() }
    }

    /**
     * On an email (its header block was found), the event's day sits inside a body sentence ([from], [to] in the text, on
     * line [i]): a line in ordinary type, part of a paragraph (a line of the same type just above or below, left edges
     * aligned), with at least [SENTENCE_WORDS] words. An email's own type sizes say nothing of the event (the tallest line
     * is the subject or an attachment), so the title is the sentence's words before the day ("What do you think of having
     * a brunch" from "... a brunch on Sunday."), when they hold at least [MIN_TITLE_WORDS] words. Else null.
     */
    private fun sentenceTitle(lines: List<OcrLine>, c: Candidates, i: Int, from: Int, to: Int): String? {
        if (i !in lines.indices) return null
        val h = { k: Int -> lines[k].bottom - lines[k].top }
        val median = lines.indices.map(h).sorted()[lines.size / 2]
        val l = lines[i]
        if (h(i) > BODY_TYPE * median) return null
        val para = listOf(i - 1, i + 1).any { j ->
            j in lines.indices && abs(lines[j].left - l.left) <= maxOf(h(i), h(j)) && abs(h(j) - h(i)) <= 0.25 * maxOf(h(i), h(j)) &&
                (if (j < i) l.top - lines[j].bottom else lines[j].top - l.bottom) in -h(i)..h(i)
        }
        val text = c.text.substring(c.lineRange(i).first, c.lineRange(i).last + 1)
        if (!para || text.trim().split(Regex("\\s+")).size < SENTENCE_WORDS) return null
        val s = c.lineRange(i).first
        val a = (from - s).coerceIn(0, text.length); val b = (to - s).coerceIn(0, text.length)
        fun clean(t: String) = t.trim().replace(LEAD_IN, "").trim().trim { !it.isLetterOrDigit() && it != ')' && it != '"' }
        fun ok(t: String) = t.split(Regex("\\s+")).count { w -> w.count(Char::isLetter) >= 2 } >= MIN_TITLE_WORDS
        return clean(text.substring(0, a)).takeIf(::ok)
    }

    /**
     * On an app's screen, a card's date block: the date line is short (only the date and time), and the line just above it, left edges
     * aligned, at most one line apart, in type no smaller, holds no date or time and reads like a title: that heading is
     * the event's title (merged with its own first line above, as [largestLine] does). Else null.
     */
    private fun cardTitle(lines: List<OcrLine>, c: Candidates, i: Int): String? {
        if (i !in lines.indices || c.lineRest(i).count { it.isLetter() } > 3) return null
        val d = lines[i]; val hd = d.bottom - d.top
        val j = lines.indices.filter { it != i && lines[it].bottom <= d.top + hd / 2 && lines[it].right > d.left && lines[it].left < d.right }
            .maxByOrNull { lines[it].bottom } ?: return null
        val t = lines[j]; val ht = t.bottom - t.top
        if (abs(t.left - d.left) > maxOf(ht, hd) || d.top - t.bottom > maxOf(ht, hd) || ht < 0.9 * hd) return null
        if (!titleLike(j, lines, c) || c.lineHasDateOrTime(j)) return null
        var first = j
        while (first - 1 in lines.indices && titleLike(first - 1, lines, c) && !c.lineHasDateOrTime(first - 1) && sameTitle(lines[first - 1], lines[first])) first--
        return (first..j).joinToString(" ") { lines[it].text.trim() }
    }

    /**
     * The heading of an app's screen or an email: a labelled subject ("Subject: ..."), else on a screen the tallest
     * title-like line in the top quarter of the text, in heading type. Null when there is none.
     */
    private fun pageHeading(lines: List<OcrLine>, c: Candidates, screen: Boolean, subjects: Set<Int>): String? {
        lines.indices.firstOrNull { SUBJECT_LINE.containsMatchIn(lines[it].text) }?.let { i ->
            return Titles.plain(SUBJECT_LINE.replace(lines[i].text, "").trim())
        }
        subjects.firstOrNull()?.let { return Titles.plain(lines[it].text.trim()) }
        if (!screen || lines.isEmpty()) return null
        val top = lines.minOf { it.top }; val span = (lines.maxOf { it.bottom } - top).coerceAtLeast(1)
        val median = lines.map { it.bottom - it.top }.sorted()[lines.size / 2]
        return lines.indices.filter { lines[it].bottom <= top + span / 4 && titleLike(it, lines, c) && !c.lineHasDateOrTime(it) &&
            (lines[it].bottom - lines[it].top) >= TitleSearch.HEADING_TYPE * median }
            .maxByOrNull { lines[it].bottom - lines[it].top }?.let { Titles.plain(lines[it].text.trim()) }
    }

    /** The title is in doubt when the page's own heading names something else: neither holds the other's words. */
    private fun headingDiffers(title: String?, heading: String?): Boolean {
        if (title == null || heading == null) return false
        fun ws(s: String) = ChatBubbles.words(s).map { it.lowercase() }.filter { it.length >= 3 }.toSet()
        val a = ws(title); val b = ws(heading)
        return b.isNotEmpty() && a.intersect(b).isEmpty()
    }

    /** The page's blocks for [TitleSearch]: paragraphs, cards and rows. */
    private fun pageBlocks(lines: List<OcrLine>) = TitleSearch.pageBlocks(lines)

    /** [b] continues the title on [a]: similar height, [b] just below [a], left edges or centres aligned. */
    private fun sameTitle(a: OcrLine, b: OcrLine): Boolean {
        val ha = a.bottom - a.top
        val hb = b.bottom - b.top
        val h = maxOf(ha, hb).toDouble()
        val gap = b.top - a.bottom
        val aligned = abs(a.left - b.left) <= MERGE_ALIGN * h || abs((a.left + a.right) - (b.left + b.right)) / 2.0 <= MERGE_ALIGN * h
        return minOf(ha, hb) >= MERGE_HEIGHT_RATIO * h && gap >= -MERGE_ALIGN * h && gap <= MERGE_MAX_GAP * h && aligned
    }

    /**
     * "Pay <provider> <type> bill". The provider, in order: an email's sender ("From: Zenith Bank <billing@...>", the
     * address and company suffixes dropped); an SMS's sign-off at the end of a body line ("... 1800 344 8863. -Zenith
     * Bank"); the first short line at the top that is a name, not the bill's own heading ("Greater Town" over "Property Tax
     * Demand Notice": no bill or statement words, no digits, not an SMS sender code); else the tallest line at the top.
     */
    private fun billTitle(lines: List<OcrLine>, c: Candidates, imageHeight: Int): String {
        val top = lines.indices.filter { lines[it].top < 0.3 * imageHeight && titleLike(it, lines, c) }
        fun tidy(s: String) = COMPANY_SUFFIX.replace(s.replace(EMAIL_ADDRESS, " "), "").replace(Regex("""\s+"""), " ").trim().trim(',', '.', '-', ' ')
        val from = lines.firstNotNullOfOrNull { l -> FROM_LINE.find(l.text)?.groupValues?.get(1)?.let(::tidy)?.takeIf { it.count(Char::isLetter) >= 3 } }
        val signed = lines.firstNotNullOfOrNull { l ->
            SIGN_OFF.find(l.text.trim())?.groupValues?.get(1)?.takeIf { it.any(Char::isLowerCase) && l.text.trim().split(' ').size >= 4 }?.let(::tidy)
        }
        // among the first few lines at the top only: field labels further down ("Consumer No.") are never the name
        val name = top.sortedBy { lines[it].top }.take(NAME_LINES).firstOrNull { i ->
            val t = tidy(lines[i].text.trim())
            t.none(Char::isDigit) && t.split(Regex("""\s+""")).size <= NAME_WORDS && !BILL_HEADING.containsMatchIn(t) &&
                !SENDER_CODE.matches(t) && !FROM_LINE.containsMatchIn(lines[i].text)
        }?.let { tidy(lines[it].text.trim()) }
        val provider = from ?: signed ?: name ?: top.maxByOrNull { lines[it].bottom - lines[it].top }?.let { lines[it].text.trim() }
        // the type from a line other than the one the provider was read from ("Brightline Power" / "Electricity bill")
        val type = lines.filterNot { provider != null && it.text.contains(provider, ignoreCase = true) }
            .firstNotNullOfOrNull { (BILL_TYPE.find(it.text) ?: PROPERTY_TAX.find(it.text))?.groupValues?.get(1) }
            ?.lowercase()?.replace(Regex("""\s+"""), " ")
        return listOfNotNull("Pay", provider, type, "bill").joinToString(" ")
    }

    private val FROM_LINE = regex("""^\W*from\s*:\s*(.+?)\s*(?:<[^>]*>?)?\s*$""")
    private val EMAIL_ADDRESS = Regex("""<?\S+@\S+>?""")
    private val COMPANY_SUFFIX = regex("""(?:\s+(?:pvt|private|co|company|ltd|limited|llp|inc|services?)\.?)+\s*$""")
    private val SIGN_OFF = Regex("""\S\s+[-–]\s*([A-Z][A-Za-z&.]*(?:\s+[A-Z][A-Za-z&.]*){0,3})\s*\.?\s*$""")
    private val SENDER_CODE = Regex("""^[A-Z]{2}-[A-Z0-9]+$""")
    /** Words of a bill's own heading or body, never a provider's name. */
    private val BILL_HEADING = regex("""\b(?:your|statement|notice|demand|reminder|bill|invoice|dear|customer|pay|payment|due|payable|amount|summary|account|postpaid|prepaid|recharge|text|message|sms|today|helpline|date)\b""")
    private val PROPERTY_TAX = regex("""\b(property\s+tax)\b""")
    private const val NAME_LINES = 3
    private const val NAME_WORDS = 8

    /** The chat title as [extract] picks it (kept by name for test/probe/CodeProbe.kt). */
    private fun chatTitle(cleaned: TextCleaner.Result, imageHeight: Int): String? {
        val c = CandidateFinder.find(cleaned.kept)
        val chat = ChatBubbles(cleaned.kept, c, 0, imageHeight)
        val date = chatDate(cleaned.kept, c, chat)
        val time = pickTime(c, date)
        return chat.title(chat.bubbleOf(date?.lineIndex ?: time?.lineIndex), date?.raw ?: time?.raw)
    }

    private fun location(lineTexts: List<String>): String? {
        for ((i, l) in lineTexts.withIndex()) {
            val m = LOCATION.matchEntire(l) ?: continue
            // "where" with no colon and more words opens a question ("Where did you leave it?"); "WHERE" alone is a label
            if (m.groupValues[1].equals("where", ignoreCase = true) && m.groupValues[2].isEmpty() && m.groupValues[3].isNotBlank()) continue
            val rest = m.groupValues[3].trim()
            val value = rest.ifEmpty { lineTexts.getOrNull(i + 1)?.trim().orEmpty() }
            if (value.isEmpty() || value.endsWith("?") || LABEL_ONLY.matches(value) ||
                Dates.findDates(value).isNotEmpty() || TimeParser.parse(value).any { it.startOffset == 0 }) continue
            return value
        }
        return null
    }
}
