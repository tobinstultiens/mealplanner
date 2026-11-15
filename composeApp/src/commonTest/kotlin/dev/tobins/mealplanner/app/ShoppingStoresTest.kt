package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShoppingStoresTest {
    private fun offer(store: Store, price: Double?, deal: String? = null) =
        Offer(store, "Product", deal, price, validFrom = "2026-01-01", validUntil = "2026-01-07")

    private fun item(key: String, category: String, vararg offers: Offer, checked: Boolean = false) =
        ShoppingItem(key, key, null, null, category, emptyList(), false, checked, offers.toList())

    private val all = Store.entries.toSet()

    // Walking order: fruit, dairy, tins. Milk is cheaper at AH, pear and cheese at Jumbo, leek at Lidl.
    private val items = listOf(
        item("apple", "Fruit & veg"),
        item("leek", "Fruit & veg", offer(Store.LIDL, 0.99)),
        item("pear", "Fruit & veg", offer(Store.AH, 1.49), offer(Store.JUMBO, 1.29), checked = true),
        item("milk", "Dairy", offer(Store.AH, 1.19), offer(Store.JUMBO, null, "1+1 gratis")),
        item("cheese", "Dairy", offer(Store.JUMBO, 3.0), checked = true),
        item("rice", "Tins", offer(Store.AH, 1.99)),
        item("salt", "Tins"),
    )

    private fun keys(list: List<ShoppingItem>) = list.map { it.key }

    @Test
    fun aPricedOfferBeatsOneWithOnlyADealText() {
        val priced = offer(Store.AH, 2.0)
        assertEquals(priced, cheapestOffer(listOf(offer(Store.JUMBO, null, "1+1 gratis"), priced)))
    }

    @Test
    fun pricedOffersAreComparedByPrice() {
        val cheap = offer(Store.LIDL, 1.1)
        assertEquals(cheap, cheapestOffer(listOf(offer(Store.AH, 1.5), cheap, offer(Store.JUMBO, 1.3))))
    }

    @Test
    fun withoutComparablePricesTheServersOrderDecides() {
        val first = offer(Store.JUMBO, null, "2 voor 3")
        assertEquals(first, cheapestOffer(listOf(first, offer(Store.LIDL, null, "1+1 gratis"))))
        val tie = offer(Store.AH, 1.5)
        assertEquals(tie, cheapestOffer(listOf(tie, offer(Store.LIDL, 1.5))))
        assertNull(cheapestOffer(emptyList()))
    }

    @Test
    fun storesNotInUseAreIgnoredWhenChoosingTheOffer() {
        val pear = items[2]
        assertEquals(Store.JUMBO, pear.bestOffer(all)?.store)
        assertEquals(Store.AH, pear.bestOffer(setOf(Store.AH, Store.LIDL))?.store)
        assertNull(pear.bestOffer(setOf(Store.LIDL)))
    }

    @Test
    fun aRowShowsTheOfferOfAStoreInUseNeverOneOfASwitchedOffStore() {
        val pear = items.first { it.key == "pear" }
        val withoutJumbo = setOf(Store.AH, Store.LIDL)
        assertEquals("AH €1.49", pear.bestOffer(withoutJumbo)?.badgeText(Strings.English))
        // Pear is grouped under AH then, and the badge agrees with the group.
        assertEquals(listOf("pear"), storeOverview(listOf(pear), withoutJumbo).stores.single { it.store == Store.AH }.items.map { it.key })
        // Cheese is on offer at Jumbo only: with Jumbo off it has no badge and sits under Any store.
        val cheese = items.first { it.key == "cheese" }
        assertNull(cheese.bestOffer(withoutJumbo))
        assertEquals(listOf("cheese"), storeOverview(listOf(cheese), withoutJumbo).anyStore.map { it.key })
    }

    @Test
    fun theOverviewHasAGroupPerStoreAndAnyStoreForTheRest() {
        val overview = storeOverview(items, all)
        assertEquals(listOf(Store.AH, Store.JUMBO, Store.LIDL), overview.stores.map { it.store })
        assertEquals(listOf("milk", "rice"), keys(overview.stores[0].items))
        assertEquals(listOf("pear", "cheese"), keys(overview.stores[1].items))
        assertEquals(listOf("leek"), keys(overview.stores[2].items))
        assertEquals(listOf("apple", "salt"), keys(overview.anyStore))
    }

    @Test
    fun anItemOnOfferOnlyAtAStoreNotInUseFallsUnderAnyStore() {
        val overview = storeOverview(items, setOf(Store.AH, Store.JUMBO))
        assertEquals(listOf(Store.AH, Store.JUMBO), overview.stores.map { it.store })
        assertEquals(listOf("apple", "leek", "salt"), keys(overview.anyStore))
    }

    @Test
    fun aStoreWithoutItemsGetsNoGroup() {
        assertEquals(listOf(Store.LIDL), storeOverview(items.filter { it.key == "leek" }, all).stores.map { it.store })
    }

    @Test
    fun theGroupTitleNamesTheStoreAndCountsItsItems() {
        assertEquals("AH · 2 items on offer", storeOverview(items, all).stores[0].title(Strings.English))
        assertEquals("Lidl · 1 item on offer", storeOverview(items, all).stores[2].title(Strings.English))
    }

    @Test
    fun aVisitHoldsTheStoresOfferItemsAndEverythingWithoutOneInWalkingOrder() {
        val visit = storeVisit(items, Store.AH, all, shoppingMode = false)
        assertEquals(listOf("Fruit & veg", "Dairy", "Tins"), visit.sections.map { it.title })
        assertEquals(listOf("apple"), keys(visit.sections[0].open))
        assertEquals(listOf("milk"), keys(visit.sections[1].open))
        assertEquals(listOf("rice", "salt"), keys(visit.sections[2].open))
    }

    @Test
    fun whatIsCheaperAtAnotherStoreIsListedWithWhereAndHowMuch() {
        val elsewhere = storeVisit(items, Store.AH, all, shoppingMode = false).elsewhere
        assertEquals(listOf("leek", "pear", "cheese"), elsewhere.map { it.item.key })
        assertEquals(listOf("Lidl €0.99", "Jumbo €1.29", "Jumbo €3.00"), elsewhere.map { it.where(Strings.English) })
    }

    @Test
    fun aStoreNotInUseNeverTakesItemsAwayFromTheVisit() {
        val visit = storeVisit(items, Store.AH, setOf(Store.AH), shoppingMode = false)
        assertTrue(visit.elsewhere.isEmpty())
        assertEquals(items.size, visit.sections.sumOf { it.open.size })
    }

    @Test
    fun everyItemIsInExactlyOneGroupInEveryView() {
        val everyKey = keys(items).sorted()
        for (shops in listOf(all, setOf(Store.AH, Store.JUMBO), setOf(Store.LIDL), setOf(Store.AH))) {
            val overview = storeOverview(items, shops)
            assertEquals(everyKey, keys(overview.stores.flatMap { it.items } + overview.anyStore).sorted(), "overview with $shops")
            for (store in shops) for (mode in listOf(false, true)) {
                val visit = storeVisit(items, store, shops, mode)
                val shown = visit.sections.flatMap { it.open + it.done } + visit.elsewhere.map { it.item }
                assertEquals(everyKey, keys(shown).sorted(), "visit to $store with $shops, shopping mode $mode")
            }
        }
    }

    /** Regular prices: €4 of milk, €10 of cheese, €2 of rice. Every offer takes a quarter off, the deal-only one half. */
    private val priced = items.map { item ->
        val regular = mapOf("milk" to 4.0, "cheese" to 10.0, "rice" to 2.0)[item.key]
        item.copy(regularPrice = regular, offers = item.offers.map { it.copy(paidShare = if (it.price == null) 0.5 else 0.75) })
    }

    @Test
    fun eachStoreSavesWhatItsOffersTakeOffTheRegularPrices() {
        val overview = storeOverview(priced, all)
        assertEquals(1.0 + 0.5, overview.stores.single { it.store == Store.AH }.saving!!, 1e-9, "milk and rice at AH")
        assertEquals(2.5, overview.stores.single { it.store == Store.JUMBO }.saving!!, 1e-9, "cheese; pear has no regular price")
        assertNull(overview.stores.single { it.store == Store.LIDL }.saving, "leek has no regular price")
        assertEquals(2.5, storeVisit(priced, Store.JUMBO, all, shoppingMode = false).saving!!, 1e-9, "only Jumbo's own offers")
    }

    @Test
    fun theSavingFollowsTheStoresInUse() {
        // Without AH, milk's offer is Jumbo's deal-only one: half of €4 saved there.
        val jumbo = storeOverview(priced, setOf(Store.JUMBO, Store.LIDL)).stores.single { it.store == Store.JUMBO }
        assertEquals(2.0 + 2.5, jumbo.saving!!, 1e-9)
    }

    @Test
    fun noSavingIsShownWhenNoneIsKnownOrItIsLessThanACent() {
        assertNull(storeOverview(items, all).stores.first().saving, "no regular prices from the server (an older one)")
        val tiny = priced.map { if (it.key == "rice") it.copy(regularPrice = 0.01) else it.copy(regularPrice = null) }
        assertNull(savingOf(tiny, all), "a quarter of a cent")
        val rice = priced.single { it.key == "rice" }
        assertEquals(0.5, rice.saving(rice.offers.single())!!, 1e-9)
        assertNull(rice.saving(rice.offers.single().copy(paidShare = null)))
    }

    @Test
    fun tickedItemsStayInTheirGroupWhenShoppingModeSetsThemAside() {
        val visit = storeVisit(items, Store.JUMBO, all, shoppingMode = true)
        val dairy = visit.sections.first { it.title == "Dairy" }
        assertEquals(listOf("cheese"), keys(dairy.done))
        assertEquals(listOf("leek", "milk", "rice"), visit.elsewhere.map { it.item.key })
    }
}
