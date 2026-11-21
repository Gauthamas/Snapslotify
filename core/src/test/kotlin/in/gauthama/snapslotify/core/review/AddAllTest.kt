package `in`.gauthama.snapslotify.core.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddAllTest {

    private fun event(id: Int, date: String? = "2026-12-0${id + 1}", confirm: Boolean = false) =
        EventDraft(id, title = "Event $id", dateIso = date, startIso = "19:00", allDay = false, dateNeedsConfirm = confirm)

    private fun session(vararg e: EventDraft) = ReviewSession(e.toMutableList(), noEventFound = false)

    /** A stand-in for the calendar insert: records what was opened; ids in [failing] fail. */
    private class Calendar(val failing: MutableSet<String> = mutableSetOf()) {
        val opened = mutableListOf<String>()
        fun open(v: CalendarValues): Boolean {
            if (v.title in failing) return false
            opened += v.title!!
            return true
        }
    }

    @Test
    fun notShownForASingleEvent() {
        assertFalse(session(event(0)).showAddAll)
        val two = session(event(0), event(1))
        assertTrue(two.showAddAll)
        two.delete(1)
        assertFalse(two.showAddAll)
    }

    @Test
    fun enabledOnlyWhenEveryEventsOwnAddIsWithTheCount() {
        val ready = session(event(0), event(1), event(2))
        assertTrue(ready.events.all { it.canAddToCalendar })
        assertTrue(ready.canAddAll)
        assertEquals(3, ready.toAdd.size)
        // one event with no date: its own Add is disabled, so Add all is too, and adds nothing
        val noDate = session(event(0), event(1, date = null))
        assertFalse(noDate.event(1).canAddToCalendar)
        assertFalse(noDate.canAddAll)
        val cal = Calendar()
        assertNull(noDate.addAll(cal::open))
        assertEquals(emptyList<String>(), cal.opened)
    }

    @Test
    fun aFlaggedDateMustBeConfirmedFirst() {
        val s = session(event(0), event(1, confirm = true))
        assertFalse(s.event(1).canAddToCalendar)
        assertFalse(s.canAddAll)
        val cal = Calendar()
        assertNull(s.addAll(cal::open))
        assertEquals(emptyList<String>(), cal.opened)
        s.confirmDate(1)
        assertTrue(s.canAddAll)
    }

    @Test
    fun anEventThatNeedsConfirmingAgainDisablesAddAllUntilConfirmed() {
        val s = session(event(0), event(1))
        assertTrue(s.canAddAll)
        // The review screen has no edit today that asks for a confirm again (picking a date confirms it), so the date is
        // flagged again directly: Add all follows each event's own Add, whatever makes it disabled.
        s.event(1).dateNeedsConfirm = true
        assertFalse(s.event(1).canAddToCalendar)
        assertFalse(s.canAddAll)
        s.confirmDate(1)
        assertTrue(s.canAddAll)
        // a date picked by the user is confirmed by the pick
        s.event(0).dateNeedsConfirm = true
        assertFalse(s.canAddAll)
        s.setDate(0, java.time.LocalDate.of(2026, 12, 24))
        assertTrue(s.canAddAll)
    }

    @Test
    fun addsEachEventWithTheSameValuesAsItsOwnAdd() {
        val s = session(event(0), event(1), event(2))
        val want = s.events.map { it.calendar() }
        val got = mutableListOf<CalendarValues>()
        val r = s.addAll { got += it; true }!!
        assertEquals(AddAllResult(3, 3, emptyList()), r)
        // last first, so the calendar editors stack with the first event on top
        assertEquals(want.reversed(), got)
        assertTrue(s.events.all { it.saved && !it.canAddToCalendar })
        assertEquals(0, s.toAdd.size)
        assertFalse(s.canAddAll)
    }

    @Test
    fun oneFailureDoesNotStopTheRestAndCanBeTriedAgain() {
        val s = session(event(0), event(1), event(2), event(3), event(4), event(5))
        val cal = Calendar(mutableSetOf("Event 2"))
        val r = s.addAll(cal::open)!!
        assertEquals(AddAllResult(added = 5, tried = 6, failed = listOf(2)), r)
        assertEquals(r, s.lastAddAll)
        assertEquals(5, cal.opened.size)
        val failed = s.event(2)
        assertTrue(failed.addFailed)
        assertFalse(failed.saved)
        // still there to retry, with its own Add and with Add all (count 1)
        assertTrue(failed.canAddToCalendar)
        assertEquals(listOf(2), s.toAdd.map { it.id })
        assertTrue(s.canAddAll)
        cal.failing.clear()
        assertEquals(AddAllResult(1, 1, emptyList()), s.addAll(cal::open))
        assertEquals(6, cal.opened.size)
        assertFalse(s.event(2).addFailed)
        assertTrue(s.events.all { it.saved })
    }

    @Test
    fun tappingTwiceAddsNothingTwice() {
        val s = session(event(0), event(1))
        val cal = Calendar()
        assertEquals(AddAllResult(2, 2, emptyList()), s.addAll(cal::open))
        assertNull(s.addAll(cal::open))
        assertEquals(listOf("Event 1", "Event 0"), cal.opened)
        // an added event's own Add is disabled and adds nothing either
        assertFalse(s.add(0, cal::open))
        assertEquals(2, cal.opened.size)
    }

    @Test
    fun anEventAddedOnItsOwnIsSkipped() {
        val s = session(event(0), event(1), event(2))
        val cal = Calendar()
        assertTrue(s.add(1, cal::open))
        assertEquals(2, s.toAdd.size)
        assertEquals(AddAllResult(2, 2, emptyList()), s.addAll(cal::open))
        assertEquals(listOf("Event 1", "Event 2", "Event 0"), cal.opened)
    }

    @Test
    fun addedFailedAndTheLastResultSurviveProcessDeath() {
        val s = session(event(0), event(1))
        s.addAll(Calendar(mutableSetOf("Event 0"))::open)
        val back = ReviewSession.restore(s.save())
        assertEquals(listOf(true to false, false to true), back.events.map { it.addFailed to it.saved })
        assertEquals(AddAllResult(1, 2, listOf(0)), back.lastAddAll)
    }
}
