package `in`.gauthama.snapslotify.diag

import android.content.Context

/** Release build: no share log (the real one is in src/debug). Nothing is written. */
object ShareTrace {
    @Suppress("UNUSED_PARAMETER")
    fun begin(context: Context, tag: String?): String = ""

    @Suppress("UNUSED_PARAMETER")
    fun event(share: String, name: String, vararg fields: Pair<String, Any?>) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun end(share: String, outcome: String, vararg fields: Pair<String, Any?>) = Unit
}
