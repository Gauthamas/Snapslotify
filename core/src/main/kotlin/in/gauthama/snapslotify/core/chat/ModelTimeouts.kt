package `in`.gauthama.snapslotify.core.chat

/**
 * Every time limit on a model call, in one place: 20 s for every text-model step, 30 s for every vision-model step. A call past its
 * limit is stopped (the native abort flag), its answer is dropped, the model is released, and the code result stands.
 *
 * Cold load is loading the model file and building its prompt prefixes (the first call after the app starts, or after
 * the model was released); generation is one answer.
 */
object ModelTimeouts {
    /** Text model (LFM2.5 350M): load and prefixes, cold. */
    const val TEXT_COLD_LOAD_MS = 20_000L
    /** A chat's title (ChatJob.TITLE). */
    const val CHAT_TITLE_MS = 20_000L
    /** A page's title for one event (ChatJob.PAGE_TITLE). */
    const val PAGE_TITLE_MS = 20_000L
    /** A chat's date phrase (ChatJob.DATE_LINE): which candidate says when. */
    const val DATE_LINE_MS = 20_000L

    /** Vision model (Try harder): load, projector and warm-up, cold. */
    const val VISION_COLD_LOAD_MS = 30_000L
    /** Try harder's one read of the image. */
    const val VISION_GENERATION_MS = 30_000L

    fun forJob(job: ChatJob): Long = when (job) {
        ChatJob.TITLE -> CHAT_TITLE_MS
        ChatJob.PAGE_TITLE -> PAGE_TITLE_MS
        ChatJob.DATE_LINE -> DATE_LINE_MS
    }
}
