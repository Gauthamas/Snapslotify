package `in`.gauthama.snapslotify.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import `in`.gauthama.snapslotify.core.ocr.OcrEngine
import `in`.gauthama.snapslotify.core.ocr.OcrImage
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrWord
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An upright (EXIF already applied) bitmap for OCR. */
class BitmapOcrImage(val bitmap: Bitmap) : OcrImage {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
}

/**
 * ML Kit's bundled Latin recognizer (com.google.mlkit:text-recognition, model inside the APK, no download).
 *
 * Boxes are the line bounding boxes. Confidence is ML Kit's line confidence (Text.Line.getConfidence), not
 * an aggregate of element confidences. ocrMs covers only recognizer.process(), from call to result.
 */
class MlKitOcrEngine : OcrEngine<BitmapOcrImage> {

    override val name = "mlkit"
    private val lazyRecognizer = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val recognizer by lazyRecognizer

    /** Only when another engine is chosen. */
    override fun close() {
        if (lazyRecognizer.isInitialized()) recognizer.close()
    }

    /** Lines ML Kit returned without any box in the last call; they are left out of the result. */
    @Volatile
    var linesWithoutBox = 0
        private set

    override suspend fun recognize(image: BitmapOcrImage): OcrResult {
        val input = InputImage.fromBitmap(image.bitmap, 0)
        val start = SystemClock.elapsedRealtime()
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(input)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val ocrMs = SystemClock.elapsedRealtime() - start
        return OcrResult(image.width, image.height, lines(text), ocrMs)
    }

    /** Every line in ML Kit order, with its words. A line without its own box falls back to the union of its element boxes. */
    private fun lines(text: Text): List<OcrLine> {
        val out = mutableListOf<OcrLine>()
        var noBox = 0
        text.textBlocks.forEachIndexed { blockIndex, block ->
            block.lines.forEachIndexed { lineIndex, line ->
                val box = line.boundingBox ?: line.elements.mapNotNull { it.boundingBox }.reduceOrNull { a, b ->
                    Rect(a).apply { union(b) }
                }
                if (box == null) {
                    noBox++
                } else {
                    val confidence = line.confidence.takeIf { it.isFinite() && it in 0f..1f }
                    // every word (element) with its own box, for layouts read by position such as month grids
                    val words = line.elements.mapNotNull { el ->
                        el.boundingBox?.let { b -> OcrWord(el.text, b.left, b.top, b.right, b.bottom, el.confidence.takeIf { it.isFinite() && it in 0f..1f }) }
                    }
                    // the line's rotation (degrees, clockwise): tells which way a sideways photo is turned
                    val angle = line.angle.takeIf { it.isFinite() }
                    out += OcrLine(line.text, box.left, box.top, box.right, box.bottom, confidence, blockIndex, lineIndex, words, angle)
                }
            }
        }
        linesWithoutBox = noBox
        return out
    }
}
