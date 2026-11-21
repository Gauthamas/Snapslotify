package `in`.gauthama.snapslotify.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTimingsTest {

    @Test
    fun firstCallIsColdTheRestWarm() {
        val t = OcrTimings()
        assertNull(t.warmMedianMs)
        assertTrue(t.record(900))
        assertFalse(t.record(300))
        assertFalse(t.record(100))
        assertFalse(t.record(200))
        assertEquals(900L, t.coldMs)
        assertEquals(listOf(300L, 100L, 200L), t.warmMs)
        assertEquals(200L, t.warmMedianMs)
    }

    @Test
    fun medianOfEvenCount() {
        assertEquals(150L, OcrTimings.median(listOf(200, 100)))
    }
}
