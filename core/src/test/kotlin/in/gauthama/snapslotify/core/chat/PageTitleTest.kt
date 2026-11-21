package `in`.gauthama.snapslotify.core.chat

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.review.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The model's title for any page: a chip on its own event, never the field ([ChatAssist.pageTitle]). Invented pages. */
class PageTitleTest {

    private val today = LocalDate.of(2026, 11, 11)

    private class Fake(val answer: () -> String = { "Pottery workshop" }, val warm: Boolean = true) : ChatModel {
        val prompts = mutableListOf<Pair<ChatJob, String>>()
        override fun warmUp() = warm
        override fun generate(job: ChatJob, prompt: String): String { prompts += job to prompt; return answer() }
        override fun cancel() = Unit
    }

    private fun page(vararg ls: String) = OcrResult(1000, 1600,
        ls.mapIndexed { i, t -> OcrLine(t, 40, 100 + 40 * i, 40 + t.length * 10, 124 + 40 * i, confidence = 0.9f, block = i) }, 0)

    @Test fun thePromptAsksForTheMeaningAndReadsTheLinesAroundTheDate() {
        val r = CodePipeline.run(page("Hello all,", "Weekend notice", "The pottery workshop is on 21 Nov 2026 at 10 am.", "Bring an apron."), today, today)
        val m = Fake()
        val (titles, outcome) = ChatAssist(m).pageTitle(r.titleContext(0))
        assertEquals(listOf("Pottery workshop"), titles)
        assertEquals(JobOutcome.OK, outcome)
        val (job, prompt) = m.prompts.single()
        assertEquals(ChatJob.PAGE_TITLE, job)
        assertTrue(prompt.startsWith(ChatPrompts.PAGE_TITLE_PREFIX))
        assertTrue(prompt.contains("in your own words, keeping its meaning"))
        assertTrue(prompt.contains("The pottery workshop is on 21 Nov 2026 at 10 am."))
    }

    @Test fun theChipGoesOnItsOwnEventAndTheFieldKeepsTheCodeTitle() {
        val r = CodePipeline.run(page("The pottery workshop is on 21 Nov 2026 at 10 am."), today, today)
        val s = ReviewSession.from(r, today)
        val code = s.events[0].title
        assertTrue(s.addPageTitle(0, "Pottery class"))
        assertEquals(code, s.events[0].title)
        assertEquals(listOf("Pottery class"), s.events[0].suggestedTitles)
        // the same title again, or one the field already holds, is not offered twice
        assertFalse(s.addPageTitle(0, "pottery class"))
        assertFalse(s.addPageTitle(0, code!!))
        // no such event: nothing
        assertFalse(s.addPageTitle(5, "Anything"))
    }

    @Test fun noModelOrNoAnswerGivesNoChip() {
        assertEquals(JobOutcome.FAILED, ChatAssist(Fake(warm = false)).pageTitle(listOf("a line")).second)
        assertEquals(JobOutcome.NONE, ChatAssist(Fake({ "NONE" })).pageTitle(listOf("a line")).second)
        assertTrue(ChatAssist(Fake({ "NONE" })).pageTitle(listOf("a line")).first.isEmpty())
        assertEquals(JobOutcome.SKIPPED, ChatAssist(Fake()).pageTitle(emptyList()).second)
    }
}
