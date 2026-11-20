package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.StandardChoice
import dev.tobins.mealplanner.api.StandardChosen
import dev.tobins.mealplanner.api.StandardMeal
import dev.tobins.mealplanner.api.StandardMeals
import dev.tobins.mealplanner.planner.WeekPlanner
import dev.tobins.mealplanner.server.db.Database
import java.time.LocalDate

/**
 * One account's standard meals (issue 265): the meals of mine it marked as always made from what is at home
 * ([RecipeRepository.setStandard]), offered in one tap where a meal falls away, and put in a slot by hand. The planner
 * knows nothing of them: it picks a standard meal in the rotation as any other recipe, and never one for being one.
 * See README, "Standard meals".
 */
class StandardMealRepository(
    private val db: Database,
    private val recipes: RecipeRepository,
    private val ingredients: IngredientRepository,
    private val stock: StockRepository,
    private val plans: PlanRepository,
    private val profiles: ProfileRepository,
) {
    /**
     * The standard meals for each of the slots they are set to be planned as ([StandardMeal]), each at the portion
     * that slot gets within the profile's range as a meal chosen by hand would, with what of it is not at home in the
     * week starting [weekStart] ([missing]); and the slots of the week no standard meal can take ([PlanRepository.whyNoStandardMeal]).
     */
    fun week(weekStart: String): StandardMeals {
        val start = parseWeekStart(weekStart)
        val targets = plans.targets()
        val range = profiles.portions()
        val atHome = stock.onHandFor(start)
        val meals = recipes.standardMeals().flatMap { candidate ->
            val missing = missing(candidate.recipeId, atHome).map { it.name }
            candidate.slots.sortedBy { it.ordinal }.map { slot ->
                val portions = WeekPlanner.portionsFor(candidate, slot, targets, range)
                StandardMeal(
                    candidate.recipeId, candidate.title, slot, portions,
                    candidate.kcal * portions, candidate.proteinG * portions, candidate.fibreG * portions, missing,
                )
            }
        }
        return StandardMeals(start.toString(), meals, plans.closedToStandardMeals(start))
    }

    /**
     * Puts the standard meal [choice] in [day]'s [slot] of the week starting [weekStart] ([PlanRepository.putStandard]),
     * and makes the ingredients of it the pantry had not at home staples again ([missing]): a standard meal's ingredients
     * are staples, and one that ran out is restocked as staples are, named on the shopping list rather than bought as
     * a row of its own. All in one transaction, so a refusal changes nothing. A recipe that is not one of my standard
     * meals, or not set to be planned as [slot], is a [ConflictException]; another account's is not there (404).
     */
    fun choose(weekStart: String, day: Int, slot: MealSlot, choice: StandardChoice): StandardChosen {
        val start = parseWeekStart(weekStart)
        return db.writing {
            val row = recipes.requireMine(choice.recipeId)
            val candidate = recipes.standardMeals().firstOrNull { it.recipeId == choice.recipeId }
                ?: throw ConflictException("\"${row.title}\" is not one of your standard meals")
            if (slot !in candidate.slots) throw ConflictException("\"${row.title}\" is not set to be planned as ${slot.name.lowercase()}")
            val missing = missing(candidate.recipeId, stock.onHandFor(start))
            val plan = plans.putStandard(weekStart, day, slot, candidate, choice.eaten)
            for (ingredient in missing) ingredients.setStaple(ingredient.id, true)
            StandardChosen(plan, missing.map { it.name })
        }
    }

    /**
     * The ingredients of standard meal [recipeId] that the pantry has not at home ([RecipeRepository.standardIngredients]):
     * neither a staple, which is always there, nor on hand for the week ([atHome], [StockRepository.onHandFor]). Marking
     * the meal made them all staples; one is missing when the account took it off the staples since, or the recipe has
     * gained it.
     */
    private fun missing(recipeId: Long, atHome: Set<Long>): List<Ingredient> =
        recipes.standardIngredients(recipeId).first.filter { !it.isStaple && it.id !in atHome }
}
