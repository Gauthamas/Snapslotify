package `in`.gauthama.snapslotify.review

import `in`.gauthama.snapslotify.core.image.Sharpness
import kotlinx.coroutines.CompletableDeferred

/**
 * After OCR: a photo that is blurry (by [Sharpness]) and read weakly (`OcrQuality.weak`) waits for the user's choice,
 * Retake or Use anyway; a sharp one, or a blurry one that still read well, goes on at once. Plain Kotlin, so the choice
 * can be tested without a device.
 */
class BlurGate {

    enum class Choice { PROCEED, RETAKE }

    private var pending: CompletableDeferred<Choice>? = null

    /** Whether the user is being asked right now. */
    val asking: Boolean get() = pending?.isActive == true

    /**
     * PROCEED without asking for a sharp image or one whose read is not [weak]; for a blurry image read weakly, [ask] is
     * called and the user's choice is awaited.
     */
    suspend fun check(score: Double, weak: Boolean = true, ask: () -> Unit): Choice {
        if (!Sharpness.isBlurry(score) || !weak) return Choice.PROCEED
        val d = CompletableDeferred<Choice>()
        pending = d
        ask()
        return d.await()
    }

    fun retake() { pending?.complete(Choice.RETAKE) }

    fun useAnyway() { pending?.complete(Choice.PROCEED) }
}
