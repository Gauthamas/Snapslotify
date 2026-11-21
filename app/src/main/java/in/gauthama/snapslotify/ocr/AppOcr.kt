package `in`.gauthama.snapslotify.ocr

import `in`.gauthama.snapslotify.core.ocr.OcrEngine
import `in`.gauthama.snapslotify.core.ocr.OcrEngineSwitch
import `in`.gauthama.snapslotify.core.ocr.OcrTimings

/**
 * One engine at a time per process: ML Kit unless a debug build chooses another. Asking for [engine] picks up a changed
 * choice and releases the engine no longer in use. Timings start again with each engine, so "cold" is its first call.
 */
object AppOcr {
    private val switch = OcrEngineSwitch(OcrEngines.factories(), OcrEngines::selected)
    private var timedEngine: OcrEngine<BitmapOcrImage>? = null
    private var currentTimings = OcrTimings()

    /** Call off the main thread: a switch may wait for the old engine to finish a call before freeing it. */
    val engine: OcrEngine<BitmapOcrImage>
        @Synchronized get() = switch.current().also {
            if (it !== timedEngine) {
                timedEngine = it
                currentTimings = OcrTimings()
            }
        }

    val timings: OcrTimings
        @Synchronized get() = currentTimings
}
