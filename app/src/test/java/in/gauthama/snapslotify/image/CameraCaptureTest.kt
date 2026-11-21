package `in`.gauthama.snapslotify.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CameraCaptureTest {

    @get:Rule val temp = TemporaryFolder()
    private val cache get() = temp.root
    private val own = "in.gauthama.snapslotify.files"

    private fun photo() = CameraCapture.newFile(cache).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test fun cancellingLeavesNothingAndOpensNothing() {
        val f = CameraCapture.newFile(cache)
        assertNull(CameraCapture.finish(false, f))
        assertFalse(f.exists())
    }

    @Test fun cancellingAfterTheCameraWroteSomethingStillRemovesIt() {
        val f = photo()
        assertNull(CameraCapture.finish(false, f))
        assertFalse(f.exists())
    }

    @Test fun aPhotoOpens() {
        val f = photo()
        assertEquals(f, CameraCapture.finish(true, f))
        assertTrue(f.exists())
    }

    @Test fun anEmptyResultOpensNothing() {
        val f = CameraCapture.newFile(cache)
        assertNull(CameraCapture.finish(true, f))
        assertFalse(f.exists())
    }

    @Test fun killedWhileTheCameraWasOpenThePathIsGone() = assertNull(CameraCapture.finish(true, null))

    @Test fun killedWhileTheCameraWasOpenTheFileIsGone() {
        val f = photo().apply { delete() }
        assertNull(CameraCapture.finish(true, f))
    }

    @Test fun onlyOurOwnCaptureFilesMatch() {
        val f = photo()
        assertEquals(f, CameraCapture.fileFor(cache, own, own, "/camera/${f.name}"))
        assertNull("another app's provider", CameraCapture.fileFor(cache, "com.other.files", own, "/camera/${f.name}"))
        assertNull("another folder", CameraCapture.fileFor(cache, own, own, "/metrics/${f.name}"))
        assertNull("metrics file", CameraCapture.fileFor(cache, own, own, "/metrics/metrics.jsonl"))
        assertNull("not a capture name", CameraCapture.fileFor(cache, own, own, "/camera/secret.jpg"))
        assertNull("climbing out", CameraCapture.fileFor(cache, own, own, "/camera/../../files/metrics/metrics.jsonl"))
        assertNull("no path", CameraCapture.fileFor(cache, own, own, null))
        assertNull("no authority", CameraCapture.fileFor(cache, null, own, "/camera/${f.name}"))
    }

    @Test fun staleFilesGoButTheOneInUseStays() {
        val old = File(cache, "camera").let { it.mkdirs(); File(it, "capture_1.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(1_000) } }
        val inUse = File(cache, "camera/capture_2.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(1_000) }
        val recent = File(cache, "camera/capture_3.jpg").apply { writeBytes(byteArrayOf(1)) }
        CameraCapture.deleteStale(cache, inUse)
        assertFalse(old.exists())
        assertTrue(inUse.exists())
        assertTrue(recent.exists())
    }

    @Test fun nothingToCleanIsFine() = CameraCapture.deleteStale(cache, null)
}
