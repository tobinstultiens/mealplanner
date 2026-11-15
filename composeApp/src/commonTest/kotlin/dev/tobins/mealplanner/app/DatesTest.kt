package dev.tobins.mealplanner.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DatesTest {
    @Test
    fun dayLabelsFollowTheLocale() {
        assertEquals("Fri 13 Nov", dayLabel("2026-11-13", FormatLocale.English))
        assertEquals("Fri, Nov 13", dayLabel("2026-11-13", FormatLocale.EnglishUs))
        assertEquals("vr 13 nov", dayLabel("2026-11-13", FormatLocale.Dutch))
        assertEquals("Mo 2 Mär", dayLabel("2026-03-02", FormatLocale.German))
    }

    @Test
    fun dayAndMonthAloneHaveNoWeekday() {
        assertEquals("16 Nov", dayMonthLabel("2026-11-16", FormatLocale.English))
        assertEquals("Nov 16", dayMonthLabel("2026-11-16", FormatLocale.EnglishUs))
        assertEquals("16 nov", dayMonthLabel("2026-11-16", FormatLocale.Dutch))
    }

    @Test
    fun recentMomentsAreToldRelativeToToday() {
        val en = FormatLocale.English
        assertEquals("today 17:08", whenLabel("2026-11-15", "17:08", today = "2026-11-15", locale = en))
        assertEquals("yesterday 17:08", whenLabel("2026-11-14", "17:08", today = "2026-11-15", locale = en))
        assertEquals("Fri 13 Nov 17:08", whenLabel("2026-11-13", "17:08", today = "2026-11-15", locale = en))
    }

    @Test
    fun isoDatesStayIsoForTheApi() {
        assertEquals("2026-11-16", plusDays("2026-11-15", 1))
        assertEquals("2026-11-09", mondayOf("2026-11-15"))
    }
}
