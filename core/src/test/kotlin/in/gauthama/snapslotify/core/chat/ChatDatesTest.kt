package `in`.gauthama.snapslotify.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ChatDatesTest {

    /** A Thursday. */
    private val today = LocalDate.of(2026, 10, 29)

    private fun check(phrase: String, expected: String?) = assertEquals(phrase, expected, ChatDates.resolve(phrase, today)?.start?.toString())

    @Test fun relativeWords() {
        check("today", "2026-10-29")
        check("tonight", "2026-10-29")
        check("Tomorrow", "2026-10-30")
        check("day after tomorrow", "2026-10-31")
        check("in 3 days", "2026-11-01")
        check("in a week", "2026-11-05")
        check("in two weeks", "2026-11-12")
    }

    @Test fun weekdays() {
        check("saturday", "2026-10-31")
        check("Sat", "2026-10-31")
        check("satu", "2026-10-31")
        check("thursday", "2026-10-29")
        check("wednesday", "2026-11-04")
        check("this saturday", "2026-10-31")
        check("coming thursday", "2026-11-05")
    }

    @Test fun nextMeansNextWeek() {
        check("next saturday", "2026-11-07")
        check("next monday", "2026-11-02")
        check("next weekend", "2026-11-07")
        check("weekend", "2026-10-31")
    }

    @Test fun dayWithoutMonth() {
        check("the 30th", "2026-10-30")
        check("14th", "2026-11-14")
        check("Fri the 14th", "2026-11-14")
        check("31st", "2026-10-31")
    }

    @Test fun dayMissingFromNextMonthSkipsIt() =
        assertEquals("2026-12-31", ChatDates.resolve("31st", LocalDate.of(2026, 11, 2))?.start?.toString())

    @Test fun nthWeekday() {
        check("first monday", "2026-11-02")
        check("last friday", "2026-10-30")
        check("second monday", "2026-11-09")
    }

    @Test fun explicitDates() {
        check("8 Nov", "2026-11-08")
        check("Nov 8", "2026-11-08")
        check("8 november", "2026-11-08")
        check("twelfth of november", "2026-11-12")
        check("twenty-first of november", "2026-11-21")
        check("12/11", "2026-11-12")
        check("5.11", "2026-11-05")
        check("12/11/26", "2026-11-12")
        check("31/02", null)
        check("Sat 14 Nov", "2026-11-14")
    }

    @Test fun pastOrVagueGivesNoDate() {
        check("yesterday", null)
        check("next week", null)
        check("this week", null)
        check("weekdays", null)
        check("today is thursday", null)
        check("it's the 29th today", null)
    }

    @Test fun nothingKnownGivesNull() = assertNull(ChatDates.resolve("soonish", today))
}
