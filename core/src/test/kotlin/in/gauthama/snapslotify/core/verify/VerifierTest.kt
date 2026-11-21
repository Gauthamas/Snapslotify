package `in`.gauthama.snapslotify.core.verify

import `in`.gauthama.snapslotify.core.extract.RawEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifierTest {

    private val text = "Pay 1,842.00 by 05 Nov 2026. Delivered. Call at 6 pm tomorrow."

    private fun ev(title: String = "x", time: String? = null, date: String? = null) = RawEvent(title, date, time, null)
    private fun codes(v: VerifiedEvent) = v.flags.map { it.code }

    @Test
    fun slotTypeCheck() {
        for (junk in listOf("312 kWh", "AX-BANKSMS", "in advance", "482913", "Delivered")) {
            assertFalse(junk, Verifier.looksLikeDate(junk))
            assertFalse(junk, Verifier.looksLikeTime(junk))
        }
        for (t in listOf("6 pm", "18:30", "11 AM", "6.30 pm", "18:30 hrs", "9 AM to 6 PM", "10:00-11:00", "tomorrow at 6 pm")) {
            assertTrue(t, Verifier.looksLikeTime(t))
        }
        for (d in listOf("Fri 23 Oct", "05/11/2026", "23 Oct 2026", "Nov 5, 2026", "tomorrow", "next Monday", "this coming Monday", "Saturday")) {
            assertTrue(d, Verifier.looksLikeDate(d))
        }
        for (t in listOf("Fri 23 Oct", "05/11/2026", "tomorrow", "1,842.00", "valid for 10 minutes", "October 2026")) {
            assertFalse(t, Verifier.looksLikeTime(t))
        }
        for (d in listOf("6 pm", "18:30 hrs", "October 2026", "Units used")) assertFalse(d, Verifier.looksLikeDate(d))
    }

    @Test
    fun notebookV31Checks() {   // the same checks as notebook cell 109
        val junkOnly = Verifier.verify(ev("Units", time = "Units used", date = "312 kWh"), text)
        assertNull(junkOnly.after)
        assertTrue(junkOnly.dropReason!!.contains("type check"))
        val relNoTime = Verifier.verify(ev("Call", date = "tomorrow"), text)
        assertNull(relNoTime.after)
        assertTrue(relNoTime.dropReason!!.contains("relative"))
        assertEquals("tomorrow", Verifier.verify(ev("Call", time = "6 pm", date = "tomorrow"), text).after!!.date)
        val junkTime = Verifier.verify(ev("Pay", time = "Delivered", date = "05 Nov 2026"), text)
        assertNull(junkTime.after!!.time)
        val odd = Verifier.verify(ev("Pay", date = "07 Nov 2026"), text)
        assertNotNull(odd.after)
        assertEquals(listOf("date_not_in_text"), odd.dateFlags)
    }

    @Test
    fun groundingAndAmbiguity() {
        val t = "Rehearsal on 05/11/2026 at 3:30 PM"
        val v = Verifier.verify(ev("Rehearsal", time = "3:30 PM", date = "05/11/2026"), t)
        assertEquals(listOf("ambiguous_date_format"), v.dateFlags)
        val clear = Verifier.verify(ev("Rehearsal", time = "3:30 PM", date = "25/11/2026"), "on 25/11/2026 at 3:30 PM")
        assertEquals(emptyList<String>(), clear.dateFlags)
    }

    @Test
    fun relativeDateIsFlaggedNotResolved() {
        val v = Verifier.verify(ev("fix the tap", time = "6 pm", date = "tomorrow"), "Mon, 19 Oct 2026\nfix the tap tomorrow at 6 pm")
        // As in the notebook: "tomorrow" is not a date found in the text, and the text is relative-only
        assertEquals(listOf("date_not_in_text", "relative_date"), v.dateFlags)
        assertEquals("tomorrow", v.after!!.date)
    }

    /** The v3.1 rule-order bug (H03b-like, invented text): due anchoring must run before the validity check. */
    @Test
    fun dueAnchoringRunsBeforeValidity() {
        val bill = "Water bill for October 2026\nPay by: 09 Nov 2026\nLate fee after 20 Nov 2026"
        val v = Verifier.verify(ev("Water bill", date = "October 2026"), bill)
        assertNotNull("a bill with a due date must not be dropped", v.after)
        assertEquals("09 Nov 2026", v.after!!.date)
        assertTrue(codes(v).containsAll(listOf("type_null_date", "due_date_mismatch")))
    }

    @Test
    fun dueAnchoringReplacesAWrongBillDate() {
        val v = Verifier.verify(ev("Pay", date = "01 Sep 2026"), "Bill date 01 Sep 2026\nDue date\n05 Nov 2026")
        assertEquals("05 Nov 2026", v.after!!.date)
        assertEquals(listOf("due_date_mismatch"), v.dateFlags)
        val two = Verifier.verify(ev("Pay", date = "05 Nov 2026"), "Due 05 Nov 2026\nPay by 07 Nov 2026")
        assertTrue(codes(two).contains("multiple_due_dates"))
    }

    @Test
    fun validityDropsEventsWithoutAnExplicitDateOrValidTime() {
        val v = Verifier.verify(ev("Thanks", date = null, time = null), "Thanks!")
        assertNull(v.after)
        assertNotNull(v.dropReason)
        val placeholder = Verifier.verify(ev("x", date = "not specified", time = "N/A"), "x")
        assertNull(placeholder.after)
    }
}
