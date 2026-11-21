package `in`.gauthama.snapslotify.stats

/**
 * Every tap cost the "taps saved" estimate uses, in one place, to correct after measuring on the phone (OPPO A17).
 *
 * Manual: adding the event by hand in the calendar app, from the screenshot. With the app: the review form and the
 * calendar editor the app opens prefilled. Both are counted from inside the app: getting to the calendar app by hand and
 * getting to the review screen (share sheet, or open the app and pick) cost about the same, so neither side counts it.
 */
object TapCosts {
    // ---- manual, without the app (each event pays all of it), counted from inside the calendar app ----
    /** Leaving the screenshot for the calendar: 0, as if the calendar app were already open (see the class note). */
    const val LEAVE_APP = 0
    /** Opening the calendar app: 0, as if it were already open (see the class note). */
    const val OPEN_CALENDAR = 0
    const val ADD = 1
    const val DATE_PICKER_OPEN = 1
    const val DATE_DAY = 1
    const val DATE_CONFIRM = 1
    /** Per month the date picker must move forward from the current month. */
    const val MONTH_STEP = 1
    const val TIME_PICKER_OPEN = 1
    const val TIME_HOUR = 1
    const val TIME_MINUTE = 1
    /** AM/PM in the time picker: only on a phone set to the 12-hour clock (a 24-hour clock has no AM/PM step). */
    const val TIME_AM_PM = 1
    const val TIME_CONFIRM = 1
    /**
     * The length a calendar app gives a new event by itself (Google Calendar: 1 hour). An end time at any other length
     * pays the end-time picker by hand: open, hour, minute, AM/PM on a 12-hour clock, confirm.
     */
    const val DEFAULT_EVENT_MINUTES = 60L
    /** An all-day event: the All-day switch instead of the time picker (not in the original list; set 0 to drop). */
    const val ALL_DAY_TOGGLE = 1
    const val SAVE = 1
    const val TITLE_FIELD = 1
    const val LOCATION_FIELD = 1
    const val PER_CHARACTER = 1
    /**
     * Added on top of the typed characters for shift and punctuation, rounded up: by hand on the final title and location,
     * and with the app on the letters the user typed one key at a time (so a title retyped in full saves nothing; a
     * backspace pays none).
     */
    const val CHARACTER_OVERHEAD_PERCENT = 10

    // ---- with the app ----
    /**
     * One keyboard step that changes several characters at once: a suggestion or autocorrect, a paste, an undo, glide
     * typing, text typed over a selection. A step of one character (a letter, a backspace) is [PER_CHARACTER].
     */
    const val MULTI_CHARACTER_STEP = 1
    /**
     * Getting to the review screen, once per page (Share, Snapslotify in the share sheet; or open Snapslotify, pick the
     * screenshot): 0, as if the review screen were already open, matching the manual side, which starts in the calendar app.
     */
    const val SHARE_OR_OPEN = 0
    /** Done on the confirmation screen after Add, once per page. */
    const val CONFIRM_DONE = 1
    /** Each event's own Add (also for each event of Add all). */
    const val APP_ADD = 1
    /** Save in the calendar editor the app opens prefilled (still a real tap; set 0 to drop). */
    const val CALENDAR_SAVE = 1
    /** The form's date picker: the field tap, the day, OK. */
    const val FORM_DATE_PICKER = 3
    /** The form's time picker: the field tap, hour, minute, AM/PM, OK (less [TIME_AM_PM] on a 24-hour clock). */
    const val FORM_TIME_PICKER = 5
    // the form's date picker also pays MONTH_STEP per month it moves from the date it opened on

    // ---- the home screen's example of a typical event ----
    const val EXAMPLE_TITLE_CHARS = 20
    const val EXAMPLE_LOCATION_CHARS = 15
    /** The example event is this many calendar months ahead. */
    const val EXAMPLE_MONTHS_AHEAD = 1
}
