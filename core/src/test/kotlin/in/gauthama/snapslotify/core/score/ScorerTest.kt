package `in`.gauthama.snapslotify.core.score

import org.junit.Assert.assertEquals
import org.junit.Test

/** The notebook's hand-written scorer checks (cell 105), with events built directly instead of from a model reply. */
class ScorerTest {

    private val key = Key(listOf(mapOf("event" to listOf("Lunch"), "time" to listOf("1 PM"), "date" to listOf("5 Nov"))))
    private val text = "Lunch on 5 Nov at 1 PM"

    /** One event; the date flags are what the verifier would raise for this text. */
    private fun one(title: String, time: String?, date: String?, flags: List<String> = emptyList()) =
        listOf(ScoreEvent(title, time, date, modelDate = date, dateFlags = flags))

    @Test
    fun notebookChecks() {
        assertEquals("pass", Scorer.score(one("Lunch", "1 PM", "5 Nov"), key, text).label)
        assertEquals("pass_flagged", Scorer.score(one("Lunch", "1 PM", "6 Nov", listOf("date_not_in_text")), key, text).label)
        assertEquals("pass_flagged", Scorer.score(one("Lunch", "1 PM", "Nov", listOf("date_not_in_text")), key, text).label)
        assertEquals("fail", Scorer.score(one("Dinner", "1 PM", "5 Nov"), key, text).label)
        assertEquals("fail", Scorer.score(emptyList(), key, text).label)
        assertEquals("invalid_json", Scorer.score(null, key, text).label)
        assertEquals("wrong_confident", Scorer.score(one("Lunch", "1 PM", "6 Nov"), key, "Lunch on 5 Nov at 1 PM, or 6 Nov").label)
    }

    @Test
    fun noEventKeys() {
        val none = Key(emptyList())
        assertEquals("pass", Scorer.score(emptyList(), none, text).label)
        assertEquals("phantom", Scorer.score(one("Lunch", "1 PM", "5 Nov"), none, text).label)
        val flaggedOk = Key(emptyList(), flaggedOk = true)
        assertEquals("pass_flagged", Scorer.score(one("Lunch", null, "tomorrow", listOf("relative_date")), flaggedOk, text).label)
        assertEquals("phantom", Scorer.score(one("Lunch", null, "5 Nov"), flaggedOk, text).label)
    }

    @Test
    fun unconfirmedDate() {
        val k = Key(listOf(mapOf("event" to listOf("tap"), "time" to listOf("6 pm"), "date" to null)))
        val t = "fix the tap tomorrow at 6 pm"
        assertEquals("pass", Scorer.score(one("fix the tap", "6 pm", null), k, t).label)
        assertEquals("pass_flagged", Scorer.score(one("fix the tap", "6 pm", "tomorrow", listOf("relative_date")), k, t).label)
        assertEquals("wrong_confident", Scorer.score(one("fix the tap", "6 pm", "tomorrow"), k, t).label)
    }

    @Test
    fun titleMustBeCopiedAndExtraEventsFail() {
        assertEquals("fail", Scorer.score(one("Lunch with Ravi", "1 PM", "5 Nov"), key, text).label)
        val two = one("Lunch", "1 PM", "5 Nov") + one("on", null, "5 Nov")
        assertEquals("fail", Scorer.score(two, key, text).label)
        val forbid = Key(key.events, forbid = listOf("1 PM"))
        assertEquals("fail", Scorer.score(one("Lunch", "1 PM", "5 Nov"), forbid, text).label)
    }

    @Test
    fun pairsEventsOneToOne() {
        val k = Key(listOf(mapOf("event" to listOf("Sprint planning"), "date" to listOf("26 Oct")),
            mapOf("event" to listOf("Retro"), "date" to listOf("30 Oct"))))
        val t = "Sprint planning Mon 26 Oct\nRetro Fri 30 Oct"
        val good = one("Sprint planning", null, "Mon 26 Oct") + one("Retro", null, "Fri 30 Oct")
        assertEquals("pass", Scorer.score(good.reversed(), k, t).label)
        val swapped = one("Sprint planning", null, "Fri 30 Oct") + one("Retro", null, "Mon 26 Oct")
        assertEquals("wrong_confident", Scorer.score(swapped, k, t).label)
    }
}
