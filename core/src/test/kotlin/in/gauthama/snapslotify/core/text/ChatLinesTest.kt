package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** Invented boxes (not dev, not held-out). Image 1000 x 2000: bottom band >= 1800 px. */
class ChatLinesTest {

    private fun line(text: String, top: Int) = OcrLine(text, 50, top, 450, top + 40, block = top)
    private fun tidy(vararg ls: OcrLine, chat: Boolean = true) =
        ChatLines.tidy(TextCleaner.Result(ls.toList(), emptyList(), looksLikeChat = chat), 2000).kept.map { it.text }

    @Test fun dropsABottomBandPlaceholder() =
        assertEquals(listOf("badminton 12th november"), tidy(line("badminton 12th november", 300), line("Type a message", 1850)))

    @Test fun keepsAPlaceholderPhraseAboveTheBottomBand() =
        assertEquals(listOf("iMessage"), tidy(line("iMessage", 900)))

    @Test fun cutsATrailingTimeStamp() =
        assertEquals(listOf("Sure", "19:00", "3.00 pm works for me"), tidy(line("Sure 19:30", 300), line("19:00 19:26", 400), line("3.00 pm works for me 21:20", 500)))

    @Test fun keepsATimeAfterAt() = assertEquals(listOf("meet at 19:30"), tidy(line("meet at 19:30", 300)))

    @Test fun leavesNonChatsAlone() =
        assertEquals(listOf("Sure 19:30", "Type a message"), tidy(line("Sure 19:30", 300), line("Type a message", 1850), chat = false))
}
