package `in`.gauthama.snapslotify

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import `in`.gauthama.snapslotify.camera.CameraActivity
import `in`.gauthama.snapslotify.databinding.ActivityMainBinding
import `in`.gauthama.snapslotify.image.CameraCapture
import `in`.gauthama.snapslotify.metrics.MetricsLog
import `in`.gauthama.snapslotify.review.ReviewActivity
import `in`.gauthama.snapslotify.stats.LocalStats
import `in`.gauthama.snapslotify.stats.TapCosts
import `in`.gauthama.snapslotify.stats.TapsSaved
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Home: Pick image (the system photo picker, no photo permission), Take photo, or share an image to the app from any
 * other app. Under the title, the promise, an example of the taps saved and, once events were added, the totals.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()

    private val pickImage: ActivityResultLauncher<PickVisualMediaRequest> =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) reviewFromPicker.launch(reviewIntent(uri))
    }
    /** A review opened from the in-app picker: Back from it opens the picker again. Other routes are unchanged. */
    private val reviewFromPicker: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        launchPicker()
    }

    private fun launchPicker() = pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    /** The photo file the camera is writing. Saved across process death: the camera app can outlive this process. */
    private var pendingCapture: File? = null
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val photo = CameraCapture.finish(success, pendingCapture)
        pendingCapture = null
        if (photo != null) openReview(captureUri(photo))
    }
    /** The own camera opens the review itself; it comes back here only when the camera was refused or would not start. */
    private val ownCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == CameraActivity.RESULT_NO_PERMISSION || r.resultCode == CameraActivity.RESULT_NO_CAMERA) startSystemCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ShareShortcut.publish(this)

        pendingCapture = savedInstanceState?.getString(STATE_PENDING_CAPTURE)?.let(::File)
        val inUse = pendingCapture
        if (savedInstanceState == null) previewIfAsked(intent)
        io.execute { runCatching { CameraCapture.deleteStale(cacheDir, inUse) } }

        binding.pickImage.setOnClickListener {
            launchPicker()
        }
        binding.takePhoto.setOnClickListener { startCamera() }
        // the on-device models come in the install's asset pack: copied once, in the background, into the app's storage
        `in`.gauthama.snapslotify.assist.ModelFiles.prepare(this)
        // a report's screenshot left from an earlier session
        `in`.gauthama.snapslotify.report.ReportCache.clear(this)
        binding.about.setOnClickListener { startActivity(android.content.Intent(this, `in`.gauthama.snapslotify.stats.AboutActivity::class.java)) }
        binding.reportProblem.setOnClickListener { `in`.gauthama.snapslotify.report.ReportProblemActivity.open(this, null) }
        // Export metrics is a dev tool: debug build only
        binding.exportMetrics.visibility = if (DebugActions.AVAILABLE) View.VISIBLE else View.GONE
        if (DebugActions.AVAILABLE) binding.exportMetrics.setOnClickListener {
            MetricsLog.shareIntent(this)?.let(::startActivity)
                ?: Toast.makeText(this, R.string.no_metrics_yet, Toast.LENGTH_SHORT).show()
        }

        // The OCR spike and debug settings live only in the debug source set: referenced by name, hidden in release.
        // (Intent.resolveActivity returns an explicit component without checking it exists, so it cannot tell.)
        val spikeIntent = Intent().setClassName(this, SPIKE_OCR_ACTIVITY)
        val settingsIntent = Intent().setClassName(this, DEBUG_SETTINGS_ACTIVITY)
        binding.openOcrSpike.visibility = if (DebugActions.AVAILABLE) View.VISIBLE else View.GONE
        binding.openDebugSettings.visibility = if (DebugActions.AVAILABLE) View.VISIBLE else View.GONE
        if (DebugActions.AVAILABLE) {
            binding.openOcrSpike.setOnClickListener { startActivity(spikeIntent) }
            binding.openDebugSettings.setOnClickListener { startActivity(settingsIntent) }
        }

        if (savedInstanceState == null) retakeIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        retakeIfAsked(intent)
        previewIfAsked(intent)
    }

    /**
     * Debug builds only, for screenshots without adding events to the real totals: "first_run" shows the home screen as
     * on first run; "about" opens About; "added" opens the confirmation with the numbers given (events, manual, app, saved, more).
     */
    private fun previewIfAsked(intent: Intent?) {
        if (!DebugActions.AVAILABLE || intent == null) return
        when (intent.getStringExtra(EXTRA_DEBUG_PREVIEW)) {
            "added" -> startActivity(`in`.gauthama.snapslotify.stats.AddedActivity.intent(this, TapsSaved.Estimate(
                intent.getIntExtra("events", 1), intent.getIntExtra("manual", 0), intent.getIntExtra("app", 0),
                intent.getIntExtra("saved", 0), 0), intent.getBooleanExtra("more", false))).also { intent.removeExtra(EXTRA_DEBUG_PREVIEW) }
            "about" -> startActivity(Intent(this, `in`.gauthama.snapslotify.stats.AboutActivity::class.java)).also { intent.removeExtra(EXTRA_DEBUG_PREVIEW) }
            else -> Unit
        }
    }

    /** The review screen's blurry-photo prompt chose Retake: open the camera again (once per request). */
    private fun retakeIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_RETAKE, false) != true) return
        intent.removeExtra(EXTRA_RETAKE)
        startCamera()
    }

    override fun onResume() {
        super.onResume()
        bindTaps()
    }

    /**
     * The taps lines: the promise and an example from the counter's costs; once events were added, the running totals
     * ([LocalStats], on this phone only).
     */
    private fun bindTaps() {
        lifecycleScope.launch {
            val firstRun = DebugActions.AVAILABLE && intent?.getStringExtra(EXTRA_DEBUG_PREVIEW) == "first_run"
            val t = LocalStats.totals(this@MainActivity).let { if (firstRun) it.copy(events = 0) else it }
            binding.promise.visibility = View.VISIBLE
            binding.example.visibility = View.VISIBLE
            val x = TapsSaved.example(java.time.LocalDate.now(), amPm = !android.text.format.DateFormat.is24HourFormat(this@MainActivity))
            binding.example.text = getString(R.string.home_example, TapCosts.EXAMPLE_TITLE_CHARS, x.manualTaps, x.appTaps)
            binding.totals.visibility = if (t.events > 0) View.VISIBLE else View.GONE
            if (t.events > 0) binding.totals.text = resources.getQuantityString(R.plurals.home_totals, t.events, t.shownSaved, t.events,
                String.format(java.util.Locale.getDefault(), "%.1f", t.averageSaved))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCapture?.let { outState.putString(STATE_PENDING_CAPTURE, it.path) }
    }

    /** The app's own camera (a short burst, the sharpest frame kept); the system camera when it cannot be used. */
    private fun startCamera() {
        try {
            ownCamera.launch(Intent(this, CameraActivity::class.java))
        } catch (e: ActivityNotFoundException) {
            startSystemCamera()
        }
    }

    /** The system camera, through its own app: a single photo. */
    private fun startSystemCamera() {
        val file = try {
            CameraCapture.newFile(cacheDir)
        } catch (e: IOException) {
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_LONG).show()
            return
        }
        pendingCapture = file
        try {
            takePhoto.launch(captureUri(file))
        } catch (e: ActivityNotFoundException) {
            CameraCapture.finish(false, file)
            pendingCapture = null
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun captureUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun openReview(uri: Uri) {
        startActivity(reviewIntent(uri))
    }

    private fun reviewIntent(uri: Uri): Intent = Intent(this, ReviewActivity::class.java).setData(uri)
        .putExtra(ReviewActivity.EXTRA_TAP_TIME, SystemClock.elapsedRealtime())

    companion object {
        private const val SPIKE_OCR_ACTIVITY = "in.gauthama.snapslotify.spike.SpikeOcrActivity"
        private const val STATE_PENDING_CAPTURE = "pending_capture"

        /** Set by the review screen's Retake: open the camera on arrival. */
        const val EXTRA_RETAKE = "retake"
        const val EXTRA_DEBUG_PREVIEW = "debug_preview"
        private const val DEBUG_SETTINGS_ACTIVITY = "in.gauthama.snapslotify.spike.DebugSettingsActivity"
    }
}
