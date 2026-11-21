package `in`.gauthama.snapslotify.core.image

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.File
import javax.imageio.ImageIO

class BurstTest {

    @Test
    fun theSharpestFrameIsKeptAndTheEarliestOfEquals() {
        assertEquals(1, Burst.sharpest(listOf(0.12, 0.40, 0.33)))
        assertEquals(0, Burst.sharpest(listOf(0.30, 0.30, 0.10)))
        assertEquals(0, Burst.sharpest(listOf(0.05)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyBurstIsAnError() { Burst.sharpest(emptyList()) }

    private fun score(img: BufferedImage): Double {
        val px = IntArray(img.width)
        return Sharpness.score(img.width, img.height) { y, out ->
            img.getRGB(0, y, img.width, 1, px, 0, img.width)
            for (i in px.indices) out[i] = Sharpness.brightness(px[i])
        }
    }

    /** Smeared sideways by [length] px, as a hand moving during one frame. */
    private fun shake(img: BufferedImage, length: Int): BufferedImage {
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also { it.graphics.drawImage(img, 0, 0, null) }
        return ConvolveOp(Kernel(length, 1, FloatArray(length) { 1f / length }), ConvolveOp.EDGE_NO_OP, null).filter(rgb, null)
    }

    @Test
    fun ofThreeFramesOfADevPageTheSteadyOneIsKept() {
        val f = File("../fixtures/posters_dev/images/C03.png")
        assumeTrue("dev poster not present", f.exists())
        val page = ImageIO.read(f)
        val frames = listOf(shake(page, 9), page, shake(page, 3))
        assertEquals(1, Burst.sharpest(frames.map(::score)))
    }
}
