package `in`.gauthama.snapslotify.stats

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import `in`.gauthama.snapslotify.R
import `in`.gauthama.snapslotify.databinding.ActivityAddedBinding

/**
 * Shown when the user comes back from the calendar editor after Add (after the last one of Add all): the taps the
 * page's added events saved as the largest text, the by-hand and with-the-app estimates under it, and for several events
 * one line with their count and total. When they saved nothing or less, it says so in words, with the two numbers
 * under it. Done closes the review; Back to the page returns to it (other events wait).
 */
class AddedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityAddedBinding.inflate(layoutInflater)
        setContentView(b.root)
        val events = intent.getIntExtra(EXTRA_EVENTS, 0)
        val manual = intent.getIntExtra(EXTRA_MANUAL, 0)
        val app = intent.getIntExtra(EXTRA_APP, 0)
        val saved = intent.getIntExtra(EXTRA_SAVED, 0)
        b.heading.text = resources.getQuantityString(R.plurals.added_heading, events, events)
        if (saved > 0) {
            b.saved.text = getString(R.string.added_saved_number, saved)
            b.savedLabel.text = resources.getQuantityString(R.plurals.added_saved_label, saved)
        } else {
            // nothing saved, or less than nothing: said plainly, no big 0; the numbers below still show why
            b.saved.setText(R.string.added_none)
            b.saved.setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Display1)
            b.savedLabel.text = resources.getQuantityString(R.plurals.added_none_label, events)
        }
        b.breakdown.text = getString(R.string.added_breakdown, manual, app)
        b.batch.visibility = if (events > 1 && saved > 0) View.VISIBLE else View.GONE
        b.batch.text = getString(R.string.added_batch, events, saved)
        val more = intent.getBooleanExtra(EXTRA_MORE, false)
        b.back.visibility = if (more) View.VISIBLE else View.GONE
        b.back.setOnClickListener { finish() }
        b.done.setOnClickListener { setResult(Activity.RESULT_OK); finish() }
    }

    companion object {
        const val EXTRA_EVENTS = "events"
        const val EXTRA_MANUAL = "manual"
        const val EXTRA_APP = "app"
        const val EXTRA_SAVED = "saved"
        const val EXTRA_MORE = "more"

        fun intent(context: Context, page: TapsSaved.Estimate, more: Boolean): Intent = Intent(context, AddedActivity::class.java)
            .putExtra(EXTRA_EVENTS, page.events).putExtra(EXTRA_MANUAL, page.manualTaps).putExtra(EXTRA_APP, page.appTaps)
            .putExtra(EXTRA_SAVED, page.saved).putExtra(EXTRA_MORE, more)
    }
}
