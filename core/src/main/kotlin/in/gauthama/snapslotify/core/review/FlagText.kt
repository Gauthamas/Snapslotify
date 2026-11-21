package `in`.gauthama.snapslotify.core.review

/** The review-screen field a flag belongs to. */
enum class Field { TITLE, DATE, TIME, LOCATION }

/** Each flag code as one plain sentence for the review screen, and the field it is shown under. */
object FlagText {

    val KNOWN = listOf(
        "relative_date", "date_not_in_text", "ambiguous_date_format", "due_date_mismatch", "multiple_due_dates",
        "weekday_mismatch", "year_not_stated", "past_date", "low_ocr_confidence", "type_null_date", "type_null_time",
        "time_not_in_text", "not_a_time", "placeholder_event", "placeholder_date", "placeholder_time", "message_date", "multiple_events", "multiple_event_dates", "time_guess", "time_no_ampm", "shot_date", "today_date", "numeric_no_year", "page_turned",
        "route_unsure", "route_date_differs", "route_time_differs",
        "grid_unread", "grid_weekday_mismatch", "grid_day_not_read", "grid_day_repeated", "grid_day_unclear", "grid_year_guessed",
        "grid_entry_unclear", "due_date_unclear", "day_month_unclear", "stray_mark_removed", "title_unsure", "time_missing",
    )

    fun field(code: String): Field = when (code) {
        "type_null_time", "time_not_in_text", "not_a_time", "placeholder_time", "multiple_events", "time_guess", "time_no_ampm", "route_time_differs", "time_missing" -> Field.TIME
        "placeholder_event", "grid_entry_unclear", "title_unsure" -> Field.TITLE
        "low_ocr_confidence" -> Field.TITLE
        else -> Field.DATE
    }

    /**
     * As [field], but low_ocr_confidence and stray_mark_removed name their field in [detail] ("DATE" or "TIME": a doubtful
     * digit there, or a stray mark taken off its number).
     */
    fun field(code: String, detail: String?): Field =
        if (code == "low_ocr_confidence" || code == "stray_mark_removed") Field.entries.firstOrNull { it.name == detail } ?: field(code) else field(code)

    /** [value]: for relative_date, the relative words in the text ("tomorrow"), if known; for message_date, that date line. */
    fun sentence(code: String, value: String?): String = when (code) {
        "relative_date" -> if (value.isNullOrBlank()) "The text only gives a relative day, not a date. Please pick the date."
        else "The text says '$value'. Is this date right?"
        "date_not_in_text" -> "This date was not found in the text. Is it right?"
        "message_date" -> if (value.isNullOrBlank()) "This day was counted from the message date. Is it right?"
        else "Counted from the message date, $value. Is this date right?"
        "shot_date" -> if (value.isNullOrBlank()) "This day was counted from the date of the screenshot. Is this right?"
        else "Counted from the date of the screenshot, $value. Is this right?"
        "numeric_no_year" -> "The date has no year, so the next one ahead was assumed. Is it right?"
        "page_turned" -> "The photo looks turned and could not be read upright. Please check every field."
        "route_unsure" -> "This page could be read more than one way. Please check the date and time."
        "route_date_differs" -> "Read another way, this page gives a different date here. Is this one right?"
        "route_time_differs" -> "Read another way, this page gives a different time here. Is this one right?"
        "today_date" -> "Counted from today. Is this right?"
        "time_no_ampm" -> "This time has no AM or PM. Is it right?"
        "time_guess" -> "This time was not found next to the date. Is it right?"
        "time_missing" -> "No time was read for this event. Pick the time below, or tick All day."
        "multiple_events", "multiple_event_dates" -> "More than one date or time found in this chat, only one is shown."
        "month_grid" -> "This day was read from a calendar grid. Is it the right day?"
        "grid_unread" -> "This page looks like a calendar, but its grid could not be read cleanly. Is this date right?"
        "grid_weekday_mismatch" -> "This date's weekday is not the one at the top of its column. Is the date right?"
        "grid_day_not_read" -> "The day number in this box could not be read, so the date comes from its place in the grid. Is it right?"
        "grid_day_repeated" -> "This day number appears more than once on the calendar. Is the date right?"
        "grid_day_unclear" -> "The day number in this box was hard to read. Is the date right?"
        "grid_year_guessed" -> "The calendar shows no year, so the year was worked out from the layout. Is it right?"
        "grid_entry_unclear" -> "The writing in this calendar box was hard to read. Please check the title."
        "due_date_unclear" -> "This bill does not make one date clearly the due date. Please pick it from the dates below."
        "day_month_unclear" -> "This date could be read with the day or the month first. Please pick it from the dates below."
        "ambiguous_date_format" -> "Day and month could be swapped (DD/MM is assumed). Is this date right?"
        "due_date_mismatch" -> "The bill's due date was used instead of another date. Is it right?"
        "multiple_due_dates" -> "The bill shows more than one due date. Is this the right one?"
        "weekday_mismatch" -> "The weekday in the text does not match this date. Is it right?"
        "year_not_stated" -> "The text gives no year, so the year was worked out from the page. Is it right?"
        "past_date" -> "This date is in the past. Is it right?"
        "low_ocr_confidence" -> "The text here was hard to read. Please check it."
        "stray_mark_removed" -> "A mark before this number was read as an extra digit and taken off. Is it right?"
        "title_unsure" -> "The event's name was not clear on the page. Please check the title."
        "type_null_date" -> "What was found did not look like a date, so it was left empty."
        "type_null_time" -> "What was found did not look like a time, so it was left empty."
        "time_not_in_text" -> "This time was not found in the text. Is it right?"
        "not_a_time" -> "This does not look like a clock time. Please check it."
        "placeholder_event", "placeholder_date", "placeholder_time" -> "The text gives no value here."
        else -> "Please check this field."
    }
}

/** Chip order: the extractor's pick, then due-anchored values, then the rest in text order; no duplicates. */
object ChipOrder {
    fun <T> order(values: List<T>, pick: T?, due: Set<T>): List<T> {
        val distinct = values.distinct()
        return listOfNotNull(pick?.takeIf { it in distinct }) +
            distinct.filter { it != pick && it in due } +
            distinct.filter { it != pick && it !in due }
    }
}
