package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSlot.BREAKFAST
import dev.tobins.mealplanner.api.MealSlot.DINNER
import dev.tobins.mealplanner.api.MealSlot.LUNCH
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepMeal
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.WeekPlan
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

    @Test
    fun prepARecipeOpensOnAWayTheRecipeIsKept() {
        val vacuumOnly = recipe(3, null, 6)
        val opened = newPrep(listOf(vacuumOnly), listOf("2026-11-18", "2026-11-19"), today = "2026-11-18")
        assertEquals(true to "2026-11-18", opened.vacuum to opened.madeOn)
        assertEquals(7, opened.days.size, "made Wednesday, six days vacuum sealed")
        assertEquals(Msg.PREP_FIRST_ON_MADE_DAY, opened.problem)
        assertEquals(false, newPrep(listOf(salad), listOf("2026-11-18"), "2026-11-18").vacuum, "in a box when it keeps that way")
        assertNull(newPrep(listOf(salad, vacuumOnly), listOf("2026-11-18"), "2026-11-18").recipe, "several: the user picks")
    }

    @Test
    fun aShareInTheBoxSaysUntilWhenItKeepsAndOnItsLastDayToEatItOrThrowItAway() {
        val spare = SparePortion(7, 1, "Salad", 1.25, PrepSlot("2026-11-18", LUNCH), lastDay = "2026-11-20", room = emptyList())
        assertEquals("Skipped on Wed 18 Nov lunch · keeps until Fri 20 Nov", spare.whenText("2026-11-18", Strings.English))
        assertEquals("Its last day: eat it today or throw it away.", spare.whenText("2026-11-20", Strings.English))
        assertEquals("Laatste dag: eet het vandaag op of gooi het weg.", spare.whenText("2026-11-20", Strings.Dutch))
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
    fun aVacuumSealedBatchSaysSoOnItsMeals() {
        val sealed = prep.copy(vacuum = true)
        val made = PlannedMeal(1, DINNER, 1, "Salad", 1.5, 600.0, 40.0, locked = true, prep = sealed)
        val later = PlannedMeal(2, LUNCH, 1, "Salad", 1.25, 500.0, 33.0, leftover = true, locked = true, prep = sealed)
        assertEquals("Dinner · prep day · vacuum sealed", Strings.English.mealLabel(made))
        assertEquals("Lunch · prepped Tue 17 Nov · vacuum sealed", Strings.English.mealLabel(later))
        assertEquals("Avondeten · prepdag · vacuüm", Strings.Dutch.mealLabel(made))
        assertEquals("Lunch · geprept di 17 nov · vacuüm · aangepast", Strings.Dutch.mealLabel(later.copy(adjusted = true)))
    }

    @Test
    fun theRecipeOfAPreppedMealSaysWhatToCookForTheLaterMeals() {
        val english = Strings.English
        assertEquals("Cook 2.75 servings: 1.5 now, 1.25 for the prep's later meals", MealToCook(1, 1.5, 2.75, skipped = false, prepMadeOn = "2026-11-17").sentence(english))
        assertEquals("Cook 2.75 servings: 0 now, 2.75 for the prep's later meals", MealToCook(1, 1.5, 2.75, skipped = true, prepMadeOn = "2026-11-17").sentence(english))
        assertEquals("Prepped on Tue 17 Nov: nothing to cook", MealToCook(1, 1.25, 0.0, skipped = false, prepMadeOn = "2026-11-17").sentence(english))
        assertEquals("Skipped: nothing to cook", MealToCook(1, 1.25, 0.0, skipped = true, prepMadeOn = "2026-11-17").sentence(english))
    }

    // --- Rough edges (issue 263)

    /** The week of 16 November with [meals] by day. */
    private fun week(vararg meals: PlannedMeal, start: String = "2026-11-16") = WeekPlan(
        start, DailyTargets(2000, 150), (0..6).map { day -> PlanDay(plusDays(start, day), meals.filter { it.day == day }, 0.0, 0.0) }, emptyList(),
    )

    @Test
    fun theBoxCardShowsOnlyOnTheWeeksTheShareCanStillBeEatenIn() {
        val spare = SparePortion(7, 1, "Salad", 1.25, PrepSlot("2026-11-21", LUNCH), lastDay = "2026-11-24", room = emptyList())
        val today = "2026-11-21"
        assertEquals(listOf(spare), sparesShown(listOf(spare), "2026-11-16", today), "this week")
        assertEquals(listOf(spare), sparesShown(listOf(spare), "2026-11-23", today), "next week, until its last day")
        assertEquals(emptyList(), sparesShown(listOf(spare), "2026-11-09", today), "a week that is over")
        assertEquals(emptyList(), sparesShown(listOf(spare), "2026-11-30", today), "a week after it keeps")
    }

    @Test
    fun aWeekWithOnlyMealsOfPreppedBatchesIsStillToPlan() {
        val lastWeeks = prep.copy(madeOn = "2026-11-15")
        val tail = PlannedMeal(0, LUNCH, 1, "Salad", 1.25, 500.0, 33.0, leftover = true, locked = true, prep = lastWeeks)
        assertEquals(false, week(tail).hasOwnMeals, "only last week's batch")
        assertEquals(true, week(tail, PlannedMeal(1, DINNER, 9, "Rice bowl", 1.0, 700.0, 30.0)).hasOwnMeals)
        // A batch made this week, prepped before the week was planned (the emulator run of #270): its other slots are still to plan.
        val thisWeeks = PlannedMeal(1, DINNER, 1, "Salad", 1.5, 600.0, 40.0, locked = true, prep = prep)
        assertEquals(false, week(thisWeeks).hasOwnMeals, "only a batch made this week")
        assertEquals(true, week(thisWeeks, PlannedMeal(2, LUNCH, 9, "Rice bowl", 1.0, 700.0, 30.0)).hasOwnMeals)
        assertEquals(false, week().hasOwnMeals)
    }

    @Test
    fun removingAPrepThatWasMadeSaysBeforehandThatItStays() {
        val english = Strings.English
        val skipped = prep.copy(meals = listOf(prep.meals[0], prep.meals[1].copy(status = MealStatus.SKIPPED)))
        assertEquals(false, skipped.made)
        assertEquals("This takes all its meals out of the plan: Tue 17 Nov dinner, Wed 18 Nov lunch (skipped).", removePrepText(skipped, english))

        // The one eaten and the one skipped are not listed: only a meal still to eat can be skipped instead.
        val planned = PrepMeal("2026-11-19", DINNER, 1.0, MealStatus.PLANNED)
        val made = skipped.copy(meals = listOf(prep.meals[0].copy(status = MealStatus.EATEN), skipped.meals[1], planned))
        assertEquals(true, made.made)
        assertEquals(
            "One of its meals was eaten, so the batch was cooked and stays in the plan. Mark the meals you do not eat skipped instead: Thu 19 Nov dinner.",
            removePrepText(made, english),
        )
        val sides = made.copy(meals = made.meals.map { it.copy(side = true) })
        assertEquals(
            "A meal with this side was eaten, so the batch was cooked and its sides stay in the plan. Mark the meals you do not eat it with skipped instead: " +
                "Thu 19 Nov dinner (side).",
            removePrepText(sides, english),
        )
        assertEquals(
            "Een maaltijd met dit bijgerecht is gegeten, dus de prep is gekookt en de bijgerechten blijven in het plan. Zet de maaltijden waarbij je het niet eet op overgeslagen: " +
                "do 19 nov avondeten (bijgerecht).",
            removePrepText(sides, Strings.Dutch),
        )
        // Nothing left to skip: it says so instead of listing what is done.
        val allDone = made.copy(meals = made.meals - planned)
        assertEquals(
            "One of its meals was eaten, so the batch was cooked and stays in the plan. All its meals are eaten or skipped already: there is nothing left to change.",
            removePrepText(allDone, english),
        )
        assertEquals(
            "Een van de maaltijden is gegeten, dus de prep is gekookt en blijft in het plan. Alle maaltijden zijn al gegeten of overgeslagen: er valt niets meer te wijzigen.",
            removePrepText(allDone, Strings.Dutch),
        )
    }

    @Test
    fun aLaterMealOfABatchShowsWhatIsInItsBoxNotWhatToCook() {
        // Of a recipe of 4 servings: the prep day scales to the batch, a later day to its own 1.25 servings.
        val later = MealToCook(1, 1.25, 0.0, skipped = false, prepMadeOn = "2026-11-17")
        assertEquals(true, later.fromBatch)
        assertEquals(1.25 / 4, later.amountsFactor(4, asWritten = false))
        assertNull(later.amountsFactor(4, asWritten = true), "as written when asked")
        assertEquals(2.75 / 4, MealToCook(1, 1.5, 2.75, skipped = false, prepMadeOn = "2026-11-17").amountsFactor(4, asWritten = false))
        assertEquals(false, later.copy(skipped = true).fromBatch, "a skipped one holds nothing")
        assertNull(MealToCook(1, 0.75, 0.0, skipped = false).amountsFactor(4, asWritten = false), "yesterday's leftovers stay as written, as before")
        assertEquals("In this meal: 1.25 servings", Strings.English(Msg.IN_THIS_MEAL, servingsText(1.25, Strings.English)))
    }

    @Test
    fun aWeekOfOnlyAnEarlierBatchSaysWhyItHasNothingToBuy() {
        val empty = ShoppingList("2026-11-23", emptyList(), emptyList())
        assertEquals("Nothing to buy: no meals are planned for this week.", nothingToBuyText(empty, Strings.English))
        val tail = empty.copy(preppedBoughtIn = "2026-11-16")
        assertEquals("Nothing to buy: this week's prepped meals were bought with the list of the week of 16 Nov.", nothingToBuyText(tail, Strings.English))
        assertEquals("Niets te kopen: de geprepte maaltijden van deze week zijn gekocht met de lijst van de week van 16 nov.", nothingToBuyText(tail, Strings.Dutch))
    }
}
