package `in`.gauthama.snapslotify.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The taps-saved arithmetic ([TapsSaved], costs in [TapCosts]). Today is Sat 14 Nov 2026.
 *
 * Both sides start inside the app (getting to the calendar app or to the review screen counts 0). A timed event by hand,
 * before its title and location: add 1 + date (open, day, confirm) 3 + time (open, hour, minute, AM/PM, confirm) 5 +
 * save 1 = 10, plus one per month ahead. With the app: Done on the confirmation 1 (once per page) + form touches + typed
 * taps and their overhead + Add 1 + Save in the calendar editor 1.
 */
class TapsSavedTest {

    private val today = LocalDate.of(2026, 11, 14)
    private fun d(s: String) = LocalDate.parse(s)
    private fun event(title: String?, location: String? = null, date: String = "2026-11-21", touches: Int = 0, typed: Int = 0, timed: Boolean = true) =
        TapsSaved.Added(title, location, d(date), timed, touches, typed)

    @Test fun monthNavigationCountsCalendarMonthsAcrossTheYear() {
        assertEquals(0, TapsSaved.monthsAhead(d("2026-11-30"), today))        // same month
        assertEquals(1, TapsSaved.monthsAhead(d("2026-12-01"), today))        // next month
        assertEquals(2, TapsSaved.monthsAhead(d("2027-01-31"), today))        // two months away, across the year end
        assertEquals(1, TapsSaved.monthsAhead(d("2027-01-05"), d("2026-12-20")))   // December to January
        assertEquals(14, TapsSaved.monthsAhead(d("2028-01-01"), today))
        assertEquals(0, TapsSaved.monthsAhead(d("2026-10-30"), today))        // an earlier month: no forward steps
        // by hand, each month is one more tap: "Yoga" is 10 + 1 + 4 + ceil(0.4) = 16 taps plus its months
        for ((date, months) in listOf("2026-11-28" to 0, "2026-12-05" to 1, "2027-01-09" to 2))
            assertEquals(date, 16 + months, TapsSaved.manual(event("Yoga", date = date), today))
        assertEquals(17, TapsSaved.manual(event("Yoga", date = "2027-01-05"), d("2026-12-20")))   // across the year end
    }

    @Test fun thePrefilledTitleAcceptedAsIs() {
        // "Dinner at Olive Tree" (20 chars), "Olive Tree, MG Road" (19), Sat 21 Nov 8 pm; nothing touched in the form
        val e = event("Dinner at Olive Tree", "Olive Tree, MG Road")
        // by hand: 10 + 1 + 20 (title) + 1 + 19 (location) + ceil(3.9) = 55; with the app: 1 + 1 + 1 = 3
        assertEquals(TapsSaved.Estimate(1, 55, 3, 52, 39), TapsSaved.estimate(e, today, firstOnPage = true))
    }

    @Test fun aTitleRetypedInFullSavesNothingForTheTitle() {
        // the title cleared and typed again as "Team lunch": 10 key taps, one field edit
        val retyped = TapsSaved.estimate(event("Team lunch", touches = 1, typed = 10), today, firstOnPage = true)
        // by hand: 10 + 1 + 10 + ceil(1.0) = 22; with the app: 1 + 1 + 10 + ceil(1.0) + 1 + 1 = 15
        assertEquals(TapsSaved.Estimate(1, 22, 15, 7, 0), retyped)
        // the title adds 0: what is left is the rest of the event, 10 by hand against 3 with the app
        assertEquals(10 - 3, retyped.saved)
        // accepted as prefilled, the same title saves its 12 taps
        assertEquals(19, TapsSaved.estimate(event("Team lunch"), today, firstOnPage = true).saved)
    }

    @Test fun aTitleWithASmallEdit() {
        // "Science Fair" with " 2027" typed on the end: 5 key taps, one field edit; "Science Fair 2027" is 17 chars
        val e = event("Science Fair 2027", touches = 1, typed = 5)
        // by hand: 10 + 1 + 17 + ceil(1.7) = 30; with the app: 1 + 1 + 5 + ceil(0.5) + 1 + 1 = 10
        assertEquals(TapsSaved.Estimate(1, 30, 10, 20, 12), TapsSaved.estimate(e, today, firstOnPage = true))
    }

    @Test fun aDateChangedToALaterMonth() {
        // prefilled Sat 21 Nov, changed in the form's picker to Sat 9 Jan 2027: the edit, the day, OK and two months
        val pick = TapsSaved.formDatePick(d("2026-11-21"), d("2027-01-09"))
        assertEquals(4, pick)
        val moved = event("Science Fair", date = "2027-01-09", touches = 1 + pick)
        // by hand, from the final date: 10 + 2 months + 1 + 12 + ceil(1.2) = 27; with the app: 1 + 5 + 1 + 1 = 8
        assertEquals(TapsSaved.Estimate(1, 27, 8, 19, 12), TapsSaved.estimate(moved, today, firstOnPage = true))
        // left on 21 Nov it would be 25 by hand and 3 with the app
        assertEquals(TapsSaved.Estimate(1, 25, 3, 22, 12), TapsSaved.estimate(event("Science Fair"), today, firstOnPage = true))
        // an earlier month moves the picker back just the same
        assertEquals(3, TapsSaved.formDatePick(d("2026-12-03"), d("2026-11-28")))
        assertEquals(2, TapsSaved.formDatePick(d("2026-11-21"), d("2026-11-28")))
    }

    @Test fun aBatchOfFiveFromACalendarPage() {
        // Add all on a January 2027 calendar grid: three timed, two all day, nothing edited
        val events = listOf(
            event("Yoga", date = "2027-01-04"),                           // 12 + 1 + 4 + 1 = 18
            event("Book club", date = "2027-01-07"),                      // 12 + 1 + 9 + 1 = 23
            event("Dentist", date = "2027-01-14"),                        // 12 + 1 + 7 + 1 = 21
            event("Team offsite", date = "2027-01-15", timed = false),    // 8 + 1 + 12 + 2 = 23
            event("Brunch", date = "2027-01-30", timed = false),          // 8 + 1 + 6 + 1 = 16
        )
        // with the app: Done once (1), then Add + calendar Save per event (2 each) = 1 + 10 = 11
        assertEquals(TapsSaved.Estimate(5, 101, 11, 90, 38), TapsSaved.page(events, today))
    }

    @Test fun theReviewStripForecastsBeforeAddAndCountsAfter() {
        val dinner = event("Dinner at Olive Tree", "Olive Tree, MG Road")
        val lunch = event("Team lunch", date = "2026-12-03", touches = 1, typed = 10)
        // before Add: the forecast carries Done (55 - 3 = 52, as above)
        assertEquals(TapsSaved.PageLine(0, 0, 1, 52), TapsSaved.pageLine(emptyList(), listOf(dinner), today))
        // after Add: the same 52 as saved, nothing left
        assertEquals(TapsSaved.PageLine(1, 52, 0, 0), TapsSaved.pageLine(listOf(dinner), emptyList(), today))
        // one added, one still ready: the rest pays no Done (23 - 14 = 9)
        assertEquals(TapsSaved.PageLine(1, 52, 1, 9), TapsSaved.pageLine(listOf(dinner), listOf(lunch), today))
        // the title is one tap per character: one more letter, one more tap by hand (40 characters, overhead stays 4)
        assertEquals(53, TapsSaved.pageLine(emptyList(), listOf(dinner.copy(title = "Dinner at Olive Trees")), today).wouldSave)
    }

    @Test fun aTwentyFourHourClockHasNoAmPmStep() {
        // only the time field filled (a time chip); the date picked and the 10-letter title typed in the form, the first event
        val e = event("Team lunch", touches = 1 + 1 + (1 + TapsSaved.formDatePick(today, d("2026-11-21"))) + 1, typed = 10)
        // by hand on a 24-hour phone: 10 - 1 (no AM/PM) + 1 + 10 + 1 = 21; with the app: Done 1 + Add event 1 + title 1
        // + date picker 3 + time chip 1 + typed 10 + 1 + Add 1 + Save 1 = 20
        assertEquals(TapsSaved.Estimate(1, 21, 20, 1, 0), TapsSaved.estimate(e, today, firstOnPage = true, amPm = false))
        // the 12-hour clock pays one more tap by hand
        assertEquals(TapsSaved.Estimate(1, 22, 20, 2, 0), TapsSaved.estimate(e, today, firstOnPage = true, amPm = true))
        // the home example loses the one AM/PM tap on a 24-hour phone
        assertEquals(TapsSaved.Estimate(1, 51, 3, 48, 35), TapsSaved.example(today, amPm = false))
    }

    @Test fun anEndTimeTheCalendarWouldNotSetPaysTheEndPickerByHand() {
        // nothing found: the date picked, "Team lunch" typed, one start-and-end chip tapped (08:00 to 12:00), the first event
        val e = event("Team lunch", touches = 1 + (1 + TapsSaved.formDatePick(today, d("2026-11-21"))) + 1, typed = 10)
        // by hand on a 24-hour phone: 1 + 3 + 4 (start) + 4 (end) + 1 + 1 + 10 + 1 = 25; with the app: Done 1 + title 1
        // + date 3 + chip 1 + typed 10 + 1 + Add 1 + Save 1 = 19
        assertEquals(TapsSaved.Estimate(1, 25, 19, 6, 0), TapsSaved.estimate(e.copy(endSet = true), today, firstOnPage = true, amPm = false))
        // an end one hour after the start (or none): the calendar sets it, so only the start picker by hand
        assertEquals(TapsSaved.Estimate(1, 21, 19, 2, 0), TapsSaved.estimate(e, today, firstOnPage = true, amPm = false))
        // a 12-hour phone: both pickers pay AM/PM
        assertEquals(27, TapsSaved.manual(e.copy(endSet = true), today, amPm = true))
        // an all-day event never pays a time picker
        assertEquals(TapsSaved.manual(e.copy(timed = false), today), TapsSaved.manual(e.copy(timed = false, endSet = true), today))
    }

    @Test fun anEventOverSeveralDaysPaysTheEndDatePickerByHand() {
        // Wed 20 to Fri 22 Jan 2027, all day, "Team offsite" (12) accepted as found; today is 14 Nov 2026
        val offsite = event("Team offsite", date = "2027-01-20", timed = false)
        // by hand: add 1 + date 3 + 2 months + all day 1 + save 1 + 1 + 12 + ceil(1.2) = 23
        assertEquals(23, TapsSaved.manual(offsite, today))
        // the end-date picker opens on the start date: 3 more for the same month
        assertEquals(26, TapsSaved.manual(offsite.copy(endDate = d("2027-01-22")), today))
        // Sat 30 Jan to Tue 2 Feb: one month step more
        assertEquals(27, TapsSaved.manual(offsite.copy(date = d("2027-01-30"), endDate = d("2027-02-02")), today))
        // an end date on the start date (or none) costs nothing more
        assertEquals(23, TapsSaved.manual(offsite.copy(endDate = d("2027-01-20")), today))
        // with the app the dates were filled in: Add + Save + Done = 3, so 23 saved
        assertEquals(TapsSaved.Estimate(1, 26, 3, 23, 12), TapsSaved.estimate(offsite.copy(endDate = d("2027-01-22")), today, firstOnPage = true))
    }

    @Test fun theHomeScreensExample() {
        // a 20-letter title, a 15-letter place, next month: 10 + 1 + 1 + 20 + 1 + 15 + ceil(3.5) = 52 by hand, 3 with the app
        assertEquals(TapsSaved.Estimate(1, 52, 3, 49, 35), TapsSaved.example(today))
    }

    /** Team lunch with everything fixed in the form: title retyped, date moved a month, time picked again. */
    private val fixedLunch = event("Team lunch", date = "2026-12-03", typed = 10,
        touches = 1 + (1 + TapsSaved.formDatePick(d("2026-11-03"), d("2026-12-03"))) + TapCosts.FORM_TIME_PICKER)

    @Test fun anEventThatNeededALotOfFixingKeepsItsTrueNumberButShowsZero() {
        // by hand 10 + 1 + 1 + 10 + 1 = 23; with the app 1 + 1 (title) + 4 (date) + 5 (time) + 10 + ceil(1.0) + 1 + 1 = 24
        val e = TapsSaved.estimate(fixedLunch, today, firstOnPage = true)
        assertEquals(TapsSaved.Estimate(1, 23, 24, -1, 0), e)
        assertEquals(0, e.shownSaved)
        // the strip forecast is the true -1, shown as "no taps saved"
        assertEquals(-1, TapsSaved.pageLine(emptyList(), listOf(fixedLunch), today).wouldSave)
        // a heavily edited event: far below 0, shown as 0
        assertEquals(-22, TapsSaved.estimate(event("Gym", touches = 30, typed = 3), today, firstOnPage = true).saved)
    }

    @Test fun aPageTotalCountsAnEventBelowZeroSoItAddsUp() {
        // the fixed Team lunch first (-1, it carries Done), then the dinner accepted as is
        // (55 by hand, Add + calendar Save = 2 with the app: 53)
        val p = TapsSaved.page(listOf(fixedLunch, event("Dinner at Olive Tree", "Olive Tree, MG Road")), today)
        assertEquals(TapsSaved.Estimate(2, 78, 26, 52, 39), p)
        assertEquals(p.manualTaps - p.appTaps, p.saved)       // floored per event it would have read 53
        assertEquals(52, p.shownSaved)
        // a page whose total is below 0 shows 0
        assertEquals(0, TapsSaved.page(listOf(fixedLunch), today).shownSaved)
    }
}
