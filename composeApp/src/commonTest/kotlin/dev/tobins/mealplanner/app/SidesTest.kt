package dev.tobins.mealplanner.app

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun aPrepEatenAsASideIsSentWithItsPortionAndOnlyNextToMealsThatCanTakeOne() {
        val hosts = setOf(PrepSlot("2026-11-17", DINNER), PrepSlot("2026-11-18", LUNCH))
        val chosen = PrepChoice(salad, madeOn = "2026-11-17", firstDay = "2026-11-17")
            .toggled("2026-11-17", DINNER).toggled("2026-11-18", LUNCH).toggled("2026-11-18", DINNER)
        // As a side, the dinner without a meal to go with is dropped; back to the meal, the choice stays as it is.
        val asSide = chosen.eatenAs(0.5, hosts)
        assertEquals(hosts, asSide.meals)
        assertNull(asSide.problem)
        assertEquals(
            PrepInput(1, "2026-11-17", false, listOf(PrepSlot("2026-11-17", DINNER), PrepSlot("2026-11-18", LUNCH)), side = 0.5),
            asSide.input(),
        )
        assertEquals(null, asSide.eatenAs(null, hosts).input().side)
        assertEquals(listOf(0.25, 0.5), SIDE_PORTIONS)
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
        assertEquals(setOf(PrepSlot("2026-11-17", DINNER), PrepSlot("2026-11-18", DINNER)), sideHosts(week, null))
    }

    @Test
    fun aSideSaysWhereItsBatchIsMadeAndWhatTheMealComesToWithIt() {
        val english = Strings.English
        val made = side(1.0)
        assertEquals("Side · prep day", english.sideLabel(made, "2026-11-17", DINNER))
        assertEquals("Side · prepped Tue 17 Nov", english.sideLabel(made, "2026-11-18", LUNCH))
        assertEquals("Bijgerecht · geprept di 17 nov", Strings.Dutch.sideLabel(made, "2026-11-18", LUNCH))
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
        assertEquals("This day is 400 kcal over the target; its sides add 225 kcal.", Strings.English(Msg.DAY_OVER_WITH_SIDES, over.first, over.second))
        assertEquals(150 to 225, day(2150.0, meal(1, DINNER, side(1.0))).overWithSides(targets))
        assertNull(day(1950.0, meal(1, DINNER, side(1.0))).overWithSides(targets), "under the target")
        assertNull(day(2150.0, meal(1, DINNER)).overWithSides(targets), "over without a side: as the planner made it")
        assertNull(day(2150.0, meal(1, DINNER, side(1.0), MealStatus.SKIPPED)).overWithSides(targets), "a skipped side counts for nothing")
    }

    @Test
    fun aSideForOneMealOnlySaysWhyItNeedsASecond() {
        val choice = PrepChoice(salad, madeOn = "2026-11-17", firstDay = "2026-11-17").toggled("2026-11-17", DINNER)
        assertEquals(Msg.PREP_TWO_SIDES, choice.eatenAs(0.5, setOf(PrepSlot("2026-11-17", DINNER))).problem)
        assertEquals(Msg.PREP_TWO_MEALS, choice.problem, "as the meal, as before")
    }

    @Test
    fun aSideInTheBoxIsCalledASide() {
        val spare = SparePortion(7, 1, "Salad", 0.5, PrepSlot("2026-11-18", LUNCH), lastDay = "2026-11-20", room = emptyList(), side = true)
        assertEquals("A side in the box: Salad", spare.titleText(Strings.English))
        assertEquals("A portion in the box: Salad", spare.copy(side = false).titleText(Strings.English))
        assertEquals("Een bijgerecht in het bakje: Salad", spare.titleText(Strings.Dutch))
    }
}
