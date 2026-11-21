package `in`.gauthama.snapslotify.core.pipeline

import `in`.gauthama.snapslotify.core.extract.TimeParser
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.review.ReviewSession
import `in`.gauthama.snapslotify.core.text.OcrNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Posts with several events: a column bar before a time, an hour glued to the year, a time wrapped under its date line.
 * Invented pages only; today is Sat 14 Nov 2026.
 */
class PostFixesTest {

    private val today = LocalDate.of(2026, 11, 14)
    private fun l(text: String, x: Int, y: Int, h: Int) = OcrLine(text, x, y, x + text.length * h / 2, y + h)
    private fun events(ls: List<OcrLine>) = CodePipeline.run(OcrResult(720, 1612, ls.mapIndexed { i, x -> x.copy(block = i) }, 0), today, today).let { r ->
        ReviewSession.from(r, today).let { s -> if (s.noEventFound) emptyList() else s.events.map { it.dateIso to (if (it.allDay) null else it.startIso) } }
    }

    /** A post: author, its counts bar, the caption lines, a venue, hashtags and the post's age. */
    private fun post(vararg caption: String): List<OcrLine> {
        val ls = mutableListOf(l("riverside.events", 110, 100, 23), l("2,121 likes", 36, 908, 31))
        var y = 957
        for (c in caption) { ls += l(c, 26, y, 28); y += 36 }
        ls += l("Venue: Town Hall", 25, y + 6, 20); ls += l("#weekend #community", 25, y + 42, 26); ls += l("6 DAYS AGO", 25, y + 94, 16)
        return ls
    }

    @Test fun aColumnBarBeforeATimeIsNotAOne() {
        assertEquals("16 January |2 PM", OcrNormalizer.normalizeNumeric("16 January |2 PM"))
        assertEquals("11 December |7:30 pm", OcrNormalizer.normalizeNumeric("11 December |7:30 pm"))
        assertEquals(listOf("2026-12-11" to "19:30", "2027-01-16" to "14:00"),
            events(post("Kite Making Friday, 11 December |7:30 pm", "Book Swap Saturday, 16 January |2 PM")).sortedBy { it.first })
    }

    @Test fun anHourGluedToTheYearIsStillTheTime() {
        assertEquals(LocalTime.of(18, 0), TimeParser.parse("Quiz Night Thu, 11 Feb 20276 pm").single().start)
        assertEquals(LocalTime.of(9, 0), TimeParser.parse("Yoga Camp 16th February 20279 AM").single().start)
        assertEquals(emptyList<Any>(), TimeParser.parse("Order 202765 confirmed"))
    }

    @Test fun aRaisedOrdinalReadWithNoSpaceBeforeTheMonthIsStillTheDate() {
        // a printed "9th January" with the "th" set small and raised: OCR reads it glued to the month
        val e = events(post("Lamp Lighting Evening", "* 9THJANUARY, 2027 *", "BETWEEN 10.15 AM & 11.00 AM"))
        assertEquals(listOf("2027-01-09" to "10:15"), e)
        assertEquals(listOf("2026-11-21" to "18:00"), events(post("Choir Practice 21stNovember 2026 6 pm")))
    }

    @Test fun aTimeWrappedUnderItsDateLineIsThatEventsTime() {
        val e = events(post("Farmers Market Monday, 30 November 2026|", "5:45 PM", "Art Fair 3 Dec 2026 10:45 am"))
        assertEquals(listOf("2026-11-30" to "17:45", "2026-12-03" to "10:45"), e.sortedBy { it.first })
    }
}
