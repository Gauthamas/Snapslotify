package `in`.gauthama.snapslotify

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.VerificationModes.times
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.gauthama.snapslotify.image.CameraCapture
import `in`.gauthama.snapslotify.review.ReviewActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** "Take photo", with the system camera stubbed: nothing is photographed, and the only image is a blank one made here. */
@RunWith(AndroidJUnit4::class)
class CameraFlowTest {

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val cameraDir get() = File(target.cacheDir, "camera")

    @Before
    fun setUp() {
        cameraDir.deleteRecursively()
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
        cameraDir.deleteRecursively()
    }

    private fun leftovers() = cameraDir.listFiles()?.size ?: 0

    private fun blankJpeg(out: java.io.OutputStream) {
        val b = Bitmap.createBitmap(200, 200, Bitmap.Config.RGB_565).apply { eraseColor(Color.WHITE) }
        b.compress(Bitmap.CompressFormat.JPEG, 80, out)
        b.recycle()
    }

    @Test
    fun cancellingTheCameraStaysOnHomeWithNoErrorAndNoFile() {
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.take_photo)).perform(click())
            intended(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
            onView(withId(R.id.take_photo)).check(matches(isDisplayed()))
            intended(hasComponent(ReviewActivity::class.java.name), times(0))
            assertEquals("the temporary file must be gone", 0, leftovers())
        }
    }

    @Test
    fun aCameraThatWroteNothingCountsAsCancelled() {
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.take_photo)).perform(click())
            onView(withId(R.id.take_photo)).check(matches(isDisplayed()))
            intended(hasComponent(ReviewActivity::class.java.name), times(0))
            assertEquals(0, leftovers())
        }
    }

    @Test
    fun recreatedWithNoCameraResultDoesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            onView(withId(R.id.take_photo)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun aPhotoOpensTheReviewWithOurCaptureFile() {
        intending(hasComponent(ReviewActivity::class.java.name)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE)).respondWithFunction { intent ->
            @Suppress("DEPRECATION")
            val out = intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
            target.contentResolver.openOutputStream(out)!!.use(::blankJpeg)
            Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.take_photo)).perform(click())
            intended(hasComponent(ReviewActivity::class.java.name))
        }
    }

    @Test
    fun theReviewDeletesTheCaptureOnceDecoded() {
        val file = CameraCapture.newFile(target.cacheDir)
        file.outputStream().use(::blankJpeg)
        val uri = FileProvider.getUriForFile(target, "${target.packageName}.files", file)
        val review = Intent(target, ReviewActivity::class.java).setData(uri)
        ActivityScenario.launch<ReviewActivity>(review).use {
            val end = System.currentTimeMillis() + 30_000
            while (file.exists() && System.currentTimeMillis() < end) Thread.sleep(100)
            assertFalse("the photo should be gone once it has been decoded", file.exists())
            // OCR runs after the decode, so the form comes a little later. It must come: deleting must not break the review.
            val formEnd = System.currentTimeMillis() + 60_000
            while (true) {
                try {
                    onView(withId(R.id.form_scroll)).check(matches(isDisplayed()))
                    break
                } catch (e: Throwable) {
                    if (System.currentTimeMillis() > formEnd) throw e
                    Thread.sleep(250)
                }
            }
        }
    }

    @Test
    fun aSharedImageIsNeverDeleted() {
        val file = File(target.cacheDir, "shared_image.jpg").apply { outputStream().use(::blankJpeg) }
        ActivityScenario.launch<ReviewActivity>(Intent(target, ReviewActivity::class.java).setData(Uri.fromFile(file))).use {
            Thread.sleep(5_000)
            assertTrue(file.exists())
        }
        file.delete()
    }
}
