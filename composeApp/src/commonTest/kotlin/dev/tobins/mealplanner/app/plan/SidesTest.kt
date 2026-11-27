package dev.tobins.mealplanner.app.plan

import dev.tobins.mealplanner.api.BatchToMake
import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealSlot
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
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.ui.sentence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the Plan and Today offer and say of a side taken from a prepped batch (issue 234, step 2). */
class SidesTest {
    private val salad = RecipeSummary(
        1, "Salad", "Lunch", emptyList(), 4, RecipeSource.MANUAL, RecipeKind.MEAL, plannable = true, added = true, keepsDays = 3,
    )

    private val prep = MealPrep(
        7, "2026-11-17", "2026-11-20", false,
        listOf(PrepMeal("2026-11-17", DINNER, 0.5, MealStatus.PLANNED, side = true), PrepMeal("2026-11-18", LUNCH, 0.5, MealStatus.PLANNED, side = true)),
    )

    private fun side(cookPortions: Double) = MealSide(1, "Salad", 0.5, 225.0, 30.0, 2.0, cost = 1.2, cookPortions = cookPortions, prep = prep)

    private fun meal(day: Int, slot: MealSlot, side: MealSide? = null, status: MealStatus = MealStatus.PLANNED) =
        PlannedMeal(day, slot, 2, "Rice bowl", 1.0, 700.0, 14.0, status = status, fibreG = 3.0, side = side)

    @Test
    fun eachMealOfAPrepIsEatenAsTheMealOrAsASideOnlyNextToAMealThatCanTakeOne() {
        // Issue 269: "Eaten as" is how the next lunch or dinner picked is eaten, and each one picked keeps how it was picked.
        val hosts = mapOf(PrepSlot("2026-11-18", LUNCH) to 9L, PrepSlot("2026-11-19", LUNCH) to 9L)
        val chosen = PrepChoice(salad, madeOn = "2026-11-17", firstDay = "2026-11-17").toggled("2026-11-17", DINNER)
            .eatenAs(0.25).toggled("2026-11-18", LUNCH).eatenAs(0.5).toggled("2026-11-19", LUNCH)
        assertNull(chosen.problem)
        assertEquals(
            PrepInput(1, "2026-11-17", false, listOf(PrepSlot("2026-11-17", DINNER), PrepSlot("2026-11-18", LUNCH, 0.25), PrepSlot("2026-11-19", LUNCH, 0.5))),
            chosen.input(),
        )

        // As a side, a slot without a meal to go with can't be picked; one picked as such a side can be taken off again.
        assertFalse(chosen.canTake("2026-11-19", DINNER, hosts))
        assertEquals(chosen, chosen.tapped("2026-11-19", DINNER, hosts))
        assertNull(chosen.tapped("2026-11-19", LUNCH, hosts).picked("2026-11-19", LUNCH))
        assertEquals(PrepSlot("2026-11-18", LUNCH, 0.5), chosen.tapped("2026-11-18", LUNCH, hosts).picked("2026-11-18", LUNCH), "a quarter becomes a half")
        // A meal picked where a side can't go comes off when tapped, rather than become a side with no meal to go with.
        assertNull(chosen.tapped("2026-11-17", DINNER, hosts).picked("2026-11-17", DINNER))
        // Back to the meal, every slot can be picked and the sides stay as they are; a side tapped then becomes the meal.
        val asMeal = chosen.eatenAs(null)
        assertEquals(chosen.meals, asMeal.meals)
        assertTrue(asMeal.canTake("2026-11-19", DINNER, hosts))
        assertEquals(PrepSlot("2026-11-18", LUNCH), asMeal.tapped("2026-11-18", LUNCH, hosts).picked("2026-11-18", LUNCH))
        assertEquals(listOf(0.25, 0.5), SIDE_PORTIONS)

        assertEquals(listOf("Dinner", "Lunch · side 0.25"), listOf(prepSlotText(DINNER, null, Strings.English), prepSlotText(LUNCH, 0.25, Strings.English)))
        assertEquals("Lunch · bijgerecht 0,5", prepSlotText(LUNCH, 0.5, Strings.Dutch))
    }

    @Test
    fun aSideCannotGoNextToAMealOfItsOwnRecipe() {
        // The server refuses it (no plate holds one recipe twice), so the dialog does not offer it.
        val hosts = mapOf(PrepSlot("2026-11-18", LUNCH) to 1L, PrepSlot("2026-11-19", LUNCH) to 9L)
        val asSide = PrepChoice(salad, madeOn = "2026-11-17", firstDay = "2026-11-17").eatenAs(0.5)
        assertFalse(asSide.canTake("2026-11-18", LUNCH, hosts))
        assertTrue(asSide.canTake("2026-11-19", LUNCH, hosts))
        assertEquals(asSide, asSide.tapped("2026-11-18", LUNCH, hosts), "tapping it picks nothing")
        // As the meal itself any slot will do, and one picked as a side before the recipe changed can still be taken off.
        assertTrue(asSide.eatenAs(null).canTake("2026-11-18", LUNCH, hosts))
    }

    @Test
    fun aSideCanGoNextToALunchOrDinnerStillToEatThatHasNone() {
        val day = PlanDay(
            "2026-11-17",
            listOf(
                meal(1, BREAKFAST), meal(1, LUNCH, status = MealStatus.EATEN), meal(1, DINNER),
            ),
            0.0, 0.0,
        )
        val next = PlanDay("2026-11-18", listOf(meal(2, LUNCH, side = side(0.0)), meal(2, DINNER)), 0.0, 0.0)
        val week = WeekPlan("2026-11-16", DailyTargets(2000, 120), listOf(day, next), emptyList())
        assertEquals(mapOf(PrepSlot("2026-11-17", DINNER) to 2L, PrepSlot("2026-11-18", DINNER) to 2L), sideHosts(week, null))
    }

    @Test
    fun aSideSaysWhereItsBatchIsMadeAndWhatTheMealComesToWithIt() {
        val english = Strings.English
        val made = side(1.0)
        assertEquals("Side · prep day", english.sideLabel(made, "2026-11-17", DINNER))
        assertEquals("Side · prepped Tue 17 Nov", english.sideLabel(made, "2026-11-18", LUNCH))
        assertEquals("Bijgerecht · geprept di 17 nov", Strings.Dutch.sideLabel(made, "2026-11-18", LUNCH))
        // A batch kept vacuum sealed says so (issue 263).
        val sealed = made.copy(prep = made.prep.copy(vacuum = true))
        assertEquals("Side · prep day · vacuum sealed", english.sideLabel(sealed, "2026-11-17", DINNER))
        assertEquals("Bijgerecht · geprept di 17 nov · vacuüm", Strings.Dutch.sideLabel(sealed, "2026-11-18", LUNCH))
        assertEquals("Tue 17 Nov dinner (side), Wed 18 Nov lunch (side)", prepMealsText(prep, english))
        assertEquals("0.5 servings · 225 kcal · 30 g protein · 2 g fibre", made.macrosText(english).replace('\u00A0', ' '))
        assertEquals("With the side: 925 kcal · 44 g protein · 5 g fibre", meal(1, DINNER, made).withSideText(english).replace('\u00A0', ' '))
        // Opened from the dinner where it is made, its recipe cooks the batch; it has no place to mark eaten, as its meal has.
        val toCook = made.toCook(skipped = false)
        assertEquals("Cook 1 serving: 0.5 now, 0.5 for the prep's later meals", toCook.sentence(english))
        assertNull(toCook.place)
        assertEquals("Prepped on Tue 17 Nov: nothing to cook", side(0.0).toCook(skipped = false).sentence(english))
    }

    @Test
    fun aDayWithASideThatGoesOverItsCaloriesSaysByHowMuchAndWhatItsSidesAdd() {
        val targets = DailyTargets(2000, 120)
        fun day(kcal: Double, vararg meals: PlannedMeal) = PlanDay("2026-11-17", meals.toList(), kcal, 0.0)
        // Over by more than the side adds: over without it too, which the sentence does not lay on the side.
        val over = assertNotNull(day(2400.0, meal(1, DINNER, side(1.0))).overWithSides(targets))
        assertEquals(400 to 225, over)
        assertEquals("This day is 400 kcal over the target; its sides add 225 kcal.", Strings.English(PlanMsg.DAY_OVER_WITH_SIDES, over.first, over.second))
        assertEquals(150 to 225, day(2150.0, meal(1, DINNER, side(1.0))).overWithSides(targets))
        assertNull(day(1950.0, meal(1, DINNER, side(1.0))).overWithSides(targets), "under the target")
        assertNull(day(2150.0, meal(1, DINNER)).overWithSides(targets), "over without a side: as the planner made it")
        assertNull(day(2150.0, meal(1, DINNER, side(1.0), MealStatus.SKIPPED)).overWithSides(targets), "a skipped side counts for nothing")
    }

    @Test
    fun aSideForOneMealOnlySaysWhyItNeedsASecond() {
        val choice = PrepChoice(salad, madeOn = "2026-11-17", firstDay = "2026-11-17").toggled("2026-11-17", DINNER)
        assertEquals(PlanMsg.PREP_TWO_SIDES, PrepChoice(salad, madeOn = "2026-11-17", firstDay = "2026-11-17").eatenAs(0.5).toggled("2026-11-17", DINNER).problem)
        assertEquals(PlanMsg.PREP_TWO_MEALS, choice.problem, "as the meal, as before")
    }

    /** Issue 269: the salad made on Tuesday's dinner as the meal, with half a serving next to Wednesday's lunch, until Friday. */
    private val mixed = MealPrep(
        8, "2026-11-17", "2026-11-20", false,
        listOf(PrepMeal("2026-11-17", DINNER, 1.5, MealStatus.PLANNED), PrepMeal("2026-11-18", LUNCH, 0.5, MealStatus.PLANNED, side = true)),
    )

    @Test
    fun removingAPrepOfMealsAndSidesNamesBothAndSaysWhatStays() {
        val english = Strings.English
        // What goes and what stays, each in a list of its own (the emulator run of the pull request found one list unclear).
        val skipped = mixed.copy(meals = mixed.meals + PrepMeal("2026-11-19", DINNER, 0.25, MealStatus.SKIPPED, side = true))
        assertEquals(
            "This takes its meals out of the plan: Tue 17 Nov dinner. The meals with one of its sides keep their main and lose the side: " +
                "Wed 18 Nov lunch, Thu 19 Nov dinner (skipped).",
            removePrepText(skipped, english),
        )
        assertEquals(
            "Hiermee gaan de maaltijden ervan uit het plan: di 17 nov avondeten. De maaltijden met een bijgerecht ervan houden hun hoofdgerecht " +
                "en verliezen het bijgerecht: wo 18 nov lunch, do 19 nov avondeten (overgeslagen).",
            removePrepText(skipped, Strings.Dutch),
        )
        val made = mixed.copy(meals = mixed.meals.map { if (it.side) it.copy(status = MealStatus.EATEN) else it })
        assertEquals(
            "One of its meals, or a meal with one of its sides, was eaten, so the batch was cooked and stays in the plan. Mark the meals you do " +
                "not eat, or do not eat it with, skipped instead: Tue 17 Nov dinner.",
            removePrepText(made, english),
        )
        // A batch of one kind keeps its own words.
        assertTrue(removePrepText(prep, english).startsWith("This takes all its sides out of the plan"))
        assertTrue(removePrepText(mixed.copy(meals = mixed.meals.take(1)), english).startsWith("This takes all its meals out of the plan"))
    }

    @Test
    fun aSideCanBeAddedToABatchStillToMakeAfterItsFirstShareUpToItsLastDay() {
        // The salad of [mixed] on Tuesday's dinner and next to Wednesday's lunch; bowls (recipe 2) everywhere else from Monday to Saturday.
        fun bowl(day: Int, slot: MealSlot, status: MealStatus = MealStatus.PLANNED, side: MealSide? = null) = meal(day, slot, side, status)
        val salads = PlannedMeal(1, DINNER, 1, "Salad", 1.5, 675.0, 90.0, locked = true, prep = mixed)
        val sideOfSalad = MealSide(1, "Salad", 0.5, 225.0, 30.0, cookPortions = 0.0, prep = mixed)
        val days = (0..5).map { day ->
            val meals = listOf(LUNCH, DINNER).map { slot ->
                when {
                    day == 1 && slot == DINNER -> salads
                    day == 2 && slot == LUNCH -> bowl(day, slot, side = sideOfSalad)
                    day == 3 && slot == LUNCH -> bowl(day, slot, MealStatus.SKIPPED)
                    else -> bowl(day, slot)
                }
            }
            PlanDay("2026-11-${16 + day}", meals, 0.0, 0.0)
        }
        val week = WeekPlan("2026-11-16", DailyTargets(2000, 120), days, emptyList())
        fun takes(day: Int, slot: MealSlot, today: String = "2026-11-16") =
            growable(week, days[day].meals.single { it.slot == slot }, today).map { it.prep.id to it.title }

        assertEquals(listOf(8L to "Salad"), takes(2, DINNER), "after its first share, up to its last day")
        assertEquals(listOf(8L to "Salad"), takes(4, DINNER), "on its last day")
        assertEquals(emptyList(), takes(1, LUNCH), "before the share it is made with")
        assertEquals(emptyList(), takes(5, LUNCH), "past its last day")
        assertEquals(emptyList(), takes(2, LUNCH), "a meal with a side already")
        assertEquals(emptyList(), takes(3, LUNCH), "a meal not still to eat")
        assertEquals(emptyList(), takes(2, DINNER, today = "2026-11-19"), "a day that has passed")
        val made = week.copy(days = days.map { day -> day.copy(meals = day.meals.map { if (it.prep != null) it.copy(prep = mixed.copy(meals = mixed.meals.map { m -> m.copy(status = MealStatus.EATEN) })) else it }) })
        assertEquals(emptyList(), growable(made, days[2].meals.single { it.slot == DINNER }, "2026-11-16"), "a batch made already")
        val salad = days[2].meals.single { it.slot == DINNER }.copy(recipeId = 1)
        assertEquals(emptyList(), growable(week, salad, "2026-11-16"), "never one recipe twice on a plate")
    }

    @Test
    fun aSideCanBeAddedToABatchOfTheAccountWithNoShareInTheWeekShown() {
        // Issue 263: next week, with bowls only; the soup made on Sunday the 22nd for its lunch and dinner keeps until Wednesday.
        val soup = MealPrep(
            9, "2026-11-22", "2026-11-25", vacuum = false,
            listOf(PrepMeal("2026-11-22", LUNCH, 1.25, MealStatus.PLANNED), PrepMeal("2026-11-22", DINNER, 1.5, MealStatus.PLANNED)),
        )
        val days = (0..6).map { day -> PlanDay("2026-11-${23 + day}", listOf(LUNCH, DINNER).map { meal(day, it) }, 0.0, 0.0) }
        val week = WeekPlan("2026-11-23", DailyTargets(2000, 120), days, emptyList(), batchesToMake = listOf(BatchToMake(3, "Soup", soup)))
        fun takes(day: Int, slot: MealSlot) = growable(week, days[day].meals.single { it.slot == slot }, "2026-11-20").map { it.prep.id to it.title }

        assertEquals(listOf(9L to "Soup"), takes(0, LUNCH), "made the week before, with no share in this one")
        assertEquals(listOf(9L to "Soup"), takes(2, DINNER), "on its last day")
        assertEquals(emptyList(), takes(3, LUNCH), "past its last day")
        // A server from before issue 263 sends none: only the batches with a share in the week shown, as before.
        assertEquals(emptyList(), growable(week.copy(batchesToMake = emptyList()), days[0].meals.first(), "2026-11-20"))
    }

    @Test
    fun aSideInTheBoxIsCalledASide() {
        val spare = SparePortion(7, 1, "Salad", 0.5, PrepSlot("2026-11-18", LUNCH), lastDay = "2026-11-20", room = emptyList(), side = true)
        assertEquals("A side in the box: Salad", spare.titleText(Strings.English))
        assertEquals("A portion in the box: Salad", spare.copy(side = false).titleText(Strings.English))
        assertEquals("Een bijgerecht in het bakje: Salad", spare.titleText(Strings.Dutch))
    }
}
