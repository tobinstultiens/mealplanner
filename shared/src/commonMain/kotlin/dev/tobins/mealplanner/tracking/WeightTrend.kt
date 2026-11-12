package dev.tobins.mealplanner.tracking

import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.TargetSuggestion
import dev.tobins.mealplanner.api.WeightEntry
import dev.tobins.mealplanner.targets.TargetCalculator
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** A weighing on a day counted from any fixed origin. */
data class Weighing(val day: Long, val kg: Double)

object WeightTrend {
    /** Share of the previous trend value kept per day: about ten days of memory. */
    private const val DAILY_RETENTION = 0.9

    private const val PERIOD_DAYS = 14

    /**
     * Exponentially smoothed weight: each weighing pulls the trend a tenth of the way
     * per day elapsed, which hides daily water-weight swings. [weighings] must be sorted by day.
     */
    fun smooth(weighings: List<Weighing>): List<Double> {
        val trend = ArrayList<Double>(weighings.size)
        for ((index, weighing) in weighings.withIndex()) {
            if (index == 0) {
                trend += weighing.kg
            } else {
                val days = (weighing.day - weighings[index - 1].day).coerceAtLeast(1)
                val pull = 1 - DAILY_RETENTION.pow(days.toInt())
                trend += trend.last() + pull * (weighing.kg - trend.last())
            }
        }
        return trend
    }

    /**
     * Trend loss per week over the last two weeks, positive when losing. Null unless
     * the weighings span those two weeks with at least six of them inside, since
     * fewer says little.
     */
    fun lossPerWeek(weighings: List<Weighing>): Double? {
        val last = weighings.lastOrNull() ?: return null
        val trend = smooth(weighings)
        val startIndex = weighings.indexOfLast { it.day <= last.day - PERIOD_DAYS }
        if (startIndex < 0 || weighings.size - startIndex - 1 < 6) return null
        val days = last.day - weighings[startIndex].day
        return (trend[startIndex] - trend.last()) / days * 7
    }

    /**
     * A new calorie target when the trend is off the goal by more than normal noise
     * (0.2 kg a week). The change is the calorie equivalent of the gap, at most 250 kcal
     * at a time, and never below the minimum for the profile.
     */
    fun suggest(profile: Profile, targets: DailyTargets, lossPerWeek: Double): TargetSuggestion? {
        val gap = profile.goalKgPerWeek - lossPerWeek // positive: losing too slowly
        if (abs(gap) < 0.2) return null
        val change = (gap * 7700 / 7).coerceIn(-250.0, 250.0)
        val suggested = (((targets.kcal - change) / 50).roundToInt() * 50).coerceAtLeast(TargetCalculator.minimumKcal(profile.sex))
        if (suggested == targets.kcal) return null
        return TargetSuggestion(targets.kcal, suggested, lossPerWeek, profile.goalKgPerWeek)
    }

    /** [entries] with their trend filled in; [dayOf] turns an ISO date into a day number. */
    fun withTrend(entries: List<WeightEntry>, dayOf: (String) -> Long): List<WeightEntry> {
        val sorted = entries.sortedBy { dayOf(it.date) }
        val trend = smooth(sorted.map { Weighing(dayOf(it.date), it.kg) })
        return sorted.mapIndexed { index, entry -> entry.copy(trendKg = trend[index]) }
    }
}
