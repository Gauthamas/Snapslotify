package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.pipeline.FinalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Wrapped times, relative days counted from a message's own date, and generic titles (720 x 1612 layouts). */
class AnchoredDayTest {

    private val nov5 = LocalDate.of(2026, 11, 5)

    /** An email screen: subject, sender, the sent line (small, top right), "to me", then the body lines. */
    private fun email(subject: String, sent: String?, vararg body: String): OcrResult {
        val lines = mutableListOf(
            OcrLine("21:28", 35, 20, 97, 39), OcrLine(subject, 35, 179, 640, 219), OcrLine("Karthik Iyer", 133, 260, 284, 293),
            OcrLine("to me", 132, 299, 192, 315),
        )
        if (sent != null) lines += OcrLine(sent, 414, 279, 684, 300)
        var y = 387
        for (t in body) {
            lines += OcrLine(t, 36, y, 36 + minOf(12 * t.length, 640), y + 30)
            y += 48
        }
        return OcrResult(720, 1612, lines, 0)
    }

    private fun one(ocr: OcrResult): FinalEvent = CodePipeline.run(ocr, nov5).events.single()

    private fun codes(e: FinalEvent) = e.flags.map { it.code }

    @Test fun aPmOnTheNextLineJoinsTheTime_email09() {
        val r = CodePipeline.run(email("Schedule: Yoga Retreat and Annual Day", "Thu, 5 Nov 2026, 9:47 AM", "Hi all,",
            "Session 1: Yoga Retreat on Sun 22 Nov at 3:00", "PM"), nov5)
        val e = r.events.first { it.resolved.start == LocalDate.of(2026, 11, 22) }
        assertEquals("3:00 PM", e.event.time)
        assertTrue(codes(e).toString(), "time_no_ampm" !in codes(e))
    }

    @Test fun aTwelveHourTimeWithNoAmOrPmIsFlagged() {
        val e = one(email("Team meeting", null, "The team meeting is on 12 Nov 2026 at 3:00."))
        assertEquals("3:00", e.event.time)
        assertTrue("time_no_ampm" in codes(e))
        assertTrue("time_no_ampm" !in codes(one(email("Team meeting", null, "The team meeting is on 12 Nov 2026 at 15:00."))))
    }

    @Test fun thisTuesdayIsCountedFromTheSentDate_email06() {
        val e = one(email("Annual Day this week", "Thu, 5 Nov 2026, 8:08 AM", "Hi team,", "Our Annual Day will be this Tuesday at 9 AM.",
            "Agenda attached."))
        assertEquals(LocalDate.of(2026, 11, 10), e.resolved.start)
        assertEquals("9 AM", e.event.time)
        assertTrue(codes(e).toString(), "message_date" in codes(e) && "time_guess" !in codes(e))
    }

    @Test fun anEmailWithNoSentDateAndNoScreenshotDateCountsFromToday() {
        val e = one(email("Annual Day", null, "Our Annual Day will be this Tuesday at 9 AM."))
        assertEquals(LocalDate.of(2026, 11, 10), e.resolved.start)   // today is Thu 5 Nov
        assertTrue(codes(e).toString(), "today_date" in codes(e) && "relative_date" !in codes(e))
    }

    // rel_05 and rel_06: weekday words counted from the chat's message date line
    private class Bubble(val text: String)

    private fun chat(day: String, vararg bubbles: String): OcrResult {
        val lines = mutableListOf(OcrLine("10:10", 29, 20, 77, 36), OcrLine("Anita", 113, 87, 183, 115), OcrLine("online", 113, 119, 167, 137),
            OcrLine(day, 275, 205, 445, 225))
        var y = 280
        for (b in bubbles) {
            lines += OcrLine(b, 45, y, 45 + minOf(11 * b.length, 600), y + 26)
            y += 90
        }
        lines += OcrLine("Message", 45, 1545, 154, 1571)
        return OcrResult(720, 1612, lines, 0)
    }

    @Test fun thisFridayInAChat_rel05() {
        val e = one(chat("Mon, 26 Oct 2026", "lets do team lunch at Blue Tokri this Friday 1 pm"))
        assertEquals(LocalDate.of(2026, 10, 30), e.resolved.start)
        assertTrue("message_date" in codes(e))
    }

    @Test fun onSundayInAChat_rel06() {
        val e = one(chat("Wed, 28 Oct 2026", "trek to Nandi Hills on Sunday 6 am"))
        assertEquals(LocalDate.of(2026, 11, 1), e.resolved.start)
        assertTrue("message_date" in codes(e))
    }

    @Test fun nextFridayStaysFlaggedWithNoDate() {
        // a time with no date: offered as chips only, still flagged as a relative day
        val r = CodePipeline.run(chat("Mon, 26 Oct 2026", "lets do team lunch at Blue Tokri next Friday 1 pm"), nov5)
        assertTrue(r.events.isEmpty())
        val e = r.ambiguous.single()
        assertNull(e.resolved.start)
        assertTrue("relative_date" in codes(e))
    }

    @Test fun genericWordsLeaveTheTitle() {
        assertEquals("Parent teacher meeting", Titles.plain("Reminder: Parent teacher meeting"))
        assertEquals("Yoga Retreat and Annual Day", Titles.plain("Schedule: Yoga Retreat and Annual Day"))
        assertEquals("Book Launch", Titles.plain("This is a reminder that the Book Launch"))
        assertEquals("Team lunch", Titles.plain("Fwd: Re: Team lunch"))
        assertNull(Titles.plain("This is a reminder."))
        val e = one(email("This is a reminder", null, "Book Launch", "on 14 Nov 2026 at 6 PM"))
        assertTrue(e.event.title.toString(), e.event.title?.contains("reminder", ignoreCase = true) != true)
    }
}
