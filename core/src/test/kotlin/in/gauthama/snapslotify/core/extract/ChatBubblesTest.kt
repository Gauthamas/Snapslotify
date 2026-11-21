package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.pipeline.PipelineResult
import `in`.gauthama.snapslotify.core.review.Field
import `in`.gauthama.snapslotify.core.review.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Invented chat screenshots (720 x 1612, laid out like the dev chats), through the app's code path. */
class ChatBubblesTest {

    /** A bubble: its wrapped lines, on the left (incoming) or the right. */
    private class B(val incoming: Boolean, vararg val lines: String)

    /** A message date line between bubbles ("Mon, 19 Oct 2026", "Today"), centred. */
    private class Day(val text: String)

    private fun chat(vararg items: Any): OcrResult {
        val lines = mutableListOf(
            OcrLine("10:10", 29, 20, 77, 36), OcrLine("Ravi (Plumber)", 113, 87, 300, 115), OcrLine("online", 113, 119, 167, 137),
        )
        var y = 205
        for (item in items) {
            when (item) {
                is Day -> {
                    val w = 10 * item.text.length
                    lines += OcrLine(item.text, 360 - w / 2, y, 360 + w / 2, y + 20)
                    y += 70
                }
                is B -> {
                    for (t in item.lines) {
                        val w = minOf(11 * t.length, 600)
                        val left = if (item.incoming) 45 else 675 - w
                        lines += OcrLine(t, left, y, left + w, y + 26)
                        y += 26 + 12
                    }
                    y += 50
                }
            }
        }
        lines += OcrLine("Message", 45, 1545, 154, 1571)
        return OcrResult(720, 1612, lines, 0)
    }

    private fun run(today: LocalDate, vararg items: Any): PipelineResult = CodePipeline.run(chat(*items), today)

    private val oct19 = LocalDate.of(2026, 10, 19)

    @Test fun ravi_tomorrowIsCountedFromTheMessageDateAndFlagged() {
        val r = run(oct19, Day("Mon, 19 Oct 2026"), B(true, "Hello sir, this is Ravi. I will come", "tomorrow at 6 pm to fix the kitchen tap."))
        val e = r.events.single()
        assertEquals(LocalDate.of(2026, 10, 20), e.resolved.start)
        assertEquals("6 pm", e.event.time)
        assertEquals("Mon, 19 Oct 2026", e.event.messageDate)
        assertEquals("Mon, 19 Oct 2026", r.chat?.messageDate)
        val codes = e.flags.map { it.code }
        assertTrue(codes.toString(), "message_date" in codes && "relative_date" !in codes)
        val draft = ReviewSession.from(r, oct19).events.single()
        assertEquals("2026-10-20", draft.dateIso)
        assertEquals("18:00", draft.startIso)
        assertTrue(draft.dateNeedsConfirm)
        assertEquals("Counted from the message date, Mon, 19 Oct 2026. Is this date right?", draft.flags.first { it.code == "message_date" }.sentence)
        assertTrue(e.event.title!!, e.event.title!!.contains("fix the kitchen tap"))
    }

    @Test fun dayAfterTomorrowAndAMessageDateWithoutAYear() {
        val e = run(oct19, Day("Mon, 19 Oct"), B(false, "lunch day after tomorrow, 1pm")).events.single()
        assertEquals(LocalDate.of(2026, 10, 21), e.resolved.start)
    }

    @Test fun aTodayOrYesterdayDateLineOrNoneCountsFromTodayWithNoScreenshotDate() {
        // today is 19 Oct; "Yesterday" is 18 Oct, so tomorrow is 19 Oct
        for ((day, want) in listOf(Day("Today") to 20, Day("Yesterday") to 19, null to 20)) {
            val items = listOfNotNull(day, B(true, "I will come tomorrow at 6 pm to fix the kitchen tap.")).toTypedArray()
            val e = run(oct19, *items).events.single()
            assertEquals(day?.text, LocalDate.of(2026, 10, want), e.resolved.start)
            assertNull(day?.text, e.event.messageDate)
            assertTrue(day?.text, "today_date" in e.flags.map { it.code } && "relative_date" !in e.flags.map { it.code })
        }
        assertEquals("Today", run(oct19, Day("Today"), B(true, "come tomorrow at 6 pm")).chat?.messageDate)
    }

    private fun title(vararg items: Any): String? = run(LocalDate.of(2026, 11, 5), Day("Thu, 5 Nov 2026"), *items).events.single().event.title

    @Test fun theTitleInsideTheDateBubble() {
        assertEquals("Badminton match", title(B(true, "ok cool"), B(false, "Badminton match on 12 Nov at 7 pm?"), B(true, "Pottery class is fun")))
    }

    @Test fun theTitleInTheEarlierBubble() {
        assertEquals("Are you free for the review meeting",
            title(B(true, "Are you free for the review meeting?"), B(false, "tue 17 nov?"), B(true, "3 pm works for me")))
    }

    @Test fun theTitleInTheLaterBubble() {
        assertEquals("dinner at Dosa House", title(B(true, "how was your day"), B(false, "sat 21 nov 6 pm?"), B(true, "Yes, dinner at Dosa House")))
    }

    @Test fun anAcknowledgementNextToTheDateIsSkipped() {
        // the same sender's "ok sure, see you then" comes first by side, but says nothing
        assertEquals("Team lunch with the interns",
            title(B(true, "Team lunch with the interns"), B(false, "fri 20 nov 1 pm"), B(false, "ok sure, see you then")))
    }

    @Test fun nothingUsableGivesNoTitle() {
        // two bubbles away is too far; "how was your day" has one content word; the later bubble holds another date
        assertNull(title(B(true, "Pottery workshop at the studio"), B(true, "how was your day"), B(false, "sat 21 nov 6 pm?"),
            B(true, "sunday dinner would be better")))
    }

    @Test fun aChatWithTwoEventsIsFlaggedLikeMsg36() {
        // the text of the dev chat msg_36: two events in one message, of which the code gives one
        val r = run(LocalDate.of(2026, 10, 30), Day("Fri, 30 Oct 2026"), B(true, "Dentist tue 10am, then haircut wed 4pm"))
        // weekdays only, no date: nothing prefilled, the times offered as chips; the flags stay on the unsettled event
        val e = r.ambiguous.single()
        assertTrue(e.flags.map { it.code }.toString(), e.flags.map { it.code }.containsAll(listOf("multiple_events", "multiple_event_dates")))
        val s = ReviewSession.from(r, LocalDate.of(2026, 10, 30))
        assertTrue(s.noEventFound && s.chipsOnly)
        assertEquals(setOf("10am", "4pm"), s.events.single().timeChips.map { it.label }.toSet())
    }

    @Test fun twoTimesOnTheSameDayFlagTheTimeOnly() {
        val e = run(LocalDate.of(2026, 11, 5), Day("Thu, 5 Nov 2026"), B(true, "Standup moved to 10am Monday"),
            B(true, "can we make it 11am Monday instead")).ambiguous.single()   // weekday only: chips, flags kept
        val codes = e.flags.map { it.code }
        assertTrue(codes.toString(), "multiple_events" in codes && "multiple_event_dates" !in codes)
    }

    @Test fun oneEventWithAnotherTimeOrAPastDayIsNotFlagged() {
        val e = run(LocalDate.of(2026, 11, 5), Day("Thu, 5 Nov 2026"), B(true, "reached home at 9:40 yesterday"),
            B(false, "badminton sat 7 nov 6 pm?"), B(true, "6 pm on sat works")).events.single()
        assertTrue(e.flags.map { it.code }.toString(), "multiple_events" !in e.flags.map { it.code })
    }

    private val oct16 = LocalDate.of(2026, 10, 16)

    /** The older messages above the Ravi case in rel_01 (talk about an invoice, message times as OCR reads them). */
    private val rel01Older = arrayOf<Any>(
        Day("Fri, 16 Oct 2026"), B(true, "call me when free"), B(false, "haha that is so true"), B(true, "it is raining heavily here"),
        B(true, "where did you keep the charger send the invoice please"), B(true, "that restaurant was too expensive"),
        B(false, "my kitchen tap is leaking badly 20:41"), Day("Mon, 19 Oct 2026"), B(true, "Good morning sir"),
        B(true, "this is Ravi, the plumber o9:12"), B(true, "I will come to fix the kitchen tap tomorrow 6 pm"), B(false, "15:48"),
    )

    @Test fun billWordsInAChatDoNotMakeItABill_rel01() {
        val e = run(oct16, *rel01Older).events.single()
        assertEquals(false, e.event.bill)
        assertEquals(LocalDate.of(2026, 10, 20), e.resolved.start)
        assertEquals("6 pm", e.event.time)   // never the message times o9:12, 20:41 or 15:48
        assertTrue(e.flags.map { it.code }.toString(), "message_date" in e.flags.map { it.code } && "time_guess" !in e.flags.map { it.code })
    }

    @Test fun aBillMessageInAChatIsStillABillByItsOtherWords() {
        val e = run(oct16, Day("Fri, 16 Oct 2026"), B(true, "Your electricity bill of Rs 1,842 is due on 20 Oct 2026")).events.single()
        assertEquals(true, e.event.bill)
    }

    @Test fun messageTimesWithOForZeroAreNotTimes() {
        assertEquals("at 09:12", `in`.gauthama.snapslotify.core.text.OcrNormalizer.normalizeNumeric("at o9:12"))
        val r = run(oct16, Day("Fri, 16 Oct 2026"), B(true, "the wifi is down again o9:14 lol"), B(true, "O8:05"), B(true, "lunch sat 17 oct?"))
        assertTrue(r.candidates.times.map { it.raw }.toString(), r.candidates.times.isEmpty())
    }

    @Test fun aTimeWrappedOntoTheNextLineIsFoundEvenOutOfOcrOrder() {
        val ocr = chat(Day("Sun, 1 Nov 2026"), B(true, "dinner at Harbor Grill tonight 8:30", "pm? 10:15"))
        val shuffled = OcrResult(ocr.imageWidth, ocr.imageHeight, ocr.lines.reversed(), 0)
        val e = CodePipeline.run(shuffled, LocalDate.of(2026, 11, 1)).events.single()
        assertEquals(LocalDate.of(2026, 11, 1), e.resolved.start)
        assertEquals("8:30 pm", e.event.time?.replace(Regex("""\s+"""), " "))
    }

    @Test fun aChatTimeIsFlaggedOnlyWhenItIsInDoubt() {
        fun read(vararg items: Any) = run(LocalDate.of(2026, 11, 5), Day("Thu, 5 Nov 2026"), *items)
        // an event with no date is offered as chips only; its flags are the same
        fun codes(vararg items: Any) = read(*items).let { it.events + it.ambiguous }.single().flags.map { it.code }
        // in the date's bubble: no flag, no note
        assertTrue("time_guess" !in codes(B(true, "badminton sat 7 nov 6 pm?")))
        // one clear time a few bubbles after the date: no flag, but the page note says where the time came from
        val near = read(B(true, "badminton sat 7 nov?"), B(false, "how was your day"), B(true, "ok 6 pm then"))
        assertTrue("time_guess" !in near.events.single().flags.map { it.code })
        assertTrue(ReviewSession.from(near, LocalDate.of(2026, 11, 5)).note!!.endsWith("Times were read from nearby messages. Check them."))
        // no date at all, nothing to measure from: one clear time is taken, two are in doubt
        assertTrue("time_guess" !in codes(B(true, "dinner at 8 pm?")))
        assertTrue("time_guess" in codes(B(true, "dinner at 8 pm?"), B(false, "can we do 9 pm")))
        // two times in the chat: either could be the event's
        assertTrue("time_guess" in codes(B(true, "badminton sat 7 nov?"), B(false, "6 pm?"), B(true, "make it 7 pm")))
        // a range, or an approximate time, a bubble after the date
        assertTrue("time_guess" in codes(B(true, "badminton sat 7 nov?"), B(false, "6-8 pm")))
        assertTrue("time_guess" in codes(B(true, "badminton sat 7 nov?"), B(false, "around 6 pm")))
        // the only time is in the bubble before the date's: not in the date's message or the ones after it
        assertTrue("time_guess" in codes(B(false, "free at 6 pm"), B(true, "badminton sat 7 nov?")))
    }

    @Test fun olderDatesWithoutATimeOrRelativeWordAreNotEvents_noisy20() {
        // the messages of the dev chat noisy_20: two past dates and a cancelled one
        val r = run(LocalDate.of(2026, 10, 30), Day("Fri, 30 Oct 2026"), B(false, "not yet"), B(false, "paid the bill on 15 Oct"),
            B(false, "traffic is crazy"), B(true, "Forget the 3 Nov plan. Lets do 6 Nov, 4pm at the cafe."), B(false, "will do"),
            B(true, "we met on 8 Oct at the mall"))
        val e = r.events.single()
        assertEquals(LocalDate.of(2026, 11, 6), e.resolved.start)
        assertEquals("4pm", e.event.time)
        assertTrue("multiple_events" !in e.flags.map { it.code })
    }

    @Test fun aWeekdayWordKeepsTheChatsFlaggedDay_noisy13() {
        // the messages of the dev chat noisy_13: two past dates, and the real day only as "saturday"
        val r = run(LocalDate.of(2026, 10, 30), Day("Fri, 30 Oct 2026"), B(false, "traffic is crazy"), B(true, "we met on 8 Oct at the mall"),
            B(true, "Sure"), B(false, "hi, what's up"), B(true, "when are you free for the review?"), B(false, "saturday works"),
            B(false, "11 am?"), B(false, "ok noted"), B(true, "[ Photo ]"), B(true, "my birthday was on 3 Oct, remember?"))
        // a time with no date: chips only, not prefilled
        assertTrue(r.events.isEmpty())
        val e = r.ambiguous.single()
        assertNull(e.resolved.start)   // not 8 Oct or 3 Oct
        assertEquals("11 am", e.event.time)
        assertTrue(e.flags.map { it.code }.toString(), "relative_date" in e.flags.map { it.code })
    }

    @Test fun twoBubblesWithPlannedDatesKeepTheLatestAndAreFlagged() {
        val e = run(LocalDate.of(2026, 10, 30), Day("Fri, 30 Oct 2026"), B(true, "movie on 7 Nov at 6 pm?"), B(false, "paid the bill on 15 Oct"),
            B(true, "or 8 Nov at 7 pm"), B(false, "ok")).events.single()
        assertEquals(LocalDate.of(2026, 11, 8), e.resolved.start)
        assertTrue(e.flags.map { it.code }.toString(), e.flags.map { it.code }.containsAll(listOf("multiple_events", "multiple_event_dates")))
    }

    @Test fun theChatContextWindowIsTheDateBubbleAndTwoEachSide() {
        val r = run(LocalDate.of(2026, 11, 5), Day("Thu, 5 Nov 2026"), B(true, "a one"), B(true, "b two"), B(true, "c three"),
            B(false, "sat 21 nov 6 pm?"), B(true, "d four"), B(true, "e five"), B(true, "f six"))
        val (window, k) = r.chat!!.window()
        assertEquals(listOf("b two", "c three", "sat 21 nov 6 pm?", "d four", "e five"), window.map { it.text })
        assertEquals(2, k)
        assertEquals(false, window[2].incoming)
    }

    @Test fun separatePlansInAChatAreOneEventEach() {
        val r = run(LocalDate.of(2026, 11, 20), Day("Fri, 20 Nov 2026"), B(true, "Pickup from the station on 3 Dec at 11 am"), B(false, "ok"),
            B(true, "Also the dentist appointment on 9 Dec at 4 pm"))
        assertEquals(listOf(LocalDate.of(2026, 12, 3), LocalDate.of(2026, 12, 9)), r.events.map { it.resolved.start })
        assertEquals(listOf("11 am", "4 pm"), r.events.map { it.event.time })
    }

    @Test fun aRevisedDateStaysOnePlan() {
        val r = run(LocalDate.of(2026, 11, 20), Day("Fri, 20 Nov 2026"), B(true, "dinner on 3 Dec at 8 pm?"), B(false, "or 4 Dec at 8 pm"))
        assertEquals(LocalDate.of(2026, 12, 4), r.events.single().resolved.start)
    }

    @Test fun aDeadlineDoesNotSplitTheChat() {
        val r = run(LocalDate.of(2026, 11, 20), Day("Fri, 20 Nov 2026"), B(true, "Last date for the hostel form is 30 Nov.", "Welcome talk on 5 Dec, 10 am."))
        assertEquals(1, r.events.size)
    }

    @Test fun aDateBeforeTheMessageDateIsAPastMention() {
        val r = run(LocalDate.of(2026, 11, 20), Day("Fri, 20 Nov 2026"), B(true, "thanks for coming on 2 Nov, it was lovely"), B(false, "anytime"))
        assertTrue(r.events.toString(), r.events.none { it.resolved.start == LocalDate.of(2026, 11, 2) })
    }

    @Test fun aSeparatorWithASentTimeIsNotAnEvent() {
        val r = run(LocalDate.of(2026, 11, 20), Day("20 Nov, 09:12"), B(true, "photos from the trip"), B(false, "lovely"))
        assertTrue(r.events.toString(), r.events.isEmpty())
    }

    @Test fun twoPlansInOneMessageKeepTheirOwnDates() {
        val r = run(LocalDate.of(2026, 11, 20), Day("Fri, 20 Nov 2026"), B(true, "Science fair on 3 Dec, 11 am.", "Parents meeting on 9 Dec, 4 pm."))
        assertEquals(listOf(LocalDate.of(2026, 12, 3), LocalDate.of(2026, 12, 9)), r.events.map { it.resolved.start })
    }
}
