package dev.tobins.mealplanner.planner

import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealSlot.BREAKFAST
import dev.tobins.mealplanner.api.MealSlot.DINNER
import dev.tobins.mealplanner.api.MealSlot.LUNCH
import dev.tobins.mealplanner.api.MealSlot.SNACK
import dev.tobins.mealplanner.api.PlannedMeal
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WeekPlannerTest {
    private val targets = DailyTargets(kcal = 2000, proteinG = 150)

    private fun candidate(id: Long, slot: MealSlot, kcal: Double, protein: Double, servings: Int = 4, tag: String = "T$id") =
        PlanCandidate(id, "Recipe $id", setOf(slot), servings, kcal, protein, listOf(tag))

    /** Enough of everything: 2 breakfasts, 2 snacks, 10 dinners that leave leftovers. */
    private val pantryOfRecipes = listOf(
        candidate(1, BREAKFAST, 430.0, 35.0, servings = 1),
        candidate(2, BREAKFAST, 300.0, 10.0, servings = 1),
        candidate(3, SNACK, 200.0, 18.0, servings = 1),
        candidate(4, SNACK, 500.0, 5.0, servings = 1),
    ) + (10L..19L).map { candidate(it, DINNER, kcal = 600.0 + (it - 10) * 30, protein = 45.0 + (it - 10)) }

    private fun List<PlannedMeal>.at(day: Int, slot: MealSlot) = single { it.day == day && it.slot == slot }

    @Test
    fun everyDayLandsNearTheCalorieTarget() {
        val plan = WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes))

        assertEquals(7 * 4, plan.size)
        for (day in 0..6) {
            val kcal = plan.filter { it.day == day }.sumOf { it.kcal }
            assertTrue(abs(kcal - 2000) <= 2000 * 0.12, "day $day has $kcal kcal")
        }
    }

    @Test
    fun breakfastAndSnackAreRoutinesAndPreferProtein() {
        val plan = WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes))

        assertEquals(setOf(1L), plan.filter { it.slot == BREAKFAST }.map { it.recipeId }.toSet())
        assertEquals(setOf(3L), plan.filter { it.slot == SNACK }.map { it.recipeId }.toSet())
    }

    @Test
    fun dinnersDifferAndTheirLeftoversBecomeTheNextLunch() {
        val plan = WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes))

        val dinners = plan.filter { it.slot == DINNER }
        assertEquals(7, dinners.map { it.recipeId }.toSet().size, "seven different dinners")
        for (day in 1..6) {
            val lunch = plan.at(day, LUNCH)
            assertEquals(plan.at(day - 1, DINNER).recipeId, lunch.recipeId)
            assertTrue(lunch.leftover)
        }
        val firstLunch = plan.at(0, LUNCH)
        assertFalse(firstLunch.leftover, "nothing was cooked before the first day, so a dinner recipe is cooked for lunch")
        assertFalse(firstLunch.recipeId == plan.at(0, DINNER).recipeId)
    }

    @Test
    fun portionsAreScaledToTheSlotBudgetWithinOneBatch() {
        // Dinner budget is 760 kcal: 1.5 servings of a 500 kcal recipe is closest.
        assertEquals(1.5, WeekPlanner.portionsFor(candidate(1, DINNER, 500.0, 40.0), budget = 760.0))
        // A single-serving recipe is simply made larger, up to double.
        assertEquals(1.5, WeekPlanner.portionsFor(candidate(1, DINNER, 500.0, 40.0, servings = 1), budget = 760.0))
        assertEquals(2.0, WeekPlanner.portionsFor(candidate(1, DINNER, 150.0, 40.0, servings = 1), budget = 760.0))
        // Leftovers are limited to what remains.
        assertEquals(1.0, WeekPlanner.portionsFor(candidate(1, DINNER, 500.0, 40.0), budget = 760.0, available = 1.0))
        assertEquals(0.5, WeekPlanner.portionsFor(candidate(1, DINNER, 2000.0, 40.0), budget = 760.0))
    }

    @Test
    fun aSingleServingDinnerLeavesNoLeftovers() {
        val singles = (1L..7L).map { candidate(it, DINNER, 700.0, 50.0, servings = 1) }
        val plan = WeekPlanner.plan(PlanRequest(targets, singles))

        assertEquals(14, plan.size, "every lunch is cooked fresh")
        assertTrue(plan.none { it.leftover })
    }

    @Test
    fun lockedMealsAreKeptAndStillProduceLeftovers() {
        val lockedDinner = PlannedMeal(day = 2, slot = DINNER, recipeId = 17, title = "Recipe 17", portions = 1.0, kcal = 810.0, proteinG = 52.0)
        val lockedLunch = PlannedMeal(day = 1, slot = LUNCH, recipeId = 99, title = "Lunch out", portions = 1.0, kcal = 650.0, proteinG = 30.0)
        val plan = WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes, locked = listOf(lockedDinner, lockedLunch)))

        assertEquals(lockedDinner.copy(locked = true), plan.at(2, DINNER))
        assertEquals(lockedLunch.copy(locked = true), plan.at(1, LUNCH))
        assertEquals(17L, plan.at(3, LUNCH).recipeId)
        assertEquals(1, plan.count { it.recipeId == 17L && it.slot == DINNER }, "the locked dinner is not planned a second time")
    }

    @Test
    fun excludedAndRecentRecipesAreAvoided() {
        val plan = WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes, excluded = setOf(10L), recent = setOf(11L)))
        val dinners = plan.filter { it.slot == DINNER }.map { it.recipeId }

        assertFalse(10L in dinners)
        assertFalse(11L in dinners, "enough other recipes, so the recently eaten one is left out")
    }

    @Test
    fun onHandIngredientsPullARecipeForward() {
        val boosted = pantryOfRecipes.map { if (it.recipeId == 14L) it.copy(onHandCount = 3) else it }
        val firstDay = WeekPlanner.plan(PlanRequest(targets, boosted)).filter { it.day == 0 }
        assertTrue(firstDay.any { it.recipeId == 14L }, "the recipe that uses up what is at home is cooked on the first day")
    }

    @Test
    fun theSameSeedGivesTheSamePlanAndNoCandidatesGiveAnEmptyOne() {
        assertEquals(WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes, seed = 7)), WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes, seed = 7)))
        assertEquals(emptyList(), WeekPlanner.plan(PlanRequest(targets, emptyList())))
    }

    @Test
    fun leftoversNeverExceedWhatTheBatchLeaves() {
        // Three servings of 390 kcal: dinner takes 2, so lunch gets the one that remains, not 1.5.
        val small = (1L..7L).map { candidate(it, DINNER, 390.0, 40.0, servings = 3) }
        val plan = WeekPlanner.plan(PlanRequest(targets, small))

        assertEquals(2.0, plan.at(0, DINNER).portions)
        val leftovers = plan.filter { it.leftover }
        assertEquals(6, leftovers.size)
        assertTrue(leftovers.all { it.portions == 1.0 })
    }

    @Test
    fun sideDishesAreNotPlannedAsMeals() {
        val side = candidate(1, DINNER, 28.0, 1.0)
        val real = candidate(2, DINNER, 650.0, 50.0)
        val plan = WeekPlanner.plan(PlanRequest(targets, listOf(side, real)))

        assertTrue(plan.none { it.recipeId == 1L }, "a 28 kcal recipe cannot fill a dinner; repeating the real meal is better")
        assertEquals(emptyList(), WeekPlanner.plan(PlanRequest(targets, listOf(side))))
    }

    @Test
    fun ingredientsOnOfferGiveASmallerPullThanOnHandOnes() {
        val onOffer = pantryOfRecipes.map { if (it.recipeId == 15L) it.copy(offerCount = 2) else it }
        val firstDay = WeekPlanner.plan(PlanRequest(targets, onOffer)).filter { it.day == 0 }
        assertTrue(firstDay.any { it.recipeId == 15L })

        val both = pantryOfRecipes.map {
            when (it.recipeId) {
                15L -> it.copy(offerCount = 3)
                16L -> it.copy(onHandCount = 3)
                else -> it
            }
        }
        // The first meal the planner fills is the first day's lunch.
        assertEquals(16L, WeekPlanner.plan(PlanRequest(targets, both)).at(0, LUNCH).recipeId, "using up what is at home comes first")
    }

    @Test
    fun pinnedRoutinesReplaceThePlannersChoice() {
        val plan = WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes, routines = mapOf(BREAKFAST to 2L)))
        assertEquals(setOf(2L), plan.filter { it.slot == BREAKFAST }.map { it.recipeId }.toSet())
        assertEquals(setOf(3L), plan.filter { it.slot == SNACK }.map { it.recipeId }.toSet(), "the snack is still chosen")

        val gone = WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes, routines = mapOf(BREAKFAST to 999L)))
        assertEquals(setOf(1L), gone.filter { it.slot == BREAKFAST }.map { it.recipeId }.toSet(), "an unplannable pin is ignored")
    }

    @Test
    fun ratingsPullARecipeForwardAndPushOneBack() {
        val rated = pantryOfRecipes.map {
            when (it.recipeId) {
                17L -> it.copy(rating = 5)
                10L -> it.copy(rating = 1)
                else -> it
            }
        }
        val plan = WeekPlanner.plan(PlanRequest(targets, rated))
        assertEquals(17L, plan.at(0, LUNCH).recipeId, "the five-star recipe is the first one planned")
        assertFalse(plan.any { it.recipeId == 10L }, "the one-star recipe is not needed this week")
    }

    @Test
    fun lunchAndDinnerOfOneDayDiffer() {
        val plan = WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes))
        for (day in 0..6) assertNotEquals(plan.at(day, LUNCH).recipeId, plan.at(day, DINNER).recipeId)
    }
}
