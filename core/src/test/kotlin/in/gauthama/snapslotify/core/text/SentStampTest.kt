package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** Invented boxes (not dev, not held-out). Image 1000 x 2000: top 30% = 600 px. */
class SentStampTest {

    private fun line(text: String, top: Int, height: Int = 30) = OcrLine(text, 50, top, 650, top + height, block = top)
    private fun tidy(vararg ls: OcrLine) = SentStamp.tidy(TextCleaner.Result(ls.toList(), emptyList()), 2000).kept.map { it.text }

    /** A screen: the cleaner took out a status bar. */
    private fun tidyOnScreen(vararg ls: OcrLine) = SentStamp.tidy(
        TextCleaner.Result(ls.toList(), listOf(TextCleaner.Removed(OcrLine("9:41", 20, 0, 60, 14), "status bar", 0.5))), 2000,
    ).kept.map { it.text }

    @Test fun dropsASmallSentLineNearTheTop() = assertEquals(
        listOf("Yoga Retreat this week", "Our Yoga Retreat will be this Tuesday at 11 AM."),
        tidyOnScreen(line("Yoga Retreat this week", 180, 42), line("Thu, 5 Nov 2026, 7:55 AM", 280, 21), line("Our Yoga Retreat will be this Tuesday at 11 AM.", 460)),
    )

    @Test fun keepsASmallDateLineWhenThePageIsNotAScreen() = assertEquals(
        listOf("Yoga Retreat this week", "Thu, 5 Nov 2026, 7:55 AM", "Our Yoga Retreat will be this Tuesday at 11 AM."),
        tidy(line("Yoga Retreat this week", 180, 42), line("Thu, 5 Nov 2026, 7:55 AM", 280, 21), line("Our Yoga Retreat will be this Tuesday at 11 AM.", 460)),
    )

    @Test fun keepsASmallDateLineBelowTheFirstBodySentence() = assertEquals(
        listOf("Yoga Retreat this week", "Hi all, the retreat is on again this year.", "Thu, 5 Nov 2026, 7:55 AM"),
        tidyOnScreen(line("Yoga Retreat this week", 180, 42), line("Hi all, the retreat is on again this year.", 240), line("Thu, 5 Nov 2026, 7:55 AM", 300, 21)),
    )

    @Test fun keepsAPosterDateLineInLargeType() = assertEquals(
        listOf("JAZZ NIGHT", "Sat 14 Nov 2026, 7:30 PM", "City Hall"),
        tidy(line("JAZZ NIGHT", 100, 120), line("Sat 14 Nov 2026, 7:30 PM", 300, 60), line("City Hall", 400)),
    )

    @Test fun keepsADateLineLowerDown() = assertEquals(
        listOf("Hello", "Thu, 5 Nov 2026, 7:55 AM", "See you"),
        tidy(line("Hello", 300), line("Thu, 5 Nov 2026, 7:55 AM", 900, 20), line("See you", 1000)),
    )

    @Test fun dropsAPostDateLineNextToItsCountsBar() = assertEquals(
        listOf("Morning run by the river. No filter.", "12 Reposts 3 Quotes 410 Likes"),
        tidy(line("Morning run by the river. No filter.", 200), line("7:12 AM · Mar 3, 2027 · 2,104 Views", 1500, 22),
            line("12 Reposts 3 Quotes 410 Likes", 1560, 22)),
    )

    @Test fun dropsADateWithAtAndPublic() = assertEquals(
        listOf("Bake sale photos from the weekend", "48 comments 12 shares"),
        tidy(line("Mar 9 at 6:40 PM · Public", 150, 22), line("Bake sale photos from the weekend", 220), line("48 comments 12 shares", 900, 22)),
    )

    @Test fun dropsADateOnlyLineUnderTheLikes() = assertEquals(
        listOf("Garden club open day", "52 likes"),
        tidy(line("Garden club open day", 200), line("52 likes", 1400, 22), line("MARCH 2, 2027", 1440, 20)),
    )

    @Test fun keepsAnEventLineInAPostWithoutACountsBarNearby() = assertEquals(
        listOf("Choir concert", "Sat 20 Mar 2027, 6:30 PM", "St Anne's Hall", "Tickets at the door", "Bring a friend", "30 likes"),
        tidy(line("Choir concert", 100, 60), line("Sat 20 Mar 2027, 6:30 PM", 700, 40), line("St Anne's Hall", 760), line("Tickets at the door", 820),
            line("Bring a friend", 880), line("30 likes", 1700, 22)),
    )

    @Test fun keepsATimeRangeLineNextToCounts() = assertEquals(
        listOf("Wed, Apr 7, 3:00 PM - 4:00 PM", "Spring planning call", "210 attendees 15 comments"),
        tidy(line("Wed, Apr 7, 3:00 PM - 4:00 PM", 900, 22), line("Spring planning call", 940), line("210 attendees 15 comments", 990, 22)),
    )

    @Test fun dropsAPublicDateLineFarFromTheCounts() = assertEquals(
        listOf("Lost cat, grey with a white paw", "Call if you see her", "Seen near the park", "Thanks all", "64 reactions"),
        tidy(line("Apr 2 at 9:15 AM · Public", 120, 22), line("Lost cat, grey with a white paw", 200), line("Call if you see her", 260),
            line("Seen near the park", 320), line("Thanks all", 380), line("64 reactions", 1500, 22)),
    )

    @Test fun readsAYearAfterACommaWithNoSpace() = assertEquals(
        listOf("Ten lessons from ten years of gardening", "8 Reposts 210 Likes"),
        tidy(line("Ten lessons from ten years of gardening", 200), line("4:02 PM · Mar 5,2027", 1500, 22), line("8 Reposts 210 Likes", 1560, 22)),
    )
}
