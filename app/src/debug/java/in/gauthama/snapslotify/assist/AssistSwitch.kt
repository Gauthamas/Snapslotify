package `in`.gauthama.snapslotify.assist

import android.content.Context

/**
 * Debug builds only: the AI on/off switch. Off means no chat model and no try harder, so the review is the code result
 * alone (as in release). Default on. Set on the debug settings screen, or by `--es ai off|on` to DebugSettingsActivity.
 */
object AssistSwitch {
    private const val PREFS = "debug_settings"
    private const val KEY = "ai_enabled"

    fun on(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}
