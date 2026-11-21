package `in`.gauthama.snapslotify.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The size cap, for sizes no phone test should allocate. */
class ImageSizeTest {

    private fun decoded(w: Int, h: Int): Long = ImageLoader.sampleSize(w, h).let { (w / it).toLong() * (h / it) }

    @Test fun aScreenshotDecodesAtFullSize() = assertEquals(1, ImageLoader.sampleSize(1080, 2340))

    @Test fun anAlreadySmallPhotoDecodesAtFullSize() = assertEquals(1, ImageLoader.sampleSize(2000, 1500))

    @Test fun cameraPhotosStayWithinThePixelLimit() {
        for ((w, h) in listOf(4000 to 3000, 8160 to 6120, 12000 to 9000, 16320 to 12240, 5119 to 5119, 5120 to 5120, 30000 to 30000, 60000 to 200)) {
            assertTrue("$w x $h decodes ${decoded(w, h)} pixels", decoded(w, h) <= ImageLoader.MAX_DECODE_PIXELS)
        }
    }

    @Test fun aCameraPhotoKeepsEnoughDetailToRead() {
        // 50 MP: sampled once, then scaled to the cap, not shrunk below it
        val s = ImageLoader.sampleSize(8160, 6120)
        assertEquals(2, s)
        assertTrue(8160 / s >= ImageLoader.MAX_SIDE)
    }

    @Test fun portraitAndLandscapeAreTheSame() = assertEquals(ImageLoader.sampleSize(6120, 8160), ImageLoader.sampleSize(8160, 6120))
}
