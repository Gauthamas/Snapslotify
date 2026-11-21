package `in`.gauthama.snapslotify.core.pipeline

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.ocr.OcrWord
import `in`.gauthama.snapslotify.core.review.ReviewSession
import `in`.gauthama.snapslotify.core.text.SenderStamps
import `in`.gauthama.snapslotify.core.text.StackedDates
import `in`.gauthama.snapslotify.core.text.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Mutation tests for programme cards (date tiles, titles beside or above their dates), bill providers, sender stamps,
 * the page's own date line, past-tense messages on a counted day, chat presence lines, post stamps, wrapped clauses and
 * headings set over several lines. Invented pages only; today is Sat 14 Nov 2026.
 */
class TitleAndPastFixesTest {

    private val today = LocalDate.of(2026, 11, 14)

    private fun l(text: String, x: Int, y: Int, h: Int = 26): OcrLine {
        val cw = h / 2
        var at = x
        val words = text.split(' ').map { w -> OcrWord(w, at, y, at + w.length * cw, y + h).also { at += (w.length + 1) * cw } }
        return OcrLine(text, x, y, words.last().right, y + h, words = words)
    }

    private fun page(w: Int, h: Int, ls: List<OcrLine>) = OcrResult(w, h, ls.mapIndexed { i, x -> x.copy(block = i) }, 0)
    private fun run(o: OcrResult) = CodePipeline.run(o, today, today)
    private fun events(r: PipelineResult) = ReviewSession.from(r, today).let { s ->
        if (s.noEventFound) emptyList() else s.events.map { Triple(it.dateIso, if (it.allDay) null else it.startIso, it.title) }
    }

    // --- programme cards ----------------------------------------------------------------------------------------------

    /** A card: the day number large on the left, its month small under it, the title beside it, time and venue under the title. */
    private fun card(y: Int, day: String, month: String, title: String, time: String, titleOnDayLine: Boolean = false): List<OcrLine> =
        if (titleOnDayLine) listOf(l("$day $title", 130, y, 44), l("$time Hall B", 256, y + 50, 22), l(month, 138, y + 58, 16))
        else listOf(l(day, 140, y, 44), l(title, 257, y + 4, 36), l("$time Hall B", 256, y + 48, 22), l(month, 138, y + 60, 16))

    private fun programme(vararg cards: List<OcrLine>) =
        page(720, 1600, listOf(l("RIVERSIDE ARTS CLUB", 67, 79, 16), l("Winter Days", 70, 108, 76)) + cards.flatMap { it })

    @Test fun aDateTileReadsItsOwnMonthAndItsCardsTitleAndTime() {
        val r = run(programme(
            card(260, "29", "NOV", "Kite Making", "9:30 am"),
            card(410, "2", "DEC", "Story Hour", "11 am"),
            card(560, "2", "DEC", "Choir Practice", "3 pm"),
            card(710, "5", "DEC", "Lantern Walk", "6 pm"),
        ))
        assertEquals(listOf(
            Triple("2026-11-29", "09:30", "Kite Making"), Triple("2026-12-02", "11:00", "Story Hour"),
            Triple("2026-12-02", "15:00", "Choir Practice"), Triple("2026-12-05", "18:00", "Lantern Walk"),
        ), events(r).sortedWith(compareBy({ it.first }, { it.second })))
    }

    @Test fun aTitleOnTheDayNumbersLineIsTheCardsTitle() {
        // OCR joins some cards' day number and title into one line ("12 Cycling Rally"), among cards read apart
        val r = run(programme(
            card(260, "11", "JAN", "Poetry Evening", "10 am"),
            card(410, "11", "JAN", "Film Club", "12 pm"),
            card(560, "12", "JAN", "Chess Meet", "10 am"),
            card(710, "12", "JAN", "Cycling Rally", "1:30 pm", titleOnDayLine = true),
            card(860, "12", "JAN", "Craft Fair", "3:30 pm"),
            card(1010, "16", "JAN", "Book Swap", "1:30 pm", titleOnDayLine = true),
        ))
        val e = events(r)
        assertTrue(e.toString(), Triple("2027-01-12", "13:30", "Cycling Rally") in e)
        assertTrue(e.toString(), Triple("2027-01-16", "13:30", "Book Swap") in e)
        assertEquals(6, e.size)
    }

    @Test fun aMonthUnderABodyLinesNumberIsNotJoined() {
        // same type size: "1st" over "August -3rd" in a paragraph is not a date tile
        val lines = listOf(l("Applications open 1st", 40, 300, 22), l("August -3rd week, see the site", 40, 330, 22))
        val out = StackedDates.tidy(TextCleaner.Result(lines, emptyList()))
        assertEquals(lines.map { it.text }, out.kept.map { it.text })
    }

    @Test fun aTitlePrintedJustAboveItsDateIsThatDatesTitleNotTheNextOne() {
        val ls = mutableListOf(l("OPEN AIR FILMS", 223, 197, 94))
        var y = 680
        for ((t, d) in listOf("The Quiet Harbour" to "Friday, 19 February · 8:00 PM", "Salt and Smoke" to "Saturday, 20 February · 7:45 PM",
            "Paper Kites" to "Friday, 26 February · 8:00 PM")) {
            ls += l(t, 140, y, 78); ls += l(d, 142, y + 100, 48); y += 245
        }
        assertEquals(listOf("The Quiet Harbour", "Salt and Smoke", "Paper Kites"), events(run(page(1536, 2200, ls))).sortedBy { it.first }.map { it.third })
    }

    // --- bills --------------------------------------------------------------------------------------------------------

    @Test fun aBillsProviderIsItsSenderItsSignOffOrItsNameLineNeverItsHeading() {
        val email = page(720, 1600, listOf(l("Your water bill for March 2027", 33, 72, 30), l("From: Lakeside Water Board <billing@example.invalid>", 34, 136, 22),
            l("Dear Customer,", 35, 272), l("Your bill is ready. Total amount payable: Rs 812.40.", 33, 365), l("Due date", 60, 450, 22), l("15 Mar 2027", 600, 450, 22)))
        assertTrue(events(run(email)).single().third!!.startsWith("Pay Lakeside Water Board"))
        val sms = page(720, 1600, listOf(l("AX-NORTHG", 29, 71), l("Text message SMS", 29, 114, 22),
            l("Dear Customer, your gas bill of", 59, 277, 28), l("Rs. 640.10 is due on 12.03.2027.", 59, 319, 28),
            l("Pay via the app to avoid late fee. -Northgate Gas", 59, 361, 28)))
        assertTrue(events(run(sms)).single().third!!.startsWith("Pay Northgate Gas"))
        val letter = page(720, 1600, listOf(l("Hillview Council", 34, 76), l("Property Tax Demand Notice", 62, 209, 33), l("Rs 4,210.00", 64, 272, 54),
            l("Amount payable", 62, 330, 22), l("Due Date 22 Mar 2027", 62, 369), l("Assessment No.", 61, 421, 19)))
        assertEquals("Pay Hillview Council property tax bill", events(run(letter)).single().third)
    }

    // --- sender stamps, the page's date line, past tense -------------------------------------------------------------

    @Test fun senderStampsAreTheMessagesStampsAndThePagesDate() {
        val ls = listOf(l("14 January 2027", 331, 162, 19),
            l("Kiran 14/01/2027 09:47", 122, 217, 25), l("Nice", 123, 256, 22),
            l("Asha 14/01/2027 09:51", 122, 318, 28), l("Paid the rent on 27 Dec", 122, 359, 22),
            l("you 14/01/2027 09:55", 132, 420, 24), l("Pottery class on 20 Jan at 6 pm, coming?", 122, 458, 22),
            l("Asha 14/01/2027 09:57", 122, 520, 26), l("Did you eat?", 121, 557, 31))
        val r = run(page(720, 1600, ls))
        assertTrue(r.cleaned.removed.count { it.rule == SenderStamps.RULE } == 4)
        // the headers are no events, the page's own date line is none, a date before the page's date is past
        assertEquals(listOf("2027-01-20" to "18:00"), events(r).map { it.first to it.second })
    }

    @Test fun oneNameAndDateLineIsNotASenderStamp() {
        assertFalse(SenderStamps.tidy(TextCleaner.Result(listOf(l("Kiran 14/01/2027 09:47", 122, 217)), emptyList())).removed.isNotEmpty())
    }

    /** A chat: header with a presence line, a date chip, bubbles with small stamps, the message box. */
    private fun chat(vararg msgs: String): OcrResult {
        val ls = mutableListOf(l("Meera", 113, 87, 28), l("last seen today at 10:55 AM", 113, 119, 18), l("Jan 14", 330, 170, 20))
        var y = 230
        for ((k, m) in msgs.withIndex()) {
            ls += l(m, if (k % 2 == 0) 45 else 300, y, 26); y += 38
            ls += l("5:1$k pm", if (k % 2 == 0) 45 else 600, y, 15); y += 60
        }
        ls += l("Message", 45, 1545, 26)
        return page(720, 1612, ls)
    }

    @Test fun aPastTenseMessageOnTheChatsDateIsNoEvent() {
        assertEquals(emptyList<Any>(), events(run(chat("Hey there", "The parcel was delivered at 4:15 pm", "Great, thanks"))))
        assertEquals(emptyList<Any>(), events(run(chat("Hey there", "Lunch was great today", "Glad you liked it"))))
    }

    @Test fun aPastTenseMessageThatMovesAPlanStaysAnEvent() {
        val e = events(run(chat("Hey there", "The dinner was moved to tomorrow at 8 pm", "Okay, noted")))
        assertEquals(listOf("2027-01-15" to "20:00"), e.map { it.first to it.second })
    }

    @Test fun thePresenceLinesTimeIsNeverTheEvents() {
        val r = run(chat("Hey there", "Movie tonight?", "Sure"))
        assertTrue(r.cleaned.removed.any { it.rule == "presence line" })
        assertTrue(events(r).none { it.second == "10:55" })
    }

    @Test fun aPostsOwnStampWithAColonForItsDotIsNotAnEvent() {
        val ls = listOf(l("Maple Hill Club", 130, 112, 33), l("Dec 11 at 4:40 PM : Public", 130, 154, 18), l("Mark your calendars:", 31, 220, 27),
            l("Book Swap - Sun 3 Jan, 11:00 AM, Central Library", 31, 260, 28), l("Quiz Night - Fri 15 Jan, 7 PM, Orchid Hall", 31, 302, 25),
            l("49 comments 12 shares", 530, 794, 23))
        assertEquals(listOf("2027-01-03", "2027-01-15"), events(run(page(720, 1600, ls))).mapNotNull { it.first }.sorted())
    }

    // --- wrapped clauses and headings over several lines ---------------------------------------------------------------

    @Test fun aClauseWrappedOverALineIsReadWhole() {
        val ls = listOf(l("riverside.events", 109, 94, 31), l("We are hosting the poetry evening on", 25, 959, 25),
            l("Wednesday, 10 February 2027 at 4:30 PM and the blood", 43, 992, 31), l("donation camp on Thu 18 Feb at 5 pm. Venue: Town Hall.", 25, 1032, 25),
            l("Hope to see you there!", 26, 1069, 27), l("1,263 likes", 25, 911, 26))
        val title: String? = events(run(page(720, 1600, ls))).firstOrNull { it.first == "2027-02-18" }?.third
        assertEquals("blood donation camp", title?.lowercase())
    }

    @Test fun aHeadingSetOverCentredLinesIsOneTitle() {
        val ls = listOf(l("THIS SEASON", 447, 96, 20), l("Moonlight", 230, 149, 152), l("Walk", 444, 290, 96),
            l("Tue, 9 Feb 2027", 387, 572, 54), l("10:30 AM", 499, 655, 41), l("Riverside Park", 425, 756, 31))
        assertEquals("Moonlight Walk", events(run(page(1240, 1754, ls))).single().third)
    }
}
