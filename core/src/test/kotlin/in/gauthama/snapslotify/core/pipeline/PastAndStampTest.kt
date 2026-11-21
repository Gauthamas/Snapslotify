package `in`.gauthama.snapslotify.core.pipeline

import `in`.gauthama.snapslotify.core.extract.PageRoute
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.ocr.OcrWord
import `in`.gauthama.snapslotify.core.review.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Mutation tests for past dates, closing dates, chips-only pages and the time-stamp size rule ([CodePipeline.sortOut],
 * [SmallStamp]). Invented pages only; today is Sat 14 Nov 2026.
 */
class PastAndStampTest {

    private val today = LocalDate.of(2026, 11, 14)

    /** A line with a word box per word (char width h/2), [h] tall. */
    private fun l(text: String, x: Int, y: Int, h: Int = 26): OcrLine {
        val cw = h / 2
        var at = x
        val words = text.split(' ').map { w -> OcrWord(w, at, y, at + w.length * cw, y + h).also { at += (w.length + 1) * cw } }
        return OcrLine(text, x, y, words.last().right, y + h, words = words)
    }

    private fun page(w: Int, h: Int, ls: List<OcrLine>) = OcrResult(w, h, ls.mapIndexed { i, x -> x.copy(block = i) }, 0)

    private fun run(o: OcrResult, shot: LocalDate? = today) = CodePipeline.run(o, today, shot).also { r ->
        if (System.getenv("PST_DEBUG") != null) println("DBG route=${r.route?.chosen}/${r.route?.how} chat=${r.cleaned.looksLikeChat} removed=${r.cleaned.removed.map { it.rule + ":" + it.line.text }} " +
            "events=${r.events.map { it.event.date + "|" + it.event.time + "|" + it.resolved.start }} drops=${r.drops} amb=${r.ambiguous.map { it.event.date + "|" + it.event.time + "|" + it.resolved.start + "|" + it.resolved.alternatives }} kept=${r.cleaned.kept.map { it.text }}")
    }
    private fun shown(r: PipelineResult) = ReviewSession.from(r, today).let { s ->
        if (s.noEventFound) emptyList() else s.events.map { it.dateIso to (if (it.allDay) null else it.startIso) }
    }

    // --- chats --------------------------------------------------------------------------------------------------------

    /** A bubble: wrapped lines, left (incoming) or right, each followed by its small sent time. */
    private class B(val incoming: Boolean, val stamp: String, vararg val lines: String)

    /** A chat 720 x 1612 scaled by [k] (type and boxes): contact header, bubbles with a small time under each, message box. */
    private fun chat(k: Double, vararg bubbles: B, picture: List<Pair<String, Int>> = emptyList()): OcrResult {
        fun s(v: Int) = (v * k).toInt()
        val ls = mutableListOf(l("Meera", s(113), s(87), s(28)), l("online", s(113), s(119), s(18)))
        var y = 205
        for (b in bubbles) {
            for (t in b.lines) {
                val w = minOf(11 * t.length, 600)
                ls += l(t, s(if (b.incoming) 45 else 675 - w), s(y), s(26)); y += 38
            }
            ls += l(b.stamp, s(if (b.incoming) 45 else 600), s(y), s(15)); y += 70
        }
        if (picture.isNotEmpty()) {
            // a poster shared into the chat: a tall block of lines of mixed sizes on the left
            for ((t, h) in picture) { ls += l(t, s(60), s(y), s(h)); y += h + 24 }
            ls += l("9:02 pm", s(45), s(y), s(15)); y += 70
        }
        ls += l("Message", s(45), s(1545), s(26))
        return page(s(720), s(1612), ls)
    }

    @Test fun aScaledChatKeepsItsPlanAndItsSmallSentTimesAreNeverTheEvent() {
        for (k in listOf(1.0, 1.6, 2.2)) {
            val r = run(chat(k,
                B(true, "7:40 pm", "Are we still on for dinner?"),
                B(false, "7:42 pm", "Yes! Olive Tree on Sat 21 Nov at 8 pm"),
                B(true, "7:43 pm", "Perfect, see you then"),
            ))
            assertEquals("scale $k", listOf("2026-11-21" to "20:00"), shown(r))
            assertTrue("scale $k", r.cleaned.kept.none { it.text.trim() in setOf("7:40 pm", "7:42 pm", "7:43 pm") })
        }
    }

    @Test fun aPosterSharedInsideAChatIsNotReadForStamps() {
        val r = run(chat(1.0,
            B(true, "8:58 pm", "Look what came in the post"),
            picture = listOf("GARDEN PARTY" to 60, "Saturday 28 November" to 34, "Lawn, Hill Road" to 22, "4:00 PM" to 16),
        ))
        // the picture is not read for size stamps, and its date is the event's
        assertTrue(r.cleaned.removed.none { it.rule == "size stamp" })
        assertEquals(listOf("2026-11-28"), shown(r).map { it.first })
        // known gap, not the size rule: the cleaner's bubble-timestamp rule still takes the picture's last line ("4:00 PM")
    }

    // --- emails -------------------------------------------------------------------------------------------------------

    /** An email screen: status bar, subject, sender + recipients, body lines (each with its own height), a tab bar. */
    private fun email(body: List<Pair<String, Int>>): OcrResult {
        val ls = mutableListOf(l("9:41", 20, 0, 14), l("Quarterly review", 20, 60, 30), l("Arun Mehta 8:05 AM", 90, 130, 20), l("to me", 90, 156, 16))
        var y = 260
        for ((t, h) in body) { ls += l(t, 20, y, h); y += h + 14 }
        ls += l("Inbox", 40, 1100, 14); ls += l("Chats", 300, 1100, 14)
        return page(800, 1200, ls)
    }

    @Test fun aSmallPrintEventLineInAnEmailBodyIsKept() {
        val r = run(email(listOf(
            "Hi all, thanks for the notes from last week." to 20,
            "The review is booked, details below." to 20,
            "Thu 3 Dec, 6:30 PM" to 13,
            "Please bring the updated figures." to 20,
            "Thanks, Arun" to 20,
        )))
        assertTrue(r.cleaned.removed.none { it.rule == "size stamp" })
        assertEquals(listOf("2026-12-03" to "18:30"), shown(r))
    }

    // --- posts --------------------------------------------------------------------------------------------------------

    /** A social post 1080 x 1920: author, caption lines (text, height), the post's own stamp, the counts row. */
    private fun post(caption: List<Pair<String, Int>>, stamp: String = "10:25 AM · Nov 12, 2026"): OcrResult {
        val ls = mutableListOf(l("Lakeside Runners", 140, 120, 32), l("@lakesiderun", 140, 160, 22))
        var y = 260
        for ((t, h) in caption) { ls += l(t, 40, y, h); y += h + 18 }
        ls += l(stamp, 40, y + 40, 20)
        ls += l("128 Reposts 14 Quotes 902 Likes", 40, y + 100, 22)
        return page(1080, 1920, ls)
    }

    @Test fun aSmallDateLineInAPostCaptionIsKeptAndThePostStampIsNot() {
        val r = run(post(listOf(
            "Our winter 10k is open to everyone." to 30,
            "Bring a friend and a flask." to 30,
            "Sun 6 Dec · 7:00 AM" to 18,
            "Start line at the boathouse." to 30,
            "All paces welcome." to 30,
        )))
        assertEquals(listOf("2026-12-06" to "07:00"), shown(r))
        // the post's own stamp is out, and it is still the page's date (dropped as a stamp, not shown)
        assertTrue(r.drops.any { it.reason.startsWith("stamp") && it.date == "2026-11-12" })
    }

    // --- posters ------------------------------------------------------------------------------------------------------

    @Test fun aTinyDateLineOnAPosterIsTheEventTheSizeRuleStaysOff() {
        val o = page(1080, 1528, listOf(
            l("AUTUMN", 140, 200, 120), l("PRINT FAIR", 140, 340, 120),
            l("Local makers, prints and zines", 140, 520, 40), l("Old Mill Hall", 140, 600, 40),
            l("Sun 6 Dec 2026 · 5:00 PM", 140, 1400, 18),
        ))
        val r = run(o, shot = null)
        assertEquals(null, SmallStamp.kind(r.cleaned, r.route!!))
        assertEquals(listOf("2026-12-06" to "17:00"), shown(r))
    }

    // --- past and closing dates ------------------------------------------------------------------------------------------

    private fun poster(vararg ls: OcrLine) = page(1080, 1528, listOf(l("CITY BOOK CLUB", 140, 200, 90)) + ls)

    @Test fun anEventTodayStaysEvenWhenItsTimeHasPassed() {
        val r = run(poster(l("Monthly meet-up", 140, 360, 40), l("Sat 14 Nov 2026, 9:00 AM", 140, 440, 40)))
        assertEquals(listOf("2026-11-14" to "09:00"), shown(r))
        assertTrue(r.drops.isEmpty())
    }

    @Test fun aPastEventIsDroppedAndNotShown() {
        val r = run(poster(l("Monthly meet-up", 140, 360, 40), l("Fri 13 Nov 2026, 6:00 PM", 140, 440, 40)))
        assertEquals(emptyList<Pair<String?, String?>>(), shown(r))
        assertEquals(listOf(Drop("2026-11-13", "past")), r.drops)
        // no event left: no past line either (the no-event card shows)
        assertEquals(null, ReviewSession.from(r, today).note)
    }

    @Test fun aClosingDateIsNotAnEvent() {
        val r = run(poster(l("Members' discount on all titles", 140, 360, 40), l("Offer ends 30 Nov 2026", 140, 440, 40)))
        assertEquals(emptyList<Pair<String?, String?>>(), shown(r))
        assertEquals(listOf(Drop("2026-11-30", "deadline")), r.drops)
    }

    @Test fun aTimeWithNoDateIsOfferedAsAChipNotPrefilled() {
        val r = run(chat(1.0, B(true, "5:10 pm", "Coffee at the corner cafe at 6:30 pm?"), B(false, "5:12 pm", "Sounds good")))
        val s = ReviewSession.from(r, today)
        assertTrue(s.noEventFound && s.chipsOnly)
        assertEquals(ReviewSession.CHIPS_NOTE, s.note)
        assertEquals("18:30", s.events.single().timeChips.first().value)
        assertEquals(null, s.events.single().dateIso)
    }

    @Test fun aDateWithNoYearJustPastIsTwoDatesAndOfferedAsAChip() {
        // 10 Nov, seen on 14 Nov: just past, or next year's
        val s = ReviewSession.from(run(poster(l("Monthly meet-up", 140, 360, 40), l("10 November", 140, 440, 40))), today)
        assertTrue(s.chipsOnly)
        // with its weekday written and fitting this year's 10 Nov (a Tuesday): past, dropped
        val r = run(poster(l("Monthly meet-up", 140, 360, 40), l("Tuesday, 10 November", 140, 440, 40)))
        assertEquals(emptyList<Pair<String?, String?>>(), shown(r))
        assertEquals(listOf(Drop("2026-11-10", "past")), r.drops)
        // a date with no year further off is the next one, prefilled
        assertEquals(listOf("2026-12-10" to null), shown(run(poster(l("Monthly meet-up", 140, 360, 40), l("10 December", 140, 440, 40)))))
    }

    @Test fun anOverdueBillGivesNoEvent() {
        val o = page(1000, 1400, listOf(
            l("CITYPOWER ELECTRICITY", 60, 80, 40), l("Electricity bill", 60, 160, 30),
            l("Amount due", 60, 300, 26), l("Rs. 1,240.00", 600, 300, 26),
            l("Due date", 60, 360, 26), l("02 Nov 2026", 600, 360, 26),
            l("Bill date", 60, 420, 26), l("18 Oct 2026", 600, 420, 26),
        ))
        val r = run(o, shot = null)
        assertEquals(PageRoute.BILL, r.route!!.chosen)
        assertEquals(emptyList<Pair<String?, String?>>(), shown(r))
        assertTrue(r.drops.any { it.reason == "past" && it.date == "2026-11-02" })
    }

    @Test fun aProgrammeKeepsItsComingSessionsAndCountsThePastOnes() {
        val rows = listOf("Tue 10 Nov 2026" to "Opening talk", "Thu 12 Nov 2026" to "Printing workshop", "Wed 18 Nov 2026" to "Book binding",
            "Fri 20 Nov 2026" to "Zine swap", "Wed 25 Nov 2026" to "Closing party")
        val ls = mutableListOf(l("WINTER WORKSHOPS", 80, 80, 70), l("Schedule", 80, 180, 36))
        // day headings, then a row per session: the time, then the title in its own column
        rows.forEachIndexed { i, (d, t) -> ls += l(d, 80, 280 + 160 * i, 36); ls += l("6:00 PM", 80, 340 + 160 * i, 28); ls += l(t, 400, 340 + 160 * i, 28) }
        val r = run(page(1080, 1200 + 0 * 1, ls), shot = null)
        assertEquals(PageRoute.PROGRAMME, r.route!!.chosen)
        assertEquals(listOf("2026-11-18", "2026-11-20", "2026-11-25"), shown(r).map { it.first })
        assertEquals(listOf("2026-11-10", "2026-11-12"), r.drops.filter { it.reason == "past" }.map { it.date })
        // the first thing on the review: a line saying the past sessions were left out
        assertEquals("2 past dates on this page were left out.", ReviewSession.from(r, today).note)
    }

    @Test fun aProgrammeAllInThePastSaysSo() {
        val rows = listOf("Mon 2 Nov 2026" to "Opening talk", "Wed 4 Nov 2026" to "Printing workshop", "Fri 6 Nov 2026" to "Book binding",
            "Mon 9 Nov 2026" to "Closing party")
        val ls = mutableListOf(l("WINTER WORKSHOPS", 80, 80, 70), l("Schedule", 80, 180, 36))
        // day headings, then a row per session: the time, then the title in its own column
        rows.forEachIndexed { i, (d, t) -> ls += l(d, 80, 280 + 160 * i, 36); ls += l("6:00 PM", 80, 340 + 160 * i, 28); ls += l(t, 400, 340 + 160 * i, 28) }
        val r = run(page(1080, 1200 + 0 * 1, ls), shot = null)
        assertEquals(PageRoute.PROGRAMME, r.route!!.chosen)
        val s = ReviewSession.from(r, today)
        assertTrue(s.noEventFound)
        assertFalse(s.chipsOnly)
        assertEquals("All dates on this page are in the past.", s.note)
    }
}
