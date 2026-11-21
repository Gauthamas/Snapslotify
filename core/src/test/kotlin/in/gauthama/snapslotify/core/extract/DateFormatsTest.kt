package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.resolve.DateResolver
import `in`.gauthama.snapslotify.core.review.ReviewSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Date and time formats (step 2): day/month with no year, "6p", ranges, 29 February, no event. Invented text. */
class DateFormatsTest {

    private val today = LocalDate.of(2026, 11, 11)

    @After fun locale() { DateOrder.override = null }

    private fun resolve(s: String) = DateResolver.resolve(s, today)
    private fun codes(s: String) = resolve(s).flags.map { it.code }

    // ---- day and month as numbers, no year ------------------------------------------------------------------------------
    @Test fun bothReadingsTheSameIsRead() {
        val d = Dates.findDates("payment is due on 5/5").single()
        val r = resolve(d.raw)
        assertEquals(LocalDate.of(2027, 5, 5), r.start)
        assertTrue(codes(d.raw).contains("numeric_no_year"))
        // a cue on the line before counts too ("is due" / "5/5.")
        assertEquals(1, Dates.findDates("your payment is due\n5/5. Pay online").size)
    }

    @Test fun oneNumberAboveTwelveIsRead() {
        assertEquals(LocalDate.of(2026, 11, 29), resolve(Dates.findDates("opening on 11/29 @ 12pm").single().raw).start)
        assertEquals(LocalDate.of(2027, 3, 21), resolve(Dates.findDates("party 3/21").single().raw).start)
    }

    @Test fun twoReadingsAskInTheDevicesOrder() {
        DateOrder.override = false                                  // day first
        val d = Dates.findDates("Pay by 3/4").single()
        val r = resolve(d.raw)
        assertNull(r.start)
        assertEquals(listOf(LocalDate.of(2027, 4, 3), LocalDate.of(2027, 3, 4)), r.alternatives)
        assertTrue(codes(d.raw).contains("day_month_unclear"))
        DateOrder.override = true                                   // month first
        assertEquals(listOf(LocalDate.of(2027, 3, 4), LocalDate.of(2027, 4, 3)), resolve(Dates.findDates("Pay by 3/4").single().raw).alternatives)
    }

    @Test fun aPrintedWeekdayThatFitsOneReadingSettlesIt() {
        DateOrder.override = true                                   // even month first: Sat fits only 3 April 2027
        // the event's date text keeps the weekday printed before it (DateParser), as the app resolves it
        val r = resolve(DateParser.parse("Sat 3/4").single().raw)
        assertEquals(LocalDate.of(2027, 4, 3), r.start)
        // a weekday that fits neither asks
        assertNull(resolve(DateParser.parse("Mon 3/4").single().raw).start)
    }

    @Test fun twoSmallNumbersWithNoCueAreNotADate() {
        assertTrue(Dates.findDates("add 3/4 cup of milk").isEmpty())
        assertTrue(Dates.findDates("rated 5/5 by guests").isEmpty())
    }

    // ---- times ----------------------------------------------------------------------------------------------------------
    private fun time(s: String) = TimeParser.parse(s).firstOrNull()

    @Test fun oneLetterAmPmInATimesCompany() {
        assertEquals(LocalTime.of(18, 0), time("party 6p-9pm at home")!!.start)
        assertEquals(LocalTime.of(21, 0), time("party 6p-9pm at home")!!.end)
        assertEquals(LocalTime.of(18, 0), time("see you at 6p")!!.start)
        assertEquals(LocalTime.of(9, 0), time("from 9a to 11a")!!.start)
        // alone it is not a time: a flat, a grade
        assertNull(time("Flat 5a, Lake Road"))
        assertNull(time("class 6a starts soon"))
    }

    @Test fun aRangesStartTakesTheEndsAmPmUnlessThatPutsItAfterTheEnd() {
        assertEquals(LocalTime.of(17, 30), time("5:30-6:30 PM")!!.start)
        assertEquals(LocalTime.of(10, 0), time("10 - 2 PM")!!.start)
        assertEquals(LocalTime.of(10, 0), time("10 - 11 AM")!!.start)
        assertEquals(LocalTime.of(18, 0), time("6-9pm")!!.start)
    }

    // ---- 29 February ----------------------------------------------------------------------------------------------------
    @Test fun leapDayWithNoYearIsTheNextLeapYearFlagged() {
        val r = resolve("29th Feb")
        assertEquals(LocalDate.of(2028, 2, 29), r.start)
        assertTrue(r.flags.map { it.code }.contains("year_not_stated"))
    }

    private fun page(vararg ls: String) = OcrResult(1000, 1600, ls.mapIndexed { i, t -> OcrLine(t, 60, 80 + 60 * i, 60 + t.length * 14, 108 + 60 * i, block = i) }, 0)

    @Test fun theLeapDaysOwnChipComesFirstNotAnotherDate() {
        val r = CodePipeline.run(page("Spring Meetup", "Date: 29th Feb", "Time: 10am to 4pm", "RSVP by 20th Feb"), today)
        val e = ReviewSession.from(r, today).events.single()
        assertEquals("2028-02-29", e.dateIso)
        assertEquals("2028-02-29", e.dateChips.first().value)
    }

    @Test fun aDateThatAsksOffersItsReadingsFirst() {
        DateOrder.override = false
        // two plausible dates: chips only, its readings first
        val s = ReviewSession.from(CodePipeline.run(page("Gas Company", "Gas bill", "Amount Rs 450.00", "Pay by 3/4"), today), today)
        assertTrue(s.chipsOnly)
        val e = s.events.single()
        assertNull(e.dateIso)
        assertEquals(listOf("2027-04-03", "2027-03-04"), e.dateChips.take(2).map { it.value })
    }

    // ---- no event -------------------------------------------------------------------------------------------------------
    @Test fun noDateAndNoTimeIsNoEvent() {
        val s = ReviewSession.from(CodePipeline.run(page("Thanks for the lovely evening!", "See you soon."), today), today)
        assertTrue(s.noEventFound)
    }
}
