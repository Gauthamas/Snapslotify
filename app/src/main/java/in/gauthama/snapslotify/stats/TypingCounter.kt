package `in`.gauthama.snapslotify.stats

/**
 * Key taps typed into one text field, counted from the minimal difference between the field's text before and after each
 * step: the start and end the two texts share are taken off, what is left is the text removed and the text put in.
 *
 * A step is one keyboard action. A keyboard sends its work in a batch (rewriting the word being composed, or deleting a
 * word and putting it back without one letter), and the batch is diffed once, from its start to its end, so a composing
 * rewrite that puts back the same letters counts only what really changed. A change outside a batch is a step of its own.
 *
 * A step that adds or removes one character is one key (a letter, a backspace). A step that changes several characters
 * at once is one action, [TapCosts.MULTI_CHARACTER_STEP]: a suggestion or autocorrect, a paste, an undo, glide typing,
 * text typed over a selection. Only letters typed one key at a time pay the shift and punctuation overhead ([letters]).
 * Plain Kotlin, no Android.
 */
class TypingCounter(initial: String = "") {

    /** One counted step: the text before and after, the minimal difference, and what it costs. */
    data class Step(val old: String, val new: String, val removed: String, val inserted: String, val taps: Int, val letters: Int)

    /** The text as of the last step. */
    var settled: String = initial
        private set
    /** Key taps since the last [take]. */
    var taps = 0
        private set
    /** Of those, letters typed one key at a time (they pay the overhead). */
    var letters = 0
        private set

    private var inBatch = false
    private var pending = false

    /** Every step, for the debug typing trace only. */
    var onStep: ((Step) -> Unit)? = null

    /** The keyboard opened a batch of changes. */
    fun beginBatch() { inBatch = true }

    /** The batch ended with the field holding [text]: one step for all of it. */
    fun endBatch(text: String) {
        inBatch = false
        if (pending) settle(text)
    }

    /** The user's change left the field holding [text]. */
    fun changed(text: String) {
        if (inBatch) pending = true else settle(text)
    }

    /** The app set the text itself (a chip, an undo button, the session): no taps, the new text is the starting point. */
    fun setByApp(text: String) {
        settled = text
        pending = false
    }

    /** The taps and letters since the last take, which start again from 0 (the field's edit was committed). */
    fun take(): Pair<Int, Int> = (taps to letters).also { taps = 0; letters = 0 }

    private fun settle(text: String) {
        pending = false
        val s = step(settled, text)
        settled = text
        taps += s.taps
        letters += s.letters
        if (s.removed.isNotEmpty() || s.inserted.isNotEmpty()) onStep?.invoke(s)
    }

    companion object {
        fun step(old: String, new: String): Step {
            var start = 0
            while (start < old.length && start < new.length && old[start] == new[start]) start++
            var end = 0
            while (end < old.length - start && end < new.length - start && old[old.length - 1 - end] == new[new.length - 1 - end]) end++
            val removed = old.substring(start, old.length - end)
            val inserted = new.substring(start, new.length - end)
            val changed = removed.length + inserted.length
            val taps = when {
                changed == 0 -> 0
                changed == 1 -> TapCosts.PER_CHARACTER
                else -> TapCosts.MULTI_CHARACTER_STEP
            }
            return Step(old, new, removed, inserted, taps, if (removed.isEmpty() && inserted.length == 1) 1 else 0)
        }
    }
}
