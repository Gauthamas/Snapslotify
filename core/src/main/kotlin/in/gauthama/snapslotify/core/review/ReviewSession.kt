package `in`.gauthama.snapslotify.core.review

import `in`.gauthama.snapslotify.core.extract.Dates
import `in`.gauthama.snapslotify.core.extract.LabelKind
import `in`.gauthama.snapslotify.core.extract.TimeParser
import `in`.gauthama.snapslotify.core.pipeline.PipelineResult
import `in`.gauthama.snapslotify.core.resolve.DateResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** One Add all: [added] of [tried] events were added; [failed] are the ids that were not. */
@Serializable
data class AddAllResult(val added: Int, val tried: Int, val failed: List<Int>)

/** A tap-to-fill value. [value] is an ISO date or HH:mm; [end] the end of a range; [line] the source OCR line. */
@Serializable
data class Chip(val label: String, val value: String, val end: String? = null, val line: Int)

@Serializable
data class FlagItem(val code: String, val field: Field, val sentence: String)

/** Taps: each edit, chip tap, confirm and delete counts 1. Typed characters are counted separately. */
@Serializable
data class Counts(
    var edits: Int = 0, var chipTaps: Int = 0, var confirms: Int = 0, var deletes: Int = 0, var typedChars: Int = 0,
    /** Of [typedChars] (key taps in the text fields), the letters typed one key at a time. */
    var typedLetters: Int = 0,
    /** A text field typed in that ended as it started ("lunch" to "luch" and back): its touch, not an edit of the field. */
    var revertedEdits: Int = 0,
    val fieldsEdited: MutableSet<String> = mutableSetOf(),
    /** Touches inside the form's date and time pickers beyond the one edit each counts (the day, hour, minute, OK...). */
    var pickerTouches: Int = 0,
) {
    val taps: Int get() = edits + chipTaps + confirms + deletes + revertedEdits
}

@Serializable
data class Prefilled(val title: Boolean, val date: Boolean, val time: Boolean, val location: Boolean)

/** What the calendar insert intent needs. All-day: [begin] is the first day at 00:00 and [end] the day after the last. */
data class CalendarValues(val title: String?, val location: String?, val begin: LocalDateTime, val end: LocalDateTime, val allDay: Boolean)

/** One event on the review screen. Dates and times are stored as ISO strings so the state survives process death. */
@Serializable
data class EventDraft(
    val id: Int,
    var title: String? = null,
    var dateIso: String? = null,
    var endDateIso: String? = null,
    var startIso: String? = null,
    var endIso: String? = null,
    var location: String? = null,
    var allDay: Boolean = true,
    val bill: Boolean = false,
    var dateNeedsConfirm: Boolean = false,
    /** No time was read but the page offers one (time_missing): not an all-day event; a time is picked, or All day ticked. */
    var timeNeedsPick: Boolean = false,
    val flags: List<FlagItem> = emptyList(),
    val titleSourceLine: Int? = null,
    var dateSourceLine: Int? = null,
    var timeSourceLine: Int? = null,
    val locationSourceLine: Int? = null,
    val dateChips: List<Chip> = emptyList(),
    val timeChips: List<Chip> = emptyList(),
    val prefilled: Prefilled = Prefilled(false, false, false, false),
    var deleted: Boolean = false,
    /** Added: the calendar editor was opened for it. Its Add then shows "Added" and Add all skips it. */
    var saved: Boolean = false,
    /** The last try to add it failed (no calendar app took it); it can be tried again. */
    var addFailed: Boolean = false,
    val counts: Counts = Counts(),
    /** Whether the review ever showed model chips (a reader of the session can tell code-only from assisted). */
    var modelAssisted: Boolean = false,
    /** The model's suggestions. Shown as chips; they reach a field only through an accept below. */
    var suggestedTitles: List<String> = emptyList(),
    var suggestedDates: List<Chip> = emptyList(),
    /** Try harder's time, if it read one. Same rule: a tap puts it in the field. */
    var suggestedTimes: List<Chip> = emptyList(),
    /** The code's title while the model's title is in the field: one tap ([ReviewSession.undoModelTitle]) puts it back. */
    var codeTitle: String? = null,
    /** The field holds the model's title, put there because the user had not edited the title. */
    var modelTitleApplied: Boolean = false,
    /** The user asked for the location field (Add location); it is shown anyway once it holds a value. */
    var locationShown: Boolean = false,
    /** Added by the user with Add event (the page had more events than were found): nothing prefilled, chips offered. */
    val userAdded: Boolean = false,
) {
    /** The model is working on this event's title (the screen shows a small note). Not saved: a restored screen has no model run. */
    @kotlinx.serialization.Transient
    var titleImproving: Boolean = false

    /** The user is in the title field right now (typing, not yet committed): a model title never replaces it then. */
    @kotlinx.serialization.Transient
    var titleBeingEdited: Boolean = false

    /** The location field is on screen: it holds a value, or the user tapped Add location. */
    val showLocation: Boolean get() = location != null || locationShown

    val date: LocalDate? get() = dateIso?.let(LocalDate::parse)
    val endDate: LocalDate? get() = endDateIso?.let(LocalDate::parse)
    val start: LocalTime? get() = startIso?.let(LocalTime::parse)
    val end: LocalTime? get() = endIso?.let(LocalTime::parse)

    /**
     * A date is set and, if it was flagged, the user confirmed it (a flagged date is never accepted silently), and it was
     * not added already.
     */
    val canAddToCalendar: Boolean get() = !deleted && !saved && dateIso != null && !dateNeedsConfirm && !timeNeedsPick

    fun calendar(): CalendarValues? {
        val d = date ?: return null
        val s = start
        // a missing time is never turned into an all-day event
        if (timeNeedsPick && !allDay && s == null) return null
        if (allDay || s == null) {
            return CalendarValues(title, location, d.atStartOfDay(), (endDate ?: d).plusDays(1).atStartOfDay(), true)
        }
        val begin = d.atTime(s)
        val e = end?.let { d.atTime(it) }?.takeIf { it.isAfter(begin) } ?: begin.plusHours(1)
        return CalendarValues(title, location, begin, e, false)
    }
}

/**
 * The review screen's state: one draft per extracted event (or one empty draft when none was found), the user's
 * edits, and the tap counts for the metrics log. Plain Kotlin; [save] / [restore] keep it across process death.
 */
@Serializable
class ReviewSession(
    val events: MutableList<EventDraft>,
    val noEventFound: Boolean,
    /** Reason codes of events the verifier dropped (for the metrics log). */
    val droppedRules: List<String> = emptyList(),
    /** Try harder ran to the end for this image (found something or not), so it is not offered again. */
    var tryHarderDone: Boolean = false,
    /** The last Add all: how many it added of how many it tried; null before the first. */
    var lastAddAll: AddAllResult? = null,
    /**
     * One note for the whole page, shown above the events: a calendar grid's month ([gridNote]), the years read for dates
     * written with no year ([yearNote]), and that chat times came from nearby messages ([timeNote]); null when there is none.
     */
    val note: String? = null,
    /** No event is prefilled, but the page has candidates it does not settle: offered as chips only, those first. */
    val chipsOnly: Boolean = false,
) {
    fun event(id: Int): EventDraft = events.first { it.id == id }

    private fun edit(id: Int, field: String, typedChars: Int = 0, typedLetters: Int = typedChars, change: EventDraft.() -> Unit) {
        event(id).apply { change(); counts.edits++; counts.typedChars += typedChars; counts.typedLetters += typedLetters; counts.fieldsEdited += field }
    }

    fun editTitle(id: Int, text: String?, typedChars: Int, typedLetters: Int = typedChars) =
        edit(id, "title", typedChars, typedLetters) { title = text?.ifBlank { null }; modelTitleApplied = false }
    fun editLocation(id: Int, text: String?, typedChars: Int, typedLetters: Int = typedChars) =
        edit(id, "location", typedChars, typedLetters) { location = text?.ifBlank { null } }

    /**
     * Typing in a text field that ended with the text it started with: the key taps and the touch into the field still
     * count as taps, but the field is not edited (a model title may still fill an untouched title).
     */
    fun countRevertedTyping(id: Int, typedChars: Int, typedLetters: Int) = event(id).apply {
        counts.revertedEdits++; counts.typedChars += typedChars; counts.typedLetters += typedLetters
    }
    fun setStartTime(id: Int, t: LocalTime?) = edit(id, "start") { startIso = t?.toString(); if (t != null) { allDay = false; timeNeedsPick = false } }
    fun setEndTime(id: Int, t: LocalTime?) = edit(id, "end") { endIso = t?.toString() }
    fun setAllDay(id: Int, value: Boolean) = edit(id, "all_day") { allDay = value; if (value) timeNeedsPick = false }

    /** A date picked by the user is confirmed by that pick. */
    fun setDate(id: Int, d: LocalDate) = edit(id, "date") { dateIso = d.toString(); endDateIso = null; dateNeedsConfirm = false }

    fun tapDateChip(id: Int, chip: Chip) = event(id).apply {
        dateIso = chip.value; endDateIso = chip.end; dateSourceLine = chip.line; dateNeedsConfirm = false
        counts.chipTaps++; counts.fieldsEdited += "date"
    }

    fun tapTimeChip(id: Int, chip: Chip) = event(id).apply {
        startIso = chip.value; endIso = chip.end; timeSourceLine = chip.line; allDay = false; timeNeedsPick = false
        counts.chipTaps++; counts.fieldsEdited += "start"
    }

    /**
     * The model finished: adds its chips to every event still on screen. This is the only thing a model result does on
     * its own. It sets no field and counts no edit.
     */
    fun addSuggestions(titles: List<String>, date: Chip?) {
        if (titles.isEmpty() && date == null) return
        for (e in events) {
            if (e.deleted) continue
            e.suggestedTitles = titles
            e.suggestedDates = listOfNotNull(date)
            e.modelAssisted = true
        }
    }

    /**
     * The model's title for one event ([index] among all events, deleted ones included), offered as a chip after any
     * already there. Not added when the event already holds it or offers it. Sets no field. True if a chip was added.
     */
    fun addPageTitle(index: Int, title: String): Boolean {
        val e = events.getOrNull(index)?.takeIf { !it.deleted } ?: return false
        if (title.equals(e.title, ignoreCase = true) || e.suggestedTitles.any { it.equals(title, ignoreCase = true) }) return false
        e.suggestedTitles = e.suggestedTitles + title
        e.modelAssisted = true
        return true
    }

    /**
     * Try harder finished: its title, date and time are added as chips to every event still on screen, after any chips
     * already there. A value the event already holds, or already offers as a suggestion, is not added again. Sets no field
     * and counts no edit. Returns how many chips were added (0: nothing new).
     */
    fun addTryHarder(title: String?, date: Chip?, time: Chip?): Int {
        tryHarderDone = true
        var added = 0
        for (e in events) {
            if (e.deleted) continue
            val before = added
            if (title != null && !title.equals(e.title, ignoreCase = true) && e.suggestedTitles.none { it.equals(title, ignoreCase = true) }) {
                e.suggestedTitles = e.suggestedTitles + title; added++
            }
            if (date != null && !(date.value == e.dateIso && date.end == e.endDateIso) &&
                e.suggestedDates.none { it.value == date.value && it.end == date.end }) {
                e.suggestedDates = e.suggestedDates + date; added++
            }
            if (time != null && !(time.value == e.startIso && time.end == e.endIso && !e.allDay) &&
                e.suggestedTimes.none { it.value == time.value && it.end == time.end }) {
                e.suggestedTimes = e.suggestedTimes + time; added++
            }
            if (added > before) e.modelAssisted = true
        }
        return added
    }

    /**
     * The model's title for one event ([index] among all events). The field keeps the code's title (with its clear
     * button); a model title that passes [TitleRules.modelTitleOk] against [pageText] and keeps the names in the code's
     * title ([TitleRules.keepsNames]) is offered as a suggestion chip, one tap to use it. A failing title is dropped, and
     * a bill keeps its "Pay ... bill". It never changes the field. True when a new chip was offered.
     */
    fun applyModelTitle(index: Int, title: String, pageText: String): Boolean {
        val e = events.getOrNull(index)?.takeIf { !it.deleted && !it.saved && !it.bill } ?: return false
        val t = title.replace(Regex("""\s+"""), " ").trim()
        if (!TitleRules.modelTitleOk(t, pageText) || t.equals(e.title, ignoreCase = true)) return false
        if (!TitleRules.keepsNames(t, e.codeTitle ?: e.title)) return false
        return addPageTitle(index, t)
    }

    /** One tap: the code's title back in the field; the model's title stays offered as a chip. */
    fun undoModelTitle(id: Int) = event(id).apply {
        if (!modelTitleApplied) return@apply
        val model = title
        title = codeTitle
        modelTitleApplied = false
        if (model != null && suggestedTitles.none { it.equals(model, ignoreCase = true) }) suggestedTitles = suggestedTitles + model
        counts.chipTaps++; counts.fieldsEdited += "title"
    }

    /**
     * Add event: one more draft, empty, for an event the page has but the review did not find. It offers every date and
     * time the page has as chips (in the first event's order), so a missed one is a tap away; nothing is prefilled and no
     * model suggestion is copied. The tap counts as an edit of the new event.
     */
    fun addEvent(): EventDraft {
        val dates = events.flatMap { it.dateChips }.distinctBy { it.label }
        val times = events.flatMap { it.timeChips }.distinctBy { it.label }
        val e = EventDraft(id = (events.maxOfOrNull { it.id } ?: -1) + 1, dateChips = dates, timeChips = times, userAdded = true)
        e.counts.edits++; e.counts.fieldsEdited += "added"
        events += e
        return e
    }

    /** Add location: the empty location field is shown. */
    fun showLocationField(id: Int) = event(id).apply { locationShown = true }

    /** The user tapped a time suggestion: the same as tapping any time chip. */
    fun acceptSuggestedTime(id: Int, chip: Chip) = tapTimeChip(id, chip)

    /**
     * The user cleared the title with the clear button: the heading that was there is offered back as the first title
     * chip, so clearing never loses it (one tap puts it back). Not a model suggestion.
     */
    fun offerClearedTitle(id: Int, text: String) = event(id).apply {
        val t = text.replace(Regex("""\s+"""), " ").trim()
        if (t.isNotEmpty() && suggestedTitles.none { it.equals(t, ignoreCase = true) }) suggestedTitles = listOf(t) + suggestedTitles
    }

    /** The user tapped a title suggestion. */
    fun acceptSuggestedTitle(id: Int, text: String) = event(id).apply {
        title = text
        modelTitleApplied = false
        counts.chipTaps++; counts.fieldsEdited += "title"
    }

    /** The user tapped a date suggestion: the same as tapping any date chip. */
    fun acceptSuggestedDate(id: Int, chip: Chip) = tapDateChip(id, chip)

    fun confirmDate(id: Int) = event(id).apply { dateNeedsConfirm = false; counts.confirms++ }

    fun delete(id: Int) = event(id).apply { deleted = true; counts.deletes++ }

    /** The calendar editor was opened for this event (the user still approves or cancels it there). */
    fun markSaved(id: Int) = event(id).apply { saved = true; addFailed = false }

    /**
     * One event's Add: when it can be added, [open] (the calendar insert, the same for Add and Add all) is called with
     * its values; true marks it added, false marks it failed so it can be tried again. Returns whether it was added.
     */
    fun add(id: Int, open: (CalendarValues) -> Boolean): Boolean {
        val e = event(id)
        if (!e.canAddToCalendar) return false
        val values = e.calendar() ?: return false
        return if (open(values)) { markSaved(id); true } else { e.addFailed = true; false }
    }

    /** Add all is shown only when more than one event is on screen. */
    val showAddAll: Boolean get() = events.count { !it.deleted } > 1

    /** What Add all would add: the events on screen not added yet. */
    val toAdd: List<EventDraft> get() = events.filter { !it.deleted && !it.saved }

    /**
     * Add all is enabled only when every event it would add could be added with its own Add (a date set, every flagged
     * date confirmed). It never adds anything a single Add would not.
     */
    val canAddAll: Boolean get() = toAdd.isNotEmpty() && toAdd.all { it.canAddToCalendar }

    /**
     * Every event not added yet, each through [add] (so the same checks and the same [open]); one that fails does not
     * stop the rest and stays to be tried again. Does nothing unless [canAddAll]. Events go last first, so the calendar
     * editors stack with the first event on top.
     */
    fun addAll(open: (CalendarValues) -> Boolean): AddAllResult? {
        if (!canAddAll) return null
        val tried = toAdd.asReversed().map { it.id }
        val failed = tried.filterNot { add(it, open) }
        return AddAllResult(tried.size - failed.size, tried.size, failed.sorted()).also { lastAddAll = it }
    }

    fun save(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun restore(s: String): ReviewSession = json.decodeFromString(serializer(), s)

        /**
         * The page note for events read from a calendar grid, with the month and year they were read under: "Read from a
         * calendar, November 2026. Check the month." Null when no event came from a grid that read cleanly.
         */
        fun gridNote(r: PipelineResult): String? {
            val months = r.events.mapNotNull { it.event.gridMonth }.distinct().ifEmpty { return null }
            return "Read from a calendar, ${months.joinToString(" and ")}. Check the month."
        }

        /**
         * The page note for dates written with no year, with the years they were read as: "No year is printed; the dates
         * were read as 2026 to 2027. Check the year." Null when every date has its year (or none was resolved).
         */
        /** The page note for a chat whose time came from a message other than the date's, flagged or not. */
        fun timeNote(r: PipelineResult): String? =
            if (r.events.any { it.event.timeFromNearby }) "Times were read from nearby messages. Check them." else null

        fun yearNote(r: PipelineResult): String? {
            val years = r.events.filter { it.resolved.yearInferred }.mapNotNull { it.resolved.start?.year }.ifEmpty { return null }
            val (lo, hi) = years.min() to years.max()
            return "No year is printed; the dates were read as ${if (lo == hi) "$lo" else "$lo to $hi"}. Check the year."
        }

        /**
         * The page note when past dates were left out of a page that still has events: "2 past dates on this page were
         * left out." Not for chats (old messages are history) or bills (an overdue bill has its own handling). Null otherwise.
         */
        fun pastLeftNote(r: PipelineResult): String? {
            if (r.events.isEmpty() || r.cleaned.looksLikeChat) return null
            val route = r.route?.chosen
            if (route == `in`.gauthama.snapslotify.core.extract.PageRoute.CHAT || route == `in`.gauthama.snapslotify.core.extract.PageRoute.BILL) return null
            val n = r.drops.filter { it.reason == "past" && it.date != null }.map { it.date }.distinct().size
            return when (n) { 0 -> null; 1 -> "1 past date on this page was left out."; else -> "$n past dates on this page were left out." }
        }

        /** The note when a page's candidates are offered as chips only. */
        const val CHIPS_NOTE = "Not sure this is an event. Tap a date or time below to add one."

        fun from(r: PipelineResult, today: LocalDate): ReviewSession {
            val c = r.candidates
            val lineTexts = r.cleaned.kept.map { it.text }
            fun one(s: String) = s.replace(Regex("""\s+"""), " ").trim()
            val dueRaw = (c.dates.filter { it.label?.kind == LabelKind.DUE }.map { one(it.raw) } +
                Dates.dueDates(c.text).map { one(it.raw) }).toSet()
            val allDateChips = c.dates.flatMap { d ->
                val res = DateResolver.resolve(d.raw, today, r.yearHints[d.offset] ?: `in`.gauthama.snapslotify.core.resolve.YearHint())
                // a day/month read two ways ("3/4"): one chip per reading, the device's usual order first
                if (res.start == null) res.alternatives.map { a -> Chip("${one(d.raw)} (${a.dayOfMonth} ${a.month.name.lowercase().replaceFirstChar(Char::uppercase).take(3)})", a.toString(), null, d.lineIndex) }
                else listOf(Chip(one(d.raw), res.start.toString(), res.end?.toString(), d.lineIndex))
            } + r.nextWeekday.flatMap { (line, phrase, days) ->
                // "next Thursday": the coming one and the one after, counted from the screenshot's date (else today)
                days.map { a -> Chip("${one(phrase)} (${a.dayOfMonth} ${a.month.name.lowercase().replaceFirstChar(Char::uppercase).take(3)})", a.toString(), null, line) }
            }
            val allTimeChips = (c.times.map { t ->
                Chip(one(t.raw).removeSuffix("."), t.start.toString(), t.end?.toString(), t.lineIndex)
            } + r.extraTimes.map { (raw, t) -> Chip(raw, t.toString(), null, -1) }).distinctBy { it.label }
            fun dateChips(pick: String?, first: Set<String> = dueRaw) = ChipOrder.order(allDateChips.map { it.label }, pick, first)
                .map { l -> allDateChips.first { it.label == l } }
            fun timeChips(pick: String?) = ChipOrder.order(allTimeChips.map { it.label }, pick, emptySet())
                .map { l -> allTimeChips.first { it.label == l } }
            fun lineOf(text: String?): Int? = text?.let { t ->
                val head = t.removePrefix("Pay ").take(12)
                lineTexts.indexOfFirst { it.contains(head) || t.contains(it.trim()) && it.trim().length >= 3 }.takeIf { it >= 0 }
            }

            val drafts = r.events.mapIndexed { i, e ->
                val ev = e.event
                val t = ev.time?.let { TimeParser.parse(it).firstOrNull() }
                val dateChip = allDateChips.firstOrNull { it.label == ev.date }
                    ?: allDateChips.firstOrNull { it.value == e.resolved.start?.toString() }
                val timeChip = allTimeChips.firstOrNull { it.label == ev.time } ?: allTimeChips.firstOrNull { it.value == t?.start?.toString() }
                val relWords = ev.date?.let { Dates.REL_PHRASE.find(it)?.value }
                    ?: Dates.REL_PHRASE.find(c.text)?.value
                val flags = e.flags.filter { it.code != "dropped" }.distinctBy { it.code to FlagText.field(it.code, it.detail) }
                    .map { FlagItem(it.code, FlagText.field(it.code, it.detail), FlagText.sentence(it.code, when (it.code) { "message_date" -> ev.messageDate; "shot_date", "today_date" -> ev.shotDate; else -> relWords })) }
                // the code's title, capped (a bill's "Pay ... bill" is kept whole), clear junk off its ends; words that only
                // may be junk (no vowel, a short or single letter, Roman-numeral or acronym-like) stay, their removal a chip
                val capped = TitleRules.short(ev.title)
                val trimmed = `in`.gauthama.snapslotify.core.text.TitleJunk.trim(capped, r.cleaned.kept)
                val cleaner = `in`.gauthama.snapslotify.core.text.TitleJunk.trim(capped, r.cleaned.kept, tierTwo = true)
                EventDraft(
                    id = i, title = if (ev.bill) ev.title else `in`.gauthama.snapslotify.core.text.TitleLetters.fix(trimmed), dateIso = e.resolved.start?.toString(), endDateIso = e.resolved.end?.toString(),
                    startIso = t?.start?.toString(), endIso = t?.end?.toString(), location = ev.location,
                    allDay = ev.bill || (t == null && flags.none { it.code == "time_missing" }), bill = ev.bill,
                    dateNeedsConfirm = flags.any { it.field == Field.DATE },
                    timeNeedsPick = t == null && flags.any { it.code == "time_missing" },
                    flags = flags, titleSourceLine = lineOf(ev.title), dateSourceLine = dateChip?.line, timeSourceLine = timeChip?.line,
                    locationSourceLine = lineOf(ev.location),
                    // a bill with no clear due date: its candidate dates first
                    dateChips = when {
                        ev.dueCandidates.isNotEmpty() -> dateChips(null, ev.dueCandidates.map(::one).toSet())
                        // a date that asks: its readings first, in order
                        e.resolved.alternatives.isNotEmpty() -> dateChips(null, allDateChips.filter { ch -> e.resolved.alternatives.any { it.toString() == ch.value } && ch.label.startsWith(one(ev.date.orEmpty())) }.map { it.label }.toSet())
                        else -> dateChips(ev.date)
                    },
                    timeChips = timeChips(ev.time),
                    prefilled = Prefilled(ev.title != null, e.resolved.start != null, t != null, ev.location != null),
                ).also { d ->
                    // the page's own heading names something else: offered as a title chip (the title is flagged)
                    ev.titleAlternative?.takeIf { !it.equals(ev.title, ignoreCase = true) }?.let { d.suggestedTitles = listOf(it) }
                    // junk taken off the title's ends: the title as read stays one tap away
                    if (!ev.bill && capped != null && trimmed != capped) d.suggestedTitles = d.suggestedTitles + capped
                    // words that may be junk: the title without them one tap away, the field keeps them
                    val cleaned = cleaner?.takeIf { !ev.bill && it != trimmed }?.let { `in`.gauthama.snapslotify.core.text.TitleLetters.fix(it) }
                    if (cleaned != null && !cleaned.equals(d.title, ignoreCase = true) && d.suggestedTitles.none { it.equals(cleaned, ignoreCase = true) })
                        d.suggestedTitles = d.suggestedTitles + cleaned
                }
            }
            val dropped = r.dropped.map { "no_explicit_date_or_valid_time" }
            if (drafts.isEmpty() && r.ambiguous.isNotEmpty()) {
                // the page does not settle the event: nothing prefilled, its own dates and times first among the chips
                val ds = r.ambiguous.flatMap { a -> listOfNotNull(a.event.date?.let(::one)) + a.event.dueCandidates.map(::one) +
                    allDateChips.filter { ch -> a.resolved.alternatives.any { it.toString() == ch.value } || ch.value == a.resolved.start?.toString() }.map { it.label } }.toSet()
                val ts = r.ambiguous.mapNotNull { it.event.time?.let(::one) }
                return ReviewSession(mutableListOf(EventDraft(id = 0, dateChips = dateChips(null, ds),
                    timeChips = ChipOrder.order(allTimeChips.map { it.label }, ts.firstOrNull(), emptySet()).map { l -> allTimeChips.first { it.label == l } })),
                    true, dropped, note = CHIPS_NOTE, chipsOnly = true)
            }
            if (drafts.isEmpty()) {
                return ReviewSession(mutableListOf(EventDraft(id = 0, dateChips = dateChips(null), timeChips = timeChips(null))), true, dropped, note = r.pastNote)
            }
            return ReviewSession(drafts.toMutableList(), false, dropped, note = listOfNotNull(pastLeftNote(r), gridNote(r), yearNote(r), timeNote(r)).joinToString(" ").ifEmpty { null })
        }
    }
}
