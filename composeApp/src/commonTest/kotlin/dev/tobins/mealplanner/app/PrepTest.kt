package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSlot.BREAKFAST
import dev.tobins.mealplanner.api.MealSlot.DINNER
import dev.tobins.mealplanner.api.MealSlot.LUNCH
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepMeal
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the Plan offers and says of prepped meals (issue 234). */
class PrepTest {
    private fun recipe(id: Long, keeps: Int?, vacuum: Int?, kind: RecipeKind = RecipeKind.MEAL, added: Boolean = true) = RecipeSummary(
        id, "Recipe $id", "Dinner", emptyList(), 4, RecipeSource.MANUAL, kind, plannable = true, added = added,
        keepsDays = keeps, keepsVacuumDays = vacuum,
    )

    private val salad = recipe(1, keeps = 3, vacuum = 5)

    @Test
    fun onlyMyMealsThatKeepCanBePrepped() {
        val recipes = listOf(salad, recipe(2, null, null), recipe(3, null, 6), recipe(4, 2, null, kind = RecipeKind.COMPONENT), recipe(5, 2, null, added = false))
        assertEquals(listOf(1L, 3L), preppable(recipes).map { it.id })
    }

    @Test
    fun noDayPastTheLastOneItKeepsCanBePicked() {
        val choice = PrepChoice(salad, madeOn = "2026-11-17", firstDay = "2026-11-17")
        assertEquals(listOf("2026-11-17", "2026-11-18", "2026-11-19", "2026-11-20"), choice.days, "made Tuesday, three days in a box")
        assertEquals("2026-11-22", choice.with(vacuum = true).days.last(), "five vacuum sealed")

        // Meals of days the new way no longer allows go.
        val chosen = choice.with(vacuum = true).toggled("2026-11-17", DINNER).toggled("2026-11-22", LUNCH)
        assertEquals(setOf(PrepSlot("2026-11-17", DINNER)), chosen.with(vacuum = false).meals)
        assertEquals(emptySet(), chosen.with(madeOn = "2026-11-21").meals.filter { it.date < "2026-11-21" }.toSet())
        // A recipe that keeps only vacuum sealed is chosen that way.
        assertEquals(true, choice.with(recipe = recipe(3, null, 6)).vacuum)
    }

    @Test
    fun aPrepNeedsARecipeTwoMealsAndItsFirstOnTheDayItIsMade() {
        val choice = PrepChoice(null, madeOn = "2026-11-17", firstDay = "2026-11-17")
        assertEquals(Msg.PREP_PICK_RECIPE, choice.problem)
        assertEquals(Msg.PREP_FIRST_ON_MADE_DAY, choice.with(recipe = salad).toggled("2026-11-18", LUNCH).problem)
        assertEquals(Msg.PREP_TWO_MEALS, choice.with(recipe = salad).toggled("2026-11-17", DINNER).problem)
        val ready = choice.with(recipe = salad).toggled("2026-11-18", LUNCH).toggled("2026-11-17", DINNER)
        assertNull(ready.problem)
        assertEquals(
            PrepInput(1, "2026-11-17", false, listOf(PrepSlot("2026-11-17", DINNER), PrepSlot("2026-11-18", LUNCH))),
            ready.input(), "in eating order",
        )
        assertEquals(Msg.PREP_TWO_MEALS, ready.toggled("2026-11-18", LUNCH).problem, "a second tap takes a meal out")
    }

    @Test
    fun aPrepIsNeverMadeOnADayThatHasPassed() {
        // On Wednesday the dialog offers Wednesday to Sunday of this week, all of next week, none of last week.
        assertEquals(listOf("2026-11-18", "2026-11-19", "2026-11-20", "2026-11-21", "2026-11-22"), prepDays("2026-11-16", "2026-11-18"))
        assertEquals(7, prepDays("2026-11-23", "2026-11-18").size)
        assertEquals(emptyList(), prepDays("2026-11-09", "2026-11-18"))
        // A meal of Tuesday offered from its menu on Wednesday: it can't be made.
        val tuesday = PrepChoice(salad, madeOn = "2026-11-17", firstDay = "2026-11-18").toggled("2026-11-17", DINNER).toggled("2026-11-18", LUNCH)
        assertEquals(Msg.PREP_MADE_ON_PAST, tuesday.problem)
        assertNull(tuesday.with(madeOn = "2026-11-18").toggled("2026-11-19", LUNCH).problem)
    }

    private val prep = MealPrep(
        7, "2026-11-17", "2026-11-20", false,
        listOf(PrepMeal("2026-11-17", DINNER, 1.5, MealStatus.PLANNED), PrepMeal("2026-11-18", LUNCH, 1.25, MealStatus.PLANNED)),
    )

    @Test
    fun aPreppedMealSaysWhereItIsMadeAndAfter() {
        val made = PlannedMeal(1, DINNER, 1, "Salad", 1.5, 600.0, 40.0, locked = true, prep = prep)
        val later = PlannedMeal(2, LUNCH, 1, "Salad", 1.25, 500.0, 33.0, leftover = true, locked = true, prep = prep)
        assertEquals("Dinner · prep day", Strings.English.mealLabel(made))
        assertEquals("Lunch · prepped Tue 17 Nov", Strings.English.mealLabel(later))
        assertEquals("Lunch · geprept di 17 nov · aangepast", Strings.Dutch.mealLabel(later.copy(adjusted = true)))
        assertEquals("Lunch · leftovers", Strings.English.mealLabel(later.copy(prep = null)), "a leftover lunch as before")
        assertEquals("Breakfast", Strings.English.mealLabel(PlannedMeal(1, BREAKFAST, 2, "Oats", 1.0, 400.0, 20.0)))
        assertEquals("Tue 17 Nov dinner, Wed 18 Nov lunch", prepMealsText(prep, Strings.English))
    }

    @Test
    fun theRecipeOfAPreppedMealSaysWhatToCookForTheLaterMeals() {
        val english = Strings.English
        assertEquals("Cook 2.75 servings: 1.5 now, 1.25 for the prep's later meals", MealToCook(1, 1.5, 2.75, skipped = false, prepMadeOn = "2026-11-17").sentence(english))
        assertEquals("Cook 2.75 servings: 0 now, 2.75 for the prep's later meals", MealToCook(1, 1.5, 2.75, skipped = true, prepMadeOn = "2026-11-17").sentence(english))
        assertEquals("Prepped on Tue 17 Nov: nothing to cook", MealToCook(1, 1.25, 0.0, skipped = false, prepMadeOn = "2026-11-17").sentence(english))
        assertEquals("Skipped: nothing to cook", MealToCook(1, 1.25, 0.0, skipped = true, prepMadeOn = "2026-11-17").sentence(english))
    }
}
