package `in`.gauthama.snapslotify.core.layout

import `in`.gauthama.snapslotify.core.extract.RouteMutationTest
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.ocr.OcrWord
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/** Invented pages (not dev, not held-out): rows that are tilted or bent are put level before the page is read. */
class PageBendTest {

    private val today = LocalDate.of(2026, 11, 5)

    /** A line with a box for each word, [h] tall, words placed by character position. */
    private fun line(text: String, left: Int, top: Int, h: Int = 30): OcrLine {
        val cw = h / 2
        var x = left
        val words = text.split(' ').map { w -> OcrWord(w, x, top, x + w.length * cw, top + h).also { x += (w.length + 1) * cw } }
        return OcrLine(text, left, top, left + text.length * cw, top + h, words = words, angle = 0f)
    }

    /** A programme across the page: date at the left, a title of a few words in the middle, the time at the right. */
    private fun programme(): OcrResult {
        val ls = mutableListOf(line("Riverside Winter Festival Programme", 60, 60, 50))
        listOf(
            Triple("Sat 5 Dec", "Choir concert in the park", "6 pm"), Triple("Sun 6 Dec", "Book fair at the library", "10 am"),
            Triple("Mon 7 Dec", "Open air film night", "7 pm"), Triple("Tue 8 Dec", "Quiz evening for families", "5 pm"),
            Triple("Wed 9 Dec", "Lantern walk by the river", "8 pm"),
        ).forEachIndexed { i, (d, t, h) ->
            val y = 220 + i * 64
            ls += line(d, 60, y); ls += line(t, 330, y); ls += line(h, 980, y)
        }
        return OcrResult(1100, 900, ls.mapIndexed { i, l -> l.copy(block = i) }, 0)
    }

    private fun events(o: OcrResult) = CodePipeline.run(o, today).events.map { Triple(it.event.title, it.event.date, it.event.time) }

    @Test fun aLevelPageIsLeftAsItIs() {
        val page = programme()
        assertNull(PageBend.measure(page.lines, page.imageWidth))
        assertNull(CodePipeline.run(page, today).bend)
    }

    @Test fun aTurnedPhotoReadsAsTheLevelPage() {
        val want = events(programme())
        assertEquals(5, want.size)
        for (deg in listOf(-3.0, 3.0)) {
            val turned = RouteMutationTest.Mutations.rotate(programme(), deg)
            assertNotNull(PageBend.measure(turned.lines, turned.imageWidth))
            assertEquals("turned $deg", want, events(turned))
        }
    }

    @Test fun aCurledOrBowedPageReadsAsTheLevelPage() {
        val want = events(programme())
        for ((name, f) in RouteMutationTest.Mutations.SHAPE.take(2)) assertEquals(name, want, events(f(programme())))
    }

    /** The rows by their lines' texts. */
    private fun rows(lines: List<OcrLine>) = Layout.rows(lines).map { r -> r.map { lines[it].text } }

    @Test fun rowsOfATurnedOrCurledPageAreTheLevelPagesOnceLevelled() {
        val want = rows(programme().lines)
        val bent = listOf(
            "turned 3" to RouteMutationTest.Mutations.rotate(programme(), 3.0),
            // the right edge lifting 6% of the width, as a sheet off a stack does (the photo this came from: about 5.5%)
            "right edge curls up" to RouteMutationTest.Mutations.bend(programme(), dy = { x -> -0.06 * 1100 * x * x * x }),
        )
        for ((name, page) in bent) {
            assertTrue("$name: the rows as photographed should not be the level page's", rows(page.lines) != want)
            val bend = PageBend.measure(page.lines, page.imageWidth)!!
            assertEquals(name, want, rows(bend.flatten(page.lines)))
        }
    }

    @Test fun theMeasuredBendFollowsTheCurl() {
        // the right edge lifts 40 px; the rows' measured height falls steadily towards it, as put in
        val curled = RouteMutationTest.Mutations.bend(programme(), dy = { x -> -40.0 * x * x * x })
        val bend = PageBend.measure(curled.lines, curled.imageWidth)!!
        val at = (1..9).map { bend.at(it * 110.0) }
        assertTrue(at.toString(), at.zipWithNext().all { (a, b) -> b <= a + 1 })
        assertTrue(at.toString(), abs((at.first() - at.last()) - 40.0 * (0.9 * 0.9 * 0.9 - 0.1 * 0.1 * 0.1)) < 8)
    }

    @Test fun aBoxIsPutBackWhereItIsInThePhoto() {
        val turned = RouteMutationTest.Mutations.rotate(programme(), 3.0)
        val bend = PageBend.measure(turned.lines, turned.imageWidth)!!
        val flat = bend.flatten(turned.lines)
        for ((a, b) in turned.lines.zip(flat)) {
            val back = bend.photographed(b.left, b.top, b.right, b.bottom)
            assertTrue("${a.top}/${back[1]}", abs(back[1] - a.top) <= 3 && abs(back[3] - a.bottom) <= 3)
        }
    }
}
