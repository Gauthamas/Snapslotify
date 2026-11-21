package `in`.gauthama.snapslotify.stats

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.localStats by preferencesDataStore(name = "local_stats")

/**
 * Running totals kept on the phone only (DataStore; no network, no analytics): the taps-saved estimate of every event
 * actually added, and how many model calls ran past their limit, by kind.
 */
object LocalStats {

    private val EVENTS = intPreferencesKey("events")
    private val MANUAL = intPreferencesKey("manual_taps")
    private val APP = intPreferencesKey("app_taps")
    private val SAVED = intPreferencesKey("saved_taps")
    private val CHARS = intPreferencesKey("chars_not_typed")
    private val NO_SAVING = intPreferencesKey("events_no_saving")
    private const val TIMEOUT_PREFIX = "timeout_"

    /**
     * [saved] is the true sum (by hand minus with the app, events below 0 included), so the totals add up; the screens
     * show it floored at 0. [noSaving]: events that saved nothing or less (they needed a lot of fixing).
     */
    data class Totals(val events: Int, val manualTaps: Int, val appTaps: Int, val saved: Int, val charsNotTyped: Int, val timeouts: Map<String, Int>,
                      val noSaving: Int = 0) {
        val shownSaved: Int get() = maxOf(0, saved)
        val averageSaved: Double get() = if (events == 0) 0.0 else maxOf(0.0, saved.toDouble() / events)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun add(context: Context, e: TapsSaved.Estimate) {
        context.applicationContext.localStats.edit { p ->
            p[EVENTS] = (p[EVENTS] ?: 0) + e.events
            p[MANUAL] = (p[MANUAL] ?: 0) + e.manualTaps
            p[APP] = (p[APP] ?: 0) + e.appTaps
            p[SAVED] = (p[SAVED] ?: 0) + e.saved
            p[CHARS] = (p[CHARS] ?: 0) + e.charsNotTyped
            // one event at a time from the review screen: an event that saved nothing or less
            if (e.events == 1 && e.saved <= 0) p[NO_SAVING] = (p[NO_SAVING] ?: 0) + 1
        }
    }

    /** One model call past its limit ([kind]: "load", a text job's name, "vision_load", "vision_read"). From any thread. */
    fun countTimeout(context: Context, kind: String) {
        val app = context.applicationContext
        scope.launch { runCatching { app.localStats.edit { p -> val k = intPreferencesKey(TIMEOUT_PREFIX + kind); p[k] = (p[k] ?: 0) + 1 } } }
    }

    suspend fun totals(context: Context): Totals {
        val p: Preferences = context.applicationContext.localStats.data.first()
        val timeouts = p.asMap().filterKeys { it.name.startsWith(TIMEOUT_PREFIX) }
            .map { (k, v) -> k.name.removePrefix(TIMEOUT_PREFIX) to (v as? Int ?: 0) }.toMap()
        return Totals(p[EVENTS] ?: 0, p[MANUAL] ?: 0, p[APP] ?: 0, p[SAVED] ?: 0, p[CHARS] ?: 0, timeouts, p[NO_SAVING] ?: 0)
    }
}
