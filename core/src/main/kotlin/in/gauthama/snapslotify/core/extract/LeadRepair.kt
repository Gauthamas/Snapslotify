package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.extract.Patterns.AMPM
import `in`.gauthama.snapslotify.core.extract.Patterns.MONTH
import `in`.gauthama.snapslotify.core.extract.Patterns.WEEKDAY
import `in`.gauthama.snapslotify.core.extract.Patterns.regex
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.text.OcrNormalizer
import `in`.gauthama.snapslotify.core.verify.Flag
import java.time.LocalDate

/**
 * OCR damage at the front of a number: an icon or a stray mark read as one or two extra characters stuck to the front
 * of a day or an hour ("710th August, Sunday" for a calendar icon before "10th"; "94 PM" for a clock icon before "4 PM").
 *
 * A number is repaired only when, as read, it is no valid day or hour, and:
 * - taking one or two characters off its front leaves exactly one valid reading ([candidates]); two possible repairs
 *   ("112 PM": 12 PM or 2 PM) are never chosen between;
 * - for a day: a weekday printed on the same line fits the repaired date in a year near the page's date (one before to
 *   one after, or the printed year), and fits no other repair;
 * - for an hour: a date with a printed weekday that fits sits on the same line or the line before or after.
 * The stray characters are blanked (the text keeps its length, so every offset stays valid), and the field the repaired
 * number ends up in carries [CODE] ([flags]). It runs on the OCR lines before anything else reads them ([repairLines]),
 * so a repaired chat date line ("Sat, 731 Oct 2026") is a date line to every later stage.
 *
 * A digit lost from the front ("1/29" read for "11/29") is not repaired: what was read is itself a valid date.
 */
object LeadRepair {

    const val CODE = "stray_mark_removed"

    /** [start, end): the repaired number in the text, after the blanked stray characters; [field] DATE or TIME. */
    data class Repair(val field: String, val start: Int, val end: Int, val stray: Int)

    private val DAY_MONTH = regex("""(?<![\w./:-])(\d{3,4})(st|nd|rd|th)?(\s+(?:of\s+)?)($MONTH)\b""")
    private val MONTH_DAY = regex("""\b($MONTH)\.?(\s+)(\d{3,4})(st|nd|rd|th)?(?![\w/:-])""")
    private val TIME = regex("""(?<![\w./:-])(\d{2,3})((?:[:.]\d{2})?\s*$AMPM)""")
    private val WEEKDAY_WORD = regex("""\b($WEEKDAY)\b\.?""")
    private val YEAR = regex("""\b(20\d{2})\b""")

    /** A repaired number on one line: [start, end) in the line's text. */
    data class LineRepair(val field: String, val start: Int, val end: Int)

    /** A line's place, which every later stage keeps (a stamp cut off its end changes only its right edge). */
    fun key(l: OcrLine) = listOf(l.left, l.top, l.bottom)

    /**
     * The OCR lines with stray leading characters blanked in their text (and taken off their words), and the repairs by
     * line ([key]). Read on all lines in order, the numbers normalised for reading ([OcrNormalizer]).
     */
    fun repairLines(lines: List<OcrLine>, anchor: LocalDate?): Pair<List<OcrLine>, Map<List<Int>, List<LineRepair>>> {
        if (anchor == null || lines.isEmpty() || lines.any { '\n' in it.text }) return lines to emptyMap()
        val joined = lines.joinToString("\n") { it.text }
        val (_, reps) = repair(OcrNormalizer.normalizeNumeric(joined), anchor)
        if (reps.isEmpty()) return lines to emptyMap()
        val starts = lines.runningFold(0) { at, l -> at + l.text.length + 1 }
        val out = lines.toMutableList()
        val byLine = mutableMapOf<List<Int>, MutableList<LineRepair>>()
        for (r in reps) {
            val li = starts.indexOfLast { it <= r.start - r.stray }
            val l = out[li]
            val s = r.start - r.stray - starts[li]
            val text = l.text.substring(0, s) + " ".repeat(r.stray) + l.text.substring(s + r.stray)
            // the word holding the stray characters loses them
            var at = 0
            val words = l.words.map { w ->
                val i = l.text.indexOf(w.text, at).takeIf { it >= 0 } ?: return@map w
                at = i + w.text.length
                if (s >= i && s + r.stray <= i + w.text.length) w.copy(text = w.text.removeRange(s - i, s - i + r.stray)) else w
            }
            out[li] = l.copy(text = text, words = words)
            byLine.getOrPut(key(l)) { mutableListOf() } += LineRepair(r.field, r.start - starts[li], r.end - starts[li])
        }
        return out to byLine
    }

    /** The text with stray leading characters blanked, and what was repaired. [anchor]: the page's date (shot or today). */
    fun repair(text: String, anchor: LocalDate?): Pair<String, List<Repair>> {
        if (anchor == null) return text to emptyList()
        val chars = text.toCharArray()
        val out = mutableListOf<Repair>()
        val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
        fun lineOf(at: Int) = lineStarts.indexOfLast { it <= at }
        fun lineText(i: Int, src: String) = src.substring(lineStarts[i], (lineStarts.getOrNull(i + 1)?.minus(1)) ?: src.length)

        // days: before or after a month name
        for ((m, digitsGroup, monthGroup) in DAY_MONTH.findAll(text).map { Triple(it, 1, 4) } + MONTH_DAY.findAll(text).map { Triple(it, 3, 1) }) {
            val g = m.groups[digitsGroup]!!
            val month = Patterns.monthNumber(m.groups[monthGroup]!!.value) ?: continue
            val line = lineText(lineOf(g.range.first), text)
            val weekdays = WEEKDAY_WORD.findAll(line).mapNotNull { Patterns.weekday(it.groupValues[1]) }.toSet()
            if (weekdays.size != 1) continue
            val years = YEAR.find(line)?.groupValues?.get(1)?.toInt()?.let { listOf(it) } ?: (anchor.year - 1..anchor.year + 1).toList()
            val fits = candidates(g.value).filter { (_, day) ->
                years.any { y -> runCatching { LocalDate.of(y, month, day) }.getOrNull()?.dayOfWeek in weekdays }
            }
            if (fits.size != 1) continue
            val (drop, _) = fits.single()
            for (k in 0 until drop) chars[g.range.first + k] = ' '
            out += Repair("DATE", g.range.first + drop, g.range.last + 1, drop)
        }

        // hours: next to a date whose printed weekday fits
        val repaired = String(chars)
        for (m in TIME.findAll(repaired)) {
            val g = m.groups[1]!!
            val read = g.value.toInt()
            if (g.value.length == 2 && read <= 23) continue
            val fits = (1..g.value.length - 1).mapNotNull { drop -> g.value.drop(drop).toIntOrNull()?.takeIf { it in 1..12 }?.let { drop } }
            if (fits.size != 1) continue
            val li = lineOf(g.range.first)
            val company = listOf(li - 1, li, li + 1).filter { it in lineStarts.indices }.any { datedWeekdayFits(lineText(it, repaired), anchor) }
            if (!company) continue
            val drop = fits.single()
            for (k in 0 until drop) chars[g.range.first + k] = ' '
            out += Repair("TIME", g.range.first + drop, g.range.last + 1, drop)
        }
        return String(chars) to out
    }

    /** Each way to take one or two characters off the front of [digits] leaving a valid day: (characters taken, day). */
    private fun candidates(digits: String): List<Pair<Int, Int>> =
        (1..minOf(2, digits.length - 1)).mapNotNull { drop -> digits.drop(drop).toIntOrNull()?.takeIf { it in 1..31 }?.let { drop to it } }

    /** A date on [line] with a printed weekday that fits it in a year near [anchor] (or its printed year). */
    private fun datedWeekdayFits(line: String, anchor: LocalDate): Boolean {
        val wd = WEEKDAY_WORD.findAll(line).mapNotNull { Patterns.weekday(it.groupValues[1]) }.toSet()
        if (wd.size != 1) return false
        return DateParser.parse(line).any { d ->
            val years = d.year?.let { listOf(it) } ?: (anchor.year - 1..anchor.year + 1).toList()
            years.any { y -> runCatching { LocalDate.of(y, d.month, d.day) }.getOrNull()?.dayOfWeek in wd }
        }
    }

    /** [CODE] on the event's date and time when the candidate they came from holds a repaired number. */
    fun flags(ev: RawEvent, c: Candidates): List<Flag> = buildList {
        fun hit(field: String, s: Int, e: Int) = c.repairs.any { it.field == field && it.start < e && it.end > s }
        fun one(s: String) = s.replace(Regex("""\s+"""), " ").trim()
        if (ev.date != null && c.dates.any { one(it.raw) == ev.date && hit("DATE", it.offset, it.end) }) add(Flag(CODE, "DATE"))
        if (ev.time != null && c.times.any { (one(it.raw) == ev.time || one(it.raw).trimEnd('.') == ev.time) && hit("TIME", it.offset, it.endOffset) }) add(Flag(CODE, "TIME"))
    }
}
