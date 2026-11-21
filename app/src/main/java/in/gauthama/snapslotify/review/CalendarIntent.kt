package `in`.gauthama.snapslotify.review

import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import `in`.gauthama.snapslotify.core.review.CalendarValues
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The calendar app's insert editor, prefilled. No calendar permission: nothing is saved unless the user saves it in
 * the editor. All-day events use UTC midnights, as CalendarContract expects; timed events use the phone's time zone.
 *
 * Each intent carries its own identifier: Android compares intents without their extras, so without it a second event's
 * intent equals the first one's and a calendar app whose editor is still open in its own task (Google Calendar) is
 * brought back showing the first event instead of opening the second.
 */
object CalendarIntent {

    private var serial = 0L

    /** The bare insert intent, to ask which calendar apps take it. */
    fun probe(): Intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)

    /** [pkg]: the calendar app to open (null: Android asks, or uses the user's default). */
    fun insert(v: CalendarValues, zone: ZoneId = ZoneId.systemDefault(), pkg: String? = null): Intent {
        val z = if (v.allDay) ZoneOffset.UTC else zone
        return probe()
            .also { if (pkg != null) it.setPackage(pkg) }
            .also { if (Build.VERSION.SDK_INT >= 29) it.identifier = "event-${System.currentTimeMillis()}-${++serial}" }
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, v.begin.atZone(z).toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, v.end.atZone(z).toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, v.allDay)
            .apply {
                v.title?.let { putExtra(CalendarContract.Events.TITLE, it) }
                v.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            }
    }
}
