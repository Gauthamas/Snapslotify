package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.pipeline.FinalEvent
import `in`.gauthama.snapslotify.core.review.Field
import `in`.gauthama.snapslotify.core.review.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The screenshot's own date as the anchor when no date line anchors a relative day (720 x 1612 layouts). */
class ShotDateTest {

    private val nov5 = LocalDate.of(2026, 11, 5)

    @Test fun datesFromFileNamesAndExif() {
        assertEquals(nov5, ShotDate.fromName("Screenshot_20261105-101523.png"))
        assertEquals(nov5, ShotDate.fromName("IMG-20261105-WA0001.jpg"))
        assertEquals(nov5, ShotDate.fromName("Screenshot 2026-11-05 at 10.15.23.png"))
        assertNull(ShotDate.fromName("rel_10.png"))
        assertNull(ShotDate.fromName("Screenshot_20261345.png"))   // no such date
        assertEquals(nov5, ShotDate.fromExif("2026:11:05 10:15:23"))
        assertNull(ShotDate.fromExif(null))
    }

    @Test fun theGalleryDateComesFirstThenExifThenTheFileName() {
        val nov10 = LocalDate.of(2026, 11, 10)
        assertEquals(nov5, ShotDate.pick(nov5, nov10, nov10))
        assertEquals(nov10, ShotDate.pick(null, nov10, nov5))
        assertEquals(nov10, ShotDate.pick(null, null, nov10))
        assertNull(ShotDate.pick(null, null, null))
        assertEquals("Thu, 5 Nov 2026", ShotDate.label(nov5))
    }

    // rel_10: a chat whose date lines say only "Yesterday" and "Today"
    private fun chat(vararg items: String): OcrResult {
        val lines = mutableListOf(OcrLine("10:10", 29, 20, 77, 36), OcrLine("Neha", 113, 87, 183, 115), OcrLine("online", 113, 119, 167, 137))
        var y = 205
        for (t in items) {
            lines += if (t.startsWith("#")) OcrLine(t.drop(1), 320, y, 400, y + 20) else OcrLine(t, 45, y, 45 + minOf(11 * t.length, 600), y + 26)
            y += 80
        }
        lines += OcrLine("Message", 45, 1545, 154, 1571)
        return OcrResult(720, 1612, lines, 0)
    }

    private val rel10 = chat("#Yesterday", "did you eat", "#Today", "yoga class tomorrow 6 am?", "sure")

    private fun one(ocr: OcrResult, shot: LocalDate?): Pair<FinalEvent, ReviewSession> {
        val r = CodePipeline.run(ocr, nov5, shot)
        return r.events.single() to ReviewSession.from(r, nov5)
    }

    @Test fun rel10_todayLineCountsFromTheScreenshotDateAndIsFlagged() {
        val (e, s) = one(rel10, nov5)
        assertEquals(LocalDate.of(2026, 11, 6), e.resolved.start)
        assertEquals("6 am", e.event.time)
        val flag = s.events.single().flags.first { it.code == "shot_date" }
        assertEquals(Field.DATE, flag.field)
        assertEquals("Counted from the date of the screenshot, Thu, 5 Nov 2026. Is this right?", flag.sentence)
        assertTrue(s.events.single().dateNeedsConfirm)
    }

    @Test fun aYesterdayLineCountsFromTheDayBefore() {
        val (e, _) = one(chat("#Yesterday", "yoga class tomorrow 6 am?"), nov5)
        assertEquals(nov5, e.resolved.start)   // tomorrow, said yesterday
        assertTrue("shot_date" in e.flags.map { it.code })
    }

    @Test fun aWeekdayLineIsTheMostRecentSuchDayOnOrBeforeTheScreenshotDate() {
        assertEquals(LocalDate.of(2026, 11, 2), ShotDate.lineDay("Monday", nov5))      // Thu 5 Nov: the Monday before
        assertEquals(nov5, ShotDate.lineDay("Thursday", nov5))                         // the same day
        assertEquals(LocalDate.of(2026, 11, 4), ShotDate.lineDay("Yesterday", nov5))
        assertNull(ShotDate.lineDay("Mon, 19 Oct 2026", nov5))
        val (e, s) = one(chat("#Monday", "yoga class tomorrow 6 am?"), nov5)
        assertEquals(LocalDate.of(2026, 11, 3), e.resolved.start)                     // tomorrow, said on Mon 2 Nov
        assertEquals("Counted from the date of the screenshot, Thu, 5 Nov 2026. Is this right?",
            s.events.single().flags.first { it.code == "shot_date" }.sentence)
    }

    @Test fun aWeekdayLineWithNoScreenshotDateCountsFromToday() {
        val (e, _) = one(chat("#Monday", "yoga class tomorrow 6 am?"), null)
        assertEquals(LocalDate.of(2026, 11, 3), e.resolved.start)   // today Thu 5 Nov: Monday 2 Nov, tomorrow 3 Nov
        assertTrue("today_date" in e.flags.map { it.code })
    }

    @Test fun noDateLineAtAllAlsoCountsFromTheScreenshotDate() {
        val (e, _) = one(chat("yoga class this Saturday 6 am?"), nov5)
        assertEquals(LocalDate.of(2026, 11, 7), e.resolved.start)
        assertTrue("shot_date" in e.flags.map { it.code })
    }

    @Test fun withNoScreenshotDateTodayIsTheAnchorWithItsOwnFlag() {
        val r = CodePipeline.run(rel10, nov5, null)
        val e = r.events.single()
        assertEquals(LocalDate.of(2026, 11, 6), e.resolved.start)
        val codes = e.flags.map { it.code }
        assertTrue(codes.toString(), "today_date" in codes && "shot_date" !in codes && "relative_date" !in codes)
        val flag = ReviewSession.from(r, nov5).events.single().flags.first { it.code == "today_date" }
        assertEquals("Counted from today. Is this right?", flag.sentence)
        assertEquals(Field.DATE, flag.field)
    }

    @Test fun aRealDateLineStillWinsOverTheScreenshotDate() {
        val (e, _) = one(chat("#Mon, 19 Oct 2026", "I will come tomorrow at 6 pm to fix the tap"), nov5)
        assertEquals(LocalDate.of(2026, 10, 20), e.resolved.start)
        assertTrue("message_date" in e.flags.map { it.code } && "shot_date" !in e.flags.map { it.code })
    }

    @Test fun aFileDateLaterThanTheRealCaptureIsUsedAndStillFlagged() {
        // taken on 5 Nov, but copied later: the gallery has no date and the file name says 10 Nov
        val shot = ShotDate.pick(null, null, ShotDate.fromName("Screenshot_20261110-090000.png"))
        val (e, s) = one(rel10, shot)
        assertEquals(LocalDate.of(2026, 11, 11), e.resolved.start)   // wrong by five days, so the flag must show
        assertEquals("Counted from the date of the screenshot, Tue, 10 Nov 2026. Is this right?",
            s.events.single().flags.first { it.code == "shot_date" }.sentence)
    }

    @Test fun anEmailWithNoSentLineCountsFromTheScreenshotDate() {
        val ocr = OcrResult(720, 1612, listOf(
            OcrLine("Annual Day", 35, 179, 300, 219), OcrLine("Karthik Iyer", 133, 260, 284, 293),
            OcrLine("Our Annual Day will be this Tuesday at 9 AM.", 36, 387, 600, 417)), 0)
        val (e, _) = one(ocr, nov5)
        assertEquals(LocalDate.of(2026, 11, 10), e.resolved.start)
        assertTrue("shot_date" in e.flags.map { it.code })
    }
}
