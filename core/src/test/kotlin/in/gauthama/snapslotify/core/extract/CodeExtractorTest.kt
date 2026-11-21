package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.text.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented layouts (not dev, not held-out). Image 1000 x 2000. */
class CodeExtractorTest {

    /** A line; [left] / [top] override the default stacked layout (left 40, 20 px gaps). */
    private class L(val text: String, val height: Int, val left: Int = 40, val top: Int? = null, val width: Int = 860)

    private fun extract(vararg ls: L): List<RawEvent> {
        var top = 100
        val lines = ls.mapIndexed { i, l ->
            val t = l.top ?: top
            OcrLine(l.text, l.left, t, l.left + l.width, t + l.height, block = i).also { top = t + l.height + 20 }
        }
        val cleaned = TextCleaner.clean(lines, 1000, 2000)
        return CodeExtractor.extract(cleaned, CandidateFinder.find(cleaned.kept), 2000)
    }

    @Test
    fun posterTakesTheLargestLineAsTitleAndTheVenueLine() {
        val e = extract(L("City Library presents", 40), L("AUTUMN FAIR", 120), L("Sat 14 Nov 2026", 60),
            L("10 AM - 4 PM", 50), L("Venue: Town Hall", 40)).single()
        assertEquals(RawEvent("AUTUMN FAIR", "Sat 14 Nov 2026", "10 AM - 4 PM", "Town Hall"), e.copy(titleSource = null, titleUnsure = false))
    }

    @Test
    fun skipsRegistrationDatesAndDoorsTimes() {
        val e = extract(L("JAZZ NIGHT", 120), L("Registration closes 01 Nov 2026", 70), L("Fri 20 Nov 2026", 50),
            L("Doors 6:30 PM", 40), L("Show 7:30 PM", 40)).single()
        assertEquals("Fri 20 Nov 2026", e.date)
        assertEquals("7:30 PM", e.time)
    }

    @Test
    fun billTitleAndDueDate() {
        val e = extract(L("Brightline Power", 70), L("Electricity bill", 40), L("Bill date 01 Sep 2026", 40),
            L("Amount due 1,842.00", 40), L("Due date", 40), L("05 Nov 2026", 40)).single()
        assertEquals("Pay Brightline Power electricity bill", e.title)
        assertEquals("05 Nov 2026", e.date)
        assertEquals(null, e.time)
    }

    @Test
    fun chatTitleIsTheFirstClauseAfterTheGreeting() {
        // a chat needs two chat signals: the header's presence line and the input bar
        val e = extract(L("Ravi", 50, top = 100), L("online", 30, top = 145), L("Hi all! Team lunch this Friday, 23 Oct", 40),
            L("at 1 PM at Cafe Blue.", 40), L("Message", 40, top = 1850)).single()
        assertEquals("Team lunch", e.title)
        assertEquals("Friday, 23 Oct", e.date)
        assertEquals("1 PM", e.time)
    }

    @Test
    fun chatTitleSkipsTheWholeHeader() {
        val e = extract(L("R", 40, top = 100), L("Ravi Kumar", 40, left = 120, top = 100), L("online", 30, left = 120, top = 145),
            L("Team lunch this Friday, 23 Oct", 40, top = 400), L("at 1 PM at Cafe Blue.", 40, top = 445),
            L("Message", 40, top = 1850)).single()
        assertEquals("Team lunch", e.title)
    }

    @Test
    fun chatTitleSkipsLastSeen() {
        val e = extract(L("Priya", 40, top = 100), L("last seen today at 9:12 PM", 30, top = 145),
            L("Movie night on Sat 24 Oct?", 40, top = 400), L("Message", 40, top = 1850)).single()
        assertEquals("Movie night", e.title)
    }

    @Test
    fun chatTitleKeepsTheEventWordOfTheDateClause() {
        val e = extract(L("Neha", 40, top = 100), L("online", 30, top = 145),
            L("badminton 12th november", 40, top = 400), L("19:00", 40, top = 445), L("Message", 40, top = 1850)).single()
        assertEquals("badminton", e.title)
        assertEquals("12th november", e.date)
    }

    @Test
    fun aStatusLineIsNotATitle() {
        val e = extract(L("Booking confirmed", 120), L("Monsoon Letters", 70), L("Sun 15 Nov 2026", 40), L("3:00 PM", 40)).single()
        assertEquals("Monsoon Letters", e.title)
    }

    @Test
    fun aStatusClauseBeforeADateIsSkipped() {
        // the reply-by date is a deadline, not a second event: one event, read as a single event
        val e = extract(L("Your salon appointment is", 40), L("scheduled on 17 November 2026 at 10:00 AM.", 40),
            L("Reply by Sat 7 Nov 2026 to change it.", 40)).single()
        assertEquals("17 November 2026", e.date)
        assertTrue(e.title!!, e.title!!.startsWith("Your salon appointment"))
    }

    @Test
    fun aMessageLineAloneDoesNotMakeAChat() {
        val e = extract(L("Book Fair", 80, top = 300), L("Sat 14 Nov 2026, 10 am", 40, top = 420), L("Message", 40, top = 1850)).single()
        assertEquals("Book Fair", e.title)
    }

    @Test
    fun valuesAreOneLineWithoutASentenceDot() {
        val e = extract(L("Annual review", 80), L("on Tuesday, 9", 40), L("Nov 2026 at 10:00 AM.", 40)).single()
        assertEquals("Tuesday, 9 Nov 2026", e.date)
        assertEquals("10:00 AM", e.time)
        assertEquals("6 p.m.", extract(L("Yoga", 80), L("Sat 14 Nov at 6 p.m.", 40)).single().time)
    }

    // ---- slice 2b (Part C) ----

    @Test
    fun multiLineTitleIsMerged() {
        val e = extract(L("CITY ARTS CLUB", 20), L("THE AUTUMN", 70), L("BOOK FAIR", 68), L("Talks and stalls", 30),
            L("21 Nov 2026", 40), L("10 AM", 30)).single()
        assertEquals("THE AUTUMN BOOK FAIR", e.title)
    }

    @Test
    fun linesOfDifferentSizeOrFarApartAreNotMerged() {
        assertEquals("JAZZ NIGHT", extract(L("JAZZ NIGHT", 90), L("Live music", 40), L("21 Nov 2026 7 PM", 40)).single().title)
        // similar height but a large gap
        assertEquals("JAZZ NIGHT", extract(L("JAZZ NIGHT", 90), L("FOOD TRUCKS", 88, top = 400), L("21 Nov 2026 7 PM", 40)).single().title)
    }

    @Test
    fun twoDatesGiveTwoEvents() {
        val es = extract(L("Team", 40), L("Planning for the next two weeks:", 40), L("Sprint planning on Mon 26 Oct,", 40),
            L("10:00-11:00 in Room Atlas.", 40), L("Retro on Fri 30 Oct at 4 PM,", 40))
        assertEquals(listOf("Sprint planning" to "10:00-11:00", "Retro" to "4 PM"), es.map { it.title to it.time })
        assertEquals(listOf("Mon 26 Oct", "Fri 30 Oct"), es.map { it.date })
    }

    @Test
    fun eachEventTakesTheTimeAfterItsDate() {
        val es = extract(L("Next week: Gym orientation on Tue", 40), L("3 Nov at 6 AM. Car service on Fri 6", 40),
            L("Nov at 11 AM at Prime Motors.", 40))
        assertEquals(listOf("Gym orientation" to "6 AM", "Car service" to "11 AM"), es.map { it.title to it.time })
    }

    @Test
    fun excludedDatesDoNotMakeExtraEvents() {
        val es = extract(L("JAZZ NIGHT", 120), L("Last date to register: Sat, 14 Nov 2026", 30), L("Fri 20 Nov 2026", 50), L("7:30 PM", 40))
        assertEquals(listOf("Fri 20 Nov 2026"), es.map { it.date })
    }

    @Test
    fun renewalIsABillAndTakesTheRenewalDate() {
        val e = extract(L("Tue, 20 Oct", 40), L("StreamBox", 30), L("Plan renewal", 30), L("Your plan renews on 14 Nov 2026.", 30),
            L("649 will be charged", 30)).single()
        assertEquals("14 Nov 2026", e.date)
        assertTrue(e.title!!.startsWith("Pay "))
    }

    @Test
    fun twoColumnLabelGivesTheDueDate() {
        // labels in a left column, values in a right column, values listed after all labels (as ML Kit returns them)
        val e = extract(L("Bharat Power Ltd", 30, top = 100), L("ELECTRICITY BILL", 20, top = 150),
            L("Statement Date", 14, top = 300, width = 200), L("Due Date", 12, top = 340, width = 150),
            L("Billing period", 14, top = 380, width = 200),
            L("Oct 9, 2026", 12, left = 600, top = 292, width = 200), L("Oct 31, 2026", 12, left = 600, top = 330, width = 200),
            L("Sep 9, 2026 to Oct 8, 2026", 14, left = 600, top = 372, width = 300)).single()
        assertEquals("Oct 31, 2026", e.date)
    }

    @Test
    fun aLabelLineIsNotAProviderOrTitle() {
        val e = extract(L("Pay by: 27 Oct 2026", 60), L("Skyline Net", 40), L("Broadband bill", 30), L("Amount due 1,200.00", 30)).single()
        assertEquals("Pay Skyline Net broadband bill", e.title)
    }

    @Test
    fun aTimeThatWrapsOntoTheNextLineExcludesBothLinesFromTheTitle() {
        val e = extract(L("Reminder - society meeting tomorrow at 7.30", 40), L("pm (Town Hall)", 40)).single()
        // "Reminder -" is a generic leading word; the day word and the time are not part of the title
        assertEquals("society meeting", e.title)
        assertEquals("7.30 pm", e.time)
    }

    @Test
    fun noDateAndNoTimeMeansNoEvent() {
        assertEquals(emptyList<RawEvent>(), extract(L("Thanks a lot!", 40), L("See you", 40)))
    }
}
