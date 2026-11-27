package dev.tobins.mealplanner.app.prices

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferExclusion
import dev.tobins.mealplanner.api.OfferSourceStatus
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.PriceVerdict
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.app.shopping.adviceAt
import dev.tobins.mealplanner.app.shopping.chipOffer
import dev.tobins.mealplanner.app.shopping.savingOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** "Not this ingredient" (issue 316) as the screens see it: which exclusions count, and what they take off the lists. */
class NotThisIngredientTest {
    private val today = "2026-11-18"
    private val shops = Store.entries.toSet()

    private fun offer(store: Store, title: String, deal: String = "25% korting", paidShare: Double? = 0.75) =
        Offer(store, title, deal, validFrom = "2026-11-16", validUntil = "2026-11-22", paidShare = paidShare)

    private fun exclusion(store: Store, title: String, ingredientId: Long, name: String = "Curry powder", at: String = "2026-11-17T10:00:00Z") =
        OfferExclusion(store, title, OfferExclusion.titleKey(title), ingredientId, name, at)

    private fun change(store: Store, title: String, ingredientId: Long, excluded: Boolean, at: String = "2026-11-18T09:00:00Z") =
        ExclusionChange(store, title, ingredientId, "Curry powder", excluded, at)

    private fun row(key: String, vararg offers: Offer) =
        ShoppingItem(key, key, 500.0, null, "Other", emptyList(), onHand = false, checked = false, offers = offers.toList(), regularPrice = 4.0)

    @Test
    fun aRowsIngredientIsReadFromItsKey() {
        assertEquals(7L, row("i7").ingredientId)
        assertEquals(7L, row("ahead:i7").ingredientId)
        assertEquals(7L, row("stock:i7").ingredientId)
        // An own item and a recipe line linked to nothing have no ingredient, and so no offer to exclude.
        assertNull(row("x:7").ingredientId)
        assertNull(row("t:kerrie").ingredientId)
    }

    @Test
    fun aWaitingExclusionCountsAtOnceAndAWaitingTakeBackTakesOneOutAtOnce() {
        val curry = exclusion(Store.JUMBO, "Hela curry", 3)
        val venison = exclusion(Store.AH, "Excellent hertenbiefstuk", 4, at = "2026-11-16T10:00:00Z")
        val known = listOf(curry, venison)

        assertSame(known, exclusionsInEffect(known, emptyList()))
        val buns = change(Store.JUMBO, "Kaneelbroodjes", 5, excluded = true)
        val pepsels = change(Store.JUMBO, "Bolletje Pepsels", 5, excluded = true, at = "2026-11-18T09:01:00Z")
        // The newest first, before the server's, as the server lists them.
        assertEquals(
            listOf("Bolletje Pepsels", "Kaneelbroodjes", "Hela curry", "Excellent hertenbiefstuk"),
            exclusionsInEffect(known, listOf(buns, pepsels)).map { it.title },
        )
        // Taken back by another title of the same product (another pack): the same exclusion.
        assertEquals(listOf(venison), exclusionsInEffect(known, listOf(change(Store.JUMBO, "Hela curry 500 g", 3, excluded = false))))
        // Made again while the server still has it: once.
        assertEquals(known, exclusionsInEffect(known, listOf(change(Store.JUMBO, "Hela curry", 3, excluded = true))))
    }

    @Test
    fun anExclusionTakesTheDealOffItsIngredientsRowsOnly() {
        val curry = offer(Store.JUMBO, "Hela curry")
        val otherPack = offer(Store.JUMBO, "Hela curry 250 g", deal = "voor 2,49")
        val ahCurry = offer(Store.AH, "Hela curry")
        val list = ShoppingList(
            "2026-11-16",
            items = listOf(row("i3", curry, otherPack, ahCurry), row("i9", curry), row("x:1")),
            staples = emptyList(),
            buyAhead = listOf(row("ahead:i3", curry)),
            stockUp = listOf(row("stock:i3", curry)),
        )

        val left = list.withoutExcluded(listOf(exclusion(Store.JUMBO, "Hela curry", 3)))

        // Every pack of the product at that store goes; the same title at another store is another product.
        assertEquals(listOf(ahCurry), left.items[0].offers)
        // Another ingredient's match of the same offer is not this one.
        assertEquals(listOf(curry), left.items[1].offers)
        assertEquals(list.items[2], left.items[2])
        assertEquals(emptyList(), left.buyAhead.single().offers)
        assertEquals(emptyList(), left.stockUp.single().offers)
        // An advice row is only worth following on offer, so it goes from the list shown.
        assertEquals(emptyList(), adviceAt(left.stockUp, null, shops, today))
        assertSame(list, list.withoutExcluded(emptyList()))
    }

    @Test
    fun theChipAndItsSavingGoWithTheExcludedDeal() {
        val curry = offer(Store.JUMBO, "Hela curry")
        val list = ShoppingList("2026-11-16", items = listOf(row("i3", curry)), staples = emptyList())
        assertEquals(curry, list.items.single().chipOffer(shops, today))
        assertNotNull(savingOf(list.items, shops, today))

        val left = list.withoutExcluded(listOf(exclusion(Store.JUMBO, "Hela curry", 3)))

        assertNull(left.items.single().chipOffer(shops, today))
        assertNull(savingOf(left.items, shops, today))
    }

    @Test
    fun anExcludedOfferIsNoLongerTheIngredientsOnTheOffersScreen() {
        val curry = offer(Store.JUMBO, "Hela curry").copy(ingredientId = 3, ingredientName = "Curry powder", verdict = PriceVerdict(daysRead = 2, daysNeeded = 56))
        val paprika = offer(Store.AH, "Rode paprika 500 g").copy(ingredientId = 8, ingredientName = "Red bell pepper")
        val state = OffersState(listOf(curry, paprika), listOf(OfferSourceStatus(Store.JUMBO, null, 1, null)))

        val shown = state.withoutExcluded(listOf(exclusion(Store.JUMBO, "Hela curry", 3)))

        // Unmatched, and without the verdict, which the server gives the account's own offers only.
        assertEquals(curry.copy(ingredientId = null, ingredientName = null, verdict = null), shown.offers[0])
        assertEquals(paprika, shown.offers[1])
        assertEquals(state.sources, shown.sources)
    }
}
