package `in`.gauthama.snapslotify.core.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.exp

class SharpnessTest {

    private fun score(img: BufferedImage): Double {
        val px = IntArray(img.width)
        return Sharpness.score(img.width, img.height) { y, out ->
            img.getRGB(0, y, img.width, 1, px, 0, img.width)
            for (i in px.indices) out[i] = Sharpness.brightness(px[i])
        }
    }

    private fun gaussian(img: BufferedImage, sigma: Double): BufferedImage {
        val r = (sigma * 3).toInt()
        val w = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)).toFloat() }
        val sum = w.sum(); for (i in w.indices) w[i] /= sum
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also { it.graphics.drawImage(img, 0, 0, null) }
        val across = ConvolveOp(Kernel(w.size, 1, w), ConvolveOp.EDGE_NO_OP, null).filter(rgb, null)
        return ConvolveOp(Kernel(1, w.size, w), ConvolveOp.EDGE_NO_OP, null).filter(across, null)
    }

    /** Smeared sideways only, as a shaking hand does: lines running across stay sharp. */
    private fun motionAcross(img: BufferedImage, length: Int): BufferedImage {
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also { it.graphics.drawImage(img, 0, 0, null) }
        return ConvolveOp(Kernel(length, 1, FloatArray(length) { 1f / length }), ConvolveOp.EDGE_NO_OP, null).filter(rgb, null)
    }

    /** Dark text-like strokes and lines on a light page. */
    private fun page(): BufferedImage = BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB).apply {
        val g = createGraphics()
        g.color = java.awt.Color(245, 245, 245); g.fillRect(0, 0, width, height)
        g.color = java.awt.Color(30, 30, 30)
        for (row in 0 until 8) for (c in 0 until 20) g.fillRect(20 + c * 28, 30 + row * 45, 3 + (c % 3) * 4, 18)
        for (row in 0 until 8) g.fillRect(10, 52 + row * 45, 580, 2)
        g.dispose()
    }

    @Test
    fun aSharpPageIsNotBlurryAndItsBlurredCopyIs() {
        val p = page()
        assertFalse(Sharpness.isBlurry(score(p)))
        assertTrue(Sharpness.isBlurry(score(gaussian(p, 4.0))))
    }

    @Test
    fun motionBlurOneWayIsCaughtThoughLinesTheOtherWayStaySharp() =
        assertTrue(Sharpness.isBlurry(score(motionAcross(page(), 15))))

    @Test
    fun aDevPosterIsSharpAndItsBlurredCopyIsBlurry() {
        val f = File("../fixtures/posters_dev/images/C01.png")
        assumeTrue("dev poster not present", f.exists())
        val img = ImageIO.read(f)
        assertFalse(Sharpness.isBlurry(score(img)))
        assertTrue(Sharpness.isBlurry(score(gaussian(img, 5.0))))
    }

    @Test
    fun aBlankImageHasNothingToMeasureAndATinyOneScoresAsSharp() {
        assertFalse(Sharpness.isBlurry(Sharpness.score(2, 2) { _, out -> out.fill(0) }))
    }
}
