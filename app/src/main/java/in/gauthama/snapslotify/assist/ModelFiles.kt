package `in`.gauthama.snapslotify.assist

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Where a model file is on the phone. A Play install carries the models in the install-time asset pack `:models`
 * (a split APK, read through the AssetManager). llama.cpp opens a model by path, so each file is copied once, in the
 * background, into the app's storage at the same relative path; until a copy is done the model counts as absent and
 * the app runs on code only. A marker beside each copy holds the app version it came from, so an update that ships new
 * models copies them again.
 *
 * A build without the pack (a local APK) uses whatever is already in the app's storage (put there by the dev harness).
 */
object ModelFiles {
    private const val TAG = "SnapslotifyModels"

    /** Every model file the pack carries, by its path in the pack and in the app's storage. */
    val PACKED = listOf(
        "vl/LFM2.5-VL-450M-mix-Q4_0.gguf",
        "vl/mmproj-LFM2.5-VL-450m-Q8_0.gguf",
    )

    /** Copies an older version made that this one no longer ships: removed once the pack is there. */
    private val STALE = listOf(
        "llm/tuned_LFM2.5-350M.Q4_K_M.gguf",
        "vl/LFM2.5-VL-450M-poster-Q4_0.gguf",
    )

    private val lock = Any()
    @Volatile private var copying = false
    private val inPack = mutableMapOf<String, Boolean>()

    /** The file at [relative] (e.g. `llm/x.gguf`), or null when it is not ready on the phone (a copy may be running). */
    fun find(context: Context, relative: String): File? {
        val app = context.applicationContext
        val file = File(app.filesDir, relative)
        if (!isInPack(app, relative)) return file.takeIf { it.isFile }
        if (file.isFile && isCurrent(app, file)) return file
        prepare(app)
        return null
    }

    /** Starts the one-time copy of the pack's models in the background, if any is missing or out of date. */
    fun prepare(context: Context) {
        val app = context.applicationContext
        removeStale(app)
        val todo = PACKED.filter { isInPack(app, it) && !File(app.filesDir, it).let { f -> f.isFile && isCurrent(app, f) } }
        if (todo.isEmpty()) return
        synchronized(lock) {
            if (copying) return
            copying = true
        }
        Thread({
            try {
                for (relative in todo) copy(app, relative)
            } finally {
                copying = false
            }
        }, "model-copy").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Only on a pack install: a local APK's models were put there by hand and stay. */
    private fun removeStale(app: Context) {
        if (PACKED.none { isInPack(app, it) }) return
        for (relative in STALE) {
            val file = File(app.filesDir, relative)
            for (f in listOf(file, marker(file), File(file.path + ".part"))) {
                if (f.exists() && f.delete()) Log.i(TAG, "removed an old model file")
            }
            file.parentFile?.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        }
    }

    private fun copy(app: Context, relative: String) {
        val file = File(app.filesDir, relative)
        val part = File(file.path + ".part")
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            file.parentFile?.mkdirs()
            app.assets.open(relative).use { input -> part.outputStream().use { input.copyTo(it, 1 shl 20) } }
            if (file.exists()) file.delete()
            if (!part.renameTo(file)) throw IOException("rename failed")
            marker(file).writeText(versionCode(app).toString())
            Log.i(TAG, "copied bytes=${file.length()} ms=${android.os.SystemClock.elapsedRealtime() - started}")
        } catch (e: IOException) {
            // most likely too little free space: the model stays absent and the app keeps to code only
            part.delete()
            Log.w(TAG, "copy failed: ${e.javaClass.simpleName}")
        }
    }

    private fun isInPack(app: Context, relative: String): Boolean = synchronized(inPack) {
        inPack.getOrPut(relative) { runCatching { app.assets.open(relative).close(); true }.getOrDefault(false) }
    }

    private fun isCurrent(app: Context, file: File): Boolean =
        runCatching { marker(file).readText().trim().toLong() == versionCode(app) }.getOrDefault(false)

    private fun marker(file: File) = File(file.path + ".copied")

    private fun versionCode(app: Context): Long =
        app.packageManager.getPackageInfo(app.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
}
