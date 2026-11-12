package dev.tobins.mealplanner.tracking

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeightTrendTest {
    private val profile = Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, goalKgPerWeek = 0.5)
    private val targets = DailyTargets(2040, 160)

    /** Daily weighings losing [perWeek] kg a week, with a repeating ±0.4 kg wobble. */
    private fun daily(days: Int, perWeek: Double) =
        (0 until days).map { Weighing(it.toLong(), 90.0 - perWeek / 7 * it + listOf(0.4, -0.4, 0.0)[it % 3]) }

    @Test
    fun trendStartsAtTheFirstWeighingAndDampsNoise() {
        val weighings = listOf(Weighing(0, 90.0), Weighing(1, 91.0), Weighing(2, 89.0))
        val trend = WeightTrend.smooth(weighings)

        assertEquals(90.0, trend[0])
        assertEquals(90.1, trend[1], absoluteTolerance = 1e-9)
        assertEquals(89.99, trend[2], absoluteTolerance = 1e-9)
    }

    @Test
    fun aGapOfSeveralDaysPullsTheTrendFurther() {
        val trend = WeightTrend.smooth(listOf(Weighing(0, 90.0), Weighing(7, 88.0)))
        // 1 - 0.9^7 of the way from 90 to 88
        assertEquals(90 - 2 * (1 - 0.4782969), trend[1], absoluteTolerance = 1e-6)
    }

    @Test
    fun lossPerWeekNeedsTwoWeeksOfData() {
        assertNull(WeightTrend.lossPerWeek(emptyList()))
        assertNull(WeightTrend.lossPerWeek(daily(10, 0.5)), "ten days is too short")
        assertNull(WeightTrend.lossPerWeek(listOf(Weighing(0, 90.0), Weighing(20, 89.0))), "two weighings say little")

        val steady = WeightTrend.lossPerWeek(daily(40, 0.5))!!
        assertEquals(0.5, steady, absoluteTolerance = 0.08)
        assertTrue(WeightTrend.lossPerWeek(daily(40, -0.3))!! < 0, "gaining shows as a negative loss")
    }

    @Test
    fun suggestionClosesTheGapInBoundedSteps() {
        assertNull(WeightTrend.suggest(profile, targets, lossPerWeek = 0.4), "within normal noise of the goal")

        // 0.3 kg a week too slow is 330 kcal a day, capped at 250, rounded to fifties.
        assertEquals(1800, WeightTrend.suggest(profile, targets, lossPerWeek = 0.2)!!.suggestedKcal)
        // 0.25 kg a week too fast: eat 275 -> 250 more.
        assertEquals(2300, WeightTrend.suggest(profile, targets, lossPerWeek = 0.75)!!.suggestedKcal)
        // Never below the minimum.
        assertEquals(1500, WeightTrend.suggest(profile, DailyTargets(1600, 160), lossPerWeek = 0.0)!!.suggestedKcal)
        assertNull(WeightTrend.suggest(profile, DailyTargets(1500, 160), lossPerWeek = 0.0), "already at the minimum")
    }
}
