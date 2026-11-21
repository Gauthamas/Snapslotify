package `in`.gauthama.snapslotify.stats

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import `in`.gauthama.snapslotify.BuildConfig
import `in`.gauthama.snapslotify.R
import `in`.gauthama.snapslotify.databinding.ActivityAboutBinding
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * About: the app's version, the privacy policy (opened in the browser: the app has no network access) and the taps-saved
 * totals kept on the phone ([LocalStats]).
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.version.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        binding.privacy.setOnClickListener { openPrivacyPolicy() }
    }

    private fun openPrivacyPolicy() {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url)))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val t = LocalStats.totals(this@AboutActivity)
            binding.totals.text = if (t.events == 0) getString(R.string.about_no_events) else getString(
                R.string.about_totals, t.events, t.shownSaved, String.format(Locale.getDefault(), "%.1f", t.averageSaved), t.charsNotTyped,
                t.manualTaps, t.appTaps,
            )
            binding.noSaving.visibility = if (t.noSaving > 0) android.view.View.VISIBLE else android.view.View.GONE
            binding.noSaving.text = resources.getQuantityString(R.plurals.about_no_saving, t.noSaving, t.noSaving)
            val timeouts = t.timeouts.values.sum()
            binding.timeouts.text = getString(R.string.about_timeouts, timeouts)
        }
    }
}
