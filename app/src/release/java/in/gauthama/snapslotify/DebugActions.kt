package `in`.gauthama.snapslotify

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import android.content.Intent
import android.net.Uri
import `in`.gauthama.snapslotify.core.pipeline.PipelineResult
import org.json.JSONObject

/** Release build: no debug tools (the real one is in src/debug). Nothing is written. */
object DebugActions {

    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    fun recordReview(context: Context, line: () -> JSONObject) = Unit

    /** Release build: no debug buttons, and no raw OCR view. */
    @Suppress("UNUSED_PARAMETER")
    fun addReviewButtons(activity: Activity, row: ViewGroup, ocr: () -> OcrResult?, engine: () -> String) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun saveTestCase(context: Context, image: Uri?, ocrJson: String, result: PipelineResult): Intent? = null
}
