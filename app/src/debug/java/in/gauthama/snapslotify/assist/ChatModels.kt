package `in`.gauthama.snapslotify.assist

import android.content.Context
import `in`.gauthama.snapslotify.core.chat.ChatModel

/** Debug build: the on-device model, when its file is on the phone. Release uses the same model without the switch. */
object ChatModels {
    fun get(context: Context): ChatModel? = if (AssistSwitch.on(context)) LlamaChatModel.get(context) else null
}
