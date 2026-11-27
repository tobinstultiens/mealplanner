package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The walk behind the later-meal warning (issue 226), on its own: an adjustment takes what was at home only as
 * far as it goes, a later meal is short only of what adjustments took from it, and order counts.
 */
class ShortfallsTest {
    private val spinach = 8L
    private val egg = 9L

    private fun meal(day: Int, title: String) = PlannedMeal(day, MealSlot.DINNER, day.toLong(), title, 1.0, 600.0, 40.0)

    /** A meal whose recipe has [recipe] g of spinach and that eats [eaten] g of it. */
    private fun demand(day: Int, recipe: Double, eaten: Double = recipe, toCook: Boolean = true) =
        MealDemand(meal(day, "Day $day"), mapOf(spinach to recipe).filterValues { it > 0 }, mapOf(spinach to eaten).filterValues { it > 0 }, toCook)

    private fun Shortfalls.shorts() = short.map { it.meal.day to it.grams }

    @Test
    fun anAdditionThatTakesALaterMealsShareLeavesItShortByThatMuch() {
        val week = listOf(demand(0, 0.0, 100.0), demand(3, 200.0))
        val result = shortfalls(week, mapOf(spinach to 200.0))
        assertEquals(listOf(3 to 100.0), result.shorts())
        assertEquals(listOf(0 to 100.0), result.taken.getValue(spinach).map { it.demand.meal.day to it.grams })
    }

    @Test
    fun enoughAtHomeLeavesNothingShortAndCountsNothing() {
        val result = shortfalls(listOf(demand(0, 0.0, 100.0), demand(3, 200.0)), mapOf(spinach to 300.0))
        assertEquals(emptyList(), result.short)
        assertEquals(emptyMap(), result.taken, "no row counts an adjustment when nothing is short")
    }

    @Test
    fun onlyWhatWasAtHomeIsTakenTheRestCameFromTheFridge() {
        // 250 g at home: the bowl takes 100 g of it, 50 g of which the curry needed.
        assertEquals(listOf(3 to 50.0), shortfalls(listOf(demand(0, 0.0, 100.0), demand(3, 200.0)), mapOf(spinach to 250.0)).shorts())
        // Nothing at home: the curry was short of all of it anyway, and the list buys that already.
        assertEquals(emptyList(), shortfalls(listOf(demand(0, 0.0, 100.0), demand(3, 200.0)), emptyMap()).short)
        // Half of it at home: the curry is short 100 g as planned, and the bowl's 100 g makes it 200 g.
        assertEquals(listOf(3 to 100.0), shortfalls(listOf(demand(0, 0.0, 100.0), demand(3, 200.0)), mapOf(spinach to 100.0)).shorts())
    }

    @Test
    fun aLaterMealAdjustedToLessNeedsLess() {
        assertEquals(emptyList(), shortfalls(listOf(demand(0, 0.0, 100.0), demand(3, 200.0, 100.0)), mapOf(spinach to 200.0)).short)
    }

    @Test
    fun aMealCookedBeforeTheAdjustedOneTookItsShareFirst() {
        // The curry on Thursday, then a bowl on Friday with spinach that had to come from the fridge.
        assertEquals(emptyList(), shortfalls(listOf(demand(3, 200.0), demand(4, 0.0, 100.0)), mapOf(spinach to 200.0)).short)
    }

    @Test
    fun anEatenMealIsNeverShortButTakesItsShare() {
        val week = listOf(demand(0, 0.0, 100.0, toCook = false), demand(1, 150.0, toCook = false), demand(3, 200.0))
        assertEquals(listOf(3 to 100.0), shortfalls(week, mapOf(spinach to 350.0)).shorts())
    }

    @Test
    fun anAmountNotKnownAtHomeClaimsNothing() {
        assertEquals(emptyList(), shortfalls(listOf(demand(0, 0.0, 100.0), demand(3, 200.0)), mapOf(spinach to null)).short)
    }

    @Test
    fun aRecipeLineIncreasedCountsOnlyWhatIsAboveTheRecipe() {
        // The bowl's recipe has 100 g and it ate 150 g. 280 g at home is 20 g short of both recipes, which the
        // list buys already; the bowl's extra 50 g leaves the curry 50 g shorter still.
        val week = listOf(demand(0, 100.0, 150.0), demand(3, 200.0))
        val result = shortfalls(week, mapOf(spinach to 280.0))
        assertEquals(listOf(3 to 50.0), result.shorts())
        assertEquals(listOf(50.0), result.taken.getValue(spinach).map { it.grams })
    }

    @Test
    fun anIngredientNoMealHasMoreOfIsNotLookedAt() {
        val week = listOf(
            MealDemand(meal(0, "Bowl"), mapOf(spinach to 100.0), mapOf(spinach to 100.0, egg to 60.0), toCook = true),
            demand(3, 200.0),
        )
        // The egg is short nowhere, as no later meal needs one; the spinach is as planned.
        assertEquals(emptyList(), shortfalls(week, mapOf(spinach to 100.0)).short)
    }
}
