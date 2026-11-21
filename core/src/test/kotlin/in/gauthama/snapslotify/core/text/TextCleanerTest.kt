package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.Fixtures
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanerTest {

    // Invented boxes (not dev, not held-out). Image 1000 x 2000: status band <= 80 px, bottom band >= 1800 px.
    private fun line(block: Int, text: String, top: Int, height: Int = 40, left: Int = 50) =
        OcrLine(text, left, top, left + 400, top + height, block = block)

    private val chat = listOf(
        line(0, "9:41", 20, height = 30),                 // status bar clock
        line(1, "Ravi", 120),
        line(2, "Dinner on Fri 23 Oct at 8 PM", 300),
        line(2, "at Spice Garden", 345),
        line(3, "6:12 PM", 400, height = 20),             // bubble timestamp: small, alone in its block
        line(4, "Message", 1850),                         // input bar
    )

    @Test
    fun removesStatusClockBubbleTimestampAndMessage() {
        val r = TextCleaner.clean(chat, 1000, 2000)
        assertEquals("Ravi\nDinner on Fri 23 Oct at 8 PM\nat Spice Garden", r.text)
        assertEquals(
            listOf("9:41" to "status bar", "6:12 PM" to "bubble timestamp", "Message" to "Message (text rule) + Message (bottom band)"),
            r.removed.map { it.line.text to it.rule },
        )
    }

    @Test
    fun keepsAClockThatIsNotSmallOrNotAlone() {
        val lines = listOf(line(0, "Doors", 300), line(0, "7:30 PM", 345, height = 20), line(1, "10:00", 500, height = 40))
        assertEquals("Doors\n7:30 PM\n10:00", TextCleaner.clean(lines, 1000, 2000).text)
    }

    @Test
    fun messageAboveTheBottomBandIsStillRemovedByTheTextRule() {
        val r = TextCleaner.clean(listOf(line(0, "Hello", 300), line(1, "message", 900)), 1000, 2000)
        assertEquals("Hello", r.text)
        assertEquals("Message (text rule)", r.removed.single().rule)
    }

    // Chat decision. A chat with two left bubbles and one right bubble, each with a small time stamp under it, and a
    // box line at the bottom ([bar], or none for a crop above the box).
    private fun chatWith(bar: String?) = listOfNotNull(
        line(0, "Ravi", 120),
        line(1, "Dinner on Fri 23 Oct at 8 PM?", 300, left = 50),
        line(2, "8:01 PM", 345, height = 20, left = 380),
        line(3, "Sure, where?", 420, left = 520),
        line(4, "8:02 PM", 465, height = 20, left = 860),
        line(5, "Spice Garden", 540, left = 50),
        line(6, "8:03 PM", 585, height = 20, left = 380),
        bar?.let { line(7, it, 1850) },
    )

    @Test fun typeAMessageBarIsAChat() = assertTrue(TextCleaner.clean(chatWith("Type a message"), 1000, 2000).looksLikeChat)
    @Test fun writeAMessageBarIsAChat() = assertTrue(TextCleaner.clean(chatWith("Write a message"), 1000, 2000).looksLikeChat)
    @Test fun textMessageBarIsAChat() = assertTrue(TextCleaner.clean(chatWith("Text message"), 1000, 2000).looksLikeChat)
    @Test fun rcsMessageBarIsAChat() = assertTrue(TextCleaner.clean(chatWith("RCS message"), 1000, 2000).looksLikeChat)
    @Test fun iMessageBarIsAChat() = assertTrue(TextCleaner.clean(chatWith("iMessage"), 1000, 2000).looksLikeChat)
    @Test fun messageWithAnEllipsisIsAChat() = assertTrue(TextCleaner.clean(chatWith("Message…"), 1000, 2000).looksLikeChat)
    @Test fun aTypedDraftInTheBoxIsAChat() = assertTrue(TextCleaner.clean(chatWith("see you there"), 1000, 2000).looksLikeChat)
    @Test fun aCropWithNoBoxIsAChat() = assertTrue(TextCleaner.clean(chatWith(null), 1000, 2000).looksLikeChat)

    @Test
    fun aPosterWithALineThatSaysOnlyMessageIsNotAChat() {
        val poster = listOf(
            line(0, "SUMMER JAZZ NIGHT", 200, height = 120), line(1, "Sat 14 Nov 2026", 400), line(2, "7:30 PM", 460),
            line(3, "City Hall", 520), line(4, "Message", 900), line(5, "the band on 98765 43210", 960),
        )
        val r = TextCleaner.clean(poster, 1000, 2000)
        assertTrue(!r.looksLikeChat)
        assertEquals("Message (text rule)", r.removed.single().rule)   // still removed, as in the notebook
    }

    @Test
    fun emptyInput() {
        assertEquals("", TextCleaner.clean(emptyList(), 1000, 2000).text)
    }

    /** Parity with the notebook's cleaner G on the saved boxes of the 29 seen cases. Lists every difference. */
    @Test
    fun parityWithNotebookG() {
        Fixtures.requireExport()
        val diffs = Fixtures.cases.mapNotNull { c ->
            val r = TextCleaner.clean(c.ocr.lines, c.ocr.imageWidth, c.ocr.imageHeight)
            val removed = r.removed.map { it.line.text to it.rule }
            when {
                r.text != c.gText -> "${c.id}: text differs\n  kotlin:   ${r.text.replace("\n", " | ")}\n  notebook: ${c.gText.replace("\n", " | ")}"
                removed != c.gRemoved -> "${c.id}: removed lines differ: kotlin $removed, notebook ${c.gRemoved}"
                else -> null
            }
        }
        println("cleaner parity: ${Fixtures.cases.size - diffs.size} of ${Fixtures.cases.size} cases identical")
        diffs.forEach(::println)
        assertTrue("cleaner differs from notebook G on ${diffs.size} case(s):\n" + diffs.joinToString("\n"), diffs.isEmpty())
        assertEquals(29, Fixtures.cases.size)
    }
}
