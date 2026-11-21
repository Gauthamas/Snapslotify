package `in`.gauthama.snapslotify.assist

import android.content.Context
import `in`.gauthama.snapslotify.core.vision.VisionModel

/** Debug build: try harder's model, when its two files are on the phone. Release uses the same model without the switch. */
object VisionModels {
    fun get(context: Context): VisionModel? = if (AssistSwitch.on(context)) LlamaVisionModel.get(context) else null
}
