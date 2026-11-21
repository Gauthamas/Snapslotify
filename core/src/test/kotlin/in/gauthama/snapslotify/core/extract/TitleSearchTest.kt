package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Titles found by searching outward from the date ([TitleSearch]). Invented chats and pages. */
class TitleSearchTest {

    private val today = LocalDate.of(2026, 11, 11)

    /** A chat: header, then messages; [me] true for a message on the right. Each message one line, 20 high, 50 apart. */
    private fun chat(vararg msgs: Pair<String, Boolean>): OcrResult {
        val ls = mutableListOf(OcrLine("Meera", 20, 10, 120, 34, block = 0), OcrLine("online", 20, 38, 90, 52, block = 1))
        msgs.forEachIndexed { i, (t, me) ->
            val w = t.length * 10
            val x = if (me) 700 - w else 20
            ls += OcrLine(t, x, 120 + 50 * i, x + w, 140 + 50 * i, confidence = 0.9f, block = i + 2)
        }
        ls += OcrLine("Message", 20, 1500, 120, 1520, block = 99)
        return OcrResult(720, 1600, ls, 0)
    }

    private fun title(o: OcrResult) = CodePipeline.run(o, today, today).events.single()
    private fun page(vararg ls: Triple<String, Int, Int>) =
        OcrResult(1000, 1600, ls.mapIndexed { i, (t, y, h) -> OcrLine(t, 40, y, 40 + t.length * h / 2, y + h, confidence = 0.9f, block = i) }, 0)

    @Test fun unrelatedMessagesBetweenTheEventAndItsDate() {
        val e = title(chat(
            "We are throwing a farewell dinner for Kiran" to false,
            "did you see the cricket score" to true,
            "ok" to false,
            "Saturday 21 Nov at 8 pm" to false,
        ))
        assertTrue(e.event.title, e.event.title!!.contains("farewell dinner", ignoreCase = true))
    }

    @Test fun theEventNamedAMessageOrTwoEarlierAndAskedWhen() {
        val e = title(chat(
            "Guess what, we are hosting a housewarming party" to false,
            "Wow!! When?" to true,
            "Sat 21 Nov" to false,
        ))
        assertTrue(e.event.title, e.event.title!!.contains("housewarming party", ignoreCase = true))
        assertTrue(e.flags.none { it.code == "title_unsure" })
    }

    @Test fun theNearestPhraseIsAboutSomethingElse() {
        // the sentence with the date names the event; the line next to it is about traffic
        val e = title(page(
            Triple("Hello all,", 100, 20),
            Triple("The pottery workshop is on 21 Nov 2026 at 10 am.", 140, 20),
            Triple("Traffic near the gate is heavy, park early please.", 166, 20),
        ))
        assertTrue(e.event.title, e.event.title!!.contains("pottery workshop", ignoreCase = true))
    }

    @Test fun neverTheSalutationOrAnAddressOrFiller() {
        val e = title(page(
            Triple("Dear Rohan,", 100, 20),
            Triple("Thank you!", 200, 34),
            Triple("We are celebrating Anu's graduation", 300, 20),
            Triple("21 Nov 2026, 6 pm", 340, 20),
            Triple("42 Lake View Road", 366, 20),
        ))
        assertTrue(e.event.title, e.event.title!!.contains("graduation"))
    }

    @Test fun aQuotedNameInTheDatesSentenceIsTheTitle() {
        val e = title(page(Triple("Asha invites you to \"Spring Book Swap\" on Sat, Nov 21, 2026 at 4 PM. Reply to join.", 140, 20)))
        assertEquals("Spring Book Swap", e.event.title)
    }

    @Test fun nothingEventLikeNearFallsBackAndIsFlagged() {
        // only numbers and filler near the date: the plain title, flagged as check the title
        val e = title(chat("ok" to true, "21 Nov 8 pm" to false, "sure" to true))
        assertTrue(e.flags.toString(), e.flags.any { it.code == "title_unsure" })
    }

    @Test fun aDaySeparatorStopsTheSearch() {
        // the event named before a day separator belongs to another day's talk: not taken
        val o = chat("Movie night at Dev's place" to false, "Sat, 14 Nov 2026" to false, "ok" to true, "21 Nov 8 pm" to false)
        val ls = o.lines.toMutableList()
        val sep = ls.indexOfFirst { it.text == "Sat, 14 Nov 2026" }
        ls[sep] = ls[sep].copy(left = 300, right = 420)   // centred: a message date line
        val e = CodePipeline.run(o.copy(lines = ls), today, today).events.single()
        assertTrue(e.event.title.toString(), e.event.title?.contains("Movie night") != true)
    }
}
