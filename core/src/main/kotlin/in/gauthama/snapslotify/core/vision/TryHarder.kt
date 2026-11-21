package `in`.gauthama.snapslotify.core.vision

import `in`.gauthama.snapslotify.core.extract.TimeParser
import `in`.gauthama.snapslotify.core.resolve.DateResolver
import `in`.gauthama.snapslotify.core.review.Chip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.max
import kotlin.math.roundToInt

/** The on-device vision-language model for "Try harder". Blocking calls; implementations serialise them. Any failure may throw. */
interface VisionModel {
    /** Loads the model and the image encoder; does nothing if that is already done. False if it could not. */
    fun warmUp(): Boolean

    /** The raw answer for one image. [rgb]: packed RGB bytes, [width] x [height], already shrunk with [VisionPrompt.size]. */
    fun read(rgb: ByteArray, width: Int, height: Int, prompt: String): String

    /** Stops whatever is running as soon as it can. */
    fun cancel()

    /** Frees the native model after a call was stopped; waits for that call to return first. */
    fun releaseAfterStop() {}
}

/** What the phone trial kept (benchmarks/vl_phone/BAR.md, private): 288 px on the long side, the short prompt. */
object VisionPrompt {
    const val LONG_SIDE = 288
    const val PROMPT = "Reply with JSON only: \"title\", \"date_text\", \"time_text\", each copied exactly from the poster, or \"not stated\"."

    /** The size the image is sent at: the long side shrunk to [LONG_SIDE]; a smaller image is not enlarged. */
    fun size(width: Int, height: Int): Pair<Int, Int> {
        val s = minOf(1.0, LONG_SIDE.toDouble() / max(width, height))
        return max(1, (width * s).roundToInt()) to max(1, (height * s).roundToInt())
    }
}

enum class TryHarderOutcome { OK, NOTHING, INVALID, TIMEOUT, FAILED, CANCELLED }

/**
 * What the model read, after code checked it. The chips carry line -1: they come from the image, not from an OCR line.
 * [dateText] / [timeText] are kept even when code could not turn them into a date or time (debug only, never logged).
 */
data class TryHarderResult(
    val outcome: TryHarderOutcome,
    val title: String? = null,
    val dateChip: Chip? = null,
    val timeChip: Chip? = null,
    val dateText: String? = null,
    val timeText: String? = null,
    val warmUpMs: Long = 0,
    val readMs: Long = 0,
    val raw: String? = null,
)

/**
 * "Try harder": one look at the image by the vision model, asked only for title, date text and time text. The model
 * reads; code decides: the date goes through DateResolver and the time through TimeParser, exactly as an OCR line would,
 * and anything they cannot read is dropped. Nothing here throws, and nothing here fills a field.
 */
class TryHarder(
    private val model: VisionModel,
    private val warmUpTimeoutMs: Long = `in`.gauthama.snapslotify.core.chat.ModelTimeouts.VISION_COLD_LOAD_MS,
    private val readTimeoutMs: Long = `in`.gauthama.snapslotify.core.chat.ModelTimeouts.VISION_GENERATION_MS,
    /** A call ran past its limit: "vision_load" or "vision_read", for a local counter. */
    private val onTimeout: (String) -> Unit = {},
) {
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        runCatching { model.cancel() }
    }

    fun run(rgb: ByteArray, width: Int, height: Int, today: LocalDate): TryHarderResult {
        val t0 = System.nanoTime()
        val ready = call(warmUpTimeoutMs, "vision_load") { model.warmUp() }
        val warmUpMs = msSince(t0)
        when {
            ready.cancelled -> return TryHarderResult(TryHarderOutcome.CANCELLED, warmUpMs = warmUpMs)
            ready.timedOut -> return TryHarderResult(TryHarderOutcome.TIMEOUT, warmUpMs = warmUpMs)
            ready.value != true -> return TryHarderResult(TryHarderOutcome.FAILED, warmUpMs = warmUpMs)
        }
        val t1 = System.nanoTime()
        val r = call(readTimeoutMs, "vision_read") { model.read(rgb, width, height, VisionPrompt.PROMPT) }
        val readMs = msSince(t1)
        val raw = r.value
        return when {
            r.cancelled -> TryHarderResult(TryHarderOutcome.CANCELLED, warmUpMs = warmUpMs, readMs = readMs)
            r.timedOut -> TryHarderResult(TryHarderOutcome.TIMEOUT, warmUpMs = warmUpMs, readMs = readMs)
            raw == null -> TryHarderResult(TryHarderOutcome.FAILED, warmUpMs = warmUpMs, readMs = readMs)
            else -> parse(raw, today).copy(warmUpMs = warmUpMs, readMs = readMs, raw = raw)
        }
    }

    private class Called<T>(val value: T?, val timedOut: Boolean, val cancelled: Boolean)

    /**
     * One call with a hard timeout. Past it: the native abort flag stops the model, its answer (if one comes later) is
     * dropped, the model is released once the stopped call returns, and the timeout is counted as [kind].
     */
    private fun <T> call(timeoutMs: Long, kind: String, block: () -> T): Called<T> {
        if (cancelled || Thread.currentThread().isInterrupted) return Called(null, false, true)
        val task = FutureTask(block)
        val worker = Thread(task, "vision-model-call").apply { isDaemon = true }
        worker.start()
        return try {
            val v = task.get(timeoutMs, TimeUnit.MILLISECONDS)
            Called(v, false, cancelled)
        } catch (_: TimeoutException) {
            cancelled = true
            runCatching { model.cancel() }
            task.cancel(true)
            Thread({ runCatching { worker.join(); model.releaseAfterStop() } }, "vision-model-release").apply { isDaemon = true }.start()
            runCatching { onTimeout(kind) }
            Called(null, true, false)
        } catch (_: ExecutionException) {
            Called(null, false, cancelled)
        } catch (_: InterruptedException) {
            cancel()
            Thread.currentThread().interrupt()
            Called(null, false, true)
        }
    }

    companion object {
        private const val MAX_TITLE_WORDS = 10
        private const val MAX_TITLE_CHARS = 80
        private val WS = Regex("""\s+""")
        private val json = Json { isLenient = true }

        private fun msSince(t: Long) = (System.nanoTime() - t) / 1_000_000

        /** The model's answer as chips. The first {...} in the answer is read; "not stated" and the like count as nothing. */
        fun parse(raw: String, today: LocalDate): TryHarderResult {
            val obj = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL).find(raw)?.value
                ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                ?: return TryHarderResult(TryHarderOutcome.INVALID, raw = raw)
            fun field(k: String): String? = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.replace(WS, " ")?.trim()?.trim('"', '\'', '`')?.trim()?.takeUnless(::isNothing)
            val title = field("title")?.takeIf { it.split(' ').size <= MAX_TITLE_WORDS && it.length <= MAX_TITLE_CHARS }
            val dateText = field("date_text")
            val timeText = field("time_text")
            val dateChip = dateText?.let { d ->
                val res = DateResolver.resolve(d, today)
                res.start?.let { Chip(d, it.toString(), res.end?.toString(), NO_LINE) }
            }
            val timeChip = timeText?.let { t ->
                TimeParser.parse(t).firstOrNull()?.let { Chip(t, it.start.toString(), it.end?.toString(), NO_LINE) }
            }
            val outcome = if (title == null && dateChip == null && timeChip == null) TryHarderOutcome.NOTHING else TryHarderOutcome.OK
            return TryHarderResult(outcome, title, dateChip, timeChip, dateText, timeText, raw = raw)
        }

        /** A chip that came from the image, not from an OCR line: nothing to outline. */
        const val NO_LINE = -1

        private fun isNothing(v: String): Boolean {
            val n = v.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
            return n.isEmpty() || n in setOf("not stated", "none", "n a", "null", "unknown", "not given", "not shown")
        }
    }
}
