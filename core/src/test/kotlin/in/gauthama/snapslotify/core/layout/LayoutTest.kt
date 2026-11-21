package `in`.gauthama.snapslotify.core.layout

import `in`.gauthama.snapslotify.core.chat.ChatRouter
import `in`.gauthama.snapslotify.core.chat.Route
import `in`.gauthama.snapslotify.core.extract.CandidateFinder
import `in`.gauthama.snapslotify.core.extract.CodeExtractor
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrWord
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.text.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented layouts (not dev, not held-out). Image 1080 x 1800. Lines are listed in a scrambled, column-first order. */
class LayoutTest {

    private fun line(text: String, left: Int, top: Int, height: Int = 40, width: Int = 300) =
        OcrLine(text, left, top, left + width, top + height, block = top)

    private fun extract(lines: List<OcrLine>) = TextCleaner.clean(lines, 1080, 1800).let { CodeExtractor.extract(it, CandidateFinder.find(it.kept), 1800) }

    // date block on the left, title-and-time block on the right; OCR reads the date column first
    private val dateLeft = listOf(
        line("CITY ARTS WEEK", 70, 130, height = 100, width = 800), line("18 - 25 NOVEMBER 2026", 70, 270),
        line("WED", 70, 378, 24), line("18 NOV", 75, 414, 45), line("WED", 70, 500, 24), line("18 NOV", 75, 536, 45),
        line("SAT", 70, 622, 24), line("21 NOV", 75, 658, 45),
        line("Food Festival", 332, 382, 44), line("12:30 PM · Rooftop Garden", 332, 437, 33),
        line("Book Launch", 332, 503, 37), line("5 PM · Studio 4", 332, 558, 37),
        line("Jazz Night", 332, 625, 44), line("7:30 PM · Cafe Lume", 332, 683, 33),
    )

    @Test
    fun rowsJoinADateBlockAndATitleBlockSideBySide() {
        val rows = Layout.rows(dateLeft).map { r -> r.map { dateLeft[it].text } }
        assertTrue(rows.contains(listOf("WED", "18 NOV", "Food Festival", "12:30 PM · Rooftop Garden")))
        assertTrue(rows.contains(listOf("SAT", "21 NOV", "Jazz Night", "7:30 PM · Cafe Lume")))
    }

    @Test
    fun programmeRowsKeepSameDateEventsApart() {
        val e = extract(dateLeft)
        assertEquals(listOf("Food Festival", "Book Launch", "Jazz Night"), e.map { it.title })
        assertEquals(listOf("18 NOV", "18 NOV", "21 NOV"), e.map { it.date })
        assertEquals(listOf("12:30 PM", "5 PM", "7:30 PM"), e.map { it.time })
    }

    @Test
    fun rowsUnderADayHeadingTakeItsDate() {
        val e = extract(listOf(
            line("FESTIVAL WEEK", 70, 130, height = 100, width = 800),
            line("THURSDAY 3 DEC", 71, 392, 47), line("Art Exhibition", 300, 459, 30), line("3pm", 71, 460, 36, 80),
            line("FRIDAY 4 DEC", 72, 562, 45), line("10:30am", 73, 631, 27, 140), line("Startup Meetup", 301, 631, 35),
            line("Food Festival", 302, 707, 30), line("6pm", 72, 708, 36, 80),
        ))
        assertEquals(listOf("Art Exhibition", "Startup Meetup", "Food Festival"), e.map { it.title })
        assertEquals(listOf("THURSDAY 3 DEC", "FRIDAY 4 DEC", "FRIDAY 4 DEC"), e.map { it.date })
        assertEquals(listOf("3pm", "10:30am", "6pm"), e.map { it.time })
    }

    private val grid = buildList {
        add(line("December 2026", 55, 61, 40))
        listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEachIndexed { k, d -> add(line(d, 30 + 150 * k, 156, 30, 60)) }
        for (day in 1..31) { val k = (day + 1) % 7; val w = (day + 1) / 7; add(line("$day", 30 + 150 * k, 217 + 200 * w, 30, 30)) }
        add(line("Piano class", 324, 264, 26, 120)); add(line("12pm", 324, 290, 26, 60)); add(line("Cricket", 324, 464, 26, 100))
    }

    @Test fun aMonthGridIsDetected() = assertTrue(Layout.isMonthGrid(grid))

    @Test fun aMonthGridIsNotAChat() = assertEquals(Route.OTHER, ChatRouter.classify(OcrResult(1080, 1800, grid, 0)).route)

    @Test fun aMonthGridIsNotAChatToTheCleaner() = assertTrue(!TextCleaner.clean(grid, 1080, 1800).looksLikeChat)

    @Test
    fun aCleanMonthGridIsReadCellByCell() {
        val e = extract(grid)
        assertEquals(listOf("Piano class" to "1 December 2026", "Cricket" to "8 December 2026"), e.map { it.title to it.date })
        assertEquals(listOf("12pm", null), e.map { it.time })
        assertTrue(e.all { it.grid })
    }

    @Test
    fun oneDayNumberOutOfPlaceIsOutvoted() {
        val bad = grid.map { if (it.text == "15") it.copy(left = it.left + 150, right = it.right + 150) else it }
        assertEquals(extract(grid), extract(bad))
    }

    @Test
    fun aGridWhoseNumbersDoNotFitGivesNoGridGuessAndEveryEventIsFlagged() {
        // most day numbers out of place: no week order fits them, so no cell date is guessed
        val bad = grid.map { l -> l.text.toIntOrNull()?.takeIf { it % 2 == 0 }?.let { l.copy(left = l.left + 150, right = l.right + 150) } ?: l }
        val e = extract(bad)
        assertTrue(e.all { it.grid })
        assertTrue(e.none { it.date?.contains("December") == true })
    }

    @Test
    fun aLineAcrossTwoCellsIsSplitByItsWordsElseGivesNoEvent() {
        // no word boxes: it cannot be split safely
        val wide = grid + line("Cricket 11:00 Dentist 19:00", 624, 664, 26, 280)
        assertEquals(listOf("Piano class", "Cricket"), extract(wide).map { it.title })
        // with word boxes: split at the wide gap, each part in its own cell (Thu 17 and Fri 18 December)
        val words = listOf(OcrWord("Cricket", 624, 664, 700, 690), OcrWord("11:00", 705, 664, 725, 690),
            OcrWord("Dentist", 775, 664, 850, 690), OcrWord("19:00", 855, 664, 900, 690))
        val split = grid + OcrLine("Cricket 11:00 Dentist 19:00", 624, 664, 900, 690, block = 664, words = words)
        val e = extract(split).filter { it.title == "Cricket" || it.title == "Dentist" }.map { Triple(it.title, it.date, it.time) }
        assertTrue(e.toString(), e.containsAll(listOf(Triple("Cricket", "17 December 2026", "11:00"), Triple("Dentist", "18 December 2026", "19:00"))))
    }

    @Test
    fun anEventTakesItsCellsDateEvenWhenTheNumberWasNotRead() {
        val hidden = grid.filterNot { it.text == "8" }
        assertEquals(listOf("Piano class" to "1 December 2026", "Cricket" to "8 December 2026"), extract(hidden).map { it.title to it.date })
    }

    @Test
    fun aGridWithNoMonthGivesNoGridGuessAndEveryEventIsFlagged() {
        val e = extract(grid.filterNot { it.text == "December 2026" })
        assertTrue(e.all { it.grid })
        assertTrue(e.none { it.date?.contains("December") == true })
    }

    @Test fun aPosterIsNotAGrid() = assertTrue(!Layout.isMonthGrid(dateLeft))
}
