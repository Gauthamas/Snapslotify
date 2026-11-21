package `in`.gauthama.snapslotify.spike

import android.os.Build
import android.os.Bundle
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

/**
 * Debug only: LLM timing spike (Build 2 step 0). Measures, does not extract.
 *
 *   adb shell am start -n in.gauthama.snapslotify/.spike.LlmSpikeActivity --es plan plan.json
 *
 * Reads files/llm/<plan> and files/llm/prompts.json (10 DEV cases), loads files/llm/<model>, and appends one JSON line
 * per generation to files/llm/runs.jsonl. Progress in files/llm/status.json. Plan keys:
 *   model, label, threads (list), ctx, batch, variants (full|trimmed), modes (free|grammar), runs, max_tokens,
 *   sustained (0 or N back-to-back generations of the first case/variant/mode), cases (optional list of ids).
 * Loads the model twice per process: the first load is "cold" (first after process start), the second "warm".
 * Shows and logs counts and timings only; the generated JSON (DEV text) goes to the private runs file only.
 */
class LlmSpikeActivity : ComponentActivity() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        }
        setContentView(R.layout.spike_ocr_activity)
        val out = findViewById<TextView>(R.id.spike_ocr_output)
        val planName = intent.getStringExtra("plan") ?: "plan.json"
        if (savedInstanceState == null) worker.execute { run(planName) { s -> runOnUiThread { out.append(s) } } }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun run(planName: String, show: (String) -> Unit) {
        val dir = File(filesDir, "llm")
        val status = File(dir, "status.json")
        fun state(s: String, done: Int, total: Int, note: String = "") =
            status.writeText(JSONObject().put("state", s).put("done", done).put("total", total).put("note", note).toString())
        try {
            val plan = JSONObject(File(dir, planName).readText())
            val prompts = JSONArray(File(dir, "prompts.json").readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                .let { all -> plan.optJSONArray("cases")?.let { ids -> val s = (0 until ids.length()).map(ids::getString).toSet(); all.filter { it.getString("id") in s } } ?: all }
            val runsFile = File(dir, "runs.jsonl")
            val modelFile = File(dir, plan.getString("model"))
            val label = plan.optString("label", modelFile.name)
            val threadsList = plan.getJSONArray("threads").let { a -> (0 until a.length()).map(a::getInt) }
            val variants = plan.getJSONArray("variants").let { a -> (0 until a.length()).map(a::getString) }
            val modes = plan.getJSONArray("modes").let { a -> (0 until a.length()).map(a::getString) }
            val runs = plan.optInt("runs", 3)
            val sustained = plan.optInt("sustained", 0)
            val nCtx = plan.optInt("ctx", 2048)
            val nBatch = plan.optInt("batch", 512)
            val maxTokens = plan.optInt("max_tokens", 64)
            val total = if (sustained > 0) sustained * threadsList.size else threadsList.size * prompts.size * variants.size * modes.size * runs
            state("loading", 0, total)

            LlamaNative.backendInit()
            fun append(o: JSONObject) = runsFile.appendText(o.toString() + "\n")
            // cold load (first in this process), free, warm load
            var t = SystemClock.elapsedRealtime()
            var model = LlamaNative.loadModel(modelFile.path)
            val coldMs = SystemClock.elapsedRealtime() - t
            check(model != 0L) { "model load failed" }
            LlamaNative.freeModel(model)
            t = SystemClock.elapsedRealtime()
            model = LlamaNative.loadModel(modelFile.path)
            val warmMs = SystemClock.elapsedRealtime() - t
            append(JSONObject().put("type", "load").put("label", label).put("model", modelFile.name).put("bytes", modelFile.length())
                .put("load_cold_ms", coldMs).put("load_warm_ms", warmMs).put("system_info", LlamaNative.systemInfo()))
            show("$label: load cold $coldMs ms, warm $warmMs ms\n")

            var done = 0
            for (threads in threadsList) {
                t = SystemClock.elapsedRealtime()
                val ctx = LlamaNative.newContext(model, nCtx, nBatch, threads)
                val ctxMs = SystemClock.elapsedRealtime() - t
                check(ctx != 0L) { "context failed" }
                fun gen(p: JSONObject, variant: String, mode: String, run: Int, seq: Int) {
                    val r = JSONObject(LlamaNative.generate(ctx, model, p.getString("system"), p.getString("user_$variant"),
                        if (mode == "grammar") p.getString("grammar") else null, maxTokens))
                    append(r.put("type", "gen").put("label", label).put("model", modelFile.name).put("threads", threads)
                        .put("ctx", nCtx).put("batch", nBatch).put("case", p.getString("id")).put("kind", p.getString("kind"))
                        .put("variant", variant).put("mode", mode).put("run", run).put("seq", seq).put("ctx_ms", ctxMs)
                        .put("uptime_ms", SystemClock.elapsedRealtime()))
                    done++
                    state("running", done, total)
                    Log.i(TAG, "gen $label t=$threads ${p.getString("id")} $variant $mode run=$run n_prompt=${r.optInt("n_prompt")} " +
                        "prefill_ms=${r.optLong("prefill_ms")} n_out=${r.optInt("n_out")} decode_ms=${r.optLong("decode_ms")}")
                }
                if (sustained > 0) {
                    val p = prompts.first()
                    for (i in 1..sustained) gen(p, variants.first(), modes.first(), 1, i)
                } else {
                    for (p in prompts) for (v in variants) for (m in modes) for (r in 1..runs) gen(p, v, m, r, 0)
                }
                LlamaNative.freeContext(ctx)
            }
            LlamaNative.freeModel(model)
            state("done", done, total)
            show("done: $done generations\n")
        } catch (e: Throwable) {
            Log.w(TAG, "llm spike failed: ${e.javaClass.simpleName}: ${e.message}")
            state("error", 0, 0, "${e.javaClass.simpleName}: ${e.message}")
            show("error: ${e.message}\n")
        }
    }

    private companion object {
        const val TAG = "SnapslotifyLlm"
    }
}
