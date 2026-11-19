package dev.tobins.mealplanner.api

import kotlinx.serialization.Serializable

/**
 * One ingredient of a meal that differs from its recipe (issue 226): [grams] of it were eaten in this
 * meal. For an ingredient of the recipe it is the new amount, 0 when it was left out; for any other
 * ingredient of the account's catalogue, what was added.
 */
@Serializable
data class MealLineChange(val ingredientId: Long, val grams: Double)

/**
 * Body of `PUT /api/plans/{weekStart}/meals/{day}/{slot}/lines`: every change of the meal, which replaces
 * those it had. An empty list puts the meal back to its recipe. The answer is the meal's [MealLines].
 */
@Serializable
data class MealAdjustment(val lines: List<MealLineChange>)

/** One ingredient of a meal as it was eaten, in [MealLines]. */
@Serializable
data class MealLine(
    /** The catalogue's ingredient; null for a recipe line linked to none, which can't be changed and counts as nothing. */
    val ingredientId: Long?,
    val name: String,
    /** Grams eaten in this meal; null when the recipe's amount can't be weighed ("1 bunch" without a weight per piece). */
    val grams: Double?,
    /** What the recipe gives of it for the meal's portions, null for an added ingredient or one that can't be weighed. */
    val recipeGrams: Double? = null,
    /** True for an ingredient of the recipe, false for one added to this meal. */
    val fromRecipe: Boolean = true,
    /** True when this meal's amount differs from the recipe's: changed, left out (0 g) or added. */
    val changed: Boolean = false,
    /** The weight of one piece, when the catalogue knows it: then the amount is shown and entered in pieces. */
    val gramsPerPiece: Double? = null,
)

/**
 * A planned meal with what was in it (`GET` and `PUT /api/plans/{weekStart}/meals/{day}/{slot}/lines`):
 * the recipe's ingredients at the meal's portions, with this meal's changes, and the numbers they come to.
 */
@Serializable
data class MealLines(
    val weekStart: String,
    val day: Int,
    val slot: MealSlot,
    val recipeId: Long,
    val title: String,
    val portions: Double,
    /** The recipe's ingredients in its order, then those added. */
    val lines: List<MealLine>,
    val kcal: Double,
    val proteinG: Double,
    val fibreG: Double,
    /** False when some ingredient with an amount has no numbers (no NEVO food, no weight per piece): see [uncounted]. */
    val complete: Boolean = true,
    /** The names of the ingredients that count as nothing. */
    val uncounted: List<String> = emptyList(),
    /** True when some line differs from the recipe; then [PlannedMeal.adjusted] is too. */
    val adjusted: Boolean = false,
    /**
     * The meals still to cook after this one in its week that what is at home no longer covers, of an
     * ingredient this meal has more of than its recipe: the warning to show. The shopping list asks for
     * that amount again. Empty when enough is left, and from a server from before it.
     */
    val shortfalls: List<LaterShortfall> = emptyList(),
)

/**
 * A meal still to cook that is [grams] short of an ingredient, because adjusted meals ate what was at home
 * for it (the stock, and what was ticked as bought on the week's shopping list).
 */
@Serializable
data class LaterShortfall(
    val day: Int,
    val slot: MealSlot,
    val title: String,
    val ingredientId: Long,
    val name: String,
    val grams: Double,
)
