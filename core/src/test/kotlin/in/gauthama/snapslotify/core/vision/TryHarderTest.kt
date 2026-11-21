package `in`.gauthama.snapslotify.core.vision

import `in`.gauthama.snapslotify.core.review.Chip
import `in`.gauthama.snapslotify.core.review.EventDraft
import `in`.gauthama.snapslotify.core.review.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TryHarderTest {

    private val today = LocalDate.of(2026, 11, 3)

    private class Fake(
        val ready: Boolean = true, val answer: String? = null, val sleepMs: Long = 0, val throws: Boolean = false,
    ) : VisionModel {
        var reads = 0
        var cancels = 0
        var lastSize = 0 to 0
        var lastPrompt = ""
        override fun warmUp() = ready
        override fun read(rgb: ByteArray, width: Int, height: Int, prompt: String): String {
            reads++; lastSize = width to height; lastPrompt = prompt
            if (sleepMs > 0) Thread.sleep(sleepMs)
            if (throws) error("native failure")
            return answer!!
        }
        override fun cancel() { cancels++ }
    }

    private fun run(m: VisionModel, readTimeout: Long = 2_000) = TryHarder(m, 2_000, readTimeout).run(ByteArray(12), 2, 2, today)

    @Test
    fun sizeShrinksTheLongSideTo288AndNeverEnlarges() {
        assertEquals(130 to 288, VisionPrompt.size(1080, 2400))
        assertEquals(288 to 204, VisionPrompt.size(1600, 1131))
        assertEquals(200 to 100, VisionPrompt.size(200, 100))
    }

    @Test
    fun answerBecomesChipsThroughTheCodeParsers() {
        val m = Fake(answer = """{"title": "Jazz Night", "date_text": "Friday the 20th of November", "time_text": "7:30 PM"}""")
        val r = run(m)
        assertEquals(TryHarderOutcome.OK, r.outcome)
        assertEquals("Jazz Night", r.title)
        assertEquals(Chip("Friday the 20th of November", "2026-11-20", null, -1), r.dateChip)
        assertEquals(Chip("7:30 PM", "19:30", null, -1), r.timeChip)
        assertEquals(VisionPrompt.PROMPT, m.lastPrompt)
    }

    @Test
    fun notStatedAndUnreadableValuesAreDropped() {
        val r = TryHarder.parse("""{"title": "not stated", "date_text": "soon", "time_text": "N/A"}""", today)
        assertEquals(TryHarderOutcome.NOTHING, r.outcome)
        assertNull(r.title); assertNull(r.dateChip); assertNull(r.timeChip)
        assertEquals("soon", r.dateText)
    }

    @Test
    fun textAroundTheJsonAndARangeAreRead() {
        val r = TryHarder.parse("Here it is:\n```json\n{\"title\":\"Book Fair\",\"date_text\":\"12-14 Dec 2026\",\"time_text\":\"10 am - 6 pm\"}\n```", today)
        assertEquals("Book Fair", r.title)
        assertEquals("2026-12-12", r.dateChip?.value)
        assertEquals("2026-12-14", r.dateChip?.end)
        assertEquals("10:00", r.timeChip?.value)
        assertEquals("18:00", r.timeChip?.end)
    }

    @Test
    fun noJsonIsInvalidAndALongTitleIsDropped() {
        assertEquals(TryHarderOutcome.INVALID, TryHarder.parse("I cannot read this poster.", today).outcome)
        val long = TryHarder.parse("""{"title": "${"word ".repeat(12)}", "date_text": "DEC19", "time_text": "not stated"}""", today)
        assertNull(long.title)
        assertEquals("2026-12-19", long.dateChip?.value)
    }

    @Test
    fun failuresGiveNoChips() {
        assertEquals(TryHarderOutcome.FAILED, run(Fake(ready = false)).outcome)
        assertEquals(TryHarderOutcome.FAILED, run(Fake(throws = true)).outcome)
        val slow = Fake(answer = "{}", sleepMs = 3_000)
        val r = run(slow, readTimeout = 200)
        assertEquals(TryHarderOutcome.TIMEOUT, r.outcome)
        assertTrue(slow.cancels > 0)
    }

    @Test
    fun cancelBeforeTheReadSkipsIt() {
        val m = Fake(answer = "{}")
        val t = TryHarder(m)
        t.cancel()
        assertEquals(TryHarderOutcome.CANCELLED, t.run(ByteArray(12), 2, 2, today).outcome)
        assertEquals(0, m.reads)
    }

    @Test
    fun sessionAddsOnlyNewChipsAndNeverAField() {
        val s = ReviewSession(mutableListOf(EventDraft(0, title = "Jazz Night", dateIso = "2026-11-20")), false)
        val before = s.event(0).copy(suggestedTitles = emptyList(), suggestedDates = emptyList(), suggestedTimes = emptyList()).toString()
        val time = Chip("7:30 PM", "19:30", null, -1)
        // same title and same date as the fields: only the time is new
        assertEquals(1, s.addTryHarder("jazz night", Chip("Nov 20", "2026-11-20", null, -1), time))
        val e = s.event(0)
        assertEquals(listOf(time), e.suggestedTimes)
        assertTrue(e.suggestedTitles.isEmpty() && e.suggestedDates.isEmpty())
        assertTrue(e.modelAssisted && s.tryHarderDone)
        assertEquals(before, e.copy(suggestedTitles = emptyList(), suggestedDates = emptyList(), suggestedTimes = emptyList(), modelAssisted = false).toString())
        assertEquals(0, e.counts.taps)
        // again: nothing new
        assertEquals(0, s.addTryHarder(null, null, time))
        s.acceptSuggestedTime(0, time)
        assertEquals("19:30", s.event(0).startIso)
        assertFalse(s.event(0).allDay)
        // survives process death
        val back = ReviewSession.restore(s.save())
        assertTrue(back.tryHarderDone)
        assertEquals(listOf(time), back.event(0).suggestedTimes)
    }

    @Test
    fun nothingFoundStillMarksItDone() {
        val s = ReviewSession(mutableListOf(EventDraft(0)), true)
        assertEquals(0, s.addTryHarder(null, null, null))
        assertTrue(s.tryHarderDone)
        assertFalse(s.event(0).modelAssisted)
    }
}
