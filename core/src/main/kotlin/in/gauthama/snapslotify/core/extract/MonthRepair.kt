package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.extract.Patterns.MONTH
import `in`.gauthama.snapslotify.core.extract.Patterns.regex

/**
 * OCR damage in a month name: one character dropped, doubled, added, swapped with its neighbour or wrong ("Novemnber",
 * "Septmber", "Decmeber", "Octobar"). Only full names of [MIN_LENGTH] letters or more are repaired (January, February,
 * March, April, August, September, October, November, December); abbreviations and the short names (May, June, July)
 * must be exact, since "Mar", "May", "Jun" and "Jul" are one letter apart. A word one change away from two month names
 * is unreadable and left as it is.
 *
 * [repair] gives a copy of the text with damaged month names written out, and maps each of its positions back to the
 * original, so dates are found on the copy but placed, and quoted, in the original. [Dates.findDates] takes a repaired
 * month only in a date with a year written, so a word that is merely one letter from a month name ("Match 5") is not
 * read as a date.
 */
object MonthRepair {

    const val MIN_LENGTH = 5

    private val FULL = listOf("january", "february", "march", "april", "august", "september", "october", "november", "december")
        .filter { it.length >= MIN_LENGTH }
    private val EXACT = regex("""^$MONTH$""")
    private val WORD = Regex("""(?<![\p{L}])(?:(?<=\d)(?:st|nd|rd|th|ST|ND|RD|TH))?(\p{L}{4,11})(?![\p{L}])""")

    /** The repaired copy: [text], each of its positions' place in the original ([original]), and the repaired spans. */
    class Fixed internal constructor(val text: String, private val map: IntArray?, private val spans: List<IntRange>) {
        fun original(i: Int): Int = map?.getOrNull(i) ?: i
        /** True when [r] (a range of [text]) covers a repaired month name. */
        fun touches(r: IntRange): Boolean = spans.any { it.first <= r.last && r.first <= it.last }
    }

    /**
     * The month name [word] is damaged from, or null (no month within one change, or more than one). "rn" read for "m"
     * ("Decernber", "Novernber") counts as one change.
     */
    fun month(word: String): String? {
        val w = word.lowercase()
        if (w.length < MIN_LENGTH - 1 || EXACT.matches(w)) return null
        if ("rn" in w) w.replace("rn", "m").let { m -> if (m in FULL) return m }
        return FULL.filter { oneEdit(w, it) }.singleOrNull()
    }

    fun repair(s: String): Fixed {
        val hits = WORD.findAll(s).mapNotNull { r -> r.groups[1]!!.let { m -> month(m.value)?.let { m to it } } }.toList()
        if (hits.isEmpty()) return Fixed(s, null, emptyList())
        val sb = StringBuilder(); val map = ArrayList<Int>(s.length + 8); val spans = mutableListOf<IntRange>()
        var at = 0
        for ((m, name) in hits) {
            for (i in at until m.range.first) { sb.append(s[i]); map += i }
            val w = m.value
            val fixed = when {
                w.all { !it.isLetter() || it.isUpperCase() } -> name.uppercase()
                w.first().isUpperCase() -> name.replaceFirstChar(Char::uppercase)
                else -> name
            }
            val from = sb.length
            // each letter to the original word's letter at that place; the last always to its last, so a date's text
            // taken from the original keeps the whole word ("Decemmber", one letter longer than "December")
            fixed.forEachIndexed { k, ch -> sb.append(ch); map += m.range.first + if (k == fixed.lastIndex) w.length - 1 else minOf(k, w.length - 1) }
            spans += from until sb.length
            at = m.range.last + 1
        }
        for (i in at until s.length) { sb.append(s[i]); map += i }
        return Fixed(sb.toString(), map.toIntArray(), spans)
    }

    /** a and b differ by exactly one change: one letter wrong, dropped, added (a doubled letter is one added), or two neighbours swapped. */
    internal fun oneEdit(a: String, b: String): Boolean {
        if (a == b) return false
        return when (a.length - b.length) {
            0 -> {
                val diff = a.indices.filter { a[it] != b[it] }
                diff.size == 1 || (diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]])
            }
            1 -> a.indices.any { a.removeRange(it, it + 1) == b }
            -1 -> b.indices.any { b.removeRange(it, it + 1) == a }
            else -> false
        }
    }
}
