package `in`.gauthama.snapslotify.report

import android.app.Activity
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import `in`.gauthama.snapslotify.BuildConfig
import `in`.gauthama.snapslotify.R
import `in`.gauthama.snapslotify.databinding.ActivityReportBinding

/**
 * Report a problem: the user picks what went wrong, may add a note, and may attach the screenshot and the text read from
 * it (both off by default). Opens the user's email app with the report filled in, to [ProblemReport.ADDRESS]; the user
 * reviews it and sends it there. With no email app, the report is shown with a copy button.
 */
class ReportProblemActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportBinding
    private var snapshot: ReportSource.Snapshot? = null
    private var sent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // a screenshot left by an earlier report
        if (savedInstanceState == null) ReportCache.clear(this)
        snapshot = ReportSource.current

        val page = snapshot?.page
        val hasImage = snapshot?.bitmap != null
        val hasText = !page?.readText.isNullOrBlank()
        binding.attachScreenshot.isEnabled = hasImage
        if (!hasImage) binding.attachScreenshotHelp.setText(R.string.report_no_screenshot)
        binding.includeText.isEnabled = hasText
        if (!hasText) binding.includeTextHelp.setText(R.string.report_no_text)

        binding.reasons.setOnCheckedChangeListener { _, _ -> binding.send.isEnabled = reason() != null }
        binding.send.setOnClickListener { send() }
        binding.copyReport.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.report_title), binding.reportText.text))
            Toast.makeText(this, R.string.report_copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // back from the email app: the attachment has been handed over, the copy goes
        if (sent) { ReportCache.clear(this); sent = false }
    }

    private fun reason(): ProblemReport.Reason? = when (binding.reasons.checkedRadioButtonId) {
        R.id.reason_wrong_date -> ProblemReport.Reason.WRONG_DATE_NOT_FLAGGED
        R.id.reason_wrong_time -> ProblemReport.Reason.WRONG_TIME_NOT_FLAGGED
        R.id.reason_wrong_title -> ProblemReport.Reason.WRONG_TITLE
        R.id.reason_no_event -> ProblemReport.Reason.NO_EVENT
        R.id.reason_missed_event -> ProblemReport.Reason.MISSED_EVENT
        R.id.reason_other -> ProblemReport.Reason.OTHER
        else -> null
    }

    private fun send() {
        val reason = reason() ?: return
        val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})"
        val device = ProblemReport.Device(version, "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", "${Build.MANUFACTURER} ${Build.MODEL}")
        val body = ProblemReport.body(reason, binding.note.text?.toString(), device, snapshot?.page, binding.includeText.isChecked)
        val subject = ProblemReport.subject(BuildConfig.VERSION_NAME)
        // the screenshot is copied to the cache only now, and only when the user chose to attach it
        val bitmap = snapshot?.bitmap
        val attachment = if (binding.attachScreenshot.isChecked && bitmap != null) ReportCache.attach(this, bitmap) else null
        val apps = if (canHandle(this, ReportIntents.build(subject, body, attachment))) emailApps(this) else emptyList()
        when (apps.size) {
            0 -> showCopy(subject, body)
            1 -> open(apps[0].pkg, subject, body, attachment)
            // more than one email app: the user picks one (Android's own picker cannot be limited to email apps here)
            else -> androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.report_pick_email_app)
                .setItems(apps.map { it.label }.toTypedArray()) { _, which -> open(apps[which].pkg, subject, body, attachment) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> ReportCache.clear(this) }
                .show()
        }
    }

    /** The report to the email app [pkg]; if it does not take it with the screenshot, the plain email; else the copy panel. */
    private fun open(pkg: String, subject: String, body: String, attachment: android.net.Uri?) {
        for (intent in listOf(ReportIntents.build(subject, body, attachment, pkg), ReportIntents.plain(subject, body, pkg))) {
            try {
                sent = true
                launcher(this, intent)
                return
            } catch (e: ActivityNotFoundException) {
                sent = false
            }
        }
        showCopy(subject, body)
    }

    private fun showCopy(subject: String, body: String) {
        ReportCache.clear(this)
        binding.noEmailMessage.text = getString(R.string.report_no_email_app, ProblemReport.ADDRESS)
        binding.reportText.text = "To: ${ProblemReport.ADDRESS}\nSubject: $subject\n\n$body"
        binding.noEmailPanel.visibility = View.VISIBLE
    }

    companion object {
        /** Whether an email app can take the report; replaced in tests. */
        @VisibleForTesting var canHandle: (Context, Intent) -> Boolean = ReportIntents::canHandle
        /** The email apps on the phone; replaced in tests. */
        @VisibleForTesting var emailApps: (Context) -> List<ReportIntents.App> = ReportIntents::emailApps
        /** Opens the email app; replaced in tests to capture the intent. */
        @VisibleForTesting var launcher: (Activity, Intent) -> Unit = { a, i -> a.startActivity(i) }

        /** Opens the screen for [snapshot] (null: a report not about a page, from the home screen). */
        fun open(context: Context, snapshot: ReportSource.Snapshot?) {
            ReportSource.current = snapshot
            context.startActivity(Intent(context, ReportProblemActivity::class.java))
        }
    }
}
