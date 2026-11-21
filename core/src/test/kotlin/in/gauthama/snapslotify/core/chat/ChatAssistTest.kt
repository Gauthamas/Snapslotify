package `in`.gauthama.snapslotify.core.chat

import `in`.gauthama.snapslotify.core.extract.Bubble
import `in`.gauthama.snapslotify.core.extract.ChatContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChatAssistTest {

    private val today = LocalDate.of(2026, 10, 29)
    private val lines = listOf("hello", "Dinner on saturday 8 pm", "see you")

    private class Fake(
        val warm: () -> Boolean = { true },
        val date: () -> String = { "saturday" },
        val title: () -> String = { "Dinner" },
    ) : ChatModel {
        val prompts = mutableListOf<Pair<ChatJob, String>>()
        var cancels = 0
        override fun warmUp() = warm()
        override fun generate(job: ChatJob, prompt: String): String {
            synchronized(prompts) { prompts += job to prompt }
            return if (job == ChatJob.DATE_LINE) date() else title()
        }
        override fun cancel() { cancels++ }
    }

    private fun assist(model: ChatModel, timeout: Long = 2_000) = ChatAssist(model, timeout)

    @Test fun bothJobsGiveChips() {
        val s = assist(Fake()).suggest(lines, "Dinner", "saturday", today, shotDate = today)   // anchored on the screenshot date
        assertEquals(listOf("Dinner"), s.titles)
        assertEquals("saturday", s.datePhrase)
        assertEquals("2026-10-31", s.dateChip?.value)
        assertEquals(1, s.dateChip?.line)
        assertEquals(JobOutcome.OK, s.date)
        assertEquals(JobOutcome.OK, s.title)
    }

    @Test fun promptsAreTheFixedPrefixPlusTheChat() {
        val m = Fake()
        assist(m).suggest(lines, "Dinner", null, today)
        val (dateJob, datePrompt) = m.prompts[0]
        val (titleJob, titlePrompt) = m.prompts[1]
        assertEquals(ChatJob.DATE_LINE, dateJob)
        assertEquals(ChatPrompts.DATE_PREFIX + "Dinner\nLines:\nDinner on saturday 8 pm\n\nCandidate phrases:\n- saturday", datePrompt)
        assertEquals(ChatJob.TITLE, titleJob)
        assertEquals(ChatPrompts.TITLE_PREFIX + "hello\nDinner on saturday 8 pm\nsee you", titlePrompt)
    }

    @Test fun withTheCodeBubblesTheTitlePromptIsTheMarkedDateBubbleTwoEachSideAndTheMessageDate() {
        val b = listOf("a", "b", "c", "sat 21 nov 6 pm?", "d", "e", "f").map { Bubble(listOf(0), it, true) }
        val m = Fake()
        val s = assist(m).suggest(lines, "Dinner", null, today, ChatContext(b, 3, "Thu, 5 Nov 2026"))
        val titlePrompt = m.prompts.single { it.first == ChatJob.TITLE }.second
        assertEquals(ChatPrompts.TITLE_PREFIX + "Message date: Thu, 5 Nov 2026\nb\nc\n[date] sat 21 nov 6 pm?\nd\ne", titlePrompt)
        assertEquals(listOf("Dinner"), s.titles)   // a chip only; the code title stays the default
    }

    @Test fun withoutADateBubbleTheTitlePromptIsAsBefore() {
        val m = Fake()
        assist(m).suggest(lines, "Dinner", null, today, ChatContext(emptyList(), null, null))
        assertEquals(ChatPrompts.TITLE_PREFIX + "hello\nDinner on saturday 8 pm\nsee you", m.prompts.single { it.first == ChatJob.TITLE }.second)
    }

    private val tomorrowLines = listOf("hello", "yoga class tomorrow 6 am", "see you")
    private fun tomorrowModel() = Fake(date = { "tomorrow" }, title = { "Yoga class" })

    @Test fun theModelChipCountsFromTheChatsDateLine() {
        val ctx = ChatContext(listOf(Bubble(listOf(1), "yoga class tomorrow 6 am", true)), 0, "Mon, 19 Oct 2026")
        val s = assist(tomorrowModel()).suggest(tomorrowLines, null, null, today, ctx, shotDate = LocalDate.of(2026, 11, 5))
        assertEquals("2026-10-20", s.dateChip?.value)
    }

    @Test fun withOnlyATodayLineTheModelChipCountsFromTheScreenshotDate() {
        val ctx = ChatContext(listOf(Bubble(listOf(1), "yoga class tomorrow 6 am", true)), 0, "Today")
        val s = assist(tomorrowModel()).suggest(tomorrowLines, null, null, today, ctx, shotDate = LocalDate.of(2026, 11, 5))
        assertEquals("2026-11-06", s.dateChip?.value)
        val y = assist(tomorrowModel()).suggest(tomorrowLines, null, null, today, ctx.copy(messageDate = "Yesterday"), LocalDate.of(2026, 11, 5))
        assertEquals("2026-11-05", y.dateChip?.value)
    }

    @Test fun aWeekdayLineAnchorsTheModelChipOnTheScreenshotDate() {
        val ctx = ChatContext(listOf(Bubble(listOf(1), "yoga class tomorrow 6 am", true)), 0, "Monday")
        assertEquals("2026-11-03", assist(tomorrowModel()).suggest(tomorrowLines, null, null, today, ctx, LocalDate.of(2026, 11, 5)).dateChip?.value)
        // no screenshot date: counted from today (29 Oct 2026, a Thursday): Monday 26 Oct, tomorrow 27 Oct
        assertEquals("2026-10-27", assist(tomorrowModel()).suggest(tomorrowLines, null, null, today, ctx, null).dateChip?.value)
    }

    @Test fun withNoScreenshotDateTheModelChipCountsFromToday() {
        val ctx = ChatContext(listOf(Bubble(listOf(1), "yoga class tomorrow 6 am", true)), 0, null)
        val s = assist(tomorrowModel()).suggest(tomorrowLines, null, null, today, ctx, shotDate = null)
        assertEquals("tomorrow", s.datePhrase)
        assertEquals("2026-10-30", s.dateChip?.value)
    }

    @Test fun noCodeTitleSaysUnknown() {
        val m = Fake()
        assist(m).suggest(lines, null, null, today)
        assertTrue(m.prompts[0].second.startsWith(ChatPrompts.DATE_PREFIX + "unknown\n"))
    }

    @Test fun anAnswerOutsideTheCandidatesIsDropped() {
        val s = assist(Fake(date = { "next tuesday" })).suggest(lines, "Dinner", null, today)
        assertEquals(JobOutcome.INVALID, s.date)
        assertNull(s.dateChip)
        assertNull(s.datePhrase)
        assertEquals(listOf("Dinner"), s.titles)
    }

    @Test fun noneGivesNoDateChip() {
        val s = assist(Fake(date = { "NONE" })).suggest(lines, "Dinner", null, today)
        assertEquals(JobOutcome.NONE, s.date)
        assertNull(s.dateChip)
    }

    @Test fun failureInOneJobKeepsTheOther() {
        val s = assist(Fake(date = { error("native failure") })).suggest(lines, "Dinner", null, today)
        assertEquals(JobOutcome.FAILED, s.date)
        assertNull(s.dateChip)
        assertEquals(listOf("Dinner"), s.titles)
    }

    @Test fun everyFailureGivesNoSuggestionsAndNeverThrows() {
        val s = assist(Fake(date = { error("x") }, title = { throw OutOfMemoryError() })).suggest(lines, "Dinner", null, today)
        assertEquals(JobOutcome.FAILED, s.date)
        assertEquals(JobOutcome.FAILED, s.title)
        assertTrue(s.titles.isEmpty())
        assertNull(s.dateChip)
    }

    @Test fun aModelThatCannotLoadIsFallback() {
        val m = Fake(warm = { false })
        val s = assist(m).suggest(lines, "Dinner", null, today)
        assertEquals(JobOutcome.FAILED, s.warmUp)
        assertTrue(m.prompts.isEmpty())
        assertTrue(s.titles.isEmpty())
    }

    @Test fun aHungWarmUpTimesOut() {
        val hang = CountDownLatch(1)
        val m = Fake(warm = { hang.await(); true })
        val s = assist(m, 100).suggest(lines, "Dinner", null, today)
        hang.countDown()
        assertEquals(JobOutcome.TIMEOUT, s.warmUp)
        assertTrue(m.cancels >= 1)
        assertTrue(s.titles.isEmpty())
    }

    @Test(timeout = 5_000) fun aHungCallIsCutAtTheTimeoutAndTheModelIsStopped() {
        val hang = CountDownLatch(1)
        val m = Fake(date = { hang.await(); "saturday" })
        val t0 = System.nanoTime()
        val s = assist(m, 200).suggest(lines, "Dinner", null, today)
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        hang.countDown()
        assertEquals(JobOutcome.TIMEOUT, s.date)
        assertNull(s.dateChip)
        assertTrue(m.cancels >= 1)
        // after a timeout the code result stands: no title is asked for this screenshot (ModelTimeoutTest)
        assertTrue(s.titles.isEmpty())
        assertTrue("took $ms ms", ms < 2_000)
    }


    @Test(timeout = 5_000) fun closingTheScreenStopsTheModelAndTheRest() {
        val hang = CountDownLatch(1)
        val m = Fake(date = { hang.await(); "saturday" })
        val a = assist(m, 60_000)
        var result: ChatSuggestions? = null
        val runner = Thread { result = a.suggest(lines, "Dinner", null, today) }
        runner.start()
        while (synchronized(m.prompts) { m.prompts.isEmpty() }) Thread.sleep(5)
        runner.interrupt()
        runner.join()
        hang.countDown()
        assertTrue(m.cancels >= 1)
        assertEquals(1, m.prompts.size)
        assertTrue(result!!.titles.isEmpty())
    }

    @Test fun afterCancelNothingRuns() {
        val m = Fake()
        val a = assist(m)
        a.cancel()
        val s = a.suggest(lines, "Dinner", null, today)
        assertTrue(m.prompts.isEmpty())
        assertTrue(s.titles.isEmpty())
    }

    @Test fun severalTitlesSplitAndCleanUp() {
        val s = assist(Fake(title = { " `Lunch ; Movie` " })).suggest(lines, null, null, today)
        assertEquals(listOf("Lunch", "Movie"), s.titles)
    }

    @Test fun aRamblingTitleIsDropped() {
        val s = assist(Fake(title = { "this is a very long answer that goes on well past any title" })).suggest(lines, null, null, today)
        assertEquals(JobOutcome.INVALID, s.title)
        assertTrue(s.titles.isEmpty())
    }

    @Test fun noCandidatesSkipsTheDateJobOnly() {
        val m = Fake()
        val s = assist(m).suggest(listOf("hello", "how are you"), null, null, today)
        assertEquals(JobOutcome.SKIPPED, s.date)
        assertEquals(1, m.prompts.size)
        assertEquals(ChatJob.TITLE, m.prompts[0].first)
    }

    @Test fun prefixesMatchThePromptsFile() {
        val file = File("../benchmarks/chat_cases/PROMPTS.md")
        assumeTrue("PROMPTS.md not present", file.exists())
        val text = file.readText().replace("\r\n", "\n")
        fun block(name: String) = text.substringAfter("```$name\n").substringBefore("\n```")
        assertEquals(block("title").substringBefore("{chat_lines}"), ChatPrompts.TITLE_PREFIX)
        assertEquals(block("date_line").substringBefore("{title}"), ChatPrompts.DATE_PREFIX)
        assertEquals(block("date_line").substringAfter("{title}"), "\nLines:\n{selected_lines}\n\nCandidate phrases:\n{candidates}")
    }
}
