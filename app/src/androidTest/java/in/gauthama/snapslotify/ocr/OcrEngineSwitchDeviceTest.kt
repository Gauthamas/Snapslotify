package `in`.gauthama.snapslotify.ocr

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.gauthama.snapslotify.core.ocr.OcrEngineKind
import `in`.gauthama.snapslotify.core.ocr.OcrJson
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.image.ImageLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Both engines on the phone, on one DEV image bundled in the test APK (never sealed or held out, never a photo). The choice
 * goes back to ML Kit afterwards, so the phone is left as it was.
 */
@RunWith(AndroidJUnit4::class)
class OcrEngineSwitchDeviceTest {

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    @After
    fun backToDefault() {
        OcrEngines.select(OcrEngineKind.MLKIT)
        AppOcr.engine
    }

    private fun image(): BitmapOcrImage {
        val f = File(target.cacheDir, IMAGE).apply { testAssets.open(IMAGE).use { i -> outputStream().use(i::copyTo) } }
        return BitmapOcrImage(ImageLoader(target.contentResolver).load(Uri.fromFile(f)).bitmap)
    }

    private fun read(kind: OcrEngineKind, img: BitmapOcrImage): Pair<String, OcrResult> {
        OcrEngines.select(kind)
        val engine = AppOcr.engine
        return engine.name to runBlocking { engine.recognize(img) }
    }

    @Test
    fun bothEnginesReturnLinesForTheSameBundledImage() {
        val img = image()
        for (kind in listOf(OcrEngineKind.MLKIT, OcrEngineKind.PADDLE)) {
            val (name, r) = read(kind, img)
            assertEquals(kind.engineName, name)
            assertTrue("$name read no lines", r.lines.isNotEmpty())
            for (l in r.lines) {
                assertTrue("$name box outside the image", l.left in 0..r.imageWidth && l.right in 0..r.imageWidth && l.top in 0..r.imageHeight && l.bottom in 0..r.imageHeight)
                assertTrue("$name box is empty", l.right > l.left && l.bottom > l.top)
                assertTrue("$name confidence out of range", l.confidence == null || l.confidence!! in 0f..1f)
            }
        }
    }

    @Test
    fun switchingBackToMlKitReturnsTheOldOutputOnASavedFixture() {
        val saved = OcrJson.decode(testAssets.open(SAVED).bufferedReader().use { it.readText() })
        val img = image()
        val (_, before) = read(OcrEngineKind.MLKIT, img)
        read(OcrEngineKind.PADDLE, img)
        val (name, after) = read(OcrEngineKind.MLKIT, img)
        assertEquals("mlkit", name)
        assertEquals(before.lines, after.lines)
        assertEquals(saved.lines.map { it.text }, after.lines.map { it.text })
        assertEquals(saved.lines.map { listOf(it.left, it.top, it.right, it.bottom) }, after.lines.map { listOf(it.left, it.top, it.right, it.bottom) })
    }

    @Test
    fun aSwitchReplacesTheEngineInUse() {
        OcrEngines.select(OcrEngineKind.MLKIT)
        val first = AppOcr.engine
        OcrEngines.select(OcrEngineKind.PADDLE)
        val second = AppOcr.engine
        assertNotSame(first, second)
        assertEquals("paddle", second.name)
        OcrEngines.select(OcrEngineKind.MLKIT)
        assertNotSame("the old ML Kit engine was released, so a new one is made", first, AppOcr.engine)
    }

    private companion object {
        const val IMAGE = "084_concert_poster.png"
        const val SAVED = "084_concert_poster.mlkit.json"
    }
}
