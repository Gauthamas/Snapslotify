package `in`.gauthama.snapslotify.core.verify

import `in`.gauthama.snapslotify.core.extract.Candidates
import `in`.gauthama.snapslotify.core.extract.RawEvent
import `in`.gauthama.snapslotify.core.ocr.OcrLine

/**
 * How sure the OCR engine was of the digits in an event's date and time, from its word-level confidence.
 *
 * The event's date or time text is found among the candidates (the same text, so the same place on the page), its
 * characters are matched to the engine's words on that line, and the lowest confidence of a word holding one of its
 * digits is the field's confidence. Below [DOUBTFUL_BELOW] the field gets the low_ocr_confidence flag (a date flag asks
 * for a confirm tap). A field whose words cannot be matched (no word boxes, text rewritten by the clean-up) gets no
 * flag: there is nothing to judge it by.
 *
 * Measured on the 224 dev images and the blur set (benchmarks/prep/none): no wrong date or time came from a doubtful
 * digit (the wrong ones were wrong readings at 0.66-0.93; doubtful digits on blurred photos gave no value at all), and
 * every value under 0.6 was right. So this is a safety net with a low cut-off: under 0.4 it flags 4 right times of the
 * dev images and no date.
 */
object DigitConfidence {

    /** A word holding a date or time digit below this engine confidence makes that field doubtful. */
    const val DOUBTFUL_BELOW = 0.4f

    /** The flags for [ev]: low_ocr_confidence with "DATE" or "TIME" as its detail, for each doubtful field. */
    fun flags(ev: RawEvent, lines: List<OcrLine>, c: Candidates, below: Float = DOUBTFUL_BELOW): List<Flag> = buildList {
        dateConfidence(ev, lines, c)?.let { if (it < below) add(Flag(CODE, "DATE")) }
        timeConfidence(ev, lines, c)?.let { if (it < below) add(Flag(CODE, "TIME")) }
    }

    /** The lowest confidence among the words holding the digits of the event's date; null when none can be matched. */
    fun dateConfidence(ev: RawEvent, lines: List<OcrLine>, c: Candidates): Float? {
        val d = ev.date ?: return null
        return c.dates.filter { one(it.raw) == d }.mapNotNull { lowest(lines, c, it.offset, it.offset + it.raw.length) }.minOrNull()
    }

    /** As [dateConfidence], for the event's time. */
    fun timeConfidence(ev: RawEvent, lines: List<OcrLine>, c: Candidates): Float? {
        val t = ev.time ?: return null
        return c.times.filter { one(it.raw).trimEnd('.') == t || one(it.raw) == t }
            .mapNotNull { lowest(lines, c, it.offset, it.offset + it.raw.length) }.minOrNull()
    }

    const val CODE = "low_ocr_confidence"

    private fun one(s: String) = s.replace(Regex("""\s+"""), " ").trim()

    /**
     * Lowest confidence of the words that hold a digit of text[start, end) (the candidates' text, line by line); null
     * when no such word is matched or none has a confidence.
     */
    fun lowest(lines: List<OcrLine>, c: Candidates, start: Int, end: Int): Float? {
        var low: Float? = null
        for (li in c.lineStarts.indices) {
            val r = c.lineRange(li)
            val a = maxOf(start, r.first); val b = minOf(end, r.last + 1)
            if (a >= b || li >= lines.size) continue
            val line = lines[li]
            var cursor = 0
            for (w in line.words) {
                val at = line.text.indexOf(w.text, cursor)
                if (at < 0) continue
                cursor = at + w.text.length
                val ws = r.first + at; val we = ws + w.text.length
                val from = maxOf(a, ws); val to = minOf(b, we)
                if (from >= to || (from until to).none { c.text[it].isDigit() }) continue
                val conf = w.confidence ?: continue
                low = minOf(low ?: conf, conf)
            }
        }
        return low
    }
}
