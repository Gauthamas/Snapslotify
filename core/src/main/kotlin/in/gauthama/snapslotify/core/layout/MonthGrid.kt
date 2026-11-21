package `in`.gauthama.snapslotify.core.layout

import `in`.gauthama.snapslotify.core.extract.Patterns.MONTH
import `in`.gauthama.snapslotify.core.extract.Patterns.WEEKDAY
import `in`.gauthama.snapslotify.core.extract.Patterns.regex
import `in`.gauthama.snapslotify.core.extract.RawEvent
import `in`.gauthama.snapslotify.core.extract.TimeParser
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.text.OcrNormalizer
import `in`.gauthama.snapslotify.core.verify.DigitConfidence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Reads month calendar grids by position. A page may hold more than one grid; each starts at a row of weekday labels.
 *
 * - A photo of a slightly turned page (or one in perspective) is first put square by the weekday names' own tilt and
 *   the day numbers' column lean ([level]); a level page is read as it is.
 * - Labels: weekday names ("Mon", "WED", OCR's "VWED"), or single letters (S M T W T F S) when five or more sit in one
 *   row; a line holding several labels ("TUE WED THU") is split into words (the OCR's word boxes, else by character
 *   position). Five of the seven labels are enough: their spacing gives the columns.
 * - Month and year: the nearest month name above the labels (alone, or with the year), and the year from the same line or
 *   the nearest year-only line ("2026", OCR's "2o26"), else anywhere on the page. No year: the year (of last, this or the
 *   next two) whose layout fits the day numbers, nearest to today; the date is then written without its year, so it is
 *   flagged as such.
 * - Part of a month (a week or more): the dates printed above the labels give the month and the days shown ("6 - 14
 *   December 2026", "December 6-14", "28 Nov - 6 Dec 2026", which runs into the next month); cells outside those dates
 *   give no event. A single row of days is taken as cells [ONE_ROW_PITCH] columns tall.
 * - Day numbers: lines that are only a number, in any corner of a cell. The week start (Sunday or Monday first, from the
 *   labels and the room left of them) and the year are the ones under which most numbers fall on their right weekday; the
 *   numbers that do not (neighbouring-month days, a week-number column, misreads) are left out. A grid is clean when at
 *   least [MIN_AGREEING] numbers, and at least [MIN_AGREEING_SHARE] of them, agree, and the labels fit that week start.
 * - Cells: a cell's date comes from its row and column (rows from the day-number rows), so a number the OCR missed does
 *   not matter. Cells of the neighbouring months give no event.
 * - Events: the lines in a cell, top to bottom. A line that starts with a time starts an entry ("10am Blood"); a line
 *   that is only a time is the time of the entry above; any other line just under an entry with no time yet continues
 *   its title ("Anna's" / "birthday"). A line holding words far apart is split where a cell boundary would be; with no
 *   word boxes, a line wider than a cell gives no event.
 *
 * Returns null when no grid on the page is clean (no guess); else the events of the clean grids (maybe none). Every
 * event is marked [RawEvent.grid], so its date is flagged for the user to confirm.
 */
object MonthGrid {

    const val MIN_AGREEING = 7
    const val MIN_AGREEING_SHARE = 0.6
    const val MIN_LABELS = 5

    /** A grid photographed turned between these (degrees) is levelled before it is read; less is read as it is. */
    const val MIN_TILT_DEGREES = 1.0
    const val MAX_TILT_DEGREES = 15.0

    /** A single row of days (a week): its cells taken as this many columns tall. */
    const val ONE_ROW_PITCH = 1.5

    /** A printed range of dates above a grid spans at most this many days (a few weeks, not a season). */
    const val MAX_RANGE_DAYS = 42

    /** Two day numbers less than this share of a column apart sideways are taken to be in the same column. */
    const val CELL_SLACK = 0.35

    private val DAY_NUMBER = Regex("""^\W*([1-9]|[12]\d|3[01])\W*$""")
    private val WEEKDAY_WORD = regex("""^\W*[a-z]?($WEEKDAY)\W*$""")
    private val LETTER = regex("""^\W*([smtwf])\W*$""")
    /** A month name, maybe with its year; a lone letter beside it is an arrow or icon read as one ("O May", "May 2027 >"). */
    private val MONTH_LINE = regex("""^\W*(?:\p{L}\W+)?($MONTH)\.?(?:\s*,?\s*([0-9oO]{4}))?(?:\W+\p{L})?\W*$""")
    private val YEAR_LINE = Regex("""^\W*(2[0oO][0-9oO]{2})\W*$""")
    private val DAYS = listOf("mo", "tu", "we", "th", "fr", "sa", "su")

    /** A word with its box: the OCR's word, or a whitespace token placed by its character position in the line. */
    private class Tok(val text: String, val left: Double, val top: Int, val right: Double, val bottom: Int, val line: Int) {
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2.0
    }

    private class LabelRow(val toks: List<Tok>, val idx: List<Int>, val base: Double, val firstDay: DayOfWeek) {
        val top = toks.minOf { it.top }
        val bottom = toks.maxOf { it.bottom }
        val lines = toks.map { it.line }.toSet()
    }

    /** True when the page holds a row of weekday labels (used with the day-number count by [Layout.isMonthGrid]). */
    fun hasLabelRow(lines: List<OcrLine>): Boolean = labelRows(level(lines)).isNotEmpty()

    /** Positions are read with the labels' tilt taken out ([labelTilt]): a photo of a slightly turned page reads as printed. */
    fun read(lines: List<OcrLine>, today: LocalDate? = null): List<RawEvent>? = readLevel(level(numeric(lines)), today)

    /** The most weekday labels found in one label row (0: none). */
    fun labelCount(lines: List<OcrLine>): Int = labelRows(level(lines)).maxOfOrNull { it.toks.size } ?: 0

    private val LOOK_ALIKE = Regex("""^(\W*)([0-9OoIl|Ss]{1,2})(\W*)$""")

    /**
     * Day numbers with look-alike letters read as digits (O 0, I l | 1, S 5) when the token also holds a real digit
     * ("1O", "2S", "l5"): a lone letter stays a letter (it may be a weekday label). Other lines are unchanged.
     */
    fun numeric(lines: List<OcrLine>): List<OcrLine> = lines.map { l ->
        val m = LOOK_ALIKE.matchEntire(l.text) ?: return@map l
        val core = m.groupValues[2]
        if (core.none(Char::isDigit) || core.all(Char::isDigit)) return@map l
        val fixed = core.map { ch -> when (ch) { 'O', 'o' -> '0'; 'I', 'l', '|' -> '1'; 'S', 's' -> '5'; else -> ch } }.joinToString("")
        val t = m.groupValues[1] + fixed + m.groupValues[3]
        if (!DAY_NUMBER.matches(t)) l
        else l.copy(text = t, words = if (l.words.size == 1) listOf(l.words[0].copy(text = t.trim())) else l.words)
    }

    /**
     * The grid put square in a photo of a turned page: rows levelled by the labels' tilt ([labelTilt]), each box moved
     * up or down by its distance along the row; then columns stood upright by their own lean ([columnLean]), each box
     * moved left or right by its height below the labels. Measured apart because a page in perspective tilts its rows and
     * its columns by different amounts. Sizes are kept; text and order are unchanged.
     */
    private fun level(lines: List<OcrLine>): List<OcrLine> {
        val k = labelTilt(lines)?.let { tan(Math.toRadians(it)) }
        val rows = if (k == null) lines else lines.map { l ->
            fun dy(a: Int, b: Int) = (k * (a + b) / 2).roundToInt()
            val d = dy(l.left, l.right)
            l.copy(top = l.top - d, bottom = l.bottom - d, words = l.words.map { w -> val e = dy(w.left, w.right); w.copy(top = w.top - e, bottom = w.bottom - e) })
        }
        // columns lean in a turned photo even when its rows arrive level ([PageBend] levels rows, not columns)
        val lean = columnLean(rows) ?: return rows
        return rows.map { l ->
            fun dx(a: Int, b: Int, t: Int, u: Int) = lean.shift((a + b) / 2.0, (t + u) / 2.0).roundToInt()
            val d = dx(l.left, l.right, l.top, l.bottom)
            l.copy(left = l.left - d, right = l.right - d, words = l.words.map { w -> val e = dx(w.left, w.right, w.top, w.bottom); w.copy(left = w.left - e, right = w.right - e) })
        }
    }

    /**
     * How the columns lean: sideways pixels per pixel down, [atLeft] at x = [left] changing by [perPixel] across the page
     * (a page in perspective fans its columns out), counted from the labels' row at [labelsY], which stays put.
     */
    private class Lean(val left: Double, val atLeft: Double, val perPixel: Double, val labelsY: Double) {
        fun shift(x: Double, y: Double) = (atLeft + perPixel * (x - left)) * (y - labelsY)
    }

    /**
     * The columns' lean, from day numbers in the same column of nearby rows: pairs at least half a column apart down the
     * page and less than [CELL_SLACK] of a column apart across it. The left and right halves of the pairs (by position)
     * each give a median lean; the lean runs straight between them. Null with fewer than [MIN_AGREEING] pairs.
     */
    private fun columnLean(lines: List<OcrLine>): Lean? {
        val row = labelRows(lines).firstOrNull() ?: return null
        val base = row.base
        val nums = lines.filter { DAY_NUMBER.matches(it.text) }
        val pairs = mutableListOf<Pair<Double, Double>>()   // (x, lean)
        for (a in nums) for (b in nums) {
            val dy = (b.top + b.bottom - a.top - a.bottom) / 2.0
            val dx = (b.left + b.right - a.left - a.right) / 2.0
            if (dy >= base / 2 && abs(dx) < CELL_SLACK * base) pairs += (a.left + a.right + b.left + b.right) / 4.0 to dx / dy
        }
        if (pairs.size < MIN_AGREEING) return null
        val labelsY = (row.top + row.bottom) / 2.0
        fun median(v: List<Double>) = v.sorted()[v.size / 2]
        val byX = pairs.sortedBy { it.first }
        val lo = byX.take(byX.size / 2); val hi = byX.drop(byX.size / 2)
        val xl = median(lo.map { it.first }); val xh = median(hi.map { it.first })
        if (lo.size < 3 || xh - xl < base) return Lean(0.0, median(pairs.map { it.second }), 0.0, labelsY)
        val ml = median(lo.map { it.second }); val mh = median(hi.map { it.second })
        return Lean(xl, ml, (mh - ml) / (xh - xl), labelsY)
    }

    /**
     * The tilt of the weekday labels in a photo of a turned page, in degrees, or null (under [MIN_TILT_DEGREES]).
     * The median slope between pairs of weekday names side by side: at least a label's width apart and no steeper than
     * [MAX_TILT_DEGREES], so names of different rows do not pair. Needs [MIN_LABELS] names; with fewer, the single
     * letters standing alone on their lines (S M T W T F S) are used instead, when there are [MIN_LABELS] of them. The
     * engine's own line angles are 0 on words this short, and a page in perspective tilts differently from one part to
     * another, so the labels are measured themselves.
     */
    internal fun labelTilt(lines: List<OcrLine>): Double? {
        val toks = lines.withIndex().flatMap { (i, l) -> tokens(l, i) }
        val names = toks.filter { WEEKDAY_WORD.matches(it.text) }.takeIf { it.size >= MIN_LABELS }
            ?: lines.withIndex().filter { (_, l) -> LETTER.matches(l.text) }.flatMap { (i, l) -> tokens(l, i) }.filter { LETTER.matches(it.text) }
        if (names.size < MIN_LABELS) return null
        val steepest = tan(Math.toRadians(MAX_TILT_DEGREES))
        val slopes = mutableListOf<Double>()
        for (a in names) for (b in names) {
            val dx = b.cx - a.cx
            if (dx < (a.right - a.left)) continue
            val k = (b.cy - a.cy) / dx
            if (abs(k) <= steepest) slopes += k
        }
        if (slopes.size < MIN_LABELS - 1) return null
        slopes.sort()
        val deg = Math.toDegrees(atan(slopes[slopes.size / 2]))
        return deg.takeIf { abs(it) >= MIN_TILT_DEGREES }
    }

    private fun readLevel(lines: List<OcrLine>, today: LocalDate?): List<RawEvent>? {
        val rows = labelRows(lines).sortedBy { it.top }
        if (rows.isEmpty()) return null
        var anyClean = false
        val out = mutableListOf<RawEvent>()
        for ((k, row) in rows.withIndex()) {
            val regionBottom = rows.getOrNull(k + 1)?.top ?: Int.MAX_VALUE
            val events = readOne(lines, row, regionBottom, rows.getOrNull(k - 1)?.bottom ?: -1, today) ?: continue
            anyClean = true
            out += events
        }
        return if (anyClean) out else null
    }

    private fun readOne(lines: List<OcrLine>, row: LabelRow, regionBottom: Int, regionTop: Int, today: LocalDate?): List<RawEvent>? {
        val head = header(lines, row, regionTop) ?: return null
        val month = head.month; val printedYear = head.year
        // below the label over its own column, not the lowest label of the row: a curled page lifts the row's ends
        fun labelAbove(l: OcrLine) = row.toks.minBy { abs(it.cx - (l.left + l.right) / 2.0) }
        val numbers = lines.indices.filter { i ->
            val l = lines[i]
            l.top > labelAbove(l).bottom && l.bottom < regionBottom && i !in row.lines && DAY_NUMBER.matches(l.text)
        }
        if (numbers.size < MIN_AGREEING) return null
        val measured = rowPitch(numbers.map { lines[it] })
        val pitch = measured ?: ONE_ROW_PITCH * row.base
        val row0Top = numbers.minOf { lines[it].top }
        // rows counted from the labels, as the cells are: a first week whose numbers were all missed (May 2027's lone
        // "1") is still row 0, so the first numbers read are row 1
        val missedRows = floor((row0Top - row.bottom) / pitch).toInt().coerceAtLeast(0)
        fun rowOf(top: Int) = ((top - row0Top) / pitch).roundToInt() + missedRows

        // week start (shift of the first label's column) and year: most numbers on their right weekday
        val maxIdx = row.idx.max()
        val years = printedYear?.let { listOf(it) }
            ?: today?.let { t -> listOf(t.year, t.year + 1, t.year - 1, t.year + 2) } ?: return null
        data class Fit(val shift: Int, val year: Int, val anchor: LocalDate, val agree: Int)
        var best: Fit? = null
        for (shift in 0..(6 - maxIdx)) {
            val firstCol = row.firstDay.minus(shift.toLong())
            if (firstCol != DayOfWeek.SUNDAY && firstCol != DayOfWeek.MONDAY && shift != 0) continue
            val gridLeft = row.toks.first().cx - (shift + 0.5) * row.base
            for (year in years) {
                // a range into the next month: a number may be a day of either month; each votes for both
                val months = (0 until head.months).map { YearMonth.of(year, month).plusMonths(it.toLong()) }
                val votes = numbers.flatMap { i ->
                    val l = lines[i]
                    val col = floor(((l.left + l.right) / 2.0 - gridLeft) / row.base).toInt()
                    val d = DAY_NUMBER.matchEntire(l.text)!!.groupValues[1].toInt()
                    if (col !in 0..6) return@flatMap emptyList()
                    months.filter { d <= it.lengthOfMonth() }.map { it.atDay(d).minusDays(7L * rowOf(l.top) + col) }.distinct()
                }.groupingBy { it }.eachCount()
                val (anchor, agree) = votes.maxByOrNull { it.value } ?: continue
                if (anchor.dayOfWeek != firstCol) continue
                val fit = Fit(shift, year, anchor, agree)
                val b = best
                if (b == null || agree > b.agree || (agree == b.agree && yearRank(year, today) < yearRank(b.year, today))) best = fit
            }
        }
        val fit = best ?: return null
        if (fit.agree < MIN_AGREEING || fit.agree < MIN_AGREEING_SHARE * numbers.size) return null

        val gridLeft = row.toks.first().cx - (fit.shift + 0.5) * row.base
        val nRows = numbers.maxOf { rowOf(lines[it].top) } + 1
        val bandTop = row.bottom.toDouble()
        val gridBottom = minOf(regionBottom.toDouble(), row0Top + (nRows - missedRows) * pitch - 0.3 * pitch)
        fun cellOf(x: Double, top: Int): Pair<Int, Int>? {
            val col = floor((x - gridLeft) / row.base).toInt()
            val r = floor((top - bandTop) / pitch).toInt()
            return if (col in 0..6 && r in 0 until nRows) r to col else null
        }

        // event fragments, by cell, top to bottom
        val skip = numbers.toSet() + row.lines
        val inGrid = lines.indices.filter { it !in skip && lines[it].top > row.bottom && lines[it].top < gridBottom }.toMutableSet()
        // a single row's height is a guess: a line just under a kept line in the same column (above any next label row)
        // continues that entry ("Client" / "demo") and is kept too
        if (measured == null) {
            for (i in lines.indices.sortedBy { lines[it].top }) {
                val l = lines[i]
                if (i in skip || i in inGrid || l.top < gridBottom || l.bottom >= regionBottom) continue
                if (inGrid.any { k -> val u = lines[k]
                        l.top >= u.top && l.top - u.bottom <= 1.2 * (u.bottom - u.top).coerceAtLeast(1) && minOf(l.right, u.right) > maxOf(l.left, u.left) }) inGrid += i
            }
        }
        val frags = inGrid.sorted().flatMap { fragments(lines[it], it, row.base) { x -> floor((x - gridLeft) / row.base).toInt() } }
        val byCell = frags.mapNotNull { f -> cellOf(f.left + 0.1 * row.base, f.top)?.let { it to f } }
            .groupBy({ it.first }, { it.second })
        val first = YearMonth.of(fit.year, month)
        val shown = head.range?.let { (a, b) ->
            val from = first.atDay(a.second.coerceAtMost(first.lengthOfMonth()))
            from to (b?.let { first.plusMonths(it.first.toLong()).let { m -> m.atDay(it.second.coerceAtMost(m.lengthOfMonth())) } } ?: from.plusDays(MAX_RANGE_DAYS.toLong()))
        }
        fun isShown(date: LocalDate) = if (shown != null) date >= shown.first && date <= shown.second else YearMonth.from(date) == first
        fun dateOf(cell: Pair<Int, Int>): LocalDate = fit.anchor.plusDays(7L * cell.first + cell.second)
        // each day number read: its value, the cell it sits in, and the engine's confidence
        val nums = numbers.map { i ->
            val l = lines[i]
            Num(DAY_NUMBER.matchEntire(l.text)!!.groupValues[1].toInt(), cellOf((l.left + l.right) / 2.0, l.top), confidence(l))
        }
        val out = mutableListOf<RawEvent>()
        for ((cell, fs) in byCell.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            val date = dateOf(cell)
            // a cell outside the dates shown: a neighbouring month's, or one outside the printed range
            if (!isShown(date)) continue
            val name = date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val written = if (printedYear != null) "${date.dayOfMonth} $name ${date.year}" else "${date.dayOfMonth} $name"
            val checks = cellChecks(row, fit.shift, cell, date, nums, ::dateOf, ::isShown, printedYear != null)
            for (e in entries(fs.sortedWith(compareBy({ it.top }, { it.left })))) {
                val unclear = e.conf != null && e.conf < DigitConfidence.DOUBTFUL_BELOW
                out += RawEvent(e.title, written, e.time, null, grid = true, gridChecks = if (unclear) checks + "grid_entry_unclear" else checks,
                    gridMonth = if (printedYear != null) "$name ${date.year}" else name)
            }
        }
        return out
    }

    /** A day number read in a grid: its value, the cell it sits in (null: outside the grid), the engine's confidence. */
    private class Num(val day: Int, val cell: Pair<Int, Int>?, val conf: Float?)

    /**
     * The checks a grid cell's date must pass; the codes of those that fail (each becomes a flag on the cell's events):
     * - grid_weekday_mismatch: the weekday label over the cell's column names another day than the date's;
     * - grid_day_not_read: the date's day number is not read in its own cell;
     * - grid_day_repeated: the date's day number is read in more than one cell of the dates shown;
     * - grid_day_unclear: the engine's confidence in that day number is low ([DigitConfidence.DOUBTFUL_BELOW]);
     * - grid_year_guessed: no year printed above the grid: the year was worked out from the layout.
     */
    private fun cellChecks(
        row: LabelRow, shift: Int, cell: Pair<Int, Int>, date: LocalDate, nums: List<Num>,
        dateOf: (Pair<Int, Int>) -> LocalDate, isShown: (LocalDate) -> Boolean, yearPrinted: Boolean,
    ): List<String> = buildList {
        val label = row.toks.zip(row.idx).firstOrNull { (_, k) -> k + shift == cell.second }?.first
        if (label != null) {
            val letter = label.text.trim { !it.isLetter() }.lowercase()
            val fits = if (letter.length == 1) date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase().startsWith(letter)
            else dayOfLabel(label.text) == date.dayOfWeek
            if (!fits) add("grid_weekday_mismatch")
        }
        val same = nums.filter { n -> n.day == date.dayOfMonth && n.cell?.let { isShown(dateOf(it)) } == true }
        val here = same.filter { it.cell == cell }
        when {
            here.isEmpty() -> add("grid_day_not_read")
            same.size > 1 -> add("grid_day_repeated")
        }
        if (here.any { it.conf != null && it.conf < DigitConfidence.DOUBTFUL_BELOW }) add("grid_day_unclear")
        if (!yearPrinted) add("grid_year_guessed")
    }

    /** The engine's confidence in a line: its least sure word, else the line's own; null when the engine gave none. */
    private fun confidence(l: OcrLine): Float? = l.words.mapNotNull { it.confidence }.minOrNull() ?: l.confidence

    /** One entry being built in a cell; [conf]: the engine's least confidence over its lines. */
    private class Entry(val title: StringBuilder, var time: String?, val timeFirst: Boolean, var last: Frag, var conf: Float? = last.conf) {
        fun take(f: Frag) { last = f; conf = listOfNotNull(conf, f.conf).minOrNull() }
    }

    /** An entry read from a cell: its title, its time, and the engine's least confidence over its lines. */
    private class GridEntry(val title: String, val time: String?, val conf: Float?)

    /**
     * Entries of one cell, top to bottom: a line that starts with a time starts an entry; a line that is only a time is the
     * time of the entry above when it has none, else it starts an entry whose title is the lines just under it; a line
     * just under an entry continues its title when that entry has no
     * time yet or started with its time ("10am Blood" / "donation camp"); else it starts a new entry.
     */
    private fun entries(fs: List<Frag>): List<GridEntry> {
        val out = mutableListOf<Entry>()
        for (f in fs) {
            val text = OcrNormalizer.normalizeNumeric(f.text).trim()
            if (text.none(Char::isLetterOrDigit)) continue
            val times = TimeParser.parse(text)
            val first = times.firstOrNull()
            val rest = cut(text, times)
            val prev = out.lastOrNull()
            val close = prev != null && f.top - prev.last.bottom <= 1.2 * (prev.last.bottom - prev.last.top).coerceAtLeast(1)
            val letters = rest.count(Char::isLetter)
            when {
                letters == 0 && first != null && prev != null && prev.time == null -> { prev.time = first.raw; prev.take(f) }
                // a time alone with no entry above that needs it: the time of the lines just under it ("13:30" / "Client")
                letters == 0 && first != null -> out += Entry(StringBuilder(), first.raw, true, f)
                letters < 3 && first == null -> {}                                   // "am" left over, a stray mark
                first != null && first.startOffset <= 1 -> out += Entry(StringBuilder(rest), first.raw, true, f)
                prev != null && close && (prev.time == null || (prev.timeFirst && first == null)) -> {
                    prev.title.append(' ').append(rest)
                    if (prev.time == null) prev.time = first?.raw
                    prev.take(f)
                }
                else -> out += Entry(StringBuilder(rest), first?.raw, false, f)
            }
        }
        return out.map { GridEntry(it.title.toString().replace(Regex("""\s+"""), " ").trim(), it.time, it.conf) }.filter { it.title.count(Char::isLetter) >= 3 }
    }

    private fun cut(text: String, times: List<`in`.gauthama.snapslotify.core.extract.ParsedTime>): String {
        val sb = StringBuilder(text)
        for (t in times.sortedByDescending { it.startOffset }) sb.replace(t.startOffset, t.endOffset, " ")
        return sb.toString().replace(Regex("""\s+"""), " ").trim().trim('-', '–', '—', '·', ':', ',', '|', '•').trim()
    }

    /** A piece of a line: the whole line, or (with word boxes) runs of words split where the gap could be a cell boundary. */
    private class Frag(val text: String, val left: Double, val top: Int, val bottom: Int, val conf: Float? = null)

    private fun fragments(l: OcrLine, i: Int, base: Double, column: (Double) -> Int = { 0 }): List<Frag> {
        // no word boxes: a line wider than a cell may run across two cells and cannot be split safely, so it gives no event
        if (l.words.size < 2) return if (l.words.isEmpty() && l.right - l.left > 1.1 * base) emptyList() else listOf(Frag(l.text, l.left.toDouble(), l.top, l.bottom, confidence(l)))
        val ws = l.words.sortedBy { it.left }
        val out = mutableListOf<MutableList<`in`.gauthama.snapslotify.core.ocr.OcrWord>>(mutableListOf(ws.first()))
        for (w in ws.drop(1)) {
            val prev = out.last().last()
            // a wide gap, or a word that starts in another column than its run with a gap of half its height ("Book club
            // 12pm" | "Gym 8:45 pm" read as one line across two cells)
            val h = (w.bottom - w.top).coerceAtLeast(1)
            val runStart = out.last().first()
            val newColumn = column(w.left + 0.1 * base) != column(runStart.left + 0.1 * base) && w.left - prev.right >= h / 2
            if (w.left - prev.right > 0.25 * base || newColumn) out += mutableListOf(w) else out.last() += w
        }
        // one run: the line's own text, which clean-up may have mended ("PTA meeting 4" + "pm"); the word texts only when split
        if (out.size == 1) return listOf(Frag(l.text, l.left.toDouble(), l.top, l.bottom, confidence(l)))
        return out.map { run -> Frag(run.joinToString(" ") { it.text }, run.first().left.toDouble(), run.minOf { it.top }, run.maxOf { it.bottom },
            run.mapNotNull { it.confidence }.minOrNull() ?: l.confidence) }
    }

    private fun yearRank(year: Int, today: LocalDate?): Int = if (today == null) 0 else when (val d = year - today.year) {
        0 -> 0; 1 -> 1; -1 -> 2; else -> 2 + abs(d)
    }

    private fun rowPitch(nums: List<OcrLine>): Double? {
        val tops = nums.map { it.top }.sorted()
        val h = nums.map { it.bottom - it.top }.sorted()[nums.size / 2].coerceAtLeast(1)
        val rows = mutableListOf<MutableList<Int>>()
        for (t in tops) if (rows.isNotEmpty() && t - rows.last().last() <= h) rows.last() += t else rows += mutableListOf(t)
        if (rows.size < 2) return null
        val starts = rows.map { r -> r.average() }
        val diffs = starts.zipWithNext { a, b -> b - a }.sorted()
        val unit = diffs.first()
        // a missing row doubles a gap: count each gap in whole units of the smallest
        val units = diffs.sumOf { (it / unit).roundToInt().coerceAtLeast(1) }
        return diffs.sum() / units
    }

    /**
     * What the grid shows: its (first) [month], the printed [year], how many [months] its days may fall in (2 for a range
     * into the next month), and the printed [range] as (month offset, day) pairs, null for a whole month; a range with no
     * end runs [MAX_RANGE_DAYS] days.
     */
    private class Header(val month: Month, val year: Int?, val months: Int = 1, val range: Pair<Pair<Int, Int>, Pair<Int, Int>?>? = null)

    /**
     * A month line ([monthAndYear]); else the nearest line above the labels that prints a range of dates ([rangeLine]);
     * else the nearest that prints one date with its month ("Week of 6 December"), taken as the first day shown.
     */
    private fun header(lines: List<OcrLine>, row: LabelRow, regionTop: Int): Header? {
        monthAndYear(lines, row, regionTop)?.let { (m, y) -> return Header(m, y) }
        val above = lines.indices.filter { lines[it].bottom <= row.top + 5 && lines[it].top > regionTop }.sortedByDescending { lines[it].bottom }
        return above.firstNotNullOfOrNull { rangeLine(headerText(lines[it])) } ?: above.firstNotNullOfOrNull { startLine(headerText(lines[it])) }
    }

    /** One date with its month on a line: the first of up to [MAX_RANGE_DAYS] days shown, maybe into the next month. */
    private fun startLine(text: String): Header? {
        val t = ANY_YEAR.replace(text, " ").replace(',', ' ')
        val one = RANGE_PART.findAll(t).filter { it.groupValues[1].isNotEmpty() || it.groupValues[4].isNotEmpty() }.toList().singleOrNull() ?: return null
        val m = monthOf(one.groupValues[1].ifEmpty { one.groupValues[4] }) ?: return null
        val d = one.groupValues[2].ifEmpty { one.groupValues[3] }.toInt()
        val year = ANY_YEAR.find(text)?.groupValues?.get(1)?.toInt()
        return Header(m, year, 2, (0 to d) to null)
    }

    private val RANGE_PART = regex("""\b(?:($MONTH)\.?\s*([1-9]|[12]\d|3[01])|([1-9]|[12]\d|3[01])(?:st|nd|rd|th)?\s*($MONTH)?)\b""")
    private val RANGE_DASH = Regex("""\s*(?:-|–|—|\bto\b|\buntil\b|\btill\b)\s*""", RegexOption.IGNORE_CASE)
    private val ANY_YEAR = Regex("""\b(20\d\d)\b""")

    /**
     * A printed range of dates: two dates joined by a dash or "to", each a day with or without its month ("6 - 14
     * December 2026", "December 6-14", "28 Nov - 6 Dec"), the year anywhere on the line. The months must be the same or
     * the next one, and the range at most [MAX_RANGE_DAYS] long; else null.
     */
    private fun rangeLine(text: String): Header? {
        val t = ANY_YEAR.replace(text, " ").replace(',', ' ')
        // each dash in turn: an earlier one may join something else ("Week 15 · 12 - 18 April", its dot read as a dash)
        return RANGE_DASH.findAll(t).firstNotNullOfOrNull { rangeAt(text, t, it) }
    }

    private fun rangeAt(text: String, t: String, dash: MatchResult): Header? {
        if (RANGE_PART.find(t, dash.range.last + 1) == null) return null
        val a = RANGE_PART.findAll(t.substring(0, dash.range.first)).lastOrNull() ?: return null
        val b = RANGE_PART.find(t, dash.range.last + 1)!!
        if (b.range.first != dash.range.last + 1) return null
        fun day(m: MatchResult) = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toInt()
        fun mon(m: MatchResult) = (m.groupValues[1].ifEmpty { m.groupValues[4] }).takeIf { it.isNotEmpty() }?.let(::monthOf)
        val m2 = mon(b) ?: mon(a) ?: return null
        val m1 = mon(a) ?: m2
        val next = when (m2) { m1 -> 0; m1.plus(1) -> 1; else -> return null }
        val d1 = day(a); val d2 = day(b)
        if (next == 0 && d2 <= d1) return null
        val span = if (next == 0) d2 - d1 else d2 + 31 - d1
        if (span > MAX_RANGE_DAYS) return null
        val year = ANY_YEAR.find(text)?.groupValues?.get(1)?.toInt()
        return Header(m1, year, next + 1, (0 to d1) to (next to d2))
    }

    private fun monthAndYear(lines: List<OcrLine>, row: LabelRow, regionTop: Int): Pair<Month, Int?>? {
        val months = lines.indices.mapNotNull { i -> MONTH_LINE.matchEntire(headerText(lines[i]).trim())?.let { i to it } }
        val above = months.filter { (i, _) -> lines[i].bottom <= row.top + 5 && lines[i].top > regionTop }
        val pick = above.maxByOrNull { (i, _) -> lines[i].bottom } ?: months.singleOrNull() ?: return null
        val month = monthOf(pick.second.groupValues[1]) ?: return null
        pick.second.groups[2]?.value?.let { return month to yearOf(it) }
        val ml = lines[pick.first]
        val years = lines.indices.filter { YEAR_LINE.matches(lines[it].text) }
        fun dist(i: Int) = abs((lines[i].top + lines[i].bottom) / 2.0 - (ml.top + ml.bottom) / 2.0) + abs(lines[i].left - ml.right) / 4.0
        val year = years.filter { lines[it].bottom <= row.top + 5 && lines[it].top > regionTop }.minByOrNull(::dist)
            ?: years.minByOrNull(::dist)
        return month to year?.let { yearOf(YEAR_LINE.matchEntire(lines[it].text)!!.groupValues[1]) }
    }

    private fun yearOf(s: String) = s.replace('o', '0').replace('O', '0').toInt()

    /**
     * Rows of weekday labels: at least [MIN_LABELS] in one row, evenly spaced, with a week order the known labels agree on.
     * A label joins a row when it is level with the row's label nearest to it sideways, give or take a slope of up to
     * [MAX_TILT_DEGREES] over the distance between them: a curled page bends its row, which levelling by one tilt leaves
     * curved, so the labels at its ends sit off the row's average height.
     */
    private fun labelRows(lines: List<OcrLine>): List<LabelRow> {
        val toks = lines.withIndex().flatMap { (i, l) -> tokens(l, i) }
            .filter { WEEKDAY_WORD.matches(it.text) || LETTER.matches(it.text) }
        val steepest = tan(Math.toRadians(MAX_TILT_DEGREES))
        val rows = mutableListOf<MutableList<Tok>>()
        for (t in toks.sortedBy { it.cy }) {
            val r = rows.lastOrNull()
            val near = r?.minByOrNull { abs(it.cx - t.cx) }
            if (near != null && abs(t.cy - near.cy) <= 0.6 * (t.bottom - t.top).coerceAtLeast(1) + steepest * abs(t.cx - near.cx)) r += t
            else rows += mutableListOf(t)
        }
        return rows.mapNotNull { r -> labelRow(r.sortedBy { it.cx }) }
    }

    private fun labelRow(ts: List<Tok>): LabelRow? {
        if (ts.size < MIN_LABELS) return null
        val words = ts.count { WEEKDAY_WORD.matches(it.text) }
        if (words in 1 until MIN_LABELS && words < ts.size - words) return null   // mostly stray letters around a few names
        // the spacing: the lower median gap (at most two labels are missing, so most gaps are one column), then refined
        // over the whole row once each label has its column
        val gaps = ts.zipWithNext { a, b -> b.cx - a.cx }.sorted()
        val rough = gaps[(gaps.size - 1) / 2].takeIf { it > 0 } ?: return null
        val idx = ts.map { ((it.cx - ts.first().cx) / rough).roundToInt() }
        val base = if (idx.last() > 0) (ts.last().cx - ts.first().cx) / idx.last() else return null
        if (idx.toSet().size != idx.size || idx.last() > 6) return null
        if (ts.zip(idx).any { (t, k) -> abs(t.cx - (ts.first().cx + k * base)) > 0.35 * base }) return null
        // the day of the first label: from the names, else from the letters M, W, F; S and T must fit it
        val known = ts.zip(idx).mapNotNull { (t, k) -> dayOfLabel(t.text)?.let { it.minus(k.toLong()) } }
        val first = known.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val fits = ts.zip(idx).all { (t, k) ->
            val d = first.plus(k.toLong())
            val letter = t.text.trim { !it.isLetter() }.lowercase()
            if (letter.length == 1) d.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase().startsWith(letter) else dayOfLabel(t.text) == d
        }
        return if (fits) LabelRow(ts, idx, base, first) else null
    }

    private fun dayOfLabel(s: String): DayOfWeek? {
        WEEKDAY_WORD.matchEntire(s.trim())?.let { m -> return DayOfWeek.entries[DAYS.indexOf(m.groupValues[1].lowercase().take(2))] }
        return when (s.trim { !it.isLetter() }.lowercase()) { "m" -> DayOfWeek.MONDAY; "w" -> DayOfWeek.WEDNESDAY; "f" -> DayOfWeek.FRIDAY; else -> null }
    }

    private fun tokens(l: OcrLine, i: Int): List<Tok> {
        if (l.words.isNotEmpty()) return l.words.map { Tok(it.text, it.left.toDouble(), it.top, it.right.toDouble(), it.bottom, i) }
        val text = l.text
        val w = (l.right - l.left).toDouble() / text.length.coerceAtLeast(1)
        return Regex("""\S+""").findAll(text).map { m -> Tok(m.value, l.left + m.range.first * w, l.top, l.left + (m.range.last + 1) * w, l.bottom, i) }.toList()
    }

    /** A header line with OCR damage in a long month name repaired ([MonthRepair]): "Decmber 2026" heads a December grid. */
    private fun headerText(l: OcrLine): String = `in`.gauthama.snapslotify.core.extract.MonthRepair.repair(l.text).text

    private fun monthOf(s: String): Month? = Month.entries.firstOrNull { it.name.lowercase().startsWith(s.lowercase().take(3)) }
}
