package `in`.gauthama.snapslotify.core.pipeline

import `in`.gauthama.snapslotify.core.extract.CandidateFinder
import `in`.gauthama.snapslotify.core.extract.ChatBubbles
import `in`.gauthama.snapslotify.core.extract.PageRoute
import `in`.gauthama.snapslotify.core.extract.ChatContext
import `in`.gauthama.snapslotify.core.extract.Candidates
import `in`.gauthama.snapslotify.core.extract.CodeExtractor
import `in`.gauthama.snapslotify.core.extract.LeadRepair
import `in`.gauthama.snapslotify.core.extract.RawEvent
import `in`.gauthama.snapslotify.core.extract.RouteChoice
import `in`.gauthama.snapslotify.core.extract.TimeParser
import `in`.gauthama.snapslotify.core.layout.PageBend
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.resolve.DateResolver
import `in`.gauthama.snapslotify.core.resolve.Resolved
import `in`.gauthama.snapslotify.core.resolve.YearHint
import `in`.gauthama.snapslotify.core.score.ScoreEvent
import `in`.gauthama.snapslotify.core.text.ChatLines
import `in`.gauthama.snapslotify.core.text.ChatPicture
import `in`.gauthama.snapslotify.core.text.Chrome
import `in`.gauthama.snapslotify.core.text.SenderStamps
import `in`.gauthama.snapslotify.core.text.SentStamp
import `in`.gauthama.snapslotify.core.text.StackedDates
import `in`.gauthama.snapslotify.core.text.TextCleaner
import `in`.gauthama.snapslotify.core.text.WrappedTimes
import `in`.gauthama.snapslotify.core.verify.DigitConfidence
import `in`.gauthama.snapslotify.core.verify.Flag
import `in`.gauthama.snapslotify.core.verify.VerifiedEvent
import `in`.gauthama.snapslotify.core.verify.Verifier
import java.time.LocalDate

/** A kept event: the verifier's result plus the resolved calendar dates. */
class FinalEvent(val verified: VerifiedEvent, val resolved: Resolved, private val pageFlags: List<Flag> = emptyList()) {
    /** The same event with one more flag. */
    fun withFlag(f: Flag) = FinalEvent(verified, resolved, pageFlags + f)

    /** One date of a list with gaps ([Resolved.listed]): the same event on that day alone. */
    fun on(date: LocalDate) = FinalEvent(verified, resolved.copy(start = date, end = null, listed = emptyList()), pageFlags)

    val event: RawEvent get() = verified.after!!
    val flags: List<Flag> get() = verified.flags + resolved.flags + pageFlags
}

/** A candidate the pipeline did not show: its date (ISO, null for a time alone) and why (past, deadline, stamp). */
data class Drop(val date: String?, val reason: String)

class PipelineResult(
    val cleaned: TextCleaner.Result,
    val candidates: Candidates,
    val events: List<FinalEvent>,
    val dropped: List<VerifiedEvent>,
    /** Time spent in the cleaner, and in candidates + extractor + verifier + resolver (for the metrics log). */
    val cleanMs: Long = 0,
    val extractMs: Long = 0,
    /** For a chat: its bubbles, the date bubble and the message date line above it (the chat model's input). */
    val chat: ChatContext? = null,
    /** How the page was routed: every route's score and signals, the route read, and how (clear, close, unsure). */
    val route: RouteChoice? = null,
    /** How the photo's rows ran when they were not level ([PageBend]): the boxes in [cleaned] are levelled; null when as taken. */
    val bend: PageBend.Bend? = null,
    /** What the page says about the year of each date written without one, by the candidate's offset ([YearHints]). */
    val yearHints: Map<Int, `in`.gauthama.snapslotify.core.resolve.YearHint> = emptyMap(),
    /** Each "next <weekday>": its line, the phrase and its two readings, offered as chips ([YearHints.nextWeekday]). */
    val nextWeekday: List<Triple<Int, String, List<LocalDate>>> = emptyList(),
    /** Candidates left out: past (before the page's own date, else today), a closing date, a stamp ([Drop]). */
    val drops: List<Drop> = emptyList(),
    /** Events the page does not settle (two plausible dates, a time with no date): offered as chips only, no prefill. */
    val ambiguous: List<FinalEvent> = emptyList(),
    /** A calendar or programme page whose dates are all past: the note shown with no event found. */
    val pastNote: String? = null,
    /** A picture inside the chat ([ChatPicture]). */
    val picture: Boolean = false,
    /** Times a stamp rule took out near a shown event's date, beside a picture: offered as time chips. */
    val extraTimes: List<Pair<String, java.time.LocalTime>> = emptyList(),
) {
    val cleanedText: String get() = cleaned.text

    /**
     * Try harder (the vision model, asked for one title, date and time) fits a poster with one event (the single-event
     * route, exactly one event) and a page where the code found no event (a blurred photo, a misread date), unless it was
     * read as a calendar grid or a bill. Never a chat or an email. Programmes, grids, lists and bills with events do not
     * get it. Its answer only adds chips, so on a page with no event it never fills a field.
     */
    val tryHarderFits: Boolean get() {
        if (cleaned.looksLikeChat || cleaned.removed.any { it.rule.startsWith("email header") }) return false
        val chosen = route?.chosen
        if (events.isEmpty()) return chosen != `in`.gauthama.snapslotify.core.extract.PageRoute.GRID &&
            chosen != `in`.gauthama.snapslotify.core.extract.PageRoute.BILL && chosen != `in`.gauthama.snapslotify.core.extract.PageRoute.CHAT
        return chosen == `in`.gauthama.snapslotify.core.extract.PageRoute.SINGLE && events.size == 1
    }

    /**
     * The lines a model reads to title event [index]: its date's line (else its time's), up to [TITLE_ABOVE] lines above
     * and [TITLE_BELOW] below, from the cleaned lines (chrome already taken out). All the lines when neither is found.
     */
    fun titleContext(index: Int): List<String> {
        val ev = events.getOrNull(index)?.event ?: return emptyList()
        fun one(s: String) = s.replace(Regex("""\s+"""), " ").trim()
        val line = candidates.dates.firstOrNull { ev.date != null && one(it.raw) == one(ev.date) }?.lineIndex
            ?: candidates.times.firstOrNull { ev.time != null && one(it.raw).trimEnd('.') == one(ev.time).trimEnd('.') }?.lineIndex
        val texts = cleaned.kept.map { it.text.trim() }
        if (line == null) return texts
        return texts.subList(maxOf(0, line - TITLE_ABOVE), minOf(texts.size, line + TITLE_BELOW + 1))
    }

    companion object {
        const val TITLE_ABOVE = 4
        const val TITLE_BELOW = 2
    }

    /**
     * For the scorer: final values, the extractor's date as the "model date", and the date flags. With [today], also
     * the resolved dates (final and extractor's) and the start time, for keys that compare calendar values.
     */
    fun scoreEvents(today: LocalDate? = null): List<ScoreEvent> = events.map {
        ScoreEvent(
            it.event.title, it.event.time, it.event.date, it.verified.before.date, it.verified.dateFlags,
            start = it.resolved.start, end = it.resolved.end,
            modelStart = today?.let { t -> DateResolver.resolve(it.verified.before.date, t).start },
            startTime = it.event.time?.let { t -> TimeParser.parse(t).firstOrNull()?.start },
        )
    }
}

/** OCR result -> TextCleaner (G) -> CandidateFinder -> CodeExtractor -> Verifier -> DateResolver. No model. */
object CodePipeline {

    /** Rule/version tag, written to every FINAL-mode line in sealed_runs.log. Change it whenever the rules change. */
    const val VERSION = "code-2b.1"

    /**
     * [shotDate]: the screenshot's own date (gallery, EXIF or file name), the anchor when no date line anchors a relative
     * day; without it, [today] is the anchor, with its own flag. [turnUnread]: the photo looked turned but could not be
     * read upright ([`in`.gauthama.snapslotify.core.ocr.PageTurn.read]), so every event is flagged.
     */
    fun run(ocr: OcrResult, today: LocalDate, shotDate: LocalDate? = null, turnUnread: Boolean = false): PipelineResult {
        // no date of its own: the current date is the anchor, flagged as such
        val anchor = shotDate ?: today
        fun one(s: String) = s.replace(Regex("""\s+"""), " ").trim()
        val t0 = System.nanoTime()
        // a photo turned a little, or of a page that curls or bows: its rows put level before anything compares positions
        val bend = PageBend.measure(ocr.lines, ocr.imageWidth)
        // a stray mark read as extra characters at the front of a day or an hour, blanked before anything reads the lines
        val (leadFixed, repaired) = LeadRepair.repairLines(bend?.flatten(ocr.lines) ?: ocr.lines, anchor)
        // letter-spaced type ("S U M M E R   F A I R") joined back into words
        val lines = `in`.gauthama.snapslotify.core.text.LetterSpacing.join(leadFixed)
        var cleaned = StackedDates.tidy(WrappedTimes.tidy(
            SentStamp.tidy(SenderStamps.tidy(ChatLines.tidy(Chrome.clean(TextCleaner.clean(lines, ocr.imageWidth, ocr.imageHeight), lines), ocr.imageHeight)), ocr.imageHeight),
        ))
        // a picture inside a chat (a poster shared as an image): only used to offer the times stamp rules took out near it
        val picture = if (cleaned.looksLikeChat) ChatPicture.find(lines, ocr.imageWidth, ocr.imageHeight) else null
        val t1 = System.nanoTime()
        var candidates = CandidateFinder.find(cleaned.kept, repaired)
        var routed = CodeExtractor.route(cleaned, candidates, ocr.imageHeight, ocr.imageWidth, today, anchor, anchorIsToday = shotDate == null)
        // a chat's, email's or post's small time stamps ([SmallStamp]), on a page clearly of that type; read again without them
        SmallStamp.tidy(cleaned, candidates, routed.choice, ocr.imageWidth, ocr.imageHeight)?.let { small ->
            val c2 = CandidateFinder.find(small.kept, repaired)
            val r2 = CodeExtractor.route(small, c2, ocr.imageHeight, ocr.imageWidth, today, anchor, anchorIsToday = shotDate == null)
            if (r2.choice.chosen == routed.choice.chosen) { cleaned = small; candidates = c2; routed = r2 }
        }
        val closingDates = if (payPage(candidates.text)) emptySet() else
            candidates.dates.filter { CodeExtractor.isDeadline(candidates, it) }.map { one(it.raw) }.toSet()
        val verified = routed.events.map { Verifier.verify(it, candidates.text) { raw -> one(raw) in closingDates } }
        val (kept, dropped) = verified.partition { it.after != null }
        val pageFlags = if (turnUnread) listOf(Flag("page_turned")) else emptyList()
        // a date with no year: an event's is the next one ahead, unless the page shows it is past ([YearHints])
        val ahead = routed.choice.chosen != PageRoute.GRID
        val hints = YearHints.of(cleaned, candidates, ahead, anchor)
        fun hintFor(ev: RawEvent) = (candidates.dates.firstOrNull { one(it.raw) == ev.date }?.let { hints[it.offset] }
            ?: `in`.gauthama.snapslotify.core.resolve.YearHint(ahead = ahead, now = anchor)).let { if (ev.grid) it.copy(ahead = false) else it }
        // a digit of the date or time the engine was unsure of: low_ocr_confidence on that field
        val events = kept.map {
            FinalEvent(it, DateResolver.resolve(it.after!!.date, today, hintFor(it.after!!)), pageFlags + DigitConfidence.flags(it.after!!, cleaned.kept, candidates) + LeadRepair.flags(it.after!!, candidates) +
                (if (it.after!!.titleUnsure) listOf(Flag("title_unsure")) else emptyList()))
        }.filter { e ->
            // an event with no date and no stated time is not shown: the page says no event was found. Every time left is
            // written in the message (stamps, sent times and the clock are taken out as chrome). A page that asks (a bill's
            // unclear due date, a day/month read two ways) keeps its event: it is the question
            e.resolved.start != null || e.resolved.alternatives.isNotEmpty() || e.event.dueUnclear || e.event.time != null
        }
        // past, closing dates and pages that do not settle the event ([sortOut])
        val sorted = sortOut(events, cleaned, candidates, routed.choice.chosen, today, ocr.imageWidth, ocr.imageHeight, ::hintFor, anchor)
        val t2 = System.nanoTime()
        val chat = CodeExtractor.chatContext(cleaned, candidates, ocr.imageWidth, ocr.imageHeight)
        // beside a picture the reading stays, but a time a stamp rule took out near a shown event's date is offered as a chip;
        // an event there with a date and no time is flagged (time_missing): it is not an all-day event, a time is to be picked
        val extra = if (picture != null) nearDateStamps(cleaned, candidates, sorted.shown, lines) else emptyList()
        val shown = if (extra.isEmpty()) sorted.shown else sorted.shown.map { e ->
            if (e.event.time == null && e.resolved.start != null && !e.event.bill) e.withFlag(Flag("time_missing", extra.joinToString(" | ") { it.first })) else e
        }
        return PipelineResult(cleaned, candidates, shown, dropped, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, chat, routed.choice, bend,
            hints, YearHints.nextWeekday(candidates, anchor), sorted.drops, sorted.ambiguous, sorted.note, picture != null, extra)
    }

    /** A taken-out stamp this many lines (in reading order) from a shown event's date line is offered beside a picture. */
    const val NEAR_DATE_LINES = 2

    /** Times taken out by a stamp rule within [NEAR_DATE_LINES] lines of a shown event's date line, as (text, time). */
    fun nearDateStamps(cleaned: TextCleaner.Result, c: Candidates, shown: List<FinalEvent>, lines: List<`in`.gauthama.snapslotify.core.ocr.OcrLine>): List<Pair<String, java.time.LocalTime>> {
        fun one(s: String) = s.replace(Regex("""\s+"""), " ").trim()
        val tops = lines.map { it.top }.sorted()
        fun pos(l: `in`.gauthama.snapslotify.core.ocr.OcrLine) = tops.count { it < l.top }
        val datePos = shown.mapNotNull { e -> e.event.date?.let { d -> c.dates.firstOrNull { one(it.raw) == one(d) } }?.lineIndex }
            .filter { it in cleaned.kept.indices }.map { pos(cleaned.kept[it]) }
        if (datePos.isEmpty()) return emptyList()
        return cleaned.removed.filter { ChatPicture.isStampRule(it.rule) && datePos.any { p -> kotlin.math.abs(pos(it.line) - p) <= NEAR_DATE_LINES } }
            .flatMap { r -> TimeParser.parse(r.line.text).map { one(r.line.text) to it.start } }.distinctBy { it.second }
    }

    /** A message in the past tense that still plans or moves something ahead: not past. */
    private val STILL_AHEAD = Regex("""(?i)\b(?:will|shall|going\s+to|gonna|moved|postponed|rescheduled|shifted|changed|tomorrow|tonight|next|coming|upcoming|plan(?:ned)?)\b""")

    /** A date written with no year whose last occurrence was at most this many days before the page's date is two dates: chips. */
    const val RECENT_PAST_DAYS = 31L

    /** A page that asks for a payment: an amount, and a bill or payment word. Its last date is a due date, not a closing date. */
    private fun payPage(text: String) = MONEY.containsMatchIn(text) && (CodeExtractor.BILL.containsMatchIn(text) || PAYMENT.containsMatchIn(text))

    private val PAYMENT = Regex("""(?i)\b(?:pay|payment|penalty|late\s+fee|tax|fees|charges)\b""")
    private val MONEY = Regex("""(?i)(?:\b(?:rs\.?|inr|usd|eur)|[₹$€£])\s*\d[\d,]*(?:\.\d{1,2})?""")

    class Sorted(val shown: List<FinalEvent>, val drops: List<Drop>, val ambiguous: List<FinalEvent>, val note: String?)

    /**
     * Now is the date the page shows (a chat's date line above the event, an email's or post's own date), else [today];
     * dates are compared, never clock times, so an event later today (or earlier today) stays.
     * - Dropped, not shown: a date before now; a closing date ("Offer ends", "Register by", [CodeExtractor.isDeadline]) on any
     *   page but a bill; a time with no date in a past-tense sentence ("was delivered at 4:15 pm"). Stamps were taken out
     *   by the cleaner; they are listed here too.
     * - Chips only, no prefill: a time with no date, a day/month read two ways, a bill whose due date is unclear, and a
     *   date written with no year whose last occurrence was within [RECENT_PAST_DAYS] before now (it was just past, or it is
     *   next year's: two plausible dates).
     * - A calendar or programme page whose events are all past: no event, with a note.
     * Past dates still count toward the route and its thresholds (they are sorted out only here).
     */
    fun sortOut(
        events: List<FinalEvent>, cleaned: TextCleaner.Result, c: Candidates, route: PageRoute, today: LocalDate,
        imageWidth: Int, imageHeight: Int, hintFor: (RawEvent) -> YearHint, shot: LocalDate = today,
    ): Sorted {
        fun one(s: String) = s.replace(Regex("""\s+"""), " ").trim()
        val bubbles = if (cleaned.looksLikeChat && cleaned.kept.isNotEmpty()) ChatBubbles(cleaned.kept, c, imageWidth, imageHeight) else null
        val pageDate = CodeExtractor.pageDate(cleaned, bubbles, today, shot)
        val drops = mutableListOf<Drop>()
        val ambiguous = mutableListOf<FinalEvent>()
        val lineTexts = c.text.split('\n')
        // a page that asks for a payment (an amount, and a bill or payment word): its last date is the due date, not a closing date
        val payPage = payPage(c.text)
        // each event's now (below), for a list's dates
        val nowOf = HashMap<FinalEvent, LocalDate>()
        val shown = events.filter { e ->
            val ev = e.event
            val cand = ev.date?.let { d -> c.dates.firstOrNull { one(it.raw) == one(d) } }
            val timeLine = ev.time?.let { t -> c.times.firstOrNull { one(it.raw).trimEnd('.') == one(t).trimEnd('.') }?.lineIndex }
            val line = cand?.lineIndex ?: timeLine
            val now = pageDate(line ?: -1) ?: today
            val start = e.resolved.start
            val last = e.resolved.end ?: e.resolved.listed.lastOrNull() ?: start
            nowOf[e] = now
            val hint = hintFor(ev)
            // a date with no year whose last occurrence is just past: past when its written weekday fits that day, else two dates
            val lastSeen = if (start != null && e.resolved.yearInferred && hint.written == null && !hint.past && !ev.grid) {
                // a list of days: its last day (the earlier ones may be just past while the list runs on)
                val seen = e.resolved.listed.lastOrNull() ?: start
                (now.year downTo now.year - 1).firstNotNullOfOrNull { y -> runCatching { LocalDate.of(y, seen.month, seen.dayOfMonth) }.getOrNull()?.takeIf { it.isBefore(now) } }
                    ?.takeIf { !it.isBefore(now.minusDays(RECENT_PAST_DAYS)) }
            } else null
            val weekday = ev.date?.let { `in`.gauthama.snapslotify.core.extract.DateParser.parse(DateResolver.written(it)).firstOrNull()?.weekday }
            val recent = lastSeen != null && (weekday == null || lastSeen.dayOfWeek != weekday)
            val pastByWeekday = lastSeen != null && weekday != null && lastSeen.dayOfWeek == weekday
            // the page's own date printed alone on its line ("24 January 2027" over messages stamped that day): the page's date line
            val pageDateLine = cand != null && pageDate(cand.lineIndex) != null && start == pageDate(cand.lineIndex) &&
                c.lineRest(cand.lineIndex).none(Char::isLetterOrDigit) && c.times.none { it.lineIndex == cand.lineIndex }
            // a day counted, not written (the chat's date line with a time, "today", a weekday): past when the message says so
            // ("The parcel was delivered at 4:15 pm", "Lunch was great today"), unless it moves or plans ("was moved to tomorrow")
            val counted = ev.date == null || ev.messageDate != null || ev.shotDate != null || ev.relativeDay
            fun lineAt(i: Int) = c.text.substring(0, i.coerceIn(0, c.text.length)).count { it == '\n' }
            val saidLine = timeLine ?: ev.title?.let { t -> lineTexts.indexOfFirst { t.isNotBlank() && it.contains(t.trim(), ignoreCase = true) }.takeIf { it >= 0 } }
                ?: `in`.gauthama.snapslotify.core.extract.AnchoredDay.WORD.find(c.text)?.let { m -> lineAt(m.range.first) }
            // the day is the chat's own date line (no date written in the message), or no date on the page at all
            val notWritten = cand == null || bubbles?.isMessageDateLine(cand.lineIndex) == true
            val toldPast = counted && notWritten && saidLine != null && lineTexts.getOrElse(saidLine) { "" }.let { t ->
                YearHints.PAST.containsMatchIn(t) && !STILL_AHEAD.containsMatchIn(t)
            }
            when {
                !ev.bill && pageDateLine -> { drops += Drop(start.toString(), "page date line"); false }
                !ev.bill && toldPast -> { drops += Drop(start?.toString(), "past"); false }
                !ev.bill && !payPage && cand != null && CodeExtractor.isDeadline(c, cand) -> { drops += Drop(start?.toString(), "deadline"); false }
                last != null && last.isBefore(now) -> { drops += Drop(start.toString(), "past"); false }
                pastByWeekday -> { drops += Drop(lastSeen.toString(), "past"); false }
                start == null && e.resolved.alternatives.isEmpty() && !ev.dueUnclear && ev.time != null &&
                    timeLine != null && YearHints.PAST.containsMatchIn(lineTexts.getOrElse(timeLine) { "" }) -> { drops += Drop(null, "past"); false }
                start == null || recent -> { ambiguous += e; false }
                else -> true
            }
        }
        for (r in cleaned.removed) if (TextCleaner.isStamp(r.rule)) {
            val d = CodeExtractor.pageDay(r.line.text.trim(), today)
            if (d != null || TimeParser.parse(r.line.text).isNotEmpty()) drops += Drop(d?.toString(), "stamp (${r.rule})")
        }
        // a list of days with gaps ("5, 12, 19 March"): one event per date listed, the past ones left out
        val split = shown.flatMap { e ->
            if (e.resolved.listed.size < 2) listOf(e) else {
                val now = nowOf[e] ?: today
                e.resolved.listed.filter { !it.isBefore(now) }.map { e.on(it) }.also { kept ->
                    e.resolved.listed.filter { it.isBefore(now) }.forEach { drops += Drop(it.toString(), "past") }
                    if (kept.isEmpty()) drops += Drop(e.resolved.start.toString(), "past")
                }
            }
        }
        val allPast = split.isEmpty() && ambiguous.isEmpty() && events.isNotEmpty() && drops.any { it.reason == "past" } &&
            (route == PageRoute.GRID || route == PageRoute.PROGRAMME)
        return Sorted(split, drops, ambiguous, if (allPast) "All dates on this page are in the past." else null)
    }
}
