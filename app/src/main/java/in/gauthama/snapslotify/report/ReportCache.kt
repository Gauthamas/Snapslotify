package `in`.gauthama.snapslotify.report

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * The cache folder a report's screenshot is copied to, only when the user chose to attach it. Shared through the app's
 * FileProvider; cleared when the user comes back from the email app and on the next launch.
 */
object ReportCache {

    private const val DIR = "reports"

    fun dir(context: Context) = File(context.cacheDir, DIR)

    /** Writes [bitmap] as a JPEG into the reports folder and returns its content URI. */
    fun attach(context: Context, bitmap: Bitmap): Uri {
        val d = dir(context).apply { mkdirs() }
        val f = File(d, "screenshot-${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}
