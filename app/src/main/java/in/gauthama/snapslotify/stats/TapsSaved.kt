package `in`.gauthama.snapslotify.stats

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * The "taps saved" estimate for events actually added ([TapCosts] holds every cost). Plain Kotlin, no Android.
 *
 * Both sides are counted from inside the app ([TapCosts]: getting to the calendar app or to the review screen is 0).
 *
 * Manual cost of one event (always the full cost, also when several come from one page): add, the date picker (and the
 * end-date picker for an event over several days: open, day, confirm, plus one per month from the start date) (open, day, confirm, plus one per month forward), the time picker (open, hour, minute,
 * AM/PM only on a 12-hour clock [amPm], confirm; again for an end time the calendar would not set by itself) or the All-day switch, save; the title field plus one per character of the final title; the location
 * field plus one per character when a location is filled; plus [TapCosts.CHARACTER_OVERHEAD_PERCENT] of those characters,
 * rounded up.
 *
 * With the app: Done on the confirmation once per page, then per event its real touches on the review form (edits, chip
 * taps, confirms, picker touches), its key taps ([TypingCounter]: one per letter or backspace, one per suggestion, paste
 * or undo) plus the same overhead on the letters typed, its Add, and Save in the calendar editor. Accepting the prefilled title and location costs nothing for them; a title retyped in full
 * costs what typing it by hand does, so it saves nothing.
 */
object TapsSaved {

    /** One event as it was added: the final values and what the user did in the form. */
    data class Added(
        val title: String?, val location: String?, val date: LocalDate, val timed: Boolean,
        /** Real touches on the review form for this event (edits, chip taps, confirms, deletes, picker touches). */
        val formTouches: Int,
        /** Key taps the user made in the text fields for this event ([TypingCounter]). */
        val typedChars: Int,
        /** Of those, letters typed one key at a time: they pay the shift and punctuation overhead. */
        val typedLetters: Int = typedChars,
        /** A timed event whose end is not [TapCosts.DEFAULT_EVENT_MINUTES] after its start: by hand it needs the end picker. */
        val endSet: Boolean = false,
        /** The last day of an event over several days: by hand it needs the end-date picker, which opens on [date]. */
        val endDate: LocalDate? = null,
    )

    /**
     * [saved] is by hand minus with the app, and may be below 0 (an event that needed a lot of fixing): totals keep the
     * true sum, so they add up; only what is shown is floored at 0 ([shown]).
     */
    data class Estimate(val events: Int, val manualTaps: Int, val appTaps: Int, val saved: Int, val charsNotTyped: Int) {
        /** The saving shown on screen: never below 0. */
        val shownSaved: Int get() = shown(saved)
    }

    /** A saving as it is shown: never below 0. */
    fun shown(saved: Int): Int = max(0, saved)

    fun monthsAhead(date: LocalDate, today: LocalDate): Int =
        max(0, (date.year - today.year) * 12 + date.monthValue - today.monthValue)

    /** Touches in the form's date picker beyond its one edit: the day, OK, and one per month moved from [from] to [to]. */
    fun formDatePick(from: LocalDate, to: LocalDate): Int =
        TapCosts.FORM_DATE_PICKER - 1 + TapCosts.MONTH_STEP * abs((to.year - from.year) * 12 + to.monthValue - from.monthValue)

    /** Shift and punctuation on top of [chars] typed characters, rounded up. */
    fun overhead(chars: Int): Int = ceil(chars * TapCosts.CHARACTER_OVERHEAD_PERCENT / 100.0).toInt()


    /** Characters of the final title and location: what the user would type by hand. */
    fun characters(e: Added): Int = (e.title?.length ?: 0) + (e.location?.trim()?.takeIf { it.isNotEmpty() }?.length ?: 0)

    /** [amPm]: the phone uses the 12-hour clock, so the calendar's time picker has an AM/PM step. */
    fun manual(e: Added, today: LocalDate, amPm: Boolean = true): Int = with(TapCosts) {
        val endDay = e.endDate?.takeIf { it.isAfter(e.date) }
            ?.let { DATE_PICKER_OPEN + DATE_DAY + DATE_CONFIRM + MONTH_STEP * monthsAhead(it, e.date) } ?: 0
        val picker = TIME_PICKER_OPEN + TIME_HOUR + TIME_MINUTE + (if (amPm) TIME_AM_PM else 0) + TIME_CONFIRM
        val base = LEAVE_APP + OPEN_CALENDAR + ADD + DATE_PICKER_OPEN + DATE_DAY + DATE_CONFIRM + MONTH_STEP * monthsAhead(e.date, today) + endDay +
            (if (e.timed) picker + (if (e.endSet) picker else 0) else ALL_DAY_TOGGLE) + SAVE
        val titleChars = e.title?.length ?: 0
        val locationChars = e.location?.trim()?.takeIf { it.isNotEmpty() }?.length ?: 0
        base + TITLE_FIELD + PER_CHARACTER * titleChars + (if (locationChars > 0) LOCATION_FIELD + PER_CHARACTER * locationChars else 0) +
            overhead(titleChars + locationChars)
    }

    /** With the app, for one event; [firstOnPage] carries the page's share or open step and the confirmation's Done. */
    fun withApp(e: Added, firstOnPage: Boolean): Int =
        (if (firstOnPage) TapCosts.SHARE_OR_OPEN + TapCosts.CONFIRM_DONE else 0) + e.formTouches + e.typedChars + overhead(e.typedLetters) +
            TapCosts.APP_ADD + TapCosts.CALENDAR_SAVE

    /** One event, [firstOnPage] when it is the first added from its page. Saved may be below 0; see [Estimate]. */
    fun estimate(e: Added, today: LocalDate, firstOnPage: Boolean, amPm: Boolean = true): Estimate {
        val m = manual(e, today, amPm)
        val a = withApp(e, firstOnPage)
        return Estimate(1, m, a, m - a, max(0, characters(e) - e.typedLetters))
    }

    /** The events added from one page: the share or open step counted once. */
    fun page(events: List<Added>, today: LocalDate, amPm: Boolean = true): Estimate =
        events.mapIndexed { i, e -> estimate(e, today, i == 0, amPm) }.fold(Estimate(0, 0, 0, 0, 0)) { s, x -> s + x }

    /**
     * The review screen's line for one page: the events already added from it and what they saved, and what the events
     * ready to add (not added yet) would save on top. The share or open step is counted once, with the first event.
     */
    data class PageLine(val added: Int, val saved: Int, val ready: Int, val wouldSave: Int)

    fun pageLine(added: List<Added>, ready: List<Added>, today: LocalDate, amPm: Boolean = true): PageLine {
        val done = page(added, today, amPm)
        val all = page(added + ready, today, amPm)
        return PageLine(added.size, done.saved, ready.size, all.saved - done.saved)
    }

    /** The home screen's example: a typical event ([TapCosts.EXAMPLE_TITLE_CHARS]...), accepted as prefilled. */
    fun example(today: LocalDate, amPm: Boolean = true): Estimate = estimate(
        Added("x".repeat(TapCosts.EXAMPLE_TITLE_CHARS), "x".repeat(TapCosts.EXAMPLE_LOCATION_CHARS),
            today.withDayOfMonth(1).plusMonths(TapCosts.EXAMPLE_MONTHS_AHEAD.toLong()), timed = true, formTouches = 0, typedChars = 0),
        today, firstOnPage = true, amPm = amPm)

    operator fun Estimate.plus(o: Estimate) =
        Estimate(events + o.events, manualTaps + o.manualTaps, appTaps + o.appTaps, saved + o.saved, charsNotTyped + o.charsNotTyped)
}
