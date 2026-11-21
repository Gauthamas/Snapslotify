package `in`.gauthama.snapslotify.core.review

import `in`.gauthama.snapslotify.core.metrics.Metrics
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Invented layouts only (not dev, not held-out, not sealed). */
class ReviewTest {

    private val today = LocalDate.of(2026, 10, 20)

    private fun ocr(vararg texts: Pair<String, Int>): OcrResult {
        var top = 100
        return OcrResult(1000, 2000, texts.mapIndexed { i, (t, h) -> OcrLine(t, 40, top, 900, top + h, block = i).also { top += h + 20 } }, 300)
    }

    private fun session(o: OcrResult) = ReviewSession.from(CodePipeline.run(o, today), today)

    // ---- flag -> sentence ----

    @Test
    fun everyKnownFlagHasOneSentence() {
        for (code in FlagText.KNOWN) {
            val s = FlagText.sentence(code, "tomorrow")
            assertTrue(code, s.endsWith(".") || s.endsWith("?"))
            assertFalse(code, s.contains("_"))
        }
        assertEquals("The text says 'tomorrow'. Is this date right?", FlagText.sentence("relative_date", "tomorrow"))
        assertEquals("Day and month could be swapped (DD/MM is assumed). Is this date right?", FlagText.sentence("ambiguous_date_format", null))
        assertEquals(Field.DATE, FlagText.field("due_date_mismatch"))
        assertEquals(Field.TIME, FlagText.field("time_not_in_text"))
        assertTrue(FlagText.sentence("something_new", null).isNotBlank())   // unknown codes still get a sentence
    }

    // ---- chip order ----

    @Test
    fun chipOrderIsPickThenDueThenRest() {
        val s = session(ocr("Brightline Power" to 60, "Electricity bill" to 40, "Bill date 01 Sep 2026" to 40, "Due date" to 40,
            "05 Nov 2026" to 40, "Late fee after 20 Nov 2026" to 40))
        val chips = s.events.single().dateChips.map { it.label }
        assertEquals("05 Nov 2026", chips.first())                       // the extractor's (due-anchored) pick
        assertEquals(setOf("01 Sep 2026", "20 Nov 2026"), chips.drop(1).toSet())
        // a poster: pick first, then the rest in text order
        val p = session(ocr("JAZZ NIGHT" to 120, "Registration closes 01 Nov 2026" to 40, "Fri 20 Nov 2026" to 60, "7:30 PM" to 40))
        assertEquals(listOf("Fri 20 Nov 2026", "01 Nov 2026"), p.events.single().dateChips.map { it.label })
        assertEquals(listOf("7:30 PM"), p.events.single().timeChips.map { it.label })
    }

    @Test
    fun chipOrderDueBeforeRest() {
        val ordered = ChipOrder.order(listOf("a", "b", "c", "d"), pick = "c", due = setOf("d"))
        assertEquals(listOf("c", "d", "a", "b"), ordered)
        assertEquals(listOf("a", "b"), ChipOrder.order(listOf("a", "b", "a"), pick = null, due = emptySet()))
    }

    // ---- review state and tap counting ----

    @Test
    fun billIsAllDayAndPrefilled() {
        val e = session(ocr("Brightline Power" to 60, "Electricity bill" to 40, "Due date" to 40, "05 Nov 2026" to 40)).events.single()
        assertTrue(e.allDay)
        assertEquals(LocalDate.of(2026, 11, 5), e.date)
        assertTrue(e.title!!.startsWith("Pay "))
        assertNotNull(e.dateSourceLine)
    }

    @Test
    fun flaggedDateNeedsAConfirmTap() {
        val s = session(ocr("Ravi" to 40, "Rehearsal on 05/11/2026 at 3:30 PM" to 40))
        val e = s.events.single()
        assertTrue(e.dateNeedsConfirm)
        assertFalse(e.canAddToCalendar)
        s.confirmDate(e.id)
        assertTrue(s.event(e.id).canAddToCalendar)
        assertEquals(1, s.event(e.id).counts.confirms)
        assertEquals(1, s.event(e.id).counts.taps)
    }

    @Test
    fun aChipTapFillsTheFieldAndConfirms() {
        val s = session(ocr("JAZZ NIGHT" to 120, "Registration closes 01 Nov 2026" to 40, "Fri 20 Nov 2026" to 60, "7:30 PM" to 40))
        val e = s.events.single()
        s.tapDateChip(e.id, e.dateChips[1])
        assertEquals(LocalDate.of(2026, 11, 1), s.event(e.id).date)
        assertFalse(s.event(e.id).dateNeedsConfirm)
        s.tapTimeChip(e.id, e.timeChips[0])
        with(s.event(e.id).counts) {
            assertEquals(2, chipTaps)
            assertEquals(2, taps)
            assertEquals(setOf("date", "start"), fieldsEdited)
        }
    }

    @Test
    fun tapCounting() {
        val s = session(ocr("JAZZ NIGHT" to 120, "Fri 20 Nov 2026" to 60, "7:30 PM" to 40))
        val id = s.events.single().id
        s.editTitle(id, "Jazz night at the club", typedChars = 12)
        s.editTitle(id, "Jazz night at the club!", typedChars = 1)
        s.editLocation(id, "Blue Note", typedChars = 9)
        s.setStartTime(id, LocalTime.of(20, 0))
        s.setAllDay(id, true)
        with(s.event(id).counts) {
            assertEquals(5, edits)              // each edit = 1 tap
            assertEquals(22, typedChars)        // typed characters are counted separately
            assertEquals(5, taps)
        }
        // typing that ended where it started ("Blue Note" to "Blue Not" and back): two key taps and the field's touch
        s.countRevertedTyping(id, typedChars = 2, typedLetters = 1)
        with(s.event(id).counts) {
            assertEquals(5, edits)
            assertEquals(24, typedChars)
            assertEquals(6, taps)
            assertEquals(setOf("title", "location", "start", "all_day"), fieldsEdited)
        }
        s.delete(id)
        assertTrue(s.event(id).deleted)
        assertEquals(7, s.event(id).counts.taps)
        assertEquals(1, s.event(id).counts.deletes)
    }

    @Test
    fun noEventGivesAnEmptyFormWithAllChips() {
        val s = session(ocr("Thanks for coming!" to 40, "Bill date 01 Sep 2026" to 40))
        assertTrue(s.noEventFound)
        val e = s.events.single()
        assertEquals(null, e.title)
        assertEquals(null, e.date)
        assertEquals(listOf("01 Sep 2026"), e.dateChips.map { it.label })
    }

    @Test
    fun multipleEventsAndSavedStateRoundTrip() {
        val two = session(ocr("Team" to 40, "Sprint planning on Mon 26 Oct," to 40, "10:00-11:00 in Room Atlas." to 40,
            "Retro on Fri 30 Oct at 4 PM," to 40))
        assertEquals(2, two.events.size)
        two.confirmDate(two.events[0].id)
        two.editTitle(two.events[1].id, "Retro!", typedChars = 1)
        val restored = ReviewSession.restore(two.save())
        assertEquals(two.events.map { it.title to it.counts }, restored.events.map { it.title to it.counts })
        assertEquals(two.events.map { it.dateChips }, restored.events.map { it.dateChips })
        assertEquals(two.events.map { it.dateNeedsConfirm }, restored.events.map { it.dateNeedsConfirm })
    }

    @Test
    fun calendarValues() {
        val s = session(ocr("JAZZ NIGHT" to 120, "Fri 20 Nov 2026" to 60, "7:30 PM to 10 PM" to 40))
        val e = s.events.single()
        s.confirmDate(e.id)
        val c = s.event(e.id).calendar()!!
        assertEquals(LocalDate.of(2026, 11, 20).atTime(19, 30), c.begin)
        assertEquals(LocalDate.of(2026, 11, 20).atTime(22, 0), c.end)
        assertFalse(c.allDay)
        val bill = session(ocr("Brightline Power" to 60, "Electricity bill" to 40, "Due date" to 40, "05 Nov 2026" to 40))
        val b = bill.events.single()
        bill.confirmDate(b.id)
        assertTrue(bill.event(b.id).calendar()!!.allDay)
    }

    // ---- metrics lines: no text, no titles ----

    @Test
    fun metricsLinesHaveNoText() {
        val o = ocr("Secret Title Words" to 120, "Fri 20 Nov 2026" to 60, "7:30 PM" to 40)
        val r = CodePipeline.run(o, today)
        val s = ReviewSession.from(r, today)
        val id = s.events.single().id
        s.confirmDate(id)
        s.markSaved(id)
        val image = Metrics.imageLine(r, o, build = "test", mode = "code", totalMs = 900, timestamp = "2026-10-28T10:00:00+05:30")
        val events = Metrics.eventLines(s, imageId = "x", secondsOnReview = 12.5)
        for (line in listOf(image) + events) {
            assertFalse(line, line.contains("Secret") || line.contains("Title") || line.contains("Nov") || line.contains("\n"))
            Json.parseToJsonElement(line).jsonObject   // one JSON object per line
        }
        val ev = Json.parseToJsonElement(events.single()).jsonObject
        assertEquals("true", ev.getValue("saved").toString())
        assertEquals("1", ev.getValue("taps").toString())
        val im = Json.parseToJsonElement(image).jsonObject
        assertTrue(im.keys.containsAll(listOf("timestamp", "build", "mode", "image_w", "image_h", "ocr_ms", "clean_ms", "extract_ms",
            "total_ms", "n_ocr_lines", "n_events")))
    }
}
