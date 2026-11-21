package `in`.gauthama.snapslotify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import `in`.gauthama.snapslotify.core.ocr.OcrQuality
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.assist.LlamaChatModel
import `in`.gauthama.snapslotify.core.pipeline.PipelineResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug build only (src/debug). "Save as test case": the original image bytes, the raw OCR JSON and the extractor output
 * go to files/testcases/<timestamp>/ (app-private), and a share-sheet intent for them is returned. Explicit user action.
 * The release build has a no-op DebugActions with AVAILABLE = false (src/release).
 */
object DebugActions {

    const val AVAILABLE = true

    /**
     * Adds the debug-only review buttons. "Show raw OCR": every line of the last image as read, with its confidence, the
     * engine and the OCR time. On screen only: nothing is written to disk, logged or copied, and the text is not selectable.
     */
    fun addReviewButtons(activity: Activity, row: ViewGroup, ocr: () -> OcrResult?, engine: () -> String) {
        if (row.findViewWithTag<View>(RAW_OCR_TAG) != null) return
        row.addView(Button(activity).apply {
            text = activity.getString(R.string.show_raw_ocr)
            tag = RAW_OCR_TAG
            setOnClickListener { ocr()?.let { showRawOcr(activity, it, engine()) } }
        }, 1)
    }

    private fun showRawOcr(activity: Activity, ocr: OcrResult, engine: String) {
        val q = OcrQuality.of(ocr)
        val text = buildString {
            append("engine ").append(engine.ifEmpty { "unknown" }).append("   ocr ").append(ocr.ocrMs).append(" ms\n")
            append("image ").append(ocr.imageWidth).append('x').append(ocr.imageHeight).append("   lines ").append(ocr.lines.size)
            append("   blocks ").append(q.blocks)
            q.meanConfidence?.let { append("   mean conf ").append("%.2f".format(it)) }
            append("\n\n")
            ocr.lines.forEachIndexed { i, l ->
                val conf = l.confidence?.let { "%.2f".format(it) } ?: " -- "
                append("%2d  %s  [%s]  b%d  %s\n".format(i, conf, engine.ifEmpty { "?" }, l.block, l.text))
            }
            if (ocr.lines.isEmpty()) append("(no lines read)\n")
        }
        val pad = (12 * activity.resources.displayMetrics.density).toInt()
        val view = TextView(activity).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(false)
            setPadding(pad, pad, pad, pad)
            tag = RAW_OCR_VIEW_TAG
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.raw_ocr_title)
            .setView(ScrollView(activity).apply { addView(HorizontalScrollView(activity).apply { addView(view) }) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    const val RAW_OCR_TAG = "show_raw_ocr"
    const val RAW_OCR_VIEW_TAG = "raw_ocr_view"

    /**
     * One JSON line per review to files/llm/review_runs.jsonl (app-private), for the phone runs in harness/phone_share.py.
     * Holds text, so it stays in that file and is never logged. Only reviews started with a run label are recorded.
     */
    fun recordReview(context: Context, line: () -> JSONObject) {
        runCatching {
            val dir = File(context.filesDir, "llm").apply { mkdirs() }
            val native = JSONArray(LlamaChatModel.instance?.drainNotes().orEmpty())
            File(dir, "review_runs.jsonl").appendText(line().put("pss_kb", android.os.Debug.getPss()).put("native", native).toString() + "\n")
        }
    }

    fun saveTestCase(context: Context, image: Uri?, ocrJson: String, result: PipelineResult): Intent? {
        val dir = File(File(context.filesDir, "testcases"), SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
        dir.mkdirs()
        val files = mutableListOf<File>()
        if (image != null) {
            val type = context.contentResolver.getType(image).orEmpty()
            val ext = if ("png" in type) "png" else "jpg"
            runCatching {
                context.contentResolver.openInputStream(image)?.use { input -> File(dir, "image.$ext").outputStream().use(input::copyTo) }
                files += File(dir, "image.$ext")
            }
        }
        File(dir, "ocr.json").writeText(ocrJson).also { files += File(dir, "ocr.json") }
        val extracted = JSONObject()
            .put("events", JSONArray(result.events.map { e ->
                JSONObject().put("title", e.event.title).put("date", e.event.date).put("time", e.event.time)
                    .put("location", e.event.location).put("bill", e.event.bill)
                    .put("start", e.resolved.start?.toString()).put("end", e.resolved.end?.toString())
                    .put("flags", JSONArray(e.flags.map { it.toString() }))
            }))
            .put("dropped", JSONArray(result.dropped.map { JSONObject().put("reason", it.dropReason) }))
        File(dir, "extracted.json").writeText(extracted.toString(1)).also { files += File(dir, "extracted.json") }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null,
        )
    }
}
