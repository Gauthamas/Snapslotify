package `in`.gauthama.snapslotify.core.ocr

/**
 * OCR times for one process: the first call is cold (it pays for loading the model), later calls are warm.
 * Keep one instance per process so "cold" means the first call after process start.
 */
class OcrTimings {

    var coldMs: Long? = null
        private set

    private val warm = mutableListOf<Long>()

    val warmMs: List<Long> get() = warm.toList()

    /** Records one call and returns true if it was the cold one. */
    @Synchronized
    fun record(ms: Long): Boolean {
        if (coldMs == null) {
            coldMs = ms
            return true
        }
        warm += ms
        return false
    }

    /** Median of the warm calls, or null before the second call. */
    val warmMedianMs: Long?
        get() = median(warmMs)

    companion object {
        fun median(values: List<Long>): Long? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            val mid = s.size / 2
            return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
        }
    }
}
