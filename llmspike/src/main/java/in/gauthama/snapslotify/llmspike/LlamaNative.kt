package `in`.gauthama.snapslotify.llmspike

/**
 * Thin JNI binding to llama.cpp for the timing spike. Handles are native pointers. All timings are measured in C++
 * with a monotonic clock. Greedy sampling (deterministic); an optional GBNF grammar constrains the output.
 */
object LlamaNative {

    init {
        System.loadLibrary("llmspike")
    }

    external fun backendInit()

    /** llama_print_system_info(): CPU features compiled in and detected. */
    external fun systemInfo(): String

    /** Returns a model handle (0 on failure). mmap is on, as in PocketPal's default. */
    external fun loadModel(path: String): Long

    /** Returns a context handle (0 on failure). */
    external fun newContext(model: Long, nCtx: Int, nBatch: Int, nThreads: Int): Long

    /**
     * Applies the model's chat template to [system] + [user], decodes the prompt (prefill), then generates up to
     * [maxTokens] tokens. Returns a JSON object: template, n_prompt, prefill_ms, ttft_ms, n_out, decode_ms, total_ms,
     * stop, text. The KV cache is cleared first, so every call is an independent generation.
     */
    external fun generate(ctx: Long, model: Long, system: String, user: String, grammar: String?, maxTokens: Int): String

    /**
     * Phone timing: one user turn, no system message, as the model was trained. With [slot] >= 0 the tokens shared with
     * [prefixText] are decoded once and kept per slot, and later calls restore them instead of decoding them again.
     * With [slot] < 0 nothing is cached. Returns a JSON object: n_prompt, n_prefix, cache_hit, prefix_ms, restore_ms,
     * prefill_ms, n_tail, ttft_ms, n_out, decode_ms, total_ms, stop, text.
     */
    external fun generateUser(ctx: Long, model: Long, slot: Int, prefixText: String, user: String, maxTokens: Int): String

    /** Stops the running decode as soon as it can; [generateUser] and [buildPrefix] then report a failure. Thread-safe. */
    external fun abort()

    /** Allows decoding again after [abort]. */
    external fun clearAbort()

    /** Drops the prefixes kept by [generateUser]. */
    external fun clearPrefixes()

    /** Cold-start evidence: major page faults so far in this process. */
    external fun majorFaults(): Long

    /** Cold-start evidence: blocks read from storage so far in this process. */
    external fun blocksRead(): Long

    /** Percent of the file's pages in the page cache now (mincore). -1 on failure. */
    external fun residentPercent(path: String): Double

    /** Asks the kernel to drop the file's clean pages from the page cache; returns the resident percent after. */
    external fun evictFromCache(path: String): Double

    /** Warm-up: reads the whole file once so its pages are in the page cache; returns milliseconds. */
    external fun readThrough(path: String): Long

    /** Warm-up: decodes a job's fixed prefix into [slot] with no real prompt. JSON: n_prefix, prefix_ms, state_bytes, majflt, inblock. */
    external fun buildPrefix(ctx: Long, model: Long, slot: Int, prefixText: String): String

    external fun freeContext(ctx: Long)

    external fun freeModel(model: Long)

    /** Try harder: loads a vision-language model (text GGUF + image encoder GGUF). A handle, or 0 on failure. */
    external fun vlLoad(modelPath: String, mmprojPath: String, nCtx: Int, nThreads: Int): Long

    /**
     * One image ([rgb]: packed RGB, [width] x [height], already shrunk) and one prompt, greedy. JSON: n_prompt, eval_ms
     * (image encode and prefill), n_out, decode_ms, total_ms, stop, text; or error. [abort] stops it between steps.
     */
    external fun vlRead(handle: Long, rgb: ByteArray, width: Int, height: Int, prompt: String, maxTokens: Int): String

    external fun vlFree(handle: Long)
}
