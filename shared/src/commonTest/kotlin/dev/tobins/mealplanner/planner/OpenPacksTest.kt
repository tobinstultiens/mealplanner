package dev.tobins.mealplanner.planner

import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepMeal
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenPacksTest {
    /** A 200 g tub at 3 euros; a serving takes 50 g of it. */
    private val tub = FreshUse(ingredientId = 1, gramsPerServing = 50.0, pack = Pack(grams = 200.0, euros = 3.0))
    private val uses = listOf(tub)

    private fun assertClose(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-9, "expected $expected, was $actual")

    @Test
    fun theRestOfAnOpenedPackIsWorthItsShareOfThePackPrice() {
        val packs = OpenPacks()
        assertClose(0.0, packs.reusable(day = 0, uses = uses, servings = 1.0))

        packs.cook(day = 0, uses = uses, servings = 1.0)

        // 150 g are left: a serving of 50 g is worth a quarter of 3 euros, four servings only the rest.
        assertClose(0.75, packs.reusable(day = 1, uses = uses, servings = 1.0))
        assertClose(2.25, packs.reusable(day = 1, uses = uses, servings = 4.0))
    }

    @Test
    fun theRestCountsOnTheDayOfTheMealThatOpenedItAndThreeDaysAfterAndNotLater() {
        val packs = OpenPacks()
        packs.cook(day = 1, uses = uses, servings = 1.0)

        assertClose(0.75, packs.reusable(day = 1, uses = uses, servings = 1.0))
        assertClose(0.75, packs.reusable(day = 4, uses = uses, servings = 1.0))
        assertClose(0.0, packs.reusable(day = 5, uses = uses, servings = 1.0))
    }

    @Test
    fun aMealUsingTheRestDoesNotRenewTheWindow() {
        val packs = OpenPacks()
        packs.cook(day = 0, uses = uses, servings = 1.0)
        packs.cook(day = 3, uses = uses, servings = 1.0)

        // Day 6 is three days after the second meal but six after the one that opened the pack.
        assertClose(0.0, packs.reusable(day = 6, uses = uses, servings = 1.0))
    }

    @Test
    fun aMealThatNeedsMoreThanTheRestOpensANewPack() {
        val packs = OpenPacks()
        packs.cook(day = 0, uses = uses, servings = 3.0) // 150 g: 50 g left
        packs.cook(day = 1, uses = uses, servings = 2.0) // 100 g: the 50 g and 50 g of a new pack

        assertClose(2.25, packs.reusable(day = 2, uses = uses, servings = 4.0))
        // The first pack was used up: no rest of it was thrown away.
        assertClose(0.0, packs.restEuros)
    }

    @Test
    fun theRestOfAPackThatLapsesIsThrownAwayAndCounted() {
        val packs = OpenPacks()
        packs.cook(day = 0, uses = uses, servings = 1.0)
        packs.cook(day = 5, uses = uses, servings = 1.0)
        assertClose(2.25, packs.restEuros)

        packs.finish()

        assertClose(4.5, packs.restEuros)
    }

    @Test
    fun aMealThatOpensANewPackWastesTheRestOfItAndOneThatUsesTheRestWastesNothing() {
        val packs = OpenPacks()

        // One serving of 50 g from a new 200 g tub leaves 150 g, 2.25 euros; four servings use all of it.
        assertClose(2.25, packs.wasted(day = 0, uses = uses, servings = 1.0))
        assertClose(0.0, packs.wasted(day = 0, uses = uses, servings = 4.0))

        packs.cook(day = 0, uses = uses, servings = 1.0)

        // The 150 g left cover a serving of 50 g or three; five servings need 250 g: 100 g of a second tub, 100 g left of it.
        assertClose(0.0, packs.wasted(day = 1, uses = uses, servings = 1.0))
        assertClose(1.5, packs.wasted(day = 1, uses = uses, servings = 5.0))
        // A tub past its days is no rest: a serving opens a new one.
        assertClose(2.25, packs.wasted(day = 5, uses = uses, servings = 1.0))
    }

    private fun meal(day: Int, slot: MealSlot, recipeId: Long, leftover: Boolean = false) =
        PlannedMeal(day, slot, recipeId, "Recipe $recipeId", portions = 1.0, kcal = 600.0, proteinG = 40.0, leftover = leftover)

    private val candidates = mapOf(1L to PlanCandidate(1, "Recipe 1", setOf(MealSlot.DINNER), 4, 600.0, 40.0, freshUses = uses))

    @Test
    fun beforeAMealOnlyTheEarlierMealsOpenedPacksAndLeftoversOpenNone() {
        val meals = listOf(
            meal(0, MealSlot.DINNER, 1),
            meal(1, MealSlot.LUNCH, 1, leftover = true),
            meal(2, MealSlot.DINNER, 1),
        )

        // Before the dinner of day 0 nothing was opened; before day 1's lunch the day 0 dinner opened a tub.
        assertClose(0.0, OpenPacks.before(meals, candidates, 0, MealSlot.DINNER).reusable(0, uses, 1.0))
        assertClose(0.75, OpenPacks.before(meals, candidates, 1, MealSlot.LUNCH).reusable(1, uses, 1.0))
        // The leftover lunch of day 1 cooks nothing: before day 1's dinner the tub has its 150 g still.
        assertClose(0.75, OpenPacks.before(meals, candidates, 1, MealSlot.DINNER).reusable(1, uses, 1.0))
        // The dinner of day 2 is not planned yet when day 1's meals are: it has opened nothing.
        assertClose(0.75, OpenPacks.before(meals, candidates, 2, MealSlot.DINNER).reusable(2, uses, 1.0))
    }

    @Test
    fun theRestOfAWholePlanIsWhatItsPacksHaveLeft() {
        val twice = listOf(meal(0, MealSlot.DINNER, 1), meal(6, MealSlot.DINNER, 1))

        // Two tubs, a quarter used each: 150 g of 200 g left in both.
        assertClose(2 * 2.25, OpenPacks.restOf(twice, candidates))
        assertClose(0.0, OpenPacks.restOf(emptyList(), candidates))
    }

    @Test
    fun theMealThatMakesABatchBuysForAllOfItAndItsLaterMealsForNothing() {
        // Four servings made on day 0's lunch and eaten on days 0 to 3: one tub, all of it used.
        val prep = MealPrep(1, "2026-11-16", "2026-11-20", false, (0..3).map { PrepMeal("2026-11-${16 + it}", MealSlot.LUNCH, 1.0, MealStatus.PLANNED) })
        val batch = (0..3).map { meal(it, MealSlot.LUNCH, 1, leftover = it > 0).copy(prep = prep) }

        assertClose(0.0, OpenPacks.restOf(batch, candidates))
        assertClose(0.0, OpenPacks.before(batch, candidates, 1, MealSlot.DINNER).reusable(1, uses, 1.0))
    }

    @Test
    fun onlyRestsUnderHalfOfThePackAreSmallAndEachPackIsJudgedByItself() {
        // Servings of 50 g from a 200 g tub: 3 servings leave 50 g (a quarter), 2 leave 100 g (exactly half, not under).
        fun leftBy(servings: Double) = OpenPacks.smallRestsOf(listOf(meal(0, MealSlot.DINNER, 1).copy(portions = servings)), candidates)

        assertClose(50.0, leftBy(3.0).single().grams)
        assertClose(0.75, leftBy(3.0).single().euros)
        assertTrue(leftBy(2.0).isEmpty(), "half the pack is not under half")
        assertTrue(leftBy(1.0).isEmpty(), "three quarters of it left is a pack mostly unused")
        assertTrue(leftBy(4.0).isEmpty(), "a pack used up leaves nothing")

        // Two packs in the week, days apart: the first leaves 150 g (not small), the second 50 g: only the second counts.
        val twoPacks = listOf(meal(0, MealSlot.DINNER, 1).copy(portions = 1.0), meal(6, MealSlot.DINNER, 1).copy(portions = 3.0))
        assertClose(50.0, OpenPacks.smallRestsOf(twoPacks, candidates).single().grams)

        // Two packs both left small, 50 g each: one line for the ingredient with both added up.
        val bothSmall = listOf(meal(0, MealSlot.DINNER, 1).copy(portions = 3.0), meal(6, MealSlot.DINNER, 1).copy(portions = 3.0))
        assertClose(100.0, OpenPacks.smallRestsOf(bothSmall, candidates).single().grams)
        assertClose(1.5, OpenPacks.smallRestsOf(bothSmall, candidates).single().euros)
    }

    @Test
    fun aRestOfLessThanFiveCentsIsNotTold() {
        val pinch = mapOf(1L to PlanCandidate(1, "Recipe 1", setOf(MealSlot.DINNER), 4, 600.0, 40.0, freshUses = listOf(FreshUse(1, 49.5, Pack(200.0, 3.0)))))

        // 0.5 g of 200 g at 3 euros: 0.75 cents.
        assertTrue(OpenPacks.smallRestsOf(listOf(meal(0, MealSlot.DINNER, 1).copy(portions = 4.0)), pinch).isEmpty())
    }

    @Test
    fun aBatchIsOnePurchaseOfAllItsPortionsAndTheRestFollowsFromThat() {
        // Seven servings made on day 0 and eaten until day 3: 350 g, two tubs, 50 g left of the second.
        val prep = MealPrep(1, "2026-11-16", "2026-11-20", false, (0..6).map { PrepMeal("2026-11-${16 + it % 4}", MealSlot.LUNCH, 1.0, MealStatus.PLANNED) })
        val batch = listOf(meal(0, MealSlot.LUNCH, 1).copy(prep = prep)) + (1..3).map { meal(it, MealSlot.LUNCH, 1, leftover = true).copy(prep = prep) }

        assertClose(50.0, OpenPacks.smallRestsOf(batch, candidates).single().grams)
    }
}
