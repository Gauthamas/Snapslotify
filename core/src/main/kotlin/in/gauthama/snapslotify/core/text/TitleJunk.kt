package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.ocr.OcrLine

/**
 * Junk OCR reads into a heading (a sticker, a logo, a fold, a background pattern): words of a shape no title word has.
 *
 * Two tiers. Tier one, [Kind.STRONG], a shape no real word has, taken off the prefilled title whatever the engine's
 * confidence: two or more case flips inside a word ("sHoWv"); one letter three times running, unless the word is a Roman
 * numeral in I, V and X ("llll", "mmm", not "III"); letters and digits alternating twice or more ("x7q2"), not a size
 * ("4x4"); a symbol no word has inside or on a word (`| [ ] { } ~ ^ \ < > =`, an "@" inside a word, a "#" after two
 * characters, so not "C#"); punctuation other than "&" inside a word of one or two letters ("n'q", not "Q&A").
 * Tier two, shapes real words share (abbreviations, acronyms, words with no vowel: "Blvd", "Mgmt", "Psst", "Dr", "BBQ"),
 * never taken off the prefilled title; their removal is only offered ([trim] with tierTwo): [Kind.SUSPECT], four or more
 * letters with no vowel, unless in capitals ("kltrs", not "BLVD"), at any confidence; [Kind.WEAK], only when the engine
 * read the word clearly less sure than the rest of the heading ([LOW_GAP]): a single letter other than "A" and "I"; a
 * short letters-only word with no vowel ("BBQ", "Rd"); a Roman numeral ("iii"). "y" counts as a vowel ("Gym", "Myth").
 *
 * A title word is judged as OCR read it: the heading line's word with the same letters and digits ("ri" in a title is
 * "~~ri" as read; the title search had already taken its symbols off).
 *
 * [trim] takes junk words off the ends of a title only (never from the middle, where names sit), never a word the page
 * has elsewhere, never a whole word with a plain word glued inside it, and never down to a title with no plain word of
 * three letters. Junk glued onto the outer side of the first or last word, with or without a symbol between
 * ("pOrTqCharity", "Fairllll", "Fair|l"), comes off that word alone: the word is cut only where its letter shape changes,
 * only when exactly one such cut leaves a plain word and a strong junk-shaped part (a weak shape glued on looks like a real
 * word's prefix: "iPhone", "McKenzie"); never next to an apostrophe or hyphen ("O'Neil", "X-Men"). [junkLine] tells a line made
 * of tier-one junk only, which is never joined to a heading.
 */
object TitleJunk {

    enum class Kind { STRONG, SUSPECT, WEAK }

    /** A weak-shaped word is junk when its confidence is at least this much below the median of the heading's other words. */
    const val LOW_GAP = 0.25f

    private val CASE_FLIP = Regex("""\p{Ll}\p{Lu}""")
    private val TRIPLE = Regex("""(\p{L})\1\1""")
    private val ROMAN = Regex("""^(?=[IVX])X{0,3}(IX|IV|V?I{0,3})$""")
    private val LETTER_DIGIT = Regex("""(?<=\p{L})\d|(?<=\d)\p{L}""")
    private val SIZE = Regex("""\d+[xX]\d+""")
    private val ODD = Regex("""[|\[\]{}~^\\<>=]|(?<=\S)@|(?<=\S\S)#""")
    private val INNER_MARK = Regex("""\p{L}[^\p{L}\p{N}\s&]+\p{L}""")
    /** A plain word inside a junk-shaped one: three or more letters in one case, or capitalised. */
    private val PLAIN = Regex("""\p{Lu}\p{Ll}{2,}|\p{Ll}{3,}|\p{Lu}{3,}""")
    private const val VOWELS = "aeiouyAEIOUY"
    private val WORDS = setOf("A", "a", "I")
    private val TOKEN = Regex("""\S+""")
    private val LANGUAGE = Regex("""\p{Lu}[#+]+""")

    private fun core(w: String) = w.trim { !it.isLetterOrDigit() }

    /** [word]'s junk shape, after the digits OCR read for letters are letters again ([TitleLetters]: "B00K" is "BOOK"). */
    fun kind(word: String): Kind? = shape(TitleLetters.fix(word) ?: word)

    private fun shape(word: String): Kind? {
        val letters = word.filter(Char::isLetter)
        if (letters.isEmpty()) return null
        val c = core(word)
        val strong = CASE_FLIP.findAll(word).count() >= 2 ||
            (TRIPLE.containsMatchIn(word) && !ROMAN.matches(c.uppercase())) ||
            (LETTER_DIGIT.findAll(word).count() >= 2 && !SIZE.matches(c)) ||
            ODD.containsMatchIn(word) ||
            (letters.length <= 2 && INNER_MARK.containsMatchIn(word))
        if (strong) return Kind.STRONG
        if (letters.length >= 4 && letters.none { it in VOWELS } && !letters.all(Char::isUpperCase)) return Kind.SUSPECT
        // a programming language's name ("C#", "C++", "F#"), not a stray letter
        if (LANGUAGE.matches(word)) return null
        // "A" and "I" are words ("A Quiet Harvest", "I will come"), never junk by shape
        if (c.length == 1 && c[0].isLetter()) return if (c in WORDS) null else Kind.WEAK
        if (ROMAN.matches(c.uppercase())) return Kind.WEAK
        if (c.all(Char::isLetter) && letters.length <= 4 && letters.none { it in VOWELS }) return Kind.WEAK
        return null
    }

    /** Characters that join the parts of real words ("O'Neil", "X-Men"): a word is never cut next to one. */
    private const val JOINERS = "'’-"

    /**
     * The places where a word's letter shape changes, the only places it may be cut: lower case to upper case ("q|C"),
     * capitals to a capitalised word ("IIII|Charity"), letters to digits or symbols and back, into or out of a run of
     * three or more of one letter ("Fair|llll"), and into or out of letters alternating with digits ("Run|t4ix"). Never
     * between a capital and the small letter after it (a word starts there), nor inside letters alternating with digits.
     */
    private fun cuts(w: String): List<Int> = (1 until w.length).filter { i ->
        val a = w[i - 1]; val b = w[i]; val next = w.getOrNull(i + 1); val before = w.getOrNull(i - 2)
        val insideAlternation = (a.isLetter() && b.isDigit() && before?.isLetter() == true && next?.isLetter() == true) ||
            (a.isDigit() && b.isLetter() && before?.isLetter() == true && next?.isDigit() == true)
        a !in JOINERS && b !in JOINERS && !(a.isUpperCase() && b.isLowerCase()) && !insideAlternation && (
            (a.isLowerCase() && b.isUpperCase()) ||
                (a.isUpperCase() && b.isUpperCase() && next?.isLowerCase() == true) ||
                a.isLetter() != b.isLetter() ||
                (a != b && next == b && w.getOrNull(i + 2) == b) ||
                (a != b && before == a && w.getOrNull(i - 3) == a) ||
                (a.isLetter() && b.isLetter() && next?.isDigit() == true && before?.isDigit() != true) ||
                (a.isLetter() && b.isLetter() && before?.isDigit() == true && next?.isDigit() != true))
    }

    private fun segments(w: String): List<String> {
        val at = listOf(0) + cuts(w) + w.length
        return at.zipWithNext { s, e -> w.substring(s, e) }
    }

    private fun plainWord(part: String) = PLAIN.matches(part) && part.any { it in VOWELS } && kind(part) == null

    /** A word with a plain word inside it, glued to junk or not ("Fair|l", "~Market", "pOrTqCharity"): never removed whole. */
    private fun holdsPlainWord(word: String): Boolean = segments(word).any(::plainWord)

    /**
     * [w] without junk glued onto its outer side ([front]: before it, else after it), cut where its letter shape changes:
     * the one cut that leaves a plain word and a junk-shaped part [junkOk] accepts. Null when no cut or more than one fits.
     */
    private fun unglue(w: String, front: Boolean, junkOk: (String, Kind) -> Boolean): String? = cuts(w).mapNotNull { i ->
        val junk = if (front) w.substring(0, i) else w.substring(i)
        val real = if (front) w.substring(i) else w.substring(0, i)
        val bare = if (front) real.trimEnd { it in ",.:;!?)" } else real.trimStart { it in "(\"'" }
        val k = kind(junk)
        if (k != null && plainWord(bare) && junkOk(junk, k)) real else null
    }.singleOrNull()

    private fun count(lines: List<OcrLine>, word: String): Int =
        lines.sumOf { l -> TOKEN.findAll(l.text).count { it.value.equals(word, ignoreCase = true) } }

    private fun median(xs: List<Float>): Float? = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    /**
     * [title] without junk words at its ends, read against the page's [lines] (their words' confidences, other mentions):
     * tier-one junk only, the prefilled title; with [tierTwo] also the words that may be junk, the title offered as a chip.
     */
    fun trim(title: String?, lines: List<OcrLine>, tierTwo: Boolean = false): String? {
        if (title == null) return null
        val words = TOKEN.findAll(title).map { it.value }.toList()
        if (words.size < 2) return title
        // the heading's line: the line holding most of the title's words
        val home = lines.maxByOrNull { l -> words.count { w -> l.words.any { core(it.text) == core(w) } } }
        /** [w] as OCR read it on the heading's line (its symbols kept). */
        fun read(w: String) = home?.words?.firstOrNull { it.text == w } ?: home?.words?.firstOrNull { core(w).isNotEmpty() && core(it.text) == core(w) }
        val heading = median(words.filter { kind(read(it)?.text ?: it) == null }.mapNotNull { read(it)?.confidence })
        fun junk(t: String): Boolean {
            val w = read(t)?.text ?: t
            val k = kind(w) ?: return false
            if (holdsPlainWord(w) || count(lines, w) > 1) return false
            if (k == Kind.STRONG) return true
            if (!tierTwo) return false
            if (k == Kind.SUSPECT) return true
            val c = read(t)?.confidence ?: return false
            return heading != null && c <= heading - LOW_GAP
        }
        /**
         * [t] as read, without junk glued onto its outer side; null when nothing comes off. A strong junk shape comes off a
         * glued word, four or more letters with no vowel only with [tierTwo]; a weak one (a letter, a short word with no
         * vowel) never: glued on, it looks like a real word's prefix ("iPhone", "eBook", "McKenzie"), whatever the confidence.
         */
        fun unglued(t: String, front: Boolean): String? {
            val w = read(t)?.text ?: t
            if (count(lines, w) > 1) return null
            return unglue(w, front) { junk, k -> (k == Kind.STRONG || (tierTwo && k == Kind.SUSPECT)) && count(lines, junk) == 0 }
        }
        val kept = words.toMutableList()
        while (kept.size > 1 && junk(kept.first())) kept.removeAt(0)
        while (kept.size > 1 && junk(kept.last())) kept.removeAt(kept.size - 1)
        unglued(kept.first(), front = true)?.let { kept[0] = it }
        unglued(kept.last(), front = false)?.let { kept[kept.size - 1] = it }
        if (kept == words || kept.none { w -> w.count(Char::isLetter) >= 3 && kind(w) == null }) return title
        return kept.joinToString(" ")
    }

    /**
     * A line of tier-one junk only: every word junk-shaped or without letters, one of strong shape and the rest strong,
     * single letters or no letters. A line of tier-two shapes only ("BBQ", "Mgmt") is never judged junk: it may be real.
     */
    fun junkLine(line: OcrLine): Boolean {
        val ws = TOKEN.findAll(line.text).map { it.value }.toList()
        if (ws.isEmpty() || ws.any(::holdsPlainWord)) return false
        val kinds = ws.map { w -> w to kind(w) }
        if (kinds.any { (w, k) -> k == null && w.any(Char::isLetter) }) return false
        return kinds.any { it.second == Kind.STRONG } && kinds.all { (w, k) -> k == Kind.STRONG || k == null || core(w).length <= 1 }
    }
}
