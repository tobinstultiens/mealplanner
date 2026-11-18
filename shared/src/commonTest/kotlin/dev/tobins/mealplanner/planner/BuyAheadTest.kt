package dev.tobins.mealplanner.planner

import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.MealSlot
import kotlin.test.Test
import kotlin.test.assertEquals

class BuyAheadTest {
    private val targets = DailyTargets(kcal = 2000, proteinG = 150)

    private fun dinner(id: Long, kcal: Double = 700.0, slot: MealSlot = MealSlot.DINNER) =
        PlanCandidate(id, "Recipe $id", setOf(slot), servings = 4, kcal = kcal, proteinG = 40.0)

    @Test
    fun nextWeeksCandidatesAreDinnersNotPlannedThisWeek() {
        val candidates = listOf(
            dinner(1),
            dinner(2),
            // A side dish: even two servings are far from a dinner.
            dinner(3, kcal = 90.0),
            dinner(4, slot = MealSlot.BREAKFAST),
        )
        assertEquals(listOf(2L), BuyAhead.provisional(candidates, targets, PortionRange(), plannedThisWeek = setOf(1L)).map { it.recipeId })
    }

    @Test
    fun aDinnerThatNeedsMoreThanTheLargestPortionIsNotBoughtAheadFor() {
        // The dinner budget is 760 kcal: 200 kcal reaches half of it at two servings, not at 1.5.
        val light = listOf(dinner(1, kcal = 200.0))
        assertEquals(emptyList(), BuyAhead.provisional(light, targets, PortionRange(0.75, 1.5), plannedThisWeek = emptySet()))
        assertEquals(listOf(1L), BuyAhead.provisional(light, targets, PortionRange(0.5, 2.0), plannedThisWeek = emptySet()).map { it.recipeId })
    }

    @Test
    fun theMealsThatSaveMostAreChosenAtMostThree() {
        val options = listOf(
            AheadOption(1, "Small saving", saving = 0.49),
            AheadOption(2, "Two euros", saving = 2.0),
            AheadOption(3, "Five euros", saving = 5.0),
            AheadOption(4, "Two euros, rated well", saving = 2.0, rating = 5),
            AheadOption(5, "One euro", saving = 1.0),
        )
        assertEquals(listOf(3L, 4L, 2L), BuyAhead.choose(options).map { it.recipeId })
    }

    @Test
    fun theChoiceIsTheSameEveryTime() {
        val options = (10L downTo 1L).map { AheadOption(it, "Recipe $it", saving = 1.0) }
        assertEquals(listOf(1L, 2L, 3L), BuyAhead.choose(options).map { it.recipeId })
        assertEquals(BuyAhead.choose(options), BuyAhead.choose(options.shuffled()))
        assertEquals(emptyList(), BuyAhead.choose(listOf(AheadOption(1, "Nothing on offer", saving = 0.0))))
    }
}
