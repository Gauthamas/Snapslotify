package `in`.gauthama.snapslotify.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import `in`.gauthama.snapslotify.core.diag.ShareDiag
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug builds only: one log of every share, in app-private files/debug/share_log.jsonl, kept to the last 200 shares.
 * Written line by line as things happen, so a crash or a kill leaves everything up to that moment.
 *
 * Only ids, timings, outcomes and counts. Never screenshot text. Exception messages are left out on purpose, because a
 * message can quote its input: a crash records class names and stack frames.
 *
 * Export: adb shell am broadcast --include-stopped-packages -n in.gauthama.snapslotify/.diag.ShareLogExportReceiver
 * then adb pull the path it prints. The release build has a no-op ShareTrace (src/release).
 */
object ShareTrace {

    private const val MAX_SHARES = 200
    private const val START = "share_received"
    private val lock = Any()
    private val counter = AtomicLong()
    private var app: Context? = null
    private var shares = 0
    @Volatile private var lastShare = "-"

    private val file get() = File(app!!.filesDir, "debug/share_log.jsonl")

    /** Runs at process start: records how the last process ended, then starts watching this one. */
    fun install(context: Context) {
        app = context.applicationContext
        file.parentFile?.mkdirs()
        runCatching { shares = lines().count { it.contains("\"event\":\"$START\"") } }
        runCatching { recordPreviousExit() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            write(lastShare, "crash", "thread" to thread.name, "stack" to stack(e))
            previous?.uncaughtException(thread, e)
        }
        startWatchdog()
    }

    fun begin(context: Context, tag: String?): String {
        val id = tag ?: "s${System.currentTimeMillis().toString(36)}${counter.incrementAndGet()}"
        lastShare = id
        synchronized(lock) {
            shares++
            if (shares > MAX_SHARES) runCatching {
                val kept = ShareDiag.lastShares(lines(), MAX_SHARES - 1) { it.contains("\"event\":\"$START\"") }
                file.writeText(kept.joinToString("\n", postfix = "\n"))
                shares = MAX_SHARES
            }
        }
        write(id, START, "temp_c" to temperature(), "process_start" to Process.getStartElapsedRealtime())
        return id
    }

    fun event(share: String, name: String, vararg fields: Pair<String, Any?>) = write(share, name, *fields)

    fun end(share: String, outcome: String, vararg fields: Pair<String, Any?>) {
        write(share, "end", "outcome" to outcome, "temp_c" to temperature(), "pss_kb" to Debug.getPss(), *fields)
    }

    private fun write(share: String, name: String, vararg fields: Pair<String, Any?>) {
        if (share.isEmpty() || app == null) return
        runCatching {
            val o = JSONObject().put("share", share).put("event", name).put("at", SystemClock.elapsedRealtime())
                .put("up", SystemClock.uptimeMillis()).put("wall", System.currentTimeMillis()).put("pid", Process.myPid())
                .put("screen", if (app!!.getSystemService(PowerManager::class.java).isInteractive) "on" else "off")
            for ((k, v) in fields) if (v != null) o.put(k, v)
            synchronized(lock) { file.appendText(o.toString() + "\n") }
        }
    }

    private fun lines(): List<String> = if (file.exists()) file.readLines() else emptyList()

    private fun temperature(): Double? = app?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }?.let { it / 10.0 }

    private fun stack(e: Throwable): String = buildString {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 5) {
            append(t.javaClass.name).append('\n')
            t.stackTrace.take(40).forEach { append("  at ").append(it).append('\n') }
            t = t.cause
            depth++
        }
    }

    /** Shares the last process left open end as killed, with the reason the system gives (Android 11 and up). */
    private fun recordPreviousExit() {
        val exit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) newExits() else emptyList()
        val rows = lines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        val ended = rows.filter { it.optString("event") == "end" }.map { it.optString("share") }.toSet()
        val open = rows.filter { it.optString("event") == START && it.optString("share") !in ended }.map { it.optString("share") }.distinct()
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) exit.firstOrNull()?.let(::reasonName) else null
        for (s in open) write(s, "end", "outcome" to ShareDiag.PROCESS_KILLED, "exit_reason" to reason, "temp_c" to temperature())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val share = open.lastOrNull() ?: rows.lastOrNull()?.optString("share") ?: "-"
            for (x in exit.reversed()) {
                write(share, "process_exit", "reason" to reasonName(x), "exit_at" to x.timestamp, "importance" to x.importance, "exit_pss_kb" to x.pss, "exit_rss_kb" to x.rss)
                if (x.reason == ApplicationExitInfo.REASON_ANR) write(share, "anr", "stack" to anrStack(x))
            }
        }
    }

    @Suppress("NewApi")
    private fun newExits(): List<ApplicationExitInfo> {
        val mark = File(app!!.filesDir, "debug/last_exit_seen")
        val seen = runCatching { mark.readText().trim().toLong() }.getOrNull()
        val all = app!!.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(app!!.packageName, 0, 10)
        all.firstOrNull()?.let { mark.writeText(it.timestamp.toString()) }
        // The first run of a fresh log has no mark: older exits are not this log's business.
        return if (seen == null) emptyList() else all.filter { it.timestamp > seen }
    }

    @Suppress("NewApi")
    private fun reasonName(x: ApplicationExitInfo) = when (x.reason) {
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
        else -> "code_${x.reason}"
    }

    /** The main thread's block of the system's ANR dump. */
    @Suppress("NewApi")
    private fun anrStack(x: ApplicationExitInfo): String = runCatching {
        val text = x.traceInputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val at = text.indexOf("\"main\"")
        if (at < 0) "no main thread in the dump" else text.substring(at).substringBefore("\n\n").take(8000)
    }.getOrDefault("dump not readable")

    /**
     * Records the main thread's stack when it stops responding for five seconds, the ANR limit: the system only reports
     * an ANR through its exit reasons if the process dies of it. A stretch when this thread was stalled too (a suspended
     * process, a sleeping phone) says nothing about the main thread, so it is ignored.
     */
    private fun startWatchdog() {
        val main = Handler(Looper.getMainLooper())
        val answered = AtomicLong(SystemClock.uptimeMillis())
        Thread({
            var reported = false
            var last = SystemClock.uptimeMillis()
            while (true) {
                main.post { answered.set(SystemClock.uptimeMillis()) }
                SystemClock.sleep(1000)
                val now = SystemClock.uptimeMillis()
                if (now - last > 3000) answered.set(now)
                last = now
                val stuck = now - answered.get()
                if (stuck > 5000 && !reported) {
                    reported = true
                    val frames = Looper.getMainLooper().thread.stackTrace.take(40).joinToString("\n") { "  at $it" }
                    write(lastShare, "anr_suspect", "stuck_ms" to stuck, "stack" to frames)
                } else if (stuck < 2000) reported = false
            }
        }, "share-log-watchdog").apply { isDaemon = true }.start()
    }

    /** Copies the log where adb can pull it without run-as. Returns the path. */
    fun export(context: Context): String {
        val out = File(context.getExternalFilesDir(null), "share_log.jsonl")
        synchronized(lock) { if (file.exists()) file.copyTo(out, overwrite = true) else out.writeText("") }
        return out.path
    }
}

/** Starts the log with the process, before any activity. */
class ShareLogInit : ContentProvider() {
    override fun onCreate(): Boolean {
        ShareTrace.install(context!!)
        `in`.gauthama.snapslotify.ocr.OcrEngines.init(context!!)
        return true
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/**
 * adb shell am broadcast --include-stopped-packages -n in.gauthama.snapslotify/.diag.ShareLogExportReceiver
 * With --el block_main_ms 7000 it blocks the main thread first, to prove the watchdog records it.
 */
class ShareLogExportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val block = intent.getLongExtra("block_main_ms", 0).coerceIn(0, 9000)
        if (block > 0) SystemClock.sleep(block)
        setResult(0, ShareTrace.export(context), null)
    }
}
