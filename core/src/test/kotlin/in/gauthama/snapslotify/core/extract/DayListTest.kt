package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.resolve.DateResolver
import `in`.gauthama.snapslotify.core.resolve.YearHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Day lists and ranges that share a month or cross one. Invented text. */
class DayListTest {

    private val today = LocalDate.of(2026, 11, 19)

    /** The (start, end) the app would prefill for the first date in [s], as an event ahead of today. */
    private fun span(s: String): Pair<LocalDate?, LocalDate?> {
        val d = DateParser.parse(s).first()
        val r = DateResolver.resolve(d.raw, today, YearHint(ahead = true))
        return r.start to r.end
    }

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test fun aCommaListOfDaysThatFollowOneAnotherIsARange() {
        assertEquals(d(2027, 1, 20) to d(2027, 1, 22), span("20, 21, 22 January"))
        assertEquals(d(2027, 1, 20) to d(2027, 1, 22), span("Wed 20, Thu 21, Fri 22 Jan"))
        assertEquals(d(2027, 6, 12) to d(2027, 6, 13), span("12,\n13 June"))
    }

    private fun listed(s: String) = DateResolver.resolve(DateParser.parse(s).first().raw, today, YearHint(ahead = true)).listed

    @Test fun aListWithGapsIsEveryDayListedAndNoneBetween() {
        val weekly = listOf(d(2027, 3, 5), d(2027, 3, 12), d(2027, 3, 19))
        assertEquals(d(2027, 3, 5) to null, span("5, 12, 19 March"))
        assertEquals(weekly, listed("5, 12, 19 March"))
        assertEquals(weekly, listed("Fri 5, Fri 12, Fri 19 March"))
        assertEquals(weekly, listed("Dates: 5, 12 & 19 March"))
        assertEquals(weekly, listed("5, 12,\n19 March"))
        // the year is the last day's: on 10 March the 5th is past, the 12th and 19th are this year's
        val r = DateResolver.resolve(DateParser.parse("5, 12, 19 March").first().raw, LocalDate.of(2027, 3, 10),
            YearHint(ahead = true, now = LocalDate.of(2027, 3, 10)))
        assertEquals(2027, r.listed.last().year)
    }

    @Test fun daysListedAfterAMonthFirstDate() {
        assertEquals(d(2027, 3, 5) to d(2027, 3, 7), span("March 5,6,7"))
        assertEquals(d(2027, 3, 5) to d(2027, 3, 7), span("Mar 5, 6 & 7"))
        assertEquals(listOf(d(2027, 3, 5), d(2027, 3, 12), d(2027, 3, 19)), listed("Mar 5, 12 & 19"))
        // a time after the date is not a day
        assertEquals(d(2027, 3, 5) to null, span("Mar 5, 12 pm"))
        assertEquals(d(2027, 3, 5) to null, span("Mar 5, 9 am"))
    }

    @Test fun aYearPrintedBeforeAMonthFirstDate() {
        val d0 = DateParser.parse("2028 March 5,6,7").first()
        assertEquals(2028, d0.year)
        assertEquals(d(2028, 3, 5) to d(2028, 3, 7), span("2028 March 5,6,7"))
    }

    @Test fun aDashAfterAMonthFirstDayIsARangeNotATwoDigitYear() {
        assertEquals(d(2027, 3, 5) to d(2027, 3, 7), span("March 5-7"))
        assertEquals(d(2027, 1, 20) to d(2027, 1, 22), span("Jan 20-22"))
        // a two-digit year that cannot be a later day stays a year
        assertEquals(2027, DateParser.parse("Nov 30 '27").first().year)
        assertEquals(2025, DateParser.parse("Nov 30-25").first().year)
    }

    @Test fun aListAfterAWordThatCountsThingsIsNotDays() {
        assertEquals(emptyList<LocalDate>(), listed("Rooms 3, 4, 20 Jan"))
        assertEquals(emptyList<LocalDate>(), listed("Seats 5, 12, 19 March"))
    }

    @Test fun thePipelineShowsOneEventPerListedDate() {
        val page = `in`.gauthama.snapslotify.core.ocr.OcrResult(1000, 1600, listOf("Chess Open", "5, 12, 19 March", "City Library")
            .mapIndexed { i, t -> `in`.gauthama.snapslotify.core.ocr.OcrLine(t, 40, 100 + 60 * i, 40 + t.length * 18, 140 + 60 * i, confidence = 0.95f, block = i) }, 0)
        val r = `in`.gauthama.snapslotify.core.pipeline.CodePipeline.run(page, today, today)
        assertEquals(listOf(d(2027, 3, 5), d(2027, 3, 12), d(2027, 3, 19)), r.events.map { it.resolved.start })
        assertEquals(listOf(null, null, null), r.events.map { it.resolved.end })
        // on 10 March 2027 the 5th is past and left out
        val later = `in`.gauthama.snapslotify.core.pipeline.CodePipeline.run(page, LocalDate.of(2027, 3, 10), LocalDate.of(2027, 3, 10))
        assertEquals(listOf(d(2027, 3, 12), d(2027, 3, 19)), later.events.map { it.resolved.start })
    }

    @Test fun weekdaysOnBothSidesOfADash() {
        assertEquals(d(2027, 1, 20) to d(2027, 1, 22), span("Wed 20 - Fri 22 January"))
        assertEquals(d(2027, 3, 5) to d(2027, 3, 7), span("Fri 5 -\nSun 7 Mar"))
    }

    @Test fun aRangeAcrossTwoMonthsAndAYearEnd() {
        assertEquals(d(2027, 1, 30) to d(2027, 2, 1), span("30 Jan - 1 Feb"))
        assertEquals(d(2027, 1, 30) to d(2027, 2, 1), span("Sat 30 Jan – Mon 1 Feb"))
        assertEquals(d(2026, 12, 30) to d(2027, 1, 1), span("30 Dec - 1 Jan"))
        assertEquals(d(2026, 12, 30) to d(2027, 1, 1), span("30 Dec - 1 Jan 2027"))
        assertEquals(d(2027, 1, 30) to d(2027, 2, 2), span("Jan 30 - Feb 2"))
    }

    @Test fun aCommaListAcrossAMonthEnd() {
        assertEquals(d(2027, 1, 30) to d(2027, 2, 1), span("30, 31 Jan, 1 Feb"))
        assertEquals(d(2026, 11, 29) to d(2026, 12, 1), span("Sun 29, Mon 30 November, Tue 1 December"))
        assertEquals(d(2026, 12, 30) to d(2027, 1, 1), span("30, 31 Dec, 1 Jan"))
    }

    @Test fun notADayList() {
        // a price before the date, two dates far apart, two dates with a gap joined by a comma
        assertNull(span("Rs 20, 21 Jan").second)
        assertEquals(2, DateParser.parse("5 Jan - 20 Apr").size)
        assertEquals(2, DateParser.parse("30 Jan, 2 Feb").size)
        assertEquals(0, DateParser.parse("Ages 20-22 years, rooms 20, 21, 22").size)
    }
}
