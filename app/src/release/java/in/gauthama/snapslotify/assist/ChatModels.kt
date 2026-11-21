package `in`.gauthama.snapslotify.assist

import android.content.Context
import `in`.gauthama.snapslotify.core.chat.ChatModel

/** Release build: the on-device chat model, when its file is on the phone (no AI switch; that is a debug setting). */
object ChatModels {
    fun get(context: Context): ChatModel? = LlamaChatModel.get(context)
}
