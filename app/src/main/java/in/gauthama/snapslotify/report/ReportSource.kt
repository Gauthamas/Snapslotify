package `in`.gauthama.snapslotify.report

import android.graphics.Bitmap

/**
 * What the review screen hands to the report screen, in memory: the page as the app showed it and the screenshot. Nothing
 * is written to disk here; the report screen copies the screenshot only when the user chose to attach it.
 */
object ReportSource {
    class Snapshot(val page: ProblemReport.Page, val bitmap: Bitmap?)

    @Volatile var current: Snapshot? = null
}
