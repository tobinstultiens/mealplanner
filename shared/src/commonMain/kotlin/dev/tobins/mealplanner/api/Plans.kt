package dev.tobins.mealplanner.api

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
)

@Serializable
data class PlanDay(
    /** ISO date, e.g. `2026-11-16`. */
    val date: String,
    val meals: List<PlannedMeal>,
    val kcal: Double,
    val proteinG: Double,
)

@Serializable
data class WeekPlan(
    /** ISO date of the first day. */
    val weekStart: String,
    val targets: DailyTargets,
    val days: List<PlanDay>,
    /** Slots the planner had no suitable recipe for. */
    val emptySlots: List<MealSlot>,
)

/** One thing to buy for a week plan. */
@Serializable
data class ShoppingItem(
    /** Stable identifier of the item within the list, used to tick it off. */
    val key: String,
    val name: String,
    /** Total weight needed, when every use could be converted to grams. */
    val grams: Double?,
    /** Total number of pieces, when the recipes count this ingredient in pieces. */
    val pieces: Double?,
    /** Shop section, taken from the food group; "Other" when unknown. */
    val category: String,
    val recipes: List<String>,
    /** True when the pantry lists it as on hand: check before buying. */
    val onHand: Boolean,
    val checked: Boolean,
    /** Store offers for this ingredient during the plan's week. */
    val offers: List<Offer> = emptyList(),
)

/** Everything the week's meals need, without the staples that are always at home. */
@Serializable
data class ShoppingList(
    val weekStart: String,
    val items: List<ShoppingItem>,
    /** Names of staples the plan uses, as a reminder to check the cupboard. */
    val staples: List<String>,
    /** Seasoning and garnish the recipes use "to taste", without an amount. */
    val toTaste: List<String> = emptyList(),
)

@Serializable
data class ShoppingCheck(val key: String, val checked: Boolean)
