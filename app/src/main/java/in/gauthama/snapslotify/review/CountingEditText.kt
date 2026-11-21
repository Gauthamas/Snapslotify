package `in`.gauthama.snapslotify.review

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * An EditText that says when the keyboard's batch of changes starts and ends (TextView calls these for the outermost
 * batch only), so the taps counter can diff a whole keyboard action once ([in.gauthama.snapslotify.stats.TypingCounter]).
 */
class CountingEditText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.editTextStyle) :
    AppCompatEditText(context, attrs, defStyleAttr) {

    /** true when a batch starts, false when it ends. */
    var onBatch: ((Boolean) -> Unit)? = null

    override fun onBeginBatchEdit() {
        super.onBeginBatchEdit()
        onBatch?.invoke(true)
    }

    override fun onEndBatchEdit() {
        super.onEndBatchEdit()
        onBatch?.invoke(false)
    }
}
