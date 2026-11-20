package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealSlot.DINNER
import dev.tobins.mealplanner.api.MealSlot.LUNCH
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepMeal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which meals of the Plan keep their recipe, as the server decides it (issue 191): their menu offers no other. */
class MealKeptTest {
    private fun meal(
        day: Int, slot: MealSlot, status: MealStatus = MealStatus.PLANNED, leftover: Boolean = false, recipe: Long = 1,
        prep: MealPrep? = null, side: MealSide? = null,
    ) = PlannedMeal(day, slot, recipe, "Soup", 1.0, 600.0, 30.0, leftover = leftover, status = status, prep = prep, side = side)

    private fun prep(vararg meals: PrepMeal) = MealPrep(1, "2026-11-16", "2026-11-19", false, meals.toList())

    private fun share(date: String, slot: MealSlot, status: MealStatus = MealStatus.PLANNED, portions: Double = 1.0, side: Boolean = false) =
        PrepMeal(date, slot, portions, status, side)

    @Test
    fun aPlannedOrSkippedMealMayBeReplaced() {
        assertNull(meal(0, DINNER).whyKept("2026-11-16", emptyList()))
        assertNull(meal(0, DINNER, MealStatus.SKIPPED).whyKept("2026-11-16", emptyList()))
    }

    @Test
    fun anEatenMealKeepsItsRecipe() {
        assertEquals(WhyKept.EATEN, meal(0, DINNER, MealStatus.EATEN).whyKept("2026-11-16", emptyList()))
    }

    @Test
    fun theLeftoverLunchOfAnEatenDinnerKeepsItsRecipeWhateverItsOwnStatus() {
        val dinner = meal(0, DINNER, MealStatus.EATEN, recipe = 5)
        val lunch = meal(1, LUNCH, leftover = true, recipe = 5)
        assertEquals(WhyKept.DINNER_EATEN, lunch.whyKept("2026-11-17", listOf(dinner)))
        assertEquals(WhyKept.DINNER_EATEN, lunch.copy(status = MealStatus.SKIPPED).whyKept("2026-11-17", listOf(dinner)))
        // Not when the dinner is still to eat, is another recipe, or the lunch is no leftover.
        assertNull(lunch.whyKept("2026-11-17", listOf(dinner.copy(status = MealStatus.PLANNED))))
        assertNull(lunch.whyKept("2026-11-17", listOf(dinner.copy(recipeId = 6))))
        assertNull(lunch.copy(leftover = false).whyKept("2026-11-17", listOf(dinner)))
        assertNull(lunch.whyKept("2026-11-17", emptyList()))
    }

    @Test
    fun theMealThatMakesAPrepStaysUntilThePrepGoes() {
        val prep = prep(share("2026-11-16", DINNER), share("2026-11-17", LUNCH))
        assertEquals(WhyKept.MAKES_BATCH, meal(0, DINNER, prep = prep).whyKept("2026-11-16", emptyList()))
        assertEquals(WhyKept.MAKES_BATCH, meal(0, DINNER, MealStatus.SKIPPED, prep = prep).whyKept("2026-11-16", emptyList()))
        // A later meal of a batch still to make may be replaced.
        assertNull(meal(1, LUNCH, leftover = true, prep = prep).whyKept("2026-11-17", emptyList()))
    }

    @Test
    fun everyMealOfAPrepThatWasMadeStays() {
        val made = prep(share("2026-11-16", DINNER, MealStatus.EATEN), share("2026-11-17", LUNCH), share("2026-11-18", LUNCH, MealStatus.SKIPPED))
        assertEquals(WhyKept.EATEN, meal(0, DINNER, MealStatus.EATEN, prep = made).whyKept("2026-11-16", emptyList()))
        assertEquals(WhyKept.BATCH_MADE, meal(1, LUNCH, leftover = true, prep = made).whyKept("2026-11-17", emptyList()))
        assertEquals(WhyKept.BATCH_MADE, meal(2, LUNCH, MealStatus.SKIPPED, leftover = true, prep = made).whyKept("2026-11-18", emptyList()))
    }

    @Test
    fun aSideGoesOnItsOwnUnlessItsBatchWasMadeOrItMakesIt() {
        fun side(prep: MealPrep) = MealSide(2, "Salad", 0.5, 200.0, 10.0, cookPortions = 0.0, prep = prep)
        val growing = prep(share("2026-11-16", DINNER, side = true), share("2026-11-17", LUNCH, side = true))
        assertTrue(meal(0, DINNER, side = side(growing)).sideStays("2026-11-16"), "makes the batch")
        assertFalse(meal(1, LUNCH, side = side(growing)).sideStays("2026-11-17"))
        val made = prep(share("2026-11-16", DINNER, MealStatus.EATEN, side = true), share("2026-11-17", LUNCH, side = true))
        assertTrue(meal(1, LUNCH, side = side(made)).sideStays("2026-11-17"), "made already")
        assertFalse(meal(1, LUNCH).sideStays("2026-11-17"), "no side")
    }

    @Test
    fun aPreppedMealWhoseShareWentElsewhereCanOnlyStaySkipped() {
        val gone = prep(share("2026-11-16", DINNER), share("2026-11-17", LUNCH, MealStatus.SKIPPED, portions = 0.0))
        assertTrue(meal(1, LUNCH, MealStatus.SKIPPED, leftover = true, prep = gone).shareGone("2026-11-17"))
        assertFalse(meal(0, DINNER, prep = gone).shareGone("2026-11-16"))
        assertFalse(meal(1, LUNCH, MealStatus.SKIPPED).shareGone("2026-11-17"), "no prep")
    }

    @Test
    fun aDinnerMovesToAnotherDayUnlessItStaysOnItsDayAndSaysWhy() {
        val dinner = meal(0, DINNER, recipe = 5)
        val leftovers = meal(1, LUNCH, leftover = true, recipe = 5)
        fun whyStays(meal: PlannedMeal, after: List<PlannedMeal> = listOf(leftovers), today: String = "2026-11-16") =
            meal.whyStays("2026-11-16", emptyList(), after, today)

        assertNull(whyStays(dinner))
        assertNull(whyStays(dinner.copy(status = MealStatus.SKIPPED)), "a skipped dinner moves too")
        assertNull(whyStays(dinner.copy(locked = true)), "a locked one keeps its lock")
        assertNull(whyStays(dinner, after = listOf(meal(1, LUNCH, recipe = 7))), "the next lunch is no leftovers of it")
        assertEquals(WhyStays.PASSED, whyStays(dinner, today = "2026-11-17"), "its day has passed")
        assertEquals(WhyStays.EATEN, whyStays(dinner.copy(status = MealStatus.EATEN)))
        val batch = prep(share("2026-11-16", DINNER), share("2026-11-17", DINNER))
        assertEquals(WhyStays.MAKES_BATCH, whyStays(dinner.copy(prep = batch)))
        assertEquals(WhyStays.PREPPED, meal(1, DINNER, prep = batch, leftover = true).whyStays("2026-11-17", emptyList(), emptyList(), "2026-11-16"))
        assertEquals(WhyStays.SIDE, whyStays(dinner.copy(side = MealSide(9, "Salad", 0.5, 200.0, 10.0, cookPortions = 0.0, prep = prep()))))
        for (stuck in listOf(leftovers.copy(locked = true), leftovers.copy(status = MealStatus.SKIPPED), leftovers.copy(status = MealStatus.EATEN))) {
            assertEquals(WhyStays.LEFTOVERS, whyStays(dinner, after = listOf(stuck)), "its leftovers can't follow: $stuck")
        }
        assertTrue(dinner.canMove("2026-11-16", emptyList(), listOf(leftovers), "2026-11-16"))
        assertFalse(meal(0, LUNCH).canMove("2026-11-16", emptyList(), emptyList(), "2026-11-16"), "only dinners move")
    }
}
