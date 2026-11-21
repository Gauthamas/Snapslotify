package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.pipeline.PipelineResult
import `in`.gauthama.snapslotify.core.review.Field
import `in`.gauthama.snapslotify.core.review.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A picture inside a chat ([ChatPicture], chip offer only) and the chat's separator test: invented chats 720 wide, a poster shared as an image among the messages. */
class ChatPictureTest {

    private val today = LocalDate.of(2026, 11, 20)

    private sealed interface Item
    /** A message: wrapped lines on the left (incoming) or right, its small sent time under it. */
    private class Msg(val incoming: Boolean, val stamp: String, vararg val lines: String) : Item
    /** A picture: its lines (text, height, left offset inside it), then its corner time (or none). */
    private class Pic(val corner: String?, vararg val lines: Triple<String, Int, Int>) : Item

    private fun chat(vararg items: Item): OcrResult {
        val ls = mutableListOf(OcrLine("Meera", 113, 87, 190, 115), OcrLine("online", 113, 119, 167, 137), OcrLine("Fri, 20 Nov 2026", 280, 170, 440, 190))
        var y = 230
        for (it in items) when (it) {
            is Msg -> {
                for (t in it.lines) { val w = minOf(11 * t.length, 600); val x = if (it.incoming) 45 else 675 - w; ls += OcrLine(t, x, y, x + w, y + 26); y += 38 }
                val x = if (it.incoming) 45 else 600
                ls += OcrLine(it.stamp, x, y, x + 70, y + 15); y += 70
            }
            is Pic -> {
                y += 20
                for ((t, h, dx) in it.lines) { val w = minOf(h * t.length / 2, 480); ls += OcrLine(t, 90 + dx, y, 90 + dx + w, y + h); y += h + 18 }
                y += 40
                it.corner?.let { c -> ls += OcrLine(c, 45, y, 115, y + 15); y += 70 }
            }
        }
        // the message box at the foot of the screen, clear of the last message
        val h = maxOf(1612, y + 400)
        ls += OcrLine("Type a message", 45, h - 67, 215, h - 41)
        return OcrResult(720, h, ls.mapIndexed { i, l -> l.copy(block = i) }, 0)
    }

    private fun run(o: OcrResult): PipelineResult = CodePipeline.run(o, today, today)
    private fun shown(r: PipelineResult) = ReviewSession.from(r, today).let { s ->
        if (s.noEventFound) emptyList() else s.events.map { it.dateIso to (if (it.allDay) null else it.startIso) }
    }

    private val poster = arrayOf(Triple("GARDEN PARTY", 60, 0), Triple("Bring a plate to share", 40, 10), Triple("Hill Road Lawn", 22, 10),
        Triple("Sat 28 Nov 2026", 22, 10), Triple("4:00 PM", 16, 10))

    /** The draft has the picture's date, its time missing and flagged, the time offered as a chip, and is not all day. */
    private fun missingTimeWithChip(r: PipelineResult, time: String) {
        val e = ReviewSession.from(r, today).events.single()
        assertEquals("2026-11-28", e.dateIso)
        assertNull(e.startIso)
        assertFalse(e.allDay)
        assertTrue(e.timeNeedsPick && e.flags.any { it.code == "time_missing" })
        assertTrue(time in e.timeChips.map { it.value })
    }

    @Test fun aPictureWithItsCornerTimeGivesItsDateAndOffersItsTime() {
        val o = chat(Msg(true, "8:58 pm", "Look what came in the post"), Pic("9:02 pm", *poster))
        assertNotNull(ChatPicture.find(o.lines, o.imageWidth, o.imageHeight))
        val r = run(o)
        assertTrue(r.picture)
        // the picture's small 4:00 PM is taken out as a stamp; nothing is put back, it is offered
        missingTimeWithChip(r, "16:00")
        // the chat's own stamps are still taken out
        assertTrue(r.cleaned.kept.none { it.text in setOf("8:58 pm", "9:02 pm") })
    }

    @Test fun messagesAboveAndBelowKeepTheirStampsOut() {
        val o = chat(Msg(true, "8:58 pm", "Look what came in the post"), Pic("9:02 pm", *poster),
            Msg(false, "9:04 pm", "Ooh nice, are you going?"), Msg(true, "9:05 pm", "Yes, want to come along?"))
        val r = run(o)
        assertTrue(r.picture)
        missingTimeWithChip(r, "16:00")
        assertTrue(r.cleaned.kept.none { it.text in setOf("8:58 pm", "9:02 pm", "9:04 pm", "9:05 pm") })
    }

    @Test fun aLongWrappedMessageIsNeverAPicture() {
        val o = chat(Msg(true, "8:58 pm", "Hey! The garden party is on Saturday", "28 November at the Hill Road lawn, starting", "at 4 pm. Everyone is bringing a plate to",
            "share, and there will be music and games", "for the kids until it gets dark. Let me", "know if you can make it so I can tell them."),
            Msg(false, "9:04 pm", "Sounds lovely, count me in"))
        assertNull(ChatPicture.find(o.lines, o.imageWidth, o.imageHeight))
        assertEquals(listOf("2026-11-28" to "16:00"), shown(run(o)))
    }

    @Test fun aChatWithNoPictureHasNoRegion() {
        val o = chat(Msg(true, "8:58 pm", "Dinner at Olive Tree on Sat 28 Nov at 8 pm?"), Msg(false, "9:04 pm", "Yes, see you there"))
        assertNull(ChatPicture.find(o.lines, o.imageWidth, o.imageHeight))
        val r = run(o)
        assertFalse(r.picture)
        assertEquals(listOf("2026-11-28" to "20:00"), shown(r))
    }

    @Test fun aPicturesTimeTakenOutAsAStampIsAFlaggedMissingTimeWithTheTimeAsAChipNeverAllDay() {
        // the picture's small "4:00 PM" is taken out as a stamp; nothing is put back, the time is offered instead
        val o = chat(Msg(true, "8:58 pm", "Look what came in the post"),
            Pic("9:02 pm", Triple("GARDEN PARTY", 60, 0), Triple("Hill Road Lawn", 22, 10), Triple("Saturday 28 November, all welcome", 22, 10), Triple("4:00 PM", 14, 10)),
            Msg(false, "9:04 pm", "Ooh nice, are you going?"))
        val r = run(o)
        assertTrue(r.picture)
        assertTrue(r.cleaned.removed.any { it.line.text == "4:00 PM" })
        val s = ReviewSession.from(r, today)
        val e = s.events.single()
        assertEquals("2026-11-28", e.dateIso)
        assertNull(e.startIso)
        assertFalse(e.allDay)                                       // not an all-day event
        assertTrue(e.timeNeedsPick)
        assertEquals(Field.TIME, e.flags.single { it.code == "time_missing" }.field)
        assertTrue("16:00" in e.timeChips.map { it.value })
        assertFalse(e.canAddToCalendar)                             // nothing is added until a time is picked
        assertNull(e.calendar())
        // the chip fills the time and the event can be added, as a timed event
        s.tapTimeChip(0, e.timeChips.first { it.value == "16:00" })
        assertTrue(e.canAddToCalendar)
        assertEquals(false, e.calendar()!!.allDay)
        assertEquals("16:00", e.calendar()!!.begin.toLocalTime().toString())
    }

    @Test fun allDayIsOnlyTheUsersChoice() {
        val o = chat(Msg(true, "8:58 pm", "Look what came in the post"),
            Pic("9:02 pm", Triple("GARDEN PARTY", 60, 0), Triple("Hill Road Lawn", 22, 10), Triple("Saturday 28 November, all welcome", 22, 10), Triple("4:00 PM", 14, 10)),
            Msg(false, "9:04 pm", "Ooh nice, are you going?"))
        val s = ReviewSession.from(run(o), today)
        s.setAllDay(0, true)
        assertTrue(s.event(0).canAddToCalendar)
        assertEquals(true, s.event(0).calendar()!!.allDay)
    }

    @Test fun aDateLineAmongLargerLinesIsNeverASeparator() {
        // the date sits right under the picture's title and heading: the picture's own date, never the chat's date line
        val o = chat(Msg(true, "8:58 pm", "Look what came in the post"),
            Pic(null, Triple("GARDEN PARTY", 60, 0), Triple("ALL WELCOME", 44, 0), Triple("Sat 28 Nov 2026", 20, 120), Triple("Hill Road Lawn", 22, 10)),
            Msg(false, "9:04 pm", "Ooh nice, are you going?"))
        assertEquals(listOf("2026-11-28"), shown(run(o)).map { it.first })
    }

    @Test fun aRealSeparatorOneMessageAwayFromAPictureStaysTheAnchor() {
        // "Fri, 20 Nov 2026" has a message of the chat's size under it; the picture after that message does not make it a picture's line
        val o = chat(Msg(false, "8:50 pm", "Can we go tomorrow?"), Pic("9:02 pm", Triple("GARDEN PARTY", 60, 0), Triple("Bring a plate to share", 40, 10),
            Triple("Hill Road Lawn", 22, 10)), Msg(true, "9:05 pm", "Sure, see you there"))
        assertEquals("2026-11-21", shown(run(o)).single().first)
    }

    @Test fun theChatsDateLineStaysTheAnchorForRelativeWords() {
        // a picture with a time and no date; "tomorrow" is counted from the chat's date line (Fri 20 Nov), not from the picture
        val o = chat(Msg(true, "8:58 pm", "Look what came in the post"),
            Pic("9:02 pm", Triple("GARDEN PARTY", 60, 0), Triple("Bring a plate to share", 40, 10), Triple("Hill Road Lawn", 22, 10), Triple("4:00 PM", 16, 10)),
            Msg(false, "9:04 pm", "Let's go tomorrow!"), Msg(true, "9:05 pm", "Great, see you there"))
        assertEquals("2026-11-21", shown(run(o)).single().first)
    }
}
