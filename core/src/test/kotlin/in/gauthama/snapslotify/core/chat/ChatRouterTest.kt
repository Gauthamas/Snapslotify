package `in`.gauthama.snapslotify.core.chat

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRouterTest {

    private fun line(text: String, left: Int, top: Int, right: Int, height: Int = 40) = OcrLine(text, left, top, right, top + height)
    private fun ocr(vararg lines: OcrLine) = OcrResult(1080, 2400, lines.toList(), 0)

    private val message = line("Message", 60, 2300, 240)

    @Test fun oneBubbleWithItsTimeAndAMessageBoxIsAChat() {
        val d = ChatRouter.classify(ocr(
            line("10:42", 40, 30, 120), line("Anu", 170, 130, 250), line("online", 170, 180, 260, 30),
            line("Dinner on Saturday 8 pm? wait not", 66, 410, 750), line("saturday, make it monday 8 pm", 66, 470, 690),
            line("10:41", 750, 530, 815, 26), message,
        ))
        assertEquals(Route.CHAT, d.route)
        assertEquals(1, d.stampedLines)
    }

    @Test fun twoStampsAreEnoughWithoutAMessageBox() {
        val d = ChatRouter.classify(ocr(
            line("did you reach", 66, 400, 360), line("10:29", 300, 450, 360, 26),
            line("bring the charger", 680, 600, 1010), line("10:32", 950, 650, 1012, 26),
        ))
        assertEquals(Route.CHAT, d.route)
        assertEquals(2, d.stampedLines)
    }

    @Test fun nameColonLinesCount() {
        val d = ChatRouter.classify(ocr(line("Ravi: dinner on friday?", 60, 400, 600), line("Priya: friday works", 60, 460, 560), line("Anu: me too", 60, 520, 400)))
        assertEquals(2 + 1, d.senderLines)
        assertTrue(d.score >= 2)
    }

    @Test fun fieldLabelsAreNotSenders() {
        val d = ChatRouter.classify(ocr(line("Due Date: 12 Nov", 60, 400, 400), line("Consumer No.: 7286", 60, 460, 400), line("Bill Date: 1 Nov", 60, 520, 400)))
        assertEquals(0, d.senderLines)
        assertEquals(Route.OTHER, d.route)
    }

    @Test fun aBillStaysOtherEvenWithAColumnLayout() {
        val d = ChatRouter.classify(ocr(
            line("Bharani Discom Ltd", 60, 100, 600), line("Statement Date", 60, 300, 400), line("01/11/2026", 800, 300, 1020),
            line("Due Date", 60, 360, 300), line("15/11/2026", 800, 360, 1020), line("Total payable", 60, 420, 360), line("Rs 1,240", 840, 420, 1020),
        ))
        assertEquals(Route.OTHER, d.route)
        assertTrue(d.documentWords >= 2)
    }

    @Test fun aCentredPosterTimeIsNotABubbleStamp() {
        val d = ChatRouter.classify(ocr(
            line("Diwali Cultural Night", 200, 300, 880, 90), line("7:00 PM", 440, 400, 640, 40),
            line("Community Hall", 300, 500, 780), line("Entry free", 400, 560, 680),
        ))
        assertEquals(0, d.stampedLines)
        assertEquals(Route.OTHER, d.route)
    }

    @Test fun aBigTicketTimeIsNotABubbleStamp() {
        val d = ChatRouter.classify(ocr(
            line("Chennai to Bengaluru", 60, 200, 800), line("06:10", 60, 300, 400, 90), line("11:05", 700, 300, 1020, 90),
            line("PNR 4471", 60, 500, 400), line("Seat 12B", 700, 500, 1000),
        ))
        assertEquals(0, d.stampedLines)
        assertEquals(Route.OTHER, d.route)
    }

    @Test fun nothingReadIsOther() = assertEquals(Route.OTHER, ChatRouter.classify(ocr()).route)
}
