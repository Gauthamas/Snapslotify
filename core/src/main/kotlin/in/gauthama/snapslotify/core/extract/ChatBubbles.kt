package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.extract.Patterns.MONTH
import `in`.gauthama.snapslotify.core.extract.Patterns.WEEKDAY
import `in`.gauthama.snapslotify.core.extract.Patterns.regex
import `in`.gauthama.snapslotify.core.layout.Layout
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** One chat bubble: its kept-line indices top to bottom, its text (lines joined by spaces) and its side (incoming = left). */
data class Bubble(val lines: List<Int>, val text: String, val incoming: Boolean)

/**
 * A chat around the code's date: the bubbles in screen order, the one that holds the date (else the time, else the
 * relative day word) as [dateBubble], and the message date line above it as printed ([messageDate], "Today" too).
 */
data class ChatContext(val bubbles: List<Bubble>, val dateBubble: Int?, val messageDate: String?) {
    /** The date bubble with up to [side] bubbles before and after it, in screen order, and its index in that list. */
    fun window(side: Int = 2): Pair<List<Bubble>, Int?> {
        val k = dateBubble ?: return bubbles.take(2 * side + 1) to null
        val from = maxOf(0, k - side)
        return bubbles.subList(from, minOf(bubbles.size, k + side + 1)) to k - from
    }
}

/** Event candidates in a chat: [times] distinct times that sit with a day, on [days] distinct days. */
data class EventCandidates(val times: Int, val days: Int)

/** The planned day of a chat with several dates: its [date] (null for a weekday with no date), its [bubble], and [events]. */
data class Planned(val date: DateCandidate?, val bubble: Int?, val events: Int)

/** A date counted from the message date: [word] "tomorrow" under "Mon, 19 Oct 2026" is "20 Oct 2026". */
data class CountedDate(
    val date: String, val messageDate: String, val lineIndex: Int, val word: String,
    /** Counted from the screenshot's own date ([messageDate] is then that date's label), not from a date line. */
    val fromShot: Boolean = false,
)

/**
 * Chat layout by position: the header (top band, down to the presence line), the message date lines between bubbles
 * ("Mon, 19 Oct 2026", "Today"), and the bubbles ([Layout.blocks] of the remaining lines), each on its sender's side.
 */
class ChatBubbles(private val lines: List<OcrLine>, private val c: Candidates, imageWidth: Int, imageHeight: Int) {

    private val left: Int
    private val right: Int
    private val messageDates: List<Int>
    val bubbles: List<Bubble>

    init {
        val band = HEADER_BAND * imageHeight
        val headerBottom = lines.filter { it.top < band && PRESENCE.containsMatchIn(it.text.trim()) }.maxOfOrNull { it.bottom }
            ?: lines.firstOrNull()?.takeIf { it.top < band }?.bottom
        val header = lines.indices.filter { headerBottom != null && lines[it].top < band && lines[it].top < headerBottom }.toSet()
        val rest = lines.indices.filter { it !in header }.ifEmpty { lines.indices.toList() }
        left = if (imageWidth > 0) 0 else rest.minOfOrNull { lines[it].left } ?: 0
        right = if (imageWidth > 0) imageWidth else rest.maxOfOrNull { lines[it].right } ?: 0
        val loose = rest.filter { isMessageDate(lines[it]) }
        messageDates = separators(loose, rest)
        val body = rest.filter { it !in messageDates }
        bubbles = Layout.blocks(body.map { lines[it] }, BUBBLE_GAP).map { b ->
            val idx = b.map { body[it] }
            val l = idx.minOf { lines[it].left }
            val r = idx.maxOf { lines[it].right }
            Bubble(idx, idx.joinToString(" ") { lines[it].text.trim() }, l - left <= right - r)
        }.sortedBy { b -> lines[b.lines.first()].top }
    }

    /** True for a message date line between bubbles: the date the messages were sent, never an event's date. */
    fun isMessageDateLine(line: Int): Boolean = line in messageDates

    /** The text of the message date line above line [line], if any. */
    fun messageDateTextAbove(line: Int): String? = messageDateAbove(lines[line].top)?.let { lines[it].text.trim() }

    fun bubbleOf(line: Int?): Int? = line?.let { l -> bubbles.indexOfFirst { l in it.lines }.takeIf { it >= 0 } }

    /** The context for [anchorLine] (the date's line, else the time's, else the relative word's). */
    fun context(anchorLine: Int?): ChatContext {
        val k = bubbleOf(anchorLine)
        val top = k?.let { lines[bubbles[it].lines.first()].top } ?: anchorLine?.let { lines[it].top } ?: Int.MAX_VALUE
        return ChatContext(bubbles, k, messageDateAbove(top)?.let { lines[it].text.trim() })
    }

    /** The bubble with today / tonight / tomorrow / day after tomorrow: the first one that also holds a time, else the first. */
    fun relativeWordBubble(): Int? {
        val withWord = bubbles.indices.filter { AnchoredDay.WORD.containsMatchIn(bubbles[it].text) }
        return withWord.firstOrNull { timesIn(it).isNotEmpty() } ?: withWord.firstOrNull()
    }

    /**
     * A chat with several dates gives one event. A bubble counts when it holds a time, a relative day word (today /
     * tonight / tomorrow / day after tomorrow) or a weekday name outside its dates ("saturday works", "11am Monday"); its
     * date counts, and a bubble with a weekday but no date counts as a day without a date. The latest counting bubble
     * (lowest; in it, the last date) is taken. "paid the bill on 15 Oct" and "we met on 8 Oct at the mall" do not count.
     * [Planned.events]: the counting bubbles, or 2 when the taken bubble holds two or more dates and two or more times
     * ("Sprint planning on Mon 26 Oct, 10:00 ... Retro on Fri 30 Oct at 4 PM").
     */
    fun latestPlanned(dates: List<DateCandidate>): Planned {
        fun counts(k: Int) = timesIn(k).isNotEmpty() || COUNTED.containsMatchIn(bubbles[k].text) || hasWeekday(k)
        val datedBubbles = dates.mapNotNull { bubbleOf(it.lineIndex) }.toSet()
        val counting = bubbles.indices.filter { (it in datedBubbles || hasWeekday(it)) && counts(it) }
        val k = counting.maxOrNull() ?: return Planned(null, null, 0)
        val date = dates.filter { bubbleOf(it.lineIndex) == k }.maxByOrNull { it.offset }
        val inBubble = dates.count { bubbleOf(it.lineIndex) == k } >= 2 && timesIn(k).size >= 2
        return Planned(date, k, maxOf(counting.size, if (inBubble) 2 else 1))
    }

    /** A weekday name in bubble [k] outside its dates (a date's own weekday, "Mon, 26 Oct", does not count). */
    private fun hasWeekday(k: Int): Boolean {
        var t = bubbles[k].text
        for (d in c.dates.filter { it.lineIndex in bubbles[k].lines }) t = t.replace(d.raw, " ")
        return WEEKDAY_WORD.containsMatchIn(t)
    }

    /** Time candidates in bubble [k], in reading order. */
    fun timesIn(k: Int): List<TimeCandidate> = c.times.filter { it.lineIndex in bubbles[k].lines }.sortedBy { it.offset }

    /**
     * For a chat with no written date: the relative day words of [AnchoredDay] (today / tonight / tomorrow / day after
     * tomorrow, this / on / coming <weekday>) in [relativeWordBubble], counted from the nearest message date line above
     * it. With no date line above, or only "Today" / "Yesterday" / a weekday name, the screenshot's own date [shot] is the
     * anchor ([ShotDate.lineDay]: the day before for "Yesterday", the most recent such weekday on or before it for
     * "Monday"). No screenshot date: null, and the date stays unresolved.
     */
    fun counted(shot: LocalDate? = null): CountedDate? {
        val k = relativeWordBubble() ?: return null
        val b = bubbles[k]
        val m = AnchoredDay.WORD.find(b.text)!!
        val line = b.lines.firstOrNull { AnchoredDay.WORD.containsMatchIn(lines[it].text) } ?: b.lines.first()
        val printed = messageDateAbove(lines[b.lines.first()].top)?.let { lines[it].text.trim() }
        if (printed != null && !RELATIVE_DAY.matches(printed)) {
            val (base, hasYear) = AnchoredDay.anchorOf(printed) ?: return null
            val day = AnchoredDay.resolve(m, base, hasYear) ?: return null
            return CountedDate(AnchoredDay.format(day, hasYear), printed, line, m.value)
        }
        if (shot == null) return null
        val base = if (printed == null) shot else ShotDate.lineDay(printed, shot) ?: return null
        val day = AnchoredDay.resolve(m, base, true) ?: return null
        return CountedDate(AnchoredDay.format(day, true), ShotDate.label(shot), line, m.value, fromShot = true)
    }

    /**
     * The title, from bubble [k] (the date bubble) and never more than one bubble away. [anchor] is the date (else time,
     * else relative word) as found; its clause is the date clause. Clauses lose their dates, times and relative day words.
     * 1. the date clause, if [MIN_CONTENT_WORDS] or more content words remain;
     * 2. if the date clause has no content word left ("09-Dec-2026 | 18:00 hrs"), the date bubble's clause with the most;
     * 3. else the bubble just before or just after it, with [MIN_CONTENT_WORDS] or more, skipping acknowledgements
     *    ("ok", "sure", "see you") and bubbles with another date; same sender first, then more content words, then the
     *    earlier one;
     * 4. else the single event word left in the date clause ("badminton" from "badminton 12th november");
     * 5. else no title.
     */
    fun title(k: Int?, anchor: String? = null): String? {
        k ?: return null
        val own = clauses(bubbles[k])
        // a date with a comma ("Saturday, 24 Oct") spans two clauses: the one with its start is the date clause
        val parts = listOfNotNull(anchor?.trim(), anchor?.trim()?.substringBefore(", "), anchor?.trim()?.substringAfterLast(", "))
        val main = parts.firstNotNullOfOrNull { a -> own.firstOrNull { it.first.contains(a, ignoreCase = true) } }
            ?: own.firstOrNull { holdsDateOrTime(it.first) } ?: own.firstOrNull()
        val mainWords = main?.let { contentWords(it.second) } ?: 0
        if (mainWords >= MIN_CONTENT_WORDS) return main!!.second
        if (mainWords == 0) own.maxByOrNull { contentWords(it.second) }?.takeIf { contentWords(it.second) >= MIN_CONTENT_WORDS }?.let { return it.second }
        val me = bubbles[k]
        val near = listOf(k - 1, k + 1).filter { it in bubbles.indices }.mapNotNull { j ->
            val b = bubbles[j]
            if (ACK.matches(b.text) || hasDate(b)) return@mapNotNull null
            clauses(b).map { it.second }.maxByOrNull { contentWords(it) }?.takeIf { contentWords(it) >= MIN_CONTENT_WORDS }?.let { j to it }
        }
        near.minWithOrNull(compareBy({ bubbles[it.first].incoming != me.incoming }, { -contentWords(it.second) }, { it.first }))
            ?.let { return it.second }
        return main?.second?.let { cl -> words(cl).filter { it.lowercase() !in STOP }.singleOrNull()?.takeIf { it.length >= 3 } }
    }

    /**
     * Event candidates: clauses of the bubbles that hold both a day (a date, or a day word other than "yesterday") and a
     * time, distinct by time ("Dentist tue 10am, then haircut wed 4pm" holds two; "sat 7 nov 6 pm?" and "6 pm on sat
     * works" are one, and so is a plan moved to another day at the same time).
     */
    fun eventCandidates(): EventCandidates {
        val found = bubbles.flatMap { b -> CLAUSE.split(b.text) }.mapNotNull { cl ->
            val time = TimeParser.parse(cl).firstOrNull() ?: return@mapNotNull null
            val day = Dates.findDates(cl).firstOrNull()?.let { "${it.day}/${it.month}" }
                ?: Dates.REL_PHRASE.findAll(cl).map { it.value.lowercase().replace(Regex("""\s+"""), " ") }
                    .firstOrNull { it != "yesterday" }?.let { w -> Patterns.weekday(w.substringAfterLast(' '))?.name ?: w }
                ?: return@mapNotNull null
            day to time.start
        }
        val times = found.map { it.second }.distinct().size
        return EventCandidates(times, if (times >= 2) found.map { it.first }.distinct().size else 1)
    }

    private fun isMessageDate(l: OcrLine): Boolean {
        val t = l.text.trim()
        if (Patterns.DATE_CHIP_LINE.matches(t)) return true
        val span = (right - left).coerceAtLeast(1)
        val centred = kotlin.math.abs((l.left + l.right) / 2.0 - (left + right) / 2.0) <= CENTRE * span && l.right - l.left <= 0.6 * span
        return centred && (ABSOLUTE_DAY.matches(t) || RELATIVE_DAY.matches(t))
    }

    private fun h(i: Int) = lines[i].bottom - lines[i].top
    private fun centred(l: OcrLine): Boolean {
        val span = (right - left).coerceAtLeast(1)
        return kotlin.math.abs((l.left + l.right) / 2.0 - (left + right) / 2.0) <= CENTRE * span && l.right - l.left <= 0.6 * span
    }

    /** The chat's text line height: the median of its lines that are not a time alone (stamps) nor a date line. */
    private fun textMedian(rest: List<Int>, dates: List<Int>): Double? =
        rest.filter { it !in dates && !TIME_ALONE.matches(lines[it].text.trim()) }
            .map(::h).sorted().let { if (it.isEmpty()) null else it[it.size / 2].toDouble().coerceAtLeast(1.0) }

    /**
     * Line [i] sits in a run of larger lines (a picture's text, a poster shared into the chat): counting from the line
     * nearest above it and from the line nearest below it, each close to the next, the unbroken lines [LARGER] x the chat's
     * text line number at least two (up to [RUN_LINES] a side). A chat's separator has a message of the chat's text size
     * next to it, so a picture one message away does not count.
     */
    private fun inLargerRun(i: Int, rest: List<Int>, med: Double): Boolean {
        val l = lines[i]
        fun count(side: List<Int>): Int {
            var prev = i; var n = 0
            for (j in side.take(RUN_LINES)) {
                val gap = if (lines[j].top >= lines[prev].bottom) lines[j].top - lines[prev].bottom else lines[prev].top - lines[j].bottom
                if (gap > RUN_GAP * maxOf(h(j), h(prev)) || h(j) < LARGER * med) break
                n++
                prev = j
            }
            return n
        }
        val above = rest.filter { it != i && lines[it].bottom <= l.top + 1 }.sortedByDescending { lines[it].bottom }
        val below = rest.filter { it != i && lines[it].top >= l.bottom - 1 }.sortedBy { lines[it].top }
        return count(above) + count(below) >= 2
    }

    /**
     * The message date lines, strictly: a short centred line holding only a date, with message lines below it, not in a
     * run of larger lines ([inLargerRun]), and matching the chat's other separators in size and place (when there are
     * several, those far from their median height or centre are not separators).
     */
    private fun separators(loose: List<Int>, rest: List<Int>): List<Int> {
        val med = textMedian(rest, loose) ?: return loose
        val ok = loose.filter { i ->
            val l = lines[i]
            centred(l) && rest.any { it !in loose && lines[it].top > l.bottom } && !inLargerRun(i, rest, med)
        }
        if (ok.size < 2) return ok
        val hs = ok.map(::h).sorted(); val mh = hs[hs.size / 2].toDouble()
        val cs = ok.map { (lines[it].left + lines[it].right) / 2.0 }.sorted(); val mcx = cs[cs.size / 2]
        val span = (right - left).coerceAtLeast(1)
        return ok.filter { kotlin.math.abs(h(it) - mh) <= SEP_SIZE * mh && kotlin.math.abs((lines[it].left + lines[it].right) / 2.0 - mcx) <= CENTRE * span }
    }

    private fun messageDateAbove(top: Int): Int? = messageDates.filter { lines[it].top < top }.maxByOrNull { lines[it].top }

    private fun hasDate(b: Bubble) = c.dates.any { it.lineIndex in b.lines } || Dates.REL_PHRASE.containsMatchIn(b.text)

    /** The bubble's clauses, each with its rest (without dates, times and relative day words); status lines and acknowledgements left out. */
    private fun clauses(b: Bubble): List<Pair<String, String>> =
        CLAUSE.split(b.text).map { it.trim() }.filter { it.isNotEmpty() }
            .filterNot { STATUS.matches(it) || ACK.matches(it) || Titles.GENERIC_LINE.matches(it) }
            .map { it to strip(it) }

    companion object {
        const val HEADER_BAND = 0.15
        const val MIN_CONTENT_WORDS = 2
        /** Lines of one bubble are at most this x the smaller line's height apart; bubbles are further apart (dev chats: 0.4-0.9 vs 1.15+). */
        const val BUBBLE_GAP = 1.0
        private const val CENTRE = 0.1
        /** Separators of one chat agree in height within this share of their median. */
        const val SEP_SIZE = 0.3
        /** Lines this x the chat's text line, close by, make a run of larger lines. */
        const val LARGER = 1.5
        const val RUN_GAP = 2.5
        const val RUN_LINES = 3
        private val TIME_ALONE = Regex("""^\W*\d{1,2}[:.]\d{2}\s*(?:[ap]\.?\s?m\.?)?\W*$""", RegexOption.IGNORE_CASE)
        private const val LEAP_YEAR = 2024   // a year with 29 Feb, for a message date printed without its year

        private val PRESENCE = regex("""(?:^|\s)(?:online|typing\W*|last seen\b.*|active now|tap here for .*info)\W*$""")
        /** A message date line may carry the time the next message was sent: "27 Dec, 11:33", "Today 9:41 AM", "Mon at 18:05". */
        private const val SENT_CLOCK = """(?:,?\s+(?:at\s+)?\d{1,2}[:.]\d{2}\s*(?:[ap]\.?\s?m\.?)?)?"""
        private val ABSOLUTE_DAY = regex(
            """^\W*(?:$WEEKDAY\.?,?\s+)?(?:\d{1,2}(?:st|nd|rd|th)?\s+$MONTH\.?|$MONTH\.?\s+\d{1,2}(?:st|nd|rd|th)?)(?:,?\s+\d{4})?$SENT_CLOCK\W*$""",
        )
        private val RELATIVE_DAY = regex("""^\W*(?:today|yesterday|$WEEKDAY)$SENT_CLOCK\W*$""")
        private val WEEKDAY_WORD = regex(
            """\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b|\b(?:this|next|coming|on)\s+(?:mon|tue|tues|wed|thu|thurs|fri|sat|sun)\b""",
        )
        private val COUNTED = regex("""\b(day\s+after\s+tomorrow|tomorrow|today|tonight)\b""")
        private val OFFSET = mapOf("today" to 0L, "tonight" to 0L, "tomorrow" to 1L, "day after tomorrow" to 2L)
        private val ACK = regex(
            """^\W*(?:(?:ok(?:ay)?|k+|sure|thanks|thank\s+you|thx|ty|see\s+you(?:\s+(?:then|there|soon))?|cool|great|done|noted|yes|yeah|yep|""" +
                """perfect|fine|alright|got\s+it|sounds\s+good|will\s+do)\W*)+$""",
        )
        private val STATUS = regex("""^\W*(?:(?:booking|appointment|order|payment)\s+)?(?:is\s+|has\s+been\s+)?(?:confirmed|scheduled|booked)\W*$""")
        private val CLAUSE = Regex("""[.!?;,]+(?=\s|$)|\s+\|\s+|\n""")
        private val DAY_ORDINAL = regex("""\b(?:the\s+)?\d{1,2}(?:st|nd|rd|th)\b""")
        private val PLACE = regex("""\b(?:venue|location|address|where)\s*[:\-–].*$""")   // the place, not the event
        private val GREETING = regex("""^(?:hi+|hello|hey|dear|greetings|good\s+(?:morning|afternoon|evening))\b[\s,!]*(?:all|guys|sir|madam|team|everyone)?[\s,!]*""")
        private val CUT_FILLER = regex("""(?:\b(?:at|on|by|from|around|about|this|next|coming|till|until|for|is|of)\s*)*\u0001""")
        private val LEADING = regex("""^(?:(?:on|at|by|from|for|is|this|next|coming|and|so)\s+)+""")
        private val TRAILING = regex("""(?:\s+(?:on|at|this|next|coming|from|by|is|are|will|be|the|for|and|$WEEKDAY))+\s*$""")
        private val WORD = Regex("""[A-Za-z][A-Za-z'’]+""")

        /** Words that say nothing about the event. */
        private val STOP = (
            "i me my we us our you your he him his she her it its they them their this that these those there here " +
                "a an the and or but so if then than to of at on in for by from with about as into up out over " +
                "is am are was were be been being do does did have has had will would shall should can could may might must " +
                "not no yes yeah yep ok okay sure thanks thank cool great fine good nice perfect done noted alright " +
                "hi hello hey dear sir madam bro please pls also just only very really too " +
                "come coming go going see free works work let lets what when where how who which why any some all " +
                "actually better instead make made wait sorry maybe plan know tell lol send sent get got still now then " +
                "date time venue " +
                "i'll we'll i'm it's let's don't can't you're that's there's i’ll we’ll i’m it’s let’s don’t"
            ).split(" ").toSet()

        private fun holdsDateOrTime(s: String) =
            Dates.findDates(s).isNotEmpty() || TimeParser.parse(s).isNotEmpty() || Dates.REL_PHRASE.containsMatchIn(s)

        fun words(s: String): List<String> = WORD.findAll(s).map { it.value }.toList()

        fun contentWords(s: String): Int = words(s).count { it.lowercase() !in STOP }

        /** The words of [s] that say something about the event (lower case, not in the stop list). */
        fun contentSet(s: String): Set<String> = words(s).map { it.lowercase() }.filter { it !in STOP }.toSet()

        /** [clause] without its dates, times, relative day words, a leading greeting and the filler words around them. */
        fun strip(clause: String): String {
            val cut = (Dates.findDates(clause).map { it.start until it.end } +
                TimeParser.parse(clause).map { it.startOffset until it.endOffset } +
                Dates.REL_PHRASE.findAll(clause).map { it.range } + DAY_ORDINAL.findAll(clause).map { it.range } +
                PLACE.findAll(clause).map { it.range }).sortedBy { it.first }
            val sb = StringBuilder()
            var i = 0
            for (r in cut) {
                if (r.first > i) sb.append(clause, i, r.first)
                if (r.last + 1 > i) sb.append('\u0001')
                i = maxOf(i, r.last + 1)
            }
            if (i < clause.length) sb.append(clause, i, clause.length)
            val s = CUT_FILLER.replace(sb.toString(), " ").replace(Regex("""\s+"""), " ").trim()
            return TRAILING.replace(LEADING.replace(GREETING.replace(s, ""), ""), "")
                .trim().trim('?', '!', '.', ',', '-', '–', ':').trim()
        }
    }
}
