package `in`.gauthama.snapslotify.report

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.gauthama.snapslotify.BuildConfig
import `in`.gauthama.snapslotify.R
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Report a problem screen on the device: the email intent's address, subject and extras; the screenshot attached and
 * the read text included only when their toggles are on; no crash and the copy panel when no email app can take it.
 * The email app is never opened: the launch is captured.
 */
@RunWith(AndroidJUnit4::class)
class ReportProblemActivityTest {

    private val launched = mutableListOf<Intent>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        launched.clear()
        ReportProblemActivity.launcher = { _, i -> launched += i }
        ReportProblemActivity.canHandle = { _, _ -> true }
        ReportProblemActivity.emailApps = { listOf(ReportIntents.App("com.example.mail", "Mail")) }
        val bmp = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        ReportSource.current = ReportSource.Snapshot(
            ProblemReport.Page("single", listOf("year_not_stated"), listOf(ProblemReport.Shown("Kite Show", "2026-12-12", "11:00")), "KITE SHOW\nSat 12 Dec 11 am"),
            bmp,
        )
    }

    @After fun tearDown() {
        ReportProblemActivity.launcher = { a, i -> a.startActivity(i) }
        ReportProblemActivity.canHandle = ReportIntents::canHandle
        ReportProblemActivity.emailApps = ReportIntents::emailApps
        ReportSource.current = null
        ReportCache.clear(context)
    }

    /**
     * The real check, not the stand-in: on a phone with an email app (Gmail, Proton Mail...) the report with and without
     * a screenshot is found to have an app. Skipped on a device with no mailto: handler.
     */
    @Test fun theRealCheckFindsTheEmailAppOnThisPhone() {
        // the email apps on the phone, asked through the shell (which sees every app, whatever the app may see)
        val mailApps = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cmd package query-activities --brief -a android.intent.action.SENDTO -d mailto:")
            .let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText() }
        org.junit.Assume.assumeTrue("no email app on this device", mailApps.contains("activities found") && !mailApps.contains("No activities"))
        assertTrue("text only", ReportIntents.canHandle(context, ReportIntents.build("s", "b", null)))
        assertTrue("with a screenshot", ReportIntents.canHandle(context, ReportIntents.build("s", "b", Uri.parse("content://in.gauthama.snapslotify.files/reports/x.jpg"))))
        // every email app found takes the report as built for it (what crashed: a SEND that no app resolved)
        val apps = ReportIntents.emailApps(context)
        assertTrue(apps.isNotEmpty())
        for (a in apps) {
            val withShot = ReportIntents.build("s", "b", Uri.parse("content://in.gauthama.snapslotify.files/reports/x.jpg"), a.pkg)
            val plain = ReportIntents.plain("s", "b", a.pkg)
            assertTrue("${a.label} takes the report", withShot.resolveActivity(context.packageManager) != null || plain.resolveActivity(context.packageManager) != null)
        }
    }

    private fun send(attach: Boolean, text: Boolean, note: String = "Time should be 10 am"): ActivityScenario<ReportProblemActivity> {
        val s = ActivityScenario.launch(ReportProblemActivity::class.java)
        s.onActivity { a ->
            a.findViewById<RadioButton>(R.id.reason_wrong_time).isChecked = true
            a.findViewById<TextView>(R.id.note).text = note
            a.findViewById<SwitchCompat>(R.id.attach_screenshot).isChecked = attach
            a.findViewById<SwitchCompat>(R.id.include_text).isChecked = text
            a.findViewById<Button>(R.id.send).performClick()
        }
        return s
    }

    @Test fun theIntentHasTheAddressSubjectAndExtras() {
        send(attach = false, text = false).close()
        val i = launched.single()
        assertEquals(Intent.ACTION_SEND, i.action)
        assertArrayEquals(arrayOf("bodhiarcade@gmail.com"), i.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Snapslotify problem report (${BuildConfig.VERSION_NAME})", i.getStringExtra(Intent.EXTRA_SUBJECT))
        val body = i.getStringExtra(Intent.EXTRA_TEXT)!!
        for (s in listOf("What went wrong: Wrong time and not flagged", "Note: Time should be 10 am", "App version: ${BuildConfig.VERSION_NAME}", "Android version:",
            "Device:", "Page type: single", "Flags: year_not_stated", "Event 1: title Kite Show; date 2026-12-12; time 11:00")) assertTrue(s, s in body)
        assertEquals("handed to the email app picked", "com.example.mail", i.`package`)
    }

    @Test fun nothingIsAttachedOrIncludedWhenTheTogglesAreOff() {
        send(attach = false, text = false).close()
        val i = launched.single()
        assertNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertFalse("KITE SHOW" in i.getStringExtra(Intent.EXTRA_TEXT)!!)
        assertTrue(ReportCache.dir(context).listFiles().isNullOrEmpty())
    }

    @Test fun theScreenshotIsAttachedOnlyWhenItsToggleIsOn() {
        send(attach = true, text = false).close()
        val i = launched.single()
        val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull(uri)
        assertEquals("${context.packageName}.files", uri!!.authority)
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(1, ReportCache.dir(context).listFiles()!!.size)
        assertFalse("KITE SHOW" in i.getStringExtra(Intent.EXTRA_TEXT)!!)
    }

    @Test fun theReadTextIsIncludedOnlyWhenItsToggleIsOn() {
        send(attach = false, text = true).close()
        val i = launched.single()
        assertNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue("Text read from the screenshot:\nKITE SHOW" in i.getStringExtra(Intent.EXTRA_TEXT)!!)
    }

    @Test fun withNoEmailAppTheReportIsShownToCopy() {
        ReportProblemActivity.canHandle = { _, _ -> false }
        val s = send(attach = true, text = true)
        s.onActivity { a ->
            assertEquals(View.VISIBLE, a.findViewById<View>(R.id.no_email_panel).visibility)
            val shown = a.findViewById<TextView>(R.id.report_text).text.toString()
            assertTrue(shown.startsWith("To: bodhiarcade@gmail.com\nSubject: Snapslotify problem report"))
            assertTrue(a.findViewById<TextView>(R.id.no_email_message).text.contains("bodhiarcade@gmail.com"))
            a.findViewById<Button>(R.id.copy_report).performClick()
        }
        s.close()
        assertTrue(launched.isEmpty())
        // the screenshot copy is not kept when it could not be sent
        assertTrue(ReportCache.dir(context).listFiles().isNullOrEmpty())
    }

    @Test fun fromTheHomeScreenTheTogglesAreOff() {
        ReportSource.current = null
        val s = ActivityScenario.launch(ReportProblemActivity::class.java)
        s.onActivity { a ->
            assertFalse(a.findViewById<SwitchCompat>(R.id.attach_screenshot).isEnabled)
            assertFalse(a.findViewById<SwitchCompat>(R.id.include_text).isEnabled)
            assertFalse(a.findViewById<Button>(R.id.send).isEnabled)
            // each toggle keeps its one-line explanation in view
            assertEquals(View.VISIBLE, a.findViewById<View>(R.id.attach_screenshot_help).visibility)
            assertEquals(View.VISIBLE, a.findViewById<View>(R.id.include_text_help).visibility)
        }
        s.close()
    }
}
