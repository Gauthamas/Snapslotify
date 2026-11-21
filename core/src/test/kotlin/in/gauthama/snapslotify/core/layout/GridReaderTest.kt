package `in`.gauthama.snapslotify.core.layout

import `in`.gauthama.snapslotify.core.extract.CandidateFinder
import `in`.gauthama.snapslotify.core.extract.CodeExtractor
import `in`.gauthama.snapslotify.core.extract.Dates
import `in`.gauthama.snapslotify.core.extract.RawEvent
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.resolve.DateResolver
import `in`.gauthama.snapslotify.core.review.Field
import `in`.gauthama.snapslotify.core.review.ReviewSession
import `in`.gauthama.snapslotify.core.text.OcrNormalizer
import `in`.gauthama.snapslotify.core.text.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import `in`.gauthama.snapslotify.core.extract.PageRoute
import java.time.LocalDate
import java.time.YearMonth

/** Invented month grids (not dev, not held-out), 1200 x 1800: the loosened grid reader and the dates around it. */
class GridReaderTest {

    private val nov7 = LocalDate.of(2026, 11, 7)

    private fun line(text: String, left: Int, top: Int, height: Int = 30, width: Int = 60) =
        OcrLine(text, left, top, left + width, top + height, block = top)

    /**
     * A month grid: label row at [labelTop], cells 150 wide and 200 tall, columns centred at 100 + 150 k. [labels]: the
     * label texts by column (null: not read). [corner]: where the day number sits ("tl" top left, "br" bottom right).
     * Neighbouring-month days fill the first and last rows when [neighbours]. [weekNumbers] adds a column of week numbers
     * left of the grid.
     */
    private fun grid(
        month: YearMonth, sundayFirst: Boolean = true, labels: List<String?>? = null, labelTop: Int = 300, corner: String = "tl",
        neighbours: Boolean = false, weekNumbers: Boolean = false, skipDays: Set<Int> = emptySet(),
    ): List<OcrLine> = buildList {
        val names = if (sundayFirst) listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat") else listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        (labels ?: names).forEachIndexed { k, t -> if (t != null) add(line(t, 100 + 150 * k - 25, labelTop, 30, 50)) }
        val first = month.atDay(1)
        val offset = if (sundayFirst) first.dayOfWeek.value % 7 else first.dayOfWeek.value - 1
        val start = first.minusDays(offset.toLong())
        for (cell in 0 until 42) {
            val d = start.plusDays(cell.toLong())
            val r = cell / 7
            val c = cell % 7
            val inMonth = YearMonth.from(d) == month
            if (!inMonth && !(neighbours && (r == 0 || r >= 4))) continue
            if (inMonth && d.dayOfMonth in skipDays) continue
            if (r > 5 || (!inMonth && r > 0 && cell > offset + month.lengthOfMonth() + 6)) continue
            val x = if (corner == "br") 100 + 150 * c + 30 else 100 + 150 * c - 65
            val y = labelTop + 60 + 200 * r + if (corner == "br") 150 else 0
            add(line("${d.dayOfMonth}", x, y, 30, 30))
            if (weekNumbers && c == 0) add(line("${45 + r}", -10, y + 40, 30, 30))
        }
    }

    private fun cell(month: YearMonth, day: Int, sundayFirst: Boolean = true, labelTop: Int = 300): Pair<Int, Int> {
        val first = month.atDay(1)
        val offset = if (sundayFirst) first.dayOfWeek.value % 7 else first.dayOfWeek.value - 1
        val i = offset + day - 1
        return (100 + 150 * (i % 7) - 65) to (labelTop + 60 + 200 * (i / 7) + 45)
    }

    private fun extract(lines: List<OcrLine>, today: LocalDate? = nov7): List<RawEvent> =
        TextCleaner.clean(lines, 1200, 1800).let { CodeExtractor.extract(it, CandidateFinder.find(it.kept), 1800, 1200, today) }

    private val dec = YearMonth.of(2026, 12)
    private val nov = YearMonth.of(2026, 11)

    /** [lines] as photographed turned: each box's centre rotated by [degrees] (negative: rows rise to the right) about (0, 0). */
    private fun turned(lines: List<OcrLine>, degrees: Double, bottomDegrees: Double = degrees): List<OcrLine> {
        val top = lines.minOf { it.top }; val span = (lines.maxOf { it.bottom } - top).coerceAtLeast(1)
        return lines.map { l ->
            // a page in perspective: the tilt eases from [degrees] at the top to [bottomDegrees] at the bottom
            val r = Math.toRadians(degrees + (bottomDegrees - degrees) * (l.top - top) / span)
            val cx = (l.left + l.right) / 2.0; val cy = (l.top + l.bottom) / 2.0
            val nx = cx * Math.cos(r) - cy * Math.sin(r); val ny = cx * Math.sin(r) + cy * Math.cos(r)
            val dx = Math.round(nx - cx).toInt(); val dy = Math.round(ny - cy).toInt()
            l.copy(left = l.left + dx, right = l.right + dx, top = l.top + dy, bottom = l.bottom + dy)
        }
    }

    private fun tiltedPage(): List<OcrLine> {
        val (x1, y1) = cell(dec, 3); val (x2, y2) = cell(dec, 17); val (x3, y3) = cell(dec, 29)
        return listOf(line("December 2026", 80, 120, 60, 500)) + grid(dec) +
            line("Choir 4pm", x1, y1, 26, 120) + line("Sports day", x2, y2, 26, 120) + line("Term ends", x3, y3, 26, 120)
    }

    private val tiltedEvents = listOf(
        Triple("Choir", "3 December 2026", "4pm"), Triple("Sports day", "17 December 2026", null), Triple("Term ends", "29 December 2026", null),
    )

    @Test fun aPhotoOfATurnedGridReadsAsPrinted() {
        // turned 5 degrees, the weekday labels drift a label's height from one end of their row to the other
        assertEquals(tiltedEvents, extract(turned(tiltedPage(), -5.0)).map { Triple(it.title, it.date, it.time) })
        assertEquals(tiltedEvents, extract(turned(tiltedPage(), 4.0)).map { Triple(it.title, it.date, it.time) })
    }

    @Test fun aGridInPerspectiveReadsAsPrinted() {
        // the labels' row tilts 5 degrees, the last week's 2: rows are levelled by the labels, columns are left alone
        assertEquals(tiltedEvents, extract(turned(tiltedPage(), -5.0, -2.0)).map { Triple(it.title, it.date, it.time) })
    }

    @Test fun columnsFannedOutByPerspectiveStayInTheirCells() {
        // shot from below: the left column stands upright, the right one leans 5 degrees, the labels' row tilts 3.5
        val (x, y) = cell(dec, 28)
        val page = tiltedPage() + line("Inventory day", x, y, 26, 140)
        val fanned = turned(page, -3.5).map { l ->
            val lean = 0.0 + 0.09 * (l.left - 35) / 1000.0
            val d = Math.round(lean * (l.top - 300)).toInt()
            l.copy(left = l.left + d, right = l.right + d)
        }
        assertEquals((tiltedEvents + Triple("Inventory day", "28 December 2026", null)).sortedBy { it.second.substringBefore(" ").toInt() },
            extract(fanned).map { Triple(it.title, it.date, it.time) }.sortedBy { it.second!!.substringBefore(" ").toInt() })
    }

    @Test fun aDamagedMonthInTheHeaderStillHeadsTheGrid() {
        // the OCR dropped a letter of the header's month: the grid is still December 2026
        val page = tiltedPage().map { if (it.text == "December 2026") it.copy(text = "Decmber 2026") else it }
        assertEquals(tiltedEvents, extract(page).map { Triple(it.title, it.date, it.time) })
    }

    @Test fun aLineRunningIntoTheNextCellIsSplitAtTheColumn() {
        // "Book club 12pm" in Monday 7 Dec's cell and "Gym 8:45 pm" in Tuesday 8 Dec's, read as one line with a small gap
        val (x, y) = cell(dec, 7)
        val h = 18
        var at = x
        val words = listOf("Book", "club", "12pm", "Gym", "8:45", "pm").map { w ->
            if (w == "Gym") at = x + 141   // the next column starts 140 to the right of the cell's text
            val ow = `in`.gauthama.snapslotify.core.ocr.OcrWord(w, at, y, at + w.length * 9, y + h); at += w.length * 9 + 6; ow
        }
        val entry = OcrLine(words.joinToString(" ") { it.text }, x, y, words.last().right, y + h, words = words, block = y)
        val got = extract(listOf(line("December 2026", 80, 120, 60, 500)) + grid(dec) + entry).map { Triple(it.title, it.date, it.time) }
        assertEquals(listOf(Triple("Book club", "7 December 2026", "12pm"), Triple("Gym", "8 December 2026", "8:45 pm")), got)
    }

    @Test fun aLevelGridHasNoTilt() {
        assertNull(MonthGrid.labelTilt(tiltedPage()))
        assertEquals(-5.0, MonthGrid.labelTilt(turned(tiltedPage(), -5.0))!!, 0.5)
    }

    /** Part of a month: [n] days from [first] in a Sunday-first grid under [header], an event on days 1, 4 and the last. */
    private fun days(header: String?, first: LocalDate, n: Int): List<OcrLine> = buildList {
        if (header != null) add(line(header, 80, 120, 60, 500))
        listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEachIndexed { k, t -> add(line(t, 100 + 150 * k - 25, 300, 30, 50)) }
        val start = first.dayOfWeek.value % 7
        for (i in 0 until n) {
            val cell = start + i; val x = 100 + 150 * (cell % 7) - 65; val y = 360 + 200 * (cell / 7)
            add(line("${first.plusDays(i.toLong()).dayOfMonth}", x, y, 30, 30))
            when (i) { 1 -> "Choir 4pm"; 4 -> "Sports day"; n - 1 -> "PTA meeting 6pm"; else -> null }?.let { add(line(it, x, y + 45, 26, 120)) }
        }
    }

    private fun shown(lines: List<OcrLine>) = extract(lines).map { Triple(it.title, it.date, it.time) }

    @Test fun aWeekAndTwoDaysUnderTheirPrintedRange() {
        val dec6 = LocalDate.of(2026, 12, 6)
        val want = listOf(Triple("Choir", "7 December 2026", "4pm"), Triple("Sports day", "10 December 2026", null), Triple("PTA meeting", "14 December 2026", "6pm"))
        assertEquals(want, shown(days("6 - 14 December 2026", dec6, 9)))
        assertEquals(want, shown(days("Dec 6 - Dec 14, 2026", dec6, 9)))
        assertEquals(want.map { it.copy(second = it.second.removeSuffix(" 2026")) }, shown(days("December 6-14", dec6, 9)))
    }

    @Test fun aRangeIntoTheNextMonthAndYear() {
        assertEquals(listOf(Triple("Choir", "29 November 2026", "4pm"), Triple("Sports day", "2 December 2026", null), Triple("PTA meeting", "6 December 2026", "6pm")),
            shown(days("28 Nov - 6 Dec 2026", LocalDate.of(2026, 11, 28), 9)))
        assertEquals(listOf(Triple("Choir", "30 December 2026", "4pm"), Triple("Sports day", "2 January 2027", null), Triple("PTA meeting", "6 January 2027", "6pm")),
            shown(days("29 Dec 2026 - 6 Jan 2027", LocalDate.of(2026, 12, 29), 9)))
    }

    @Test fun oneWeekUnderAMonthOrAStartDate() {
        val dec6 = LocalDate.of(2026, 12, 6)
        assertEquals(listOf(Triple("Choir", "7 December 2026", "4pm"), Triple("Sports day", "10 December 2026", null), Triple("PTA meeting", "12 December 2026", "6pm")),
            shown(days("December 2026", dec6, 7)))
        assertEquals(listOf(Triple("Choir", "7 December", "4pm"), Triple("Sports day", "10 December", null), Triple("PTA meeting", "12 December", "6pm")),
            shown(days("Week of 6 December", dec6, 7)))
    }

    @Test fun cellsOutsideThePrintedRangeGiveNoEvent() {
        // the grid shows 6 to 14 December, the range printed is 7 to 13: the last day's event is left out
        assertEquals(listOf("Choir", "Sports day"), shown(days("7 - 13 December 2026", LocalDate.of(2026, 12, 6), 9)).map { it.first })
    }

    private val may27 = YearMonth.of(2027, 5)

    @Test fun aLetterReadFromAnIconBesideTheMonthIsNotPartOfIt() {
        val (x, y) = cell(may27, 4)
        for (header in listOf("O May 2027", "May 2027 >", "< May 2027 O")) {
            assertEquals(header, listOf(Triple("Swim", "4 May 2027", "4pm")), shown(listOf(line(header, 80, 120, 60, 500)) + grid(may27) + line("Swim 4pm", x, y, 26, 120)))
        }
    }

    @Test fun aFirstWeekWhoseNumbersWereAllMissedStillCounts() {
        // May 2027's first row holds only the 1st (a Saturday); with it unread, the first numbers read are the second row
        val (x1, y1) = cell(may27, 4); val (x2, y2) = cell(may27, 26)
        assertEquals(listOf(Triple("Swim", "4 May 2027", "4pm"), Triple("Dentist", "26 May 2027", "10:30am")),
            shown(listOf(line("May 2027", 80, 120, 60, 500)) + grid(may27, skipDays = setOf(1)) + line("Swim 4pm", x1, y1, 26, 120) + line("Dentist 10:30am", x2, y2, 26, 120)))
    }

    @Test fun singleLetterLabelsByPositionWithOneNotRead() {
        val (x, y) = cell(dec, 15)
        val lines = listOf(line("December 2026", 80, 120, 60, 500)) +
            grid(dec, labels = listOf("S", "M", "T", "W", "T", null, "S")) + line("Choir 4pm", x, y, 26, 120)
        assertEquals(listOf(Triple("Choir", "15 December 2026", "4pm")), extract(lines).map { Triple(it.title, it.date, it.time) })
    }

    @Test fun monthAndYearOnSeparateLinesAndAnOcrOInTheYear() {
        val (x, y) = cell(dec, 4)
        val lines = listOf(line("DECEMBER", 80, 120, 60, 400), line("2o26", 900, 140, 40, 120)) + grid(dec) + line("Quiz night", x, y, 26, 120)
        assertEquals(listOf("4 December 2026"), extract(lines).map { it.date })
    }

    @Test fun noYearShownTakesTheYearTheLayoutFitsNearestToTodayAndFlagsIt() {
        val jan = YearMonth.of(2027, 1)
        val (x, y) = cell(jan, 9)
        val lines = listOf(line("January", 80, 120, 60, 300)) + grid(jan) + line("Marathon 5:30am", x, y, 26, 140)
        val e = extract(lines).single()
        assertEquals("9 January", e.date)   // written without its year: the resolver flags it
        val r = DateResolver.resolve(e.date, nov7)
        assertEquals(LocalDate.of(2027, 1, 9), r.start)
        assertTrue(r.flags.any { it.code == "year_not_stated" })
    }

    @Test fun neighbouringMonthDaysAreIgnoredAndTheirCellsGiveNoEvent() {
        val jan = YearMonth.of(2027, 1)
        val (x, y) = cell(jan, 5, sundayFirst = false)
        val lines = listOf(line("January 2027", 80, 120, 60, 500)) + grid(jan, sundayFirst = false, neighbours = true) +
            line("Vendor call 09:00", x, y, 26, 140) +
            line("Old note", 100 - 65, 300 + 60 + 45, 26, 120)   // in the first cell: Mon 28 December, not January
        assertEquals(listOf("Vendor call" to "5 January 2027"), extract(lines).map { it.title to it.date })
    }

    @Test fun aDayNumberInTheBottomRightCornerWorks() {
        val (x, y) = cell(dec, 16)
        val lines = listOf(line("December 2026", 80, 120, 60, 500)) + grid(dec, corner = "br") + line("Carol practice", x, y, 26, 120)
        assertEquals(listOf("16 December 2026"), extract(lines).map { it.date })
    }

    @Test fun aWeekNumberColumnIsNotADay() {
        val (x, y) = cell(dec, 11)
        val lines = listOf(line("December 2026", 80, 120, 60, 500)) + grid(dec, weekNumbers = true) + line("Robotics demo", x, y, 26, 120)
        assertEquals(listOf("11 December 2026"), extract(lines).map { it.date })
    }

    @Test fun aTitleOverTwoLinesAndTimeFirstEntriesWithContinuations() {
        val feb = YearMonth.of(2027, 2)
        val (x1, y1) = cell(feb, 13)
        val (x2, y2) = cell(feb, 5)
        val lines = listOf(line("February 2027", 80, 120, 60, 500)) + grid(feb) +
            line("Anna's", x1, y1, 26, 100) + line("birthday", x1, y1 + 30, 26, 110) +
            line("10am Blood", x2, y2, 26, 130) + line("donation camp", x2, y2 + 30, 26, 130) +
            line("6pm Yoga", x2, y2 + 60, 26, 110)
        val e = extract(lines).map { Triple(it.title, it.date, it.time) }
        assertEquals(listOf(Triple("Blood donation camp", "5 February 2027", "10am"), Triple("Yoga", "5 February 2027", "6pm"),
            Triple("Anna's birthday", "13 February 2027", null)), e)
    }

    @Test fun twoMonthGridsOnOnePage() {
        val jan = YearMonth.of(2027, 1)
        val (x1, y1) = cell(dec, 20, labelTop = 160)
        val (x2, y2) = cell(jan, 30, labelTop = 1500)
        val lines = listOf(line("DECEMBER 2026", 80, 80, 50, 400)) + grid(dec, labelTop = 160) + line("Carol 5pm", x1, y1, 26, 100) +
            line("JANUARY 2027", 80, 1420, 50, 400) + grid(jan, labelTop = 1500) + line("Alumni meet 4pm", x2, y2, 26, 130)
        assertEquals(listOf("20 December 2026", "30 January 2027"), extract(lines).map { it.date })
    }

    /** A December 2026 grid ([header]) with one entry on the 4th; [grid] lines changed by [change]. */
    private fun staffMeeting(header: String = "December 2026", skip: Set<Int> = emptySet(), change: (OcrLine) -> OcrLine = { it }): ReviewSession {
        val (x, y) = cell(dec, 4)
        val lines = listOf(line(header, 80, 120, 60, 500)) + grid(dec, skipDays = skip).map(change) + line("Staff meeting 3 pm", x, y, 26, 140).let(change)
        return ReviewSession.from(CodePipeline.run(OcrResult(1200, 1800, lines, 0), nov7), nov7)
    }

    private fun codes(s: ReviewSession) = s.events.single().flags.map { it.code }

    @Test fun aCalendarWhoseDatesAreAllPastGivesNoEventAndSaysSo() {
        val oct = YearMonth.of(2026, 10)
        val (x1, y1) = cell(oct, 6); val (x2, y2) = cell(oct, 20)
        val lines = listOf(line("October 2026", 80, 120, 60, 500)) + grid(oct) + line("Staff meeting 3 pm", x1, y1, 26, 140) +
            line("Book club 6 pm", x2, y2, 26, 120)
        val r = CodePipeline.run(OcrResult(1200, 1800, lines, 0), nov7)
        assertEquals(PageRoute.GRID, r.route!!.chosen)
        val s = ReviewSession.from(r, nov7)
        assertTrue(s.noEventFound)
        assertEquals("All dates on this page are in the past.", s.note)
        assertEquals(listOf("2026-10-06", "2026-10-20"), r.drops.filter { it.reason == "past" }.map { it.date })
    }

    @Test fun aCalendarShowsOnlyItsComingDates() {
        val (x1, y1) = cell(nov, 3); val (x2, y2) = cell(nov, 7); val (x3, y3) = cell(nov, 20)
        val lines = listOf(line("November 2026", 80, 120, 60, 500)) + grid(nov) + line("Staff meeting 3 pm", x1, y1, 26, 140) +
            line("Book club 6 pm", x2, y2, 26, 120) + line("Choir 7 pm", x3, y3, 26, 100)
        val s = ReviewSession.from(CodePipeline.run(OcrResult(1200, 1800, lines, 0), nov7), nov7)
        // the 3rd is past; the 7th is today, its time already passed or not, and stays
        assertEquals(listOf("2026-11-07", "2026-11-20"), s.events.map { it.dateIso })
    }

    @Test fun aCleanGridHasOneNoteForThePageAndNoFlagOnItsEvents() {
        val s = staffMeeting()
        assertEquals("Read from a calendar, December 2026. Check the month.", s.note)
        val e = s.events.single()
        assertEquals(listOf<String>(), e.flags.map { it.code })
        assertTrue(!e.dateNeedsConfirm)
    }

    @Test fun aGridEventIsFlaggedOnlyForTheCheckItFails() {
        // its day number not read: the date comes from the cell's place alone
        assertEquals(listOf("grid_day_not_read"), codes(staffMeeting(skip = setOf(4))))
        assertEquals("The day number in this box could not be read, so the date comes from its place in the grid. Is it right?",
            staffMeeting(skip = setOf(4)).events.single().flags.single().sentence)
        // no year printed: the year came from the layout
        assertTrue("grid_year_guessed" in codes(staffMeeting(header = "December")))
        // the engine unsure of the day number, or of the entry
        assertEquals(listOf("grid_day_unclear"), codes(staffMeeting { if (it.text == "4") it.copy(confidence = 0.3f) else it }))
        assertEquals(listOf("grid_entry_unclear"), codes(staffMeeting { if (it.text.startsWith("Staff")) it.copy(confidence = 0.3f) else it }))
        // every one of these asks for a confirm tap, as any date flag does; the unclear entry asks about the title
        assertTrue(staffMeeting(skip = setOf(4)).events.single().dateNeedsConfirm)
        assertEquals(Field.TITLE, staffMeeting { if (it.text.startsWith("Staff")) it.copy(confidence = 0.3f) else it }.events.single().flags.single().field)
    }

    @Test fun aDayNumberReadTwiceAmongTheDatesShownIsFlagged() {
        // a second "4" read in another cell of December (a misread 14): the 4th is no longer sure
        val (x, _) = cell(dec, 14)
        val s = staffMeeting { if (it.text == "14" && it.left == x) it.copy(text = "4") else it }
        assertTrue(codes(s).toString(), "grid_day_repeated" in codes(s))
    }

    @Test fun aGridWithEventsListedBelowIsReadAsAList() {
        val feb = YearMonth.of(2027, 2)
        val lines = listOf(line("February 2027", 80, 120, 60, 500)) + grid(feb) +
            line("Tue 9 Feb - Science Exhibition, 10 am", 80, 1650, 30, 800) + line("Tue 16 Feb - Sports Day, 8:30 am", 80, 1700, 30, 800)
        val e = extract(lines)
        assertEquals(listOf("Science Exhibition", "Sports Day"), e.map { it.title })
        assertTrue(e.none { it.grid })
    }

    @Test fun aGridDayNumberNextToTheMonthHeadingIsNotADate() {
        // OCR order puts the "28" day number right before the heading line: "28\nFebruary 2027" is not 28 February
        val feb = YearMonth.of(2027, 2)
        val g = grid(feb)
        val last = g.first { it.text == "28" }
        val lines = listOf(last) + listOf(line("February 2027", 80, 120, 60, 500)) + (g - last) +
            line("Tue 9 Feb - Science Exhibition, 10 am", 80, 1650, 30, 800) + line("Fri 26 Feb - Rehearsal, 3:30 pm", 80, 1700, 30, 800)
        assertEquals(listOf("Tue 9 Feb", "Fri 26 Feb"), extract(lines).map { it.date })
    }

    @Test fun dayAndMonthWithNoYearOnlyWhenTheyCannotBeSomethingElse() {
        fun found(s: String) = Dates.findDates(s).map { it.day to it.month }
        assertEquals(listOf(25 to 3), found("Sale ends 25/3"))
        assertEquals(listOf(25 to 3), found("Sale ends 3/25"))
        assertEquals(listOf(5 to 11), found("Thu 05/11 at 6 pm"))
        assertEquals(emptyList<Pair<Int, Int>>(), found("Gate 05/11"))      // both 12 or less, no weekday
        assertEquals(emptyList<Pair<Int, Int>>(), found("Open 24/7"))
        assertEquals(emptyList<Pair<Int, Int>>(), found("Price 3/4 off"))
        // the next one ahead, always flagged
        val r = DateResolver.resolve("25/3", nov7)
        assertEquals(LocalDate.of(2027, 3, 25), r.start)
        assertEquals(listOf("numeric_no_year"), r.flags.map { it.code })
        val thu = DateResolver.resolve("Thu 05/11", LocalDate.of(2026, 11, 1))
        assertEquals(LocalDate.of(2026, 11, 5), thu.start)
        assertEquals(DayOfWeek.THURSDAY, thu.start!!.dayOfWeek)
        // the weekday must fit: Fri 05/11 is not a date in the next year ahead
        assertNull(DateResolver.resolve("Fri 05/11", LocalDate.of(2026, 11, 1)).start)
    }

    @Test fun anLReadForOneInAnHourBeforeAmPm() {
        assertEquals("Mass 11 pm", OcrNormalizer.normalizeNumeric("Mass 1l pm"))
        assertEquals("doors 10am", OcrNormalizer.normalizeNumeric("doors I0am"))
        assertEquals("Hall 1l", OcrNormalizer.normalizeNumeric("Hall 1l"))   // no am/pm after it: left alone
        assertEquals("1 pm", OcrNormalizer.normalizeNumeric("l pm"))
        assertEquals("tea at 1:30pm", OcrNormalizer.normalizeNumeric("tea at l:30pm"))
        assertEquals("l am here", OcrNormalizer.normalizeNumeric("l am here"))   // may be "I am": left alone
    }

    /**
     * A one-week planner photographed on a curled page: Monday-first columns 150 apart, the row rising more and more
     * towards Sunday (by [lift] per column), as a sheet bending up at its edge does; the dot in the printed range read
     * as a dash. Invented content.
     */
    private fun curledWeek(): List<OcrLine> {
        val lift = listOf(0, 2, 5, 10, 17, 31, 55)
        fun at(k: Int, text: String, dy: Int, height: Int = 20, width: Int = 90) = line(text, 100 + 150 * k - 40, 300 + dy - lift[k], height, width)
        return buildList {
            add(line("WEEK 50 - 7- 13 DECEMBER 2026", 600, 150, 50, 450))
            listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").forEachIndexed { k, t -> add(at(k, t, 0, 18, 50)) }
            for (k in 0..6) add(at(k, "${7 + k}", 38, 32, 40))
            add(at(0, "9:00 Team", 120)); add(at(0, "standup", 145))
            add(at(1, "14:30", 120)); add(at(1, "Dentist", 145)); add(at(1, "check-up", 170)); add(at(1, "visit", 195))
            add(at(3, "Book club", 120))
            add(at(6, "l pm", 120)); add(at(6, "Picnic", 145))
        }
    }

    @Test fun aCurledOneWeekPlannerReadsEveryDay() {
        assertEquals(listOf(
            Triple("Team standup", "7 December 2026", "9:00"), Triple("Dentist check-up visit", "8 December 2026", "14:30"),
            Triple("Book club", "10 December 2026", null), Triple("Picnic", "13 December 2026", "1 pm"),
        ), shown(curledWeek()))
    }
}
