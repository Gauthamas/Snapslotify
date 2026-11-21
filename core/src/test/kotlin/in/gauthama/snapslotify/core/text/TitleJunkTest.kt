package `in`.gauthama.snapslotify.core.text

import `in`.gauthama.snapslotify.core.ocr.OcrLine
import `in`.gauthama.snapslotify.core.ocr.OcrWord
import `in`.gauthama.snapslotify.core.text.TitleJunk.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Junk words at a heading's ends and junk-only lines ([TitleJunk]). Invented text. */
class TitleJunkTest {

    /** A line of (word, confidence), its confidence the words' mean. */
    private fun line(vararg ws: Pair<String, Float>, top: Int = 200) = OcrLine(ws.joinToString(" ") { it.first }, 100, top, 900, top + 90,
        ws.map { it.second }.average().toFloat(), words = ws.mapIndexed { i, (t, c) -> OcrWord(t, 100 + 150 * i, top, 240 + 150 * i, top + 90, c) })

    @Test fun strongShapes() {
        for (w in listOf("sHoWv", "llll", "mmm", "x7q2", "a0b9k", "r]m", "l|l", "~~ri", "e@sk", "xq#@", "n'q;"))
            assertEquals(w, Kind.STRONG, TitleJunk.kind(w))
    }

    @Test fun noVowelShapes() {
        for (w in listOf("kltrs", "Zxcv", "Blvd", "Mgmt", "Psst", "Crwth")) assertEquals(w, Kind.SUSPECT, TitleJunk.kind(w))
    }

    @Test fun weakShapes() {
        for (w in listOf("r", "Y", "t", "BBQ", "Rd", "BLVD", "iii", "IV")) assertEquals(w, Kind.WEAK, TitleJunk.kind(w))
    }

    @Test fun realWordsAreNotJunk() {
        for (w in listOf("A", "I", "a", "iPhone", "eBay", "TEDx", "YouTube", "K-Pop", "3D", "4x4", "C#", "B12", "PS5", "C++", "Gym",
            "Rock'n'Roll", "O'Neil", "Q&A", "R&B", "Night", "Market"))
            assertNull(w, TitleJunk.kind(w))
    }

    @Test fun strongJunkComesOffEitherEndWhateverTheConfidence() {
        val l = line("sHoWv" to 0.9f, "Spring" to 0.92f, "Book" to 0.95f, "Fair" to 0.93f, "x7q2" to 0.9f)
        assertEquals("Spring Book Fair", TitleJunk.trim("sHoWv Spring Book Fair x7q2", listOf(l)))
    }

    @Test fun noVowelJunkStaysInThePrefillAndComesOffOnlyInTheOffer() {
        val l = line("kltrs" to 0.9f, "Spring" to 0.92f, "Book" to 0.95f, "Fair" to 0.93f, "x7q2" to 0.9f)
        assertEquals("kltrs Spring Book Fair", TitleJunk.trim("kltrs Spring Book Fair x7q2", listOf(l)))
        assertEquals("Spring Book Fair", TitleJunk.trim("kltrs Spring Book Fair x7q2", listOf(l), tierTwo = true))
    }

    @Test fun aWordAsOcrReadIt() {
        // the title search took the symbols off "~~ri"; it is judged as read
        val l = line("~~ri" to 0.3f, "Farmers" to 0.91f, "Market" to 0.88f)
        assertEquals("Farmers Market", TitleJunk.trim("ri Farmers Market", listOf(l)))
    }

    @Test fun weakJunkOnlyWhenReadClearlyLessSure() {
        val low = line("Night" to 0.95f, "Market" to 0.93f, "r" to 0.3f)
        assertEquals("Night Market r", TitleJunk.trim("Night Market r", listOf(low)))
        assertEquals("Night Market", TitleJunk.trim("Night Market r", listOf(low), tierTwo = true))
        val sure = line("Night" to 0.95f, "Market" to 0.93f, "r" to 0.9f)
        assertEquals("Night Market r", TitleJunk.trim("Night Market r", listOf(sure), tierTwo = true))
        // stylised type: everything read unsure, the gap too small
        val styled = line("Night" to 0.5f, "Market" to 0.55f, "r" to 0.4f)
        assertEquals("Night Market r", TitleJunk.trim("Night Market r", listOf(styled), tierTwo = true))
    }

    @Test fun aAndIStay() {
        val l = line("A" to 0.4f, "Quiet" to 0.95f, "Harvest" to 0.96f)
        assertEquals("A Quiet Harvest", TitleJunk.trim("A Quiet Harvest", listOf(l)))
    }

    @Test fun neverFromTheMiddle() {
        val l = line("Jazz" to 0.95f, "x7q2" to 0.3f, "Park" to 0.95f)
        assertEquals("Jazz x7q2 Park", TitleJunk.trim("Jazz x7q2 Park", listOf(l)))
    }

    @Test fun junkGluedOnASymbolComesOffTheWordAlone() {
        val l = line("Spring" to 0.95f, "Book" to 0.95f, "Fair|l" to 0.4f)
        assertEquals("Spring Book Fair", TitleJunk.trim("Spring Book Fair|l", listOf(l)))
    }

    /** One heading with junk glued straight onto its first or last word, the rest read sure. */
    private fun glued(title: String, conf: Float) = title.split(" ").let { ws ->
        line(*ws.mapIndexed { i, w -> w to (if (i == 0 || i == ws.size - 1) conf else 0.94f) }.toTypedArray())
    }

    @Test fun strongJunkGluedStraightOnComesOffAndTheWordStays() {
        for ((input, want) in listOf(
            "dRuMxGarden Party" to "Garden Party",          // case flips, then a capitalised word
            "IIIIPotluck Dinner" to "Potluck Dinner",        // a run of capitals, then a capitalised word
            "Book Fairpppp" to "Book Fair",                  // into a run of one letter
            "Quiz Nightw3x9" to "Quiz Night",                // into letters alternating with digits
            "Quiz Nightw3x" to "Quiz Night",
            "k2v7Movie Club" to "Movie Club",                // out of letters alternating with digits
            "Yoga Class]q" to "Yoga Class",                  // into a symbol
        )) for (c in listOf(0.3f, 0.9f)) assertEquals("$input @$c", want, TitleJunk.trim(input, listOf(glued(input, c))))
    }

    @Test fun noVowelJunkGluedOnComesOffOnlyInTheOffer() {
        val l = line("KrvztLunch" to 0.3f, "Talk" to 0.94f)
        assertEquals("KrvztLunch Talk", TitleJunk.trim("KrvztLunch Talk", listOf(l)))
        assertEquals("Lunch Talk", TitleJunk.trim("KrvztLunch Talk", listOf(l), tierTwo = true))
    }

    @Test fun weakJunkGluedOnNeverComesOffWhateverTheConfidence() {
        for (c in listOf(0.2f, 0.9f)) {
            for (two in listOf(false, true)) {
                assertEquals("dLibrary Tour", TitleJunk.trim("dLibrary Tour", listOf(line("dLibrary" to c, "Tour" to 0.93f)), two))
                assertEquals("Town TourBxq", TitleJunk.trim("Town TourBxq", listOf(line("Town" to 0.93f, "TourBxq" to c)), two))
            }
        }
        // a weak word standing alone at an end is offered off when read clearly less sure, never taken off the prefill
        val d = line("d" to 0.2f, "Library" to 0.93f, "Tour" to 0.94f)
        assertEquals("d Library Tour", TitleJunk.trim("d Library Tour", listOf(d)))
        assertEquals("Library Tour", TitleJunk.trim("d Library Tour", listOf(d), tierTwo = true))
    }

    @Test fun prefixedAndCamelCaseNamesSurviveEvenReadLessSure() {
        for (name in listOf("iPhone", "eBook", "McKenzie", "eBay", "YouTube", "TEDx", "iPad", "eSports", "McDonald", "MacBook",
            "LeBron", "DeShawn", "MacArthur", "PowerPoint", "LinkedIn", "GitHub", "WiFi", "iOS", "DiCaprio", "O'Neil", "X-Men")) {
            for ((title, line) in listOf(
                "$name Repair Clinic" to line(name to 0.3f, "Repair" to 0.95f, "Clinic" to 0.93f),
                "Family Meetup $name" to line("Family" to 0.95f, "Meetup" to 0.93f, name to 0.3f),
            )) assertEquals(title, title, TitleJunk.trim(title, listOf(line)))
        }
    }

    @Test fun aWordWithAPlainWordInsideIsNeverCutWhole() {
        // strong-shaped, but no single cut leaves a plain word: the word stays as it is
        val l = line("Jazz" to 0.95f, "ParktEaMx" to 0.3f)
        assertEquals("Jazz ParktEaMx", TitleJunk.trim("Jazz ParktEaMx", listOf(l)))
        // lower case glued onto lower case: no shape change to cut at
        val m = line("Spring" to 0.95f, "Fairbnmp" to 0.3f)
        assertEquals("Spring Fairbnmp", TitleJunk.trim("Spring Fairbnmp", listOf(m)))
    }

    @Test fun realWordsThatLookGluedSurvive() {
        for (t in listOf("iPhone Repair Day", "eBay Sellers Meetup", "McDonald Lecture", "PowerPoint Basics", "YouTube Live",
            "TEDx Talks", "LinkedIn Clinic", "Open Day iOS", "Jazz WiFi Lounge"))
            assertEquals(t, t, TitleJunk.trim(t, listOf(glued(t, 0.9f))))
        // joined by an apostrophe or a hyphen, or no letter shape to cut at: safe even when read less sure
        for (t in listOf("O'Brien Lecture", "X-Men Night", "K-Pop Party", "Robotics TEDx", "Choir Rehearsal"))
            assertEquals(t, t, TitleJunk.trim(t, listOf(glued(t, 0.3f))))
    }

    /**
     * Real words at the start or the end of a title: no vowel, acronyms, abbreviations, y as the vowel, misspellings.
     * Each must survive in the prefilled title at every confidence; a cut one is listed with its junk shape.
     */
    @Test fun realWordsAtTheEndsSurvive() {
        val words = listOf("Hmm", "Shh", "Psst", "Brr", "Tsk", "Nth", "Pfft", "Crwth", "Cwm",
            "NASA", "UNICEF", "BBQ", "DIY", "FAQ", "HTML", "NGO", "RSVP", "LGBTQ", "HR",
            "Dr", "Mr", "Mrs", "Ms", "St", "Jr", "Rd", "Blvd", "Dept", "Mgmt", "Pvt", "Ltd", "Bldg", "vs",
            "Gym", "Myth", "Rhythm", "Lynx", "Crypt", "Hymn", "Spy", "Nymph", "Syzygy",
            "Beginers", "Wokshop", "Comunity", "Festivel", "Calender", "Libary")
        val cut = mutableListOf<String>()
        for (w in words) for (c in listOf(0.95f, 0.8f, 0.6f, 0.4f, 0.2f, 0.05f)) {
            val front = "$w Garden Talk"
            val back = "Garden Talk $w"
            val f = TitleJunk.trim(front, listOf(line(w to c, "Garden" to 0.94f, "Talk" to 0.92f)))
            val b = TitleJunk.trim(back, listOf(line("Garden" to 0.94f, "Talk" to 0.92f, w to c)))
            if (f != front) cut += "$w first @$c (${TitleJunk.kind(w)})"
            if (b != back) cut += "$w last @$c (${TitleJunk.kind(w)})"
        }
        assertTrue("cut: $cut", cut.isEmpty())
    }

    @Test fun gluedJunkStaysWhereTheRuleSaysNever() {
        // in the middle
        val mid = line("Jazz" to 0.95f, "pOrTqPark" to 0.3f, "Night" to 0.95f)
        assertEquals("Jazz pOrTqPark Night", TitleJunk.trim("Jazz pOrTqPark Night", listOf(mid)))
        // the glued word is on the page elsewhere
        val head = line("dRuMxGarden" to 0.3f, "Party" to 0.95f)
        val body = line("Sponsored" to 0.95f, "by" to 0.95f, "dRuMxGarden" to 0.95f, top = 600)
        assertEquals("dRuMxGarden Party", TitleJunk.trim("dRuMxGarden Party", listOf(head, body)))
    }

    @Test fun aWordThePageHasElsewhereStays() {
        val head = line("Kltrs" to 0.9f, "Open" to 0.95f, "Day" to 0.95f)
        val body = line("Hosted" to 0.95f, "by" to 0.95f, "Kltrs" to 0.95f, top = 600)
        assertEquals("Kltrs Open Day", TitleJunk.trim("Kltrs Open Day", listOf(head, body)))
    }

    @Test fun neverDownToNothing() {
        val l = line("x7q2" to 0.3f, "r]m" to 0.3f)
        assertEquals("x7q2 r]m", TitleJunk.trim("x7q2 r]m", listOf(l)))
        assertEquals("BBQ Rd", TitleJunk.trim("BBQ Rd", listOf(line("BBQ" to 0.3f, "Rd" to 0.9f))))
    }

    @Test fun junkLines() {
        assertTrue(TitleJunk.junkLine(line("rrrr" to 0.4f, "...." to 0.4f, "j" to 0.4f)))
        assertTrue(TitleJunk.junkLine(line("x7q2" to 0.9f, "sHoWv" to 0.9f)))
        // tier-two shapes only (no vowel, short letters, acronyms) may be real: never a junk line, whatever the confidence
        assertFalse(TitleJunk.junkLine(line("Xqzw" to 0.3f, "Pfqk" to 0.3f)))
        assertFalse(TitleJunk.junkLine(line("i" to 0.3f, "j" to 0.4f)))
        assertFalse(TitleJunk.junkLine(line("PTA" to 0.3f)))
        // a line with a real word is never a junk line
        assertFalse(TitleJunk.junkLine(line("x7q2" to 0.3f, "Annual" to 0.9f)))
    }
}
