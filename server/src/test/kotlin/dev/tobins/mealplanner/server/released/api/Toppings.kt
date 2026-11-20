// Frozen from v0.1.10 (de4360a169911ad09794223687f4df8e42a3ebe4) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released.api

import kotlinx.serialization.Serializable

/**
 * An optional topping of a recipe (issue 226): an ingredient of the account's catalogue and the [grams] one
 * meal usually gets of it. The account's own, as its rating is, also on a cooking-site recipe, and a sync
 * never changes it. It is not in the recipe's nutrition, cost or shopping until a meal has it added.
 */
@Serializable
data class RecipeTopping(
    val ingredientId: Long,
    val name: String,
    val grams: Double,
    /** The weight of one piece, when the catalogue knows it: then the amount is shown and entered in pieces. */
    val gramsPerPiece: Double? = null,
)

/** One topping as `PUT /api/recipes/{id}/toppings` takes it. */
@Serializable
data class ToppingInput(val ingredientId: Long, val grams: Double)

/** Body of `PUT /api/recipes/{id}/toppings`: every topping in its order, replacing those before; none takes them all away. */
@Serializable
data class ToppingsInput(val toppings: List<ToppingInput>)

/**
 * Something to add to a meal of a recipe with one tap (`GET /api/recipes/{id}/add-ons`): one of the recipe's
 * toppings, or an ingredient added to its meals before, with the amount to add.
 */
@Serializable
data class AddOn(
    val ingredientId: Long,
    val name: String,
    /** The topping's usual amount, or what the latest meal of the recipe had of it. */
    val grams: Double,
    val gramsPerPiece: Double? = null,
    /** True for one of the recipe's toppings, false for one only added to its meals before. */
    val topping: Boolean = false,
    /** How many meals of the recipe had it added. */
    val times: Int = 0,
)
