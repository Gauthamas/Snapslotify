package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.extract.Patterns.regex
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.text.TitleJunk

/**
 * An event's title, found by searching outward from its date (or time, or day word).
 *
 * The page is cut into blocks: a chat's bubbles; elsewhere paragraphs, cards and rows ([`in`.gauthama.snapslotify.core.layout.Layout.blocks]).
 * The date's own block is read first, then one block at a time above and below it, at most [MAX_BLOCKS] blocks or
 * [MAX_LINES] lines away, never past a day separator (a chat's message date line). A block of filler only (no content
 * words: "ok", "sure", "thanks", an emoji, a bare question such as "When?") is passed over and not counted.
 *
 * Each clause (a block's text cut at sentence marks, commas and "and"/"but") is a candidate phrase. In the date's own
 * clause, the words before the date (else after it); quoted words, when there are any, are the phrase. Never a
 * salutation line ("Hi Asha,"), a web or mail address, or a line chrome already took out (status bars, bars, sender
 * lines, advert headlines are not in the lines at all).
 *
 * Score: content words ([CONTENT], up to [MAX_CONTENT]); a name or place (a capitalised word after the first, [NAME]);
 * quoted words ([QUOTED]); a possessive ([POSSESSIVE]); heading type ([HEADING]); a subject line ([SUBJECT]); the date's
 * own clause ([SAME_CLAUSE]) or block ([SAME_BLOCK]); minus [CROSSED] per block crossed, [LONG] per word past
 * [LONG_WORDS], [QUESTION] for a question. In a chat, the same speaker as the date's message ([SAME_SPEAKER]), and the
 * message a bare question just above the date's message replies to ([REPLIED]). The best phrase is the title when it
 * scores at least [THRESHOLD]; otherwise none ([search] null: the caller falls back, and flags the title).
 */
object TitleSearch {

    const val MAX_BLOCKS = 3
    const val MAX_LINES = 10
    const val THRESHOLD = 1.2
    const val CONTENT = 0.6
    const val MAX_CONTENT = 5
    const val NAME = 0.8
    const val QUOTED = 1.5
    const val POSSESSIVE = 0.5
    const val HEADING = 1.5
    const val HEADING_TYPE = 1.3
    const val SUBJECT = 1.5
    const val SAME_CLAUSE = 2.5
    const val SAME_BLOCK = 0.5
    const val CARD = 2.0
    const val CROSSED = 0.6
    const val TYPE_CHANGE = 1.6
    const val DOWN_IN_CHAT = 0.5
    const val LOW_CONFIDENCE = 0.7
    const val UNREAD = 1.0
    const val BLOCK_GAP = 0.8
    const val ADDRESS_PENALTY = 1.5
    const val LONG = 0.2
    const val LONG_WORDS = 7
    const val QUESTION = 0.8
    const val SAME_SPEAKER = 0.4
    const val REPLIED = 1.0
    const val MAX_TITLE_WORDS = 12

    enum class Source { SAME_BLOCK, EXPANDED }

    /** [weak]: the phrase was read from lines the OCR was unsure of: the title is shown but flagged. */
    /** [line]: the line the phrase was read from (-1: a phrase run over several lines). */
    class Pick(val title: String, val source: Source, val score: Double, val weak: Boolean = false, val line: Int = -1)

    /** A block: its lines (indices into the page's lines, top to bottom) and, in a chat, whether it is an incoming message. */
    class Block(val lines: List<Int>, val incoming: Boolean? = null)

    private const val MARK = '\u0001'
    private const val OTHER = '\u0002'

    /** A message stamp inside a bubble, read or misread ("9:12 PM", "1111 PM"): not a content word. */
    private val STAMP = Regex("""\b\d{1,4}(?::\d{2})?\s*[ap]\.?m\b\.?""", RegexOption.IGNORE_CASE)

    /** A street address: a house number then a capitalised word ("26 Lake Rd"): the place, not the event. */
    private val STREET = Regex("""\b\d{1,6}\s+[A-Z][a-z]+""")
    /** A clause opening with "at" (after any marks): a place, not the event's name. */
    private val AT_PLACE = Regex("""^\W*at\s+\p{L}""", RegexOption.IGNORE_CASE)

    /** A place field ("Venue: City Hall"): the place, not the event; taken out before clauses are cut at colons. */
    private val PLACE_FIELD = regex("""\b(?:venue|location|address|where)\s*[:\-–].*$""")

    /**
     * A page's blocks (not a chat's): lines stacked with gaps up to [BLOCK_GAP] line heights, left edges aligned; a line
     * that sits on the same row just right of a block's line (OCR cut the line in two) joins that block, in reading order.
     */
    fun pageBlocks(lines: List<OcrLine>): List<Block> {
        // stacked lines, split where the type size changes (a heading over its subtitle is two blocks)
        val blocks = `in`.gauthama.snapslotify.core.layout.Layout.blocks(lines, BLOCK_GAP).flatMap { b ->
            val parts = mutableListOf(mutableListOf(b.first()))
            for (i in b.drop(1)) {
                val a = lines[parts.last().last()]; val h1 = a.bottom - a.top; val h2 = lines[i].bottom - lines[i].top
                if (maxOf(h1, h2) > TYPE_CHANGE * minOf(h1, h2)) parts += mutableListOf(i) else parts.last() += i
            }
            parts
        }.toMutableList()
        fun beside(a: OcrLine, b: OcrLine): Boolean {
            val h = maxOf(a.bottom - a.top, b.bottom - b.top)
            val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            return overlap >= 0.5 * minOf(a.bottom - a.top, b.bottom - b.top) && b.left >= a.right - h / 2 && b.left - a.right <= 2 * h
        }
        var moved = true
        while (moved) {
            moved = false
            for (bi in blocks.indices) {
                val b = blocks[bi]
                if (b.size != 1) continue
                val host = blocks.indices.firstOrNull { ai -> ai != bi && blocks[ai].any { beside(lines[it], lines[b[0]]) } } ?: continue
                blocks[host].add(b[0]); blocks[host].sortWith(compareBy({ lines[it].top }, { lines[it].left }))
                blocks.removeAt(bi); moved = true; break
            }
        }
        // a heading set over two or three centred lines ("Photography" / "Walk", "BLOOD" / "DONATION" / "CAMP"): lines in
        // heading type, centres aligned, each just under the one above, are one block (their left edges differ)
        val median = lines.map { it.bottom - it.top }.sorted().let { if (it.isEmpty()) 1 else it[it.size / 2].coerceAtLeast(1) }
        fun headingType(i: Int) = (lines[i].bottom - lines[i].top) >= HEADING_TYPE * median
        fun centred(a: OcrLine, b: OcrLine) = kotlin.math.abs((a.left + a.right) - (b.left + b.right)) / 2.0 <= CENTRE_ALIGN * maxOf(a.bottom - a.top, b.bottom - b.top)
        var joined = true
        while (joined) {
            joined = false
            val order = blocks.sortedBy { b -> b.minOf { lines[it].top } }
            for (k in 0 until order.size - 1) {
                val a = order[k]; val b = order[k + 1]
                val la = a.maxByOrNull { lines[it].bottom }!!; val lb = b.minByOrNull { lines[it].top }!!
                val gap = lines[lb].top - lines[la].bottom
                // short lines in the same type (two names whose "&" was not read) may sit a little further apart
                val short = sameType(lines[la], lines[lb]) && listOf(la, lb).all { lines[it].text.trim().split(Regex("""\s+""")).size <= 2 }
                if (a.all(::headingType) && b.all(::headingType) && centred(lines[la], lines[lb]) &&
                    gap <= (if (short) NAMES_GAP else BLOCK_GAP) * minOf(lines[la].bottom - lines[la].top, lines[lb].bottom - lines[lb].top)) {
                    blocks.remove(b); a.addAll(b); a.sortWith(compareBy({ lines[it].top }, { lines[it].left })); joined = true; break
                }
            }
        }
        // two names in heading type with a connector line between them ("Meera" / "&" / "Arjun", "weds", "and", "+"):
        // one heading, read across the connector
        joined = true
        while (joined) {
            joined = false
            val order = blocks.sortedBy { b -> b.minOf { lines[it].top } }
            for (k in 0 until order.size - 2) {
                val a = order[k]; val m = order[k + 1]; val b = order[k + 2]
                if (m.size != 1 || !CONNECTOR.matches(lines[m[0]].text.trim())) continue
                val la = lines[a.maxByOrNull { lines[it].bottom }!!]; val lm = lines[m[0]]; val lb = lines[b.minByOrNull { lines[it].top }!!]
                val hh = maxOf(la.bottom - la.top, lb.bottom - lb.top)
                if (a.all(::headingType) && b.all(::headingType) && sameType(la, lb) && centred(la, lm) && centred(lm, lb) &&
                    lm.top - la.bottom <= CONNECTOR_GAP * hh && lb.top - lm.bottom <= CONNECTOR_GAP * hh) {
                    blocks.remove(m); blocks.remove(b); a.addAll(m); a.addAll(b); a.sortWith(compareBy({ lines[it].top }, { lines[it].left })); joined = true; break
                }
            }
        }
        return blocks.sortedBy { b -> b.minOf { lines[it].top } }.map { Block(it) }
    }

    /** Two heading lines are centred on each other when their centres are within this many line heights. */
    const val CENTRE_ALIGN = 0.6
    /** Two heading lines are in the same type when the taller is at most this many times the other. */
    const val SAME_TYPE = 1.25
    /** Two short centred heading lines in the same type join across a gap of up to this many line heights. */
    const val NAMES_GAP = 1.2
    /** The gap above and below a connector line between two names, at most, in the names' line heights. */
    const val CONNECTOR_GAP = 1.5

    private fun sameType(a: OcrLine, b: OcrLine) = maxOf(a.bottom - a.top, b.bottom - b.top) <= SAME_TYPE * minOf(a.bottom - a.top, b.bottom - b.top).coerceAtLeast(1)

    /** A line that only joins two names: "&", "and", "weds", "with", "+", "x", a heart. */
    private val CONNECTOR = Regex("""^(?:&|and|weds|wed|with|\+|x|×|♥|❤|❤️|♡)$""", RegexOption.IGNORE_CASE)
    /** "and" as a connector reads as "&", so the clause cut at "and" does not split the two names. */
    private val AND_LINE = Regex("""^\s*and\s*$""", RegexOption.IGNORE_CASE)

    /** A word broken by a hyphen at a line end ("Health Check-" / "up Camp"): one word again. */
    private val HYPHEN_BREAK = Regex("""(\p{L})-\s+(\p{Ll})""")
    /** The same in capitals ("KITE FESTI-" / "VAL"): the space goes, the hyphen stays (it may be a real one, "CHECK-UP"). */
    private val CAPS_HYPHEN_BREAK = Regex("""(\p{Lu})-\s+(\p{Lu})""")
    private val CLAUSE_END = Regex("""[.!?;]+(?=\s|$)|[,:](?=\s)|\s+(?:and|but)\s+|\s+\|\s+|\n""", RegexOption.IGNORE_CASE)
    private val ADDRESS = regex("""https?:|www\.|\S+@\S+|\.(?:com|org|net|in)\b""")
    private val QUOTE = Regex("""["“”]([^"“”]{3,80})["“”]""")
    private val SUBJECT_LABEL = regex("""^\W*(?:sub|subject)\s*[:\-]\s*""")
    private val LEADING = regex("""^(?:(?:and|so|but|on|at|by|for|from|to|is|are|this|next|coming)\s+)+""")

    /** A status line ("Booking confirmed"): what happened to a booking, not the event's name. */
    private val STATUS = regex("""^\W*(?:(?:booking|appointment|order|payment|reservation|ticket)\s+)?(?:is\s+|has\s+been\s+)?(?:confirmed|scheduled|booked|cancelled|canceled)\W*$""")
    private val TRAILING = regex("""(?:\s+(?:on|at|by|for|from|to|is|are|was|were|will|be|this|next|coming|up|the|a|an|of|in|and|@))+\s*$""")
    private val CAPITALISED = Regex("""^[A-Z][a-z]""")

    /**
     * The title for an event whose date (or time, or day word) is on [anchorLine], or null when no phrase scores enough.
     * [lines] and [c] are the page's; [blocks] its blocks top to bottom; [separators]: lines the search never crosses.
     */
    fun search(
        lines: List<OcrLine>, c: Candidates, blocks: List<Block>, anchorLine: Int, separators: Set<Int> = emptySet(),
        anchor: IntRange? = null, subjects: Set<Int> = emptySet(), screen: Boolean = false,
    ): Pick? {
        val home = blocks.indexOfFirst { anchorLine in it.lines }
        if (home < 0) return null
        val median = lines.map { it.bottom - it.top }.sorted().let { it[it.size / 2] }.coerceAtLeast(1)
        val salutations = lines.indices.filter { salutation(lines, it) }.toSet()
        val own = anchor ?: c.lineRange(anchorLine)
        // a card's date block: the date's line holds only the date and time; the line right above it in its block is its heading
        val card = blocks[home].lines.indexOf(anchorLine).takeIf { screen && it > 0 && c.lineRest(anchorLine).count(Char::isLetter) <= 3 }
            ?.let { blocks[home].lines[it - 1] }
        val phrases = blocks.map { b -> phrases(lines, c, b, salutations, median, own, subjects) }
        // a block of filler: at most one content word, a question or nothing ("ok", "Wow!! When?")
        fun blockText(k: Int) = blocks[k].lines.joinToString(" ") { lines[it].text }
        fun bare(k: Int) = ChatBubbles.contentWords(ChatBubbles.strip(STAMP.replace(blockText(k), " "))).let { n -> n == 0 || (n <= 1 && '?' in blockText(k)) }
        fun filler(k: Int) = bare(k)
        val chat = blocks[home].incoming != null

        // the message a bare question just above the date's message replies to
        // (any page: a question and its answer, "Cool... when and where?" then the date, also outside a chat app)
        // (whoever sent it: a short message's side is not a reliable sign of its sender)
        // a block with nothing in it (a stray stamp, an emoji): passed over when looking for the question and its message
        fun empty(k: Int) = '?' !in blockText(k) && ChatBubbles.contentWords(ChatBubbles.strip(STAMP.replace(blockText(k), " "))) == 0
        var q = home - 1
        while (q >= 0 && empty(q)) q--
        val replied = q.takeIf { it >= 0 && bare(it) && '?' in blockText(it) }?.let { var r = it - 1; while (r >= 0 && empty(r)) r--; r.takeIf { x -> x >= 0 } }

        var best: Pick? = null
        for (dir in listOf(0, -1, 1)) {
            var k = home + dir
            var crossed = 0
            var lineCount = 0
            if (dir == 0) k = home
            while (k in blocks.indices) {
                if (dir != 0) {
                    val between = if (dir < 0) lines[blocks[k].lines.last()].bottom..lines[blocks[home].lines.first()].top
                    else lines[blocks[home].lines.last()].bottom..lines[blocks[k].lines.first()].top
                    if (separators.any { lines[it].top in between }) break
                    lineCount += blocks[k].lines.size
                    if (lineCount > MAX_LINES) break
                }
                val crossedHere = crossed
                for (p in phrases[k]) {
                    if (p.content == 0) continue
                    var s = CONTENT * minOf(p.content, MAX_CONTENT) + (if (p.name) NAME else 0.0) + (if (p.quoted) QUOTED else 0.0) +
                        (if (p.possessive) POSSESSIVE else 0.0) + (if (p.heading && !chat) HEADING else 0.0) + (if (p.subject) SUBJECT else 0.0) -
                        LONG * maxOf(0, p.words - LONG_WORDS) - (if (p.question) QUESTION else 0.0)
                    if (dir == 0) s += if (p.marked) SAME_CLAUSE else SAME_BLOCK
                    if (dir == 0 && card != null && p.line == card) s += CARD
                    if (dir != 0) s -= CROSSED * (crossedHere + 1)
                    if (chat && dir > 0) s -= DOWN_IN_CHAT
                    if (p.unread) s -= UNREAD
                    if (p.address) s -= ADDRESS_PENALTY
                    if (chat && dir != 0 && blocks[k].incoming == blocks[home].incoming) s += SAME_SPEAKER
                    if (k == replied) s += REPLIED
                    debug?.invoke("block $k dir $dir crossed $crossedHere score ${"%.2f".format(s)} '${p.text}'")
                    if (s >= THRESHOLD && (best == null || s > best.score)) best = Pick(p.text, if (k == home) Source.SAME_BLOCK else Source.EXPANDED, s, p.unread, p.line)
                }
                if (dir == 0) break
                if (!filler(k)) crossed++
                if (crossed >= MAX_BLOCKS) break
                k += dir
            }
        }
        return best
    }

    private class Phrase(
        val text: String, val content: Int, val words: Int, val marked: Boolean, val question: Boolean, val name: Boolean,
        val quoted: Boolean, val possessive: Boolean, val heading: Boolean, val subject: Boolean, var unread: Boolean = false,
        val address: Boolean = false, var line: Int = -1,
    )

    /** Dev only: each candidate's score. */
    @Volatile var debug: ((String) -> Unit)? = null

    /** A salutation line: a few words ending in a comma, opening its block ("Hi Asha,", "Dear all,"). */
    private fun salutation(lines: List<OcrLine>, i: Int): Boolean {
        val t = lines[i].text.trim()
        return t.endsWith(",") && t.split(Regex("""\s+""")).size <= 6 && Dates.findDates(t).isEmpty()
    }

    private fun phrases(lines: List<OcrLine>, c: Candidates, b: Block, salutations: Set<Int>, median: Int, own: IntRange, subjects: Set<Int>): List<Phrase> {
        val spans = c.dates.map { it.offset until it.end } + c.times.map { it.offset until it.endOffset } +
            Dates.REL_PHRASE.findAll(c.text).map { it.range } + AnchoredDay.WORD.findAll(c.text).map { it.range }
        val out = mutableListOf<Phrase>()
        for (li in b.lines) {
            if (li in salutations || li !in c.lineStarts.indices) continue
            // a line of junk only is never a title
            if (TitleJunk.junkLine(lines[li])) continue
            val r = c.lineRange(li)
            val raw = c.text.substring(r.first, r.last + 1)
            if (ADDRESS.containsMatchIn(raw) && ChatBubbles.contentWords(ADDRESS.replace(raw, " ")) == 0) continue
            var line = masked(c, r, spans, own)
            val subject = SUBJECT_LABEL.containsMatchIn(line) || li in subjects
            line = SUBJECT_LABEL.replace(line, "")
            val heading = (lines[li].bottom - lines[li].top) >= HEADING_TYPE * median
            // a block's lines read as one text: a clause may run over a line break
            val unread = (lines[li].confidence ?: 1f) < LOW_CONFIDENCE
            out += clauses(line).mapNotNull { (clause, question) -> phrase(clause, question, heading, subject)?.also { it.unread = unread; it.line = li } }
        }
        return joinRunOn(out, b, lines, c, salutations, median, spans, own)
    }

    /** Line range [r] of the text with the event's own date ([own]) as one [MARK] and every other date or time as [OTHER]. */
    private fun masked(c: Candidates, r: IntRange, spans: List<IntRange>, own: IntRange): String {
        val chars = c.text.substring(r.first, r.last + 1).toCharArray()
        for (s in spans) {
            val mine = s.first <= own.last && s.last >= own.first
            for (k in maxOf(s.first, r.first)..minOf(s.last, r.last)) chars[k - r.first] = if (mine) MARK else OTHER
        }
        return PLACE_FIELD.replace(String(chars), "").replace(Regex("""$MARK+"""), MARK.toString()).replace(Regex("""$OTHER+"""), OTHER.toString())
    }

    /** The clauses of a block's text, read across its line breaks, with whether each ends in a question mark. */
    private fun joinRunOn(
        perLine: List<Phrase>, b: Block, lines: List<OcrLine>, c: Candidates, salutations: Set<Int>, median: Int, spans: List<IntRange>,
        own: IntRange,
    ): List<Phrase> {
        if (b.lines.size < 2) return perLine
        // a line runs on into the next when the sentence goes on: never into a line that starts with a date or time (a
        // date line under a heading or label starts its own clause); and only from a line of more than three words, into
        // a line that starts in lower case, or between two lines of heading type (a title set over two lines)
        val kept = b.lines.filter { it !in salutations && it in c.lineStarts.indices && !TitleJunk.junkLine(lines[it]) }
        val texts = kept.map { li -> SUBJECT_LABEL.replace(masked(c, c.lineRange(li), spans, own), "").let { if (AND_LINE.matches(it)) "&" else it } }
        fun connector(n: Int) = CONNECTOR.matches(lines[kept[n]].text.trim())
        fun headingType(li: Int) = (lines[li].bottom - lines[li].top) >= HEADING_TYPE * median
        val parts = kept.indices.map { n ->
            val t = texts[n]
            if (n == kept.size - 1) return@map t
            val next = texts[n + 1].trimStart()
            // a line OCR made of junk only (a sticker, a logo, a pattern) runs on into nothing and nothing runs on into it
            val junk = TitleJunk.junkLine(lines[kept[n]]) || TitleJunk.junkLine(lines[kept[n + 1]])
            val runsOn = !junk && next.firstOrNull() != MARK && next.firstOrNull() != OTHER &&
                (t.trim().split(' ').count { it.isNotBlank() } > 3 || next.firstOrNull()?.isLowerCase() == true || (headingType(kept[n]) && headingType(kept[n + 1])) ||
                    connector(n) || connector(n + 1))
            if (runsOn) t else "$t\n"
        }
        val text = parts.joinToString(" ")
        // where each line starts in the joined text
        val starts = parts.runningFold(0) { at, t -> at + t.length + 1 }
        val heading = b.lines.filterNot { CONNECTOR.matches(lines[it].text.trim()) }.all { (lines[it].bottom - lines[it].top) >= HEADING_TYPE * median }
        // a clause is read weakly when most of the lines it comes from are (a weak stamp elsewhere in the block does not count)
        fun unread(from: Int, to: Int): Boolean {
            val spanned = kept.indices.filter { n -> starts[n] < to && starts[n] + parts[n].length > from }.map { kept[it] }
            return spanned.isNotEmpty() && 2 * spanned.count { (lines[it].confidence ?: 1f) < LOW_CONFIDENCE } > spanned.size
        }
        val whole = clauseSpans(text).mapNotNull { (range, question) ->
            phrase(text.substring(range.first, range.last + 1).replace(HYPHEN_BREAK, "$1-$2").replace(CAPS_HYPHEN_BREAK, "$1-$2"), question, heading, false)?.also {
                it.unread = unread(range.first, range.last + 1)
                // the line the clause starts on (a clause run over lines is read from its first line)
                it.line = kept.getOrElse(kept.indices.lastOrNull { n -> starts[n] <= range.first } ?: 0) { -1 }
            }
        }
        // a line that is its own clause keeps its own (subject, heading) reading; clauses across lines are added
        // the date's own line has its own words before the date: a clause run on from the line above adds nothing
        val ownLine = perLine.any { it.marked && it.content >= 2 }
        return perLine.filter { p -> whole.none { it.text == p.text && it.marked && !p.marked } } +
            whole.filter { w -> perLine.none { it.text == w.text && (it.marked || !w.marked) } && !(ownLine && w.marked) }
    }

    /** As [clauses], with each clause's place in [text]. */
    private fun clauseSpans(text: String): List<Pair<IntRange, Boolean>> {
        val out = mutableListOf<Pair<IntRange, Boolean>>()
        var at = 0
        for (m in CLAUSE_END.findAll(text)) {
            out += (at until m.range.first) to m.value.contains('?')
            at = m.range.last + 1
        }
        out += (at until text.length) to false
        return out.filter { !it.first.isEmpty() && text.substring(it.first.first, it.first.last + 1).isNotBlank() }
    }

    private fun clauses(text: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        var at = 0
        for (m in CLAUSE_END.findAll(text)) {
            out += text.substring(at, m.range.first) to m.value.contains('?')
            at = m.range.last + 1
        }
        out += text.substring(at) to false
        return out.filter { it.first.isNotBlank() }
    }

    /** An amount or a reference number ("Rs 299", "Booking ID TX66", "Total 815"): a price or a record, never the event's name. */
    private val RECORD = regex("""\b(?:id|ref|pnr|order|invoice|txn|total)\b\W*\w*\d""")

    private fun phrase(clause: String, question: Boolean, heading: Boolean, subject: Boolean): Phrase? {
        if (BillDue.MONEY.containsMatchIn(clause) || RECORD.containsMatchIn(clause)) return null
        val marked = MARK in clause
        var t = clause
        val quote = QUOTE.find(t)?.groupValues?.get(1)?.takeIf { ChatBubbles.contentWords(it) > 0 }
        var own = marked
        if (quote != null) t = quote
        else if (marked) {
            val before = t.substringBefore(MARK); val after = t.substringAfterLast(MARK)
            // the words after the date are its details (a venue, a note): they count as the same block only
            if (ChatBubbles.contentWords(before) > 0) t = before else { t = after; own = false }
        }
        val street = STREET.containsMatchIn(t.replace(MARK, ' ').replace(OTHER, ' ')) || AT_PLACE.containsMatchIn(t)
        t = ChatBubbles.strip(t.replace(MARK, ' ').replace(OTHER, ' '))
        t = ADDRESS.replace(t, " ")
        t = TRAILING.replace(LEADING.replace(t.trim(), ""), "").trim().trim { !it.isLetterOrDigit() && it != '"' && it != ')' && it != '\'' }
        if (STATUS.matches(t)) return null
        t = Titles.plain(t) ?: return null
        val ws = t.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (ws.isEmpty()) return null
        val title = ws.take(MAX_TITLE_WORDS).joinToString(" ")
        val content = ChatBubbles.contentWords(title)
        return Phrase(
            title, content, ws.size, own, question, ws.drop(1).any { CAPITALISED.containsMatchIn(it) }, quote != null,
            Regex("""\w['’]s\b|\ws['’](?=\s|$)""").containsMatchIn(title), heading, subject, address = street,
        )
    }
}
