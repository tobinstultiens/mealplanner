package dev.tobins.mealplanner.server.offers

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNull

class LidlGuardTest {
    private fun date(s: String) = LocalDate.parse(s)

    @Test
    fun noOffersMeansNothingToJudge() {
        assertNull(LidlGuard.warning(emptyList(), date("2026-11-20")))
    }

    @Test
    fun warnsWhenEveryOfferHasEnded() {
        // Monday 16 November; the last offers ended on Sunday the 15th.
        assertContains(LidlGuard.warning(listOf(date("2026-11-09"), date("2026-11-15")), date("2026-11-16"))!!, "have ended")
    }

    @Test
    fun anOfferEndingTodayIsStillCurrent() {
        // Wednesday 18 November: the week's offers run to the 22nd.
        assertNull(LidlGuard.warning(listOf(date("2026-11-22")), date("2026-11-18")))
        assertNull(LidlGuard.warning(listOf(date("2026-11-18")), date("2026-11-18")))
    }

    @Test
    fun onFridayOrLaterWarnsWhenNoneRunsIntoNextWeek() {
        val thisWeek = listOf(date("2026-11-22"))
        assertNull(LidlGuard.warning(thisWeek, date("2026-11-19")), "Thursday: next week's offers need not be up yet")
        for (day in listOf("2026-11-20", "2026-11-21", "2026-11-22")) {
            assertContains(LidlGuard.warning(thisWeek, date(day))!!, "next week", message = day)
        }
    }

    @Test
    fun onFridayOrLaterAnOfferIntoNextWeekIsEnough() {
        assertNull(LidlGuard.warning(listOf(date("2026-11-22"), date("2026-11-29")), date("2026-11-20")))
        assertNull(LidlGuard.warning(listOf(date("2026-11-23")), date("2026-11-22")))
    }
}
