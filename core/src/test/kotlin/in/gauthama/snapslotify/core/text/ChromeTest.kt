package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.ocr.OcrWord
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * App chrome taken out by position and shape ([Chrome]). Invented screens; the phone is placed inside a larger picture
 * (offset [X], [Y]) so nothing can lean on the picture's own edges.
 */
class ChromeTest {

    private val X = 300; private val Y = 500                    // where the "phone" sits in the picture
    private val today = LocalDate.of(2026, 11, 11)

    /** A line at (x, y) inside the phone, with a word box per word (char width h/2), [h] tall; [gap] extra pixels before word [gapAt]. */
    private fun l(text: String, x: Int, y: Int, h: Int = 20, gap: Int = 0, gapAt: Int = -1): OcrLine {
        val cw = h / 2
        var at = X + x
        val parts = text.split(' ')
        val words = parts.mapIndexed { k, w ->
            if (k == gapAt) at += gap
            OcrWord(w, at, Y + y, at + w.length * cw, Y + y + h).also { at += (w.length + 1) * cw }
        }
        return OcrLine(text, X + x, Y + y, words.last().right, Y + y + h, words = words)
    }

    private fun page(vararg ls: OcrLine) = OcrResult(2000, 2400, ls.mapIndexed { i, x -> x.copy(block = i) }, 0)
    private fun kept(o: OcrResult) = CodePipeline.run(o, today, today).cleaned.kept.map { it.text }
    private fun event(o: OcrResult) = CodePipeline.run(o, today, today).events.single().event

    /** An email screen: status bar, subject, sender + recipients (indented by the avatar), body, a tab bar at the foot. */
    private fun email(body: List<String>) = page(
        *listOf(l("9:41", 20, 0, 14), l("Planning meeting", 20, 60, 30), l("Arun Mehta 8:05 AM", 90, 130), l("to me", 90, 156, 16)).toTypedArray(),
        *body.mapIndexed { k, t -> l(t, 20, 220 + 30 * k) }.toTypedArray(),
        l("Inbox", 40, 1100, 14), l("Chats", 300, 1100, 14),
    )

    @Test fun theStatusBarSenderLineAndTabBarAreNotTheMessage() {
        val k = kept(email(listOf("Can we meet on Friday 13 November 2026 to plan the trip", "around the office? Let me know.")))
        assertTrue(k.toString(), "9:41" !in k && "Arun Mehta 8:05 AM" !in k && "to me" !in k && "Inbox" !in k && "Chats" !in k)
        assertTrue("Planning meeting" in k)
    }

    @Test fun aSentTimeIsNeverTheEventTimeAndTheBodyTimeWins() {
        // no time in the body: the event is all day, not at the sent time
        assertEquals(null, event(email(listOf("Can we meet on Friday 13 November 2026 to plan the trip", "around the office? Let me know."))).time)
        // a time in the body is the event's time
        assertEquals("3 pm", event(email(listOf("Can we meet on Friday 13 November 2026 at 3 pm to plan", "the trip around the office? Let me know."))).time)
    }

    @Test fun aMessageStampAtTheEndOfALineIsCutOff() {
        // a chat: the stamp sits after a gap wider than the line's own word gaps, a minute from the next message's stamp:
        // cut; the words stay
        val o = page(l("Riya", 20, 0, 24), l("online", 20, 30, 14), l("dinner on Sat 14 Nov works for me 9:12 PM", 20, 100, 20, gap = 60, gapAt = 8),
            l("great see you", 300, 160), l("9:13 PM", 420, 184, 12), l("Message", 20, 1000, 18))
        val k = kept(o)
        assertTrue(k.toString(), "dinner on Sat 14 Nov works for me" in k)
        // a time inside the sentence (ordinary spacing) is kept
        val p = page(l("Riya", 20, 0, 24), l("online", 20, 30, 14), l("dinner on Sat 14 Nov at 8 PM works", 20, 100), l("great see you", 300, 160),
            l("9:13 PM", 420, 184, 12), l("Message", 20, 1000, 18))
        assertTrue("dinner on Sat 14 Nov at 8 PM works" in kept(p))
    }

    @Test fun aSeparatorKeepsItsDayButNotItsTime() {
        // a chat (presence line, input bar); the separator is centred in the text's frame (x 20 to 350)
        val o = page(l("Riya", 20, 0, 24), l("online", 20, 30, 14), l("dinner tomorrow? lets fix a place", 20, 100, 20), l("ok see you then", 200, 140, 20),
            l("Today 6:02 PM", 140, 60, 14), l("sure", 20, 180, 20), l("Message", 20, 1000, 18))
        val k = kept(o)
        assertTrue(k.toString(), "Today" in k && "Today 6:02 PM" !in k)
    }

    @Test fun anEmojiLedBodyLineStaysButAButtonBarGoes() {
        // in the body, an icon glyph before a place or a time is the message's own
        val o = page(l("You're invited to Asha's housewarming", 20, 300), l("? Lake View Apartments, Pune", 20, 340), l("9 7 PM onwards on Sat 21 Nov", 20, 380),
            l("+ Add to calendar", 20, 1000, 18), l("Party time", 20, 0, 22))
        val k = kept(o)
        assertTrue(k.toString(), "? Lake View Apartments, Pune" in k && "9 7 PM onwards on Sat 21 Nov" in k)
        assertTrue(k.toString(), "+ Add to calendar" !in k)
    }

    @Test fun aRowStampBesideItsMessageGoesButATimeReplyStays() {
        // a chat: one message with its stamp on the same row, then a reply that is only a time
        val o = page(l("Riya", 20, 0, 24), l("online", 20, 30, 14), l("dinner on Sat 14 Nov?", 20, 100), l("9:12 PM", 300, 102, 18),
            l("8 pm?", 330, 160), l("Message", 20, 1000, 18))
        val r = CodePipeline.run(o, today, today)
        val k = r.cleaned.kept.map { it.text }
        assertTrue(k.toString(), "9:12 PM" !in k && "8 pm?" in k)
    }

    @Test fun aChatsRunOfStampsGoesButAProgrammesTimeColumnStays() {
        // a chat: three messages, each with its stamp beside it, all within minutes; and a last stamp in small type with a
        // stray mark read after it. None is an event's time: the page has no event
        val chat = page(l("Riya", 20, 0, 24), l("online", 20, 30, 14),
            l("are you there", 20, 100), l("6:01 PM", 300, 102, 18), l("yes just got home", 20, 160), l("6:02 PM", 300, 162, 18),
            l("ok talk later", 20, 220), l("6:04 PM", 300, 222, 18), l("bye then", 20, 280), l("6:05 PMx", 300, 306, 12), l("Message", 20, 1000, 18))
        assertTrue(CodePipeline.run(chat, today, today).events.isEmpty())
        // a programme: titles with their times beside them, across the day: the times stay
        val prog = page(l("Riya", 20, 0, 24), l("online", 20, 30, 14), l("Welcome talk on Sat 21 Nov", 20, 100), l("9:00 AM", 400, 102),
            l("Workshop", 20, 160), l("11:30 AM", 400, 162), l("Closing", 20, 220), l("4:30 PM", 400, 222), l("Message", 20, 1000, 18))
        assertTrue(kept(prog).containsAll(listOf("9:00 AM", "11:30 AM", "4:30 PM")))
    }

    @Test fun aMonthOrWeekdayLineIsNeverFurniture() {
        // on an app screen (status bar), a day badge's month far below the rest stays: it is part of a date
        val o = page(l("9:41", 20, 0, 14), l("Club notices", 20, 60, 30), l("Quiz night on Fri 20 Nov 2026 at 7 pm in the hall", 20, 120),
            l("Bring a friend, everyone is welcome to join us", 20, 150), l("DEC", 20, 1000, 18), l("Inbox", 200, 1000, 14))
        val k = kept(o)
        assertTrue(k.toString(), "DEC" in k && "Inbox" !in k)
    }

    @Test fun aCalendarsLowestEntriesAreNotAFooter() {
        // a poster ending in a short line far below the rest: two short lines at most go, on a page with no app screen
        val o = page(l("Book Fair", 20, 0, 40), l("Sat 21 Nov 2026, 10 am", 20, 80), l("City Library", 20, 120),
            l("Entry free for children under twelve and their parents", 20, 1000), l("Bring a bag", 20, 1030), l("No pets please", 20, 1060))
        val k = kept(o)
        assertTrue(k.toString(), "Bring a bag" in k && "No pets please" in k)
    }
}
