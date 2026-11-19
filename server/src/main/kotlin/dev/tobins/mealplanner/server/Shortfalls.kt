package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.shopping.SCRAP_GRAMS
import kotlin.math.max
import kotlin.math.min

/**
 * One meal of a week as [shortfalls] walks it: the grams of each ingredient its recipe gives it ([gramsUsed]),
 * those it eats as adjusted ([MealAdjustments.gramsEaten]), and whether it is still to cook
 * ([PlanRepository.toCook]), as only such a meal can still come up short.
 */
class MealDemand(val meal: PlannedMeal, val recipe: Map<Long, Double>, val eaten: Map<Long, Double>, val toCook: Boolean)

/** [meal], still to cook, is [grams] short of ingredient [ingredientId], because adjusted meals ate what was at home for it. */
data class Shortfall(val meal: PlannedMeal, val ingredientId: Long, val grams: Double)

/** [grams] of an ingredient beyond its recipe that the meal of [demand] took of what was at home. */
class Taken(val demand: MealDemand, val grams: Double)

/**
 * What the adjustments of a week leave its meals still to cook short of ([short]), and, for each ingredient
 * that leaves one short, what the adjusted meals took of what was at home beyond their recipes ([taken]):
 * the part of their additions that the shopping list counts as their need.
 */
class Shortfalls(val short: List<Shortfall>, val taken: Map<Long, List<Taken>>) {
    companion object {
        val NONE = Shortfalls(emptyList(), emptyMap())
    }
}

/** An amount within this of the recipe's is the recipe's, as [MealAdjustments] stores no such change. */
private const val SAME_GRAMS = 0.05

/**
 * What adjustments leave the meals still to cook short of (issue 226). An adjustment is what was in the
 * fridge or the pantry, so on its own it buys nothing; but where it took what was at home for a later meal,
 * that meal comes up short, and the shopping list asks for it again.
 *
 * [meals] are those cooked or still to cook, in the order they are cooked; [atHome] is what was at home for
 * them by ingredient (null when its amount is not known, which says nothing about being short; absent when
 * none was). For each ingredient that some meal has more of than its recipe, the walk goes through the meals
 * twice at once: as adjusted, each taking what it eats while something is left, and as planned, each taking
 * its recipe's. A meal still to cook needs its recipe's amount, or less when it was itself adjusted to less
 * (more than its recipe is from the fridge too); what it is short as adjusted beyond what it would be short as
 * planned (at least [SCRAP_GRAMS]) is what adjustments left it short. So nothing is short while there is
 * enough left, and a shortage the list buys for already is not counted twice.
 */
fun shortfalls(meals: List<MealDemand>, atHome: Map<Long, Double?>): Shortfalls {
    val increased = meals.flatMapTo(LinkedHashSet()) { meal -> meal.eaten.filter { (id, grams) -> grams > (meal.recipe[id] ?: 0.0) + SAME_GRAMS }.keys }
    val short = ArrayList<Shortfall>()
    val taken = LinkedHashMap<Long, List<Taken>>()
    for (id in increased) {
        if (id in atHome && atHome[id] == null) continue
        val home = atHome[id] ?: 0.0
        var left = home
        var leftAsPlanned = home
        val ofIngredient = ArrayList<Shortfall>()
        val beyondRecipes = ArrayList<Taken>()
        for (meal in meals) {
            val recipe = meal.recipe[id] ?: 0.0
            val eaten = meal.eaten[id] ?: 0.0
            if (meal.toCook) {
                val more = max(0.0, min(recipe, eaten) - left) - max(0.0, recipe - leftAsPlanned)
                if (more >= SCRAP_GRAMS) ofIngredient += Shortfall(meal.meal, id, more)
            }
            val used = min(eaten, max(left, 0.0))
            if (used > recipe + SAME_GRAMS) beyondRecipes += Taken(meal, used - recipe)
            left -= used
            leftAsPlanned -= min(recipe, max(leftAsPlanned, 0.0))
        }
        if (ofIngredient.isEmpty()) continue
        short += ofIngredient
        taken[id] = beyondRecipes
    }
    return Shortfalls(short, taken)
}
