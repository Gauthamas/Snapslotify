package `in`.gauthama.snapslotify.core.diag

import `in`.gauthama.snapslotify.core.chat.ChatAssist
import `in`.gauthama.snapslotify.core.chat.ChatJob
import `in`.gauthama.snapslotify.core.chat.ChatModel
import `in`.gauthama.snapslotify.core.chat.ChatSuggestions
import `in`.gauthama.snapslotify.core.chat.JobOutcome
import `in`.gauthama.snapslotify.core.chat.Route
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch

class ShareDiagTest {

    private val ok = ChatSuggestions(warmUp = JobOutcome.OK, date = JobOutcome.OK, title = JobOutcome.OK)

    @Test fun outcomes() {
        assertEquals("ok", ShareDiag.outcome(Route.OTHER, false, null))
        assertEquals("ok", ShareDiag.outcome(Route.OTHER, true, null))
        assertEquals("no_model_file", ShareDiag.outcome(Route.CHAT, false, null))
        assertEquals("ok", ShareDiag.outcome(Route.CHAT, true, ok))
        assertEquals("ok", ShareDiag.outcome(Route.CHAT, true, ok.copy(date = JobOutcome.NONE, title = JobOutcome.INVALID)))
        assertEquals("model_timeout", ShareDiag.outcome(Route.CHAT, true, ok.copy(title = JobOutcome.TIMEOUT)))
        assertEquals("model_error", ShareDiag.outcome(Route.CHAT, true, ok.copy(date = JobOutcome.FAILED)))
        assertEquals("model_timeout", ShareDiag.outcome(Route.CHAT, true, ok.copy(date = JobOutcome.FAILED, title = JobOutcome.TIMEOUT)))
    }

    @Test fun keepsTheLastSharesWithTheirLines() {
        val lines = listOf("x", "start a", "a1", "start b", "b1", "start c", "c1", "c2")
        assertEquals(lines, ShareDiag.lastShares(lines, 3) { it.startsWith("start") })
        assertEquals(lines, ShareDiag.lastShares(lines, 200) { it.startsWith("start") })
        assertEquals(listOf("start b", "b1", "start c", "c1", "c2"), ShareDiag.lastShares(lines, 2) { it.startsWith("start") })
    }

    private class Fake(val hang: CountDownLatch? = null) : ChatModel {
        override fun warmUp() = true
        override fun generate(job: ChatJob, prompt: String): String {
            if (job == ChatJob.TITLE) hang?.await()
            return if (job == ChatJob.DATE_LINE) "saturday" else "Dinner"
        }
        override fun cancel() = Unit
    }

    private val lines = listOf("Dinner on saturday 8 pm")
    private val today = LocalDate.of(2026, 10, 29)

    @Test fun assistReportsEachStepInOrder() {
        val seen = mutableListOf<String>()
        ChatAssist(Fake(), 2_000, trace = { name, job, outcome -> seen += listOfNotNull(name, job, outcome).joinToString(" ") }).suggest(lines, "Dinner", null, today)
        assertEquals(
            listOf("warmup_start", "warmup_end OK", "call_start DATE_LINE", "call_end DATE_LINE OK", "call_start TITLE", "call_end TITLE OK"),
            seen,
        )
    }

    @Test fun aCutCallIsReportedAsATimeout() {
        val hang = CountDownLatch(1)
        val seen = mutableListOf<String>()
        ChatAssist(Fake(hang), 100, trace = { name, job, outcome -> seen += listOfNotNull(name, job, outcome).joinToString(" ") }).suggest(lines, "Dinner", null, today)
        hang.countDown()
        assertEquals("call_end TITLE TIMEOUT", seen.last())
    }
}
