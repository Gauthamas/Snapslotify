package `in`.gauthama.snapslotify.core.ocr

/**
 * One recognised text line with its bounding box in image pixels.
 *
 * [block] and [line] are the engine's block index and the line's index inside that block. They are in the
 * saved box dumps (run3_boxes.json) and the geometry cleaner G uses [block] ("own-block timestamps").
 * [confidence] is the engine's line confidence (0..1), or null if the engine gives none.
 * [words] are the line's words (ML Kit elements) with their own boxes, in reading order; empty when the engine or an older
 * dump gives none. A clean-up step that rewrites [text] leaves [words] as the engine read them.
 * [angle] is the engine's rotation of the line in degrees, clockwise (ML Kit: -180..180), or null when the engine or an
 * older dump gives none; [PageTurn] uses it to tell which way a sideways page is turned.
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float? = null,
    val block: Int = 0,
    val line: Int = 0,
    val words: List<OcrWord> = emptyList(),
    val angle: Float? = null,
)

/** One word of a line, with its box in image pixels and the engine's confidence (0..1) or null. */
data class OcrWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val confidence: Float? = null)

/** All lines of one image, in engine order, plus the size of the image the engine saw and the OCR time. */
data class OcrResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val lines: List<OcrLine>,
    val ocrMs: Long,
) {
    /** Lines joined with newlines; equal to the engine's full text for the saved fixtures. */
    val text: String get() = lines.joinToString("\n") { it.text }

    val blockCount: Int get() = lines.map { it.block }.distinct().size
}

/** An image as the pipeline sees it. Platform code adapts its own bitmap type to this. */
interface OcrImage {
    val width: Int
    val height: Int
}

/** Text recognition behind an interface, so tests can feed saved OCR JSON instead of a real engine. */
interface OcrEngine<in I : OcrImage> {
    /** The name written into the metrics log. */
    val name: String get() = "unknown"

    suspend fun recognize(image: I): OcrResult

    /** Frees the engine's model. The engine is not used again afterwards. */
    fun close() = Unit
}
