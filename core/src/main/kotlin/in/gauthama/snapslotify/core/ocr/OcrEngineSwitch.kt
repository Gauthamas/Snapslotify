package `in`.gauthama.snapslotify.core.ocr

enum class OcrEngineKind(val engineName: String) {
    MLKIT("mlkit"), PADDLE("paddle");

    companion object {
        val DEFAULT = MLKIT

        /** A stored name back to the engine. Anything unknown is the default. */
        fun of(name: String?): OcrEngineKind = entries.firstOrNull { it.engineName == name } ?: DEFAULT
    }
}

/**
 * Holds the one OCR engine in use, chosen by [selected] each time it is asked for, so a change applies to the next share
 * with no rebuild. When the choice changes, the old engine is closed before the new one is made, so two models are never in
 * memory together. An engine with no factory, as in a build without that runtime, falls back to the default.
 */
class OcrEngineSwitch<I : OcrImage>(
    private val factories: Map<OcrEngineKind, () -> OcrEngine<I>>,
    private val selected: () -> OcrEngineKind,
) {
    private var activeKind: OcrEngineKind? = null
    private var active: OcrEngine<I>? = null

    @Synchronized
    fun current(): OcrEngine<I> {
        val wanted = selected().takeIf { it in factories } ?: OcrEngineKind.DEFAULT
        active?.takeIf { wanted == activeKind }?.let { return it }
        runCatching { active?.close() }
        active = null
        return factories.getValue(wanted)().also {
            active = it
            activeKind = wanted
        }
    }
}
