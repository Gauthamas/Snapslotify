package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.review.ReviewSession
import `in`.gauthama.snapslotify.core.text.OcrNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A wrong title is never shown without a flag; a date glued to its time is read apart. Invented text. */
class ConfidentWrongTest {

    private val today = LocalDate.of(2026, 11, 11)

    private fun line(t: String, x: Int, y: Int, h: Int = 20, conf: Float = 0.9f) = OcrLine(t, x, y, x + t.length * h / 2, y + h, confidence = conf)
    private fun page(vararg ls: OcrLine) = OcrResult(1000, 1600, ls.mapIndexed { i, l -> l.copy(block = i) }, 0)
    private fun run(o: OcrResult) = CodePipeline.run(o, today, today)

    @Test fun aDayGluedToItsTimeByAColonIsReadApart() {
        assertEquals("Fri, Nov 20 7:00- 9:00 PM", OcrNormalizer.normalizeNumeric("Fri, Nov 20:7:00- 9:00 PM"))
        val e = run(page(line("Book launch", 20, 100, 30), line("Fri, Nov 20:7:00- 9:00 PM", 20, 160))).events.single()
        assertEquals("7:00- 9:00 PM", e.event.time)
        // an ordinary clock is untouched
        assertEquals("Nov 20 at 10:30 AM", OcrNormalizer.normalizeNumeric("Nov 20 at 10:30 AM"))
    }

    @Test fun aScreensHeadingThatNamesSomethingElseFlagsTheTitleAndIsOffered() {
        // an app screen (status bar); its heading is not the sentence the title came from
        val r = run(page(line("9:41", 20, 0, 12), line("Garden club", 20, 60, 34), line("Hi all,", 20, 160),
            line("Our seed swap is on Sat 21 Nov 2026 at 10 am in the hall.", 20, 200)))
        val e = r.events.single()
        assertTrue(e.flags.toString(), e.flags.any { it.code == "title_unsure" })
        assertEquals(listOf("Garden club"), ReviewSession.from(r, today).events.single().suggestedTitles)
    }

    @Test fun aTitleReadFromWeakLinesIsFlagged() {
        val e = run(page(line("Qvrrz plnk sesh", 20, 100, 30, conf = 0.4f), line("Sat 21 Nov 2026 at 10 am", 20, 160))).events.single()
        assertTrue(e.flags.toString(), e.flags.any { it.code == "title_unsure" })
    }

    @Test fun aChatsQuestionAboveAStrayStampStillMarksTheMessageItAnswers() {
        val ls = listOf(line("Meera", 20, 10, 24), line("online", 20, 38, 14),
            line("We are hosting a housewarming party", 20, 120), line("Nice!! When?", 400, 170),
            line("1111 PM", 600, 196, 12), line("Sat 21 Nov", 20, 240), line("Message", 20, 1500, 18))
        val e = run(OcrResult(720, 1600, ls.mapIndexed { i, l -> l.copy(block = i) }, 0)).events.single()
        assertTrue(e.event.title, e.event.title!!.contains("housewarming party"))
    }
}
