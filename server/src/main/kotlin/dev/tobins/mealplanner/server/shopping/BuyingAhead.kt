package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.planner.BuyAhead
import dev.tobins.mealplanner.server.accounts.ProfileRepository
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.plan.WeekCosts
import dev.tobins.mealplanner.server.plan.WeekMeals
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.DAYS
import dev.tobins.mealplanner.server.recipes.RecipeCandidates
import java.time.LocalDate

/** The buy-ahead items, and whether their meals are a provisional pick rather than next week's plan. */
internal class BuyAheadAdvice(val items: List<ShoppingItem>, val provisional: Boolean)

/**
 * What to buy on this week's list for next week's meals ([dev.tobins.mealplanner.api.ShoppingList.buyAhead]): the
 * meals planned for next week, or a provisional choice of them when it has no plan yet. [ListAdvice] asks for it.
 */
class BuyingAhead(
    private val weekMeals: WeekMeals,
    private val costs: WeekCosts,
    private val recipes: RecipeCandidates,
    private val profiles: ProfileRepository,
    private val stock: StockRepository,
) {
    /**
     * What to buy now for next week's meals. When next week is planned already, those are its
     * meals as planned; otherwise a provisional choice among the dinners the planner could pick
     * then ([BuyAhead.provisional]), one batch each. Nothing of it is stored: next week is planned
     * as usual later, with what was ticked here on hand. The meals of a batch made this week are bought
     * with it ([WeekMeals.forShopping]): a next week with only those counts as not planned, and the
     * provisional choice is for the dinners they leave free (issue 263), none when they fill every one.
     */
    internal fun buyAhead(start: LocalDate, thisWeek: List<PlannedMeal>, advisor: ShoppingAdvisor): BuyAheadAdvice {
        val next = start.plusDays(DAYS.toLong())
        val nextWeek = weekMeals.forShopping(next)
        val limit = if (nextWeek.isEmpty()) minOf(BuyAhead.MAX_MEALS, freeDinners(next)) else BuyAhead.MAX_MEALS
        // Next week still has no plan of its own: the advice is empty, not about planned meals.
        if (limit == 0) return BuyAheadAdvice(emptyList(), provisional = true)
        val (meals, ratings) = if (nextWeek.isNotEmpty()) {
            planned(next, nextWeek) to emptyMap()
        } else {
            val targets = profiles.state().targets ?: return BuyAheadAdvice(emptyList(), false)
            val candidates = BuyAhead.provisional(
                recipes.planCandidates(dislikes = profiles.profile()?.dislikes.orEmpty()),
                targets,
                profiles.portions(),
                plannedThisWeek = thisWeek.mapTo(HashSet()) { it.recipeId },
            )
            candidates.map { PlannedRecipe(it.recipeId, it.title, it.servings.toDouble()) } to candidates.associate { it.recipeId to it.rating }
        }
        val items = advisor.buyAhead(
            meals,
            recipes.linesWithIngredients(meals.map { it.recipeId }),
            ratings,
            onHand = stock.onHandFor(next),
            onOfferNextWeek = costs.onOffer(next),
            limit = limit,
        )
        return BuyAheadAdvice(items, provisional = nextWeek.isEmpty())
    }

    /** The dinners of the week starting [start] that hold no meal yet: those the meals of a batch made the week before leave free. */
    private fun freeDinners(start: LocalDate): Int = DAYS - weekMeals.of(start).count { it.slot == MealSlot.DINNER }
}
