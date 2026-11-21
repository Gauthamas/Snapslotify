package `in`.gauthama.snapslotify.core.ocr

/**
 * Numbers about how well an image read, to see which photos fail before deciding what to build for them. No text.
 * [meanConfidence] and [minConfidence] are over the lines that have one, and null when none does.
 */
data class OcrQuality(val blocks: Int, val lines: Int, val meanConfidence: Float?, val minConfidence: Float?) {
    companion object {
        /** A weak read: the lines' mean confidence below this ... */
        const val WEAK_CONFIDENCE = 0.65f

        /** ... or fewer than this many letters and digits read in all. */
        const val MIN_TEXT = 60

        /**
         * The read is weak: little text came out, or what came out the engine was unsure of. Set on the blur set
         * (fixtures/blur_set): the heavy and motion blurred photos that read no date read 0-36 letters and digits or a
         * mean confidence of 0.44-0.58; the blurred photos that still read their dates, 0.67-0.81 and 147+.
         */
        fun weak(ocr: OcrResult): Boolean {
            val text = ocr.lines.sumOf { l -> l.text.count(Char::isLetterOrDigit) }
            val mean = of(ocr).meanConfidence
            return text < MIN_TEXT || (mean != null && mean < WEAK_CONFIDENCE)
        }

        fun of(ocr: OcrResult): OcrQuality {
            val c = ocr.lines.mapNotNull { it.confidence }
            return OcrQuality(ocr.blockCount, ocr.lines.size, if (c.isEmpty()) null else c.sum() / c.size, c.minOrNull())
        }
    }
}
