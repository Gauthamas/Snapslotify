package `in`.gauthama.snapslotify.core.verify

import `in`.gauthama.snapslotify.core.extract.Dates
import `in`.gauthama.snapslotify.core.extract.Patterns
import `in`.gauthama.snapslotify.core.extract.RawEvent

data class Flag(val code: String, val detail: String = "") {
    override fun toString() = if (detail.isEmpty()) code else "$code: $detail"
}

/** [after] is null when the event was dropped; [dropReason] then says why. */
data class VerifiedEvent(val before: RawEvent, val after: RawEvent?, val flags: List<Flag>, val dropReason: String?) {
    /** The flags that ask the user to confirm the date (the notebook's DATE_FLAGS). */
    val dateFlags: List<String> get() = flags.map { it.code }.filter { it in Verifier.DATE_FLAGS }
}

/**
 * Port of the notebook's v3 + v3.1 verifier (cells 103, 109), with one change in order: due-date anchoring runs
 * BEFORE the validity check (in v3.1 it ran after, so a bill whose date failed the type check was dropped: H03b).
 *
 * Order: placeholders -> slot type check -> grounding / ambiguity / time checks (on the extractor's values) ->
 * relative_date -> due-date anchoring -> validity. Flags, does not drop, except type-check failures (value becomes
 * null) and events with neither an explicit date nor a valid time. No relative-date arithmetic.
 */
object Verifier {

    val DATE_FLAGS = listOf("relative_date", "date_not_in_text", "ambiguous_date_format", "due_date_mismatch", "multiple_due_dates", "month_grid", "message_date", "multiple_event_dates", "shot_date", "today_date", "route_unsure", "route_date_differs", "grid_unread", "grid_weekday_mismatch", "grid_day_not_read", "grid_day_repeated", "grid_day_unclear", "grid_year_guessed", "due_date_unclear", "day_month_unclear")

    private val MISSING = setOf("", "not specified", "n/a", "none", "unknown", "null")
    private val TIME_VALUE = Patterns.regex(
        """(?<![\d.,])\d{1,2}(?:[:.]\d{2})?\s*(?:[ap]\.?\s?m\b\.?|hrs?\b)|(?<![\d.,])\d{1,2}:\d{2}(?!\d)""",
    )
    private val CLOCK = Patterns.regex("""\b\d{1,2}:\d{2}(?:\s?[ap]\.?m\b\.?)?|\b\d{1,2}\s?[ap]\.?m\b\.?""")
    private val AMPM_OR_HRS = Patterns.regex("""[ap]\.?\s?m\b|\bhrs?\b""")
    private val RELATIVE = Patterns.regex("""\b(today|tonight|tomorrow|yesterday)\b""")

    /** An explicit date in a format [Dates.findDates] parses, or a relative phrase. */
    fun looksLikeDate(v: String?): Boolean = !v.isNullOrEmpty() && (Dates.findDates(v).isNotEmpty() || Dates.REL_PHRASE.containsMatchIn(v))

    /** A clock time or a range of them. */
    fun looksLikeTime(v: String?): Boolean = !v.isNullOrEmpty() && TIME_VALUE.containsMatchIn(v)

    /**
     * [text] is the cleaned (and digit-normalised) OCR text the event came from. [closing]: a due date written as a closing
     * date ("Last date for the form is 30 Nov") on a page that asks for no payment; it never replaces the event's date.
     */
    fun verify(ev: RawEvent, text: String, closing: (String) -> Boolean = { false }): VerifiedEvent {
        val flags = mutableListOf<Flag>()
        val title = placeholder(ev.title, "event", flags)
        var time = placeholder(ev.time, "time", flags)
        var date = placeholder(ev.date, "date", flags)
        val onlyPlaceholders = date == null && time == null

        // Slot type check: a value that is not a date / time becomes null
        if (date != null && !looksLikeDate(date)) { flags += Flag("type_null_date", "'$date'"); date = null }
        if (time != null && !looksLikeTime(time)) { flags += Flag("type_null_time", "'$time'"); time = null }

        // Grounding and ambiguity (on the extractor's date)
        if (date != null) {
            val eventDates = Dates.findDates(date)
            val textDates = Dates.findDates(text)
            val matched = eventDates.flatMap { m -> textDates.filter { Dates.sameDay(m, it) } }
            if (ev.grid) {
                // no flag for being read from a grid (the page has one note for that): only the cell checks that failed
                for (c in ev.gridChecks) flags += Flag(c, "'$date' read from a calendar grid cell")
            } else if (ev.messageDate != null) {
                flags += Flag("message_date", "counted from the message date, ${ev.messageDate}")
            } else if (ev.shotDate != null && ev.anchorIsToday) {
                flags += Flag("today_date", "counted from today's date, ${ev.shotDate}")
            } else if (ev.shotDate != null) {
                flags += Flag("shot_date", "counted from the date of the screenshot, ${ev.shotDate}")
            } else if (eventDates.isEmpty() || !eventDates.all { m -> textDates.any { Dates.sameDay(m, it) } }) {
                flags += Flag("date_not_in_text", "'$date'")
            }
            if ((eventDates + matched).any { it.ambiguous }) flags += Flag("ambiguous_date_format", "'$date' read as DD/MM")
        }
        if (time != null) {
            if (!inText(time, text)) flags += Flag("time_not_in_text", "'$time'")
            else if (!(CLOCK.containsMatchIn(time) || RELATIVE.containsMatchIn(time))) flags += Flag("not_a_time", "'$time'")
        }

        if (time != null && !AMPM_OR_HRS.containsMatchIn(time) && Regex("""\d{1,2}""").find(time)?.value?.toInt() in 1..12) {
            flags += Flag("time_no_ampm", "'$time' has no AM or PM")
        }
        if (ev.timeGuessed && time != null) flags += Flag("time_guess", ev.timeDoubt ?: "'$time' was not found next to the date")
        if (ev.moreTimes) flags += Flag("multiple_events", "more than one time in this chat")
        if (ev.moreDates) flags += Flag("multiple_event_dates", "more than one date in this chat")
        for (r in ev.routeFlags) flags += Flag(r, "the page reads more than one way")
        if (!ev.grid && ev.messageDate == null && ev.shotDate == null && (ev.relativeDay || Dates.isRelativeOnly(text))) flags += Flag("relative_date", "date '$date' kept as an unconfirmed suggestion")

        // A bill whose due date the evidence did not settle: kept with no date, its candidates offered, the page flagged
        if (ev.bill && ev.dueUnclear) {
            return VerifiedEvent(ev, ev.copy(title = title, date = null, time = null), flags + Flag("due_date_unclear", ev.dueCandidates.joinToString(" | ")), null)
        }

        // Due-date anchoring, before validity; not on a bill, whose one date was already chosen by its evidence
        val due = if (ev.bill) emptyList() else Dates.dueDates(text).filterNot { closing(it.raw) }
        if (due.size > 1) {
            flags += Flag("multiple_due_dates", due.joinToString { it.raw })
        } else if (due.size == 1) {
            val d = due.single()
            if (Dates.findDates(date).none { Dates.sameDay(it, d) || Dates.sameDay(d, it) }) {
                flags += Flag("due_date_mismatch", "'$date' -> '${d.raw}'")
                date = d.raw
            }
        }

        // Validity: an explicit parseable date or a valid time
        if (Dates.findDates(date).isEmpty() && time == null) {
            val why = if (onlyPlaceholders) "no date and no time (placeholders only)"
            else "no explicit date and no valid time after the type check" + (date?.let { " (relative date '$it' only)" } ?: "")
            return VerifiedEvent(ev, null, flags + Flag("dropped", why), why)
        }
        return VerifiedEvent(ev, ev.copy(title = title, date = date, time = time), flags, null)
    }

    private fun placeholder(v: String?, field: String, flags: MutableList<Flag>): String? {
        if (v != null && norm(v).trim(' ', '.') in MISSING) {
            if (v.isNotBlank()) flags += Flag("placeholder_$field", "'$v'")
            return null
        }
        return v
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim().lowercase()

    private fun inText(value: String, text: String) = norm(value).trim(' ', '.', ',', ':', ';', '!', '-', '"', '\'') in norm(text)
}
