package `in`.gauthama.snapslotify.core.ocr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrEngineSwitchTest {

    private object Image : OcrImage {
        override val width = 10
        override val height = 10
    }

    private class Fake(override val name: String, val output: OcrResult) : OcrEngine<OcrImage> {
        var closed = false
        override suspend fun recognize(image: OcrImage): OcrResult {
            check(!closed) { "a closed engine was used" }
            return output
        }
        override fun close() { closed = true }
    }

    private val saved = OcrJson.decode(
        """{"width":100,"height":200,"ocr_ms":12,"lines":[{"block":0,"line":0,"text":"Book Fair","boundingBox":{"left":1,"top":2,"right":30,"bottom":12},"confidence":0.9}]}""",
    )
    private val other = OcrResult(100, 200, listOf(OcrLine("other", 0, 0, 1, 1, 0.5f)), 99)
    private val made = mutableListOf<Fake>()
    private var choice = OcrEngineKind.MLKIT

    private fun switch() = OcrEngineSwitch<OcrImage>(
        mapOf(
            OcrEngineKind.MLKIT to { Fake("mlkit", saved).also { made += it } },
            OcrEngineKind.PADDLE to { Fake("paddle", other).also { made += it } },
        ),
    ) { choice }

    @Test fun theFlagSelectsTheEngine() {
        val s = switch()
        assertEquals("mlkit", s.current().name)
        choice = OcrEngineKind.PADDLE
        assertEquals("paddle", s.current().name)
    }

    @Test fun theDefaultIsMlKit() {
        assertEquals(OcrEngineKind.MLKIT, OcrEngineKind.DEFAULT)
        assertEquals(OcrEngineKind.MLKIT, OcrEngineKind.of(null))
        assertEquals(OcrEngineKind.MLKIT, OcrEngineKind.of("something old"))
        assertEquals(OcrEngineKind.PADDLE, OcrEngineKind.of("paddle"))
    }

    @Test fun nothingIsMadeUntilAsked() {
        switch()
        assertTrue(made.isEmpty())
    }

    @Test fun theSameChoiceKeepsTheSameEngine() {
        val s = switch()
        assertSame(s.current(), s.current())
        assertEquals(1, made.size)
    }

    @Test fun switchingClosesTheOldEngineBeforeMakingTheNew() {
        var openWhenMade = -1
        val first = Fake("mlkit", saved)
        val s = OcrEngineSwitch<OcrImage>(
            mapOf(
                OcrEngineKind.MLKIT to { first },
                OcrEngineKind.PADDLE to { openWhenMade = if (first.closed) 0 else 1; Fake("paddle", other) },
            ),
        ) { choice }
        s.current()
        choice = OcrEngineKind.PADDLE
        s.current()
        assertEquals("two models must never be in memory together", 0, openWhenMade)
    }

    @Test fun switchingBackToMlKitReturnsTheOldOutputOnASavedFixture() = runBlocking {
        val s = switch()
        val before = s.current().recognize(Image)
        choice = OcrEngineKind.PADDLE
        assertEquals(other, s.current().recognize(Image))
        choice = OcrEngineKind.MLKIT
        val after = s.current().recognize(Image)
        assertEquals(saved, before)
        assertEquals(before, after)
        assertEquals(3, made.size)
        assertTrue(made[0].closed && made[1].closed && !made[2].closed)
    }

    @Test fun anEngineThisBuildDoesNotHaveFallsBackToMlKit() {
        choice = OcrEngineKind.PADDLE
        val s = OcrEngineSwitch<OcrImage>(mapOf(OcrEngineKind.MLKIT to { Fake("mlkit", saved) })) { choice }
        assertEquals("mlkit", s.current().name)
    }

    @Test fun aFailingCloseDoesNotStopTheSwitch() {
        val bad = object : OcrEngine<OcrImage> {
            override val name = "mlkit"
            override suspend fun recognize(image: OcrImage) = saved
            override fun close() = error("will not close")
        }
        val s = OcrEngineSwitch<OcrImage>(mapOf(OcrEngineKind.MLKIT to { bad }, OcrEngineKind.PADDLE to { Fake("paddle", other) })) { choice }
        assertNotSame(null, s.current())
        choice = OcrEngineKind.PADDLE
        assertEquals("paddle", s.current().name)
    }
}
