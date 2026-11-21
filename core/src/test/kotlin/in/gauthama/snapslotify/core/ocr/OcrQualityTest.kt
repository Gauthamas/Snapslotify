package `in`.gauthama.snapslotify.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrQualityTest {

    private fun line(block: Int, confidence: Float?) = OcrLine("x", 0, 0, 1, 1, confidence, block)

    @Test fun countsAndConfidence() {
        val q = OcrQuality.of(OcrResult(10, 10, listOf(line(0, 0.5f), line(0, 1.0f), line(1, null)), 0))
        assertEquals(2, q.blocks)
        assertEquals(3, q.lines)
        assertEquals(0.75f, q.meanConfidence!!, 0.001f)
        assertEquals(0.5f, q.minConfidence!!, 0.001f)
    }

    @Test fun nothingRead() = assertEquals(OcrQuality(0, 0, null, null), OcrQuality.of(OcrResult(10, 10, emptyList(), 0)))
}
