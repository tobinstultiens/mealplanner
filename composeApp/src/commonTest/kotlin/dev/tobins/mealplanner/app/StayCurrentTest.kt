package dev.tobins.mealplanner.app

import kotlin.test.Test
import kotlin.test.assertEquals

class StayCurrentTest {
    @Test
    fun todayFollowsToTheNewDayWhenItWasShowingTheOldOne() {
        assertEquals("2026-11-19", followedDate(oldNow = "2026-11-18", shown = "2026-11-18", newNow = "2026-11-19"))
    }

    @Test
    fun aDayTheUserSteppedToStays() {
        assertEquals("2026-11-19", followedDate(oldNow = "2026-11-18", shown = "2026-11-19", newNow = "2026-11-19"))
        assertEquals("2026-11-17", followedDate(oldNow = "2026-11-18", shown = "2026-11-17", newNow = "2026-11-19"))
    }

    @Test
    fun returningTheSameDayMovesNothing() {
        assertEquals("2026-11-20", followedDate("2026-11-18", "2026-11-20", "2026-11-18"))
        assertEquals("2026-11-18", followedDate("2026-11-18", "2026-11-18", "2026-11-18"))
    }

    @Test
    fun thePlanFollowsToTheNewWeekOnlyWhenItWasOnThisWeek() {
        val thisWeek = mondayOf("2026-11-22") // a Sunday
        val nextWeek = mondayOf("2026-11-23") // the Monday after
        assertEquals(nextWeek, followedDate(thisWeek, thisWeek, nextWeek))
        // Looking at next week on Sunday evening: after midnight that is this week, and the plan stays on it.
        assertEquals(nextWeek, followedDate(thisWeek, nextWeek, nextWeek))
        // A week further on stays too.
        assertEquals("2026-11-30", followedDate(thisWeek, "2026-11-30", nextWeek))
    }

    @Test
    fun theWeekDoesNotMoveWhileOnlyTheDayChangesWithinIt() {
        val monday = mondayOf("2026-11-18")
        assertEquals(monday, followedDate(monday, monday, mondayOf("2026-11-19")))
    }
}
