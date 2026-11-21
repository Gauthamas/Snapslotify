package `in`.gauthama.snapslotify.core.pipeline

import `in`.gauthama.snapslotify.core.extract.Dates
import `in`.gauthama.snapslotify.core.extract.MonthRepair
import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.ocr.OcrWord
import `in`.gauthama.snapslotify.core.review.ReviewSession
import `in`.gauthama.snapslotify.core.text.LetterSpacing
import `in`.gauthama.snapslotify.core.text.OcrNormalizer
import `in`.gauthama.snapslotify.core.text.TitleLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The ways a date and a heading are printed or read: glued and spaced ordinals, misread months, spaced numbers, listed
 * days, letter-spaced type, digits read for letters, two names with a connector, announcing lead-ins. Invented posters
 * only; today is Sun 15 Nov 2026.
 */
class WrittenFormsTest {

    private val today = LocalDate.of(2026, 11, 15)

    /** A centred poster: "text@height" lines. */
    private fun poster(vararg lines: String): OcrResult {
        var y = 200
        return OcrResult(720, 1612, lines.mapIndexed { i, s ->
            val at = s.lastIndexOf('@')
            val (t, h) = if (at > 0) s.substring(0, at) to s.substring(at + 1).toInt() else s to 26
            val w = t.length * h * 55 / 100
            OcrLine(t, 360 - w / 2, y, 360 + w / 2, y + h, block = i).also { y += h + 18 }
        }, 0)
    }

    private fun first(vararg lines: String) = ReviewSession.from(CodePipeline.run(poster(*lines), today, today), today).let { s ->
        if (s.noEventFound) null else s.events.first()
    }

    private fun dateOf(date: String) = first("KITE FESTIVAL@64", date + "@30", "10 AM onwards")?.let { it.dateIso to it.endDateIso }

    @Test fun aDayAndMonthGluedSpacedOrSetWithOrdinalsAreRead() {
        for (d in listOf("6THDECEMBER, 2026", "6DECEMBER 2026", "DECEMBER6, 2026", "Sun, Dec6 2026", "6 th December 2026", "6ᵗʰ December 2026",
                "6th of December 2026", "on the 6th day of December, 2026", "6 DECEMBER2026", "December 6th,2026", "DECEMBER 6TH2026",
                "Dec 62026", "SUN . 6 . DEC 2026"))
            assertEquals(d, "2026-12-06" to null, dateOf(d))
    }

    @Test fun numbersWithSpacesBarsOrBulletsBetweenAreADate() {
        for (d in listOf("06 . 12 . 2026", "06 / 12 / 2026", "06 | 12 | 2026", "06 • 12 • 2026"))
            assertEquals(d, "2026-12-06" to null, dateOf(d))
    }

    @Test fun misreadMonthsAreRepairedWithAYear() {
        for (d in listOf("6 DECEM8ER 2026", "6 Decernber 2026", "6thDecernber 2026"))
            assertEquals(d, "2026-12-06", dateOf(d)?.first)
        assertEquals("2027-07-06", dateOf("6 Ju1y 2027")?.first)
        assertEquals("2027-04-06", dateOf("6 Apri1 2027")?.first)
        assertEquals("november", MonthRepair.month("Novernber"))
        // a short name one letter from two months stays unread
        assertNull(MonthRepair.month("Mav"))
        assertEquals("6 DECEMBER 2026", OcrNormalizer.normalizeNumeric("6 DECEM8ER 2026"))
        assertEquals("6th Dec", OcrNormalizer.normalizeNumeric("6ᵗʰ Dec"))
    }

    @Test fun listedDaysRunFromTheFirst() {
        assertEquals("2026-12-06" to "2026-12-08", dateOf("6, 7 & 8 Dec 2026"))
    }

    @Test fun aMonthAtALineEndIsNotDatedByTheNextLinesHour() {
        // "DEC" ends one line, "10 AM" starts the next: 10 is the hour, not the day
        assertEquals(emptyList<Any>(), Dates.findDates("SUN . DEC\n10 AM onwards"))
        // a month glued to a day with no year is a chat's date pill or a code, not a date
        assertEquals(emptyList<Any>(), Dates.findDates("January3"))
    }

    @Test fun letterSpacedTypeIsJoinedBackIntoWords() {
        assertEquals("SUMMER FAIR", LetterSpacing.join(OcrLine("S U M M E R  F A I R", 0, 0, 400, 40)))
        assertEquals("6 DECEMBER 2026", LetterSpacing.join(OcrLine("6 D E C E M B E R 2026", 0, 0, 400, 40)))
        // word breaks from the letters' boxes when the text has single spaces only
        val letters = "KITESHOW".mapIndexed { i, c -> val x = i * 30 + (if (i >= 4) 40 else 0); OcrWord(c.toString(), x, 0, x + 20, 40) }
        assertEquals("KITE SHOW", LetterSpacing.join(OcrLine("K I T E S H O W", 0, 0, 300, 40, words = letters)))
        // a calendar's weekday initials stay as they are
        assertNull(LetterSpacing.join(OcrLine("S M T W T F S", 0, 0, 400, 40)))
        assertEquals("SUMMER FAIR", first("S U M M E R  F A I R@50", "Sat 9 January 2027@30", "10 AM onwards", "Riverside Park@24")?.title)
    }

    @Test fun digitsReadForLettersInATitleAreLetters() {
        assertEquals("KITE SHOW", TitleLetters.fix("KITE SH0W"))
        assertEquals("FILM NIGHT", TitleLetters.fix("F1LM NIGHT"))
        assertEquals("SUMMER FAIR", TitleLetters.fix("5UMMER FAIR"))
        assertEquals("Kite Show", TitleLetters.fix("Kite Sh0w"))
        for (keep in listOf("PS5 launch", "COVID19 drive", "5K run", "Win10 class", "50PLUS club", "10AM", "15TH"))
            assertEquals(keep, TitleLetters.fix(keep))
        assertEquals("BOOK FAIR", first("B00K FAIR@64", "Sat 9 January 2027@30", "10 AM onwards")?.title)
    }

    @Test fun twoNamesWithAConnectorLineAreOneTitle() {
        for (c in listOf("&", "weds", "+", "with"))
            assertEquals("Meera $c Arjun", first("Meera@70", "$c@36", "Arjun@70", "Sat 9 January 2027@30", "10 AM onwards")?.title)
        assertEquals("Meera & Arjun", first("Meera@70", "and@36", "Arjun@70", "Sat 9 January 2027@30", "10 AM onwards")?.title)
    }

    @Test fun twoNamesWhoseConnectorWasNotReadAreOneTitle() {
        // the "&" between them was a drawn glyph OCR did not read: a gap of about one line height where it sat
        val ls = listOf(OcrLine("Meera", 288, 504, 462, 538, block = 0), OcrLine("Arjun", 308, 569, 441, 601, block = 1),
            OcrLine("ON", 367, 621, 381, 626, block = 2), OcrLine("* 9THJANUARY, 2027 *", 289, 634, 461, 647, block = 3),
            OcrLine("AT THE TEMPLE HALL", 291, 650, 455, 663, block = 4), OcrLine("BETWEEN 10.15 AM & 11.00 AM", 303, 666, 444, 679, block = 5))
        val s = ReviewSession.from(CodePipeline.run(OcrResult(720, 1612, ls, 0), today, today), today)
        assertEquals("Meera Arjun", s.events.single().title)
        assertEquals("2027-01-09", s.events.single().dateIso)
    }

    @Test fun anAnnouncingLeadInIsNotPartOfTheTitle() {
        for (lead in listOf("TO CELEBRATE THE UNION OF", "JOIN US FOR", "Save the Date", "SAVE THE DATE@40", "You are cordially invited@40", "YOU'RE INVITED@40"))
            assertEquals(lead, "Kite Show", first(lead, "Kite Show@64", "Sat 9 January 2027@30", "10 AM onwards")?.title)
        // a heading set in two sizes is still one title
        assertEquals("Moonlight Walk", first("Moonlight@60", "Walk@40", "Sat 9 January 2027@30", "10 AM onwards", "Riverside Park@24", "Free entry@24")?.title)
    }

    @Test fun aHeadingBrokenByAHyphenInCapitalsLosesTheSpace() {
        assertEquals("KITE FESTI-VAL", first("KITE FESTI-@64", "VAL@64", "Sat 9 January 2027@30", "10 AM onwards")?.title)
        assertEquals("KITE FESTI-VAL", TitleLetters.fix("KITE FESTI- VAL"))
    }
}
