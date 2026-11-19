// Frozen from v0.1.8 (1a56aa21c718bafd22d7c56d7176ef7e8bf7ca1c) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released.api

import kotlinx.serialization.Serializable

/** The meals of a day, in eating order. [share] is the part of the daily calories a slot gets. */
enum class MealSlot(val share: Double) {
    BREAKFAST(0.22),
    LUNCH(0.30),
    DINNER(0.38),
    SNACK(0.10),
}

/** One meal in a week plan. */
@Serializable
data class PlannedMeal(
    /** 0 is the plan's first day. */
    val day: Int,
    val slot: MealSlot,
    val recipeId: Long,
    val title: String,
    /** How many of the recipe's servings to eat. */
    val portions: Double,
    val kcal: Double,
    val proteinG: Double,
    /** True when this is what is left of a recipe cooked on an earlier day. */
    val leftover: Boolean = false,
    /** Locked meals survive regeneration of the plan. */
    val locked: Boolean = false,
    val status: MealStatus = MealStatus.PLANNED,
    /** Estimated euros for the portions eaten, with the week's offers; null when unknown. */
    val cost: Double? = null,
    /**
     * The servings to cook when this meal is made, what the recipe shows when opened from it; null
     * from a server that does not send it. A dinner: its own [portions] plus those of the next day's
     * leftover lunch cooked with it. That lunch: 0, it was cooked the day before. A skipped meal: 0.
     * Not stored: it follows from the week's meals, and agrees with what the shopping list counts.
     */
    val cookPortions: Double? = null,
    /**
     * Grams of fibre in the portions eaten; null from a server before fibre counted (issue 223), and then
     * the app shows no fibre. Not stored with the meal as [kcal] is: it follows from the recipe's
     * nutrition as it is now, as [cost] follows from today's prices, so meals planned before fibre
     * counted have it too.
     */
    val fibreG: Double? = null,
    /**
     * True when what was in this meal differs from its recipe (issue 226, [MealLines]). Then [kcal],
     * [proteinG] and [fibreG] are those of the meal as adjusted, worked out when read; an app that
     * does not know adjustments shows the right numbers without this.
     */
    val adjusted: Boolean = false,
)

/**
 * The recipe the user puts in a meal's slot themselves (`PUT /api/plans/{weekStart}/meals/{day}/{slot}`).
 * A request type only: the answer is the week's [WeekPlan], unchanged. Leaving a meal out is the
 * existing status route with [MealStatus.SKIPPED].
 */
@Serializable
data class MealChoice(val recipeId: Long)

@Serializable
data class PlanDay(
    /** ISO date, e.g. `2026-11-16`. */
    val date: String,
    val meals: List<PlannedMeal>,
    val kcal: Double,
    val proteinG: Double,
    /** Fibre of the meals not skipped, as [kcal]; null from a server before fibre counted. */
    val fibreG: Double? = null,
)

@Serializable
data class WeekPlan(
    /** ISO date of the first day. */
    val weekStart: String,
    val targets: DailyTargets,
    val days: List<PlanDay>,
    /** Slots the planner had no suitable recipe for. */
    val emptySlots: List<MealSlot>,
    /** Estimated euros for every planned meal with the week's offers, and at regular prices. */
    val cost: Double? = null,
    val costWithoutOffers: Double? = null,
)
