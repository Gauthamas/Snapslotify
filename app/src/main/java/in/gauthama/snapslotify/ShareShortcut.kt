package `in`.gauthama.snapslotify

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Sharing shortcut, so Snapslotify can show in the share sheet's direct-share row for images. Matches the share-target
 * in res/xml/shortcuts.xml by [CATEGORY]; a share through it arrives at ReviewActivity as a normal ACTION_SEND.
 * Direct share from shortcuts works on Android 10 and up; older versions keep the plain app entry.
 */
object ShareShortcut {
    const val ID = "share_image"
    const val CATEGORY = "in.gauthama.snapslotify.category.SHARE_IMAGE"

    /** Publishes (or refreshes) the dynamic shortcut. Never throws: the share sheet entry is a convenience. */
    fun publish(context: Context) {
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, ID)
                .setShortLabel(context.getString(R.string.app_name))
                .setLongLabel(context.getString(R.string.share_shortcut_long))
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                .setCategories(setOf(CATEGORY))
                .setLongLived(true)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    /** An image was shared in: tells the system, which ranks the shortcut higher in the direct-share row. */
    fun reportUsed(context: Context) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, ID) }
    }
}
