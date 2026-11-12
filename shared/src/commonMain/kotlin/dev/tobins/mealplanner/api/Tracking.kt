package dev.tobins.mealplanner.api

import kotlinx.serialization.Serializable

/** What became of a planned meal. */
enum class MealStatus { PLANNED, EATEN, SKIPPED }

/** Something eaten outside the plan, logged with rough numbers. */
@Serializable
data class LogEntry(
    val id: Long = 0,
    /** ISO date. */
    val date: String,
    val description: String,
    val kcal: Double,
    val proteinG: Double,
)

/** One day as the Today screen shows it. */
@Serializable
data class DayView(
    val date: String,
    /** The plan week this day belongs to (its Monday). */
    val weekStart: String,
    /** Offset of the day within that week. */
    val dayIndex: Int,
    /** Null until a profile exists. */
    val targets: DailyTargets?,
    val meals: List<PlannedMeal>,
    val extras: List<LogEntry>,
    /** Eaten meals plus extras. */
    val eatenKcal: Double,
    val eatenProteinG: Double,
    /** Everything planned that is not skipped, plus extras: where the day ends if the plan is followed. */
    val expectedKcal: Double,
    val expectedProteinG: Double,
)

@Serializable
data class WeightEntry(
    /** ISO date. */
    val date: String,
    val kg: Double,
    /** Smoothed weight on that date; day-to-day noise mostly removed. */
    val trendKg: Double = kg,
)

/** A proposed change of the calorie target, from comparing the weight trend with the goal. */
@Serializable
data class TargetSuggestion(
    val currentKcal: Int,
    val suggestedKcal: Int,
    /** Loss per week over the measured period according to the trend; negative when gaining. */
    val actualKgPerWeek: Double,
    val goalKgPerWeek: Double,
)

@Serializable
data class WeightLog(
    val entries: List<WeightEntry>,
    /** Trend change per week over the last two weeks; null with too little data. */
    val actualKgPerWeek: Double?,
    /** Null when the trend matches the goal or there is too little data. */
    val suggestion: TargetSuggestion?,
)
