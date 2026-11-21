package `in`.gauthama.snapslotify.core.resolve

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.review.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A date written with no year: its year is taken as sure only with a weekday that fits and within
 * [DateResolver.NEAREST_YEAR_LIMIT_DAYS] of today; otherwise year_not_stated. Mutation tests with several values of today.
 */
class YearFlagTest {

    private fun year(date: String, today: String) = DateResolver.resolve(date, LocalDate.parse(today))
    private fun flagged(r: Resolved) = r.flags.any { it.code == "year_not_stated" }

    @Test fun aFittingWeekdayCloseToTodayNeedsNoYearFlag() {
        val r = year("Sat 5 Dec", "2026-11-05")
        assertEquals(LocalDate.of(2026, 12, 5), r.start)
        assertFalse(flagged(r))
        assertTrue(r.yearInferred)
    }

    @Test fun noWeekdayAWeekdayThatDoesNotFitOrADateFarAwayIsFlagged() {
        assertTrue("no weekday", flagged(year("5 Dec", "2026-11-05")))
        assertTrue("does not fit", flagged(year("Fri 5 Dec", "2026-11-05")))
        // Sat 5 Dec 2026 is 143 days before 27 Apr 2027: the weekday fits but the date is too far to be sure
        assertTrue("far", flagged(year("Sat 5 Dec", "2027-04-27")))
        // at the limit and just past it
        assertFalse(flagged(year("Sat 5 Dec", LocalDate.of(2026, 12, 5).minusDays(DateResolver.NEAREST_YEAR_LIMIT_DAYS).toString())))
        assertTrue(flagged(year("Sat 5 Dec", LocalDate.of(2026, 12, 5).minusDays(DateResolver.NEAREST_YEAR_LIMIT_DAYS + 1).toString())))
        // a written year is never flagged
        assertFalse(flagged(year("5 Dec 2026", "2026-11-05")))
    }

    /** A programme for December 2026 to January 2027 with weekdays, and one line with no weekday. Invented. */
    private fun programme(): OcrResult {
        fun l(t: String, x: Int, y: Int) = OcrLine(t, x, y, x + t.length * 15, y + 30)
        val ls = mutableListOf(l("Riverside Winter Festival", 60, 60), l("Programme", 60, 120))
        listOf("Sat 5 Dec" to "Choir concert", "Sun 13 Dec" to "Book fair", "Thu 31 Dec" to "New Year's Eve gala",
            "Fri 1 Jan" to "Fireworks walk", "15 Jan" to "Film night").forEachIndexed { i, (d, t) ->
            val y = 220 + i * 70
            ls += l(d, 60, y); ls += l(t, 330, y); ls += l("6 pm", 800, y)
        }
        return OcrResult(1000, 1000, ls.mapIndexed { i, x -> x.copy(block = i) }, 0)
    }

    private val truth = listOf("2026-12-05", "2026-12-13", "2026-12-31", "2027-01-01", "2027-01-15").map(LocalDate::parse)

    /**
     * The mutation: the same programme read on several days. A year that is not the programme's must carry a date flag;
     * dates with a fitting weekday near today carry no year flag; the line with no weekday always does.
     */
    @Test fun noWrongYearGoesUnflaggedWhateverToday() {
        val todays = mapOf(
            "28 Dec" to "2026-12-28", "1 Jan" to "2027-01-01", "29 Feb" to "2028-02-29", "month end" to "2027-01-31",
            "seen after its event dates" to "2027-01-29", "before" to "2026-11-20",
        )
        val failed = mutableListOf<String>()
        for ((name, t) in todays) {
            val today = LocalDate.parse(t)
            val r = CodePipeline.run(programme(), today)
            for (e in r.events) {
                val got = e.resolved.start ?: continue
                val want = truth.firstOrNull { it.monthValue == got.monthValue && it.dayOfMonth == got.dayOfMonth } ?: continue
                val dateFlag = e.flags.any { it.code == "year_not_stated" || it.code == "weekday_mismatch" }
                if (got != want && !dateFlag) failed += "$name: $got for $want with no flag"
                if (got.dayOfMonth == 15 && !e.flags.any { it.code == "year_not_stated" }) failed += "$name: no weekday, but no year flag"
            }
        }
        assertTrue(failed.joinToString("\n"), failed.isEmpty())
    }

    @Test fun onePageNoteNamesTheYearsRead() {
        val r = CodePipeline.run(programme(), LocalDate.parse("2026-11-20"))
        assertEquals("No year is printed; the dates were read as 2026 to 2027. Check the year.", ReviewSession.from(r, LocalDate.parse("2026-11-20")).note)
        val withYear = OcrResult(1000, 1000, listOf(OcrLine("Choir concert", 60, 60, 300, 100), OcrLine("Sat 5 Dec 2026, 6 pm", 60, 140, 400, 180)), 0)
        assertNull(ReviewSession.from(CodePipeline.run(withYear, LocalDate.parse("2026-11-20")), LocalDate.parse("2026-11-20")).note)
    }

    @Test fun leapDay() {
        // 29 Feb exists only in 2028 among 2027-2029: read as 2028; Tue fits and 120 days away at most -> no flag
        val r = year("Tue 29 Feb", "2027-11-01")
        assertEquals(LocalDate.of(2028, 2, 29), r.start)
        assertFalse(flagged(r))
        assertTrue(flagged(year("29 Feb", "2027-11-01")))
    }
}
