package `in`.gauthama.snapslotify.spike

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import `in`.gauthama.snapslotify.assist.AssistSwitch
import `in`.gauthama.snapslotify.assist.LlamaVisionModel
import `in`.gauthama.snapslotify.core.ocr.OcrEngineKind
import `in`.gauthama.snapslotify.ocr.OcrEngines

/**
 * Debug builds only: debug settings. For now the OCR engine, which applies to the next share, with no rebuild.
 *   adb shell am start -n in.gauthama.snapslotify/.spike.DebugSettingsActivity
 * `--es ai off|on` sets the AI switch before the screen shows (for scripted comparisons).
 */
class DebugSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra("ai")) { "off" -> AssistSwitch.set(this, false); "on" -> AssistSwitch.set(this, true) }
        // --ez quiet true: set and close at once, so a scripted switch leaves the screen and the app process as they were
        if (intent.getBooleanExtra("quiet", false)) { finish(); overridePendingTransition(0, 0); return }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val group = RadioGroup(this).apply { tag = "ocr_engine_group" }
        for (kind in OcrEngineKind.entries) {
            group.addView(RadioButton(this).apply {
                id = kind.ordinal + 1
                text = when (kind) {
                    OcrEngineKind.MLKIT -> "ML Kit (default)"
                    OcrEngineKind.PADDLE -> "PaddleOCR (PP-OCRv5 mobile, ONNX Runtime)"
                }
                tag = "ocr_engine_${kind.engineName}"
                isChecked = OcrEngines.selected() == kind
            })
        }
        group.setOnCheckedChangeListener { _, checkedId -> OcrEngines.select(OcrEngineKind.entries[checkedId - 1]) }
        val vl = RadioGroup(this).apply { tag = "vl_model_group" }
        for (v in LlamaVisionModel.Variant.entries) {
            vl.addView(RadioButton(this).apply {
                id = 100 + v.ordinal
                text = when (v) {
                    LlamaVisionModel.Variant.TUNED -> "Tuned LFM2.5-VL 450M, posters and chat (default)"
                    LlamaVisionModel.Variant.BASE -> "Base LFM2.5-VL 450M"
                }
                tag = "vl_model_${v.key}"
                isChecked = LlamaVisionModel.selected(this@DebugSettingsActivity) == v
            })
        }
        vl.setOnCheckedChangeListener { _, checkedId -> LlamaVisionModel.select(this, LlamaVisionModel.Variant.entries[checkedId - 100]) }
        val ai = android.widget.CheckBox(this).apply {
            text = "AI on: chat model chips and try harder (off = code result only, as in release)"
            tag = "ai_enabled"
            isChecked = AssistSwitch.on(this@DebugSettingsActivity)
            setOnCheckedChangeListener { _, on -> AssistSwitch.set(this@DebugSettingsActivity, on) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(pad, pad, pad, pad)
            addView(ai)
            addView(TextView(this@DebugSettingsActivity).apply { text = "OCR engine" ; textSize = 20f })
            addView(TextView(this@DebugSettingsActivity).apply { text = "Applies to the next share. The engine not chosen is freed when the next share starts." })
            addView(group)
            addView(TextView(this@DebugSettingsActivity).apply { text = "Try harder model"; textSize = 20f; setPadding(0, pad, 0, 0) })
            addView(TextView(this@DebugSettingsActivity).apply {
                text = "Applies to the next try harder. Without the chosen model's file on the phone, or under 3 GB of RAM, try harder is not offered."
            })
            addView(vl)
        })
    }
}
