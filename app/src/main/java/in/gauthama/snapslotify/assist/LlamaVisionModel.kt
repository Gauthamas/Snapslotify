package `in`.gauthama.snapslotify.assist

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Log
import `in`.gauthama.snapslotify.core.vision.VisionModel
import `in`.gauthama.snapslotify.llmspike.LlamaNative
import org.json.JSONObject
import java.io.File

/**
 * Try harder's model: LFM2.5-VL 450M (Q4_0 text model, Q8_0 image encoder) through llama.cpp, one per process. Two text
 * models share the one image encoder, chosen on the debug settings screen: the tuned LoRA merge (default; trained on
 * posters and on the chat jobs, and also the chat model's file) or the base model. The tuned one changed only language
 * layers, so the base mmproj stays valid.
 * Before it loads, the chat model is freed, and the chat model frees this one before it loads: on a 4 GB phone the two
 * are never in memory together. Freed when idle and the system asks for memory back.
 */
class LlamaVisionModel private constructor(private val model: File, private val mmproj: File, val variant: Variant) : VisionModel {

    enum class Variant(val key: String, val file: String) {
        TUNED("tuned", "vl/LFM2.5-VL-450M-mix-Q4_0.gguf"),
        BASE("base", "vl/LFM2.5-VL-450M-Q4_0.gguf"),
    }

    private val lock = Any()
    private var handle = 0L
    private var lastUsedAt = 0L

    @Volatile var lastLoadMs = 0L

    override fun warmUp(): Boolean {
        LlamaChatModel.instance?.releaseNow()
        return synchronized(lock) {
            LlamaNative.clearAbort()
            lastUsedAt = SystemClock.elapsedRealtime()
            if (handle == 0L) {
                val t0 = System.nanoTime()
                LlamaNative.backendInit()
                handle = LlamaNative.vlLoad(model.path, mmproj.path, N_CTX, THREADS)
                lastLoadMs = (System.nanoTime() - t0) / 1_000_000
                Log.i(TAG, "vision load model=${variant.key} ok=${handle != 0L} ms=$lastLoadMs")
            }
            handle != 0L
        }
    }

    override fun read(rgb: ByteArray, width: Int, height: Int, prompt: String): String = synchronized(lock) {
        LlamaNative.clearAbort()
        check(handle != 0L) { "not warmed up" }
        val o = JSONObject(LlamaNative.vlRead(handle, rgb, width, height, prompt, MAX_TOKENS))
        lastUsedAt = SystemClock.elapsedRealtime()
        check(!o.has("error")) { o.getString("error") }
        check(o.getString("stop") != "aborted" && o.getString("stop") != "decode_error") { "stopped: ${o.getString("stop")}" }
        // Counts and timings only; never the text.
        Log.i(TAG, "vision read model=${variant.key} ${width}x$height n_prompt=${o.optInt("n_prompt")} eval_ms=${o.optLong("eval_ms")} " +
            "n_out=${o.optInt("n_out")} decode_ms=${o.optLong("decode_ms")} total_ms=${o.optLong("total_ms")} stop=${o.getString("stop")}")
        o.getString("text")
    }

    override fun cancel() = LlamaNative.abort()

    /** Frees the model now, waiting for a running call to finish first. */
    fun releaseNow() = synchronized(lock) { release() }

    /** After a call was stopped at its time limit: free the native model and context once that call returns (it holds the lock). */
    override fun releaseAfterStop() = releaseNow()

    private fun releaseIfIdle(level: Int) {
        Thread({
            synchronized(lock) {
                val idleMs = SystemClock.elapsedRealtime() - lastUsedAt
                val free = handle != 0L && idleMs >= IDLE_BEFORE_RELEASE_MS
                Log.i(TAG, "vision trim level=$level idle_ms=$idleMs released=$free")
                if (free) release()
            }
        }, "vision-model-release").apply { isDaemon = true }.start()
    }

    private fun release() {
        if (handle != 0L) LlamaNative.vlFree(handle)
        handle = 0L
    }

    companion object {
        private const val TAG = "SnapslotifyLlm"
        const val MMPROJ_FILE = "vl/mmproj-LFM2.5-VL-450m-Q8_0.gguf"
        private const val N_CTX = 1024
        private const val THREADS = 4
        private const val MAX_TOKENS = 200
        private const val IDLE_BEFORE_RELEASE_MS = 60_000L
        private const val PREFS = "debug_settings"
        private const val KEY = "vl_model"

        /**
         * Device RAM gate: below 3 GiB of total RAM, or on a low-RAM device, try harder is not offered and the code
         * result stands alone. The model peaks near 450 MB on the 4 GB OPPO A17.
         */
        const val MIN_TOTAL_RAM = 3L * 1024 * 1024 * 1024

        @Volatile var instance: LlamaVisionModel? = null
            private set
        private var callbacks = false

        fun selected(context: Context): Variant {
            val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return Variant.entries.firstOrNull { it.key == key } ?: Variant.TUNED
        }

        fun select(context: Context, v: Variant) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v.key).apply()
        }

        fun ramOk(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            return !am.isLowRamDevice && mi.totalMem >= MIN_TOTAL_RAM
        }

        /** The chosen model, or null (code result only) when the RAM gate fails or its files are not on the phone. */
        fun get(context: Context): LlamaVisionModel? {
            val app = context.applicationContext
            val variant = selected(app)
            instance?.let { if (it.variant == variant) return it }
            if (!ramOk(app)) return null
            val model = ModelFiles.find(app, variant.file) ?: return null
            val mmproj = ModelFiles.find(app, MMPROJ_FILE) ?: return null
            return synchronized(this) {
                val current = instance
                if (current != null && current.variant == variant) current else {
                    current?.releaseNow()   // the other variant: freed before the chosen one can load
                    LlamaVisionModel(model, mmproj, variant).also { instance = it }
                }.also { registerCallbacks(app) }
            }
        }

        private fun registerCallbacks(app: Context) {
            if (callbacks) return
            callbacks = true
            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    @Suppress("DEPRECATION")
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                        instance?.releaseIfIdle(level)
                    }
                }
                override fun onConfigurationChanged(newConfig: Configuration) = Unit
                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {
                    instance?.releaseIfIdle(-1)
                }
            })
        }
    }
}
