package `in`.gauthama.snapslotify.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import `in`.gauthama.snapslotify.core.ocr.OcrEngine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.ocr.paddle.PaddleOcr
import `in`.gauthama.snapslotify.core.ocr.paddle.Tensor
import `in`.gauthama.snapslotify.core.ocr.paddle.TensorRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.nio.FloatBuffer

/**
 * Debug builds only: PP-OCRv5 mobile (official ONNX files in assets/paddle/) on ONNX Runtime, CPU, arm64-v8a. The steps
 * around the models are in :core (PaddleOcr), shared with the laptop harness. The models load on first use, inside that
 * call's ocrMs, as ML Kit's bundled model does. One lock covers running and closing, so a close waits for a run in flight.
 */
class PaddleOcrEngine(private val context: Context) : OcrEngine<BitmapOcrImage> {

    override val name = "paddle"
    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var ocr: PaddleOcr? = null
    private var closed = false

    override suspend fun recognize(image: BitmapOcrImage): OcrResult = withContext(Dispatchers.Default) {
        synchronized(lock) {
            check(!closed) { "engine closed" }
            val start = SystemClock.elapsedRealtime()
            val engine = ocr ?: load()
            val b = image.bitmap
            val argb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            val lines = engine.recognize(b.width, b.height, argb)
            OcrResult(b.width, b.height, lines, SystemClock.elapsedRealtime() - start)
        }
    }

    private fun load(): PaddleOcr {
        val e = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(THREADS) }
        fun session(name: String) = context.assets.open("paddle/$name").use { e.createSession(it.readBytes(), options) }
        val d = session("det.onnx")
        val r = session("rec.onnx")
        val chars = JSONArray(context.assets.open("paddle/rec_dict.json").bufferedReader().use { it.readText() }).let { a -> List(a.length()) { a.getString(it) } }
        env = e; det = d; rec = r
        return PaddleOcr(runner(e, d), runner(e, r), chars).also { ocr = it }
    }

    private fun runner(e: OrtEnvironment, s: OrtSession): TensorRunner {
        val input = s.inputNames.first()
        return TensorRunner { data, shape ->
            OnnxTensor.createTensor(e, FloatBuffer.wrap(data), shape).use { t ->
                s.run(mapOf(input to t)).use { out ->
                    val o = out.get(0) as OnnxTensor
                    val f = o.floatBuffer
                    Tensor(FloatArray(f.remaining()).also(f::get), o.info.shape)
                }
            }
        }
    }

    /** Frees both sessions. The shared environment stays, as ONNX Runtime intends; it holds no model. */
    override fun close() = synchronized(lock) {
        closed = true
        runCatching { det?.close() }
        runCatching { rec?.close() }
        det = null; rec = null; ocr = null
    }

    private companion object {
        const val THREADS = 4
    }
}
