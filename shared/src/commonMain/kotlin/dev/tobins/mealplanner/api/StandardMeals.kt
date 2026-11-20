package dev.tobins.mealplanner.api

import kotlinx.serialization.Serializable

// Standard meals (issue 265): recipes of mine I can always make from what is at home, offered in one tap where a
// meal falls away. All of these are types of routes of their own, which servers before them answer with a 404.

/** Body of `PUT /api/recipes/{id}/standard`: makes one of my meals a standard meal, or no longer one. */
@Serializable
data class StandardInput(val standard: Boolean)

/**
 * The answer of `PUT /api/recipes/{id}/standard`: the recipe as it is now, and what marking it did to the pantry.
 * Marking makes the ingredients its lines are linked to staples, as what is always at home: [addedStaples] names
 * those that were not yet, in the recipe's order. [unlinked] names its lines that mean no ingredient of the catalogue
 * yet, so could not be made one. Unmarking changes no staple, and both are empty then.
 */
@Serializable
data class StandardSwitch(
    val recipe: RecipeDetail,
    val addedStaples: List<String> = emptyList(),
    val unlinked: List<String> = emptyList(),
)

/**
 * One standard meal as one of its slots would have it in a week: at the portion the slot gets within the profile's
 * range, with its numbers at that portion. [missing] names its ingredients the pantry has not at home that week:
 * neither a staple nor on hand. It can be chosen all the same.
 */
@Serializable
data class StandardMeal(
    val recipeId: Long,
    val title: String,
    val slot: MealSlot,
    val portions: Double,
    val kcal: Double,
    val proteinG: Double,
    val fibreG: Double,
    val missing: List<String> = emptyList(),
)

/** A slot of a week whose meal no standard meal can take the place of, and why, in a sentence. */
@Serializable
data class ClosedSlot(val day: Int, val slot: MealSlot, val reason: String)

/**
 * `GET /api/plans/{weekStart}/standard-meals`: the account's standard meals for each of their slots in the week, by
 * title and then slot, and the slots whose meal stays ([closed]): there, none is offered. Any other slot can take one.
 */
@Serializable
data class StandardMeals(
    val weekStart: String,
    val meals: List<StandardMeal>,
    val closed: List<ClosedSlot> = emptyList(),
)

/**
 * Body of `PUT /api/plans/{weekStart}/meals/{day}/{slot}/standard`: puts the standard meal [recipeId] in the slot,
 * locked, in place of what is there, and with [eaten] marks it eaten at once.
 */
@Serializable
data class StandardChoice(val recipeId: Long, val eaten: Boolean = false)

/**
 * The answer of `PUT /api/plans/{weekStart}/meals/{day}/{slot}/standard`: the week, and the ingredients of the meal that
 * were not at home ([StandardMeal.missing]) and are staples again now, so the shopping list names them among the
 * staples to restock.
 */
@Serializable
data class StandardChosen(val plan: WeekPlan, val addedStaples: List<String> = emptyList())
