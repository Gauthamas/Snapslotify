package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.extract.Patterns

/**
 * Fixes common OCR confusions, one character for one, so offsets and line breaks are unchanged:
 * - letters read for digits inside numeric date and time tokens: O/o -> 0, l/I/| -> 1, S -> 5. A token is digits and
 *   those letters in up to three parts joined by / . : - ("O5/11/2O26", "1O:3O", "10:00-1l"), standing alone (no letter
 *   or digit right before or after), with at least two real digits (one is enough in a clock time with two-digit
 *   minutes, "1O:OO");
 * - the same letters in a day number of one or two characters with a real digit, right before or after a month name
 *   ("2O Nov", "1S December", "Nov 2O"), and in a year of 20xx standing alone with three real digits ("2O26");
 * - the digit 0 read for the letter O in the only month names that contain an O: "0ct", "0ctober", "N0v", "N0vember";
 * - digits read for letters in a full month name: 0 for O, 1 for l or I, 8 for B, 5 for S ("DECEM8ER", "Ju1y", "Apri1");
 * - raised ordinal letters read as superscript characters ("6ᵗʰ") as plain letters;
 * - a capital S read for 5 right before "pm" at the start of a word ("Spm", "S:30 pm"); not before "am", so "Sam"
 *   stays a name;
 * - an l or I read for 1 next to a digit in a two-character hour right before am/pm ("1l pm" is 11 pm, "I0am" 10am);
 * - a lone l or | read for 1 as an hour right before "pm" ("l pm"); not before "am", where "l am" may be "I am".
 */
object OcrNormalizer {

    private val CLOCK_TOKEN = Regex("""^[0-9OoIl|]{1,2}[:.][0-9OoIl|]{2}$""")
    private val DAY_BEFORE_MONTH = Regex(
        """(?<![A-Za-z0-9])(?!\|\d)([0-9OoIlS|]{1,2})(?=(?:st|nd|rd|th)?\s*${Patterns.MONTH}(?![A-Za-z]))""", RegexOption.IGNORE_CASE,
    )
    private val DAY_AFTER_MONTH = Regex(
        """(?<![A-Za-z])(${Patterns.MONTH}\.?\s)(?!\|\d)([0-9OoIlS|]{1,2})(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE,
    )

    /** A day of two characters, one a real digit, right before its ordinal ("2oth", "1Oth"). */
    private val ORDINAL_DAY = Regex("""(?<![A-Za-z0-9])([0-9OoIl]{2})(?=(?:st|nd|rd|th)(?![A-Za-z]))""")

    private val YEAR = Regex("""(?<![A-Za-z0-9])2[0-9OoIl]{3}(?![A-Za-z0-9])""")

    private fun day(d: String) = if (d.none(Char::isDigit) || d.all(Char::isDigit)) d else String(CharArray(d.length) { FIX[d[it]] ?: d[it] })

    // a "|" before a digit opens no token: it is a column bar ("|7:30 pm"), not a 1
    private val TOKEN = Regex("""(?<![A-Za-z0-9])(?!\|\d)[0-9OoIlS|]{1,4}(?:[/.:\-][0-9OoIlS|]{1,4}){1,2}(?![A-Za-z0-9])""")
    private val FIX = mapOf('O' to '0', 'o' to '0', 'I' to '1', 'l' to '1', '|' to '1', 'S' to '5')
    private val L_FOR_ONE = Regex("""(?<![A-Za-z0-9.,:])(?=[0-9lI]{2}(?:[:.]\d{2})?\s?[aApP]\.?\s?[mM](?![A-Za-z]))(?=[lI]?\d)[0-9lI]{2}""")
    private val S_FOR_FIVE = Regex("""(?<![A-Za-z0-9.,:])S(?=(?:[:.]\d{2})?\s?[pP]\.?\s?[mM](?![A-Za-z]))""")
    private val ONE_BEFORE_PM = Regex("""(?<![A-Za-z0-9.,:])[l|](?=(?:[:.]\d{2})?\s?[pP]\.?\s?[mM](?![A-Za-z]))""")
    /** A month's day glued to a clock by a colon ("Oct 1:7:00"): three colon groups, the first a day after a month name. */
    private val DAY_GLUED_TO_CLOCK = Regex("""(?i)(?<=\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]{0,6}\.?\s{1,2}\d{1,2}):(?=\d{1,2}:\d{2}(?!\d))""")
    private val MONTH_WITH_ZERO = Regex("""(?i)(?<![A-Za-z])(?:0ct(?:ober)?|n0v(?:ember)?)(?![A-Za-z])""")

    /** Superscript letters of ordinals, one for one. */
    private val SUPERSCRIPT = mapOf('ᵗ' to 't', 'ʰ' to 'h', 'ˢ' to 's', 'ⁿ' to 'n', 'ᵈ' to 'd', 'ʳ' to 'r')
    private val FULL_MONTHS = setOf("JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE", "JULY", "AUGUST", "SEPTEMBER",
        "OCTOBER", "NOVEMBER", "DECEMBER")
    /** A word of letters with a 0, 1, 5 or 8 inside, not after another letter or digit. */
    private val DIGIT_IN_WORD = Regex("""(?<![A-Za-z0-9])[A-Za-z][A-Za-z0158]{2,8}(?![A-Za-z])""")

    /** A month name with digits read for letters, written with the letters (same length, case kept); else null. */
    private fun monthWithDigits(w: String): String? {
        if (w.none(Char::isDigit) || w.last().isDigit() && w.dropLastWhile(Char::isDigit).length < 3) return null
        for (one in listOf('L', 'I')) {
            val letters = w.map { c -> when (c) { '0' -> 'O'; '1' -> one; '8' -> 'B'; '5' -> 'S'; else -> c.uppercaseChar() } }.joinToString("")
            if (letters in FULL_MONTHS) {
                val upper = w.filter(Char::isLetter).all(Char::isUpperCase)
                return w.mapIndexed { i, c -> if (c.isDigit()) (if (upper || i == 0) letters[i] else letters[i].lowercaseChar()) else c }.joinToString("")
            }
        }
        return null
    }

    fun normalizeNumeric(s0: String): String {
        val s = DIGIT_IN_WORD.replace(String(CharArray(s0.length) { SUPERSCRIPT[s0[it]] ?: s0[it] })) { m -> monthWithDigits(m.value) ?: m.value }
        val digits = TOKEN.replace(s) { m ->
            val token = m.value
            val needed = if (CLOCK_TOKEN.matches(token)) 1 else 2
            if (token.count { it.isDigit() } < needed) token else String(CharArray(token.length) { FIX[token[it]] ?: token[it] })
        }.let { t -> DAY_BEFORE_MONTH.replace(t) { m -> day(m.groupValues[1]) } }
            .let { t -> DAY_AFTER_MONTH.replace(t) { m -> m.groupValues[1] + day(m.groupValues[2]) } }
            .let { t -> YEAR.replace(t) { m -> if (m.value.count(Char::isDigit) >= 3) day(m.value).let { y -> if (y.startsWith("20")) y else m.value } else m.value } }
        val ordinals = ORDINAL_DAY.replace(digits) { m -> day(m.groupValues[1]) }
        val hours = L_FOR_ONE.replace(ordinals) { m -> m.value.replace('l', '1').replace('I', '1') }
        val unglued = DAY_GLUED_TO_CLOCK.replace(hours, " ")
        return MONTH_WITH_ZERO.replace(ONE_BEFORE_PM.replace(S_FOR_FIVE.replace(unglued, "5"), "1")) { m ->
            // upper-case O if the next letter is upper case ("0CT"), else "O" at the start / "o" inside
            val upper = m.value.any { it.isUpperCase() && it != '0' } && m.value.filter(Char::isLetter).all(Char::isUpperCase)
            m.value.mapIndexed { i, c -> if (c == '0') (if (upper || i == 0) 'O' else 'o') else c }.joinToString("")
        }
    }
}
