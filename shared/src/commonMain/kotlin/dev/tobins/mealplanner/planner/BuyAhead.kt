package dev.tobins.mealplanner.planner

import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.MealSlot

/** A meal next week could have, with what buying its keepable ingredients on this week's offers saves. */
data class AheadOption(
    val recipeId: Long,
    val title: String,
    /** Euros saved against the regular price, over the ingredients that qualify for buying ahead. */
    val saving: Double,
    val rating: Int? = null,
)

/**
 * Which meals of next week to buy ahead for, with offers that end this week.
 *
 * Next week is not planned yet, so this is a provisional choice: the dinners the planner could
 * pick then, ranked by what buying ahead saves. Only what is bought carries over (as on hand),
 * and next week's plan is made as usual, with next week's own offers.
 *
 * Unlike [WeekPlanner] there is no random jitter: the list is computed again on every tick, and
 * must not change under the user's fingers.
 */
object BuyAhead {
    /** Buying ahead for more meals than this fills the freezer for a plan that is not made yet. */
    const val MAX_MEALS = 3

    /** Euros a meal must save to be worth buying ahead for; below it, buying next week costs little more. */
    const val MIN_SAVING = 0.5

    /**
     * The dinners the planner could pick next week, for someone with [targets]: meals by their
     * calories, and not one of [plannedThisWeek], which the planner avoids next week as recently
     * eaten.
     */
    fun provisional(candidates: List<PlanCandidate>, targets: DailyTargets, plannedThisWeek: Set<Long>): List<PlanCandidate> =
        candidates.filter { it.recipeId !in plannedThisWeek && WeekPlanner.fitsAsMeal(it, MealSlot.DINNER, targets) }

    /**
     * The meals to buy ahead for, best first: those saving at least [MIN_SAVING], most saved first,
     * then the better rated, then by id so the choice is the same every time; at most [MAX_MEALS].
     */
    fun choose(options: List<AheadOption>): List<AheadOption> =
        options.filter { it.saving >= MIN_SAVING }
            .sortedWith(compareByDescending<AheadOption> { it.saving }.thenByDescending { it.rating ?: 3 }.thenBy { it.recipeId })
            .take(MAX_MEALS)
}
