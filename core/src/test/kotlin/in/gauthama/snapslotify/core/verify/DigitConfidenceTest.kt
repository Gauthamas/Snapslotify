package `in`.gauthama.snapslotify.core.verify

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.ocr.OcrWord
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.review.Field
import `in`.gauthama.snapslotify.core.review.FlagText
import `in`.gauthama.snapslotify.core.review.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DigitConfidenceTest {

    private val today = LocalDate.of(2026, 11, 7)

    /** One line, its words 20 px apart, each with the engine confidence [conf] gives it (null: none). */
    private fun line(text: String, conf: (String) -> Float?): OcrLine {
        var x = 50
        val words = text.split(" ").map { w -> OcrWord(w, x, 100, x + 15 * w.length, 130, conf(w)).also { x += 15 * w.length + 20 } }
        return OcrLine(text, 50, 100, x, 130, words = words)
    }

    private fun session(vararg lines: OcrLine) =
        ReviewSession.from(CodePipeline.run(OcrResult(1000, 800, lines.toList(), 0), today), today)

    private val text = "Quiz night 12 December 2026 at 7:30 pm"

    @Test
    fun aDoubtfulDateDigitFlagsTheDateAndAsksForAConfirm() {
        val e = session(line(text) { w -> if (w == "12") 0.2f else 0.9f }).events.single()
        assertEquals("2026-12-12", e.dateIso)
        val f = e.flags.single { it.code == "low_ocr_confidence" }
        assertEquals(Field.DATE, f.field)
        assertEquals("The text here was hard to read. Please check it.", f.sentence)
        assertTrue(e.dateNeedsConfirm)
    }

    @Test
    fun aDoubtfulTimeDigitFlagsTheTimeOnly() {
        val e = session(line(text) { w -> if (w == "7:30") 0.3f else 0.9f }).events.single()
        assertEquals(listOf(Field.TIME), e.flags.filter { it.code == "low_ocr_confidence" }.map { it.field })
        assertFalse(e.dateNeedsConfirm)
    }

    @Test
    fun bothDoubtfulGiveOneFlagOnEachField() {
        val e = session(line(text) { w -> if (w == "12" || w == "7:30") 0.1f else 0.9f }).events.single()
        assertEquals(setOf(Field.DATE, Field.TIME), e.flags.filter { it.code == "low_ocr_confidence" }.map { it.field }.toSet())
    }

    @Test
    fun aDoubtfulWordWithoutADigitOfTheDateOrTimeGivesNoFlag() {
        val e = session(line(text) { w -> if (w == "Quiz" || w == "December") 0.1f else 0.9f }).events.single()
        assertTrue(e.flags.none { it.code == "low_ocr_confidence" })
    }

    @Test
    fun confidentDigitsOrNoConfidenceGiveNoFlag() {
        assertTrue(session(line(text) { 0.9f }).events.single().flags.none { it.code == "low_ocr_confidence" })
        assertTrue(session(line(text) { null }).events.single().flags.none { it.code == "low_ocr_confidence" })
        // no word boxes at all (an older dump): nothing to judge by
        assertTrue(session(OcrLine(text, 50, 100, 900, 130)).events.single().flags.none { it.code == "low_ocr_confidence" })
    }

    @Test
    fun theFlagNamesItsFieldAndKeepsItsSentence() {
        assertEquals(Field.DATE, FlagText.field("low_ocr_confidence", "DATE"))
        assertEquals(Field.TIME, FlagText.field("low_ocr_confidence", "TIME"))
        assertEquals(Field.TITLE, FlagText.field("low_ocr_confidence", ""))
        assertEquals("The text here was hard to read. Please check it.", FlagText.sentence("low_ocr_confidence", null))
    }
}
