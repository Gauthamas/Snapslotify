package `in`.gauthama.snapslotify.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import `in`.gauthama.snapslotify.R
import `in`.gauthama.snapslotify.core.image.Burst
import `in`.gauthama.snapslotify.core.image.Sharpness
import `in`.gauthama.snapslotify.databinding.ActivityCameraBinding
import `in`.gauthama.snapslotify.image.CameraCapture
import `in`.gauthama.snapslotify.image.sharpness
import `in`.gauthama.snapslotify.review.ReviewActivity
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The app's own camera: one tap takes a short burst of [BURST] frames and keeps the sharpest ([Sharpness], measured on
 * the middle of each frame at full resolution), then opens the review. The other frames are deleted at once; the kept
 * one lives only until the review has decoded it, as with the system camera.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var capture: ImageCapture? = null
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var busy = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else { setResult(RESULT_NO_PERMISSION); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.shutter.setOnClickListener { takeBurst() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            // Both streams 4:3 (the sensor's full field), and the preview fits rather than fills (layout), so what the
            // preview shows is what the photo holds; filling the tall screen would crop the preview's left and right.
            val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val preview = Preview.Builder().setResolutionSelector(ratio).build().also { it.surfaceProvider = binding.preview.surfaceProvider }
            val ic = ImageCapture.Builder().setResolutionSelector(ratio).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic)
                capture = ic
            } catch (e: Exception) {
                Log.w(TAG, "camera not started: ${e.javaClass.simpleName}")
                setResult(RESULT_NO_CAMERA); finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** [BURST] frames one after another, then the sharpest is kept. */
    private fun takeBurst() {
        val ic = capture ?: return
        if (busy) return
        busy = true
        binding.shutter.isEnabled = false
        binding.status.visibility = View.VISIBLE
        binding.status.setText(R.string.camera_hold_still)
        val frames = mutableListOf<File>()
        val t0 = SystemClock.elapsedRealtime()
        fun next() {
            val f = try { CameraCapture.newFile(cacheDir, System.currentTimeMillis() + frames.size) } catch (e: Exception) { null }
            if (f == null) { fail(frames); return }
            ic.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), io, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    frames += f
                    if (frames.size < BURST) next() else pick(frames, t0)
                }

                override fun onError(e: ImageCaptureException) {
                    f.delete()
                    Log.w(TAG, "frame ${frames.size + 1} failed: ${e.imageCaptureError}")
                    if (frames.isEmpty()) runOnUiThread { fail(frames) } else pick(frames, t0)
                }
            })
        }
        next()
    }

    /** On the camera's thread: score every frame, keep the sharpest, delete the rest, open the review. */
    private fun pick(frames: List<File>, t0: Long) {
        val scores = frames.map { f -> runCatching { middleSharpness(f) }.getOrDefault(0.0) }
        val best = Burst.sharpest(scores)
        frames.forEachIndexed { i, f -> if (i != best) f.delete() }
        Log.i(TAG, "burst frames=${frames.size} best=$best scores=${scores.joinToString { "%.3f".format(it) }} ms=${SystemClock.elapsedRealtime() - t0}")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", frames[best])
        runOnUiThread {
            startActivity(Intent(this, ReviewActivity::class.java).setData(uri)
                .putExtra(ReviewActivity.EXTRA_TAP_TIME, SystemClock.elapsedRealtime()))
            finish()
        }
    }

    private fun fail(frames: List<File>) {
        frames.forEach { it.delete() }
        Toast.makeText(this, R.string.camera_failed, Toast.LENGTH_LONG).show()
        busy = false
        binding.shutter.isEnabled = true
        binding.status.visibility = View.GONE
    }

    /** Sharpness of the middle of the frame (half its width and height) at full resolution: quick, and where the page is. */
    private fun middleSharpness(f: File): Double {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val w = bounds.outWidth; val h = bounds.outHeight
        val r = Rect(w / 4, h / 4, w * 3 / 4, h * 3 / 4)
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(f.path, false) ?: return 0.0
        val bmp = try { decoder.decodeRegion(r, null) } finally { decoder.recycle() } ?: return 0.0
        return try { bmp.sharpness() } finally { bmp.recycle() }
    }

    companion object {
        private const val TAG = "SnapCamera"

        /** Frames per tap: a short burst, then the sharpest is kept. */
        const val BURST = 4

        /** The user refused the camera permission, or no camera could be started: the caller uses the system camera. */
        const val RESULT_NO_PERMISSION = RESULT_FIRST_USER
        const val RESULT_NO_CAMERA = RESULT_FIRST_USER + 1
    }
}
