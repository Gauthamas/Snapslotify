package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.ocr.OcrWord
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Routing of email screens, cards and adverts by layout ([`in`.gauthama.snapslotify.core.text.Chrome],
 * [`in`.gauthama.snapslotify.core.chat.ChatRouter.paragraphRun], [CodeExtractor]'s title rules). Invented screens,
 * placed inside a larger picture at ([X], [Y]).
 */
class EmailRouteTest {

    private val X = 200; private val Y = 300
    private val today = LocalDate.of(2026, 11, 11)

    private fun l(text: String, x: Int, y: Int, h: Int = 20, dx: Int = 0): OcrLine {
        val cw = h / 2
        var at = X + dx + x
        val words = text.split(' ').map { w -> OcrWord(w, at, Y + y, at + w.length * cw, Y + y + h).also { at += (w.length + 1) * cw } }
        return OcrLine(text, X + dx + x, Y + y, words.last().right, Y + y + h, words = words)
    }

    private fun page(ls: List<OcrLine>) = OcrResult(2400, 2400, ls.mapIndexed { i, x -> x.copy(block = i) }, 0)
    private fun run(ls: List<OcrLine>) = CodePipeline.run(page(ls), today, today)

    /** A mail app's message: status bar, subject, sender with sent time, "to me", body paragraph, tab bar. */
    private fun mail(body: List<String>, dx: Int = 0) = listOf(
        l("9:41", 20, 0, 14, dx), l("Weekend plans", 20, 60, 30, dx), l("Kavya Rao 8:05 AM", 90, 130, 20, dx), l("to me", 90, 156, 16, dx),
    ) + body.mapIndexed { k, t -> l(t, 20, 220 + 26 * k, 20, dx) } + listOf(l("Inbox", 40, 1100, 14, dx), l("Meet", 300, 1100, 14, dx))

    private val paragraph = listOf(
        "Hi all, hope the week is going well and everyone is rested",
        "What do you think of having a picnic at the lake on Sunday.",
        "We could bring the games and some food and stay until the",
        "evening if the weather holds up, let me know what suits you",
        "Thanks",
    )

    @Test fun anEmailWithParagraphsIsNotAChatOrAProgramme() {
        val r = run(mail(paragraph))
        assertNotEquals(PageRoute.CHAT, r.route!!.chosen)
        assertNotEquals(PageRoute.PROGRAMME, r.route!!.chosen)
        // the day is in a body sentence: the title is that sentence, not the subject or the tallest line
        val e = r.events.single().event
        assertTrue(e.title, e.title!!.contains("picnic at the lake"))
    }

    @Test fun aCardsTitleIsTheHeadingAboveItsDateLine() {
        // a booking card in an email: the heading right above the date line, not the larger greeting below
        val r = run(listOf(l("9:41", 20, 0, 14), l("Your booking at Fern Hall", 20, 60, 24), l("Table at Fern Hall", 30, 140, 18),
            l("Fri, Nov 20 at 7:30 PM", 30, 166, 18), l("Party size", 60, 220, 14), l("Fern Hall 9:15 AM", 60, 300, 18), l("to me", 60, 324, 14),
            l("Enjoy your evening with us", 20, 400, 30), l("Inbox", 40, 1100, 14), l("Meet", 300, 1100, 14)))
        val e = r.events.single().event
        assertEquals("Table at Fern Hall", e.title)
        assertEquals("7:30 PM", e.time)
    }

    @Test fun aSecondCopyOfTheScreenSideBySideIsReadOnce() {
        // two phone screens side by side (before and after): the same card twice gives one event, not a programme
        val card = { dx: Int -> listOf(l("9:41", 20, 0, 14, dx), l("Your booking at Fern Hall", 20, 60, 24, dx), l("Table at Fern Hall", 30, 140, 18, dx),
            l("Fri, Nov 20 at 7:30 PM", 30, 166, 18, dx), l("Party size", 60, 220, 14, dx), l("Inbox", 40, 1100, 14, dx)) }
        val r = run(card(0) + card(800))
        assertNotEquals(PageRoute.PROGRAMME, r.route!!.chosen)
        assertEquals(1, r.events.size)
        assertEquals("2026-11-20", r.events.single().resolved.start.toString())
    }

    @Test fun anAdvertsHeadlineAroundAPhoneIsNotTheTitle() {
        // an advert: a large headline, then a phone screen (small status-bar clock) with a booking on it
        val r = run(listOf(l("Plan trips faster", 0, 0, 44), l("with smart cards", 0, 50, 44), l("9:41", 40, 190, 12),
            l("Table at Fern Hall", 40, 240, 18), l("Fri, Nov 20 at 7:30 PM", 40, 266, 18), l("Party size", 60, 320, 14), l("Inbox", 60, 900, 14)))
        val k = r.cleaned.kept.map { it.text }
        assertTrue(k.toString(), "Plan trips faster" !in k && "with smart cards" !in k)
        assertEquals("Table at Fern Hall", r.events.single().event.title)
    }

    @Test fun aPostersOwnTimeUnderItsTitleIsNotAStatusBar() {
        // a poster: a large title, then its time in ordinary type, a date block beside: nothing is an advert frame
        val r = run(listOf(l("BOOK", 300, 0, 80), l("SWAP", 300, 90, 80), l("10:00", 300, 260, 40), l("City Library", 300, 340, 40),
            l("SAT", 0, 380, 48), l("21", 0, 440, 130), l("NOV", 0, 580, 50), l("2026", 0, 640, 34), l("Entry free", 300, 480, 28)))
        assertTrue(r.cleaned.removed.none { it.rule.contains("advert") || it.rule.contains("status bar") })
    }

    @Test fun aChatWithLongMessagesIsStillAChat() {
        // bubbles on both sides with stamps; one long message wraps over lines, never across the whole screen
        val r = run(listOf(l("Riya", 20, 0, 24), l("online", 20, 30, 14),
            l("dinner on Sat 14 Nov at 8 pm?", 20, 100), l("9:12 PM", 260, 124, 12),
            l("sure, and shall we ask the others", 420, 170), l("to come along as well this time", 420, 194),
            l("if they are free that evening", 420, 218), l("and bring something sweet along", 420, 242), l("9:14 PM", 700, 266, 12),
            l("ok", 20, 300), l("9:15 PM", 60, 324, 12), l("Message", 20, 1000, 18)))
        assertEquals(PageRoute.CHAT, r.route!!.chosen)
    }
}
