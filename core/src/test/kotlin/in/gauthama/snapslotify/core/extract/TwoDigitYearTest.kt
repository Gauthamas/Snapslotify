package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.resolve.DateResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Two-digit years ("26-Feb-27", "20/03/27", "20.03.27") are read as 20xx in every route; invented strings. */
class TwoDigitYearTest {

    private fun one(s: String) = Dates.findDates(s).single().let { Triple(it.day, it.month, it.year) }
    private fun resolved(s: String, today: String) = DateResolver.resolve(s, LocalDate.parse(today))

    @Test fun aTwoDigitYearAfterAMonthNameOrInANumberDate() {
        assertEquals(Triple(26, 2, 2027), one("Due date 26-Feb-27"))
        assertEquals(Triple(20, 3, 2027), one("Pay by 20/03/27"))
        assertEquals(Triple(20, 3, 2027), one("Pay by 20.03.27"))
        assertEquals(Triple(26, 2, 2027), one("Payable by 26 Feb '27"))
        assertEquals(Triple(26, 2, 2027), one("Payable by Feb 26, '27"))
        // with its year read, no year flag
        assertFalse(resolved("26-Feb-27", "2026-11-10").flags.any { it.code == "year_not_stated" })
    }

    @Test fun aLeapDayOnlyInALeapYear() {
        assertEquals(LocalDate.of(2028, 2, 29), resolved("29-Feb-28", "2027-11-01").start)
        assertTrue(Dates.findDates("29-Feb-27").isEmpty())          // 2027 has no 29 Feb: not a date
        assertEquals(LocalDate.of(2028, 2, 29), resolved("29/02/28", "2027-11-01").start)
    }

    @Test fun datesAtTheTurnOfTheYear() {
        assertEquals(LocalDate.of(2026, 12, 31), resolved("31-Dec-26", "2026-12-30").start)
        assertEquals(LocalDate.of(2027, 1, 1), resolved("01-Jan-27", "2026-12-30").start)
        // the written year wins over the nearest one: "31-Dec-25" seen on 30 Dec 2026 is last year's
        assertEquals(LocalDate.of(2025, 12, 31), resolved("31-Dec-25", "2026-12-30").start)
    }

    @Test fun aNumberAfterASpaceIsNotAYear() {
        // "5 Dec 10 am": a date with no year and a time, not 5 Dec 2010
        val d = Dates.findDates("Meet on 5 Dec 10 am").single()
        assertEquals(null, d.year)
        assertEquals(Triple(5, 12, null as Int?), Triple(d.day, d.month, d.year))
        // a dash and a time: "5 Dec - 10:30" keeps the time
        assertEquals(null, Dates.findDates("5 Dec-10:30").single().year)
    }
}
