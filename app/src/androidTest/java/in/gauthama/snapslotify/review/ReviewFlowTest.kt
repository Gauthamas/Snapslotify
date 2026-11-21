package `in`.gauthama.snapslotify.review

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.gauthama.snapslotify.DebugActions
import `in`.gauthama.snapslotify.R
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Review flow on a device. Images: one DEV synthetic image from the test APK's assets (never the sealed set), plus a
 * blank image and a 4000 x 4000 image generated here. The calendar editor intent is stubbed, so nothing reaches a calendar.
 */
@RunWith(AndroidJUnit4::class)
class ReviewFlowTest {

    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        Intents.init()
        intending(hasAction(Intent.ACTION_INSERT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
    }

    @After
    fun tearDown() {
        Intents.release()
        File(target.cacheDir, "test_images").deleteRecursively()
    }

    private fun imageFile(name: String): File = File(target.cacheDir, "test_images").apply { mkdirs() }.let { File(it, name) }

    private fun devImage(name: String): Uri {
        val f = imageFile(name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> f.outputStream().use(input::copyTo) }
        return Uri.fromFile(f)
    }

    private fun generated(name: String, w: Int, h: Int, config: Bitmap.Config): Uri {
        val bmp = Bitmap.createBitmap(w, h, config).apply { eraseColor(Color.WHITE) }
        imageFile(name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bmp.recycle()
        return Uri.fromFile(imageFile(name))
    }

    private fun launch(uri: Uri) = ActivityScenario.launch<ReviewActivity>(Intent(target, ReviewActivity::class.java).setData(uri))

    /** Polls until [matcher] is displayed (OCR runs off the main thread, so there is no idling resource for it). */
    private fun waitFor(matcher: Matcher<View>, timeoutMs: Long = 60_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                onView(matcher).check(matches(isDisplayed()))
                return
            } catch (e: Throwable) {
                if (System.currentTimeMillis() > end) throw e
                Thread.sleep(250)
            }
        }
    }

    private fun inEvent(id: Int, viewId: Int) = allOf(withId(viewId), isDescendantOfA(withTagValue(`is`("event_$id"))))

    @Test
    fun devImageChipThenCalendarEditor() {
        launch(devImage(DEV_IMAGE)).use {
            waitFor(withTagValue(`is`("date_chip_0")))
            onView(allOf(withTagValue(`is`("date_chip_0")), isDescendantOfA(withTagValue(`is`("event_0"))))).perform(scrollTo(), click())
            onView(inEvent(0, R.id.add_to_calendar)).perform(scrollTo(), click())
            intended(allOf(hasAction(Intent.ACTION_INSERT), hasData(CalendarContract.Events.CONTENT_URI)))
        }
    }

    @Test
    fun rotationKeepsTheReview() {
        launch(devImage(DEV_IMAGE)).use { scenario ->
            waitFor(withTagValue(`is`("date_chip_0")))
            onView(allOf(withTagValue(`is`("date_chip_0")), isDescendantOfA(withTagValue(`is`("event_0"))))).perform(scrollTo(), click())
            var before = ""
            scenario.onActivity { before = it.findViewById<View>(android.R.id.content).findViewWithTag<View>("event_0").findViewById<TextView>(R.id.date).text.toString() }
            scenario.recreate()
            waitFor(inEvent(0, R.id.date))
            var after = ""
            scenario.onActivity { after = it.findViewById<View>(android.R.id.content).findViewWithTag<View>("event_0").findViewById<TextView>(R.id.date).text.toString() }
            assertEquals(before, after)
        }
    }

    @Test
    fun nonEventImageShowsEmptyForm() {
        launch(generated("blank.jpg", 800, 800, Bitmap.Config.ARGB_8888)).use {
            waitFor(withId(R.id.no_event))
        }
    }

    @Test
    fun veryLargeImageDoesNotCrash() {
        launch(generated("large.jpg", 4000, 4000, Bitmap.Config.RGB_565)).use {
            waitFor(withId(R.id.form_scroll))
        }
    }

    private fun titleOf(scenario: ActivityScenario<ReviewActivity>): String {
        var t = ""
        scenario.onActivity { t = it.findViewById<View>(android.R.id.content).findViewWithTag<View>("event_0").findViewById<TextView>(R.id.title).text.toString() }
        return t
    }

    private fun screenHeldOn(scenario: ActivityScenario<ReviewActivity>): Boolean {
        var on = false
        scenario.onActivity { on = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        return on
    }

    private fun waitUntil(what: String, timeoutMs: Long, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            assertTrue("timed out waiting for: $what", System.currentTimeMillis() < end)
            Thread.sleep(50)
        }
    }

    @Test
    fun viewOcrJsonShowsItPrettyPrinted() {
        launch(devImage(DEV_IMAGE)).use {
            waitFor(inEvent(0, R.id.title))
            onView(withId(R.id.view_ocr_json)).perform(scrollTo(), click())
            onView(withTagValue(`is`(ReviewActivity.OCR_JSON_VIEW_TAG))).inRoot(isDialog()).check(matches(isDisplayed()))
            // One key to a line with an indent is what pretty printed means; the boxes show that it is the real OCR result.
            onView(withTagValue(`is`(ReviewActivity.OCR_JSON_VIEW_TAG))).inRoot(isDialog())
                .check(matches(withText(containsString("\n    \"lines\": ["))))
            onView(withTagValue(`is`(ReviewActivity.OCR_JSON_VIEW_TAG))).inRoot(isDialog())
                .check(matches(withText(containsString("\"boundingBox\""))))
            onView(withText(R.string.close)).inRoot(isDialog()).perform(click())
            onView(withId(R.id.view_ocr_json)).check(matches(isDisplayed()))  // back on the review screen, still scrolled to the button
        }
    }

    /** Debug build: the raw lines of the last image, with the engine that read them and the OCR time. */
    @Test
    fun showRawOcrListsTheLinesWithEngineAndTime() {
        launch(devImage(DEV_IMAGE)).use {
            waitFor(inEvent(0, R.id.title))
            onView(withTagValue(`is`(DebugActions.RAW_OCR_TAG))).perform(scrollTo(), click())
            val raw = onView(withTagValue(`is`(DebugActions.RAW_OCR_VIEW_TAG))).inRoot(isDialog())
            raw.check(matches(isDisplayed()))
            raw.check(matches(withText(containsString("engine mlkit   ocr "))))
            raw.check(matches(withText(containsString("[mlkit]"))))
            raw.check(matches(withText(containsString(" ms\n"))))
            onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
        }
    }

    /** The hold starts with the share, before OCR has finished, and a poster releases it once the router has decided. */
    @Test
    fun screenIsHeldFromTheShareUntilThePosterIsRouted() {
        launch(generated("first.jpg", 800, 800, Bitmap.Config.ARGB_8888)).close() // OCR is warm, so only this share's timing is under test
        launch(devImage(DEV_IMAGE)).use { scenario ->
            waitUntil("the screen to be held on", 3_000) { screenHeldOn(scenario) }
            waitUntil("the hold to be released", 60_000) { !screenHeldOn(scenario) }
            waitFor(inEvent(0, R.id.title))
        }
    }

    /** A poster is code-only: the screen is not held on and no suggestion appears, even with the model on the phone. */
    @Test
    fun posterGetsNoModelAndNoHeldScreen() {
        launch(devImage(DEV_IMAGE)).use { scenario ->
            waitFor(inEvent(0, R.id.title))
            Thread.sleep(3_000)
            assertFalse(screenHeldOn(scenario))
            onView(inEvent(0, R.id.title_suggestions)).check(matches(not(isDisplayed())))
            onView(inEvent(0, R.id.date_suggestions)).check(matches(not(isDisplayed())))
        }
    }

    /**
     * A chat with the model on the phone: code result first, screen held on while the model works, then chips, then the
     * screen released, and the fields still hold the code result. Skipped when the model file is absent.
     */
    @Test
    fun chatShowsCodeResultFirstThenChipsWithoutChangingAField() {
        assumeTrue("model not on the phone", File(target.filesDir, "vl/LFM2.5-VL-450M-mix-Q4_0.gguf").isFile)
        launch(devImage(DEV_CHAT_IMAGE)).use { scenario ->
            waitFor(inEvent(0, R.id.title))
            val title = titleOf(scenario)
            waitUntil("the screen to be held on", 5_000) { screenHeldOn(scenario) }
            onView(inEvent(0, R.id.title_suggestions)).check(matches(not(isDisplayed())))
            waitUntil("the model to finish", 120_000) { !screenHeldOn(scenario) }
            waitFor(inEvent(0, R.id.title_suggestions), 5_000)
            assertEquals(title, titleOf(scenario))
        }
    }

    private fun shareIntent(uri: Uri) = Intent(Intent.ACTION_SEND).setClass(target, ReviewActivity::class.java).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)

    /** Launches through a share. ActivityScenario finds its screen by comparing intents, and the screen takes on each new share's intent. */
    private fun launchShare(uri: Uri) = ActivityScenario.launch<ReviewActivity>(shareIntent(uri))

    /** A second share handed to the running screen, as the system does for a singleTop activity. The soak test covers the path through the system. */
    private fun ActivityScenario<ReviewActivity>.shareAgain(uri: Uri) {
        val share = shareIntent(uri)
        onActivity { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(it, share) }
    }

    private fun waitForNewTitle(scenario: ActivityScenario<ReviewActivity>, old: String) =
        waitUntil("the new review's title", 60_000) { runCatching { titleOf(scenario) }.getOrDefault(old).let { it != old } }

    @Test
    fun secondShareReplacesAFinishedReviewAndWhatWasTyped() {
        launchShare(devImage(DEV_IMAGE)).use { scenario ->
            waitFor(inEvent(0, R.id.title))
            onView(inEvent(0, R.id.title)).perform(scrollTo(), replaceText("Typed by hand"))
            scenario.shareAgain(devImage(DEV_IMAGE_2))
            waitForNewTitle(scenario, "Typed by hand")
            waitFor(inEvent(0, R.id.title))
            assertNotEquals("Typed by hand", titleOf(scenario))
            var cards = 0
            scenario.onActivity { cards = it.findViewById<android.view.ViewGroup>(R.id.events).childCount }
            assertEquals(1, cards)
        }
    }

    /** The old chat's chips must never land on the new review, and the screen hold must end. Skipped with no model on the phone. */
    @Test
    fun secondShareCancelsChipsStillComputing() {
        assumeTrue("model not on the phone", File(target.filesDir, "vl/LFM2.5-VL-450M-mix-Q4_0.gguf").isFile)
        launchShare(devImage(DEV_CHAT_IMAGE)).use { scenario ->
            waitFor(inEvent(0, R.id.title))
            val chatTitle = titleOf(scenario)
            waitUntil("the screen to be held on", 5_000) { screenHeldOn(scenario) }
            scenario.shareAgain(devImage(DEV_IMAGE))
            waitForNewTitle(scenario, chatTitle)
            waitFor(inEvent(0, R.id.title))
            assertFalse("a poster must not hold the screen", screenHeldOn(scenario))
            Thread.sleep(15_000) // longer than the chat's chips would have taken
            onView(inEvent(0, R.id.title_suggestions)).check(matches(not(isDisplayed())))
            onView(inEvent(0, R.id.date_suggestions)).check(matches(not(isDisplayed())))
            assertFalse(screenHeldOn(scenario))
        }
    }

    /** And the model is free again for the share after that: a chat replacing a chat still gets its chips. Skipped with no model. */
    @Test
    fun chatReplacingAChatStillGetsChips() {
        assumeTrue("model not on the phone", File(target.filesDir, "vl/LFM2.5-VL-450M-mix-Q4_0.gguf").isFile)
        launchShare(devImage(DEV_CHAT_IMAGE)).use { scenario ->
            waitFor(inEvent(0, R.id.title))
            val first = titleOf(scenario)
            waitUntil("the screen to be held on", 5_000) { screenHeldOn(scenario) }
            scenario.shareAgain(devImage(DEV_CHAT_IMAGE_2))
            waitForNewTitle(scenario, first)
            // Either chip row: one model call may hit its 25 s limit, and the other's chip still has to arrive.
            waitUntil("a chip from the model", 120_000) {
                var shown = false
                scenario.onActivity { a ->
                    val card = a.findViewById<View>(android.R.id.content).findViewWithTag<View>("event_0")
                    shown = card?.findViewById<View>(R.id.title_suggestions)?.visibility == View.VISIBLE ||
                        card?.findViewById<View>(R.id.date_suggestions)?.visibility == View.VISIBLE
                }
                shown
            }
            waitUntil("the screen to be released", 5_000) { !screenHeldOn(scenario) }
        }
    }

    /** The model chosen on the debug settings screen is on the phone and passes the RAM gate. */
    private fun visionModelOnPhone() = `in`.gauthama.snapslotify.assist.LlamaVisionModel.get(target) != null

    private fun tryHarderStatus(scenario: ActivityScenario<ReviewActivity>): String {
        var s = ""
        scenario.onActivity { a ->
            val v = a.findViewById<TextView>(R.id.try_harder_status)
            s = if (v.visibility == View.VISIBLE) v.text.toString() else ""
        }
        return s
    }

    private fun visible(scenario: ActivityScenario<ReviewActivity>, id: Int): Boolean {
        var shown = false
        scenario.onActivity { a -> shown = a.findViewById<View>(id).visibility == View.VISIBLE }
        return shown
    }

    /**
     * Try harder on a poster: offered after the code result, the screen held while it runs, and it ends with chips or
     * "nothing new" without changing a field; then it is not offered again. Skipped when the model files are absent.
     */
    @Test
    fun tryHarderAddsChipsWithoutChangingAField() {
        assumeTrue("vision model not on the phone", visionModelOnPhone())
        launch(devImage(DEV_IMAGE)).use { scenario ->
            waitFor(inEvent(0, R.id.title))
            waitFor(withId(R.id.try_harder), 20_000)
            val title = titleOf(scenario)
            onView(withId(R.id.try_harder)).perform(scrollTo(), click())
            waitUntil("the screen to be held on", 3_000) { screenHeldOn(scenario) }
            assertEquals(target.getString(R.string.try_harder_running), tryHarderStatus(scenario))
            val found = target.getString(R.string.try_harder_found)
            val nothing = target.getString(R.string.try_harder_nothing)
            waitUntil("try harder to finish", 150_000) { tryHarderStatus(scenario).let { it == found || it == nothing } }
            assertEquals(title, titleOf(scenario))
            assertFalse("no second run is offered", visible(scenario, R.id.try_harder))
            waitUntil("the screen to be released", 5_000) { !screenHeldOn(scenario) }
            if (tryHarderStatus(scenario) == found) {
                var any = false
                scenario.onActivity { a ->
                    val card = a.findViewById<View>(android.R.id.content).findViewWithTag<View>("event_0")
                    any = listOf(R.id.title_suggestions, R.id.date_suggestions, R.id.time_suggestions).any { card.findViewById<View>(it).visibility == View.VISIBLE }
                }
                assertTrue("found, so a suggestion row is shown", any)
            }
        }
    }

    /** Stop while it runs: the screen is released, nothing is added, and it can be tried again. Skipped without the model. */
    @Test
    fun tryHarderCanBeStopped() {
        assumeTrue("vision model not on the phone", visionModelOnPhone())
        launch(devImage(DEV_IMAGE)).use { scenario ->
            waitFor(inEvent(0, R.id.title))
            waitFor(withId(R.id.try_harder), 20_000)
            onView(withId(R.id.try_harder)).perform(scrollTo(), click())
            waitUntil("the screen to be held on", 3_000) { screenHeldOn(scenario) }
            Thread.sleep(2_000)
            onView(withId(R.id.try_harder)).perform(scrollTo(), click())
            waitUntil("stopped", 5_000) { tryHarderStatus(scenario) == target.getString(R.string.try_harder_stopped) }
            waitUntil("the screen to be released", 30_000) { !screenHeldOn(scenario) }
            assertTrue("offered again", visible(scenario, R.id.try_harder))
            onView(inEvent(0, R.id.title_suggestions)).check(matches(not(isDisplayed())))
            onView(inEvent(0, R.id.time_suggestions)).check(matches(not(isDisplayed())))
        }
    }

    /** A new share while try harder runs: the old run's chips never land on the new review. Skipped without the model. */
    @Test
    fun secondShareCancelsTryHarder() {
        assumeTrue("vision model not on the phone", visionModelOnPhone())
        launchShare(devImage(DEV_IMAGE)).use { scenario ->
            waitFor(inEvent(0, R.id.title))
            waitFor(withId(R.id.try_harder), 20_000)
            val first = titleOf(scenario)
            onView(withId(R.id.try_harder)).perform(scrollTo(), click())
            waitUntil("the screen to be held on", 3_000) { screenHeldOn(scenario) }
            scenario.shareAgain(devImage(DEV_IMAGE_2))
            waitForNewTitle(scenario, first)
            waitFor(inEvent(0, R.id.title))
            waitUntil("the old run's hold to end", 5_000) { !screenHeldOn(scenario) }
            // The wait below outlasts the phone's screen timeout; the test, not the app, keeps the screen on for it.
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(45_000) // longer than the first run would have taken
            onView(inEvent(0, R.id.title_suggestions)).check(matches(not(isDisplayed())))
            onView(inEvent(0, R.id.date_suggestions)).check(matches(not(isDisplayed())))
            onView(inEvent(0, R.id.time_suggestions)).check(matches(not(isDisplayed())))
            assertEquals("", tryHarderStatus(scenario))
        }
    }

    private companion object {
        /** A DEV synthetic poster (fixtures/synthetic100/dev); never a sealed id. */
        const val DEV_IMAGE = "084_concert_poster.png"
        const val DEV_IMAGE_2 = "064_fest_poster.png"
        const val DEV_CHAT_IMAGE_2 = "097_invite_chat.png"
        /** A DEV synthetic chat from the same folder. */
        const val DEV_CHAT_IMAGE = "093_invite_chat.png"
    }
}
