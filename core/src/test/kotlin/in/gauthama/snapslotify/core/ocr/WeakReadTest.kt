package `in`.gauthama.snapslotify.core.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A weak read ([OcrQuality.weak]): little text, or text the engine was unsure of. Invented reads. */
class WeakReadTest {
    private fun read(vararg ls: Pair<String, Float?>) =
        OcrResult(1000, 1600, ls.mapIndexed { i, (t, c) -> OcrLine(t, 10, 40 * i, 10 + t.length * 10, 40 * i + 20, confidence = c) }, 0)

    @Test fun littleTextIsWeak() {
        assertTrue(OcrQuality.weak(read()))
        assertTrue(OcrQuality.weak(read("Fair 21 Nov" to 0.95f)))
    }

    @Test fun unsureTextIsWeak() {
        assertTrue(OcrQuality.weak(read("Spring book fair at the city library hall" to 0.5f, "Saturday 21 November 2026 from ten" to 0.55f)))
    }

    @Test fun plentyOfSureTextIsNotWeak() {
        assertFalse(OcrQuality.weak(read("Spring book fair at the city library hall" to 0.9f, "Saturday 21 November 2026 from ten" to 0.85f)))
        // an engine that gives no confidence: judged on the amount of text alone
        assertFalse(OcrQuality.weak(read("Spring book fair at the city library hall" to null, "Saturday 21 November 2026 from ten" to null)))
    }
}
