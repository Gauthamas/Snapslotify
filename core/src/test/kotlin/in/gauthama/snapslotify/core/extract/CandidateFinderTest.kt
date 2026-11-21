package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.text.OcrNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class CandidateFinderTest {

    private fun lines(vararg texts: String) = texts.mapIndexed { i, t -> OcrLine(t, 10, 100 + 60 * i, 500, 140 + 60 * i, block = i) }

    private fun dates(text: String) = CandidateFinder.find(lines(*text.split("\n").toTypedArray())).dates
    private fun times(text: String) = CandidateFinder.find(lines(*text.split("\n").toTypedArray())).times

    /** One row per date format: input, raw found, day, month, year, range start day, weekday, ambiguous. */
    private val dateTable = listOf(
        Row("05-Nov-2026", "05-Nov-2026", 5, 11, 2026),
        Row("05/11/2026", "05/11/2026", 5, 11, 2026, ambiguous = true),
        Row("25-12-2026", "25-12-2026", 25, 12, 2026),
        Row("05.11.26", "05.11.26", 5, 11, 2026, ambiguous = true),
        Row("on 5 Nov 2026", "5 Nov 2026", 5, 11, 2026),
        Row("5th November 2026", "5th November 2026", 5, 11, 2026),
        Row("Nov 5, 2026", "Nov 5, 2026", 5, 11, 2026),
        Row("2026-11-05", "2026-11-05", 5, 11, 2026),
        Row("Fri 23 Oct", "Fri 23 Oct", 23, 10, null, weekday = DayOfWeek.FRIDAY),
        Row("this Saturday, 24 Oct at 7:30 PM", "Saturday, 24 Oct", 24, 10, null, weekday = DayOfWeek.SATURDAY),
        Row("Sunday, November 8, 2026", "Sunday, November 8, 2026", 8, 11, 2026, weekday = DayOfWeek.SUNDAY),
        Row("12 - 15 NOV 2026", "12 - 15 NOV 2026", 15, 11, 2026, startDay = 12),
        Row("21 & 22 NOV 2026", "21 & 22 NOV 2026", 22, 11, 2026, startDay = 21),
        Row("Nov 5 - 7, 2026", "Nov 5 - 7, 2026", 7, 11, 2026, startDay = 5),
        Row("O5/11/2O26", "05/11/2026", 5, 11, 2026, ambiguous = true),       // OCR: O read for 0
        Row("1S/11/2026", "15/11/2026", 15, 11, 2026),                         // OCR: S read for 5
    )

    data class Row(val input: String, val raw: String, val day: Int, val month: Int, val year: Int?,
                   val startDay: Int? = null, val weekday: DayOfWeek? = null, val ambiguous: Boolean = false)

    @Test
    fun dateFormats() {
        for (r in dateTable) {
            val found = dates(r.input)
            assertEquals(r.input, 1, found.size)
            val d = found.single()
            assertEquals(r.input, listOf(r.raw, r.day, r.month, r.year, r.startDay, r.weekday, r.ambiguous),
                listOf(d.raw, d.day, d.month, d.year, d.startDay, d.weekday, d.ambiguous))
        }
    }

    @Test
    fun notDates() {
        for (s in listOf("October 2026", "Bill for Oct 2026", "Amount 1,842.00", "312 kWh", "Friday", "OTP 482913")) {
            assertEquals(s, emptyList<Any>(), dates(s))
        }
    }

    /** One row per time format: input, raw found, start, end. */
    @Test
    fun timeFormats() {
        val table = listOf(
            listOf("at 6 pm", "6 pm", "18:00", null),
            listOf("6:30 PM", "6:30 PM", "18:30", null),
            listOf("6.30 pm", "6.30 pm", "18:30", null),
            listOf("18:30", "18:30", "18:30", null),
            listOf("18:30 hrs", "18:30 hrs", "18:30", null),
            listOf("11 AM", "11 AM", "11:00", null),
            listOf("12 PM", "12 PM", "12:00", null),
            listOf("12 AM", "12 AM", "00:00", null),
            listOf("10:00-11:00", "10:00-11:00", "10:00", "11:00"),
            listOf("9 AM to 6 PM", "9 AM to 6 PM", "09:00", "18:00"),
            listOf("10 - 11 AM", "10 - 11 AM", "10:00", "11:00"),
            listOf("9:00 AM PST", "9:00 AM PST", "09:00", null),
            listOf("1O:3O AM", "10:30 AM", "10:30", null),                        // OCR: O read for 0
            listOf("10:00-1l:00", "10:00-11:00", "10:00", "11:00"),               // OCR: l read for 1
        )
        for ((input, raw, start, end) in table) {
            val found = times(input!!)
            assertEquals(input, 1, found.size)
            val t = found.single()
            assertEquals(input, listOf(raw, start, end), listOf(t.raw, t.start.toString(), t.end?.toString()))
        }
    }

    @Test
    fun notTimes() {
        for (s in listOf("1,842.00", "05.11.26", "valid for 10 minutes", "312 kWh", "Fri 23 Oct", "25:00", "13 PM", "Room 4")) {
            assertEquals(s, emptyList<Any>(), times(s))
        }
    }

    @Test
    fun labels() {
        fun label(text: String) = dates(text).single().label?.kind
        assertEquals(LabelKind.DUE, label("Due date: 05 Nov 2026"))
        assertEquals(LabelKind.DUE, label("Pay by 09 Nov 2026"))
        assertEquals(LabelKind.DUE, label("Due date\n05 Nov 2026"))                 // label on the line before
        assertEquals(LabelKind.NON_EVENT, label("Bill date 01 Sep 2026"))
        assertEquals(LabelKind.NON_EVENT, label("Statement date: 01 Sep 2026"))
        assertEquals(LabelKind.REGISTRATION, label("Registration closes 01 Nov 2026"))
        assertEquals(LabelKind.BOOKED_ON, label("Booked on 01 Oct 2026"))
        assertNull(label("Sat 14 Nov 2026"))
        // The previous line's label already has its own date, so it does not carry over
        assertNull(dates("Due date 05 Nov 2026\nEvent 14 Nov 2026")[1].label)
        assertEquals(LabelKind.DOORS, times("Doors 6:30 PM").single().label?.kind)
        // The nearer label wins on a line with two
        assertEquals(LabelKind.DUE, label("Bill date 01 Sep 2026, Due date 05 Nov 2026".substringAfter(", ")))
    }

    @Test
    fun registrationAndRenewalLabels() {
        assertEquals(LabelKind.REGISTRATION, dates("Last date to register: Sat, 14 Nov 2026").single().label?.kind)
        assertEquals(LabelKind.DUE, dates("Your plan renews on 14 Nov 2026").single().label?.kind)
        assertEquals(LabelKind.DUE, dates("Payable by 03 Nov 2026").single().label?.kind)
    }

    @Test
    fun aLabelOnTheSameRowToTheLeftLabelsTheValue() {
        val ls = listOf(OcrLine("Due Date", 50, 340, 200, 352, block = 0), OcrLine("Billing period", 50, 380, 250, 394, block = 1),
            OcrLine("Oct 31, 2026", 600, 330, 800, 342, block = 2), OcrLine("Sep 9, 2026", 600, 372, 800, 386, block = 3))
        assertEquals(listOf(LabelKind.DUE, LabelKind.NON_EVENT), CandidateFinder.find(ls).dates.map { it.label?.kind })
    }

    @Test
    fun zeroReadForOInAMonthName() {
        assertEquals("15-Oct-2026", dates("BILL DATE 15-0ct-2026").single().raw)
        assertEquals("3 Nov", dates("3 N0v").single().raw)
    }

    @Test
    fun lineIndexAndBox() {
        val c = CandidateFinder.find(lines("BOOK FAIR", "Sat 14 Nov 2026", "10 AM"))
        assertEquals(1, c.dates.single().lineIndex)
        assertEquals(160, c.dates.single().box.top)
        assertEquals(2, c.times.single().lineIndex)
        // A date split over two lines belongs to the line where it starts
        val split = CandidateFinder.find(lines("Thursday, 29 October", "2026"))
        assertEquals(listOf(0), split.dates.map { it.lineIndex })
        assertEquals(2026, split.dates.single().year)
    }

    @Test
    fun ocrNormalizerOnlyTouchesNumericTokens() {
        assertEquals("05/11/2026 10:30", OcrNormalizer.normalizeNumeric("O5/11/2O26 1O:3O"))
        assertEquals("SOLD OUT, Slot 5, Hall O", OcrNormalizer.normalizeNumeric("SOLD OUT, Slot 5, Hall O"))
        assertEquals("Level 2. Sl.No 4", OcrNormalizer.normalizeNumeric("Level 2. Sl.No 4"))
        assertTrue(OcrNormalizer.normalizeNumeric("x").length == 1)
    }

    @Test
    fun spmIsFivePmButSamIsAName() {
        assertEquals(java.time.LocalTime.of(17, 0), times("Hackathon\nSpm\u00b7 Rooftop").single().start)
        assertEquals(java.time.LocalTime.of(17, 30), times("Doors S:30 pm").single().start)
        assertEquals("5pm· Rooftop, Sam", OcrNormalizer.normalizeNumeric("Spm· Rooftop, Sam"))
        for (s in listOf("Sam's party", "Meet Sam at the door", "S am", "spm", "USPM office", "Spmx")) {
            assertEquals(s, emptyList<Any>(), times(s))
        }
    }
}
