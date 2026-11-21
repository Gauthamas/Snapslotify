package `in`.gauthama.snapslotify.core.review

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The title cap, the model-title check and where a model title may go; the location rule; Try harder's pages. Invented text. */
class TitleRulesTest {

    private val today = LocalDate.of(2026, 11, 11)

    private fun page(vararg ls: Pair<String, Int>): OcrResult {
        var top = 100
        return OcrResult(1000, 2000, ls.mapIndexed { i, (t, h) -> OcrLine(t, 40, top, 900, top + h, confidence = 0.9f, block = i).also { top += h + 20 } }, 300)
    }

    // ---- the cap ----

    @Test fun aShortTitleIsKeptAsItIs() {
        assertEquals("Pottery workshop", TitleRules.short("Pottery  workshop "))
        assertEquals("Zoom call with the whole family", TitleRules.short("Zoom call with the whole family"))
        assertNull(TitleRules.short(null))
    }

    @Test fun aLongTitleIsCutAtAClauseBoundary() {
        assertEquals("Annual kite festival, with food stalls", TitleRules.short("Annual kite festival, with food stalls, music and a lantern walk by the river"))
        assertEquals("Garden club meeting", TitleRules.short("Garden club meeting - bring your own seeds and a packed lunch for everyone"))
    }

    @Test fun withNoBoundaryItIsCutAtAWordAndDoesNotEndOnASmallWord() {
        val t = TitleRules.short("our plan is a picnic at the lake near the old boat house this weekend")!!
        assertTrue(t, TitleRules.fits(t))
        assertEquals("our plan is a picnic at the lake", t)
    }

    @Test fun aCutTitleNeverExceedsTheCap() {
        val t = TitleRules.short("Supercalifragilistic expialidocious celebrations commemorating extraordinary achievements tonight")!!
        assertTrue(t.length <= TitleRules.MAX_CHARS)
    }

    // ---- the model title check ----

    private val text = "Hello all,\nThe pottery workshop is on 21 Nov 2026 at 10 am.\nRoom 4B, Arts Centre"

    @Test fun aModelTitleMadeOfPageWordsPasses() {
        assertTrue(TitleRules.modelTitleOk("Pottery Workshop", text))
        assertTrue(TitleRules.modelTitleOk("Pottery workshop at the Arts Centre", text))
        assertTrue(TitleRules.modelTitleOk("Workshop in Room 4", text))
    }

    @Test fun aWordOrNumberNotOnThePageFails() {
        assertFalse(TitleRules.modelTitleOk("Ceramics class", text))          // own words
        assertFalse(TitleRules.modelTitleOk("Pottery workshop 22 Nov", text)) // a number not on the page
        assertFalse(TitleRules.modelTitleOk("Potery workshop", text))          // spelt differently
    }

    @Test fun moreThanEightWordsOrOnlySmallWordsFail() {
        assertFalse(TitleRules.modelTitleOk("the pottery workshop is on at the arts centre", text))
        assertFalse(TitleRules.modelTitleOk("at the", text))
        assertFalse(TitleRules.modelTitleOk("  ", text))
    }

    @Test fun aDateOrATimeIsNotATitle() {
        assertFalse(TitleRules.modelTitleOk("10 am", text))
        assertFalse(TitleRules.modelTitleOk("Workshop 21 Nov", text))
    }

    @Test fun aModelTitleMustKeepTheNamesInTheCodeTitle() {
        assertFalse(TitleRules.keepsNames("Flight", "Your flight lands in Lisbon"))
        assertFalse(TitleRules.keepsNames("Party", "party for Marco"))
        assertFalse(TitleRules.keepsNames("Glow-Lake Fest", "Your pass to Glow-Lake River Fest"))
        assertTrue(TitleRules.keepsNames("Lisbon flight", "Your flight lands in Lisbon"))
        assertTrue(TitleRules.keepsNames("Bake sale", "What about a bake sale"))   // the first word is not a name
        assertTrue(TitleRules.keepsNames("Bake sale", null))
    }

    @Test fun aBillKeepsItsCodeTitle() {
        val s = ReviewSession.from(CodePipeline.run(page("Brightline Power" to 60, "Electricity bill" to 40, "Due date" to 40, "05 Dec 2026" to 40), today, today), today)
        assertTrue(s.events[0].bill)
        assertFalse(s.applyModelTitle(0, "Brightline Power", "Brightline Power Electricity bill Due date 05 Dec 2026"))
    }

    // ---- a suggestion only: the field keeps the code's title ----

    private fun session() = ReviewSession.from(CodePipeline.run(page("The pottery workshop is on 21 Nov 2026 at 10 am." to 40), today, today), today)

    @Test fun aPassingModelTitleIsOfferedAsAChipAndTheFieldKeepsTheCodeTitle() {
        val s = session()
        val code = s.events[0].title
        assertTrue(s.applyModelTitle(0, "Pottery workshop", text))
        assertEquals(code, s.events[0].title)
        assertFalse(s.events[0].modelTitleApplied)
        assertEquals(listOf("Pottery workshop"), s.events[0].suggestedTitles)
        assertFalse(s.applyModelTitle(0, "Pottery workshop", text))             // offered once
        // one tap on the chip puts it in the field
        s.acceptSuggestedTitle(0, "Pottery workshop")
        assertEquals("Pottery workshop", s.events[0].title)
        assertEquals(1, s.events[0].counts.chipTaps)
    }

    @Test fun aFailingModelTitleLeavesTheCodeTitleAndShowsNothing() {
        val s = session()
        val code = s.events[0].title
        assertFalse(s.applyModelTitle(0, "Ceramics class", text))
        assertEquals(code, s.events[0].title)
        assertTrue(s.events[0].suggestedTitles.isEmpty())
    }

    @Test fun aClearedTitleIsOfferedBackFirstAndOneTapRestoresIt() {
        val s = session()
        val code = s.events[0].title!!
        s.applyModelTitle(0, "Pottery workshop", text)
        s.offerClearedTitle(0, code)
        assertEquals(listOf(code, "Pottery workshop"), s.events[0].suggestedTitles)
        s.offerClearedTitle(0, code)                                            // offered once
        assertEquals(2, s.events[0].suggestedTitles.size)
        s.editTitle(0, "", 0)
        s.acceptSuggestedTitle(0, code)
        assertEquals(code, s.events[0].title)
    }

    @Test fun anEditedTitleIsNeverReplacedTheModelTitleIsOnlyAChip() {
        val s = session()
        s.editTitle(0, "My own title", 12)
        assertTrue(s.applyModelTitle(0, "Pottery workshop", text))
        assertEquals("My own title", s.events[0].title)
        assertEquals(listOf("Pottery workshop"), s.events[0].suggestedTitles)
    }

    @Test fun theStateSurvivesSaveAndRestoreButTheImprovingNoteDoesNot() {
        val s = session()
        s.applyModelTitle(0, "Pottery workshop", text)
        s.events[0].titleImproving = true
        val r = ReviewSession.restore(s.save())
        assertEquals(listOf("Pottery workshop"), r.events[0].suggestedTitles)
        assertFalse(r.events[0].titleImproving)
    }

    // ---- location ----

    @Test fun locationIsHiddenUntilFoundOrAsked() {
        val s = session()
        assertNull(s.events[0].location)
        assertFalse(s.events[0].showLocation)
        s.showLocationField(0)
        assertTrue(s.events[0].showLocation)
        val v = ReviewSession.from(CodePipeline.run(page("Book swap evening" to 80, "Sat 21 Nov 2026, 6 pm" to 40, "Venue: Old Mill Library" to 40), today, today), today)
        assertEquals("Old Mill Library", v.events[0].location)
        assertTrue(v.events[0].showLocation)
    }

    @Test fun aQuestionOrAColumnLabelIsNotALocation() {
        fun loc(vararg ls: Pair<String, Int>) = CodePipeline.run(page(*ls), today, today).events.firstOrNull()?.event?.location
        assertNull(loc("Book swap evening" to 80, "Sat 21 Nov 2026, 6 pm" to 40, "where did you put the spare keys?" to 40))
        assertNull(loc("Book swap evening" to 80, "Sat 21 Nov 2026, 6 pm" to 40, "Venue" to 40, "Time" to 40))
        assertEquals("Old Mill Library", loc("Book swap evening" to 80, "Sat 21 Nov 2026, 6 pm" to 40, "Where:" to 40, "Old Mill Library" to 40))
        // a label alone on its line, the place on the next
        assertEquals("Old Mill Library", loc("Book swap evening" to 80, "Sat 21 Nov 2026, 6 pm" to 40, "WHERE" to 40, "Old Mill Library" to 40))
    }

    // ---- Try harder's pages ----

    @Test fun tryHarderFitsAOneEventPosterOnly() {
        assertTrue(CodePipeline.run(page("BOOK SWAP EVENING" to 120, "Sat 21 Nov 2026" to 60, "6 pm" to 40), today, today).tryHarderFits)
        // several dates: a list, not a poster
        assertFalse(CodePipeline.run(page("Club fixtures" to 80, "Sat 21 Nov 2026 Home game" to 40, "Sat 28 Nov 2026 Away game" to 40,
            "Sat 5 Dec 2026 Home game" to 40), today, today).tryHarderFits)
        // no event found: offered (its answer is chips only)
        assertTrue(CodePipeline.run(page("Thank you for visiting" to 80), today, today).tryHarderFits)
        assertTrue(CodePipeline.run(page("KITE SHOW" to 120, "Riverside park" to 40), today, today).tryHarderFits)
    }
}
