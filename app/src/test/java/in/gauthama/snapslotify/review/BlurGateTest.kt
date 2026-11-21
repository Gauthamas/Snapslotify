package `in`.gauthama.snapslotify.review

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurGateTest {

    @Test
    fun aSharpPhotoGoesOnWithoutAsking() = runBlocking {
        var asked = false
        assertEquals(BlurGate.Choice.PROCEED, BlurGate().check(0.40) { asked = true })
        assertFalse(asked)
    }

    @Test
    fun aBlurryPhotoAsksAndRetakeEndsTheReview() = runBlocking {
        val gate = BlurGate()
        var asked = false
        val choice = async(start = CoroutineStart.UNDISPATCHED) { gate.check(0.05) { asked = true } }
        assertTrue(asked)
        assertTrue(gate.asking)
        assertFalse(choice.isCompleted)
        gate.retake()
        assertEquals(BlurGate.Choice.RETAKE, choice.await())
        assertFalse(gate.asking)
    }

    @Test
    fun aBlurryPhotoThatStillReadWellGoesOnWithoutAsking() = runBlocking {
        var asked = false
        assertEquals(BlurGate.Choice.PROCEED, BlurGate().check(0.05, weak = false) { asked = true })
        assertFalse(asked)
        // a sharp photo read weakly is not asked about either: the ask is for blur only
        assertEquals(BlurGate.Choice.PROCEED, BlurGate().check(0.40, weak = true) { asked = true })
        assertFalse(asked)
    }

    @Test
    fun useAnywayReadsTheBlurryPhoto() = runBlocking {
        val gate = BlurGate()
        val choice = async(start = CoroutineStart.UNDISPATCHED) { gate.check(0.05) {} }
        yield()
        gate.useAnyway()
        assertEquals(BlurGate.Choice.PROCEED, choice.await())
        // a second answer changes nothing
        gate.retake()
        assertEquals(BlurGate.Choice.PROCEED, choice.await())
    }
}
