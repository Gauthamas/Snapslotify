package `in`.gauthama.snapslotify.core

/**
 * Title-selector screen (LLM measurement only): builds the prompts for schema T (title line id) and schema TDT (title line
 * id + date and time candidate ids). Written once, not tuned. The model only picks ids, it never writes text.
 *
 * Line listing, in priority order: the [TALLEST] tallest lines, then lines within 1 of a date/time candidate line (reading
 * order), then the rest in reading order; lines are added until the prompt reaches the cap. Line ids are reading-order
 * ids (1-based), so a listed id always names the same line. A case is SKIPPED (no call) when the fixed part, the candidate
 * list (TDT) and the tallest lines do not fit.
 */
object TitleSelectorPrompt {
    const val INSTRUCTION = "Identify and extract information matching the following schema. Return data as a JSON object. Missing data should be omitted."
    const val CAP = 220
    const val TALLEST = 5

    enum class Schema(val schema: String, val nPredict: Int) {
        T("{\"title_line_id\": \"integer, the id of the line that is the event title\"}", 24),
        TDT("{\"title_line_id\": \"integer, the id of the line that is the event title\", " +
            "\"date_choice\": \"string, the id of the event date candidate, for example D1\", " +
            "\"time_choice\": \"string, the id of the event start time candidate, for example T1\"}", 48),
    }

    data class Line(val id: Int, val text: String, val height: Int)
    data class Cand(val id: String, val raw: String, val label: String?, val lineIndex: Int)

    class Built(val user: String?, val listed: List<Int>, val tokens: Int, val skipped: String?)

    fun system(s: Schema) = "$INSTRUCTION\n${s.schema}\n"

    /** User-message pieces; each ends with one newline so token counts add up line by line. */
    const val LINES_HEADER = "Lines:\n"
    fun linePiece(l: Line) = "${l.id}: ${l.text}\n"
    fun candPiece(dates: List<Cand>, times: List<Cand>): String {
        fun one(c: Cand) = "${c.id}: ${c.raw}" + (c.label?.let { " ($it)" } ?: "") + "\n"
        return "Dates:\n" + (dates.joinToString("") { one(it) }.ifEmpty { "none\n" }) +
            "Times:\n" + (times.joinToString("") { one(it) }.ifEmpty { "none\n" })
    }

    /** Indices into [lines] in priority order (each once). */
    fun priority(lines: List<Line>, candLines: Collection<Int>): List<Int> {
        val tall = lines.indices.sortedWith(compareByDescending<Int> { lines[it].height }.thenBy { it }).take(TALLEST)
        val near = candLines.flatMap { listOf(it - 1, it, it + 1) }.filter { it in lines.indices }.toSortedSet()
        return (tall + near + lines.indices).distinct()
    }

    /**
     * [fixedTokens]: chat template + system message + [LINES_HEADER] as the runtime counts them (measured).
     * [count]: tokens of one piece.
     */
    fun fit(lines: List<Line>, dates: List<Cand>, times: List<Cand>, schema: Schema, fixedTokens: Int, count: (String) -> Int, cap: Int = CAP): Built {
        val order = priority(lines, (dates + times).map { it.lineIndex })
        val cand = if (schema == Schema.TDT) candPiece(dates, times) else ""
        var used = fixedTokens + (if (cand.isEmpty()) 0 else count(cand))
        val listed = mutableListOf<Int>()
        for ((n, i) in order.withIndex()) {
            val t = count(linePiece(lines[i]))
            if (used + t > cap) {
                if (n < minOf(TALLEST, lines.size)) return Built(null, emptyList(), used + t, "tallest lines do not fit")
                break
            }
            used += t
            listed += i
        }
        if (lines.isEmpty() && used > cap) return Built(null, emptyList(), used, "fixed part does not fit")
        val user = LINES_HEADER + listed.joinToString("") { linePiece(lines[it]) } + cand
        return Built(user, listed, used, null)
    }
}
