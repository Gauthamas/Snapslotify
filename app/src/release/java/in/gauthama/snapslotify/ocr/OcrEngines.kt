package `in`.gauthama.snapslotify.ocr

import `in`.gauthama.snapslotify.core.ocr.OcrEngine
import `in`.gauthama.snapslotify.core.ocr.OcrEngineKind

/** Release build: ML Kit only. No other engine, runtime or model is in this build, and nothing exposes a choice. */
object OcrEngines {
    fun factories(): Map<OcrEngineKind, () -> OcrEngine<BitmapOcrImage>> = mapOf(OcrEngineKind.MLKIT to { MlKitOcrEngine() })

    fun selected(): OcrEngineKind = OcrEngineKind.MLKIT
}
