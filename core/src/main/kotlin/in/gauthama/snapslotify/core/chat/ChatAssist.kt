package `in`.gauthama.snapslotify.core.chat

import `in`.gauthama.snapslotify.core.extract.AnchoredDay
import `in`.gauthama.snapslotify.core.extract.ChatContext
import `in`.gauthama.snapslotify.core.extract.ShotDate
import `in`.gauthama.snapslotify.core.review.Chip
import java.time.LocalDate
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class ChatJob { DATE_LINE, TITLE, PAGE_TITLE }

/** The on-device model. Blocking calls; implementations serialise them. Any failure may throw. */
interface ChatModel {
    /** Loads the model and builds the fixed prefixes; does nothing if that is already done. False if it could not. */
    fun warmUp(): Boolean

    /** The raw answer to one prompt. */
    fun generate(job: ChatJob, prompt: String): String

    /** Stops whatever is running as soon as it can. */
    fun cancel()

    /** Frees the native model after a call was stopped; waits for that call to return first. The next call loads it again. */
    fun releaseAfterStop() {}
}

/** The exact prompts of benchmarks/chat_cases/PROMPTS.md, byte for byte, as in the training rows. */
object ChatPrompts {
    const val TITLE_PREFIX = "Write a short calendar title (1 to 5 words) for the event planned in this chat, spelt correctly. " +
        "If there are several events, separate the titles with \" ; \". Output only the title.\n\nChat:\n"
    const val DATE_PREFIX = "Which candidate phrase says when the event will happen? Not a statement of today's date, not a deadline, " +
        "not a past day, not a plan that was cancelled or changed. Copy the phrase exactly, or answer NONE.\n\nEvent: "

    fun title(lines: List<String>) = TITLE_PREFIX + lines.joinToString("\n")

    /**
     * Any page (a poster, an email, a ticket, a message): a title that keeps the event's meaning in the model's own words,
     * not words copied from the page. Not a tuned prompt: the chat prompt above is the tuned one, used for chats.
     */
    const val PAGE_TITLE_PREFIX = "Write a short calendar title (1 to 5 words) for the event described below. Say what the event is, " +
        "in your own words, keeping its meaning; do not copy greetings, names of apps, prices or addresses. Spell it correctly. " +
        "Output only the title.\n\nText:\n"

    fun pageTitle(lines: List<String>) = PAGE_TITLE_PREFIX + lines.joinToString("\n")

    /** The date bubble is marked; the message date, if known, comes first. One bubble per line, in screen order. */
    const val DATE_MARK = "[date] "

    fun titleAround(bubbles: List<String>, dateIndex: Int?, messageDate: String?) = TITLE_PREFIX +
        (messageDate?.let { "Message date: $it\n" } ?: "") +
        bubbles.mapIndexed { i, b -> if (i == dateIndex) DATE_MARK + b else b }.joinToString("\n")

    fun date(event: String, lines: List<String>, candidates: List<String>) =
        DATE_PREFIX + event + "\nLines:\n" + lines.joinToString("\n") + "\n\nCandidate phrases:\n" + candidates.joinToString("\n") { "- $it" }
}

enum class JobOutcome { OK, NONE, INVALID, TIMEOUT, FAILED, SKIPPED }

/** What the model offered. Empty means the code result stands alone. Never applied to a field by itself. */
data class ChatSuggestions(
    val titles: List<String> = emptyList(),
    val dateChip: Chip? = null,
    /** The candidate phrase picked, even if it could not be turned into a calendar date. */
    val datePhrase: String? = null,
    val warmUp: JobOutcome = JobOutcome.SKIPPED,
    val warmUpMs: Long = 0,
    val date: JobOutcome = JobOutcome.SKIPPED,
    val dateMs: Long = 0,
    val title: JobOutcome = JobOutcome.SKIPPED,
    val titleMs: Long = 0,
    val rawDate: String? = null,
    val rawTitle: String? = null,
)

/**
 * The model's two jobs for one chat: the date phrase from the lister's candidates, then a title from the lines around
 * that phrase. Code keeps the decisions: an answer outside the candidates is dropped, and ChatDates does the date
 * arithmetic. Nothing here throws: a failure, a timeout or no model gives empty suggestions.
 */
class ChatAssist(
    private val model: ChatModel,
    /** One limit for every call, overriding [ModelTimeouts] (debug runs only); null: the per-call limits. */
    private val callTimeoutMs: Long? = null,
    /** Step names, job and outcome, for the debug share log. Never any text. */
    private val trace: (name: String, job: String?, outcome: String?) -> Unit = { _, _, _ -> },
    /** A call ran past its limit: its kind ("load", or the job's name), for a local counter. */
    private val onTimeout: (String) -> Unit = {},
) {

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        runCatching { model.cancel() }
    }

    /**
     * [context]: the code's chat bubbles. With a date bubble, the title prompt is that bubble (marked), the two bubbles
     * before it and the two after it, and the message date; else the lines around the picked date phrase, as before.
     * The model's answers are only chips: it never sets the date or time, and the code title stays the default.
     */
    fun suggest(
        lines: List<String>, codeTitle: String?, codeDate: String?, today: LocalDate, context: ChatContext? = null, shotDate: LocalDate? = null,
    ): ChatSuggestions {
        val anchor = chipAnchor(context, shotDate ?: today)   // no date of its own: the current date, as in the code
        val t0 = System.nanoTime()
        trace("warmup_start", null, null)
        val ready = call(LOAD) { model.warmUp() }
        val warmUpMs = msSince(t0)
        val warm = when {
            ready.timedOut -> JobOutcome.TIMEOUT
            ready.value == true -> JobOutcome.OK
            else -> JobOutcome.FAILED
        }
        trace("warmup_end", null, warm.name)
        if (warm != JobOutcome.OK) return ChatSuggestions(warmUp = warm, warmUpMs = warmUpMs)

        val listing = DatePhraseLister.list(lines)
        var out = ChatSuggestions(warmUp = warm, warmUpMs = warmUpMs)
        var picked: String? = null
        if (listing.candidates.isNotEmpty()) {
            val t1 = System.nanoTime()
            val selected = listing.selectedLines.map { lines[it] }
            trace("call_start", ChatJob.DATE_LINE.name, null)
            val r = call(ChatJob.DATE_LINE.name) { model.generate(ChatJob.DATE_LINE, ChatPrompts.date(codeTitle?.ifBlank { null } ?: UNKNOWN, selected, listing.candidates)) }
            val answer = r.value?.let(::clean)
            picked = answer?.let { a -> listing.candidates.firstOrNull { norm(it) == norm(a) } }
            val outcome = when {
                r.timedOut -> JobOutcome.TIMEOUT
                answer == null -> JobOutcome.FAILED
                norm(answer) == "none" -> JobOutcome.NONE
                picked == null -> JobOutcome.INVALID
                else -> JobOutcome.OK
            }
            trace("call_end", ChatJob.DATE_LINE.name, outcome.name)
            out = out.copy(date = outcome, dateMs = msSince(t1), rawDate = r.value, datePhrase = picked, dateChip = picked?.let { chip(it, listing, anchor, today) })
        }

        val t2 = System.nanoTime()
        val forTitle = picked ?: codeCandidate(codeDate, listing.candidates) ?: listing.candidates.firstOrNull()
        trace("call_start", ChatJob.TITLE.name, null)
        val prompt = context?.takeIf { it.dateBubble != null }?.let(::titlePrompt) ?: ChatPrompts.title(titleLines(lines, listing, forTitle))
        val r = call(ChatJob.TITLE.name) { model.generate(ChatJob.TITLE, prompt) }
        val titles = r.value?.let(::titlesFrom).orEmpty()
        val outcome = when {
            r.timedOut -> JobOutcome.TIMEOUT
            r.value == null -> JobOutcome.FAILED
            titles.isEmpty() -> if (norm(clean(r.value)) == "none") JobOutcome.NONE else JobOutcome.INVALID
            else -> JobOutcome.OK
        }
        trace("call_end", ChatJob.TITLE.name, outcome.name)
        return out.copy(title = outcome, titleMs = msSince(t2), rawTitle = r.value, titles = titles)
    }

    /**
     * A title for one event of any page, from [lines] (the lines around the event's date, chrome already taken out):
     * the model's titles and how the call went. Only chips: the code title stays in the field. Never throws.
     */
    fun pageTitle(lines: List<String>): Pair<List<String>, JobOutcome> {
        if (lines.isEmpty()) return emptyList<String>() to JobOutcome.SKIPPED
        trace("warmup_start", null, null)
        val ready = call(LOAD) { model.warmUp() }
        val warm = when {
            ready.timedOut -> JobOutcome.TIMEOUT
            ready.value == true -> JobOutcome.OK
            else -> JobOutcome.FAILED
        }
        trace("warmup_end", null, warm.name)
        if (warm != JobOutcome.OK) return emptyList<String>() to warm
        val body = lines.toMutableList()
        while (body.size > 1 && tooLong(body)) body.removeAt(body.size - 1)
        trace("call_start", ChatJob.PAGE_TITLE.name, null)
        val r = call(ChatJob.PAGE_TITLE.name) { model.generate(ChatJob.PAGE_TITLE, ChatPrompts.pageTitle(body)) }
        val titles = r.value?.let(::titlesFrom).orEmpty().take(1)
        val outcome = when {
            r.timedOut -> JobOutcome.TIMEOUT
            r.value == null -> JobOutcome.FAILED
            titles.isEmpty() -> if (norm(clean(r.value)) == "none") JobOutcome.NONE else JobOutcome.INVALID
            else -> JobOutcome.OK
        }
        trace("call_end", ChatJob.PAGE_TITLE.name, outcome.name)
        return titles to outcome
    }

    private class Called<T>(val value: T?, val timedOut: Boolean)

    /**
     * One call of [kind] ([LOAD] or a job's name) with its limit ([ModelTimeouts]). Past the limit: the native abort flag
     * stops the model, the answer (if one comes later) is dropped, no further call is made for this screenshot, the model
     * is released once the stopped call returns, and the timeout is counted.
     */
    private fun <T> call(kind: String, block: () -> T): Called<T> {
        if (cancelled || Thread.currentThread().isInterrupted) return Called(null, false)
        val limit = callTimeoutMs ?: if (kind == LOAD) ModelTimeouts.TEXT_COLD_LOAD_MS else ModelTimeouts.forJob(ChatJob.valueOf(kind))
        val task = FutureTask(block)
        val worker = Thread(task, "chat-model-call").apply { isDaemon = true }
        worker.start()
        return try {
            Called(task.get(limit, TimeUnit.MILLISECONDS), false)
        } catch (_: TimeoutException) {
            cancelled = true
            runCatching { model.cancel() }
            task.cancel(true)
            // free the native model once the stopped call has returned (it holds the model's lock until then)
            Thread({ runCatching { worker.join(); model.releaseAfterStop() } }, "chat-model-release").apply { isDaemon = true }.start()
            runCatching { onTimeout(kind) }
            Called(null, true)
        } catch (_: ExecutionException) {
            Called(null, false)
        } catch (_: InterruptedException) {
            cancel()
            Thread.currentThread().interrupt()
            Called(null, false)
        }
    }

    /**
     * The day the model's date phrase is counted from, as in the code: the chat's message date line; "Today",
     * "Yesterday" or a weekday name, or no line, take the screenshot's own date ([ShotDate.lineDay]). Null when there is none.
     */
    private fun chipAnchor(context: ChatContext?, shotDate: LocalDate?): LocalDate? {
        val printed = context?.messageDate?.trim()
        if (printed != null) {
            AnchoredDay.anchorOf(printed)?.takeIf { it.second }?.let { return it.first }
            if (shotDate == null) return null
            ShotDate.lineDay(printed, shotDate)?.let { return it }
        }
        return shotDate
    }

    /** No anchor: a phrase that needs one ("tomorrow", "this friday") gives no chip; a full date is read against [today]. */
    private fun chip(phrase: String, listing: DatePhraseListing, anchor: LocalDate?, today: LocalDate): Chip? {
        if (anchor == null && ChatDates.needsAnchor(phrase)) return null
        val res = ChatDates.resolve(phrase, anchor ?: today) ?: return null
        val line = listing.phrases.firstOrNull { norm(it.text) == norm(phrase) }?.firstLine ?: return null
        return Chip(phrase, res.start.toString(), res.end?.toString(), line)
    }

    private fun titlesFrom(raw: String): List<String> {
        val c = clean(raw)
        if (c.isEmpty() || norm(c) == "none") return emptyList()
        return c.split(';').map { it.trim() }.filter { it.isNotEmpty() && norm(it) != "none" && it.split(WS).size <= MAX_TITLE_WORDS }.distinct()
    }

    /** The date bubble with two bubbles each side; over the cap, the outermost go first (later side first). */
    private fun titlePrompt(context: ChatContext): String {
        for (side in intArrayOf(22, 12, 11, 1, 0)) {
            val before = side / 10
            val after = side % 10
            val k = context.dateBubble!!
            val from = maxOf(0, k - before)
            val texts = context.bubbles.subList(from, minOf(context.bubbles.size, k + after + 1)).map { it.text }
            if (!tooLong(texts) || side == 0) return ChatPrompts.titleAround(texts, k - from, context.messageDate)
        }
        error("unreachable")
    }

    /** The date's line or lines and one neighbour each side, as in the evaluation. Header chips are skipped. Over the cap, the following neighbour goes first. */
    private fun titleLines(lines: List<String>, listing: DatePhraseListing, phrase: String?): List<String> {
        val header = listing.headerLines.toSet()
        if (phrase == null) {
            val body = lines.filterIndexed { i, _ -> i !in header }.toMutableList()
            while (body.size > 1 && tooLong(body)) body.removeAt(body.size - 1)
            return body
        }
        val hit = listing.phrases.first { norm(it.text) == norm(phrase) }
        val prev = (hit.firstLine - 1 downTo 0).firstOrNull { it !in header }
        val next = (hit.lastLine + 1 until lines.size).firstOrNull { it !in header }
        val base = (hit.firstLine..hit.lastLine).toSet()
        val variants = listOf(base + listOfNotNull(prev, next), base + listOfNotNull(prev), base + listOfNotNull(next), base)
        return variants.map { v -> v.sorted().map { lines[it] } }.firstOrNull { !tooLong(it) } ?: base.sorted().map { lines[it] }
    }

    companion object {
        /** The model's load and warm-up, as a call kind. */
        const val LOAD = "load"
        private const val MAX_TITLE_WORDS = 8
        private const val UNKNOWN = "unknown"
        /** 120 tokens of chat lines, at the four characters per token PROMPTS.md estimates with. */
        private const val TITLE_CAP_CHARS = 480
        private val WS = Regex("""\s+""")

        private fun tooLong(lines: List<String>) = lines.sumOf { it.length + 1 } > TITLE_CAP_CHARS

        private fun msSince(t: Long) = (System.nanoTime() - t) / 1_000_000

        private fun norm(s: String) = s.replace(WS, " ").trim().lowercase()

        /** Surrounding whitespace, quotes or backticks and a leading list dash removed, as in the evaluation. */
        private fun clean(s: String) = s.trim().removePrefix("- ").trim().trim('`', '"', '\'').trim()

        /** Code's own date as a candidate phrase: equal, else the longest candidate inside it or containing it. */
        private fun codeCandidate(codeDate: String?, candidates: List<String>): String? {
            val p = norm(codeDate ?: return null)
            return candidates.firstOrNull { norm(it) == p }
                ?: candidates.filter { norm(it) in p || p in norm(it) }.maxByOrNull { it.length }
        }
    }
}
