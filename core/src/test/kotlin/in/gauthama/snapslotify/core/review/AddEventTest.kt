package `in`.gauthama.snapslotify.core.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Add event: an empty event for one the page has but the review did not find. Invented values. */
class AddEventTest {

    private val d1 = Chip("4 Dec", "2026-12-04", null, 3)
    private val d2 = Chip("26 Mar", "2027-03-26", null, 7)
    private val t1 = Chip("8 AM - 12 PM", "08:00", "12:00", 8)

    private fun found(id: Int, date: String, dates: List<Chip>, times: List<Chip>) =
        EventDraft(id, title = "Found $id", dateIso = date, dateChips = dates, timeChips = times)

    @Test fun anEmptyEventWithEveryChipOfThePage() {
        val s = ReviewSession(mutableListOf(found(0, "2026-12-04", listOf(d1, d2), emptyList()), found(1, "2027-03-26", listOf(d2, d1), listOf(t1))), false)
        val e = s.addEvent()
        assertEquals(2, e.id)
        assertEquals(3, s.events.size)
        assertTrue(e.userAdded)
        assertNull(e.title); assertNull(e.dateIso); assertNull(e.startIso)
        assertEquals(listOf("4 Dec", "26 Mar"), e.dateChips.map { it.label })
        assertEquals(listOf("8 AM - 12 PM"), e.timeChips.map { it.label })
        assertEquals(1, e.counts.taps)
        // not addable until a date is set; one chip tap sets it
        assertFalse(e.canAddToCalendar)
        s.tapDateChip(e.id, d2)
        s.tapTimeChip(e.id, t1)
        assertTrue(e.canAddToCalendar)
        assertEquals("2027-03-26", e.dateIso)
        assertEquals("08:00", e.startIso)
        // Add all now counts it
        assertTrue(s.showAddAll)
        assertEquals(3, s.toAdd.size)
    }

    @Test fun idsStayUniqueAfterADelete() {
        val s = ReviewSession(mutableListOf(found(0, "2026-12-04", listOf(d1), emptyList()), found(1, "2026-12-04", listOf(d1), emptyList())), false)
        s.delete(1)
        assertEquals(2, s.addEvent().id)
        assertEquals(3, s.addEvent().id)
    }

    @Test fun survivesASaveAndRestore() {
        val s = ReviewSession(mutableListOf(found(0, "2026-12-04", listOf(d1), emptyList())), false)
        s.addEvent()
        val back = ReviewSession.restore(s.save())
        assertEquals(2, back.events.size)
        assertTrue(back.events[1].userAdded)
        assertFalse(back.events[0].userAdded)
    }
}
