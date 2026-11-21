package `in`.gauthama.snapslotify.core.diag

import `in`.gauthama.snapslotify.core.chat.ChatSuggestions
import `in`.gauthama.snapslotify.core.chat.JobOutcome
import `in`.gauthama.snapslotify.core.chat.Route

/** Plain rules behind the debug share log, so they can be tested without a phone. */
object ShareDiag {

    const val OK = "ok"
    const val MODEL_TIMEOUT = "model_timeout"
    const val MODEL_ERROR = "model_error"
    const val NO_MODEL_FILE = "no_model_file"
    const val PROCESS_KILLED = "process_killed"
    const val OTHER = "other"

    /** A timeout outranks an error, because it is the one with a limit to report against. */
    fun outcome(route: Route, hasModel: Boolean, s: ChatSuggestions?): String {
        if (route != Route.CHAT) return OK
        if (!hasModel) return NO_MODEL_FILE
        if (s == null) return OTHER
        val jobs = listOf(s.warmUp, s.date, s.title)
        return when {
            JobOutcome.TIMEOUT in jobs -> MODEL_TIMEOUT
            JobOutcome.FAILED in jobs -> MODEL_ERROR
            else -> OK
        }
    }

    /** The lines from the [max]-th last share onwards; [isStart] marks a line that starts a share. */
    fun lastShares(lines: List<String>, max: Int, isStart: (String) -> Boolean): List<String> {
        val starts = lines.indices.filter { isStart(lines[it]) }
        return if (starts.size <= max) lines else lines.drop(starts[starts.size - max])
    }
}
