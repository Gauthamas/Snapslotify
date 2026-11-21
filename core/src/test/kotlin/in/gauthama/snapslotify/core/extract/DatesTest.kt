package `in`.gauthama.snapslotify.core.extract

import `in`.gauthama.snapslotify.core.Fixtures
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dates.findDates / isRelativeOnly / dueDates are ports of the notebook's v3 rules (cell 103). */
class DatesTest {

    private fun f(s: String) = Dates.findDates(s).map { listOf(it.day, it.month, it.year, it.ambiguous) }

    @Test
    fun notebookSyntheticChecks() {   // the same checks as notebook cell 103
        assertEquals(listOf(listOf(5, 11, 2026, false)), f("5 Nov 2026"))
        assertEquals(listOf(listOf(5, 11, 2026, false)), f("5th November 2026"))
        assertEquals(listOf(listOf(5, 11, 2026, false)), f("Nov 5, 2026"))
        assertEquals(listOf(listOf(5, 11, 2026, false)), f("05-Nov-2026"))
        assertEquals(listOf(listOf(29, 10, 2026, false)), f("Thursday, 29 October\n2026"))
        assertEquals(listOf(listOf(5, 11, 2026, true)), f("05/11/2026"))
        assertEquals(listOf(listOf(25, 12, 2026, false)), f("25-12-2026"))
        assertEquals(listOf(listOf(5, 11, 2026, true)), f("05.11.26"))
        assertEquals(listOf(listOf(23, 10, null, false)), f("Fri 23 Oct"))
        assertEquals(emptyList<Any>(), f("Bill for Oct 2026") + f("October 2026") + f("Friday"))
        assertEquals(listOf(listOf(15, 11, 2026, false)), f("12 - 15 NOV 2026"))
        assertEquals(listOf(listOf(5, 11, 2026, false)), f("2026-11-05"))
        assertEquals(emptyList<Any>(), f("31 Nov 2026") + f("30/02/2026"))         // not real dates
        assertEquals(listOf(listOf(29, 2, null, false)), f("29 Feb"))              // valid without a year
        for (t in listOf("see you tomorrow at 6", "day after tomorrow at 11", "this coming Monday", "next Sunday at 5", "on Saturday!")) {
            assertTrue(t, Dates.isRelativeOnly(t))
        }
        for (t in listOf("this Saturday, 24 Oct at 7:30 PM", "Fri 23 Oct", "on 23 Oct 2026", "meeting at 5 pm")) {
            assertFalse(t, Dates.isRelativeOnly(t))
        }
        assertTrue(Dates.isRelativeOnly("Mon, 19 Oct 2026\nI will come tomorrow at 6 pm"))   // chip line is the sent date
        assertEquals(listOf("05 Nov 2026"), Dates.dueDates("Due date\n05 Nov 2026").map { it.raw })
        assertEquals(listOf("09 Nov 2026"), Dates.dueDates("Pay by: 09 Nov 2026").map { it.raw })
        assertEquals(emptyList<String>(), Dates.dueDates("Bill date 01 Oct 2026\nLate fee after 20 Nov 2026").map { it.raw })
        assertEquals(emptyList<String>(), Dates.dueDates("Pay before the due date to avoid a late fee.").map { it.raw })
    }

    /** The same three rules on the G text of the 29 seen cases must give the notebook's output. */
    @Test
    fun parityWithNotebookRules() {
        Fixtures.requireExport()
        val diffs = mutableListOf<String>()
        for (c in Fixtures.cases) {
            val nb = c.rules
            val nbDates = nb.getValue("find_dates").jsonArray.map { it.jsonObject }.map {
                listOf(it.getValue("day").jsonPrimitive.int, it.getValue("month").jsonPrimitive.int,
                    it.getValue("year").jsonPrimitive.intOrNull, it.getValue("raw").jsonPrimitive.content,
                    it.getValue("ambiguous").jsonPrimitive.boolean)
            }
            val ktDates = Dates.findDates(c.gText).map { listOf(it.day, it.month, it.year, it.raw, it.ambiguous) }
            if (ktDates != nbDates) diffs += "${c.id}: find_dates kotlin $ktDates notebook $nbDates"
            val nbRel = nb.getValue("is_relative_only").jsonPrimitive.boolean
            if (Dates.isRelativeOnly(c.gText) != nbRel) diffs += "${c.id}: is_relative_only kotlin ${!nbRel} notebook $nbRel"
            val nbDue = nb.getValue("due_dates").jsonArray.map { it.jsonObject.getValue("raw").jsonPrimitive.content }
            val ktDue = Dates.dueDates(c.gText).map { it.raw }
            if (ktDue != nbDue) diffs += "${c.id}: due_dates kotlin $ktDue notebook $nbDue"
        }
        println("rule parity (find_dates, is_relative_only, due_dates): ${diffs.size} difference(s)")
        diffs.forEach(::println)
        assertTrue(diffs.joinToString("\n"), diffs.isEmpty())
    }
}
