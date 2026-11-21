package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.review.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A bill's one date, by positive evidence only ([BillDue]). Invented bills; no list of dates to avoid. */
class BillDueTest {

    private val today = LocalDate.of(2026, 11, 10)

    private fun l(t: String, x: Int, y: Int, h: Int = 28) = OcrLine(t, x, y, x + t.length * h / 2, y + h)

    private fun read(vararg lines: OcrLine): ReviewSession {
        val page = OcrResult(1000, 1600, listOf(l("Brightline Power", 60, 40, 50), l("Electricity Bill", 60, 110, 36)) + lines.toList(), 0)
        return ReviewSession.from(CodePipeline.run(page.copy(lines = page.lines.mapIndexed { i, x -> x.copy(block = i) }), today), today)
    }

    @Test fun theDateWithADeadlineWordIsTheEventWhateverOtherDatesSay() {
        val s = read(l("Bill date: 01 Nov 2026", 60, 200), l("Amount payable Rs 1,245.50", 60, 260),
            l("Due date: 20 Nov 2026", 60, 320), l("Disconnection on 05 Dec 2026", 60, 380))
        val e = s.events.single()
        assertEquals("2026-11-20", e.dateIso)
        assertTrue(e.flags.none { it.code == "due_date_unclear" })
    }

    @Test fun aDeadlineAtTheEndOfTheLineAboveCountsWhenTheDateLineOnlyJoinsIt() {
        // an SMS: "... is due" / "on 20.02.2027. Pay via ..."
        val s = read(l("Rs. 502.77 for A/c 7465XXXX30 is due", 60, 200), l("on 20.02.2027. Pay via the app", 60, 240))
        assertEquals("2027-02-20", s.events.single().dateIso)
    }

    @Test fun aHeadingAboveIsNotTakenWhenTheDateLineHasItsOwnWords() {
        // "Due Date" heads a line with its own words ("Rebate if paid by"): no deadline for that date, so the other
        // date, under its own "Last date", is the one
        val s = read(l("Due Date", 60, 200), l("Rebate if paid early 14 Jan 2027", 60, 240), l("Last date 22 Jan 2027", 60, 300))
        assertEquals("2027-01-22", s.events.single().dateIso)
    }

    @Test fun equalEvidenceGivesNoDateTheCandidatesAndAFlag() {
        val s = read(l("Due date: 20 Jan 2027", 60, 200), l("Pay by 15 Jan 2027 for a 2% rebate", 60, 260), l("Bill date: 02 Jan 2027", 60, 320))
        // two plausible due dates: nothing prefilled, both offered first as chips
        assertTrue(s.noEventFound && s.chipsOnly)
        val e = s.events.single()
        assertNull(e.dateIso)
        assertEquals(setOf("20 Jan 2027", "15 Jan 2027"), e.dateChips.take(2).map { it.label }.toSet())
    }

    @Test fun noEvidenceGivesNoDateEither() {
        val s = read(l("Statement 02 Jan 2027", 60, 200), l("Consumer No. 4512 3321", 60, 260), l("Total Rs 1,245.50", 60, 320), l("Next reading 30 Jan 2027", 60, 380))
        assertTrue(s.noEventFound && s.chipsOnly)
        val e = s.events.single()
        assertNull(e.dateIso)
        assertEquals(2, e.dateChips.size)
    }

    @Test fun anAmountOnTheSameRowSettlesATie() {
        val s = read(l("Due date: 20 Jan 2027", 60, 200), l("Rs 1,245.50", 600, 200), l("Pay by 15 Jan 2027 for a 2% rebate", 60, 300))
        assertEquals("2027-01-20", s.events.single().dateIso)
    }

    @Test fun aTwoColumnLabelGivesItsValue() {
        val s = read(l("Bill date", 60, 200), l("Due date", 60, 250), l("02 Jan 2027", 600, 196), l("20 Jan 2027", 600, 246))
        assertEquals("2027-01-20", s.events.single().dateIso)
    }
}
