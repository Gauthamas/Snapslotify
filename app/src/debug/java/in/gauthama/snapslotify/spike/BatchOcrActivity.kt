package `in`.gauthama.snapslotify.spike

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import `in`.gauthama.snapslotify.R
import `in`.gauthama.snapslotify.core.ocr.OcrJson
import `in`.gauthama.snapslotify.image.ImageLoader
import `in`.gauthama.snapslotify.ocr.UprightRead
import `in`.gauthama.snapslotify.image.sharpness
import `in`.gauthama.snapslotify.image.ImagePrep
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.ocr.PageTurn
import android.graphics.Bitmap
import `in`.gauthama.snapslotify.core.ocr.OcrEngineKind
import `in`.gauthama.snapslotify.ocr.AppOcr
import `in`.gauthama.snapslotify.ocr.OcrEngines
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Debug only: OCR every image in a folder, one at a time, in the foreground.
 *
 *   adb shell am start -n in.gauthama.snapslotify/.spike.BatchOcrActivity --es dir snap_test
 *
 * The images are read from the app's PRIVATE files dir (files/<dir>/, default files/snap_test/), so no storage
 * permission is needed. Get them there with `adb push ... /data/local/tmp/snap_test/` and
 * `adb shell run-as in.gauthama.snapslotify cp -r /data/local/tmp/snap_test files/` (debuggable builds only).
 *
 * Per image: the OcrResult JSON (run3_boxes.json shape, lines with boxes and confidence) plus decode_ms, n_lines,
 * cold, original size. Results: files/batch_ocr.json (rewritten after every image). Progress: files/batch_status.json.
 * Images go through the app's ImageLoader and the app's ML Kit engine, so cold = first OCR call of the process.
 * Shows and logs counts and timings only, never OCR text.
 */
class BatchOcrActivity : ComponentActivity() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        }
        setContentView(R.layout.spike_ocr_activity)
        val output = findViewById<TextView>(R.id.spike_ocr_output)
        // A folder name inside the private files dir; anything with a path separator is refused
        val name = intent.getStringExtra(EXTRA_DIR)?.takeIf { it.isNotBlank() && '/' !in it && it != ".." } ?: DEFAULT_DIR
        val dir = File(filesDir, name)
        // Engine comparison: --es engine paddle|mlkit chooses the engine (it stays chosen), --ei passes 2 reads every image twice
        intent.getStringExtra(EXTRA_ENGINE)?.let { OcrEngines.select(OcrEngineKind.of(it)) }
        val passes = intent.getIntExtra(EXTRA_PASSES, 1).coerceIn(1, 3)
        // A/B of pre-processing: --es pre none|contrast|sharpen|straighten|enlarge
        val pre = intent.getStringExtra(EXTRA_PRE) ?: "none"
        if (savedInstanceState == null) {
            worker.execute { run(dir, passes, pre) { line -> runOnUiThread { output.append(line) } } }
        }
    }

    /**
     * The app's read after one pre-processing step. contrast and sharpen change every image before OCR; straighten
     * (median line angle 1-15 degrees) and enlarge (median text height under [SMALL_TEXT] px) read once to measure, then
     * once more only when the step applies. Returns the read used and whether the step changed anything.
     */
    private fun prepRead(engine: `in`.gauthama.snapslotify.core.ocr.OcrEngine<`in`.gauthama.snapslotify.ocr.BitmapOcrImage>, bitmap: Bitmap, pre: String): Pair<PageTurn.Read, Boolean> {
        fun once(b: Bitmap) = b.let { kotlinx.coroutines.runBlocking { UprightRead.read(engine, it) } }
        return when (pre) {
            // the first read only, never read again upright: the "before" of a turned photo
            "noturn" -> PageTurn.Read(kotlinx.coroutines.runBlocking { engine.recognize(`in`.gauthama.snapslotify.ocr.BitmapOcrImage(bitmap)) }) to false
            "contrast", "sharpen" -> {
                val b = if (pre == "contrast") ImagePrep.contrast(bitmap) else ImagePrep.sharpen(bitmap)
                try { once(b) to true } finally { b.recycle() }
            }
            "straighten", "enlarge" -> {
                val first = once(bitmap)
                val deciding = first.ocr.lines.filter { l -> l.text.count(Char::isLetterOrDigit) >= 3 }
                if (deciding.size < 3) return first to false
                if (pre == "straighten") {
                    // the engine gives short lines an angle of 0: the tilt of the long lines only
                    val long = first.ocr.lines.filter { l -> l.text.count(Char::isLetterOrDigit) >= 8 }.mapNotNull { it.angle }.sorted()
                    val a = long.takeIf { it.size >= 3 }?.get(long.size / 2)
                    if (a == null || kotlin.math.abs(a) < 1f || kotlin.math.abs(a) > 15f) return first to false
                    val b = ImagePrep.rotate(bitmap, -a)
                    try { once(b) to true } finally { b.recycle() }
                } else {
                    val hgt = deciding.map { minOf(it.bottom - it.top, it.right - it.left) }.sorted()[deciding.size / 2]
                    if (hgt >= SMALL_TEXT) return first to false
                    val factor = minOf(2.0, SMALL_TEXT.toDouble() / hgt.coerceAtLeast(1), 4096.0 / maxOf(bitmap.width, bitmap.height))
                    if (factor <= 1.05) return first to false
                    val b = ImagePrep.enlarge(bitmap, factor)
                    try { once(b) to true } finally { b.recycle() }
                }
            }
            else -> once(bitmap) to false
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun run(dir: File, passes: Int, pre: String, show: (String) -> Unit) {
        val status = File(filesDir, STATUS_FILE)
        val resultsFile = File(filesDir, RESULTS_FILE)
        resultsFile.delete()
        val images = dir.listFiles { f -> f.extension.lowercase() in setOf("png", "jpg", "jpeg") }?.sortedBy { it.name }
        if (images.isNullOrEmpty()) {
            writeStatus(status, "error", 0, 0, "no readable images in files/${dir.name}")
            show("No readable images in ${dir.path}\n")
            return
        }
        val loader = ImageLoader(contentResolver)
        val results = JSONArray()
        val started = SystemClock.elapsedRealtime()
        val total = images.size * passes
        writeStatus(status, "running", 0, total)
        var done = 0
        for (pass in 1..passes) for ((n, f) in images.withIndex()) {
            val entry = try {
                val image = loader.load(Uri.fromFile(f))
                val engine = AppOcr.engine
                // the app's own read: a page photographed turned is read once more from the image turned upright
                val sharp = image.bitmap.sharpness()
                val t0 = SystemClock.elapsedRealtime()
                val (read, applied) = prepRead(engine, image.bitmap, pre)
                val preMs = SystemClock.elapsedRealtime() - t0
                val ocr = read.ocr
                image.bitmap.recycle()
                val cold = AppOcr.timings.record(ocr.ocrMs)
                Log.i(TAG, "batch ${engine.name} pass $pass ${n + 1}/${images.size} lines=${ocr.lines.size} decode_ms=${image.decodeMs} ocr_ms=${ocr.ocrMs} cold=$cold")
                JSONObject(OcrJson.encode(ocr, file = f.name, includesModelLoad = cold))
                    .put("engine", engine.name)
                    .put("pass", pass)
                    .put("decode_ms", image.decodeMs)
                    .put("n_lines", ocr.lines.size)
                    .put("cold", cold)
                    .put("pss_kb", android.os.Debug.getPss())
                    .put("original_width", image.originalWidth)
                    .put("original_height", image.originalHeight)
                    .put("turn", read.turn)
                    .put("sharpness", sharp)
                    .put("pre", pre)
                    .put("pre_applied", applied)
                    .put("read_ms", preMs)
                    .put("turn_unread", read.turnUnread)
                    .put("reread_ms", read.rereadMs)
                    .put("first_lines", read.firstLines)
                    .put("reread_lines", read.rereadLines ?: -1)
                    .put("reread_turn", read.rereadTurn ?: -2)
                    .apply { read.rejected?.let { put("rejected_reread", JSONObject(OcrJson.encode(it))) } }
            } catch (e: Throwable) {
                Log.w(TAG, "batch ${n + 1}/${images.size} failed: ${e.javaClass.simpleName}")
                JSONObject().put("file", f.name).put("pass", pass).put("error", e.javaClass.simpleName)
            }
            results.put(entry)
            resultsFile.writeText(JSONObject().put("results", results).toString(1))
            writeStatus(status, "running", ++done, total)
            show("$done/$total ${entry.optLong("ocr_ms", -1)} ms\n")
        }
        writeStatus(status, "done", total, total, "total_ms=${SystemClock.elapsedRealtime() - started}")
        show("Done: ${images.size} images\n")
    }

    private fun writeStatus(file: File, state: String, done: Int, total: Int, note: String = "") {
        file.writeText(JSONObject().put("state", state).put("done", done).put("total", total).put("note", note).toString())
    }

    private companion object {
        const val EXTRA_PRE = "pre"
        /** Median text height (px) under which enlarge applies. */
        const val SMALL_TEXT = 24

        const val TAG = "SnapslotifyBatch"
        const val EXTRA_DIR = "dir"
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_PASSES = "passes"
        const val DEFAULT_DIR = "snap_test"
        const val RESULTS_FILE = "batch_ocr.json"
        const val STATUS_FILE = "batch_status.json"
    }
}
