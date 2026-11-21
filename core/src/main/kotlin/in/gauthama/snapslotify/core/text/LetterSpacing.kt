package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.ocr.OcrLine

/**
 * Letter-spaced type ("S U M M E R   F A I R", common on invitations and posters) read by OCR as one letter per word:
 * each run of four or more single letters is joined back into a word. Word breaks inside a run come from the gaps
 * between the letters' boxes (a gap clearly wider than the run's usual one), else from two or more spaces in the text;
 * with neither, the run is one word. Digits and longer words around a run stay as they are ("6 D E C E M B E R 2026" is
 * "6 DECEMBER 2026").
 *
 * A calendar's weekday initials ("S M T W T F S") are never joined: a run made only of the letters S, M, T, W and F is
 * left alone.
 */
object LetterSpacing {

    const val MIN_RUN = 4
    /** A gap this many times the run's median gap (and at least [BREAK_HEIGHT] of the line height wider) is a word break. */
    const val BREAK_RATIO = 1.8
    const val BREAK_HEIGHT = 0.25

    private val WEEKDAY_INITIALS = setOf('S', 'M', 'T', 'W', 'F')

    fun join(lines: List<OcrLine>): List<OcrLine> = lines.map { l -> join(l)?.let { l.copy(text = it) } ?: l }

    /** The line's text with its letter-spaced runs joined, or null when it has none. */
    fun join(l: OcrLine): String? {
        val text = l.text
        // tokens with their spaces before them
        val toks = Regex("""(\s*)(\S+)""").findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()
        fun single(t: String) = t.length == 1 && t[0].isLetter()
        val runs = mutableListOf<IntRange>()
        var i = 0
        while (i < toks.size) {
            if (!single(toks[i].second)) { i++; continue }
            var j = i
            while (j + 1 < toks.size && single(toks[j + 1].second)) j++
            if (j - i + 1 >= MIN_RUN && !toks.subList(i, j + 1).all { it.second[0].uppercaseChar() in WEEKDAY_INITIALS }) runs += i..j
            i = j + 1
        }
        if (runs.isEmpty()) return null
        // word boxes, when OCR gave one per token
        val boxes = l.words.takeIf { it.size == toks.size }
        val h = (l.bottom - l.top).coerceAtLeast(1)
        val breaks = mutableSetOf<Int>()   // token indices that start a new word inside a run
        for (r in runs) {
            if (boxes != null) {
                val gaps = (r.first + 1..r.last).associateWith { k -> boxes[k].left - boxes[k - 1].right }
                val med = gaps.values.sorted().let { it[it.size / 2] }.coerceAtLeast(0)
                gaps.forEach { (k, g) -> if (g > BREAK_RATIO * med && g - med > BREAK_HEIGHT * h) breaks += k }
            } else {
                for (k in r.first + 1..r.last) if (toks[k].first.length >= 2) breaks += k
            }
        }
        val sb = StringBuilder()
        for ((k, t) in toks.withIndex()) {
            val inRun = runs.any { k in it && k != it.first }
            when {
                inRun && k !in breaks -> sb.append(t.second)
                sb.isEmpty() -> sb.append(t.second)
                else -> sb.append(' ').append(t.second)
            }
        }
        return sb.toString()
    }
}
