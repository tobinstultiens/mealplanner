package dev.tobins.mealplanner.planner

import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealSlot.BREAKFAST
import dev.tobins.mealplanner.api.MealSlot.DINNER
import dev.tobins.mealplanner.api.MealSlot.LUNCH
import dev.tobins.mealplanner.api.MealSlot.SNACK
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PrepMeal
import dev.tobins.mealplanner.api.PlanPriority
import dev.tobins.mealplanner.api.PlannedMeal
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

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
    fun portionsAreScaledToTheSlotBudgetWithinTheRange() {
        val wide = PortionRange(0.5, 2.0)
        // Dinner budget is 760 kcal: 1.5 servings of a 500 kcal recipe is closest.
        assertEquals(1.5, WeekPlanner.portionsFor(candidate(1, DINNER, 500.0, 40.0), DINNER, targets, wide))
        assertEquals(1.5, WeekPlanner.portionsFor(candidate(1, DINNER, 500.0, 40.0, servings = 1), DINNER, targets, wide))
        assertEquals(2.0, WeekPlanner.portionsFor(candidate(1, DINNER, 150.0, 40.0, servings = 1), DINNER, targets, wide))
        // Leftovers are limited to what remains.
        assertEquals(1.0, WeekPlanner.portionsFor(candidate(1, DINNER, 500.0, 40.0), DINNER, targets, wide, available = 1.0))
        assertEquals(0.5, WeekPlanner.portionsFor(candidate(1, DINNER, 2000.0, 40.0), DINNER, targets, wide))

        // The profile's defaults: 0.75 to 1.5 servings for lunch and dinner.
        val defaults = PortionRange()
        assertEquals(1.5, WeekPlanner.portionsFor(candidate(1, DINNER, 150.0, 40.0, servings = 1), DINNER, targets, defaults))
        assertEquals(0.75, WeekPlanner.portionsFor(candidate(1, DINNER, 2000.0, 40.0), DINNER, targets, defaults))
        assertEquals(1.5, WeekPlanner.portionsFor(candidate(1, LUNCH, 300.0, 40.0), LUNCH, targets, defaults))
        // Breakfast and snack still go up to two servings: a single-serving bowl is simply made larger.
        assertEquals(2.0, WeekPlanner.portionsFor(candidate(1, BREAKFAST, 220.0, 20.0, servings = 1), BREAKFAST, targets, defaults))
        assertEquals(2.0, WeekPlanner.portionsFor(candidate(1, SNACK, 100.0, 5.0, servings = 1), SNACK, targets, defaults))
        // A profile that allows more than two lets them go as far as the rest.
        assertEquals(2.5, WeekPlanner.portionsFor(candidate(1, BREAKFAST, 176.0, 20.0, servings = 1), BREAKFAST, targets, PortionRange(1.0, 2.5)))
        // What a dinner left may be less than the smallest portion; that is all the lunch gets.
        assertEquals(0.5, WeekPlanner.portionsFor(candidate(1, DINNER, 500.0, 40.0), LUNCH, targets, defaults, available = 0.5))
        assertEquals(1.5, WeekPlanner.portionsFor(candidate(1, DINNER, 300.0, 40.0), LUNCH, targets, defaults, available = 2.5))
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
    fun aPreppedBatchIsPlannedAroundLeavesNoLeftoverLunchAndCountsOnce() {
        // Recipe 17 made on Tuesday's dinner and eaten Wednesday and Thursday at dinner and Friday at lunch.
        val prep = MealPrep(
            id = 1, madeOn = "2026-11-17", lastDay = "2026-11-21", vacuum = false,
            meals = listOf(1 to DINNER, 2 to DINNER, 3 to DINNER, 4 to LUNCH).map { (day, slot) -> PrepMeal("2026-11-${16 + day}", slot, 1.0, MealStatus.PLANNED) },
        )
        val prepped = listOf(1 to DINNER, 2 to DINNER, 3 to DINNER, 4 to LUNCH).mapIndexed { i, (day, slot) ->
            PlannedMeal(day, slot, 17, "Recipe 17", 1.0, 810.0, 52.0, leftover = i > 0, locked = true, prep = prep)
        }
        val plan = WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes, locked = prepped))

        assertEquals(prepped, prepped.map { plan.at(it.day, it.slot) }, "the prepped meals stay as they are")
        for (day in 2..3) assertNotEquals(17L, plan.at(day, LUNCH).recipeId, "no leftover lunch of the batch on day $day")
        assertEquals(4, plan.count { it.recipeId == 17L }, "the batch's recipe is planned nowhere else")
        assertEquals(7, plan.filter { it.slot == DINNER }.size)
    }

    /** Recipe 17 as the planner's favourite: rated 5, rich in protein and with its ingredients at home. */
    private val favourite17 = pantryOfRecipes.map { if (it.recipeId == 17L) it.copy(rating = 5, proteinG = 80.0, onHandCount = 3) else it }

    /** The locked meals of a batch of recipe 17 made on [madeOn] (ISO) and eaten in [meals] of the week of 16 November; [cooked] is the one that makes it, if in this week. */
    private fun batchOf17(madeOn: String, meals: List<Pair<Int, MealSlot>>, cooked: Pair<Int, MealSlot>?): List<PlannedMeal> {
        val prep = MealPrep(
            id = 1, madeOn = madeOn, lastDay = "2026-11-21", vacuum = false,
            meals = meals.map { (day, slot) -> PrepMeal("2026-11-${16 + day}", slot, 1.0, MealStatus.PLANNED) },
        )
        return meals.map { (day, slot) -> PlannedMeal(day, slot, 17, "Recipe 17", 1.0, 810.0, 80.0, leftover = (day to slot) != cooked, locked = true, prep = prep) }
    }

    @Test
    fun everyMealOfAPreppedBatchCountsAgainstPlanningItsRecipeAgain() {
        val alone = WeekPlanner.plan(PlanRequest(targets, favourite17))
        assertTrue(alone.any { it.recipeId == 17L && it.slot == DINNER }, "without a prep the favourite is a dinner")

        // Made on Tuesday's dinner and eaten Wednesday lunch and Thursday dinner: three uses, not one.
        val thisWeek = batchOf17("2026-11-17", listOf(1 to DINNER, 2 to LUNCH, 3 to DINNER), cooked = 1 to DINNER)
        val plan = WeekPlanner.plan(PlanRequest(targets, favourite17, locked = thisWeek))
        assertEquals(thisWeek, thisWeek.map { plan.at(it.day, it.slot) }, "the prepped meals stay as they are")
        assertEquals(3, plan.count { it.recipeId == 17L }, "the batch's recipe is not planned again, nor its leftovers: ${plan.filter { it.recipeId == 17L }}")

        // The tail of a batch made the Sunday before: none of its meals cooks, and each still counts.
        val tail = batchOf17("2026-11-15", listOf(0 to LUNCH, 0 to DINNER, 1 to DINNER), cooked = null)
        val after = WeekPlanner.plan(PlanRequest(targets, favourite17, locked = tail))
        assertEquals(3, after.count { it.recipeId == 17L }, "the recipe of last week's batch is not planned again: ${after.filter { it.recipeId == 17L }}")
        assertEquals(7, after.filter { it.slot == DINNER }.size, "every dinner is planned all the same")
    }

    @Test
    fun aSideOfAPreppedBatchCountsAgainstPlanningItsRecipeAsAMain() {
        val prep = MealPrep(
            id = 2, madeOn = "2026-11-16", lastDay = "2026-11-19", vacuum = false,
            meals = listOf(0, 3).map { PrepMeal("2026-11-${16 + it}", DINNER, 0.5, MealStatus.PLANNED, side = true) },
        )
        val withSides = listOf(0, 3).map { day ->
            val side = MealSide(17, "Recipe 17", 0.5, 405.0, 40.0, cookPortions = if (day == 0) 1.0 else 0.0, prep = prep)
            PlannedMeal(day, DINNER, 10L + day, "Recipe ${10 + day}", 1.0, 600.0, 45.0, locked = true, side = side)
        }
        val plan = WeekPlanner.plan(PlanRequest(targets, favourite17, locked = withSides))

        assertEquals(0, plan.count { it.recipeId == 17L }, "the side's recipe is not also planned as a main: ${plan.filter { it.recipeId == 17L }}")
        assertEquals(withSides, withSides.map { plan.at(it.day, it.slot) })
    }

    /**
     * The recipes of the emulator run of #270 (issue 263), as the server made them candidates: four that fill a dinner at
     * 2720 kcal a day, and a stir fry too light to. Lunches are cooked from them too, so every one is repeated in a week.
     */
    private val runOf270 = listOf(
        PlanCandidate(2, "Chicken curry", setOf(LUNCH, DINNER), 4, 483.125, 39.9875, fibreG = 2.0375),
        PlanCandidate(1, "Chili con carne", setOf(LUNCH, DINNER), 4, 434.5, 33.075, fibreG = 11.425),
        PlanCandidate(4, "Pasta bolognese", setOf(LUNCH, DINNER), 4, 510.75, 28.725, fibreG = 3.75),
        PlanCandidate(6, "Salmon with potatoes", setOf(LUNCH, DINNER), 2, 559.5, 38.9, fibreG = 8.5),
        PlanCandidate(5, "Veggie stir fry", setOf(LUNCH, DINNER), 4, 215.0, 6.7, fibreG = 4.8),
    )

    /** The chili of that run, made on Friday's dinner and eaten on Saturday's and on next Monday's. */
    private val chiliBatch: List<PlannedMeal> = MealPrep(
        id = 1, madeOn = "2026-11-20", lastDay = "2026-11-24", vacuum = false,
        meals = listOf("2026-11-20", "2026-11-21", "2026-11-23").map { PrepMeal(it, DINNER, 1.5, MealStatus.PLANNED) },
    ).let { prep -> listOf(4, 5).map { day -> PlannedMeal(day, DINNER, 1, "Chili con carne", 1.5, 651.75, 49.6, leftover = day == 5, locked = true, prep = prep) } }

    @Test
    fun aRecipePreppedThisWeekIsPlannedAgainOnlyWhenNoOtherRecipeFitsTheSlot() {
        val run = PlanRequest(DailyTargets(2720, 145, 38), runOf270, locked = chiliBatch, portions = PortionRange(0.75, 1.5))
        for (seed in 0L until 20L) {
            val plan = WeekPlanner.plan(run.copy(seed = seed))
            // The other three are repeated instead: a repeat can't be helped, but the chili fills its batch's meals already.
            assertEquals(chiliBatch, plan.filter { it.recipeId == 1L }, "seed $seed: ${plan.filter { it.recipeId == 1L }}")
            assertEquals(7, plan.count { it.slot == DINNER })
        }
        // With nothing else that fits a dinner, the chili is planned again rather than a dinner left empty.
        val onlyChili = WeekPlanner.plan(run.copy(candidates = runOf270.filter { it.recipeId in setOf(1L, 5L) }))
        assertEquals(7, onlyChili.count { it.slot == DINNER && it.recipeId == 1L })
    }

    @Test
    fun aLunchTakesADinnerRecipeBeforeTheOnlyLunchRecipeWhenThatWasPrepped() {
        // Recipe 20 is the only lunch recipe, prepped for Monday's and Tuesday's lunches; the dinners are single servings, so
        // they leave no leftover lunch and every other lunch is chosen.
        val lunchRecipe = candidate(20, LUNCH, 550.0, 40.0)
        val singleDinners = (10L..19L).map { candidate(it, DINNER, kcal = 600.0 + (it - 10) * 30, protein = 45.0 + (it - 10), servings = 1) }
        val prep = MealPrep(
            id = 4, madeOn = "2026-11-16", lastDay = "2026-11-19", vacuum = false,
            meals = listOf(0, 1).map { PrepMeal("2026-11-${16 + it}", LUNCH, 1.0, MealStatus.PLANNED) },
        )
        val batch = listOf(0, 1).map { day -> PlannedMeal(day, LUNCH, 20, "Recipe 20", 1.0, 550.0, 40.0, leftover = day > 0, locked = true, prep = prep) }

        for (seed in 0L until 10L) {
            val plan = WeekPlanner.plan(PlanRequest(targets, listOf(lunchRecipe) + singleDinners, locked = batch, seed = seed))
            assertEquals(batch, plan.filter { it.recipeId == 20L }, "seed $seed: the lunches after the batch are cooked from dinner recipes")
            assertEquals(7, plan.count { it.slot == LUNCH }, "seed $seed: every lunch is planned")
        }
        // With no dinner recipe either, the prepped recipe is planned again rather than a lunch left empty.
        val onlyIt = WeekPlanner.plan(PlanRequest(targets, listOf(lunchRecipe), locked = batch))
        assertEquals(7, onlyIt.count { it.slot == LUNCH && it.recipeId == 20L })
    }

    @Test
    fun aBatchEatenAsAMealAndAsASideCountsEachShareAndGetsNoSideFromThePlanner() {
        // Issue 269: recipe 17 made on Tuesday's dinner, eaten then as the meal and on Thursday as a side next to recipe 13.
        val prep = MealPrep(
            id = 3, madeOn = "2026-11-17", lastDay = "2026-11-20", vacuum = false,
            meals = listOf(PrepMeal("2026-11-17", DINNER, 1.0, MealStatus.PLANNED), PrepMeal("2026-11-19", DINNER, 0.5, MealStatus.PLANNED, side = true)),
        )
        val batch = listOf(
            PlannedMeal(1, DINNER, 17, "Recipe 17", 1.0, 810.0, 80.0, locked = true, prep = prep),
            PlannedMeal(3, DINNER, 13, "Recipe 13", 1.0, 600.0, 45.0, locked = true, side = MealSide(17, "Recipe 17", 0.5, 405.0, 40.0, cookPortions = 0.0, prep = prep)),
        )
        val plan = WeekPlanner.plan(PlanRequest(targets, favourite17, locked = batch))

        assertEquals(batch, batch.map { plan.at(it.day, it.slot) }, "the batch's meal and side stay as they are")
        assertEquals(1, plan.count { it.recipeId == 17L }, "the batch's recipe is not planned again as a main: ${plan.filter { it.recipeId == 17L }}")
        assertEquals(1, plan.count { it.side != null }, "the planner adds no side")
        assertEquals(7, plan.filter { it.slot == DINNER }.size, "every dinner is planned all the same")
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
        // Three servings of 390 kcal, up to two a meal: dinner takes 2, so lunch gets the one that remains, not 1.5.
        val small = (1L..7L).map { candidate(it, DINNER, 390.0, 40.0, servings = 3) }
        val plan = WeekPlanner.plan(PlanRequest(targets, small, portions = PortionRange(0.5, 2.0)))

        assertEquals(2.0, plan.at(0, DINNER).portions)
        val leftovers = plan.filter { it.leftover }
        assertEquals(6, leftovers.size)
        assertTrue(leftovers.all { it.portions == 1.0 })
    }

    @Test
    fun aLeftoverLunchMayBeSmallerThanTheSmallestPortion() {
        // Two servings of 520 kcal: dinner takes 1.5, and the half that remains is lunch rather than thrown away.
        val small = (1L..7L).map { candidate(it, DINNER, 520.0, 40.0, servings = 2) }
        val plan = WeekPlanner.plan(PlanRequest(targets, small))

        assertEquals(1.5, plan.at(0, DINNER).portions)
        val leftovers = plan.filter { it.leftover }
        assertEquals(6, leftovers.size)
        assertTrue(leftovers.all { it.portions == 0.5 }, "got ${leftovers.map { it.portions }}")
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
    fun anExcludedLeftoverLunchIsReplacedByAFreshlyCookedOne() {
        // A reroll of a leftover lunch: every other meal locked, its recipe excluded.
        val week = WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes))
        val old = week.at(3, LUNCH)
        assertTrue(old.leftover)
        val plan = WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes, locked = week - old, excluded = setOf(old.recipeId)))

        val lunch = plan.at(3, LUNCH)
        assertNotEquals(old.recipeId, lunch.recipeId)
        assertFalse(lunch.leftover, "with no lunch recipes, a dinner recipe is cooked for lunch")
        assertEquals((week - old).map { it.copy(locked = true) }, plan - lunch, "the rest of the week is untouched")
    }

    @Test
    fun anExcludedPinnedRoutineGivesWayOnTheRerolledDayOnly() {
        val week = WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes, routines = mapOf(BREAKFAST to 2L)))
        val old = week.at(4, BREAKFAST)
        assertEquals(2L, old.recipeId)
        val plan = WeekPlanner.plan(
            PlanRequest(targets, pantryOfRecipes, locked = week - old, excluded = setOf(2L), routines = mapOf(BREAKFAST to 2L)),
        )

        assertEquals(1L, plan.at(4, BREAKFAST).recipeId, "the other breakfast takes the rerolled day")
        assertEquals((0..6).map { if (it == 4) 1L else 2L }, plan.filter { it.slot == BREAKFAST }.map { it.recipeId })

        // Without another breakfast recipe the slot stays empty rather than repeating the excluded one.
        val onlyPin = pantryOfRecipes.filter { it.recipeId != 1L }
        val empty = WeekPlanner.plan(PlanRequest(targets, onlyPin, locked = week - old, excluded = setOf(2L), routines = mapOf(BREAKFAST to 2L)))
        assertTrue(empty.none { it.day == 4 && it.slot == BREAKFAST })
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

    @Test
    fun cheaperMealsWinWhenTheyFitAsWell() {
        // Ten equal dinners; offers make four of them a third of the price.
        val dinners = (20L..29L).map { id ->
            candidate(id, DINNER, kcal = 700.0, protein = 50.0).copy(costPerServing = if (id < 24) 1.0 else 3.0)
        }
        val plan = WeekPlanner.plan(PlanRequest(targets, pantryOfRecipes.take(4) + dinners, seed = 3))

        // The first lunch has no leftovers yet, so it is cooked from a dinner recipe too.
        val cooked = plan.filter { (it.slot == DINNER || it.slot == LUNCH) && !it.leftover }.map { it.recipeId }.toSet()
        assertTrue(cooked.containsAll((20L..23L).toList()), "every cheap dinner is used, got $cooked")
    }

    /**
     * Light and heavy recipes of one to six servings in every slot, so that the old range of 0.5 to 2
     * servings used all of it; the dinners' first tag repeats, so tags count too.
     */
    private val variedRecipes = listOf(
        candidate(1, BREAKFAST, 430.0, 35.0, servings = 1),
        candidate(2, BREAKFAST, 180.0, 12.0, servings = 1),
        candidate(5, BREAKFAST, 260.0, 20.0, servings = 2),
        candidate(3, SNACK, 200.0, 18.0, servings = 1),
        candidate(4, SNACK, 90.0, 4.0, servings = 1),
        candidate(6, LUNCH, 350.0, 25.0, servings = 2),
        candidate(7, LUNCH, 240.0, 15.0, servings = 1),
    ) + (10L..29L).map { id ->
        candidate(id, DINNER, kcal = 170.0 + (id * 97) % 760, protein = 15.0 + (id * 13) % 40, servings = 1 + (id % 6).toInt(), tag = "T${id % 4}")
    }

    private val variedTargets = listOf(DailyTargets(2000, 150), DailyTargets(1500, 110), DailyTargets(2800, 180))

    @Test
    fun withTheDefaultsLunchAndDinnerStayWithinTheRange() {
        for (targets in variedTargets) for (seed in 0L until 50L) {
            val plan = WeekPlanner.plan(PlanRequest(targets, variedRecipes, seed = seed))
            val case = "${targets.kcal} kcal, seed $seed"
            for (meal in plan.filter { it.slot == DINNER || (it.slot == LUNCH && !it.leftover) }) {
                assertTrue(meal.portions in 0.75..1.5, "$case: ${meal.slot} of ${meal.portions}")
            }
            // A leftover lunch is what the dinner left, which may be less than the smallest portion.
            for (meal in plan.filter { it.leftover }) assertTrue(meal.portions in 0.5..1.5, "$case: leftovers of ${meal.portions}")
            for (meal in plan.filter { it.slot == BREAKFAST || it.slot == SNACK }) {
                assertTrue(meal.portions in 0.75..2.0, "$case: ${meal.slot} of ${meal.portions}")
            }
        }
    }

    @Test
    fun aRecipeThatNeedsTwoServingsToReachHalfTheDinnerIsNoDinner() {
        // The dinner budget is 760 kcal: 200 kcal reaches half of it at two servings, not at 1.5.
        val light = candidate(30, DINNER, 200.0, 30.0)
        val recipes = listOf(light, candidate(31, DINNER, 650.0, 50.0))

        assertFalse(WeekPlanner.fitsAsMeal(light, DINNER, targets, PortionRange()))
        assertTrue(WeekPlanner.plan(PlanRequest(targets, recipes)).none { it.recipeId == 30L })
        // Up to two servings it was a dinner, which the planner took for variety.
        assertTrue(WeekPlanner.fitsAsMeal(light, DINNER, targets, PortionRange(0.5, 2.0)))
        val wide = WeekPlanner.plan(PlanRequest(targets, recipes, portions = PortionRange(0.5, 2.0)))
        assertTrue(wide.any { it.recipeId == 30L && it.slot == DINNER && it.portions == 2.0 })
    }

    @Test
    fun aSingleServingBreakfastIsStillPlannedAtUpToTwo() {
        // The breakfast budget is 440 kcal: two servings of 180 come closest.
        val recipes = listOf(candidate(2, BREAKFAST, 180.0, 12.0, servings = 1), candidate(10, DINNER, 650.0, 50.0))
        val plan = WeekPlanner.plan(PlanRequest(targets, recipes))
        assertEquals(List(7) { 2.0 }, plan.filter { it.slot == BREAKFAST }.map { it.portions })
    }

    // --- Fibre and the profile's priority (issue 223)

    /** 600 kcal, 45 g protein and 9 g fibre for lunch: the share of these targets a 600 kcal serving fills exactly. */
    private val withFibre = DailyTargets(kcal = 2000, proteinG = 150, fibreG = 30)

    /** A four-serving dinner of 600 kcal a serving, by default with nothing that pulls it forward or back. */
    private fun dish(id: Long, protein: Double = 30.0, fibre: Double = 0.0, kcal: Double = 600.0) =
        PlanCandidate(id, "Recipe $id", setOf(DINNER), 4, kcal, protein, listOf("T$id"), fibreG = fibre)

    /**
     * The first recipe the planner picks among [recipes] under [priority], whatever the seed: the first
     * day's lunch, cooked from a dinner recipe. Every case below wins by more than the random nudge (0.3).
     */
    private fun firstPick(recipes: List<PlanCandidate>, priority: PlanPriority = PlanPriority.BALANCED): Long {
        val picks = (0L until 20L).map { seed ->
            WeekPlanner.plan(PlanRequest(withFibre, recipes, days = 1, priority = priority, seed = seed)).at(0, LUNCH).recipeId
        }
        return picks.distinct().singleOrNull() ?: fail("$priority: the pick depends on the seed, $picks")
    }

    @Test
    fun theHigherFibreOfTwoOtherwiseEqualRecipesIsPicked() {
        assertEquals(2L, firstPick(listOf(dish(1, fibre = 2.0), dish(2, fibre = 8.0))))
        assertEquals(2L, firstPick(listOf(dish(2, fibre = 8.0), dish(1, fibre = 2.0))), "not because of the order")
        // A recipe whose foods have no fibre value counts as none, and is still planned.
        assertEquals(1L, firstPick(listOf(dish(1, fibre = 0.0))))
    }

    @Test
    fun eachPriorityChangesThePickInACaseBuiltForIt() {
        // Rated higher and using up what is at home (+1.8) against a full share of protein (+2.0, +3.0 under Protein).
        val protein = listOf(dish(1, protein = 45.0).copy(rating = 1), dish(2, protein = 0.0).copy(rating = 5, onHandCount = 2))
        assertEquals(2L, firstPick(protein))
        assertEquals(1L, firstPick(protein, PlanPriority.PROTEIN))

        // The same against fibre at its cap (+1.5, +3.0 under Fibre).
        val fibre = listOf(dish(1, fibre = 15.0), dish(2).copy(rating = 5, onHandCount = 3))
        assertEquals(2L, firstPick(fibre))
        assertEquals(1L, firstPick(fibre, PlanPriority.FIBRE))

        // Twice the typical price (-1.2, -2.4 under Price) against +1.8; the breakfast makes the cheap price the typical one.
        val price = listOf(
            dish(1).copy(costPerServing = 1.0),
            dish(2).copy(costPerServing = 2.0, rating = 5, onHandCount = 2),
            candidate(3, BREAKFAST, 600.0, 30.0).copy(costPerServing = 1.0),
        )
        assertEquals(2L, firstPick(price))
        assertEquals(1L, firstPick(price, PlanPriority.PRICE))
    }

    @Test
    fun amongEquallyCheapRecipesTheHigherProteinAndFibreOneWinsUnderEveryPriority() {
        val cheap = listOf(
            dish(1, protein = 30.0, fibre = 3.0).copy(costPerServing = 1.0),
            dish(2, protein = 45.0, fibre = 9.0).copy(costPerServing = 1.0),
            dish(3, protein = 45.0, fibre = 9.0).copy(costPerServing = 4.0),
        )
        for (priority in PlanPriority.entries) assertEquals(2L, firstPick(cheap, priority), "$priority")
    }

    @Test
    fun noPriorityChangesThePickBetweenRecipesThatDifferOnlyInCalorieFit() {
        // Recipe 2 is too rich to fit: its smallest portion, 0.75, is 750 kcal for a 600 kcal lunch. That
        // portion has the same protein, fibre and price per kcal as recipe 1's serving.
        val recipes = listOf(
            dish(1, protein = 30.0, fibre = 6.0).copy(costPerServing = 1.2),
            dish(2, protein = 40.0, fibre = 8.0, kcal = 1000.0).copy(costPerServing = 2.0),
        )
        for (priority in PlanPriority.entries) assertEquals(1L, firstPick(recipes, priority), "$priority")
    }

    @Test
    fun aPlannedMealHasTheFibreOfItsPortions() {
        val plan = WeekPlanner.plan(PlanRequest(withFibre, listOf(dish(1, fibre = 8.0), dish(2, fibre = 4.0, kcal = 400.0)), days = 1))
        for (meal in plan) assertEquals(if (meal.recipeId == 1L) 8.0 * meal.portions else 4.0 * meal.portions, meal.fibreG)
    }

    /**
     * A range of 0.5 to 2 servings plans exactly as the planner did before the range (issue 227): the
     * plans of the first seed in full, and of 50 seeds for three targets, and every portion and
     * meal-or-not of every recipe in every slot, as checksums of what the planner then made.
     */
    @Test
    fun aRangeOfHalfToTwoServingsPlansAsBefore() {
        val wide = PortionRange(0.5, 2.0)
        fun plan(targets: DailyTargets, seed: Long) = WeekPlanner.plan(PlanRequest(targets, variedRecipes, portions = wide, seed = seed))
        // Day, slot's first letter, recipe, quarter servings, and L for leftovers.
        fun List<PlannedMeal>.fingerprint() =
            joinToString(" ") { "${it.day}${it.slot.name.first()}${it.recipeId}x${(it.portions * 4).toInt()}${if (it.leftover) "L" else ""}" }
        fun checksum(text: String) = text.fold(0L) { sum, c -> sum * 31 + c.code }

        assertEquals(
            "0B5x7 0L6x7 0D18x8 0S3x4 1B5x7 1L7x8 1D27x6 1S3x4 2B5x7 2L27x5L 2D17x8 2S3x4 3B5x7 3L17x8L 3D24x8 3S3x4 " +
                "4B5x7 4L6x7 4D26x7 4S3x4 5B5x7 5L26x5L 5D12x5 5S3x4 6B5x7 6L7x8 6D11x6 6S3x4",
            plan(variedTargets[0], 0).fingerprint(),
        )
        assertEquals(
            "0B1x3 0L6x5 0D18x6 0S3x3 1B1x3 1L7x7 1D17x8 1S3x3 2B1x3 2L17x6L 2D24x8 2S3x3 3B1x3 3L6x5 3D26x6 3S3x3 " +
                "4B1x3 4L26x4L 4D12x4 4S3x3 5B1x3 5L7x7 5D11x5 5S3x3 6B1x3 6L11x4L 6D10x6 6S3x3",
            plan(variedTargets[1], 0).fingerprint(),
        )
        assertEquals(
            "0B1x6 0L6x8 0D27x8 0S3x6 1B1x6 1L27x7L 1D12x7 1S3x6 2B1x6 2L7x8 2D18x8 2S3x6 3B1x6 3L6x8 3D11x8 3S3x6 " +
                "4B1x6 4L11x7L 4D21x6 4S3x6 5B1x6 5L21x5L 5D20x7 5S3x6 6B1x6 6L20x5L 6D15x5 6S3x6",
            plan(variedTargets[2], 0).fingerprint(),
        )
        val plans = variedTargets.joinToString("\n") { targets -> (0L until 50L).joinToString("\n") { plan(targets, it).fingerprint() } }
        assertEquals(-7531212802159692011L, checksum(plans))

        val everywhere = variedRecipes.map { it.copy(slots = MealSlot.entries.toSet()) }
        val sizes = variedTargets.joinToString("\n") { targets ->
            variedRecipes.indices.joinToString(" ") { i ->
                MealSlot.entries.joinToString(",") { slot ->
                    val quarters = (WeekPlanner.portionsFor(variedRecipes[i], slot, targets, wide) * 4).toInt()
                    "$quarters${if (WeekPlanner.fitsAsMeal(everywhere[i], slot, targets, wide)) "y" else "n"}"
                }
            }
        }
        assertEquals(-4364112392982162551L, checksum(sizes))
    }

    /** A tub of 200 g at 6 euros of which a serving takes 50 g: a quarter of it, 1.5 euros, a serving. */
    private val tub = FreshUse(ingredientId = 1, gramsPerServing = 50.0, pack = Pack(grams = 200.0, euros = 6.0))

    private val sharingATub = pantryOfRecipes.map { if (it.recipeId == 10L || it.recipeId == 19L) it.copy(freshUses = listOf(tub)) else it }

    /** The days recipe 10 and recipe 19, which share the tub, are cooked (a lunch may be cooked too) in the plan of each seed; null for one not planned. */
    private fun tubCookDays(candidates: List<PlanCandidate>, locked: List<PlannedMeal> = emptyList()) = (0L until 20L).map { seed ->
        val dinners = WeekPlanner.plan(PlanRequest(targets, candidates, locked = locked, packWeights = PackWeights(waste = 0.0), seed = seed)).filter { it.slot == DINNER || (it.slot == LUNCH && !it.leftover) }
        dinners.firstOrNull { it.recipeId == 10L }?.day to dinners.firstOrNull { it.recipeId == 19L }?.day
    }

    private fun withinATubsDays(days: Pair<Int?, Int?>) = days.first != null && days.second != null && abs(days.first!! - days.second!!) <= 3

    @Test
    fun dinnersThatShareAPackAreCookedWithinTheDaysItKeeps() {
        // Without the tub the two are planned apart or one is left out in some weeks; with it, in none.
        assertFalse(tubCookDays(pantryOfRecipes).all(::withinATubsDays))
        assertTrue(tubCookDays(sharingATub).all(::withinATubsDays), tubCookDays(sharingATub).toString())
    }

    @Test
    fun aLockedMealThatOpenedAPackCountsForTheMealsAfterIt() {
        val locked = PlannedMeal(0, DINNER, 19, "Recipe 19", 0.75, 650.0, 40.0, locked = true)

        val days = tubCookDays(sharingATub, listOf(locked))

        assertTrue(days.all(::withinATubsDays), days.toString())
    }

    @Test
    fun anotherRecipeForADinnerPrefersTheOneThatUsesTheRestOfAnOpenPack() {
        // Three dinners alike but for the tub: recipe 19, locked on day 0, opened it; recipe 10 takes from it, recipe 11 does not.
        val snacksAndBreakfasts = pantryOfRecipes.filter { DINNER !in it.slots }
        val dinners = listOf(10L, 11L, 19L).map { candidate(it, DINNER, kcal = 600.0, protein = 45.0) }
        val withTub = snacksAndBreakfasts + dinners.map { if (it.recipeId == 11L) it else it.copy(freshUses = listOf(tub)) }
        val withoutTub = snacksAndBreakfasts + dinners
        // The dinner of day 0 locked, as a reroll of day 1's dinner leaves it, with the recipe it replaces excluded.
        val locked = listOf(PlannedMeal(0, DINNER, 19, "Recipe 19", 0.75, 650.0, 40.0, locked = true))
        fun dinnerOfDay1(candidates: List<PlanCandidate>, seed: Long) =
            WeekPlanner.plan(PlanRequest(targets, candidates, locked = locked, excluded = setOf(19L), seed = seed)).first { it.day == 1 && it.slot == DINNER }.recipeId

        val seeds = 0L until 20L
        assertTrue(seeds.all { dinnerOfDay1(withTub, it) == 10L }, "with the tub open, recipe 10 is picked every time")
        assertTrue(seeds.any { dinnerOfDay1(withoutTub, it) == 11L }, "without it, recipe 11 is picked too")
    }

    @Test
    fun aRecipeThatOpensANewPackForASmallAmountLosesToAnEqualOne() {
        fun weeksCooked(candidates: List<PlanCandidate>, id: Long, weights: PackWeights) = (0L until 20L).count { seed ->
            WeekPlanner.plan(PlanRequest(targets, candidates, packWeights = weights, seed = seed)).any { it.recipeId == id && !it.leftover }
        }
        // The dinner most weeks cook, given 10 g of a 500 g pack of 10 euros: 9.80 euros of rest left in the cupboard.
        val none = PackWeights(reuse = 0.75, waste = 0.0)
        val favourite = pantryOfRecipes.filter { DINNER in it.slots }.maxBy { weeksCooked(pantryOfRecipes, it.recipeId, none) }
        val smallAmount = FreshUse(3, gramsPerServing = 2.5, pack = Pack(grams = 500.0, euros = 10.0))
        val withSmallAmount = pantryOfRecipes.map { if (it.recipeId == favourite.recipeId) it.copy(freshUses = listOf(smallAmount)) else it }

        val without = weeksCooked(withSmallAmount, favourite.recipeId, none)
        val with = weeksCooked(withSmallAmount, favourite.recipeId, PackWeights(reuse = 0.75, waste = 0.2))

        assertEquals(weeksCooked(pantryOfRecipes, favourite.recipeId, none), without, "without a penalty a pack opened alone changes nothing")
        assertTrue(with < without, "it is cooked in $with weeks with the penalty and $without without it")
    }

    @Test
    fun aLockedDinnerOfARecipeThePlannerKnowsButDoesNotPickLeavesItsLeftoversForTheNextLunch() {
        val own = candidate(90, DINNER, kcal = 650.0, protein = 45.0)
        val locked = listOf(PlannedMeal(2, DINNER, 90, "Recipe 90", 1.25, 812.5, 56.25, locked = true))
        val request = PlanRequest(targets, pantryOfRecipes, locked = locked, seed = 3)

        val unknown = WeekPlanner.plan(request)
        val known = WeekPlanner.plan(request.copy(known = listOf(own)))

        assertFalse(unknown.at(3, LUNCH).recipeId == 90L, "without it, Thursday's lunch is another recipe")
        assertEquals(90L to true, known.at(3, LUNCH).let { it.recipeId to it.leftover }, "with it, Wednesday's leftovers")
        assertTrue(known.none { it.recipeId == 90L && it.day != 2 && it.slot == DINNER }, "and it is never picked for another dinner")
    }

    @Test
    fun recipesWithoutFreshUsesPlanAsBefore() {
        val request = PlanRequest(targets, pantryOfRecipes, seed = 5)

        assertEquals(WeekPlanner.plan(request), WeekPlanner.plan(request.copy(candidates = pantryOfRecipes.map { it.copy(freshUses = emptyList()) })))
    }
}
