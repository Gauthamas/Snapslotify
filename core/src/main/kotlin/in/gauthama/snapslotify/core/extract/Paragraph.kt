package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import kotlin.math.abs

/**
 * A sentence split over two lines of one paragraph. Line i continues line i - 1 when that line sits just above it (gap at
 * most a line's height), left edges aligned (within a line's height), in the same type (heights within a quarter), and
 * does not end a sentence (". ! ?"). Date and label matching then read the two lines as one phrase ("... Rs. 435, Due" /
 * "date is 12th April" is "Due date is 12th April").
 */
object Paragraph {

    private val SENTENCE_END = Regex("""[.!?]["')\]]*\s*$""")

    /** True when line [i] continues the sentence of line [i] - 1. */
    fun continues(lines: List<OcrLine>, i: Int): Boolean {
        if (i <= 0 || i >= lines.size) return false
        val a = lines[i - 1]; val b = lines[i]
        val ha = a.bottom - a.top; val hb = b.bottom - b.top; val h = maxOf(ha, hb)
        return a.bottom <= b.top + hb / 2 && b.top - a.bottom <= h && abs(a.left - b.left) <= h && abs(ha - hb) <= h / 4 &&
            a.text.isNotBlank() && !SENTENCE_END.containsMatchIn(a.text)
    }
}
