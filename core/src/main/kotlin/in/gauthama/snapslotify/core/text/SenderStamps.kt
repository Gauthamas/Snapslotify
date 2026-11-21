package `in`.gauthama.snapslotify.core.text

/**
 * A chat that prints each message under a header of its sender, the date and the time ("Divya 24/01/2027 09:47", "you
 * o9/12/2026 11:17": forum and team apps). The header is the message's stamp, not an event: on a page with at least
 * [MIN_HEADERS] such lines, each line that holds only a short name (one to three words), a numeric date and a clock time
 * is taken out. Like other stamps it stays the page's own date ([TextCleaner.isStamp]): a date before it is past.
 */
object SenderStamps {

    const val MIN_HEADERS = 2
    const val RULE = "sender stamp"

    /** OCR reads a zero as o or O, and may split a year ("09/12/2 026"). */
    private const val D = "[0-9oO]"
    private val HEADER = Regex(
        """^\s*\p{L}[\p{L}'.\-]*(?:\s+\p{L}[\p{L}'.\-]*){0,2}\s+$D{1,2}\s?[/.\-]\s?$D{1,2}\s?[/.\-]\s?$D(?:\s?$D){1,3}\s*,?\s+$D{1,2}[:.]$D{2}(?:\s*[ap]\.?\s?m\.?)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun isHeader(text: String) = HEADER.matches(text)

    fun tidy(r: TextCleaner.Result): TextCleaner.Result {
        val headers = r.kept.filter { isHeader(it.text) }
        if (headers.size < MIN_HEADERS) return r
        val out = headers.toSet()
        return TextCleaner.Result(r.kept.filterNot { it in out }, r.removed + headers.map { TextCleaner.Removed(it, RULE, 0.0) }, r.looksLikeChat)
    }
}
