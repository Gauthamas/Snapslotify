package `in`.gauthama.snapslotify.core.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DateResolverTest {

    private val today = LocalDate.of(2026, 10, 19)
    private fun r(s: String?) = DateResolver.resolve(s, today)
    private fun codes(s: String?) = r(s).flags.map { it.code }

    @Test
    fun numericDatesAreDdMm() {
        assertEquals(LocalDate.of(2026, 11, 5), r("05/11/2026").start)
        assertEquals(LocalDate.of(2026, 11, 5), r("05.11.26").start)
        assertEquals(emptyList<String>(), codes("05/11/2026"))
    }

    @Test
    fun writtenFormsTheVlmReturns() {
        // "the 20th of November"
        assertEquals(LocalDate.of(2026, 11, 20), r("the 20th of November").start)
        assertEquals(LocalDate.of(2026, 11, 20), r("Friday the 20th of November").start)
        assertEquals(listOf("year_not_stated"), codes("Friday the 20th of November"))
        assertEquals(LocalDate.of(2026, 11, 3), r("3 of Nov 2026").start)
        // "13 12 2026": the whole string only, DD/MM, still flagged when both could be a month
        assertEquals(LocalDate.of(2026, 12, 13), r("13 12 2026").start)
        assertEquals(emptyList<String>(), codes("13 12 2026"))
        assertEquals(LocalDate.of(2026, 11, 5), r("05 11 2026").start)
        assertEquals(codes("05/11/2026"), codes("05 11 2026"))
        assertNull(r("call 13 12 2026 now").start)
        // "DEC19" and "19DEC"
        assertEquals(LocalDate.of(2026, 12, 19), r("DEC19").start)
        assertEquals(LocalDate.of(2026, 12, 19), r("19DEC").start)
        assertEquals(LocalDate.of(2026, 12, 19), r("DEC19 2026").start)
        assertNull(r("Market2").start)
    }

    @Test
    fun yearNotStatedTakesTheYearNearestToToday() {
        assertEquals(LocalDate.of(2026, 10, 23), r("Fri 23 Oct").start)
        // the weekday written fits and the date is near today: the year is taken as sure (YearFlagTest)
        assertEquals(emptyList<String>(), codes("Fri 23 Oct"))
        assertEquals(listOf("year_not_stated"), codes("23 Oct"))
        assertEquals(LocalDate.of(2027, 1, 5), DateResolver.resolve("5 Jan", LocalDate.of(2026, 12, 20)).start)
        assertEquals(listOf("year_not_stated", "past_date"), codes("10 Oct"))
    }

    @Test
    fun weekdayMismatch() {
        assertEquals(listOf("weekday_mismatch"), codes("Thu 23 Oct 2026"))   // 23 Oct 2026 is a Friday
        assertEquals(emptyList<String>(), codes("Friday, 23 Oct 2026"))
    }

    @Test
    fun pastDate() {
        assertEquals(listOf("past_date"), codes("01 Sep 2026"))
    }

    @Test
    fun rangesGiveStartAndEnd() {
        val x = r("12 - 15 NOV 2026")
        assertEquals(LocalDate.of(2026, 11, 12), x.start)
        assertEquals(LocalDate.of(2026, 11, 15), x.end)
    }

    @Test
    fun aRangeWhoseStartDayIsLaterStartsInThePreviousMonth() {
        val x = r("30-2 Dec 2026")
        assertEquals(LocalDate.of(2026, 11, 30), x.start)
        assertEquals(LocalDate.of(2026, 12, 2), x.end)
    }

    @Test
    fun relativeWordsAreNotResolved() {
        assertNull(r("tomorrow").start)
        assertNull(r("next Monday").start)
        assertNull(r(null).start)
    }
}
