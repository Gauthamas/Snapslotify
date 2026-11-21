package `in`.gauthama.snapslotify.core.score

import java.time.LocalDate
import java.time.LocalTime

/**
 * One returned event as the scorer sees it. [modelDate] is the extractor's date before the verifier.
 * The resolved fields are used by synthetic keys (ISO dates, HH:MM times); phrase keys use the strings.
 */
data class ScoreEvent(
    val title: String?, val time: String?, val date: String?, val modelDate: String?, val dateFlags: List<String>,
    val start: LocalDate? = null, val end: LocalDate? = null, val modelStart: LocalDate? = null, val startTime: LocalTime? = null,
)

data class PerEvent(
    val title: String?, val modelDate: String?, val date: String?, val dateFlags: List<String>,
    var matched: String? = null, var modelDateOk: Boolean? = null, var finalDateOk: Boolean? = null,
)

data class Score(val label: String, val reasons: List<String>, val perEvent: List<PerEvent> = emptyList())

/** One expected event. */
interface Expect {
    val name: String
    /** True if the date is UNCONFIRMED (null, or kept with relative_date). */
    val unconfirmedDate: Boolean
    /** Reasons the non-date fields do not match (empty = they do). */
    fun nonDateProblems(e: ScoreEvent): List<String>
    fun dateOk(e: ScoreEvent, flags: List<String>): Boolean
    fun modelDateOk(e: ScoreEvent): Boolean
    /** The returned title names this expected event (used to mark dropped events that were real). */
    fun titleMatches(e: ScoreEvent): Boolean

    /** Title rule: true if this event's title may skip the "copied from the text" check (alternative bill rule). */
    fun titleCopyWaived(e: ScoreEvent, rule: TitleRule): Boolean = false
}

/**
 * STRICT: every title must be copied from the text (the notebook rule).
 * PAYEE_FOR_BILLS: proposed post-hoc rule: for bills, the copy check is waived when the payee appears in the title.
 */
enum class TitleRule { STRICT, PAYEE_FOR_BILLS }

/**
 * Port of the notebook's scorer v3 (cell 105) with its helpers (_ws, _clean, _event_problems; cells 17, 18),
 * generalised over the expected-event type ([Expect]).
 * Precedence: invalid_json > phantom > wrong_confident > fail > pass_flagged > pass_with_extra_lines > pass_extra_keys > pass.
 * pending_review is kept in the label list for the table; nothing produces it (no key is a draft now).
 * pass_extra_keys needs keys a model reply had beyond event/time/date; the code extractor has none, so it never occurs.
 */
object Scorer {

    val LABELS = listOf("pass", "pass_extra_keys", "pass_with_extra_lines", "pass_flagged", "fail", "wrong_confident",
        "phantom", "invalid_json", "pending_review")
    val GOOD = setOf("pass", "pass_extra_keys", "pass_flagged")
    val DATE_FLAGS = listOf("relative_date", "date_not_in_text", "ambiguous_date_format", "due_date_mismatch", "multiple_due_dates")
    private const val EXTRA_EVENTS_FAIL = true

    fun ws(s: String?): String = (s ?: "").replace(Regex("\\s+"), " ").trim().lowercase()
    fun clean(s: String) = ws(s).trim(' ', '.', ',', ':', ';', '!', '-', '"', '\'')

    /** Reasons one returned event does not meet one expected event's phrase fields (empty = it does). */
    fun eventProblems(e: ScoreEvent, want: Map<String, List<String>?>): List<String> = want.mapNotNull { (field, phrases) ->
        val vals = ws(when (field) { "event" -> e.title; "time" -> e.time; else -> e.date })
        val ps = phrases ?: return@mapNotNull null
        if (ps.any { ws(it) in vals }) null else "$field: none of $ps found" + if (vals.isEmpty()) " (field missing)" else ""
    }

    fun score(events: List<ScoreEvent>?, key: Key, text: String, rule: TitleRule = TitleRule.STRICT): Score =
        score(events, key.events.map { PhraseExpect(it, key.payeeCases) }, key.forbid, key.flaggedOk, text, rule)

    fun score(
        events: List<ScoreEvent>?, wanted: List<Expect>, forbid: List<String>, flaggedOk: Boolean, text: String,
        rule: TitleRule = TitleRule.STRICT,
    ): Score {
        if (events == null) return Score("invalid_json", listOf("no usable output"))
        val per = events.map { PerEvent(it.title, it.modelDate, it.date, it.dateFlags) }
        if (wanted.isEmpty()) {
            if (events.isEmpty()) return Score("pass", emptyList(), per)
            if (flaggedOk && events.all { "relative_date" in it.dateFlags }) {
                return Score("pass_flagged", listOf("${events.size} event(s), all flagged relative_date"), per)
            }
            return Score("phantom", listOf("no event in the text, but ${events.size} event(s) left after code"), per)
        }

        val reasons = mutableListOf<String>()
        val normText = ws(text)
        for (e in events) {
            if (e.title.isNullOrEmpty() || clean(e.title) in normText) continue
            if (wanted.any { it.titleCopyWaived(e, rule) }) continue
            reasons += "title not copied from the text: '${e.title}'"
        }
        val slots: List<Int?> = events.indices.toList() + List(maxOf(0, wanted.size - events.size)) { null }
        var best: Pair<Int, List<Int?>>? = null
        for (perm in permutations(slots, wanted.size)) {
            val cost = wanted.zip(perm).sumOf { (w, j) ->
                if (j == null) 99 else w.nonDateProblems(events[j]).size + if (w.dateOk(events[j], events[j].dateFlags)) 0 else 1
            }
            if (best == null || cost < best.first) best = cost to perm
        }
        val perm = best!!.second
        val wrongConfident = mutableListOf<String>()
        for ((w, j) in wanted.zip(perm)) {
            if (j == null) {
                reasons += "expected '${w.name}': not returned"
                continue
            }
            val p = per[j]
            p.matched = w.name
            p.finalDateOk = w.dateOk(events[j], events[j].dateFlags)
            p.modelDateOk = if (w.unconfirmedDate) false else w.modelDateOk(events[j])
            reasons += w.nonDateProblems(events[j]).map { "expected '${w.name}' (event ${j + 1}): $it" }
            if (p.finalDateOk == false && events[j].dateFlags.isEmpty()) {
                wrongConfident += "expected '${w.name}' (event ${j + 1}): unflagged wrong date '${events[j].date}'"
            }
        }
        val unmatched = events.indices.filter { it !in perm }
        val extra = if (unmatched.isEmpty()) emptyList()
        else listOf("${unmatched.size} extra event(s): " + unmatched.joinToString("; ") { "'${events[it].title}'" })
        if (EXTRA_EVENTS_FAIL) reasons += extra
        val joined = events.flatMap { listOf(it.title, it.time, it.date) }.filter { !it.isNullOrEmpty() }.joinToString(" | ") { ws(it) }
        reasons += forbid.filter { ws(it) in joined }.map { "contains forbidden '$it'" }

        if (wrongConfident.isNotEmpty()) return Score("wrong_confident", wrongConfident + reasons, per)
        if (reasons.isNotEmpty()) return Score("fail", reasons, per)
        val flaggedMatched = perm.filterNotNull().filter { per[it].dateFlags.isNotEmpty() }
        if (flaggedMatched.isNotEmpty()) return Score("pass_flagged", flaggedMatched.map { "flagged: ${per[it].dateFlags}" } + extra, per)
        if (extra.isNotEmpty()) return Score("pass_with_extra_lines", extra, per)
        return Score("pass", emptyList(), per)
    }

    /** itertools.permutations(slots, r), in the same order (ties in cost keep the first, as in the notebook). */
    private fun permutations(slots: List<Int?>, r: Int): Sequence<List<Int?>> = sequence {
        val used = BooleanArray(slots.size)
        val cur = ArrayList<Int?>()
        suspend fun SequenceScope<List<Int?>>.go() {
            if (cur.size == r) { yield(cur.toList()); return }
            for (i in slots.indices) {
                if (used[i]) continue
                used[i] = true; cur += slots[i]
                go()
                used[i] = false; cur.removeAt(cur.size - 1)
            }
        }
        go()
    }
}

/** A notebook key event: field -> accepted phrases; a null date list is UNCONFIRMED. */
class PhraseExpect(private val want: Map<String, List<String>?>, private val bill: Boolean = false) : Expect {
    override val name: String = want.getValue("event")!!.first()
    override val unconfirmedDate = want.containsKey("date") && want["date"] == null
    override fun nonDateProblems(e: ScoreEvent) = Scorer.eventProblems(e, want - "date")
    override fun titleMatches(e: ScoreEvent) = Scorer.eventProblems(e, mapOf("event" to want["event"])).isEmpty()
    override fun dateOk(e: ScoreEvent, flags: List<String>) = phraseDateOk(e.date, flags)
    override fun modelDateOk(e: ScoreEvent) = phraseDateOk(e.modelDate, emptyList())
    /** Bills only: the title contains an accepted title phrase (for the seen bills these name the payee or the bill). */
    override fun titleCopyWaived(e: ScoreEvent, rule: TitleRule) =
        rule == TitleRule.PAYEE_FOR_BILLS && bill && Scorer.eventProblems(e, mapOf("event" to want["event"])).isEmpty()

    private fun phraseDateOk(value: String?, flags: List<String>): Boolean {
        if (!want.containsKey("date")) return true
        val phrases = want["date"] ?: return value == null || "relative_date" in flags
        return value != null && phrases.any { Scorer.ws(it) in Scorer.ws(value) }
    }
}

/**
 * A synthetic-set key event (dev_expected.json): ISO date (null = unknown from the image), optional end date, optional
 * HH:MM start, generated title, payee for bills.
 * - Title: non-bills: the expected title appears in the returned title. Bills: the payee appears in the returned title
 *   (the expected title "Pay <payee> <type>" is generated and never in the text).
 * - Date: resolved start (and end, when expected) equal the key; null key date = UNCONFIRMED.
 * - Time: resolved start time equals the key's start when the key has one (bills are all-day: no time check).
 */
class SyntheticExpect(
    private val title: String, private val payee: String?, private val date: LocalDate?, private val endDate: LocalDate?,
    private val start: LocalTime?,
) : Expect {
    override val name: String = title
    override val unconfirmedDate = date == null
    private val bill get() = payee != null

    override fun nonDateProblems(e: ScoreEvent): List<String> {
        val out = mutableListOf<String>()
        val want = payee ?: title
        if (Scorer.ws(want) !in Scorer.ws(e.title)) out += "event: '${if (bill) "payee " else ""}$want' not in title '${e.title}'"
        if (start != null && e.startTime != start) out += "time: expected $start, got ${e.startTime ?: "none"} ('${e.time}')"
        return out
    }

    override fun titleMatches(e: ScoreEvent) = Scorer.ws(payee ?: title) in Scorer.ws(e.title)

    override fun dateOk(e: ScoreEvent, flags: List<String>): Boolean =
        if (date == null) e.start == null && e.date == null || "relative_date" in flags
        else e.start == date && (endDate == null || e.end == endDate)

    override fun modelDateOk(e: ScoreEvent) = date != null && e.modelStart == date

    override fun titleCopyWaived(e: ScoreEvent, rule: TitleRule) =
        rule == TitleRule.PAYEE_FOR_BILLS && bill && Scorer.ws(payee) in Scorer.ws(e.title)
}
