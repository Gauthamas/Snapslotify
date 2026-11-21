package `in`.gauthama.snapslotify.core.review

import `in`.gauthama.snapslotify.core.extract.Dates
import `in`.gauthama.snapslotify.core.extract.TimeParser

/**
 * Titles as the review screen shows them: the code's title capped to a short length, and the check a model's title
 * must pass before it is shown at all.
 */
object TitleRules {
    /** A title longer than this (words or characters) is cut. Also the most words a model title may have. */
    const val MAX_WORDS = 8
    const val MAX_CHARS = 48
    /** A cut leaves at least this many words. */
    private const val MIN_WORDS = 2

    private val WS = Regex("""\s+""")
    /** Where a clause ends: , ; : ! ? | ( ) [ ], "..", a full stop before a space, a dash between spaces. */
    private val BOUNDARY = Regex("""\s*(?:[,;:!?|()\[\]]|\.{2,}|…|\.(?=\s)|\s[-–—]\s)""")
    /** Words a cut title does not end on. */
    private val TRAILING = Regex(
        """(?:\s+(?:of|the|to|a|an|and|or|for|at|on|in|with|by|from|our|my|your|their|his|her|its|is|are|was|were|will|be|that|this|has|have|you|we|i))+$""",
        RegexOption.IGNORE_CASE,
    )
    private val END_PUNCT = Regex("""[\s,;:\-–—|(\[.]+$""")

    /** Words that need not be on the page for a model title to pass: they carry no content of their own. */
    private val FUNCTION_WORDS = setOf(
        "a", "an", "the", "of", "for", "to", "in", "on", "at", "and", "or", "with", "by", "from", "my", "our", "your", "their",
        "his", "her", "its", "s", "is", "are", "be", "this", "that", "&",
    )
    private val TOKEN = Regex("""\p{L}+|\d+""")

    /**
     * [title] at most [MAX_WORDS] words and [MAX_CHARS] characters. A longer one is cut at the last clause boundary that
     * fits (keeping at least [MIN_WORDS] words); with none, after the last word that fits, and trailing small words
     * ("of", "the", "to", ...) go too. A title that fits is returned as it is (spaces tidied). Null stays null.
     */
    fun short(title: String?): String? {
        val t = title?.replace(WS, " ")?.trim()?.ifEmpty { null } ?: return title
        if (fits(t)) return t
        // the longest prefix of whole words that fits
        val words = t.split(' ')
        var n = minOf(words.size, MAX_WORDS)
        while (n > 1 && words.take(n).joinToString(" ").length > MAX_CHARS) n--
        val prefix = words.take(n).joinToString(" ")
        val atClause = BOUNDARY.findAll(t).map { it.range.first }
            .filter { it in 1..prefix.length && wordCount(t.substring(0, it)) >= MIN_WORDS }
            .lastOrNull()
        val cut = if (atClause != null) t.substring(0, atClause) else TRAILING.replace(prefix, "").ifEmpty { prefix }
        return END_PUNCT.replace(cut, "").ifEmpty { prefix }
    }

    /**
     * The model's title keeps every name in the code's title: each word after the first that has a capital letter
     * ("Honolulu", "Static-Fox", "iMBrace"), whole, case ignored. A shorter title that drops the name of the place,
     * person or show is not better than the code's. True when the code had no title.
     */
    fun keepsNames(model: String, code: String?): Boolean {
        if (code == null) return true
        val have = TOKEN.findAll(model.lowercase()).map { it.value }.toSet()
        val words = code.trim().split(WS).drop(1)
        return words.flatMap { w -> TOKEN.findAll(w).map { it.value }.toList() }
            .filter { it.length > 1 && it.any(Char::isUpperCase) && it.lowercase() !in FUNCTION_WORDS }
            .all { it.lowercase() in have }
    }

    fun fits(t: String) = wordCount(t) <= MAX_WORDS && t.length <= MAX_CHARS

    private fun wordCount(s: String) = s.trim().split(WS).count { it.isNotEmpty() }

    /**
     * A model's title may be shown only when it passes: at most [MAX_WORDS] words, every content word (not a small
     * function word, not a single letter) on the page, every number in it on the page, and no date or time in it (a
     * title is not the event's day). Case and punctuation are ignored; words are compared whole.
     */
    fun modelTitleOk(title: String, pageText: String): Boolean {
        val t = title.replace(WS, " ").trim()
        if (t.isEmpty() || wordCount(t) > MAX_WORDS) return false
        if (Dates.findDates(t).isNotEmpty() || TimeParser.parse(t).isNotEmpty()) return false
        val page = TOKEN.findAll(pageText.lowercase()).map { it.value }.toSet()
        val tokens = TOKEN.findAll(t.lowercase()).map { it.value }.toList()
        if (tokens.none { it[0].isLetter() && it.length > 1 && it !in FUNCTION_WORDS }) return false
        return tokens.all { tok ->
            when {
                tok[0].isDigit() -> tok in page
                tok.length == 1 || tok in FUNCTION_WORDS -> true
                else -> tok in page
            }
        }
    }
}
