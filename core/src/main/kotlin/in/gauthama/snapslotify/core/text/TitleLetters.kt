package `in`.gauthama.snapslotify.core.text

/**
 * Digits OCR read for letters in a title's words: 0 for O, 1 for I, 5 for S, 8 for B ("KITE SH0W", "F1LM NIGHT",
 * "5UMMER FAIR", "Sh0w"). Only in a word of two or more letters, and only a digit with a letter on both sides, or at the
 * word's start before three or more letters; in lower case only 0 (o), since a lower-case 1 may be an l or an i. A word
 * with any other digit, or a digit at its end, is left alone ("PS5", "COVID19", "5K", "B12", "Win10"). A word broken by a
 * hyphen at a line end loses the space after the hyphen; the hyphen stays (it may be a real one, "CHECK-UP").
 */
object TitleLetters {

    private val UPPER = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B')
    private val WORD = Regex("""[\p{L}\d]+""")

    /** A word broken at a line end by a hyphen and joined back with a space ("FESTI- VAL", "Check- up"): the space goes. */
    private val HYPHEN_BREAK = Regex("""(\p{Lu})-\s+(\p{Lu})|(\p{L})-\s+(\p{Ll})""")

    fun fix(title: String?): String? = title?.let { t ->
        WORD.replace(t) { m -> word(m.value) }.replace(HYPHEN_BREAK) { m -> m.groupValues.drop(1).filter(String::isNotEmpty).joinToString("-") }
    }

    private fun word(w: String): String {
        if (w.none(Char::isDigit) || w.count(Char::isLetter) < 2 || w.any { it.isDigit() && it !in UPPER }) return w
        if (w.last().isDigit()) return w
        val out = w.toCharArray()
        for ((i, c) in w.withIndex()) {
            if (!c.isDigit()) continue
            // the letters around this digit (over other digits of the same run)
            val before = (i - 1 downTo 0).firstOrNull { !w[it].isDigit() }?.let { w[it] }
            val after = (i + 1 until w.length).firstOrNull { !w[it].isDigit() }?.let { w[it] }
            // a single digit (not 8) opening a word of three or more letters: "5UMMER", not "50PLUS" or "8BALL"
            val atStart = before == null && i == 0 && !w[1].isDigit() && c != '8' && w.count(Char::isLetter) >= 3
            if (after == null || (before == null && !atStart)) return w
            val upper = (before ?: after).isUpperCase() && after.isUpperCase()
            val lower = (before ?: after).isLowerCase() && after.isLowerCase()
            out[i] = when {
                upper -> UPPER.getValue(c)
                lower && c == '0' -> 'o'
                else -> return w
            }
        }
        return String(out)
    }
}
