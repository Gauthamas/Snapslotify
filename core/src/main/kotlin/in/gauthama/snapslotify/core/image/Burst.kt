package `in`.gauthama.snapslotify.core.image

/** A short burst of frames of the same page: the one to keep. */
object Burst {

    /** Index of the sharpest frame by [Sharpness] score; the earliest of equals. Throws on an empty burst. */
    fun sharpest(scores: List<Double>): Int {
        require(scores.isNotEmpty()) { "empty burst" }
        var best = 0
        for (i in 1 until scores.size) if (scores[i] > scores[best]) best = i
        return best
    }
}
