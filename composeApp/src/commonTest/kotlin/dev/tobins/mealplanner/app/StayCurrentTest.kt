package dev.tobins.mealplanner.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun eachReturnToTheFrontIsCountedOnce() {
        val front = AppFront(inFront = true)
        front.report(true)
        assertEquals(0, front.returns, "already in front")
        front.report(false)
        front.report(false)
        assertFalse(front.inFront.value)
        front.report(true)
        front.report(true)
        assertEquals(1, front.returns)
        assertTrue(front.inFront.value)
    }

    @Test
    fun aCheckWhileInFrontStopsBehindAndRunsAtOnceOnReturn() = runTest {
        val front = AppFront(inFront = true)
        var runs = 0
        backgroundScope.launch { front.whileInFront(30.seconds) { runs++ } }
        runCurrent()
        assertEquals(1, runs, "at once")
        advanceTimeBy(60_001)
        assertEquals(3, runs, "and every 30 s")

        front.report(false)
        advanceTimeBy(10 * 60_000)
        assertEquals(3, runs, "nothing while behind")

        front.report(true)
        runCurrent()
        assertEquals(4, runs, "at once on return")
    }
}
