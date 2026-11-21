package `in`.gauthama.snapslotify.core.ocr

import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.review.ReviewSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

class PageTurnTest {

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int, angle: Float?, block: Int = 0) =
        OcrLine(text, l, t, r, b, block = block, angle = angle)

    /** Three words laid out upright on a 1000 x 800 page. */
    private val upright = listOf(
        line("Staff meeting", 100, 100, 400, 130, 0f, 0),
        line("Annual Day", 100, 300, 350, 330, 0f, 1),
        line("Carol practice", 500, 500, 850, 530, 0f, 2),
        line("5", 600, 100, 615, 125, 0f, 3),
    )

    /** The same page photographed turned [turn] degrees clockwise, with the engine's angle on every line. */
    private fun turned(lines: List<OcrLine>, w: Int, h: Int, turn: Int, angle: Float? = turn.toFloat().let { if (it > 180) it - 360 else it }) =
        lines.map { l ->
            val (a, b, c, d) = forward(l.left, l.top, l.right, l.bottom, turn, w, h)
            l.copy(left = a, top = b, right = c, bottom = d, angle = angle,
                words = l.words.map { x -> forward(x.left, x.top, x.right, x.bottom, turn, w, h).let { (p, q, r, s) -> x.copy(left = p, top = q, right = r, bottom = s) } })
        }

    /** An upright page of [w] x [h] turned [turn] degrees clockwise: where a box lands in the photo. */
    private fun forward(l: Int, t: Int, r: Int, b: Int, turn: Int, w: Int, h: Int): IntArray = when (turn) {
        90 -> intArrayOf(h - b, l, h - t, r)          // (x, y) -> (h - y, x)
        180 -> intArrayOf(w - r, h - b, w - l, h - t)
        270 -> intArrayOf(t, w - r, b, w - l)         // (x, y) -> (y, w - x)
        else -> intArrayOf(l, t, r, b)
    }

    @Test
    fun anUprightPageIsNotTurned() = assertEquals(0, PageTurn.detect(upright))

    @Test
    fun tallLinesWithTheirAngleTellWhichWayThePageIsTurned() {
        assertEquals(90, PageTurn.detect(turned(upright, 1000, 800, 90)))
        assertEquals(270, PageTurn.detect(turned(upright, 1000, 800, 270)))
        assertEquals(180, PageTurn.detect(turned(upright, 1000, 800, 180)))
    }

    @Test
    fun tallLinesWithoutAnAngleOrWithSplitAnglesAreNotTurned() {
        assertEquals(0, PageTurn.detect(turned(upright, 1000, 800, 90, angle = null)))
        val split = turned(upright, 1000, 800, 90).mapIndexed { i, l -> if (i == 1) l.copy(angle = -90f) else l }
        assertEquals(0, PageTurn.detect(split))
        // flat lines whose angle says 90 do not fit together
        assertEquals(0, PageTurn.detect(upright.map { it.copy(angle = 90f) }))
    }

    @Test
    fun mixedTallAndFlatLinesAreNotTurned() {
        val mixed = upright.take(2) + turned(upright.drop(2), 1000, 800, 90)
        assertEquals(0, PageTurn.detect(mixed + line("Side note", 900, 100, 930, 300, 90f, 9)))
    }

    @Test
    fun turningBackRestoresEveryBoxAndTheReadingOrder() {
        for (turn in listOf(90, 180, 270)) {
            val w = if (turn == 180) 1000 else 800; val h = if (turn == 180) 800 else 1000
            val photo = OcrResult(w, h, turned(upright, 1000, 800, turn).reversed(), 0)
            val back = PageTurn.upright(photo, turn)
            assertEquals(1000, back.imageWidth); assertEquals(800, back.imageHeight)
            assertEquals(upright.map { listOf(it.text, it.left, it.top, it.right, it.bottom) },
                back.lines.sortedBy { it.text }.let { s -> upright.map { u -> s.first { it.text == u.text } } }.map { listOf(it.text, it.left, it.top, it.right, it.bottom) })
            assertEquals(listOf("Staff meeting", "5", "Annual Day", "Carol practice"), back.lines.map { it.text })
            assertEquals(0f, back.lines.first().angle!!, 0.01f)
            for (l in back.lines) {
                assertArrayEquals(forward(l.left, l.top, l.right, l.bottom, turn, 1000, 800),
                    PageTurn.boxInImage(l.left, l.top, l.right, l.bottom, turn, 1000, 800))
            }
        }
    }

    // ---- a page photographed at a slant ----

    @Test fun aSlantedPageIsTurnedBackByItsOwnAngle() {
        assertEquals(-38, PageTurn.tilt(upright.map { it.copy(angle = -38.4f) }))
        assertEquals(25, PageTurn.tilt(upright.map { it.copy(angle = 25f) }))
        // a page at 71 degrees: its own angle, not the quarter turn that would leave it 19 degrees off
        val at71 = turned(upright, 1000, 800, 90, angle = 71f)
        assertEquals(71, PageTurn.read(OcrResult(800, 1000, at71, 0)) { t -> OcrResult(1000, 800, upright, 0).takeIf { t == 71 } }.turn)
    }

    @Test fun aSmallOrSplitSlantIsNoTilt() {
        assertEquals(0, PageTurn.tilt(upright.map { it.copy(angle = 8f) }))
        assertEquals(0, PageTurn.tilt(upright.mapIndexed { i, l -> l.copy(angle = if (i % 2 == 0) 30f else -30f) }))
        assertEquals(0, PageTurn.tilt(upright.map { it.copy(angle = 90f) }))
    }

    @Test fun aSlantedReadIsKeptOnlyWhenItComesOutLevel() {
        val slanted = OcrResult(1000, 800, upright.map { it.copy(angle = -35f) }, 0)
        assertEquals(-35, PageTurn.read(slanted) { OcrResult(1300, 1200, upright, 0) }.turn)
        val still = PageTurn.read(slanted) { slanted }
        assertEquals(0, still.turn); assertEquals(true, still.turnUnread)
    }

    @Test fun aBoxOfTheLevelledImageLandsBackOnThePhoto() {
        // the photo 1000 x 800, levelled by turning -turn: a point there goes back to where it was in the photo
        for (turn in listOf(-38, 20, 71)) {
            val r = Math.toRadians(-turn.toDouble()); val c = Math.cos(r); val s = Math.sin(r)
            fun up(x: Double, y: Double) = (x * c - y * s) to (x * s + y * c)
            val corners = listOf(up(0.0, 0.0), up(1000.0, 0.0), up(0.0, 800.0), up(1000.0, 800.0))
            val dx = corners.minOf { it.first }; val dy = corners.minOf { it.second }
            val (ux, uy) = up(600.0, 300.0).let { (x, y) -> x - dx to y - dy }
            val b = PageTurn.boxInImage(ux.toInt() - 5, uy.toInt() - 5, ux.toInt() + 5, uy.toInt() + 5, turn, 0, 0, 1000, 800)
            assertEquals("turn $turn", 600.0, (b[0] + b[2]) / 2.0, 2.0)
            assertEquals("turn $turn", 300.0, (b[1] + b[3]) / 2.0, 2.0)
        }
    }

    // ---- dev pages (benchmarks/ is private and gitignored; skipped when absent) ----

    private fun dev(file: String, pick: (String) -> Boolean): List<Pair<String, OcrResult>> {
        val f = File("../benchmarks/$file")
        assumeTrue("$file not present", f.exists())
        return OcrJson.decodeRecords(f.readText()).filter { pick(it.first) }
    }

    private val today = LocalDate.of(2026, 11, 7)

    private fun events(ocr: OcrResult): List<String> {
        val s = ReviewSession.from(CodePipeline.run(ocr, today), today)
        return if (s.noEventFound) emptyList() else s.events.map { d ->
            "${d.title} | ${d.dateIso} | ${if (d.allDay) "-" else d.startIso} | ${d.flags.map { "${it.field}:${it.code}" }.sorted()}"
        }
    }

    private fun photo(ocr: OcrResult, turn: Int): OcrResult {
        val (w, h) = if (turn == 180) ocr.imageWidth to ocr.imageHeight else ocr.imageHeight to ocr.imageWidth
        // the engine's order on a turned photo is not the upright order: shuffled
        return OcrResult(w, h, turned(ocr.lines, ocr.imageWidth, ocr.imageHeight, turn).shuffled(Random(turn)), 0)
    }

    /**
     * The app's path: each page photographed turned 90, 180 and 270 degrees is detected as turned and read again from
     * the image turned upright. The stand-in engine gives the page's upright OCR for that re-read (what ML Kit reads from
     * the upright image is measured on the phone, not here). The re-read is used for everything and gives the upright
     * page's events.
     */
    private fun sameEventsThroughReread(pages: List<Pair<String, OcrResult>>) {
        assumeTrue(pages.isNotEmpty())
        val bad = mutableListOf<String>()
        for ((name, ocr) in pages) {
            val want = events(ocr)
            for (turn in listOf(90, 180, 270)) {
                var asked = 0
                val read = PageTurn.read(photo(ocr, turn)) { t -> asked++; if (t == turn) ocr else null }
                if (asked != 1 || read.ocr !== ocr || read.turn != turn || read.turnUnread) bad += "$name turned $turn: asked $asked, turn ${read.turn}"
                else if (events(read.ocr) != want) bad += "$name turned $turn: events differ"
            }
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    @Test
    fun monthGridsPhotographedTurnedAreReadAgainUpright() =
        sameEventsThroughReread(dev("posters_dev/ocr_words.json") { Regex("""C(0[1-9]|10)\.png""").matches(it) })

    @Test
    fun programmesPhotographedTurnedAreReadAgainUpright() =
        sameEventsThroughReread(dev("suite/dev_ocr.json") { it.startsWith("programme_") }.take(4))

    @Test
    fun chatsPhotographedTurnedAreReadAgainUpright() =
        sameEventsThroughReread(dev("phone_ocr/chat_clean_ocr.json") { true }.take(4))

    /**
     * Saved dumps only: turning the boxes back (no re-read) gives the same events, except where the engine reads a
     * column as one run that a top-to-bottom order splits. Known limit: C06, a month grid with a "COMING UP" list beside
     * it; its list lines get interleaved with the grid rows and three of its five events are lost. The app re-reads
     * instead, so this limit is only in dumps.
     */
    @Test
    fun boxesTurnedBackGiveTheSameEventsExceptAColumnBesideAGrid() {
        val pages = dev("posters_dev/ocr_words.json") { Regex("""C(0[1-9]|10)\.png""").matches(it) && it != "C06.png" }
        assumeTrue(pages.isNotEmpty())
        val bad = mutableListOf<String>()
        for ((name, ocr) in pages) for (turn in listOf(90, 180, 270)) {
            val p = photo(ocr, turn)
            if (events(PageTurn.upright(p, PageTurn.detect(p.lines))) != events(ocr)) bad += "$name turned $turn"
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    // ---- the re-read's guards ----

    private val page = OcrResult(1000, 800, upright, 0)
    private val sideways = OcrResult(800, 1000, turned(upright, 1000, 800, 90), 0)

    @Test
    fun aPageThatIsNotTurnedIsNeverReadAgain() {
        val read = PageTurn.read(page) { error("re-read of a page that is not turned") }
        assertEquals(Triple(page, 0, false), Triple(read.ocr, read.turn, read.turnUnread))
        assertEquals(0L, read.rereadMs)
    }

    @Test
    fun aReReadThatStillLooksTurnedOrLostTextOrFailedKeepsTheFirstReadFlagged() {
        val stillTall = PageTurn.read(sideways) { sideways.copy(lines = sideways.lines.map { it.copy(angle = null) }) }
        // "Staff meeting" lost: 12 of 34 letters and digits
        val fewer = PageTurn.read(sideways) { page.copy(lines = page.lines.drop(1)) }
        // the same text in fewer lines is not lost text (read upright, pieces split when sideways are joined)
        val joined = page.copy(lines = listOf(page.lines[0].copy(text = "Staff meeting Annual Day", right = 700)) + page.lines.drop(2))
        assertEquals(Triple(joined, 90, false), PageTurn.read(sideways) { joined }.let { Triple(it.ocr, it.turn, it.turnUnread) })
        val failed = PageTurn.read(sideways) { null }
        for (r in listOf(stillTall, fewer, failed)) assertEquals(Triple(sideways, 0, true), Triple(r.ocr, r.turn, r.turnUnread))
        val good = PageTurn.read(sideways) { page }
        assertEquals(Triple(page, 90, false), Triple(good.ocr, good.turn, good.turnUnread))
    }

    @Test
    fun aTurnedPageThatCouldNotBeReadUprightFlagsEveryEvent() {
        val lines = listOf(OcrLine("Quiz night 12 December 2026 at 7 pm", 50, 100, 900, 130, block = 0))
        val r = CodePipeline.run(OcrResult(1000, 800, lines, 0), today, turnUnread = true)
        val s = ReviewSession.from(r, today)
        assertEquals(1, s.events.size)
        val f = s.events.single().flags.single { it.code == "page_turned" }
        assertEquals(`in`.gauthama.snapslotify.core.review.Field.DATE, f.field)
        assertEquals(emptyList<String>(), ReviewSession.from(CodePipeline.run(OcrResult(1000, 800, lines, 0), today), today)
            .events.single().flags.filter { it.code == "page_turned" }.map { it.code })
    }
}
