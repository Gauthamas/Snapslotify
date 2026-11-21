package `in`.gauthama.snapslotify.metrics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * The app's only stored data: files/metrics/metrics.jsonl, one JSON line per image and per event (no image, no OCR
 * text, no titles; see core Metrics). Shared through the share sheet only when the user taps Export.
 */
object MetricsLog {

    private fun file(context: Context) = File(File(context.filesDir, "metrics"), "metrics.jsonl")

    @Synchronized
    fun append(context: Context, lines: List<String>) {
        if (lines.isEmpty()) return
        val f = file(context)
        f.parentFile?.mkdirs()
        f.appendText(lines.joinToString("") { it + "\n" })
    }

    /** A share-sheet intent for the log, or null if nothing has been logged yet. */
    fun shareIntent(context: Context): Intent? {
        val f = file(context)
        if (!f.exists() || f.length() == 0L) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null,
        )
    }
}
