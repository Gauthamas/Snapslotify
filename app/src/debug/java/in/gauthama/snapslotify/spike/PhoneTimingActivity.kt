package `in`.gauthama.snapslotify.spike

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import `in`.gauthama.snapslotify.R
import `in`.gauthama.snapslotify.llmspike.LlamaNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Debug only: phone timing of the tuned model against docs/PHONE_BUDGET.md. Measures, does not extract.
 *
 *   adb shell am start -n in.gauthama.snapslotify/.spike.PhoneTimingActivity --es phase warm
 *
 * Reads files/llm/phone_prompts.json and files/llm/<model>. Appends one JSON line per event to files/llm/phone_runs.jsonl,
 * progress in files/llm/phone_status.json. Phases: cold, threads, warm (warm, then thermal, then parity, in one process),
 * stageA (one cold first use; debug flags: --ez warmup true, --ez evict true, --es label name).
 * Shows and logs counts and timings only; generated text goes to the private runs file only.
 */
class PhoneTimingActivity : ComponentActivity() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.5f }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        }
        setContentView(R.layout.spike_ocr_activity)
        val out = findViewById<TextView>(R.id.spike_ocr_output)
        val phase = intent.getStringExtra("phase") ?: "warm"
        if (savedInstanceState == null) worker.execute { run(phase) { s -> runOnUiThread { out.append(s) } } }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private class Row(val id: String, val case: String, val job: String, val prompt: String)

    private fun run(phase: String, show: (String) -> Unit) {
        val dir = File(filesDir, "llm")
        val status = File(dir, "phone_status.json")
        val runs = File(dir, "phone_runs.jsonl")
        var peakPss = 0L
        var sampling = true
        fun state(s: String, done: Int, note: String = "") = status.writeText(
            JSONObject().put("phase", phase).put("state", s).put("done", done).put("note", note).put("peak_pss_kb", peakPss).toString())
        val sampler = thread(isDaemon = true) {
            while (sampling) {
                peakPss = maxOf(peakPss, Debug.getPss())
                SystemClock.sleep(2000)
            }
        }
        try {
            state("loading", 0)
            val cfg = JSONObject(File(dir, "phone_prompts.json").readText())
            val prefixes = cfg.getJSONObject("prefixes")
            val rows = cfg.getJSONArray("rows").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { Row(it.getString("id"), it.getString("case"), it.getString("job"), it.getString("prompt")) }
            }
            val modelFile = File(dir, cfg.getString("model"))
            val maxTokens = cfg.getInt("max_tokens")
            val nCtx = cfg.getInt("ctx")
            val nBatch = cfg.getInt("batch")
            fun append(o: JSONObject) = runs.appendText(o.put("phase_run", phase).put("uptime_ms", SystemClock.elapsedRealtime()).toString() + "\n")

            // Stage A flags: evict the file from the page cache first; warm up (read the file, then build both prefixes).
            val warmup = intent.getBooleanExtra("warmup", false)
            val evict = intent.getBooleanExtra("evict", false)
            val runLabel = intent.getStringExtra("label") ?: phase
            val residentAtStart = LlamaNative.residentPercent(modelFile.path)
            val residentAfterEvict = if (evict) LlamaNative.evictFromCache(modelFile.path) else residentAtStart
            val readMs = if (warmup) LlamaNative.readThrough(modelFile.path) else 0L
            val procStart = Process.getStartElapsedRealtime()
            val mf0 = LlamaNative.majorFaults()
            val bk0 = LlamaNative.blocksRead()

            LlamaNative.backendInit()
            var t = SystemClock.elapsedRealtime()
            val model = LlamaNative.loadModel(modelFile.path)
            val loadMs = SystemClock.elapsedRealtime() - t
            check(model != 0L) { "model load failed" }
            append(JSONObject().put("type", "load").put("load_ms", loadMs).put("bytes", modelFile.length()).put("system_info", LlamaNative.systemInfo())
                .put("label", runLabel).put("warmup", warmup).put("evict", evict).put("resident_start_pct", residentAtStart)
                .put("resident_after_evict_pct", residentAfterEvict).put("resident_after_load_pct", LlamaNative.residentPercent(modelFile.path))
                .put("read_through_ms", readMs).put("majflt_load", LlamaNative.majorFaults() - mf0).put("inblock_load", LlamaNative.blocksRead() - bk0)
                .put("proc_start_ms", procStart).put("pss_kb", Debug.getPss()))
            show("load $loadMs ms\n")
            var done = 0

            fun context(threads: Int): Long {
                val tc = SystemClock.elapsedRealtime()
                val c = LlamaNative.newContext(model, nCtx, nBatch, threads)
                check(c != 0L) { "context failed" }
                append(JSONObject().put("type", "context").put("threads", threads).put("ctx_ms", SystemClock.elapsedRealtime() - tc))
                return c
            }

            fun gen(ctx: Long, r: Row, stage: String, threads: Int, rep: Int, cached: Boolean, runNo: Int = 0) {
                val slot = if (!cached) -1 else if (r.job == "date_line") 0 else 1
                val prefix = prefixes.getString(r.job)
                val o = JSONObject(LlamaNative.generateUser(ctx, model, slot, prefix, r.prompt, maxTokens))
                check(!o.has("error")) { o.getString("error") }
                append(o.put("type", "gen").put("stage", stage).put("id", r.id).put("case", r.case).put("job", r.job)
                    .put("threads", threads).put("rep", rep).put("run", runNo).put("pss_kb", Debug.getPss()))
                done++
                state("running", done)
                Log.i(TAG, "gen $stage ${r.job} rep=$rep run=$runNo n_prompt=${o.optInt("n_prompt")} total_ms=${o.optLong("total_ms")}")
            }

            val threads = cfg.getInt("threads")
            when (phase) {
                "stageA" -> {
                    val c = context(threads)
                    val date = rows.first { it.job == "date_line" }
                    val title = rows.first { it.job == "title" }
                    if (warmup) {
                        for ((slot, job) in listOf(0 to "date_line", 1 to "title")) {
                            val o = JSONObject(LlamaNative.buildPrefix(c, model, slot, prefixes.getString(job)))
                            check(!o.has("error")) { o.getString("error") }
                            append(o.put("type", "warmup_prefix").put("job", job).put("label", runLabel).put("pss_kb", Debug.getPss()))
                        }
                    }
                    append(JSONObject().put("type", "ready").put("label", runLabel).put("since_process_start_ms", SystemClock.elapsedRealtime() - procStart))
                    gen(c, date, "first", threads, 1, cached = true)
                    gen(c, title, "first", threads, 1, cached = true)
                    for (rep in 1..3) {
                        gen(c, date, "warm_after", threads, rep, cached = true)
                        gen(c, title, "warm_after", threads, rep, cached = true)
                    }
                    LlamaNative.freeContext(c)
                }
                "cold" -> {
                    val c = context(threads)
                    gen(c, rows.first { it.job == "date_line" }, "cold", threads, 1, cached = true)
                    gen(c, rows.first { it.job == "title" }, "cold", threads, 1, cached = true)
                    LlamaNative.freeContext(c)
                }
                "threads" -> {
                    val r = rows.first { it.job == "date_line" }
                    for (n in listOf(4, 6, 8)) {
                        LlamaNative.clearPrefixes()
                        val c = context(n)
                        gen(c, r, "threads_build", n, 0, cached = true)
                        for (i in 1..3) gen(c, r, "threads", n, i, cached = true)
                        LlamaNative.freeContext(c)
                    }
                }
                "warm" -> {
                    val c = context(threads)
                    // decode each prefix once, so every measured call below restores it
                    gen(c, rows.first { it.job == "date_line" }, "warm_build", threads, 0, cached = true)
                    gen(c, rows.first { it.job == "title" }, "warm_build", threads, 0, cached = true)
                    for (rep in 1..3) for (r in rows) gen(c, r, "warm", threads, rep, cached = true)
                    for (runNo in 1..5) for (r in rows) gen(c, r, "thermal", threads, 1, cached = true, runNo = runNo)
                    for (r in rows) gen(c, r, "parity_nocache", threads, 1, cached = false)
                    LlamaNative.freeContext(c)
                }
                else -> error("unknown phase $phase")
            }
            LlamaNative.freeModel(model)
            sampling = false
            sampler.join(2500)
            append(JSONObject().put("type", "phase_end").put("peak_pss_kb", peakPss).put("vmhwm_kb", vmHwmKb()))
            state("done", done)
            show("done $done\n")
        } catch (e: Throwable) {
            Log.w(TAG, "phone timing failed: ${e.javaClass.simpleName}: ${e.message}")
            sampling = false
            state("error", 0, "${e.javaClass.simpleName}: ${e.message}")
            show("error: ${e.message}\n")
        }
    }

    private fun vmHwmKb(): Long = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM") }
        ?.filter { it.isDigit() }?.toLongOrNull() ?: -1

    private companion object {
        const val TAG = "SnapslotifyLlm"
    }
}
