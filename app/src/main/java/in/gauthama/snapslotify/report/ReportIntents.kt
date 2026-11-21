package `in`.gauthama.snapslotify.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The email the user reviews and sends, to [ProblemReport.ADDRESS]. The app has no INTERNET permission and sends nothing
 * itself.
 *
 * The email apps are found by who takes a mailto: link ([emailApps]), and the report is handed to the one the user picks
 * by its package. Not a SEND with a mailto: selector: on some phones (an OPPO A17 with Gmail and Proton Mail) Android
 * finds no app for that pair and the start crashes.
 */
object ReportIntents {

    /** One email app: its package and the name shown (with its maker when two apps share a name). */
    data class App(val pkg: String, val label: String)

    /** The email apps on the phone: the apps that take a mailto: link, one per app. */
    fun emailApps(context: Context): List<App> {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
        val clash = apps.groupingBy { it.label }.eachCount()
        return apps.map { a ->
            val maker = a.pkg.split('.').let { p -> p.getOrNull(if (p.firstOrNull() in setOf("com", "org", "net", "ch", "in")) 1 else 0) }
                ?.replaceFirstChar { it.uppercase() }
            if ((clash[a.label] ?: 0) > 1 && maker != null) a.copy(label = "${a.label} ($maker)") else a
        }
    }

    /** The report for the email app [pkg] (null: any app that takes it), with the screenshot when [attachment] is set. */
    fun build(subject: String, body: String, attachment: Uri?, pkg: String? = null): Intent = Intent(Intent.ACTION_SEND).apply {
        type = if (attachment != null) "image/jpeg" else "text/plain"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(ProblemReport.ADDRESS))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
        if (attachment != null) {
            putExtra(Intent.EXTRA_STREAM, attachment)
            clipData = ClipData.newRawUri("screenshot", attachment)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (pkg != null) setPackage(pkg)
    }

    /** The plain email (no attachment) for [pkg], when the app does not take the report with SEND. */
    fun plain(subject: String, body: String, pkg: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${ProblemReport.ADDRESS}")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(ProblemReport.ADDRESS))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            setPackage(pkg)
        }

    /** True when an email app is on the phone. */
    fun canHandle(context: Context, @Suppress("UNUSED_PARAMETER") intent: Intent): Boolean = emailApps(context).isNotEmpty()
}
