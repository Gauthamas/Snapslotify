package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A stray mark read as extra characters at the front of a day or an hour ([LeadRepair]). Invented text. */
class LeadRepairTest {

    private val today = LocalDate.of(2026, 11, 11)

    private fun repaired(text: String) = LeadRepair.repair(text, today)

    private fun run(vararg ls: String) = CodePipeline.run(
        OcrResult(1000, 1600, ls.mapIndexed { i, t -> OcrLine(t, 40, 100 + 40 * i, 40 + t.length * 12, 124 + 40 * i, block = i) }, 0), today, today,
    )

    @Test fun aStrayDigitBeforeADayGoesWhenThePrintedWeekdayFits() {
        // 10 August is a Sunday in 2025; "710" is no day
        val (t, r) = repaired("Join us on 710th August, Sunday")
        assertEquals("Join us on  10th August, Sunday", t)
        assertEquals(listOf("DATE"), r.map { it.field })
        assertEquals(t.length, "Join us on 710th August, Sunday".length)
    }

    @Test fun noWeekdayNoRepair() {
        assertTrue(repaired("Join us on 710th August").second.isEmpty())
        // a weekday that fits no repair in the years near the page's date: none
        assertTrue(repaired("Join us on 731st August, Wednesday").second.isEmpty())
    }

    @Test fun twoPossibleRepairsAreNeverChosenBetween() {
        // "321 March, Saturday": 21 March (2026) and 1 March (2025) are both Saturdays
        assertTrue(repaired("Fair on 321 March, Saturday").second.isEmpty())
        // "131 October, Saturday": 31 October 2026 is a Saturday; 1 October is not, in 2025 to 2027: one repair
        assertEquals(" 31 October, Saturday", repaired("131 October, Saturday").first)
        // "112 PM": 12 PM or 2 PM
        assertTrue(repaired("Sat 31 October 2026\n112 PM onwards").second.none { it.field == "TIME" })
    }

    @Test fun aStrayDigitBeforeAnHourGoesNextToADateWhoseWeekdayFits() {
        val (t, r) = repaired("Sat 31 October 2026\n94 PM onwards")
        assertEquals("Sat 31 October 2026\n 4 PM onwards", t)
        assertEquals(listOf("TIME"), r.map { it.field })
        // no dated weekday near it: left alone
        assertTrue(repaired("Party time\n94 PM onwards").second.isEmpty())
        // a valid hour as read is never touched, nor a 24-hour time written with PM
        assertTrue(repaired("Sat 31 October 2026\n19 PM onwards").second.isEmpty())
    }

    @Test fun aDateAsReadIsNeverRepaired() {
        // a digit lost from the front ("1/29" for "11/29") reads as a valid date: nothing to take off
        assertTrue(repaired("opening on 1/29 @ 12pm").second.isEmpty())
        assertTrue(repaired("Sunday 10th August").second.isEmpty())
    }

    @Test fun theRepairedFieldsAreFlagged() {
        val e = run("Asha turns five", "We would love to see you", "710th August, Sunday", "94 PM onwards", "Lake View Hall").events.single()
        assertEquals("4 PM", e.event.time)
        assertTrue(e.event.date!!.startsWith("10th August"))
        val codes = e.flags.map { "${it.code}:${it.detail}" }
        assertTrue(codes.toString(), "stray_mark_removed:DATE" in codes && "stray_mark_removed:TIME" in codes)
    }
}
