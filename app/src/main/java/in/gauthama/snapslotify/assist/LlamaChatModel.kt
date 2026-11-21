package `in`.gauthama.snapslotify.assist

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Log
import `in`.gauthama.snapslotify.core.chat.ChatJob
import `in`.gauthama.snapslotify.core.chat.ChatModel
import `in`.gauthama.snapslotify.core.chat.ChatPrompts
import `in`.gauthama.snapslotify.llmspike.LlamaNative
import org.json.JSONObject
import java.io.File

/**
 * The chat jobs through llama.cpp, one per process. The model is try harder's language model (the VLM's text GGUF, loaded
 * without its image encoder): one model file serves both. One lock serialises everything on the native context. The model and both prefixes stay in memory until the system asks for memory back or the process dies;
 * in either case the next request warms up again and the code result stands meanwhile.
 */
class LlamaChatModel private constructor(private val file: File) : ChatModel {

    private val lock = Any()
    private var model = 0L
    private var ctx = 0L
    private var prefixesBuilt = false
    private var lastUsedAt = 0L

    /** Milliseconds spent by the last warm-up that did real work: load, then both prefixes. */
    @Volatile var lastLoadMs = 0L
    @Volatile var lastPrefixMs = 0L

    override fun warmUp(): Boolean {
        // Never both models in memory on a 4 GB phone: try harder's model goes first.
        LlamaVisionModel.instance?.releaseNow()
        return warmUpLocked()
    }

    /** Frees the model now, waiting for a running call to finish first. */
    fun releaseNow() = synchronized(lock) { release() }

    /** After a call was stopped at its time limit: free the native model and context once that call returns (it holds the lock). */
    override fun releaseAfterStop() = releaseNow()

    private fun warmUpLocked(): Boolean = synchronized(lock) {
        LlamaNative.clearAbort()
        lastUsedAt = SystemClock.elapsedRealtime()
        if (model == 0L) {
            val t0 = System.nanoTime()
            LlamaNative.backendInit()
            model = LlamaNative.loadModel(file.path)
            if (model == 0L) return false
            ctx = LlamaNative.newContext(model, N_CTX, N_BATCH, THREADS)
            if (ctx == 0L) { release(); return false }
            lastLoadMs = (System.nanoTime() - t0) / 1_000_000
        }
        if (!prefixesBuilt) {
            val t1 = System.nanoTime()
            for (job in ChatJob.entries) {
                val o = JSONObject(LlamaNative.buildPrefix(ctx, model, slot(job), prefix(job)))
                note(o.put("step", "prefix").put("job", job.name))
                if (o.has("error")) {
                    Log.w(TAG, "prefix failed for $job")
                    return false
                }
            }
            lastPrefixMs = (System.nanoTime() - t1) / 1_000_000
            prefixesBuilt = true
        }
        true
    }

    override fun generate(job: ChatJob, prompt: String): String = synchronized(lock) {
        LlamaNative.clearAbort()
        check(prefixesBuilt) { "not warmed up" }
        val o = JSONObject(LlamaNative.generateUser(ctx, model, slot(job), prefix(job), prompt, MAX_TOKENS))
        lastUsedAt = SystemClock.elapsedRealtime()
        note(o.put("step", "generate").put("job", job.name))
        check(!o.has("error")) { o.getString("error") }
        check(o.getString("stop") != "decode_error") { "decode stopped" }
        Log.i(TAG, "generate $job n_prompt=${o.optInt("n_prompt")} n_out=${o.optInt("n_out")} total_ms=${o.optLong("total_ms")}")
        o.getString("text")
    }

    override fun cancel() = LlamaNative.abort()

    private val notes = mutableListOf<JSONObject>()

    private fun note(o: JSONObject) = synchronized(notes) { notes += o.put("at", android.os.SystemClock.elapsedRealtime()) }

    /** Native timings since the last call, for the private run file. */
    fun drainNotes(): List<JSONObject> = synchronized(notes) { notes.toList().also { notes.clear() } }

    /**
     * Frees the memory if the model has been idle a while. Never stops a call: this phone sends memory trims constantly,
     * even to an app that has just started, and a trim must not take chips away from the screen in front of the user.
     */
    private fun releaseIfIdle(level: Int) {
        Thread({
            synchronized(lock) {
                val idleMs = SystemClock.elapsedRealtime() - lastUsedAt
                val free = model != 0L && idleMs >= IDLE_BEFORE_RELEASE_MS
                Log.i(TAG, "trim level=$level idle_ms=$idleMs released=$free")
                if (free) release()
            }
        }, "chat-model-release").apply { isDaemon = true }.start()
    }

    private fun release() {
        if (ctx != 0L) LlamaNative.freeContext(ctx)
        if (model != 0L) LlamaNative.freeModel(model)
        LlamaNative.clearPrefixes()
        ctx = 0L
        model = 0L
        prefixesBuilt = false
    }

    private fun slot(job: ChatJob) = when (job) {
        ChatJob.DATE_LINE -> 0
        ChatJob.TITLE -> 1
        ChatJob.PAGE_TITLE -> 2
    }

    private fun prefix(job: ChatJob) = when (job) {
        ChatJob.DATE_LINE -> ChatPrompts.DATE_PREFIX
        ChatJob.TITLE -> ChatPrompts.TITLE_PREFIX
        ChatJob.PAGE_TITLE -> ChatPrompts.PAGE_TITLE_PREFIX
    }

    companion object {
        private const val TAG = "SnapslotifyLlm"
        /** The VLM's language model, the same file try harder loads (always the tuned one, whatever the debug choice). */
        private val MODEL_FILE = LlamaVisionModel.Variant.TUNED.file
        private const val N_CTX = 2048
        private const val N_BATCH = 512
        private const val THREADS = 8
        private const val MAX_TOKENS = 32
        private const val IDLE_BEFORE_RELEASE_MS = 60_000L

        @Volatile var instance: LlamaChatModel? = null
            private set

        /** The model, or null when its file is not on the phone. */
        fun get(context: Context): LlamaChatModel? {
            instance?.let { return it }
            val app = context.applicationContext
            val file = ModelFiles.find(app, MODEL_FILE) ?: return null
            return synchronized(this) {
                instance ?: LlamaChatModel(file).also { m ->
                    instance = m
                    app.registerComponentCallbacks(object : ComponentCallbacks2 {
                        override fun onTrimMemory(level: Int) {
                            @Suppress("DEPRECATION")
                            if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) m.releaseIfIdle(level)
                        }
                        override fun onConfigurationChanged(newConfig: Configuration) = Unit
                        @Deprecated("Deprecated in Java")
                        override fun onLowMemory() = m.releaseIfIdle(-1)
                    })
                }
            }
        }
    }
}
