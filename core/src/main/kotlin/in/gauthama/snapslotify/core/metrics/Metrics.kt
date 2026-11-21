package `in`.gauthama.snapslotify.core.metrics

import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.PipelineResult
import `in`.gauthama.snapslotify.core.review.ReviewSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Metrics log lines (spec section 6): one JSON object per line, one line per processed image and one per event.
 * Counts, timings, flags and booleans only: no image, no OCR text, no titles, no dates or times.
 */
object Metrics {

    fun imageLine(r: PipelineResult, ocr: OcrResult, build: String, mode: String, totalMs: Long, timestamp: String, imageId: String = "",
                  ocrEngine: String = "mlkit"): String =
        buildJsonObject {
            put("type", "image")
            put("image_id", imageId)
            put("timestamp", timestamp)
            put("build", build)
            put("mode", mode)
            put("ocr_engine", ocrEngine)
            put("image_w", ocr.imageWidth)
            put("image_h", ocr.imageHeight)
            put("ocr_ms", ocr.ocrMs)
            put("clean_ms", r.cleanMs)
            put("extract_ms", r.extractMs)
            put("total_ms", totalMs)
            put("n_ocr_lines", ocr.lines.size)
            put("n_events", r.events.size)
            put("n_dropped", r.dropped.size)
        }.toString()

    /**
     * One line per event on the review screen, plus one per event the verifier dropped. Every line carries the OCR engine and
     * its time, so engines can be compared row by row; [ocrMs] is null when it is not known.
     */
    fun eventLines(s: ReviewSession, imageId: String, secondsOnReview: Double, ocrEngine: String = "mlkit", ocrMs: Long? = null): List<String> =
        s.events.mapIndexed { i, e ->
            buildJsonObject {
                put("type", "event")
                put("image_id", imageId)
                put("ocr_engine", ocrEngine)
                put("ocr_ms", ocrMs)
                put("index", i)
                put("no_event_found", s.noEventFound)
                put("user_added", e.userAdded)
                put("prefilled", buildJsonObject {
                    put("title", e.prefilled.title); put("date", e.prefilled.date)
                    put("time", e.prefilled.time); put("location", e.prefilled.location)
                })
                put("flags", JsonArray(e.flags.map { JsonPrimitive(it.code) }))
                put("fields_edited", JsonArray(e.counts.fieldsEdited.sorted().map(::JsonPrimitive)))
                put("edits", e.counts.edits)
                put("chips_tapped", e.counts.chipTaps)
                put("confirms", e.counts.confirms)
                put("deletes", e.counts.deletes)
                put("taps", e.counts.taps)
                put("typed_chars", e.counts.typedChars)
                put("seconds_on_review", secondsOnReview)
                put("deleted", e.deleted)
                put("saved", e.saved)
                put("dropped_by_rule", null as String?)
            }.toString()
        } + s.droppedRules.map { rule ->
            buildJsonObject {
                put("type", "event")
                put("image_id", imageId)
                put("ocr_engine", ocrEngine)
                put("ocr_ms", ocrMs)
                put("dropped_by_rule", rule)
            }.toString()
        }
}
