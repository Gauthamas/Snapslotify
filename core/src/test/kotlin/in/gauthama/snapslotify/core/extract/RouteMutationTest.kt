package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrJson
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.ocr.OcrWord
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Standing mutation test: small OCR changes that must not change a page's route. On one invented page of each route
 * it must pass; with ROUTE_MUT_OCR (OCR files, ';'), ROUTE_MUT_OUT and ROUTE_MUT_TODAY it also runs every mutation on
 * every page of those files and writes one line per page and mutation (route before and after, and the signals that
 * changed), for the dev and test sets (counts and routes only).
 */
class RouteMutationTest {

    /** Each mutation: a name and the change. A mutation that finds nothing to change leaves the page as it is. */
    object Mutations {
        private val WEEKDAY = Regex("""^\W*(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*\W*$""", RegexOption.IGNORE_CASE)
        private val WEEKDAY_TOKEN = Regex("""(?i)\b(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*\b""")
        private val DAY = Regex("""^\W*(?:[1-9]|[12]\d|3[01])\W*$""")

        private fun OcrResult.mapText(f: (String) -> String) =
            copy(lines = lines.map { l -> l.copy(text = f(l.text), words = l.words.map { it.copy(text = f(it.text)) }) })

        /**
         * The line of the first event's title as the page is read now (a line it contains, or that contains it); with no
         * event title, the tallest line with three or more letters.
         */
        private fun OcrResult.titleIndex(): Int? {
            val title = CodePipeline.run(this, LocalDate.of(2026, 11, 5)).events.firstOrNull()?.event?.title?.lowercase()?.trim()
            if (!title.isNullOrEmpty()) {
                lines.indices.firstOrNull { val t = lines[it].text.lowercase().trim(); t.length >= 3 && (t in title || title in t) }?.let { return it }
            }
            return lines.indices.filter { lines[it].text.count(Char::isLetter) >= 3 }.maxByOrNull { lines[it].bottom - lines[it].top }
        }

        private fun OcrResult.appendWord(word: String): OcrResult {
            val i = titleIndex() ?: return this
            val l = lines[i]
            val w = (l.bottom - l.top) * word.length / 2
            val add = OcrWord(word, l.right + 8, l.top, l.right + 8 + w, l.bottom)
            return copy(lines = lines.toMutableList().also { it[i] = l.copy(text = "${l.text} $word", right = l.right + 8 + w, words = if (l.words.isEmpty()) l.words else l.words + add) })
        }

        private fun OcrResult.insert(text: String, left: Int, top: Int, height: Int) =
            copy(lines = lines + OcrLine(text, left, top, left + height * text.length / 2, top + height, block = (lines.maxOfOrNull { it.block } ?: 0) + 1))

        private fun OcrResult.typicalHeight() = lines.map { it.bottom - it.top }.sorted().let { if (it.isEmpty()) 20 else it[it.size / 2] }

        /** The page turned by [degrees] about its middle; each box is the upright box around its turned corners, as the engine's is. */
        fun rotate(o: OcrResult, degrees: Double): OcrResult {
            val a = Math.toRadians(degrees); val c = cos(a); val s = sin(a); val cx = o.imageWidth / 2.0; val cy = o.imageHeight / 2.0
            fun box(l: Int, t: Int, r: Int, b: Int): IntArray {
                val px = mutableListOf<Double>(); val py = mutableListOf<Double>()
                for (x in listOf(l, r)) for (y in listOf(t, b)) { val mx = x - cx; val my = y - cy; px += cx + mx * c - my * s; py += cy + mx * s + my * c }
                return intArrayOf(px.min().roundToInt(), py.min().roundToInt(), px.max().roundToInt(), py.max().roundToInt())
            }
            return o.copy(lines = o.lines.map { l ->
                val k = box(l.left, l.top, l.right, l.bottom)
                l.copy(left = k[0], top = k[1], right = k[2], bottom = k[3], angle = l.angle?.plus(degrees.toFloat()),
                    words = l.words.map { w -> val m = box(w.left, w.top, w.right, w.bottom); w.copy(left = m[0], top = m[1], right = m[2], bottom = m[3]) })
            })
        }

        /**
         * A bent page: each point moved down by [dy] of its position across the page (0 to 1), and right by [dx] of its
         * position down the page, both in pixels. A line's box is the box around its moved corners and middle, so a long
         * line on a curled row grows taller, as the engine's box of a bent line does; its angle takes the local slope.
         */
        fun bend(o: OcrResult, dy: (Double) -> Double = { 0.0 }, dx: (Double) -> Double = { 0.0 }): OcrResult {
            val w = o.imageWidth.toDouble().coerceAtLeast(1.0); val h = o.imageHeight.toDouble().coerceAtLeast(1.0)
            fun box(l: Int, t: Int, r: Int, b: Int): IntArray {
                val xs = listOf(l.toDouble(), (l + r) / 2.0, r.toDouble()); val ys = listOf(t.toDouble(), b.toDouble())
                val px = xs.flatMap { x -> ys.map { y -> x + dx(y / h) } }; val py = xs.flatMap { x -> ys.map { y -> y + dy(x / w) } }
                return intArrayOf(px.min().roundToInt(), py.min().roundToInt(), px.max().roundToInt(), py.max().roundToInt())
            }
            return o.copy(lines = o.lines.map { l ->
                val b = box(l.left, l.top, l.right, l.bottom)
                val slope = (dy(l.right / w) - dy(l.left / w)) / (l.right - l.left).coerceAtLeast(1)
                l.copy(left = b[0], top = b[1], right = b[2], bottom = b[3], angle = l.angle?.plus(Math.toDegrees(kotlin.math.atan(slope)).toFloat()),
                    words = l.words.map { x -> val c = box(x.left, x.top, x.right, x.bottom); x.copy(left = c[0], top = c[1], right = c[2], bottom = c[3]) })
            })
        }

        /** Page-shape mutations: the reading must not change (events as well as route). */
        val SHAPE: List<Pair<String, (OcrResult) -> OcrResult>> = listOf(
            // a sheet lifting at its right edge, as a page off a stack does: the rows rise faster and faster, 3% of the width
            "right edge curls up" to { o -> bend(o, dy = { x -> -0.03 * o.imageWidth * x * x * x }) },
            // a sheet bowing: the middle of each row 1.5% of the width below its ends
            "page bows" to { o -> bend(o, dy = { x -> 0.06 * o.imageWidth * x * (1 - x) }) },
            // a sheet bending top to bottom: the columns lean out at the bottom, 2% of the width
            "columns bend" to { o -> bend(o, dx = { y -> 0.02 * o.imageWidth * y * y }) },
        )

        /** Full month names of five letters or more, as OCR damages them ([MonthRepair]). */
        private val LONG_MONTH = Regex("""(?i)\b(?:january|february|march|april|august|september|october|november|december)\b""")

        /** Each long month name changed in its middle by [f] (dropped, doubled or swapped letters). */
        private fun OcrResult.damageMonths(f: (String, Int) -> String) = mapText { t -> LONG_MONTH.replace(t) { m -> f(m.value, m.value.length / 2) } }

        /** OCR damage in month names: the reading must not change. */
        val MONTHS: List<Pair<String, (OcrResult) -> OcrResult>> = listOf(
            "month letter dropped" to { o -> o.damageMonths { w, k -> w.removeRange(k, k + 1) } },
            "month letter doubled" to { o -> o.damageMonths { w, k -> w.substring(0, k + 1) + w.substring(k) } },
            "month letters swapped" to { o -> o.damageMonths { w, k -> w.substring(0, k) + w[k + 1] + w[k] + w.substring(k + 2) } },
        )

        private const val MON = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)"
        private val DAY_BEFORE_MONTH = Regex("""(?i)(?<![\w./:-])\d{1,2}(?=(?:st|nd|rd|th)?\s+(?:of\s+)?$MON[a-z]*\b)""")
        private val DAY_AFTER_MONTH = Regex("""(?i)(?<=\b$MON[a-z]{0,6}\.?\s)\d{1,2}(?![\w/:-])""")
        private val HOUR = Regex("""(?i)(?<![\w./:-])\d{1,2}(?=(?:[:.]\d{2})?\s*[ap]\.?\s?m\b)""")

        /** OCR damage at the front of a number ([LeadRepair]): a stray digit stuck to every day by a month name, or every hour before AM/PM. */
        val PREPEND: List<Pair<String, (OcrResult) -> OcrResult>> = listOf("1", "7", "9").flatMap { d ->
            listOf<Pair<String, (OcrResult) -> OcrResult>>(
                "digit $d before a day" to { o -> o.mapText { t -> DAY_AFTER_MONTH.replace(DAY_BEFORE_MONTH.replace(t) { d + it.value }) { d + it.value } } },
                "digit $d before an hour" to { o -> o.mapText { t -> HOUR.replace(t) { d + it.value } } },
            )
        }

        val ALL: List<Pair<String, (OcrResult) -> OcrResult>> = listOf<Pair<String, (OcrResult) -> OcrResult>>(
            "O for 0" to { o -> o.mapText { it.replace('0', 'O') } },
            "S for 5" to { o -> o.mapText { it.replace('5', 'S') } },
            "bill word in title" to { o -> o.appendWord("Bill") },
            "ticket word in title" to { o -> o.appendWord("Ticket") },
            "stray Message line" to { o -> o.insert("Message", o.imageWidth / 20, (o.imageHeight * 0.93).toInt(), o.typicalHeight()) },
            "extra noise line" to { o ->
                val mid = o.lines.sortedBy { it.top }.getOrNull(o.lines.size / 2)
                if (mid == null) o else o.insert("~ - .. ,", mid.left, mid.bottom + 4, o.typicalHeight())
            },
            "one weekday label dropped" to { o ->
                val single = o.lines.indices.filter { WEEKDAY.matches(o.lines[it].text) }
                if (single.size >= 3) o.copy(lines = o.lines.filterIndexed { i, _ -> i != single[single.size / 2] })
                else {
                    val k = o.lines.indexOfFirst { WEEKDAY_TOKEN.findAll(it.text).count() >= 5 }
                    if (k < 0) o else {
                        val l = o.lines[k]
                        val m = WEEKDAY_TOKEN.findAll(l.text).toList().let { it[it.size / 2] }
                        val text = (l.text.substring(0, m.range.first) + l.text.substring(m.range.last + 1)).replace(Regex("""\s{2,}"""), " ").trim()
                        o.copy(lines = o.lines.toMutableList().also { it[k] = l.copy(text = text, words = l.words.filterNot { w -> w.text == m.value }) })
                    }
                }
            },
            "one day number dropped" to { o ->
                val days = o.lines.indices.filter { DAY.matches(o.lines[it].text) }
                if (days.isEmpty()) o else o.copy(lines = o.lines.filterIndexed { i, _ -> i != days[days.size / 2] })
            },
            "small rotation" to { o -> rotate(o, 3.0) },
        ) + SHAPE + MONTHS
    }

    private val today = LocalDate.of(2026, 11, 5)

    private fun route(o: OcrResult): RouteChoice = CodePipeline.run(o, today).route!!

    private fun l(text: String, left: Int, top: Int, height: Int = 28, width: Int = text.length * height / 2, block: Int = 0) =
        OcrLine(text, left, top, left + width, top + height, block = block)

    // ---- one invented page per route --------------------------------------------------------------------------------
    private fun grid(): OcrResult {
        val ls = mutableListOf(l("November 2026", 60, 60, 50))
        listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEachIndexed { i, d -> ls += l(d, 60 + i * 130, 180, 26) }
        for (d in 1..30) { val col = (d - 1) % 7; val row = (d - 1) / 7; ls += l("$d", 60 + col * 130, 240 + row * 150, 26) }
        ls += l("Dentist 10am", 60 + 3 * 130, 240 + 1 * 150 + 40, 22, 110)   // 11 Nov
        ls += l("Swim 4pm", 60 + 5 * 130, 240 + 2 * 150 + 40, 22, 90)       // 20 Nov
        return OcrResult(1000, 1250, ls.mapIndexed { i, x -> x.copy(block = i) }, 0)
    }

    private fun bill() = OcrResult(1000, 1600, listOf(
        l("Brightline Power", 60, 80, 50), l("Electricity Bill", 60, 160, 36), l("Consumer No: 4512 3321", 60, 240),
        l("Bill Date: 01 Nov 2026", 60, 300), l("Amount due: Rs 1,245.50", 60, 360, 32), l("Due date: 20 Nov 2026", 60, 420, 32),
        l("Pay online or at any collection centre.", 60, 520),
    ).mapIndexed { i, x -> x.copy(block = i) }, 0)

    private fun chat() = OcrResult(720, 1612, listOf(
        l("10:10", 29, 20, 16), l("Ravi", 113, 87, 28), l("online", 113, 119, 18),
        l("Shall we go for dinner this week?", 45, 300, 26, 380), l("9:12 pm", 360, 330, 16, 60),
        l("Yes, Sat 14 Nov at 8 pm works", 330, 420, 26, 340), l("9:14 pm", 610, 450, 16, 60),
        l("Great, see you then", 45, 520, 26, 240), l("9:15 pm", 220, 550, 16, 60),
        l("Message", 45, 1545, 26, 109),
    ).mapIndexed { i, x -> x.copy(block = i) }, 0)

    private fun programme(): OcrResult {
        val ls = mutableListOf(l("Winter Festival", 60, 60, 60), l("Schedule", 60, 150, 30))
        listOf("Sat 5 Dec" to "Choir Concert" to "6 pm", "Sun 6 Dec" to "Book Fair" to "10 am", "Mon 7 Dec" to "Film Night" to "7 pm",
            "Tue 8 Dec" to "Quiz Evening" to "5 pm").forEachIndexed { i, (dt, time) ->
            val y = 260 + i * 110
            ls += l(dt.first, 60, y, 30); ls += l(dt.second, 320, y, 30); ls += l(time, 760, y, 30)
        }
        return OcrResult(1000, 1000, ls.mapIndexed { i, x -> x.copy(block = i) }, 0)
    }

    private fun poster() = OcrResult(1000, 1600, listOf(
        l("City Library presents", 60, 80, 40), l("AUTUMN FAIR", 60, 200, 120), l("Sat 14 Nov 2026", 60, 380, 60),
        l("10 AM - 4 PM", 60, 480, 50), l("Venue: Town Hall", 60, 580, 40), l("Entry Rs 50", 60, 660, 34),
    ).mapIndexed { i, x -> x.copy(block = i) }, 0)

    private fun several() = OcrResult(1000, 1600, listOf(
        l("Green Valley School", 60, 80, 44), l("Dear parents,", 60, 200), l("Parents meeting on Mon 16 Nov at 4 pm.", 60, 260),
        l("Sports day on Fri 20 Nov at 9 am.", 60, 320), l("Regards, The Principal", 60, 420),
    ).mapIndexed { i, x -> x.copy(block = i) }, 0)

    @Test fun eachInventedPageTakesItsRoute() {
        assertEquals(PageRoute.GRID, route(grid()).chosen)
        assertEquals(PageRoute.BILL, route(bill()).chosen)
        assertEquals(PageRoute.CHAT, route(chat()).chosen)
        assertEquals(PageRoute.PROGRAMME, route(programme()).chosen)
        assertEquals(PageRoute.SINGLE, route(poster()).chosen)
        assertEquals(PageRoute.SEVERAL, route(several()).chosen)
    }

    @Test fun noMutationChangesTheRouteOfAnInventedPage() {
        val failed = mutableListOf<String>()
        for ((name, page) in listOf("grid" to grid(), "bill" to bill(), "chat" to chat(), "programme" to programme(), "poster" to poster(), "several" to several())) {
            val before = route(page)
            for ((m, f) in Mutations.ALL) {
                val after = route(f(page))
                if (after.chosen != before.chosen) failed += "$name / $m: ${before.chosen} -> ${after.chosen} (${changed(before, after)})"
            }
        }
        assertTrue(failed.joinToString("\n"), failed.isEmpty())
    }

    /** The signals whose points changed, for the route before and the route after: the check that failed. */
    private fun changed(a: RouteChoice, b: RouteChoice): String {
        val routes = setOf(a.chosen, b.chosen)
        fun key(s: RouteSignal) = "${s.route}:${s.name}"
        val sa = a.signals.filter { it.route in routes }.associateBy(::key); val sb = b.signals.filter { it.route in routes }.associateBy(::key)
        return (sa.keys + sb.keys).filter { sa[it]?.points != sb[it]?.points || sa[it]?.count != sb[it]?.count }.joinToString("; ") { k ->
            "$k ${sa[k]?.let { "${it.points}(${it.count})" } ?: "-"} -> ${sb[k]?.let { "${it.points}(${it.count})" } ?: "-"}"
        } + "; scores ${a.chosen} ${"%.1f".format(a.scores[a.chosen])}->${"%.1f".format(b.scores[a.chosen])}, " +
            "${b.chosen} ${"%.1f".format(a.scores[b.chosen])}->${"%.1f".format(b.scores[b.chosen])}" + (if (b.how != RouteChoice.How.CLEAR) ", ${b.how}" else "")
    }

    /** Harness: every mutation on every page of the given OCR files. Counts and routes only, never OCR text. */
    @Test fun mutationsOnOcrFiles() {
        val src = System.getenv("ROUTE_MUT_OCR"); val out = System.getenv("ROUTE_MUT_OUT"); val todayS = System.getenv("ROUTE_MUT_TODAY")
        assumeTrue(src != null && out != null && todayS != null)
        val day = LocalDate.parse(todayS)
        val sb = StringBuilder()
        for (path in src!!.split(';')) for (r in Json.parseToJsonElement(File(path).readText()).jsonObject.getValue("results").jsonArray) {
            val e = r.jsonObject
            if (e["pass"]?.jsonPrimitive?.content?.let { it != "1" } == true || "error" in e) continue
            val page = OcrJson.decode(e.toString())
            val before = CodePipeline.run(page, day).route!!
            // the digit prepend mutations are measured here only: with no printed weekday they are not repaired, and may change the route
            for ((m, f) in Mutations.ALL + Mutations.PREPEND) {
                val after = CodePipeline.run(f(page), day).route!!
                sb.append(JsonObject(mapOf(
                    "set" to JsonPrimitive(File(path).nameWithoutExtension), "file" to e.getValue("file"), "mutation" to JsonPrimitive(m),
                    "before" to JsonPrimitive(before.chosen.name), "after" to JsonPrimitive(after.chosen.name),
                    "check" to JsonPrimitive(if (after.chosen == before.chosen) "" else changed(before, after)),
                )).toString()).append('\n')
            }
        }
        File(out!!).writeText(sb.toString())
    }
}
