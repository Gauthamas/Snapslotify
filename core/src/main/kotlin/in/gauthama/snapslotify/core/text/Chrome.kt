package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.extract.Dates
import `in`.gauthama.snapslotify.core.extract.TimeParser
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrWord
import kotlin.math.abs

/**
 * An app's own screen furniture, found by position and shape (never by what it says): it is not the message, so it gives
 * no title, and a time in it (a sent time, a message stamp, the clock) is never an event's time. A time stated in the
 * message body is always kept.
 *
 * Every position is measured in the text's own frame (the box around all the lines), never the picture's: a phone
 * photographed on a table, or shown inside an advert, puts its screen anywhere in the picture. "Top", "bottom",
 * "centred" and "short" are shares of that frame; gaps are in line heights.
 *
 * - Status bar: a bare clock that is the topmost line of the text, in the top [STATUS_TOP] of its frame.
 * - Email header block: a short sender line (a name of up to four words with no digits, and exactly one time, at its
 *   end or in a time line to its right on the same row), right above a short line starting at the same left edge (the
 *   recipients: words, no time); both indented from the body's left margin (the sender's picture sits there). Only on a
 *   page that already shows a screen (a status-bar clock, or an icon bar in the top or bottom band): a poster's indented
 *   "name, time" over "place" is its content. Both lines go, and the time line with them.
 * - Trailing stamp: a time at the end of a line, set apart from the words before it by a wide gap or by smaller type,
 *   and within [STAMP_MINUTES] of another stamp on the page (a message's stamps run together; a time stated in a message
 *   stands apart from them): cut off; the rest of the line stays.
 *   This and the two other stamp rules apply only on a message app's screen (a chat, a status-bar clock or an email
 *   header block): on a poster or a programme a time at a line's end, or a small centred date line, is the content.
 * - Small stamp (chat pages): a line that is only a time, in type under [SMALL_STAMP] of the page's usual line height,
 *   within [STAMP_MINUTES] of another stamp: a message's stamp under it. A reply that is only a time ("8 pm?") is
 *   written at the message size and stays.
 * - Separator stamp: a small, short, centred line holding a date or day word and a time ("Today 5:59 PM"): the time is
 *   cut off; the day stays (it anchors relative days).
 * - Icon buttons and bars: in the top or bottom [BAND] of the page, a short line led by a one- or two-character icon
 *   glyph read as text ("+ Add", a search glass read as a letter), with no date or time, even counting a digit the glyph
 *   may have swallowed. Not in the body, where a message's own emoji lead its lines (a clock or a pin before the time
 *   or the place).
 * - Row stamp (chat pages): a line that is only a time, on the same row as message text to its left: that message's
 *   stamp; at most [ROW_STAMPS] on a page, or any number all within [ROW_STAMP_SPAN_MINUTES] (a programme's column of
 *   times runs across the day). A reply that is only a
 *   time ("7 pm?") has nothing beside it and stays.
 *
 * Icon buttons and the footer band are not the message, so they give no title; but their words still count for the
 * page's route (a "Pay bills" button says the page is a bill): [RouteScore] reads them from the removed lines.
 * - Footer band: short lines of words (no digits, no month or weekday) in the bottom [FOOTER_TOP] of the frame, below a gap of at least
 *   [FOOTER_GAP] line heights from everything above (tab bars, input bars, reply buttons). Only where the page shows an
 *   app's screen (a status-bar clock, an email header block or a chat), and never on a calendar grid: a poster's or a
 *   programme's last lines are its own.
 *
 * - Advert frame: a picture of a phone inside an advert. A bare clock in status-bar type (at most [STATUS_TYPE] of the
 *   usual height; also one the cleaner already took out as a stamp) in the top [FRAME_TOP] of the text, with only
 *   headline lines above it (type at least [HEADLINE] of the usual height) and a gap of [FRAME_GAP] line heights between
 *   them, is the phone's status bar; the headline lines are the advert's, not the message's. Both go, and the clock
 *   counts as the screen cue.
 * - Second copy of the screen: a picture of two screens side by side (a before and an after). When at least
 *   [COPY_PAIRS] lines appear twice, same words, same row, one in each of two columns, only the first column is read.
 * - An email header block makes the page an email: it is then not a chat ([TextCleaner.Result.looksLikeChat] false).
 *
 * A separate stage after [TextCleaner] (which stays the notebook's cleaner): [CodePipeline] runs it on the kept lines.
 */
object Chrome {

    const val STATUS_TOP = 0.12
    const val FOOTER_TOP = 0.80
    const val FOOTER_GAP = 3.0
    const val BAND = 0.20
    const val STAMP_MINUTES = 15
    const val FRAME_TOP = 0.40
    const val STATUS_TYPE = 0.8
    const val HEADLINE = 1.3
    const val FRAME_GAP = 2.0
    const val COPY_PAIRS = 3
    const val SMALL_STAMP = 0.85
    const val ROW_STAMPS = 2
    const val ROW_STAMP_SPAN_MINUTES = 60

    /** The rules whose lines still count for the route (their words describe the page, not the message). */
    val ROUTE_WORDS = setOf("icon button or bar", "footer band")

    /** A month or weekday name: a line holding one is part of a date (a day badge's "JAN"), never furniture. */
    private val DATE_WORD = `in`.gauthama.snapslotify.core.extract.Patterns.regex(
        """\b(?:${`in`.gauthama.snapslotify.core.extract.Patterns.MONTH}|${`in`.gauthama.snapslotify.core.extract.Patterns.WEEKDAY})\b""")

    private val BARE_CLOCK = Regex("""^\W*\d{1,2}[:.]\d{2}(?:\s?[ap]\.?m\.?)?\W*$""", RegexOption.IGNORE_CASE)

    class Out(val kept: List<OcrLine>, val removed: List<TextCleaner.Removed>)

    private fun hasDateOrTime(s: String) = Dates.findDates(OcrNormalizer.normalizeNumeric(s)).isNotEmpty() || TimeParser.parse(OcrNormalizer.normalizeNumeric(s)).isNotEmpty()
    private fun hasTime(s: String) = TimeParser.parse(OcrNormalizer.normalizeNumeric(s)).isNotEmpty()

    /** [cleaned] with the chrome taken out; the removed lines are added to its removed ones. */
    fun clean(cleaned: TextCleaner.Result, allLines: List<OcrLine>): TextCleaner.Result {
        if (cleaned.kept.isEmpty()) return cleaned
        val median = allLines.map { (it.bottom - it.top).toDouble() }.sorted().let { if (it.isEmpty()) 1.0 else it[it.size / 2] }.coerceAtLeast(1.0)
        // stamps the cleaner already took out still tell when this page's messages were sent
        val earlier = cleaned.removed.filter { it.rule.contains("timestamp") || it.rule.contains("status bar") }
            .mapNotNull { TimeParser.parse(OcrNormalizer.normalizeNumeric(it.line.text)).firstOrNull()?.start }
        val out = apply(cleaned.kept, median, cleaned.looksLikeChat, `in`.gauthama.snapslotify.core.layout.Layout.isMonthGrid(cleaned.kept), earlier,
            cleaned.removed.map { it.line })
        // an email's header block makes the page an email, not a chat
        val email = out.removed.any { it.rule.startsWith("email header") }
        return if (out.removed.isEmpty()) cleaned else TextCleaner.Result(out.kept, cleaned.removed + out.removed, cleaned.looksLikeChat && !email)
    }

    fun apply(
        lines: List<OcrLine>, median: Double, chat: Boolean = false, grid: Boolean = false, earlierStamps: List<java.time.LocalTime> = emptyList(),
        removedBefore: List<OcrLine> = emptyList(),
    ): Out {
        if (lines.isEmpty()) return Out(lines, emptyList())
        // the text's frame: where the screen's content is, wherever the screen sits in the picture
        val fx0 = lines.minOf { it.left }.toDouble(); val fx1 = lines.maxOf { it.right }.toDouble()
        val fy0 = lines.minOf { it.top }.toDouble(); val fy1 = lines.maxOf { it.bottom }.toDouble()
        val w = (fx1 - fx0).coerceAtLeast(1.0); val h = (fy1 - fy0).coerceAtLeast(1.0)
        val drop = mutableMapOf<Int, String>()
        val replace = mutableMapOf<Int, Pair<OcrLine, OcrLine>>()   // index -> (kept part, cut stamp)
        fun wd(l: OcrLine) = (l.right - l.left).toDouble()

        // a second copy of the screen side by side: read the first column only
        secondCopy(lines).forEach { drop[it] = "second copy of the screen" }

        // status bar: the topmost line, a bare clock, high on the page
        lines.indices.filter { it !in drop }.minByOrNull { lines[it].top }?.let { i ->
            val l = lines[i]
            if (BARE_CLOCK.matches(l.text.trim()) && l.bottom <= fy0 + STATUS_TOP * h) drop[i] = "status bar (topmost clock)"
        }
        // or the first clock under an advert's headline: the headline is the advert's frame, the clock the phone's
        if (drop.values.none { it.startsWith("status bar") }) {
            // the phone's clock is in status-bar type, well under the text's usual (a poster's own time is not); the cleaner
            // may have taken it out already (as a stamp): it still marks where the phone's screen starts
            fun isClock(l: OcrLine) = BARE_CLOCK.matches(l.text.trim()) && l.bottom <= fy0 + FRAME_TOP * h && (l.bottom - l.top) <= STATUS_TYPE * median
            val kept = lines.indices.filter { it !in drop && isClock(lines[it]) }.minByOrNull { lines[it].top }
            val gone = removedBefore.filter(::isClock).minByOrNull { it.top }
            val clock = listOfNotNull(kept?.let { lines[it] }, gone).minByOrNull { it.top }
            if (clock != null) {
                val above = lines.indices.filter { it !in drop && lines[it].bottom <= clock.top }
                val headline = above.isNotEmpty() && above.all { (lines[it].bottom - lines[it].top) >= HEADLINE * median } &&
                    clock.top - above.maxOf { lines[it].bottom } >= FRAME_GAP * median
                if (headline) {
                    above.forEach { drop[it] = "advert frame" }
                    if (kept != null && lines[kept] === clock) drop[kept] = "status bar (under an advert's frame)"
                }
            }
        }

        // icon buttons and bars, in the top or bottom band
        for (i in lines.indices) {
            if (i in drop) continue
            val l = lines[i]
            val toks = l.text.trim().split(Regex("""\s+"""))
            val first = toks.firstOrNull().orEmpty()
            val inBand = l.bottom <= fy0 + BAND * h || l.top >= fy1 - BAND * h
            // a glyph read as one or two characters; a number of two digits leading a line is a day, never an icon
            val glyph = inBand && toks.size in 2..5 && first.length <= 2 && !(first.length == 2 && first.all(Char::isDigit)) &&
                (first.any { !it.isLetter() } || (first.length == 1 && first !in setOf("A", "I", "a"))) && toks[1].firstOrNull()?.isUpperCase() == true
            if (glyph && toks.drop(1).joinToString(" ").none(Char::isDigit) && !hasDateOrTime(l.text) && !DATE_WORD.containsMatchIn(l.text)) drop[i] = "icon button or bar"
        }

        // email header blocks: indented sender + recipient lines, the sender with a time; only on a page showing a screen
        val long = lines.filter { wd(it) >= 0.5 * w }
        val screenCue = drop.values.any { it.startsWith("status bar") || it == "icon button or bar" || it == "second copy of the screen" || it == "advert frame" }
        if (long.size >= 2 && screenCue) {
            val margin = long.minOf { it.left }.toDouble()
            for (i in lines.indices) {
                val s = lines[i]
                if (i in drop || wd(s) > 0.6 * w || s.left < margin + 0.05 * w) continue
                val sTimes = TimeParser.parse(OcrNormalizer.normalizeNumeric(s.text))
                if (sTimes.size > 1) continue
                val name = sTimes.firstOrNull()?.let { t -> OcrNormalizer.normalizeNumeric(s.text).removeRange(t.startOffset, t.endOffset) } ?: s.text
                if (name.count(Char::isLetter) < 3 || name.any(Char::isDigit) || name.trim().split(Regex("""\s+""")).size > 4) continue
                val sh = (s.bottom - s.top).coerceAtLeast(1)
                val rowTime = lines.indices.firstOrNull { j ->
                    j != i && lines[j].left > s.right && minOf(lines[j].bottom, s.bottom) - maxOf(lines[j].top, s.top) >= 0.5 * sh &&
                        hasTime(lines[j].text) && TimeParser.parse(OcrNormalizer.normalizeNumeric(lines[j].text)).first().startOffset <= 2
                }
                val endsWithTime = TimeParser.parse(OcrNormalizer.normalizeNumeric(s.text)).lastOrNull()?.let { it.endOffset >= OcrNormalizer.normalizeNumeric(s.text).trimEnd().length - 1 } == true
                if (rowTime == null && !endsWithTime) continue
                val r = lines.indices.firstOrNull { j ->
                    val x = lines[j]
                    j != i && x.top >= s.bottom - sh / 2 && x.top - s.bottom <= 1.0 * sh && abs(x.left - s.left) <= 0.03 * w &&
                        wd(x) <= 0.6 * w && (x.bottom - x.top) <= 1.2 * sh && Dates.findDates(x.text).isEmpty() &&
                        x.text.count(Char::isLetter) >= 3 && !hasTime(x.text)
                } ?: continue
                drop[i] = "email header (sender)"; drop[r] = "email header (recipients)"
                rowTime?.let { drop[it] = "email header (sent time)" }
            }
        }

        // a message app's screen: a chat, a status-bar clock or an email header block. Stamps exist only there; on a
        // poster or a programme a time at a line's end or a small centred date line is the content
        val screen = chat || drop.values.any { it.startsWith("status bar") || it.startsWith("email header") }
        val rowCandidates = mutableListOf<Int>()
        val trailingCandidates = mutableMapOf<Int, Pair<OcrLine, OcrLine>>()
        for (i in lines.indices) {
            if (i in drop) continue
            val l = lines[i]
            val s = l.text.trim()
            val lh = (l.bottom - l.top).toDouble()
            val toks = s.split(Regex("""\s+"""))
            // row stamp on a chat page: a time alone, beside message text to its left on the same row
            if (!screen) continue
            if (chat && toks.size <= 3 && hasTime(s) && s.count(Char::isLetter) <= 4 && lines.indices.any { j ->
                    val x = lines[j]
                    j != i && x.right <= l.left && x.text.count(Char::isLetter) >= 3 && !hasTime(x.text) &&
                        minOf(x.bottom, l.bottom) - maxOf(x.top, l.top) >= 0.5 * minOf(lh, (x.bottom - x.top).toDouble())
                }) { rowCandidates += i; continue }
            // separator stamp: small, short, centred, a day and a time and nothing else
            val cx = (l.left + l.right) / 2.0
            if (lh <= 0.9 * median && wd(l) <= 0.5 * w && abs(cx - (fx0 + fx1) / 2) <= 0.08 * w && hasTime(s)) {
                val sep = cut(l, onlyDayLeft = true)
                if (sep != null) { replace[i] = sep; continue }
            }
            // trailing stamp: a time at the end, set apart by a gap or smaller type (kept to those near another stamp below)
            trailing(l)?.let { trailingCandidates[i] = it }
        }
        // a chat's stamps beside their messages: few, or all within an hour (a programme's times run across the day)
        val rowTimes = rowCandidates.mapNotNull { TimeParser.parse(OcrNormalizer.normalizeNumeric(lines[it].text)).firstOrNull()?.start?.toSecondOfDay() }
        val withinHour = rowTimes.size == rowCandidates.size && rowTimes.isNotEmpty() && (rowTimes.max() - rowTimes.min()) <= ROW_STAMP_SPAN_MINUTES * 60
        if (rowCandidates.size <= ROW_STAMPS || withinHour) rowCandidates.forEach { drop[it] = "message stamp (beside its message)" }
        // small stamps on a chat page: a time alone in small type, near another stamp in time
        if (chat) {
            // the clock with a stray mark after it taken off (a read tick: "7:25 PMx")
            fun clock(t: String) = t.trim().trimEnd { !it.isLetterOrDigit() }.dropLastWhile { it.isLetter() && it.uppercaseChar() !in "AMP" }
            val small = lines.indices.filter { i ->
                i !in drop && BARE_CLOCK.matches(clock(lines[i].text)) && (lines[i].bottom - lines[i].top) < SMALL_STAMP * median
            }
            val times = small.associateWith { TimeParser.parse(OcrNormalizer.normalizeNumeric(clock(lines[it].text))).firstOrNull()?.start }
            val known = earlierStamps + lines.indices.filter { drop[it]?.startsWith("message stamp") == true }
                .mapNotNull { TimeParser.parse(OcrNormalizer.normalizeNumeric(lines[it].text)).firstOrNull()?.start }
            for (i in small) {
                val t = times[i] ?: continue
                val others = known + times.filterKeys { it != i }.values.filterNotNull()
                if (others.any { minutesApart(it, t) <= STAMP_MINUTES }) drop[i] = "message stamp (small)"
            }
        }
        // a message's stamps run together: a trailing time is a stamp when another stamp is within STAMP_MINUTES of it
        val stampTimes = (lines.indices.filter { BARE_CLOCK.matches(lines[it].text.trim()) || drop[it]?.startsWith("message stamp") == true }
            .mapNotNull { TimeParser.parse(OcrNormalizer.normalizeNumeric(lines[it].text)).firstOrNull()?.start } +
            replace.values.mapNotNull { (_, st) -> TimeParser.parse(OcrNormalizer.normalizeNumeric(st.text)).firstOrNull()?.start } + earlierStamps)
        for ((i, pair) in trailingCandidates) {
            val t = TimeParser.parse(OcrNormalizer.normalizeNumeric(pair.second.text)).firstOrNull()?.start ?: continue
            val others = stampTimes + trailingCandidates.filterKeys { it != i }.values.mapNotNull { TimeParser.parse(OcrNormalizer.normalizeNumeric(it.second.text)).firstOrNull()?.start }
            if (others.any { o -> minutesApart(o, t) <= STAMP_MINUTES }) replace[i] = pair
        }

        // footer band: short undated lines low on the page, below a wide gap
        val footer = lines.indices.filter {
            it !in drop && lines[it].top >= fy0 + FOOTER_TOP * h && wd(lines[it]) <= 0.6 * w && !hasDateOrTime(lines[it].text) && !DATE_WORD.containsMatchIn(lines[it].text) &&
                // a bar's words, never a number (a day badge's "31", a phone number)
                lines[it].text.count(Char::isLetter) >= 3 && lines[it].text.none(Char::isDigit)
        }
        if (footer.isNotEmpty() && !grid && screen) {
            val top = footer.minOf { lines[it].top }
            val above = lines.indices.filter { it !in drop && it !in footer && lines[it].bottom <= top }.maxOfOrNull { lines[it].bottom }
            if (above != null && top - above >= FOOTER_GAP * median) footer.forEach { drop[it] = "footer band" }
        }

        val kept = mutableListOf<OcrLine>(); val removed = mutableListOf<TextCleaner.Removed>()
        for (i in lines.indices) {
            val l = lines[i]
            when {
                i in drop -> removed += TextCleaner.Removed(l, drop.getValue(i), (l.bottom - l.top) / median)
                i in replace -> { val (k, stamp) = replace.getValue(i); kept += k; removed += TextCleaner.Removed(stamp, "message stamp", (stamp.bottom - stamp.top) / median) }
                else -> kept += l
            }
        }
        return Out(kept, removed)
    }

    /**
     * The lines of a second screen beside the first: lines whose words appear twice on one row, in two columns apart.
     * With [COPY_PAIRS] or more such pairs, every line right of the split between the columns; else none.
     */
    private fun secondCopy(lines: List<OcrLine>): List<Int> {
        fun key(t: String) = t.lowercase().filter(Char::isLetterOrDigit)
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (i in lines.indices) for (j in lines.indices) {
            val a = lines[i]; val b = lines[j]
            val k = key(a.text)
            if (i == j || k.length < 4 || k != key(b.text) || a.right > b.left) continue
            val hh = maxOf(a.bottom - a.top, b.bottom - b.top)
            if (abs((a.top + a.bottom) - (b.top + b.bottom)) / 2.0 <= 1.5 * hh) pairs += i to j
        }
        if (pairs.size < COPY_PAIRS) return emptyList()
        val split = pairs.map { (i, j) -> (lines[i].right + lines[j].left) / 2.0 }.sorted().let { it[it.size / 2] }
        return lines.indices.filter { (lines[it].left + lines[it].right) / 2.0 > split }
    }

    private fun minutesApart(a: java.time.LocalTime, b: java.time.LocalTime): Int {
        val d = abs(a.toSecondOfDay() - b.toSecondOfDay()) / 60
        return minOf(d, 24 * 60 - d)
    }

    /**
     * A time at the end of [l] set apart from the words before it: a gap at least twice the line's own gaps between words
     * (and a character wide), or type under 0.85 of theirs: (rest, stamp).
     */
    private fun trailing(l: OcrLine): Pair<OcrLine, OcrLine>? {
        val ws = l.words.sortedBy { it.left }
        if (ws.size < 2) return null
        for (n in 1..minOf(2, ws.size - 1)) {
            val tail = ws.takeLast(n); val head = ws.dropLast(n)
            val tailText = tail.joinToString(" ") { it.text }
            if (!BARE_CLOCK.matches(tailText.trim())) continue
            if (head.none { it.text.any(Char::isLetter) }) return null
            val charW = (l.right - l.left).toDouble() / l.text.length.coerceAtLeast(1)
            val gap = tail.first().left - head.last().right
            val gaps = head.zipWithNext { a, b -> (b.left - a.right).toDouble() }.sorted()
            val usual = if (gaps.isEmpty()) charW else maxOf(gaps[gaps.size / 2], 1.0)
            val headH = head.map { it.bottom - it.top }.sorted()[head.size / 2].toDouble()
            val tailH = tail.maxOf { it.bottom - it.top }.toDouble()
            if ((gap >= 2 * usual && gap >= charW) || tailH < 0.85 * headH) return split(l, head, tail)
        }
        return null
    }

    /** [l] without its time (the part with the day kept); null unless what is left is only a day (when [onlyDayLeft]). */
    private fun cut(l: OcrLine, onlyDayLeft: Boolean): Pair<OcrLine, OcrLine>? {
        val norm = OcrNormalizer.normalizeNumeric(l.text)
        val t = TimeParser.parse(norm).firstOrNull() ?: return null
        // a separator gives its day first and its time last ("Today 5:59 PM", "Fri, Jul 7 at 10:50 AM")
        if (onlyDayLeft && norm.substring(t.endOffset).any(Char::isLetterOrDigit)) return null
        val rest = (l.text.substring(0, t.startOffset) + l.text.substring(t.endOffset)).replace(Regex("""\s+(?:at|@)\s*$""", RegexOption.IGNORE_CASE), "").trim()
        if (onlyDayLeft) {
            val dayOnly = Dates.findDates(rest).isNotEmpty() || Dates.REL_PHRASE.containsMatchIn(rest)
            if (!dayOnly) return null
            val leftover = Dates.REL_PHRASE.replace(rest, "").let { r -> Dates.findDates(r).fold(r) { acc, d -> acc.replace(d.raw, "") } }
            if (leftover.count(Char::isLetter) > 3) return null
        }
        val stamp = l.copy(text = norm.substring(t.startOffset, t.endOffset), words = emptyList())
        return l.copy(text = rest, words = l.words.filterNot { w -> TimeParser.parse(OcrNormalizer.normalizeNumeric(w.text)).isNotEmpty() || w.text.equals("PM", true) || w.text.equals("AM", true) }) to stamp
    }

    private fun split(l: OcrLine, head: List<OcrWord>, tail: List<OcrWord>): Pair<OcrLine, OcrLine> {
        val keep = l.copy(text = head.joinToString(" ") { it.text }, right = head.last().right, words = head)
        val stamp = l.copy(text = tail.joinToString(" ") { it.text }, left = tail.first().left, words = tail)
        return keep to stamp
    }
}
