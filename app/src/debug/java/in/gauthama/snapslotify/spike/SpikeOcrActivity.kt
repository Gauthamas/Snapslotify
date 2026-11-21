package `in`.gauthama.snapslotify.spike

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import `in`.gauthama.snapslotify.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Feasibility spike, step A: runs ML Kit's bundled on-device OCR over every
 * fixture in assets/screenshots (results in files/ocr_results.json, and the same
 * results plus per-line bounding boxes in files/run3_boxes.json), then over
 * every photo in assets/photos (results in files/ocr_photos_<timestamp>.json).
 *
 * Started with `--es set heldout`, it runs only the held-out screenshots in
 * assets/fixtures_heldout/screenshots (results with line boxes in files/heldout_boxes.json)
 * and shows file names and timings but no recognised text.
 */
class SpikeOcrActivity : ComponentActivity() {

    private val worker = Executors.newSingleThreadExecutor()

    /** Only the very first OCR call of the run pays for loading the model. */
    private var firstImage = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.spike_ocr_activity)
        val output = findViewById<TextView>(R.id.spike_ocr_output)
        output.text = "Running OCR…\n"
        val heldOut = intent.getStringExtra(EXTRA_SET) == SET_HELDOUT
        worker.execute { runOcr(heldOut) { line -> runOnUiThread { output.append(line) } } }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun runOcr(heldOut: Boolean, log: (String) -> Unit) {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).use { recognizer ->
            if (heldOut) {
                runHeldOut(recognizer, log)
            } else {
                runScreenshots(recognizer, log)
                runPhotos(recognizer, log)
            }
        }
    }

    /** Held-out screenshots only, with line boxes. The recognised text is saved but not shown. */
    private fun runHeldOut(recognizer: TextRecognizer, log: (String) -> Unit) {
        val names = listImages(HELDOUT_DIR, setOf("png"))
        if (names.isEmpty()) {
            log("No PNGs found in assets/$HELDOUT_DIR (fixtures/fixtures_heldout/screenshots in the repo).\n")
            return
        }
        val boxes = JSONArray()
        for (name in names) {
            val bitmap = assets.open("$HELDOUT_DIR/$name").use(BitmapFactory::decodeStream)
            boxes.put(recognise(recognizer, name, bitmap, log, withLines = true, showText = false))
        }
        save(HELDOUT_FILE, boxes, log)
    }

    private fun runScreenshots(recognizer: TextRecognizer, log: (String) -> Unit) {
        val names = listImages(SCREENSHOT_DIR, setOf("png"))
        if (names.isEmpty()) {
            log("No PNGs found in assets/$SCREENSHOT_DIR (fixtures/screenshots in the repo).\n")
            return
        }
        val results = JSONArray()
        val boxes = JSONArray()
        for (name in names) {
            val bitmap = assets.open("$SCREENSHOT_DIR/$name").use(BitmapFactory::decodeStream)
            val entry = recognise(recognizer, name, bitmap, log, withLines = true)
            boxes.put(entry)
            results.put(JSONObject(entry.toString()).apply { remove("lines") })
        }
        save(RESULTS_FILE, results, log)
        save(BOXES_FILE, boxes, log)
    }

    private fun runPhotos(recognizer: TextRecognizer, log: (String) -> Unit) {
        val names = listImages(PHOTO_DIR, setOf("jpg", "jpeg", "png"))
        if (names.isEmpty()) {
            log("No images found in assets/$PHOTO_DIR (fixtures/photos in the repo).\n")
            return
        }
        val results = JSONArray()
        for (name in names) {
            val path = "$PHOTO_DIR/$name"
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            val orientation = assets.open(path).use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED
                )
            }
            val bitmap = applyOrientation(decodeDownscaled(path, bounds), orientation)

            results.put(
                recognise(recognizer, name, bitmap, log)
                    .put("original_size", size(bounds.outWidth, bounds.outHeight))
                    .put("processed_size", size(bitmap.width, bitmap.height))
                    .put("exif_orientation", orientation)
            )
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        save("ocr_photos_$stamp.json", results, log)
    }

    private fun recognise(
        recognizer: TextRecognizer,
        name: String,
        bitmap: Bitmap,
        log: (String) -> Unit,
        withLines: Boolean = false,
        showText: Boolean = true,
    ): JSONObject {
        val start = SystemClock.elapsedRealtime()
        val result = runCatching { Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))) }
        val ocrMs = SystemClock.elapsedRealtime() - start

        val entry = JSONObject()
            .put("file", name)
            .put("width", bitmap.width)
            .put("height", bitmap.height)
            .put("ocr_ms", ocrMs)
            .put("includes_model_load", firstImage)
        firstImage = false
        result
            .onSuccess { entry.put("text", it.text).put("block_count", it.textBlocks.size) }
            .onFailure { entry.put("error", it.toString()) }
        if (withLines) result.onSuccess { entry.put("lines", lines(it)) }

        log("── $name (${bitmap.width}×${bitmap.height}) ${ocrMs} ms\n")
        log(result.fold({ if (showText) it.text else "(${it.textBlocks.size} blocks, text not shown)" }, { "ERROR: $it" }) + "\n\n")
        return entry
    }

    /** Every recognised line in ML Kit order, with its bounding box in image pixels (null if ML Kit gives none). */
    private fun lines(text: Text): JSONArray {
        val out = JSONArray()
        text.textBlocks.forEachIndexed { blockIndex, block ->
            block.lines.forEachIndexed { lineIndex, line ->
                val box = line.boundingBox?.let {
                    JSONObject().put("left", it.left).put("top", it.top)
                        .put("right", it.right).put("bottom", it.bottom)
                }
                out.put(
                    JSONObject()
                        .put("block", blockIndex)
                        .put("line", lineIndex)
                        .put("text", line.text)
                        .put("boundingBox", box ?: JSONObject.NULL)
                )
            }
        }
        return out
    }

    private fun listImages(dir: String, extensions: Set<String>): List<String> =
        assets.list(dir).orEmpty()
            .filter { it.substringAfterLast('.', "").lowercase() in extensions }
            .sorted()

    /** Decodes with the smallest power-of-two sample size that brings the long side to <= MAX_SIDE. */
    private fun decodeDownscaled(path: String, bounds: BitmapFactory.Options): Bitmap {
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longSide / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return assets.open(path).use { BitmapFactory.decodeStream(it, null, options)!! }
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun size(width: Int, height: Int) = JSONObject().put("width", width).put("height", height)

    private fun save(fileName: String, results: JSONArray, log: (String) -> Unit) {
        val file = File(filesDir, fileName)
        file.writeText(JSONObject().put("results", results).toString(2))
        log("Saved ${results.length()} results to ${file.absolutePath}\n\n")
    }

    private companion object {
        const val SCREENSHOT_DIR = "screenshots"
        const val PHOTO_DIR = "photos"
        const val RESULTS_FILE = "ocr_results.json"
        const val BOXES_FILE = "run3_boxes.json"
        const val HELDOUT_DIR = "fixtures_heldout/screenshots"
        const val HELDOUT_FILE = "heldout_boxes.json"
        const val EXTRA_SET = "set"
        const val SET_HELDOUT = "heldout"

        /** Long-side limit for photos; screenshots (1080×2340) are used as-is. */
        const val MAX_SIDE = 2048
    }
}
