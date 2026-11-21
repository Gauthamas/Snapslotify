package `in`.gauthama.snapslotify.core.review

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The rule: a model result changes no field until the user accepts it, and never overwrites what the user typed. */
class SuggestionRuleTest {

    private val chip = Chip("monday", "2026-11-02", null, 3)

    private fun session(vararg events: EventDraft) = ReviewSession(events.toMutableList(), false)
    private fun draft(id: Int = 0) = EventDraft(
        id, title = "Code title", dateIso = "2026-10-31", startIso = "20:00", location = "Hall", allDay = false, dateNeedsConfirm = true,
    )

    /** Everything in an event except the chip lists themselves, as text. */
    private fun fields(e: EventDraft) = e.copy(suggestedTitles = emptyList(), suggestedDates = emptyList(), suggestedTimes = emptyList(), modelAssisted = false).toString()

    @Test fun addingSuggestionsChangesNoField() {
        val s = session(draft())
        val before = fields(s.event(0))
        s.addSuggestions(listOf("Dinner"), chip)
        assertEquals(before, fields(s.event(0)))
        assertEquals("Code title", s.event(0).title)
        assertEquals("2026-10-31", s.event(0).dateIso)
        assertTrue(s.event(0).dateNeedsConfirm)
    }

    @Test fun addingSuggestionsCountsNoEdit() {
        val s = session(draft())
        s.addSuggestions(listOf("Dinner"), chip)
        val c = s.event(0).counts
        assertEquals(0, c.taps)
        assertEquals(0, c.typedChars)
        assertTrue(c.fieldsEdited.isEmpty())
    }

    @Test fun aSuggestionDoesNotFillAnEmptyField() {
        val s = ReviewSession(mutableListOf(EventDraft(id = 0)), true)
        s.addSuggestions(listOf("Dinner"), chip)
        assertEquals(null, s.event(0).title)
        assertEquals(null, s.event(0).dateIso)
        assertFalse(s.event(0).canAddToCalendar)
    }

    @Test fun whatTheUserTypedStays() {
        val s = session(draft())
        s.editTitle(0, "My title", 8)
        s.setDate(0, LocalDate.of(2026, 12, 25))
        s.addSuggestions(listOf("Dinner"), chip)
        assertEquals("My title", s.event(0).title)
        assertEquals("2026-12-25", s.event(0).dateIso)
    }

    @Test fun acceptingATitleChangesOnlyTheTitle() {
        val s = session(draft())
        s.addSuggestions(listOf("Dinner"), chip)
        s.acceptSuggestedTitle(0, "Dinner")
        assertEquals("Dinner", s.event(0).title)
        assertEquals("2026-10-31", s.event(0).dateIso)
        assertTrue(s.event(0).dateNeedsConfirm)
        assertEquals(1, s.event(0).counts.chipTaps)
    }

    @Test fun acceptingADateChangesOnlyTheDate() {
        val s = session(draft())
        s.addSuggestions(listOf("Dinner"), chip)
        s.acceptSuggestedDate(0, chip)
        assertEquals("2026-11-02", s.event(0).dateIso)
        assertEquals("Code title", s.event(0).title)
        assertFalse(s.event(0).dateNeedsConfirm)
    }

    @Test fun nothingToSuggestLeavesTheSessionAsItWas() {
        val s = session(draft())
        s.addSuggestions(emptyList(), null)
        assertFalse(s.event(0).modelAssisted)
        assertTrue(s.event(0).suggestedTitles.isEmpty())
    }

    @Test fun aDeletedEventGetsNoChips() {
        val s = session(draft(0), draft(1))
        s.delete(0)
        s.addSuggestions(listOf("Dinner"), chip)
        assertTrue(s.event(0).suggestedTitles.isEmpty())
        assertEquals(listOf("Dinner"), s.event(1).suggestedTitles)
    }

    @Test fun chipsSurviveProcessDeathAndOldSessionsStillLoad() {
        val s = session(draft())
        s.addSuggestions(listOf("Dinner"), chip)
        val back = ReviewSession.restore(s.save())
        assertEquals(listOf("Dinner"), back.event(0).suggestedTitles)
        assertEquals(listOf(chip), back.event(0).suggestedDates)

        // A session saved before chips existed has none of these keys.
        val old = Json.parseToJsonElement(session(draft()).save()).toString()
            .replace(Regex(""","suggestedTitles":\[[^\]]*\]"""), "").replace(Regex(""","suggestedDates":\[[^\]]*\]"""), "")
        assertTrue(ReviewSession.restore(old).event(0).suggestedTitles.isEmpty())
    }
}
