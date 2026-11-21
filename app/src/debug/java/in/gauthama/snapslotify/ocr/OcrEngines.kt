package `in`.gauthama.snapslotify.ocr

import android.content.Context
import `in`.gauthama.snapslotify.core.ocr.OcrEngine
import `in`.gauthama.snapslotify.core.ocr.OcrEngineKind

/**
 * Debug build: the engines on offer and the one chosen on the debug settings screen. The choice is read for every share, so
 * a change applies to the next share, with no rebuild. The release build offers ML Kit only (src/release).
 */
object OcrEngines {
    private const val PREFS = "debug_settings"
    private const val KEY = "ocr_engine"
    private var app: Context? = null

    /** Set once at process start by the debug share-log provider. */
    fun init(context: Context) {
        app = context.applicationContext
    }

    fun factories(): Map<OcrEngineKind, () -> OcrEngine<BitmapOcrImage>> = mapOf(
        OcrEngineKind.MLKIT to { MlKitOcrEngine() },
        OcrEngineKind.PADDLE to { PaddleOcrEngine(checkNotNull(app) { "not initialised" }) },
    )

    fun selected(): OcrEngineKind = OcrEngineKind.of(app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY, null))

    fun select(kind: OcrEngineKind) {
        checkNotNull(app).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, kind.engineName).apply()
    }
}
