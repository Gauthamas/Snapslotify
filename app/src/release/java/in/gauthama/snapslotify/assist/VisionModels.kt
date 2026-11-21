package `in`.gauthama.snapslotify.assist

import android.content.Context
import `in`.gauthama.snapslotify.core.vision.VisionModel

/** Release build: Try harder's model, when its two files are on the phone (no AI switch; that is a debug setting). */
object VisionModels {
    fun get(context: Context): VisionModel? = LlamaVisionModel.get(context)
}
