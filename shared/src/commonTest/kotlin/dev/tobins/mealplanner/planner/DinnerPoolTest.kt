package dev.tobins.mealplanner.planner

import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.MealSlot.DINNER
import dev.tobins.mealplanner.api.MealSlot.LUNCH
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.SuggestionGround.FAVOURITE
import dev.tobins.mealplanner.api.SuggestionGround.ON_OFFER
import dev.tobins.mealplanner.api.SuggestionGround.USES_WHAT_IS_THERE
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DinnerPoolTest {
    private val targets = DailyTargets(kcal = 2000, proteinG = 150, fibreG = 30)

    /** A four-serving dinner of 650 kcal a serving at 4 euros, neither on offer nor rated. */
    private fun dinner(id: Long, tag: String = "T$id") = PlanCandidate(
        id, "Recipe $id", setOf(DINNER), 4, kcal = 650.0, proteinG = 45.0, tags = listOf(tag),
        costPerServing = 4.0, regularCostPerServing = 4.0,
    )

    /** On offer: half the price, which saves 2 euros a serving. */
    private fun onOffer(id: Long) = dinner(id).copy(offerCount = 1, costPerServing = 2.0)

    private fun rated(id: Long) = dinner(id).copy(rating = 5)

    private fun onHand(id: Long) = dinner(id).copy(onHandCount = 1)

    /** A 200 g tub at 3 euros, of which a serving takes 50 g. */
    private val tub = FreshUse(ingredientId = 1, gramsPerServing = 50.0, pack = Pack(grams = 200.0, euros = 3.0))

    private fun pool(candidates: List<PlanCandidate>, picked: List<PlanCandidate> = emptyList(), kept: List<PlannedMeal> = emptyList(), weeks: Map<Long, Int> = emptyMap()) =
        DinnerPool.Week(PlanRequest(targets, candidates), kept, picked, weeks)

    @Test
    fun withEnoughOffersToFillThePageItStillHoldsRecipesOfTheOtherGrounds() {
        val offers = (1L..10L).map(::onOffer)
        val candidates = offers + rated(20) + rated(21) + onHand(30)
        val week = pool(candidates)
        // The offers are the best by the planner's score: their price is the median, the others' twice that.
        assertTrue(offers.all { week.rate(it).score > week.rate(rated(20)).score })

        val page = week.suggest(hidden = emptySet(), seen = emptySet(), count = 6)

        assertEquals(6, page.suggestions.size)
        assertTrue(page.more)
        assertEquals(
            listOf(ON_OFFER, USES_WHAT_IS_THERE, FAVOURITE, ON_OFFER, FAVOURITE, ON_OFFER),
            page.suggestions.map { it.grounds.single() },
            "the grounds in turn, offers first, while each has recipes left",
        )
        assertEquals(listOf(1L, 30L, 20L, 2L, 21L, 3L), page.suggestions.map { it.candidate.recipeId })
    }

    @Test
    fun aRecipeWithoutAGroundIsNotSuggestedAndOneOfSeveralIsTakenOnce() {
        val both = onOffer(1).copy(rating = 5)
        val page = pool(listOf(both, dinner(2), rated(3))).suggest(emptySet(), emptySet(), count = 6)

        assertEquals(listOf(1L, 3L), page.suggestions.map { it.candidate.recipeId })
        assertEquals(listOf(ON_OFFER, FAVOURITE), page.suggestions.first().grounds)
        assertFalse(page.more)
    }

    @Test
    fun aRecipeNotEatenForThreeWeeksIsAFavouriteAndOneEatenLastWeekOrNeverIsNot() {
        val week = pool(listOf(dinner(1), dinner(2), dinner(3)), weeks = mapOf(1L to 3, 2L to 2))

        assertEquals(listOf(FAVOURITE), week.rate(dinner(1)).grounds)
        assertEquals(3, week.rate(dinner(1)).weeksSinceEaten)
        assertEquals(emptyList(), week.rate(dinner(2)).grounds)
        assertEquals(emptyList(), week.rate(dinner(3)).grounds)
    }

    @Test
    fun anOfferThatSavesLessThanFiveCentsAServingIsNoGround() {
        val pinch = dinner(1).copy(offerCount = 1, costPerServing = 3.97)

        assertEquals(emptyList(), pool(listOf(pinch)).rate(pinch).grounds)
        assertEquals(listOf(ON_OFFER), pool(listOf(pinch)).rate(pinch.copy(costPerServing = null)).grounds, "without prices an offer counts")
    }

    @Test
    fun whatWasChosenHiddenOrSeenIsNotSuggested() {
        val candidates = (1L..8L).map(::onOffer)
        val week = pool(candidates, picked = listOf(onOffer(1)))

        val first = week.suggest(hidden = setOf(2L), seen = emptySet(), count = 3)
        val next = week.suggest(hidden = setOf(2L), seen = first.suggestions.map { it.candidate.recipeId }.toSet(), count = 3)

        assertEquals(listOf(3L, 4L, 5L), first.suggestions.map { it.candidate.recipeId })
        assertEquals(listOf(6L, 7L, 8L), next.suggestions.map { it.candidate.recipeId }, "the next ones, none shown before")
        assertFalse(next.more)
    }

    @Test
    fun aRecipeOfABatchPreppedThisWeekComesOnlyWhenNoOtherIsLeft() {
        val batch = PlannedMeal(1, DINNER, 1, "Recipe 1", 1.0, 650.0, 45.0, locked = true, prep = dev.tobins.mealplanner.api.MealPrep(1, "2026-11-17", "2026-11-20", false, emptyList()))
        val candidates = listOf(onOffer(1), onOffer(2))

        assertEquals(listOf(2L), pool(candidates, kept = listOf(batch)).suggest(emptySet(), emptySet(), 6).suggestions.map { it.candidate.recipeId })
        assertEquals(listOf(1L), pool(candidates, kept = listOf(batch)).suggest(emptySet(), setOf(2L), 6).suggestions.map { it.candidate.recipeId })
    }

    @Test
    fun afterAPickThatOpensAFreshPackARecipeThatUsesItsRestRanksHigherAndSaysWhy() {
        val opener = onOffer(1).copy(freshUses = listOf(tub))
        val finisher = dinner(2).copy(freshUses = listOf(tub))
        val candidates = listOf(opener, finisher) + (3L..9L).map(::rated)
        fun rank(week: DinnerPool.Week) = week.suggest(emptySet(), emptySet(), count = 20).suggestions.indexOfFirst { it.candidate.recipeId == 2L }

        val before = pool(candidates)
        val after = pool(candidates, picked = listOf(opener))

        assertTrue(after.rate(finisher).score > before.rate(finisher).score)
        assertEquals(-1, rank(before), "nothing to say for it before: it has no ground")
        assertEquals(0, rank(after), "the only recipe that uses what is there, so first in its turn")
        val rated = after.rate(finisher)
        assertEquals(listOf(USES_WHAT_IS_THERE), rated.grounds)
        assertEquals(listOf(1L to 1L), rated.packRests.map { (ingredient, pick) -> ingredient to pick.recipeId })
        assertTrue(after.suggest(emptySet(), emptySet(), count = 20).suggestions.none { it.candidate.recipeId == 1L }, "the pick is not suggested again")
    }

    @Test
    fun everyChosenDinnerGetsAFreeDayWithItsLeftoverLunchAndTheKeptMealsStay() {
        val kept = listOf(
            PlannedMeal(2, DINNER, 50, "Locked", 1.0, 700.0, 40.0, locked = true),
            PlannedMeal(4, DINNER, 51, "Eaten", 1.0, 700.0, 40.0, status = MealStatus.EATEN),
            PlannedMeal(4, LUNCH, 52, "Kept lunch", 1.0, 600.0, 40.0, locked = true),
        )
        val picks = (1L..5L).map(::dinner)
        val days = listOf(0, 1, 3, 5, 6)

        val placed = DinnerPool.place(picks, days, kept, emptyMap(), PlanRequest(targets, picks))

        val dinners = placed.filter { it.slot == DINNER }
        assertEquals(picks.map { it.recipeId }.toSet(), dinners.map { it.recipeId }.toSet(), "none dropped")
        assertEquals(days.toSet(), dinners.map { it.day }.toSet())
        assertTrue(dinners.all { it.locked })
        val lunches = placed.filter { it.slot == LUNCH }
        assertTrue(lunches.all { it.leftover && !it.locked })
        // None in place of the kept Friday lunch after Thursday's dinner, nor after Sunday's, the week's last day.
        assertEquals(listOf(1, 2, 6), lunches.map { it.day }.sorted())
        for (lunch in lunches) assertEquals(dinners.single { it.day == lunch.day - 1 }.recipeId, lunch.recipeId, "the leftovers of the dinner before")
    }

    @Test
    fun dinnersSharingAFreshPackLandWithinTheDaysItKeeps() {
        val a = dinner(1, tag = "A").copy(freshUses = listOf(tub))
        val b = dinner(2, tag = "B").copy(freshUses = listOf(tub))
        val others = (3L..5L).map { dinner(it) }
        // Chosen in an order that would put the two first and last on the days left.
        val picks = listOf(a) + others + b
        val kept = listOf(PlannedMeal(1, DINNER, 50, "Locked", 1.0, 700.0, 40.0, locked = true), PlannedMeal(3, DINNER, 51, "Locked", 1.0, 700.0, 40.0, locked = true))
        val days = listOf(0, 2, 4, 5, 6)

        val placed = DinnerPool.place(picks, days, kept, emptyMap(), PlanRequest(targets, picks))

        val dayOf = placed.filter { it.slot == DINNER }.associate { it.recipeId to it.day }
        assertTrue(abs(dayOf.getValue(1) - dayOf.getValue(2)) <= OpenPacks.WINDOW_DAYS, "within the days the tub keeps: $dayOf")
        val packs = picks.associateBy { it.recipeId }
        val inPickOrder = picks.zip(days).map { (pick, day) -> WeekPlanner.mealOf(pick, day, DINNER, targets, PortionRange()) }
        assertTrue(OpenPacks.restOf(placed, packs) < OpenPacks.restOf(inPickOrder, packs), "less rest than on the days in the order chosen")
    }

    @Test
    fun withAsManyDinnersAsDaysTheOneWithLeastLeftoversGoesOnSunday() {
        val big = dinner(1).copy(servings = 8)
        val single = dinner(2).copy(servings = 1, kcal = 760.0)
        val picks = listOf(single, big)

        val placed = DinnerPool.place(picks, listOf(5, 6), emptyList(), emptyMap(), PlanRequest(targets, picks))

        assertEquals(mapOf(1L to 5, 2L to 6), placed.filter { it.slot == DINNER }.associate { it.recipeId to it.day })
        assertEquals(listOf(6 to 1L), placed.filter { it.slot == LUNCH }.map { it.day to it.recipeId })
    }
}
