package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.resolve.DateResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** OCR damage in month names ([MonthRepair]); invented strings. */
class MonthRepairTest {

    private fun one(s: String) = Dates.findDates(s).single().let { Triple(it.day, it.month, it.year) }
    private fun none(s: String) = assertTrue(s, Dates.findDates(s).isEmpty())

    @Test fun eachKindOfDamageInALongMonthName() {
        assertEquals(Triple(20, 11, 2026), one("due on 20th Novemnber 2026"))   // a letter added
        assertEquals(Triple(20, 11, 2026), one("due on 20 Novmber 2026"))       // a letter dropped
        assertEquals(Triple(20, 11, 2026), one("due on 20 Novvember 2026"))     // a letter doubled
        assertEquals(Triple(20, 11, 2026), one("due on 20 Novmeber 2026"))      // two neighbours swapped
        assertEquals(Triple(20, 11, 2026), one("due on 20 Novenber 2026"))      // a wrong letter
        assertEquals(Triple(5, 9, 2026), one("Septmber 5, 2026"))
        assertEquals(Triple(14, 2, 2027), one("14 FEBRAURY 2027"))
        assertEquals(Triple(3, 3, 2027), one("3 Marhc 2027"))
        assertEquals(Triple(9, 4, 2027), one("9 Aprl 2027"))
    }

    @Test fun theDateKeepsItsOriginalTextAndPlace() {
        val s = "Dear Customer, due on 20th Novemnber 2026. Pay via UPI"
        val d = Dates.findDates(s).single()
        assertEquals("20th Novemnber 2026", d.raw)
        assertEquals(s.indexOf("20th"), d.start)
        assertEquals(LocalDate.of(2026, 11, 20), DateResolver.resolve(d.raw, LocalDate.of(2026, 11, 10)).start)
        // a damaged word longer than the name keeps its whole text, so the date reads again the same way
        val longer = Dates.findDates("on 3 Decemmber 2026").single()
        assertEquals("3 Decemmber 2026", longer.raw)
        assertEquals(LocalDate.of(2026, 12, 3), DateResolver.resolve("Thursday, 3 Decemmber", LocalDate.of(2026, 11, 10)).start)
        // the text after the repaired word keeps its own offsets: a second date is placed right
        val t = "Novemnber 20, 2026 and 5 Dec 2026"
        assertEquals(t.indexOf("5 Dec"), Dates.findDates(t)[1].start)
    }

    @Test fun shortNamesAndAbbreviationsMustBeExact() {
        // Mar / May and Jun / Jul are one letter apart: no repair
        assertEquals(Triple(5, 3, 2027), one("5 Mar 2027"))
        assertEquals(Triple(5, 5, 2027), one("5 May 2027"))
        assertEquals(Triple(5, 6, 2027), one("5 Jun 2027"))
        assertEquals(Triple(5, 7, 2027), one("5 Jul 2027"))
        none("5 Mav 2027"); none("5 Jum 2027"); none("5 Juli 2027")
        // "Juny" is one letter from June and from July: unreadable
        assertNull(MonthRepair.month("Juny"))
    }

    @Test fun wordsThatLookLikeMonthsAreNot() {
        none("Match 5")                 // one letter from March, but no year: not a date
        none("Augusta 12")
        none("Octopus 12, 2026")        // two changes from October
        none("Marching 5 2026")
        none("Decembers 2026")          // no day
        assertNull(MonthRepair.month("Junior"))
        assertNull(MonthRepair.month("Mayday"))
    }

    @Test fun withNoYearAPrintedWeekdayMakesItADate() {
        // a programme's day heading: weekday, day, damaged month, no year
        assertEquals(Triple(12, 12, null as Int?), one("Saturday, 12 Decmber"))
        assertEquals(Triple(12, 12, null as Int?), one("Sat Decmber 12"))
        none("12 Decmber")             // no year and no weekday: not enough to read a damaged month
    }

    @Test fun aPrintedWeekdayMustStillFit() {
        // 12 Dec 2026 is a Saturday: "Thu" does not fit, so the date is flagged
        assertTrue(DateResolver.resolve("Thu 12 Decmber", LocalDate.of(2026, 11, 10)).flags.any { it.code == "weekday_mismatch" })
        val r = DateResolver.resolve("Thu 20 Novemnber 2026", LocalDate.of(2026, 11, 10))   // 20 Nov 2026 is a Friday
        assertTrue(r.flags.any { it.code == "weekday_mismatch" })
        val ok = DateResolver.resolve("Fri 20 Novemnber 2026", LocalDate.of(2026, 11, 10))
        assertTrue(ok.flags.none { it.code == "weekday_mismatch" })
    }
}
