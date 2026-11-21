package `in`.gauthama.snapslotify.core.chat

import `in`.gauthama.snapslotify.core.review.ReviewSession
import `in`.gauthama.snapslotify.core.vision.TryHarder
import `in`.gauthama.snapslotify.core.vision.TryHarderOutcome
import `in`.gauthama.snapslotify.core.vision.VisionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Time limits on model calls with a fake slow model: the call falls back to the code result at its limit, the model is
 * stopped (abort flag) and released after the stopped call returns, the timeout is counted, and the late answer is
 * dropped. The fake behaves like the native one: it runs until its abort flag is set, then returns what it had.
 */
class ModelTimeoutTest {

    companion object {
        /** A short wait that, like a native call, carries on through a Java interrupt. */
        fun pause() = try { Thread.sleep(5) } catch (_: InterruptedException) { }
    }

    private val today = LocalDate.of(2026, 11, 14)

    /** A slow text model: each generation runs [runMs], or until cancel() sets the abort flag; then it returns [late]. */
    private class SlowChat(val runMs: Long, val late: String = "Late title", val honoursAbort: Boolean = true) : ChatModel {
        val aborted = AtomicBoolean(false)
        val jobs = mutableListOf<ChatJob>()
        val released = CountDownLatch(1)
        val returned = CountDownLatch(1)
        var cancels = 0
        override fun warmUp() = true
        override fun generate(job: ChatJob, prompt: String): String {
            synchronized(jobs) { jobs += job }
            // like native code: a Java interrupt does not stop it, only the abort flag does
            try {
                val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(runMs)
                while (System.nanoTime() < until && !(honoursAbort && aborted.get())) pause()
                return late
            } finally {
                returned.countDown()
            }
        }
        override fun cancel() { cancels++; aborted.set(true) }
        override fun releaseAfterStop() { released.countDown() }
    }

    @Test(timeout = 5_000) fun aSlowPageTitleFallsBackStopsReleasesCountsAndDropsTheLateAnswer() {
        val m = SlowChat(runMs = 3_000)
        val timeouts = mutableListOf<String>()
        val t0 = System.nanoTime()
        val (titles, outcome) = ChatAssist(m, 150, onTimeout = { synchronized(timeouts) { timeouts += it } }).pageTitle(listOf("Kite Show", "Sat 12 Dec 11 am"))
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertEquals(JobOutcome.TIMEOUT, outcome)
        assertTrue("the late answer is dropped", titles.isEmpty())
        assertTrue("returned at the limit, took $ms ms", ms < 1_000)
        assertTrue("the model was told to stop", m.cancels >= 1)
        // the abort flag ended the slow generation early, then the model was released
        assertTrue(m.returned.await(1, TimeUnit.SECONDS))
        assertTrue(m.released.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("PAGE_TITLE"), timeouts)
    }

    @Test(timeout = 5_000) fun afterATimeoutNoOtherCallIsMadeForThatScreenshot() {
        val m = SlowChat(runMs = 3_000)
        val a = ChatAssist(m, 150)
        val s = a.suggest(listOf("hello", "Dinner on saturday 8 pm", "see you"), "Dinner", null, today)
        assertEquals(JobOutcome.TIMEOUT, s.date)
        assertTrue(s.titles.isEmpty())
        assertEquals(listOf(ChatJob.DATE_LINE), synchronized(m.jobs) { m.jobs.toList() })
        // and a later page title on the same assist is not tried either
        assertTrue(a.pageTitle(listOf("x")).first.isEmpty())
        assertEquals(listOf(ChatJob.DATE_LINE), synchronized(m.jobs) { m.jobs.toList() })
    }

    @Test(timeout = 5_000) fun aModelThatIgnoresTheAbortStillFallsBackAndIsReleasedOnlyWhenItReturns() {
        val m = SlowChat(runMs = 800, honoursAbort = false)
        val (titles, outcome) = ChatAssist(m, 100).pageTitle(listOf("Kite Show"))
        assertEquals(JobOutcome.TIMEOUT, outcome)
        assertTrue(titles.isEmpty())
        // still running: not released under it
        assertFalse(m.released.await(200, TimeUnit.MILLISECONDS))
        assertTrue(m.returned.await(2, TimeUnit.SECONDS))
        assertTrue(m.released.await(1, TimeUnit.SECONDS))
    }

    @Test fun eachCallTypeHasItsOwnLimit() {
        // 20 s for every text-model step, 30 s for every vision-model step
        for (job in ChatJob.entries) assertEquals(20_000L, ModelTimeouts.forJob(job))
        assertEquals(20_000L, ModelTimeouts.TEXT_COLD_LOAD_MS)
        assertEquals(30_000L, ModelTimeouts.VISION_GENERATION_MS)
        assertEquals(30_000L, ModelTimeouts.VISION_COLD_LOAD_MS)
    }

    /** A slow vision model, the same way. */
    private class SlowVision(val runMs: Long) : VisionModel {
        val aborted = AtomicBoolean(false)
        val released = CountDownLatch(1)
        var cancels = 0
        override fun warmUp() = true
        override fun read(rgb: ByteArray, width: Int, height: Int, prompt: String): String {
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(runMs)
            while (System.nanoTime() < until && !aborted.get()) pause()
            return """{"title":"Late Show","date":"12 December","time":"11 am"}"""
        }
        override fun cancel() { cancels++; aborted.set(true) }
        override fun releaseAfterStop() { released.countDown() }
    }

    @Test(timeout = 5_000) fun aSlowTryHarderReadFallsBackStopsReleasesAndDropsTheLateAnswer() {
        val m = SlowVision(runMs = 3_000)
        val timeouts = mutableListOf<String>()
        val r = TryHarder(m, 1_000, 150, onTimeout = { synchronized(timeouts) { timeouts += it } }).run(ByteArray(12), 2, 2, today)
        assertEquals(TryHarderOutcome.TIMEOUT, r.outcome)
        assertEquals(null, r.title)
        assertEquals(null, r.dateChip)
        assertTrue(m.cancels >= 1)
        assertTrue(m.released.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("vision_read"), timeouts)
    }

    @Test fun aModelTitleNeverReplacesATitleBeingTyped() {
        val s = ReviewSession(mutableListOf(`in`.gauthama.snapslotify.core.review.EventDraft(0, title = "Kite Show", dateIso = "2026-12-12")), noEventFound = false)
        s.event(0).titleBeingEdited = true
        assertFalse(s.applyModelTitle(0, "Kite Show", "Kite Show Sat 12 Dec"))   // same text: nothing to do
        assertTrue(s.applyModelTitle(0, "Show", "Kite Show Sat 12 Dec"))
        assertEquals("Kite Show", s.event(0).title)
        assertTrue("offered as a chip", "Show" in s.event(0).suggestedTitles)
        // not being typed in: still only a chip, the field keeps its title
        s.event(0).titleBeingEdited = false
        assertFalse(s.applyModelTitle(0, "Show", "Kite Show Sat 12 Dec"))
        assertEquals("Kite Show", s.event(0).title)
    }
}
