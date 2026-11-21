package `in`.gauthama.snapslotify.report

/**
 * A problem report the user sends by email: what went wrong, an optional note, the app and device, and what the app showed
 * for the page. Plain text only; the app never sends it itself (see [ReportIntents]). The text read from the page is in
 * the body only when the user turned that on.
 */
object ProblemReport {

    /** The one address reports go to. */
    const val ADDRESS = "bodhiarcade@gmail.com"

    enum class Reason(val label: String) {
        /** The date was wrong and the app showed it with no flag (no warning to check it). */
        WRONG_DATE_NOT_FLAGGED("Wrong date and not flagged"),
        /** The time was wrong and the app showed it with no flag. */
        WRONG_TIME_NOT_FLAGGED("Wrong time and not flagged"),
        WRONG_TITLE("Wrong title"),
        NO_EVENT("Event shown but there was none"),
        MISSED_EVENT("Event missed"),
        OTHER("Other"),
    }

    /** One event as the app showed it. */
    data class Shown(val title: String?, val date: String?, val time: String?)

    /** The page the report is about; null when the report is not about a page (sent from the home screen). */
    data class Page(val type: String?, val flags: List<String>, val shown: List<Shown>, val readText: String?)

    data class Device(val appVersion: String, val androidVersion: String, val model: String)

    fun subject(appVersion: String) = "Snapslotify problem report ($appVersion)"

    fun body(reason: Reason, note: String?, device: Device, page: Page?, includeText: Boolean): String = buildString {
        appendLine("What went wrong: ${reason.label}")
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("Note: $it") }
        appendLine()
        appendLine("App version: ${device.appVersion}")
        appendLine("Android version: ${device.androidVersion}")
        appendLine("Device: ${device.model}")
        if (page == null) {
            appendLine("Page: none (reported from the home screen)")
        } else {
            appendLine("Page type: ${page.type ?: "unknown"}")
            appendLine("Flags: ${page.flags.ifEmpty { listOf("none") }.joinToString(", ")}")
            if (page.shown.isEmpty()) appendLine("Shown: no event")
            page.shown.forEachIndexed { i, e ->
                appendLine("Event ${i + 1}: title ${e.title ?: "(none)"}; date ${e.date ?: "(none)"}; time ${e.time ?: "(none)"}")
            }
            if (includeText && !page.readText.isNullOrBlank()) {
                appendLine()
                appendLine("Text read from the screenshot:")
                appendLine(page.readText.trim())
            }
        }
    }.trimEnd() + "\n"
}
