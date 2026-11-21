package `in`.gauthama.snapslotify.core.ocr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * OcrResult <-> JSON in the shape of one entry of benchmarks/step_a_ocr/run3_boxes.json:
 *
 * {"file", "width", "height", "ocr_ms", "includes_model_load", "text", "block_count",
 *  "lines": [{"block", "line", "text", "boundingBox": {"left", "top", "right", "bottom"}}]}
 *
 * Each line also gets "confidence" and "angle" (degrees, clockwise) when the engine gave them, and "words" (each with "text", "boundingBox" and
 * "confidence") when the engine gave them (the notebook ignores unknown keys).
 * "file" and "includes_model_load" are not part of OcrResult; they are written only when passed in.
 */
object OcrJson {

    private val json = Json {
        prettyPrint = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(result: OcrResult, file: String? = null, includesModelLoad: Boolean? = null): String =
        json.encodeToString(Entry.serializer(), Entry.from(result, file, includesModelLoad))

    fun decode(text: String): OcrResult = json.decodeFromString(Entry.serializer(), text).toResult()

    /** A whole dump file: {"results": [entry, ...]}. */
    fun decodeResults(text: String): List<OcrResult> =
        json.decodeFromString(Dump.serializer(), text).results.map { it.toResult() }

    /** A whole dump file as (file name, result) pairs; entries without a file name get "". */
    fun decodeRecords(text: String): List<Pair<String, OcrResult>> =
        json.decodeFromString(Dump.serializer(), text).results.map { (it.file ?: "") to it.toResult() }

    @Serializable
    private data class Dump(val results: List<Entry>)

    @Serializable
    private data class Entry(
        val file: String? = null,
        val width: Int,
        val height: Int,
        @SerialName("ocr_ms") val ocrMs: Long,
        @SerialName("includes_model_load") val includesModelLoad: Boolean? = null,
        val text: String = "",
        @SerialName("block_count") val blockCount: Int = 0,
        val lines: List<Line> = emptyList(),
    ) {
        fun toResult() = OcrResult(width, height, lines.map { it.toOcrLine() }, ocrMs)

        companion object {
            fun from(r: OcrResult, file: String?, includesModelLoad: Boolean?) = Entry(
                file, r.imageWidth, r.imageHeight, r.ocrMs, includesModelLoad, r.text, r.blockCount,
                r.lines.map { l ->
                    Line(l.block, l.line, l.text, Box(l.left, l.top, l.right, l.bottom), l.confidence,
                        l.words.takeIf { it.isNotEmpty() }?.map { w -> Word(w.text, Box(w.left, w.top, w.right, w.bottom), w.confidence) }, l.angle)
                },
            )
        }
    }

    @Serializable
    private data class Line(
        val block: Int,
        val line: Int,
        val text: String,
        val boundingBox: Box,
        val confidence: Float? = null,
        val words: List<Word>? = null,
        val angle: Float? = null,
    ) {
        fun toOcrLine() = OcrLine(text, boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
            confidence, block, line, words.orEmpty().map { OcrWord(it.text, it.boundingBox.left, it.boundingBox.top, it.boundingBox.right, it.boundingBox.bottom, it.confidence) }, angle)
    }

    @Serializable
    private data class Word(val text: String, val boundingBox: Box, val confidence: Float? = null)

    @Serializable
    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)
}
