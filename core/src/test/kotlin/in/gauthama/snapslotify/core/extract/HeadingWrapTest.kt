package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.review.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** A heading wrapped over lines is one title ([HeadingWrap]). Invented pages only. */
class HeadingWrapTest {

    private fun l(text: String, x: Int, y: Int, h: Int) = OcrLine(text, x, y, x + text.length * h / 2, y + h)

    private fun widen(picked: String, at: Int, vararg ls: OcrLine): String {
        val lines = ls.toList()
        val median = lines.map { it.bottom - it.top }.sorted()[lines.size / 2]
        return HeadingWrap.widen(picked, at, lines, CandidateFinder.find(lines), median)
    }

    @Test fun aHeadingEndingInADashJoinsTheNameUnderIt() {
        assertEquals("Eye test - Dr. Mehta", widen("Mehta", 1, l("Eye test -", 120, 260, 90), l("Dr. Mehta", 116, 360, 100),
            l("Wed 25 Nov", 128, 500, 76), l("12 pm", 130, 640, 66), l("Lake Clinic", 129, 750, 72)))
    }

    @Test fun aJoiningWordOrALowerCaseStartJoinsLinesOfDifferentSize() {
        assertEquals("Garden Walk with the Hill Club", widen("Garden Walk with", 0, l("Garden Walk with", 100, 200, 90), l("the Hill Club", 110, 300, 64),
            l("Sat 5 Dec", 120, 450, 60), l("10 am", 120, 540, 60)))
        assertEquals("Open studio day for young artists", widen("Open studio day", 0, l("Open studio day", 100, 200, 90), l("for young artists", 110, 300, 64),
            l("Sat 5 Dec", 120, 450, 60), l("10 am", 120, 540, 60)))
    }

    @Test fun aChangeOfTypeWithNoCueEndsTheHeading() {
        assertEquals("SPRING FAIR", widen("SPRING FAIR", 0, l("SPRING FAIR", 100, 200, 120), l("Riverside Park", 110, 330, 60),
            l("Sat 5 Dec", 120, 450, 60), l("10 am", 120, 540, 60)))
    }

    @Test fun aDateLineOrAGreetingIsNeverJoined() {
        assertEquals("Book Fair", widen("Book Fair", 0, l("Book Fair", 100, 200, 80), l("Sat 5 Dec", 100, 290, 80), l("Town Hall", 100, 400, 60)))
        val ls = listOf(l("Book Fair", 100, 200, 80), l("Sat 5 Dec", 100, 290, 80), l("Town Hall", 100, 400, 60))
        assertEquals(listOf(0), HeadingWrap.group(ls, CandidateFinder.find(ls), 0))
        assertEquals("party for Asha!", widen("party for Asha!", 1, l("Hey, it's a", 100, 200, 60), l("party for Asha!", 100, 270, 60),
            l("Sat 5 Dec", 100, 400, 40), l("6 pm", 100, 460, 40)))
    }

    @Test fun atMostThreeLinesAndAtMostEightWords() {
        // four lines that each continue: three are joined; over eight words the picked line stands
        val g = HeadingWrap.group(listOf(l("Music and", 100, 100, 70), l("art for", 100, 180, 70), l("young and", 100, 260, 70), l("old", 100, 340, 70)),
            CandidateFinder.find(listOf(l("Music and", 100, 100, 70), l("art for", 100, 180, 70), l("young and", 100, 260, 70), l("old", 100, 340, 70))), 0)
        assertEquals(3, g.size)
        assertEquals("Summer reading club", widen("Summer reading club", 0, l("Summer reading club", 100, 100, 70),
            l("for children teenagers parents and", 100, 180, 70), l("grandparents of the town", 100, 260, 70), l("Sat 5 Dec", 100, 400, 50), l("10 am", 100, 470, 50)))
    }

    @Test fun anAppointmentCardsTitleIsItsWholeHeading() {
        val today = LocalDate.of(2026, 11, 14)
        val ls = listOf(l("Eye test -", 122, 266, 90), l("Dr. Mehta", 116, 361, 101), l("Wed 25 Nov", 128, 507, 76), l("12 pm", 130, 650, 66),
            l("Lake Health", 129, 756, 72), l("Centre", 134, 868, 58))
        val r = CodePipeline.run(OcrResult(1240, 1754, ls.mapIndexed { i, x -> x.copy(block = i) }, 0), today, today)
        assertEquals("Eye test - Dr. Mehta", ReviewSession.from(r, today).events.single().title)
    }
}
