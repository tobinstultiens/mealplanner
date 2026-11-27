// Frozen from v0.1.13 (bd9ebd8e6d679057d814bb48ea7186dbd3816907) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * What became of a planned meal. Annotated because the app sends it bare as a request body: the web
 * client cannot find a serializer by reflection, as the JVM and Android do for an enum without one.
 */
@Serializable
enum class MealStatus { PLANNED, EATEN, SKIPPED }

/**
 * Something eaten outside the plan (README, "Food eaten outside the plan"): typed, as a description with rough numbers, or
 * a food of the account's catalogue picked in an amount ([food], issue 362), whose numbers the server counted when it was
 * logged or its amount last changed. Either way the entry keeps its numbers: a food's values changed later do not reach it.
 * Also the body of `POST /api/log` for a typed entry, where [fibreG] and [food] stay null and are not sent, so that the body
 * is the one apps sent before them.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LogEntry(
    val id: Long = 0,
    /** ISO date. */
    val date: String,
    /** As typed; for a food, the server's line in English ("2 bar Protein bar"), for apps that do not know foods. */
    val description: String,
    val kcal: Double,
    val proteinG: Double,
    /** A logged food's fibre, which the day's fibre counts as it counts a planned meal's; null for a typed entry. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fibreG: Double? = null,
    /** The food it is, in the amount and unit it was picked in; null for a typed entry. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val food: LoggedFood? = null,
)

/**
 * A food logged as eaten outside the plan (issue 362): the account's ingredient or product [ingredientId] in [amount] of
 * [unit], one of the units it has, as picked. [name] is the food's name when it was logged, so that the entry reads the
 * same once the food is renamed or gone.
 */
@Serializable
data class LoggedFood(
    /** Null once the food is gone from the catalogue: the entry keeps its numbers, which can be typed over but not recounted. */
    val ingredientId: Long? = null,
    val name: String,
    val amount: Double,
    /** `g`, `ml`, `piece` or the name of one of the food's portions (`bar`). */
    val unit: String,
    /** False when it counted as nothing: the food had no values, or its amount could not be weighed. */
    val counted: Boolean = true,
)

/**
 * Body of `POST /api/log/foods`: the account's ingredient [ingredientId] in [amount] of [unit] eaten on [date] (ISO), counted
 * from its values now. The answer is the day's [DayView]. `PUT /api/log/{id}/food` takes a [MealFoodInput] to change the
 * amount, the unit or the food, and recounts.
 */
@Serializable
data class LogFoodInput(val date: String, val ingredientId: Long, val amount: Double, val unit: String)

/**
 * Body of `PUT /api/log/{id}`: the entry typed over, a typed entry from then on (a food's entry too, such as one whose food
 * is gone and cannot be recounted). Its date stays. The answer is the day's [DayView].
 */
@Serializable
data class LogTextInput(val description: String, val kcal: Double, val proteinG: Double)

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
    /**
     * Fibre eaten and expected, as the kcal; null from a server before fibre counted. A logged food's fibre counts here
     * ([LogEntry.fibreG]); a typed extra has kcal and protein only, so it adds nothing.
     */
    val eatenFibreG: Double? = null,
    val expectedFibreG: Double? = null,
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
