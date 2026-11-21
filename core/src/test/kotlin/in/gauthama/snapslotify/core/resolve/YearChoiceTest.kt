package `in`.gauthama.snapslotify.core.resolve

import `in`.gauthama.snapslotify.core.extract.BillDue
import `in`.gauthama.snapslotify.core.extract.CandidateFinder
import `in`.gauthama.snapslotify.core.extract.Paragraph
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.review.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The year of a date written without one ([YearHint]), sentences split over two lines ([Paragraph]), "next <weekday>". Invented text. */
class YearChoiceTest {

    private val today = LocalDate.of(2026, 11, 11)
    private fun start(s: String, h: YearHint = YearHint()) = DateResolver.resolve(s, today, h).start.toString()

    @Test fun withNoHintTheNearestYearAsBefore() {
        // 20 September is 52 days back and 313 ahead: the nearest (a bill's date)
        assertEquals("2026-09-20", start("20 Sep"))
    }

    @Test fun anEventsDateIsTheNextOneAhead() {
        assertEquals("2027-09-20", start("20 Sep", YearHint(ahead = true)))
        assertEquals("2026-12-05", start("5 Dec", YearHint(ahead = true)))
        // today counts
        assertEquals("2026-11-11", start("11 Nov", YearHint(ahead = true)))
        // ahead of the screenshot's own date when it is known
        assertEquals("2026-09-20", start("20 Sep", YearHint(ahead = true, now = LocalDate.of(2026, 9, 1))))
    }

    @Test fun pastTenseOrAnEarlierSentDateKeepsItInThePast() {
        assertEquals("2026-09-20", start("20 Sep", YearHint(ahead = true, past = true)))
        // written on 3 March 2026: 20 February is just before it, 20 March just after; both that year
        assertEquals("2026-02-20", start("Feb 20", YearHint(ahead = true, written = LocalDate.of(2026, 3, 3))))
        assertEquals("2026-03-20", start("Mar 20", YearHint(ahead = true, written = LocalDate.of(2026, 3, 3))))
    }

    @Test fun aWrittenWeekdayMovesTheYearToWhereItFitsTheSameWay() {
        // 1 October is a Tuesday in 2024 and 2030, not in 2026 to 2029
        val ahead = DateResolver.resolve("Tue, Oct 1", today, YearHint(ahead = true))
        assertEquals("2030-10-01", ahead.start.toString())
        assertTrue(ahead.flags.toString(), ahead.flags.any { it.code == "year_not_stated" && "weekday fits" in it.detail })
        assertEquals("2024-10-01", start("Tue, Oct 1", YearHint(ahead = true, past = true)))
        // no hint: as before, the nearest year, the weekday's mismatch flagged
        val plain = DateResolver.resolve("Tue, Oct 1", today)
        assertEquals("2026-10-01", plain.start.toString())
        assertTrue(plain.flags.any { it.code == "weekday_mismatch" })
    }

    private fun page(vararg ls: String, gap: Int = 8) = OcrResult(1000, 1600,
        ls.mapIndexed { i, t -> OcrLine(t, 40, 100 + (24 + gap) * i, 40 + t.length * 11, 124 + (24 + gap) * i, block = i) }, 0)

    @Test fun onAPosterThePastTenseOfTheDatesSentenceDecides() {
        val ahead = CodePipeline.run(page("Quiz Night", "Teams of four meet at the hall on 20 September at 7 pm"), today, today).events.single()
        assertEquals("2027-09-20", ahead.resolved.start.toString())
        // read as this year's 20 September, which is past: dropped, not shown
        val past = CodePipeline.run(page("Quiz Night", "The final round was held at the hall on 20 September at 7 pm"), today, today)
        assertTrue(past.events.isEmpty())
        assertEquals(listOf(`in`.gauthama.snapslotify.core.pipeline.Drop("2026-09-20", "past")), past.drops)
    }

    @Test fun aSentenceSplitOverTwoLinesIsOnePhrase() {
        val o = page("Hello, your plan this month costs Rs. 300, Due", "date is 14th March, please pay on time.", "Bill made on 1st March 2027 for you.")
        assertTrue(Paragraph.continues(o.lines, 1))
        val c = CandidateFinder.find(o.lines)
        assertEquals("14th March", BillDue.choose(o.lines, c).date?.raw)
        // a line that ends its sentence, or one far below, does not run on
        val ended = page("Hello, your plan this month costs Rs. 300.", "date is 14th March, please pay on time.")
        assertFalse(Paragraph.continues(ended.lines, 1))
        val far = page("Hello, your plan this month costs Rs. 300, Due", "date is 14th March, please pay on time.", gap = 80)
        assertFalse(Paragraph.continues(far.lines, 1))
    }

    @Test fun nextWeekdayOffersBothReadings() {
        // seen on Wednesday 11 November: the coming Thursday (12th) or the one after (19th), both offered; no date is set
        val r = CodePipeline.run(page("Team sync", "Would you be free next Thursday at 2 pm to go over the plan?"), today, today)
        val s = ReviewSession.from(r, today)
        val chips = s.events.single().dateChips.map { it.value }
        assertTrue(chips.toString(), "2026-11-12" in chips && "2026-11-19" in chips)
        assertEquals(null, s.events.single().dateIso)
    }
}
