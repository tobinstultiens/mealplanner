package dev.tobins.mealplanner.api

import kotlinx.serialization.Serializable

/**
 * One food planned in a slot of the plan (issue 358): an ingredient or product of the account's catalogue in the [amount]
 * of the [unit] it was picked in ("2 bar"), kept so, so that the slot shows it that way and it is changed in it. A slot
 * holds foods alone ([PlannedMeal.foodsOnly]) or next to its recipe; [PlannedMeal.foods] lists them in the order they
 * were added.
 */
@Serializable
data class MealFood(
    /** The food's own id in its slot, for `PUT` and `DELETE /api/plans/{weekStart}/meals/{day}/{slot}/foods/{id}`. */
    val id: Long,
    val ingredientId: Long,
    val name: String,
    val amount: Double,
    /** `g`, `ml`, `piece` or the name of one of the ingredient's portions (`bar`). */
    val unit: String,
    /** What the amount weighs, a volume counted at its density or else as water; null when that is not known. */
    val grams: Double? = null,
    val kcal: Double = 0.0,
    val proteinG: Double = 0.0,
    val fibreG: Double = 0.0,
    /** False when it counts as nothing: the ingredient has no values, or its amount cannot be weighed. */
    val counted: Boolean = true,
)

/** A slot of the plan on one date ([date], ISO): where foods are planned, as a shopping row names them ([ShoppingItem.foodSlots]). */
@Serializable
data class PlannedSlot(val date: String, val slot: MealSlot)

/**
 * Body of `POST /api/plans/{weekStart}/meals/{day}/{slot}/foods` (a food added to the slot, which an empty slot becomes a
 * slot of foods by) and of `PUT .../foods/{id}` (a food replaced): the account's ingredient [ingredientId] in [amount] of
 * [unit], one of the units it has (`Ingredient.units`). The answer is the week's [WeekPlan].
 */
@Serializable
data class MealFoodInput(val ingredientId: Long, val amount: Double, val unit: String)
