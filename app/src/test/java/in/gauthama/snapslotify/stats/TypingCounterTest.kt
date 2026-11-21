package `in`.gauthama.snapslotify.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Key taps in the review form's text fields ([TypingCounter]) and what they do to the saved estimate, on the Team lunch
 * event: title "Team lunch", Thu 3 Dec 2026 1 pm, no location; today Sat 14 Nov 2026. The keyboard steps are faked the
 * way a keyboard sends them: a batch that rewrites the word being composed.
 */
class TypingCounterTest {

    private val today = LocalDate.of(2026, 11, 14)
    private val title = "Team lunch"

    /** One keyboard batch: each text the field passes through, then the batch end. */
    private fun TypingCounter.batch(vararg texts: String) {
        beginBatch()
        texts.forEach(::changed)
        endBatch(texts.last())
    }

    /** The Team lunch event with [final] as its title, after [touches] form edits and the counter's taps and letters. */
    private fun saved(final: String, touches: Int = 0, c: TypingCounter? = null) = TapsSaved.estimate(
        TapsSaved.Added(final, null, LocalDate.of(2026, 12, 3), timed = true, formTouches = touches, typedChars = c?.taps ?: 0,
            typedLetters = c?.letters ?: 0), today, firstOnPage = true).saved

    @Test fun theTeamLunchEventAcceptedAsIs() {
        // by hand: 10 + 1 month + 1 (title field) + 10 + ceil(1.0) = 23; with the app: 1 (Add) + 1 (calendar Save)
        // + 1 (Done on the confirmation) = 3 (both sides start inside the app)
        assertEquals(TapsSaved.Estimate(1, 23, 3, 20, 10), TapsSaved.estimate(
            TapsSaved.Added(title, null, LocalDate.of(2026, 12, 3), timed = true, formTouches = 0, typedChars = 0), today, firstOnPage = true))
    }

    @Test fun deletingOneLetterInsideAWordIsOneTap() {
        // backspace after the n of "lunch": the keyboard rewrites the composing word "lunch" as "luch"
        val rewrite = TypingCounter(title).apply { batch("Team luch") }
        // or deletes the word and puts it back without the letter, in one batch
        val reinsert = TypingCounter(title).apply { batch("Team ", "Team luch") }
        for (c in listOf(rewrite, reinsert)) {
            assertEquals(1, c.taps)
            assertEquals(0, c.letters)          // a backspace pays no shift or punctuation overhead
        }
        // the estimate moves by 3: one field edit and one backspace with the app, one character fewer by hand
        assertEquals(20 - 3, saved("Team luch", touches = 1, c = rewrite))
        assertEquals(17, saved("Team luch", touches = 1, c = reinsert))
        // a second letter deleted in the same edit: 2 more (one backspace, one character fewer by hand)
        rewrite.batch("Team lch")
        assertEquals(17 - 2, saved("Team lch", touches = 1, c = rewrite))
    }

    @Test fun typingOneLetterInsideAWordIsOneTap() {
        // "lunch" to "lunech": the composing word rewritten with one letter more
        val c = TypingCounter(title).apply { batch("Team lunech") }
        assertEquals(1, c.taps)
        assertEquals(1, c.letters)
        // by hand 10 + 1 + 1 + 11 + ceil(1.1) = 25 (+2); with the app 1 + 1 + 1 + ceil(0.1) + 1 + 1 = 6 (+3): saved 20 to 19
        assertEquals(19, saved("Team lunech", touches = 1, c = c))
    }

    @Test fun aLetterDeletedAndTypedBackStillCostsItsTaps() {
        // "Team lunch" to "Team luch" and back: the title ends as prefilled, but the touch and two keys were made
        val c = TypingCounter(title)
        c.batch("Team luch")
        assertEquals(17, saved("Team luch", touches = 1, c = c))
        c.batch(title)
        assertEquals(2, c.taps)
        assertEquals(1, c.letters)
        // by hand 23 (the same title); with the app 1 + 1 (field touch) + 2 + ceil(0.1) + 1 + 1 = 7
        assertEquals(16, saved(title, touches = 1, c = c))
    }

    @Test fun theClearButtonIsOneTap() {
        // the clear button empties the field as the app (no key taps); its one tap is the field's edit
        val c = TypingCounter(title)
        c.setByApp("")
        assertEquals(0, c.taps)
        // by hand 10 + 1 month + 1 (title field) = 12; with the app 1 + 1 (the clear) + 1 + 1 = 4
        assertEquals(8, saved("", touches = 1, c = c))
        // then "Lunch" typed: five keys and one overhead with the app, five letters and one overhead by hand
        var text = ""
        for (ch in "Lunch") { text += ch; c.batch(text) }
        assertEquals(5 to 5, c.taps to c.letters)
        assertEquals(8, saved("Lunch", touches = 1, c = c))
    }

    @Test fun aComposingRewriteThatPutsBackTheSameLettersCountsNothing() {
        val c = TypingCounter(title)
        c.batch("Team lunch")                   // the keyboard re-sets the word under the cursor
        c.batch("Team ", "Team lunch")          // or deletes it and puts it back
        assertEquals(0, c.taps)
    }

    @Test fun aSuggestionTappedIsOneTap() {
        // "lun" typed, then the suggestion "lunch" tapped
        val c = TypingCounter("Team ")
        c.batch("Team l"); c.batch("Team lu"); c.batch("Team lun")
        c.batch("Team lunch")
        assertEquals(4, c.taps)
        assertEquals(3, c.letters)
        // autocorrect swapping letters is one step as well
        assertEquals(1, TypingCounter("Team luhcn").apply { batch("Team lunch") }.taps)
    }

    @Test fun pastingTheFullTitleIsOneAction() {
        // the field cleared and the copied title pasted: one select-all-and-delete, one paste
        val c = TypingCounter("Lunch with team")
        c.changed("")
        c.changed(title)
        assertEquals(2, c.taps)
        assertEquals(0, c.letters)
        // by hand 23; with the app 1 + 1 (field edit) + 2 + 0 + 2 = 6
        assertEquals(17, saved(title, touches = 1, c = c))
    }

    @Test fun undoIsOneTap() {
        // one letter deleted, then undone; the text is back as it was
        val c = TypingCounter(title)
        c.batch("Team luch")
        c.changed(title)                        // undo puts the n back
        assertEquals(2, c.taps)
        // a paste undone at once is one tap too
        val p = TypingCounter("")
        p.changed(title); p.changed("")
        assertEquals(2, p.taps)
    }

    @Test fun textTheAppSetsCountsNothing() {
        val c = TypingCounter("")
        c.setByApp(title)
        c.batch("Team luch")
        assertEquals(1 to 0, c.take())
        assertEquals(0 to 0, c.take())          // taken at the field's commit, the count starts again
        assertEquals("Team luch", c.settled)
    }

    @Test fun theLocationFieldCountsTheSameWay() {
        // "Olive Tree" typed letter by letter into the location field, one letter deleted and typed again
        val c = TypingCounter("")
        var text = ""
        for (ch in "Olive Tree") { text += ch; c.batch(text) }
        c.batch("Olive Tre"); c.batch("Olive Tree")
        assertEquals(12, c.taps)
        assertEquals(11, c.letters)
    }
}
