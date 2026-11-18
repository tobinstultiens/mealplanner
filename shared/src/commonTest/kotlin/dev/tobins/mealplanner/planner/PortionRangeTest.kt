package dev.tobins.mealplanner.planner

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import kotlin.test.Test
import kotlin.test.assertEquals

class PortionRangeTest {
    @Test
    fun lunchAndDinnerStayWithinTheRangeAndBreakfastAndSnackReachTwo() {
        val range = PortionRange(0.75, 1.5)
        assertEquals(listOf(0.75, 1.0, 1.25, 1.5), range.steps(MealSlot.DINNER))
        assertEquals(listOf(0.75, 1.0, 1.25, 1.5), range.steps(MealSlot.LUNCH))
        assertEquals(listOf(0.75, 1.0, 1.25, 1.5, 1.75, 2.0), range.steps(MealSlot.BREAKFAST))
        assertEquals(listOf(0.75, 1.0, 1.25, 1.5, 1.75, 2.0), range.steps(MealSlot.SNACK))
        assertEquals(listOf(1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5), PortionRange(1.0, 2.5).steps(MealSlot.SNACK))
    }

    @Test
    fun whatIsLeftLimitsTheStepsAndLessThanTheSmallestIsAllThereIs() {
        val range = PortionRange(0.75, 1.5)
        assertEquals(listOf(0.75, 1.0), range.steps(MealSlot.LUNCH, available = 1.0))
        assertEquals(listOf(0.5), range.steps(MealSlot.LUNCH, available = 0.5))
    }

    @Test
    fun aRangeIsInQuarterServingsFromAQuarterToThreeAndNotUpsideDown() {
        assertEquals(emptyList(), PortionRange().problems())
        assertEquals(emptyList(), PortionRange(0.25, 3.0).problems())
        assertEquals(emptyList(), PortionRange(1.0, 1.0).problems())
        assertEquals(listOf("minPortions must be between 0.25 and 3"), PortionRange(0.0, 1.0).problems())
        assertEquals(listOf("maxPortions must be between 0.25 and 3"), PortionRange(1.0, 3.25).problems())
        assertEquals(listOf("minPortions must be in quarter servings"), PortionRange(0.8, 1.5).problems())
        assertEquals(listOf("maxPortions must be in quarter servings"), PortionRange(0.75, 1.6).problems())
        assertEquals(listOf("minPortions must not be more than maxPortions"), PortionRange(1.5, 1.0).problems())
        assertEquals(2, PortionRange(Double.NaN, 1.0).problems().size, "not a number is neither in the range nor a quarter")
    }

    @Test
    fun aProfileWithoutARangeHasTheDefaults() {
        assertEquals(PortionRange(0.75, 1.5), PortionRange.of(null))
        val profile = Profile(80.0, 180.0, 40, Sex.MALE, ActivityLevel.LIGHT)
        assertEquals(PortionRange(0.75, 1.5), PortionRange.of(profile))
        assertEquals(PortionRange(1.0, 2.0), PortionRange.of(profile.copy(minPortions = 1.0, maxPortions = 2.0)))
    }
}
