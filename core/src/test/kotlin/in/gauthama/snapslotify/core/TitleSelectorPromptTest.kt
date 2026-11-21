package `in`.gauthama.snapslotify.core

import `in`.gauthama.snapslotify.core.TitleSelectorPrompt.Cand
import `in`.gauthama.snapslotify.core.TitleSelectorPrompt.Line
import `in`.gauthama.snapslotify.core.TitleSelectorPrompt.Schema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleSelectorPromptTest {
    private val words: (String) -> Int = { s -> s.split(Regex("\\s+")).count { it.isNotEmpty() } }
    private fun lines(vararg h: Int) = h.mapIndexed { i, x -> Line(i + 1, "w$i", x) }

    @Test
    fun priorityIsTallestThenNearCandidatesThenReadingOrder() {
        val ls = lines(1, 9, 1, 1, 8, 1, 7, 6, 5, 1, 1)
        // tallest five: indices 1, 4, 6, 7, 8; near candidate line 2: 1, 2, 3; then the rest
        assertEquals(listOf(1, 4, 6, 7, 8, 2, 3, 0, 5, 9, 10), TitleSelectorPrompt.priority(ls, listOf(2)))
    }

    @Test
    fun fillsUntilTheCapAndKeepsReadingOrderIds() {
        val ls = lines(1, 9, 1, 1, 8, 1, 7, 6, 5, 1, 1)   // each line piece is 2 "tokens"
        val b = TitleSelectorPrompt.fit(ls, emptyList(), emptyList(), Schema.T, fixedTokens = 10, count = words, cap = 26)
        assertNull(b.skipped)
        assertEquals(listOf(1, 4, 6, 7, 8, 0, 2, 3), b.listed)   // no candidates: reading order after the tallest
        assertEquals(26, b.tokens)
        assertTrue(b.user!!.startsWith("Lines:\n2: w1\n5: w4\n"))
    }

    @Test
    fun skipsWhenTheTallestLinesDoNotFit() {
        val ls = lines(5, 4, 3, 2, 1, 1)
        val b = TitleSelectorPrompt.fit(ls, emptyList(), emptyList(), Schema.T, fixedTokens = 10, count = words, cap = 17)
        assertEquals("tallest lines do not fit", b.skipped)
        assertNull(b.user)
    }

    @Test
    fun tdtCountsTheCandidateListAndTIgnoresIt() {
        val ls = lines(5, 4, 3, 2, 1, 1)
        val d = listOf(Cand("D1", "5 Nov", "Due", 0))
        val t = TitleSelectorPrompt.fit(ls, d, emptyList(), Schema.T, 10, words, cap = 22)
        val tdt = TitleSelectorPrompt.fit(ls, d, emptyList(), Schema.TDT, 10, words, cap = 22)
        assertEquals(6, t.listed.size)
        assertEquals("tallest lines do not fit", tdt.skipped)
        val wide = TitleSelectorPrompt.fit(ls, d, emptyList(), Schema.TDT, 10, words, cap = 40)
        assertTrue(wide.user!!.endsWith("Dates:\nD1: 5 Nov (Due)\nTimes:\nnone\n"))
    }
}
