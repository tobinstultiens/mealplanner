package dev.tobins.mealplanner.shopping

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which offer a row of the list goes by when the deal that saves most does not fit what it needs (issue 105): the rule
 * in force, the owner's "Switch it on", and the one from before it.
 */
class RowSavingTest {
    private val today = "2026-11-18"
    private val shops = Store.entries.toSet()

    private fun offer(store: Store, deal: String, share: Double?, packSize: String = "500 g") =
        Offer(store, "Kipfilet $packSize", deal, null, packSize = packSize, validFrom = "2026-11-16", validUntil = "2026-11-22", paidShare = share)

    /** Chicken, [grams] of it at a regular price of €10 for the lot. */
    private fun chicken(grams: Double, vararg offers: Offer) =
        ShoppingItem("i1", "Chicken", grams, null, "Meat", emptyList(), onHand = false, checked = false, offers = offers.toList(), regularPrice = 10.0)

    private val onePlusOne = offer(Store.JUMBO, "1+1 gratis", 0.5)
    private val quarterOff = offer(Store.AH, "25% korting", 0.75, packSize = "300 g")

    @Test
    fun aSmallerOfferThatFitsIsTakenWhenTheDeepestDealWantsMorePacksThanNeeded() {
        assertTrue(PREFER_FITTING_OFFER, "the owner's decision on issue 118")
        // 450 g is one pack of 500 g: "1+1 gratis" would mean buying two. Two packs of 300 g at a quarter off fit, and
        // save €2.50 of €10.
        val row = chicken(450.0, onePlusOne, quarterOff)
        assertEquals(quarterOff, row.bestOffer(shops, today))
        assertEquals(2.5, row.savingToday(shops, today)!!, 1e-9)
        assertEquals(Store.AH, row.savingOffer(shops, today)?.store)
    }

    @Test
    fun theRuleFromBeforeKeptTheDeepestDealAndSavedNothing() {
        val row = chicken(450.0, onePlusOne, quarterOff)
        assertEquals(onePlusOne, row.bestOffer(shops, today, preferFitting = false))
        assertNull(row.savingToday(shops, today, preferFitting = false))
        assertEquals(Store.JUMBO, row.savingOffer(shops, today, preferFitting = false)?.store)
    }

    @Test
    fun theDeepestDealStaysWhenItFitsEitherWay() {
        // A kilo is two packs of 500 g: the "1+1 gratis" is had in full.
        val row = chicken(1000.0, quarterOff, onePlusOne)
        for (preferFitting in listOf(false, true)) {
            assertEquals(onePlusOne, row.bestOffer(shops, today, preferFitting))
            assertEquals(5.0, row.savingToday(shops, today, preferFitting)!!, 1e-9)
        }
    }

    @Test
    fun aDealThatDoesNotFitStaysWhenNothingElseSavesAndFits() {
        assertEquals(onePlusOne, chicken(450.0, onePlusOne).bestOffer(shops, today))
        assertNull(chicken(450.0, onePlusOne).savingToday(shops, today))
        // An offer that fits but can't be valued is no better.
        val unknown = offer(Store.LIDL, "Gratis beanie bij 2 stuks", null)
        assertEquals(onePlusOne, chicken(450.0, unknown, onePlusOne).bestOffer(shops, today))
        // Nor is one at a store not shopped at.
        assertEquals(onePlusOne, chicken(450.0, onePlusOne, quarterOff).bestOffer(setOf(Store.JUMBO), today))
    }

    @Test
    fun aRowWithoutAWeightGoesByTheDeepestDeal() {
        // Nothing says how many packs are needed, so every deal may fit.
        val row = chicken(450.0, onePlusOne, quarterOff).copy(grams = null)
        assertEquals(onePlusOne, row.bestOffer(shops, today))
    }
}
