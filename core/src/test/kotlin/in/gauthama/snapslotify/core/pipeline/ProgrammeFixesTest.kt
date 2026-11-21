package `in`.gauthama.snapslotify.core.pipeline

import `in`.gauthama.snapslotify.core.chat.ChatRouter
import `in`.gauthama.snapslotify.core.extract.CandidateFinder
import `in`.gauthama.snapslotify.core.extract.LabelKind
import `in`.gauthama.snapslotify.core.extract.TimeParser
import `in`.gauthama.snapslotify.core.extract.Titles
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.review.ReviewSession
import `in`.gauthama.snapslotify.core.text.OcrNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Mutation tests for programmes: two columns of days, a time over its title, a title on the time's line, a label covering
 * only the value after it, am/pm and colons as OCR reads them, a day's ordinal, a month alone never a title, a time over
 * its title never a chat stamp, and a word-final I in a title. Invented pages only; today is Sat 14 Nov 2026.
 */
class ProgrammeFixesTest {

    private val today = LocalDate.of(2026, 11, 14)
    private fun l(text: String, x: Int, y: Int, h: Int) = OcrLine(text, x, y, x + text.length * h / 2, y + h)
    private fun page(w: Int, h: Int, ls: List<OcrLine>) = OcrResult(w, h, ls.mapIndexed { i, x -> x.copy(block = i) }, 0)
    private fun events(o: OcrResult) = CodePipeline.run(o, today, today).let { r ->
        ReviewSession.from(r, today).let { s -> if (s.noEventFound) emptyList() else s.events.map { Triple(it.dateIso, if (it.allDay) null else it.startIso, it.title) } }
    }

    @Test fun twoDayColumnsWithTimesOverTitlesAreReadApart() {
        val ls = mutableListOf(l("RIVERSIDE", 94, 89, 120), l("FOOD FAIR", 93, 258, 120), l("2 DAYS 20 STALLS", 81, 611, 45),
            l("SATURDAY", 134, 842, 57), l("12 DEC", 158, 937, 44), l("SUNDAY", 868, 833, 70), l("13 DEC", 873, 936, 42))
        val sat = listOf("11AM" to "Kite Show", "1PM" to "Chess Corner", "4PM" to "Brass Band")
        val sun = listOf("10:30AM" to "Bread Making", "2PM" to "Puppet Play", "6PM" to "Lantern Walk")
        // as OCR groups them: each time in one block with the title under it
        val blocks = ls.mapIndexed { i, x -> x.copy(block = i) }.toMutableList()
        var y = 1073
        for (k in sat.indices) {
            val b = 100 + 2 * k
            blocks += l(sat[k].first, 130, y, 27).copy(block = b); blocks += l(sat[k].second, 160, y + 37, 50).copy(block = b)
            blocks += l(sun[k].first, 872, y, 28).copy(block = b + 1); blocks += l(sun[k].second, 874, y + 49, 42).copy(block = b + 1)
            y += 200
        }
        val e = events(OcrResult(1536, 2200, blocks, 0)).sortedWith(compareBy({ it.first }, { it.second }))
        assertEquals(listOf(
            Triple("2026-12-12", "11:00", "Kite Show"), Triple("2026-12-12", "13:00", "Chess Corner"), Triple("2026-12-12", "16:00", "Brass Band"),
            Triple("2026-12-13", "10:30", "Bread Making"), Triple("2026-12-13", "14:00", "Puppet Play"), Triple("2026-12-13", "18:00", "Lantern Walk"),
        ), e)
    }

    @Test fun aTimeOverItsTitleIsNotAChatStamp() {
        // "6:00 PM" sits just under a short line and ends right of it, as a chat stamp does; the title right under it makes it a row's time
        val above = l("Talk", 100, 950, 50)
        val time = OcrLine("6:00 PM", 110, 1020, 210, 1047)
        assertEquals(1, ChatRouter.classify(page(1536, 2200, listOf(above, time))).stampedLines)
        assertEquals(0, ChatRouter.classify(page(1536, 2200, listOf(above, time, l("Lantern Walk", 112, 1057, 50)))).stampedLines)
    }

    @Test fun aMonthAloneIsNeverACardsTitle() {
        // a card whose day number the OCR lost leaves its month alone ("DEC") in the date column, beside its time line
        val ls = mutableListOf(l("Jazz Nights", 90, 155, 157))
        var y = 508
        for ((day, title) in listOf("4" to "Blue Trio", "" to "Quintet Four", "18" to "Saffron Sky", "8" to "Nocturne")) {
            val month = if (day == "8") "JAN" else "DEC"
            if (day.isNotEmpty()) ls += l("$day $month", 194, y, 72) else ls += l(month, 174, y + 80, 31)
            ls += l(title, 390, y + 4, 51)
            ls += l("Doors open 6:30 PM Music 7:30 PM", 392, y + 80, 40)
            y += 290
        }
        val e = events(page(1536, 2200, ls))
        assertTrue(e.toString(), e.none { it.third.equals("DEC", ignoreCase = true) })
    }

    @Test fun rowsUnderADayHeadingMayHoldTheirTitleOnTheTimeLine() {
        val ls = mutableListOf(l("ST. JUDE'S HALL", 409, 142, 42), l("Winter Programme", 172, 207, 116))
        var y = 530
        for ((d, rows) in listOf("Monday 21 December" to listOf("5:30 pm Choir practice", "7 pm Quiz night"),
            "Tuesday 22 December" to listOf("10:30 am Toy swap", "6 pm Carol singing"))) {
            ls += l(d, 194, y, 55); y += 100
            for (r in rows) { ls += l(r, 220, y, 50); y += 90 }
            y += 30
        }
        assertEquals(listOf(Triple("2026-12-21", "17:30", "Choir practice"), Triple("2026-12-21", "19:00", "Quiz night"),
            Triple("2026-12-22", "10:30", "Toy swap"), Triple("2026-12-22", "18:00", "Carol singing")),
            events(page(1536, 2200, ls)).sortedWith(compareBy({ it.first }, { it.second })))
    }

    @Test fun aLabelCoversOnlyTheValueRightAfterIt() {
        val c = CandidateFinder.find(listOf(l("Doors open 6:30 PM Music 7:30 PM", 100, 100, 30)))
        assertEquals(LabelKind.DOORS, c.times[0].label?.kind)
        assertNull(c.times[1].label)
    }

    @Test fun amPmAndColonsAsOcrReadsThem() {
        assertEquals(LocalTime.of(17, 30), TimeParser.parse("5:30 pmn Studio").single().start)
        assertEquals(LocalTime.of(16, 30), TimeParser.parse("4:30 pnm").single().start)
        assertEquals(LocalTime.of(16, 0), TimeParser.parse("4 prm").single().start)
        assertEquals(LocalTime.of(17, 30), TimeParser.parse("530 pm Hall").single().start)
        assertEquals(LocalTime.of(11, 30), TimeParser.parse("1130 am").single().start)
        assertTrue(TimeParser.parse("Rent Rs 500 pm").isEmpty())
    }

    @Test fun aDaysOrdinalReadWithALetter() {
        assertEquals("20th February", OcrNormalizer.normalizeNumeric("2oth February"))
        assertEquals("10th", OcrNormalizer.normalizeNumeric("1Oth"))
    }

    @Test fun aWordFinalCapitalIIsAnL() {
        assertEquals("Football Match", Titles.plain("FootbalI Match"))
        assertEquals("McIntosh Hall", Titles.plain("McIntosh Hall"))
    }
}
