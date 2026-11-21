package `in`.gauthama.snapslotify.core.ocr.paddle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The steps around the models, with fake model output. No model file or runtime is needed. */
class PaddleOcrTest {

    private val noRun = TensorRunner { _, _ -> error("no model here") }
    private val chars = listOf("a", "b", "c")
    private val engine = PaddleOcr(noRun, noRun, chars)

    /** Classes: 0 blank, 1 a, 2 b, 3 c, 4 space. */
    private fun steps(vararg picks: Pair<Int, Float>): Tensor {
        val classes = chars.size + 2
        val d = FloatArray(picks.size * classes)
        picks.forEachIndexed { s, (c, p) ->
            for (i in 0 until classes) d[s * classes + i] = (1 - p) / (classes - 1)
            d[s * classes + c] = p
        }
        return Tensor(d, longArrayOf(1, picks.size.toLong(), classes.toLong()))
    }

    @Test fun ctcDropsBlanksAndRepeats() {
        val (text, _) = engine.decode(steps(0 to .9f, 1 to .9f, 1 to .9f, 0 to .9f, 1 to .9f, 2 to .9f))
        assertEquals("aab", text)
    }

    @Test fun theLastClassIsASpace() = assertEquals("a b", engine.decode(steps(1 to .9f, 4 to .9f, 2 to .9f)).first)

    @Test fun confidenceIsTheMeanOfTheCharactersKept() {
        val (_, score) = engine.decode(steps(0 to .99f, 1 to .8f, 1 to .1f, 2 to .6f))
        // the repeat of "a" is not kept: its own step's best class decides, and the blank is never counted
        assertTrue("score $score", score in 0f..1f)
        val (_, plain) = engine.decode(steps(1 to .8f, 2 to .6f))
        assertEquals(0.7f, plain, 0.001f)
    }

    @Test fun nothingReadScoresZero() = assertEquals("" to 0f, engine.decode(steps(0 to .9f, 0 to .9f)))

    @Test fun rawScoresGetASoftmaxSoConfidenceStaysInRange() {
        val raw = Tensor(floatArrayOf(-3f, 5f, -1f, -2f, -4f), longArrayOf(1, 1, 5))
        val (text, score) = engine.decode(raw)
        assertEquals("a", text)
        assertTrue("score $score", score in 0.9f..1f)
    }

    @Test fun aRectangleIsFoundInTheProbabilityMap() {
        val w = 100; val h = 50
        val p = FloatArray(w * h)
        for (y in 20 until 30) for (x in 10 until 60) p[y * w + x] = 0.9f
        val found = engine.regions(p, w, h)
        assertEquals(1, found.size)
        val q = found[0]
        // 50 x 10, grown on each side by 50 * 10 * 1.5 / 120 = 6.25
        assertEquals(62.5f, q.width, 0.5f)
        assertEquals(22.5f, q.height, 0.5f)
        assertEquals(3.75f, q.minX, 0.5f)
    }

    @Test fun aWeakRegionIsDropped() {
        val p = FloatArray(100)
        for (i in 0 until 100) p[i] = 0.4f
        assertTrue(engine.regions(p, 10, 10).isEmpty())
    }

    @Test fun separateRegionsStaySeparate() {
        val w = 100; val h = 50
        val p = FloatArray(w * h)
        for (y in 5 until 15) for (x in 5 until 40) p[y * w + x] = 0.9f
        for (y in 30 until 40) for (x in 50 until 90) p[y * w + x] = 0.9f
        assertEquals(2, engine.regions(p, w, h).size)
    }

    @Test fun aSlantedLineGetsARotatedRectangleAndUprightBounds() {
        val pts = (0 until 100).flatMap { x -> listOf(Pt(x.toFloat(), x * 0.2f), Pt(x.toFloat(), x * 0.2f + 10)) }
        val q = PaddleOcr.minAreaRect(pts)
        assertTrue("width ${q.width}", abs(q.width - 101f) < 3)
        assertTrue("height ${q.height}", q.height < 11f)
        assertTrue("reads left to right", q.p[1].x > q.p[0].x)
        val b = q.bounds(1000, 1000)
        assertEquals(0, b[0])
        assertTrue(b[2] >= 99 && b[3] >= 29)
    }

    @Test fun boundsStayInsideTheImage() {
        val q = Quad(listOf(Pt(-5f, -5f), Pt(120f, -5f), Pt(120f, 60f), Pt(-5f, 60f)))
        assertArrayEquals(intArrayOf(0, 0, 100, 50), q.bounds(100, 50))
    }

    @Test fun linesReadTopToBottomThenLeftToRight() {
        fun box(x: Float, y: Float) = Quad(listOf(Pt(x, y), Pt(x + 10, y), Pt(x + 10, y + 5), Pt(x, y + 5)))
        val right = box(100f, 3f); val left = box(5f, 8f); val below = box(5f, 40f)
        assertEquals(listOf(left, right, below), PaddleOcr.readingOrder(listOf(below, right, left)))
    }

    private fun word(l: Int, t: Int, r: Int, b: Int, text: String, s: Float = 1f) = PaddleOcr.Word(intArrayOf(l, t, r, b), l.toFloat(), t.toFloat(), text, s)

    @Test fun wordsOnOneRowBecomeOneLine() {
        val lines = PaddleOcr.LineJoin.toLines(listOf(word(483, 317, 792, 394, "Science"), word(289, 320, 469, 392, "Data"), word(416, 558, 511, 598, "Nov"),
            word(514, 561, 542, 594, "5"), word(557, 557, 663, 598, "2026")))
        assertEquals(listOf("Data Science", "Nov 5 2026"), lines.map { it.text })
        assertArrayEquals(intArrayOf(289, 317, 792, 394), lines[0].box)
    }

    @Test fun aWideGapSplitsARow() {
        val lines = PaddleOcr.LineJoin.toLines(listOf(word(10, 10, 60, 40, "Left"), word(900, 12, 960, 40, "Right")))
        assertEquals(listOf("Left", "Right"), lines.map { it.text })
    }

    @Test fun differentRowsStayApart() {
        val lines = PaddleOcr.LineJoin.toLines(listOf(word(10, 10, 60, 40, "Top"), word(10, 50, 60, 80, "Below")))
        assertEquals(listOf("Top", "Below"), lines.map { it.text })
    }

    @Test fun lineConfidenceIsWeightedByLength() {
        val l = PaddleOcr.LineJoin.toLines(listOf(word(0, 0, 90, 30, "abcd", 1f), word(100, 0, 120, 30, "e", 0.5f))).single()
        assertEquals(0.9f, l.score, 0.001f)
    }

    @Test fun theWholeRoadWithFakeModels() {
        val detMap = TensorRunner { _, shape ->
            val h = shape[2].toInt(); val w = shape[3].toInt()
            val p = FloatArray(h * w)
            for (y in h / 4 until h / 2) for (x in w / 8 until w / 2) p[y * w + x] = 0.9f
            Tensor(p, longArrayOf(1, 1, h.toLong(), w.toLong()))
        }
        val recSteps = TensorRunner { _, _ -> steps(1 to .9f, 2 to .7f) }
        val lines = PaddleOcr(detMap, recSteps, chars).recognize(200, 100, IntArray(200 * 100) { -1 })
        assertEquals(1, lines.size)
        val l = lines[0]
        assertEquals("ab", l.text)
        assertEquals(0.8f, l.confidence!!, 0.001f)
        assertEquals(0, l.block)
        assertTrue("box in image pixels: ${l.left} ${l.top} ${l.right} ${l.bottom}", l.left in 0..40 && l.right in 80..200 && l.top in 0..40 && l.bottom in 30..100)
    }
}
