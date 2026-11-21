package `in`.gauthama.snapslotify.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The report's address, subject and body (JVM). Invented page values only. */
class ProblemReportTest {

    private val device = ProblemReport.Device("1.0 (1, release)", "12 (API 31)", "OPPO CPH2477")
    private val page = ProblemReport.Page("several", listOf("year_not_stated", "time_guess"),
        listOf(ProblemReport.Shown("Kite Show", "2026-12-12", "11:00"), ProblemReport.Shown("Book Swap", "2026-12-13", "all day")),
        "KITE SHOW\nSat 12 Dec 11 am")

    @Test fun theAddressIsTheOneConstant() {
        assertEquals("bodhiarcade@gmail.com", ProblemReport.ADDRESS)
    }

    @Test fun theSubjectNamesTheAppAndVersion() {
        assertEquals("Snapslotify problem report (1.0)", ProblemReport.subject("1.0"))
    }

    @Test fun theBodyHoldsTheDeviceThePageAndTheReason() {
        val b = ProblemReport.body(ProblemReport.Reason.WRONG_TIME_NOT_FLAGGED, "  The time should be 10 am ", device, page, includeText = false)
        for (s in listOf("What went wrong: Wrong time and not flagged", "Note: The time should be 10 am", "App version: 1.0 (1, release)", "Android version: 12 (API 31)",
            "Device: OPPO CPH2477", "Page type: several", "Flags: year_not_stated, time_guess",
            "Event 1: title Kite Show; date 2026-12-12; time 11:00", "Event 2: title Book Swap; date 2026-12-13; time all day")) {
            assertTrue(s, s in b)
        }
    }

    @Test fun theReadTextIsInTheBodyOnlyWhenChosen() {
        assertFalse("KITE SHOW" in ProblemReport.body(ProblemReport.Reason.OTHER, null, device, page, includeText = false))
        val with = ProblemReport.body(ProblemReport.Reason.OTHER, null, device, page, includeText = true)
        assertTrue("Text read from the screenshot:\nKITE SHOW\nSat 12 Dec 11 am" in with)
    }

    @Test fun aReportFromTheHomeScreenHasNoPage() {
        val b = ProblemReport.body(ProblemReport.Reason.MISSED_EVENT, "", device, null, includeText = true)
        assertTrue("Page: none (reported from the home screen)" in b)
        assertFalse("Note:" in b)
    }
}
