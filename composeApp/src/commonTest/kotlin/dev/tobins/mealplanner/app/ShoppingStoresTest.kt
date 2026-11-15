package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShoppingStoresTest {
    private val today = "2026-01-02"

    private fun offer(store: Store, price: Double?, deal: String? = null, share: Double? = null) =
        Offer(store, "Product", deal, price, validFrom = "2026-01-01", validUntil = "2026-01-07", paidShare = share)

    private fun item(key: String, category: String, vararg offers: Offer, checked: Boolean = false) =
        ShoppingItem(key, key, null, null, category, emptyList(), false, checked, offers.toList())

    private val all = Store.entries.toSet()

    // Walking order: fruit, dairy, tins. Milk saves most at AH (the tie with Jumbo goes to the first offer), pear and cheese at Jumbo, leek at Lidl.
    private val items = listOf(
        item("apple", "Fruit & veg"),
        item("leek", "Fruit & veg", offer(Store.LIDL, 0.99, share = 0.75)),
        item("pear", "Fruit & veg", offer(Store.AH, 1.49, share = 0.8), offer(Store.JUMBO, 1.29, share = 0.7), checked = true),
        item("milk", "Dairy", offer(Store.AH, 1.19, share = 0.5), offer(Store.JUMBO, null, "1+1 gratis", share = 0.5)),
        item("cheese", "Dairy", offer(Store.JUMBO, 3.0, share = 0.75), checked = true),
        item("rice", "Tins", offer(Store.AH, 1.99, share = 0.75)),
        item("salt", "Tins"),
    )

    private fun keys(list: List<ShoppingItem>) = list.map { it.key }

    @Test
    fun theOfferThatSavesMostWinsNotTheCheaperPack() {
        val chicken = item("chicken", "Meat", offer(Store.AH, 3.49, share = 0.9), offer(Store.JUMBO, 8.99, share = 0.7))
        assertEquals(Store.JUMBO, chicken.bestOffer(all, today)?.store)
        assertEquals(listOf("chicken"), keys(storeOverview(listOf(chicken), all, today).stores.single { it.store == Store.JUMBO }.items))
    }

    @Test
    fun anOfferWithoutAShareKeepsItsBadgeButNotItsStoreGroup() {
        val bread = item("bread", "Bakery", offer(Store.LIDL, 1.0))
        assertEquals("Lidl €1.00", bread.bestOffer(all, today)?.badgeText(Strings.English, today))
        val overview = storeOverview(listOf(bread), all, today)
        assertTrue(overview.stores.isEmpty())
        assertEquals(listOf("bread"), keys(overview.anyStore))
        val visit = storeVisit(listOf(bread), Store.AH, all, today, shoppingMode = false)
        assertTrue(visit.elsewhere.isEmpty())
        assertEquals(listOf("bread"), keys(visit.sections.single().open))
    }

    @Test
    fun anOfferThatSavesBeatsAnotherStoresOfferThatSavesNothing() {
        val rice = item("rice", "Tins", offer(Store.AH, 0.5), offer(Store.JUMBO, 1.5, share = 0.9))
        assertEquals(listOf("rice"), keys(storeOverview(listOf(rice), all, today).stores.single().items))
        assertEquals(Store.JUMBO, storeOverview(listOf(rice), all, today).stores.single().store)
    }

    @Test
    fun storesNotInUseAreIgnoredWhenChoosingTheOffer() {
        val pear = items[2]
        assertEquals(Store.JUMBO, pear.bestOffer(all, today)?.store)
        assertEquals(Store.AH, pear.bestOffer(setOf(Store.AH, Store.LIDL), today)?.store)
        assertNull(pear.bestOffer(setOf(Store.LIDL), today))
    }

    @Test
    fun aRowShowsTheOfferOfAStoreInUseNeverOneOfASwitchedOffStore() {
        val pear = items.first { it.key == "pear" }
        val withoutJumbo = setOf(Store.AH, Store.LIDL)
        assertEquals("AH €1.49", pear.bestOffer(withoutJumbo, today)?.badgeText(Strings.English, today))
        // Pear is grouped under AH then, and the badge agrees with the group.
        assertEquals(listOf("pear"), storeOverview(listOf(pear), withoutJumbo, today).stores.single { it.store == Store.AH }.items.map { it.key })
        // Cheese is on offer at Jumbo only: with Jumbo off it has no badge and sits under Any store.
        val cheese = items.first { it.key == "cheese" }
        assertNull(cheese.bestOffer(withoutJumbo, today))
        assertEquals(listOf("cheese"), storeOverview(listOf(cheese), withoutJumbo, today).anyStore.map { it.key })
    }

    @Test
    fun theOverviewHasAGroupPerStoreAndAnyStoreForTheRest() {
        val overview = storeOverview(items, all, today)
        assertEquals(listOf(Store.AH, Store.JUMBO, Store.LIDL), overview.stores.map { it.store })
        assertEquals(listOf("milk", "rice"), keys(overview.stores[0].items))
        assertEquals(listOf("pear", "cheese"), keys(overview.stores[1].items))
        assertEquals(listOf("leek"), keys(overview.stores[2].items))
        assertEquals(listOf("apple", "salt"), keys(overview.anyStore))
    }

    @Test
    fun anItemOnOfferOnlyAtAStoreNotInUseFallsUnderAnyStore() {
        val overview = storeOverview(items, setOf(Store.AH, Store.JUMBO), today)
        assertEquals(listOf(Store.AH, Store.JUMBO), overview.stores.map { it.store })
        assertEquals(listOf("apple", "leek", "salt"), keys(overview.anyStore))
    }

    @Test
    fun aStoreWithoutItemsGetsNoGroup() {
        assertEquals(listOf(Store.LIDL), storeOverview(items.filter { it.key == "leek" }, all, today).stores.map { it.store })
    }

    @Test
    fun theGroupTitleNamesTheStoreAndCountsItsItems() {
        assertEquals("AH · 2 items on offer", storeOverview(items, all, today).stores[0].title(Strings.English))
        assertEquals("Lidl · 1 item on offer", storeOverview(items, all, today).stores[2].title(Strings.English))
    }

    @Test
    fun aVisitHoldsTheStoresOfferItemsAndEverythingWithoutOneInWalkingOrder() {
        val visit = storeVisit(items, Store.AH, all, today, shoppingMode = false)
        assertEquals(listOf("Fruit & veg", "Dairy", "Tins"), visit.sections.map { it.title })
        assertEquals(listOf("apple"), keys(visit.sections[0].open))
        assertEquals(listOf("milk"), keys(visit.sections[1].open))
        assertEquals(listOf("rice", "salt"), keys(visit.sections[2].open))
    }

    @Test
    fun whatIsCheaperAtAnotherStoreIsListedWithWhereAndHowMuch() {
        val elsewhere = storeVisit(items, Store.AH, all, today, shoppingMode = false).elsewhere
        assertEquals(listOf("leek", "pear", "cheese"), elsewhere.map { it.item.key })
        assertEquals(listOf("Lidl €0.99", "Jumbo €1.29", "Jumbo €3.00"), elsewhere.map { it.where(Strings.English, today) })
    }

    @Test
    fun aStoreNotInUseNeverTakesItemsAwayFromTheVisit() {
        val visit = storeVisit(items, Store.AH, setOf(Store.AH), today, shoppingMode = false)
        assertTrue(visit.elsewhere.isEmpty())
        assertEquals(items.size, visit.sections.sumOf { it.open.size })
    }

    @Test
    fun everyItemIsInExactlyOneGroupInEveryView() {
        val everyKey = keys(items).sorted()
        for (shops in listOf(all, setOf(Store.AH, Store.JUMBO), setOf(Store.LIDL), setOf(Store.AH))) {
            val overview = storeOverview(items, shops, today)
            assertEquals(everyKey, keys(overview.stores.flatMap { it.items } + overview.anyStore).sorted(), "overview with $shops")
            for (store in shops) for (mode in listOf(false, true)) {
                val visit = storeVisit(items, store, shops, today, mode)
                val shown = visit.sections.flatMap { it.open + it.done } + visit.elsewhere.map { it.item }
                assertEquals(everyKey, keys(shown).sorted(), "visit to $store with $shops, shopping mode $mode")
            }
        }
    }

    /** Regular prices: €4 of milk, €10 of cheese, €2 of rice. Offers take a quarter off, the milk's half. */
    private val priced = items.map { item ->
        val regular = mapOf("milk" to 4.0, "cheese" to 10.0, "rice" to 2.0)[item.key]
        item.copy(regularPrice = regular)
    }

    @Test
    fun eachStoreSavesWhatItsOffersTakeOffTheRegularPrices() {
        val overview = storeOverview(priced, all, today)
        assertEquals(2.0 + 0.5, overview.stores.single { it.store == Store.AH }.saving!!, 1e-9, "milk and rice at AH")
        assertEquals(2.5, overview.stores.single { it.store == Store.JUMBO }.saving!!, 1e-9, "cheese; pear has no regular price")
        assertNull(overview.stores.single { it.store == Store.LIDL }.saving, "leek has no regular price")
        assertEquals(2.5, storeVisit(priced, Store.JUMBO, all, today, shoppingMode = false).saving!!, 1e-9, "only Jumbo's own offers")
    }

    @Test
    fun theSavingFollowsTheStoresInUse() {
        // Without AH, milk's offer is Jumbo's deal-only one: half of €4 saved there.
        val jumbo = storeOverview(priced, setOf(Store.JUMBO, Store.LIDL), today).stores.single { it.store == Store.JUMBO }
        assertEquals(2.0 + 2.5, jumbo.saving!!, 1e-9)
    }

    @Test
    fun noSavingIsShownWhenNoneIsKnownOrItIsLessThanACent() {
        assertNull(storeOverview(items, all, today).stores.first().saving, "no regular prices from the server (an older one)")
        val tiny = priced.map { if (it.key == "rice") it.copy(regularPrice = 0.01) else it.copy(regularPrice = null) }
        assertNull(savingOf(tiny, all, today), "a quarter of a cent")
        val rice = priced.single { it.key == "rice" }
        assertEquals(0.5, rice.saving(rice.offers.single())!!, 1e-9)
        assertNull(rice.saving(rice.offers.single().copy(paidShare = null)))
    }

    @Test
    fun tickedItemsStayInTheirGroupWhenShoppingModeSetsThemAside() {
        val visit = storeVisit(items, Store.JUMBO, all, today, shoppingMode = true)
        val dairy = visit.sections.first { it.title == "Dairy" }
        assertEquals(listOf("cheese"), keys(dairy.done))
        assertEquals(listOf("leek", "milk", "rice"), visit.elsewhere.map { it.item.key })
    }

    // Monday 16 November: AH's week runs until Sunday, Lidl's second week starts on Thursday.
    private val monday = "2026-11-16"

    private fun dated(store: Store, price: Double, share: Double, from: String, until: String) =
        Offer(store, "Product", null, price, validFrom = from, validUntil = until, paidShare = share)

    @Test
    fun anEndedOfferIsIgnoredEverywhere() {
        val last = item("pea", "Tins", dated(Store.JUMBO, 1.0, 0.5, "2026-11-04", "2026-11-10")).copy(regularPrice = 2.0)
        assertNull(last.bestOffer(all, monday))
        assertNull(last.savingOffer(all, monday))
        assertNull(savingOf(listOf(last), all, monday))
        assertEquals(listOf("pea"), keys(storeOverview(listOf(last), all, monday).anyStore))
        assertEquals("", last.dealText(all, monday, Strings.English))

        // It does not decide the store either: the valid AH offer is the one used.
        val both = item("pea", "Tins", dated(Store.JUMBO, 1.0, 0.2, "2026-11-04", "2026-11-10"), dated(Store.AH, 1.5, 0.75, "2026-11-16", "2026-11-22"))
        assertEquals(Store.AH, both.bestOffer(all, monday)?.store)
        assertEquals(listOf(Store.AH), storeOverview(listOf(both), all, monday).stores.map { it.store })
    }

    @Test
    fun anOfferThatStartsLaterShowsItsDayButSavesNothingToday() {
        val later = dated(Store.LIDL, 1.79, 0.5, "2026-11-19", "2026-11-25")
        val feta = item("feta", "Dairy", later).copy(regularPrice = 3.0)
        assertEquals(later, feta.bestOffer(all, monday))
        assertEquals("Lidl €1.79\nfrom Thu", later.badgeText(Strings.English, monday))
        assertEquals("Lidl €1,79\nvanaf do", later.badgeText(Strings.Dutch, monday))
        assertEquals("Lidl €1.79", later.badgeText(Strings.English, "2026-11-19"), "on the day it starts it is plain")
        assertEquals("Lidl €1.79\nfrom 23 Nov", later.copy(validFrom = "2026-11-23").badgeText(Strings.English, monday), "a week or more away says the date")
        // It stays under its store, but what it saves is not counted before it starts.
        val overview = storeOverview(listOf(feta), all, monday)
        assertEquals(listOf(Store.LIDL), overview.stores.map { it.store })
        assertNull(overview.stores.single().saving)
        assertNull(storeVisit(listOf(feta), Store.LIDL, all, monday, shoppingMode = false).saving)
        assertEquals(1.5, savingOf(listOf(feta), all, "2026-11-19")!!, 1e-9)
    }

    @Test
    fun anOfferValidTodayDecidesTheStoreEvenWhenABetterOneStartsLater() {
        val ah = dated(Store.AH, 2.0, 0.8, "2026-11-16", "2026-11-22")
        val lidl = dated(Store.LIDL, 1.0, 0.4, "2026-11-19", "2026-11-25")
        val milk = item("milk", "Dairy", ah, lidl).copy(regularPrice = 2.5)
        assertEquals(ah, milk.bestOffer(all, monday))
        assertEquals(listOf(Store.AH), storeOverview(listOf(milk), all, monday).stores.map { it.store })
        assertEquals(0.5, savingOf(listOf(milk), all, monday)!!, 1e-9)
        // The Lidl deal is still there to read, with its day.
        assertEquals("AH: €2.00 (Product)\nLidl: €1.00 from Thu (Product)", milk.dealText(all, monday, Strings.English))
    }

    private fun packItem(grams: Double?, pieces: Double?, offer: Offer, otherAmounts: List<String> = emptyList()) =
        ShoppingItem("chicken", "Chicken", grams, pieces, "Meat", emptyList(), false, false, listOf(offer), regularPrice = 4.0, otherAmounts = otherAmounts)

    private fun packOffer(packSize: String?, deal: String?, share: Double = 0.5) =
        Offer(Store.AH, "Chicken", deal, null, packSize = packSize, validFrom = "2026-01-01", validUntil = "2026-01-07", paidShare = share)

    @Test
    fun theRowSaysWhatToPickUpInBothLanguages() {
        val two = packItem(1400.0, null, packOffer("700 g", "25% korting", 0.75))
        assertEquals("2 × 700 g", two.packsToBuy(two.offers.single())?.text(Strings.English))
        val kilo = packItem(1000.0, null, packOffer("1,5 kg", "25% korting", 0.75))
        assertEquals("1 × 1,5 kg", kilo.packsToBuy(kilo.offers.single())?.text(Strings.Dutch))
        val tight = packItem(450.0, null, packOffer("500 g", "1+1 gratis"))
        assertEquals("1 × 500 g · deal needs 2", tight.packsToBuy(tight.offers.single())?.text(Strings.English))
        assertEquals("1 × 500 g · actie vraagt 2", tight.packsToBuy(tight.offers.single())?.text(Strings.Dutch))
        val pieces = packItem(null, 4.0, packOffer("3 stuks", null, 0.75))
        assertEquals("2 × 3 pieces", pieces.packsToBuy(pieces.offers.single())?.text(Strings.English))
        assertEquals("2 × 3 stuks", pieces.packsToBuy(pieces.offers.single())?.text(Strings.Dutch))
    }

    @Test
    fun theRowSaysHowMuchIsOnHandAndWhatIsLeftToBuy() {
        val offer = packOffer("700 g", "25% korting", 0.75)
        fun stocked(left: Double?, grams: Double? = 1400.0) =
            packItem(grams, null, offer).copy(onHand = true, onHandGrams = left)

        // Part of it at home: what is left to buy, and the packs for that alone.
        val part = stocked(800.0)
        assertEquals("800 g on hand, buy 600 g", part.onHandText(Strings.English))
        assertEquals("800 g in huis, koop 600 g", part.onHandText(Strings.Dutch))
        assertEquals("1 × 700 g", part.packsStillToBuy(offer)?.text(Strings.English))
        // All of it at home: enough, and nothing to pick up.
        val enough = stocked(1500.0)
        assertEquals("1,5 kg in huis: genoeg", enough.onHandText(Strings.Dutch))
        assertEquals("1.5 kg on hand: enough", enough.onHandText(Strings.English))
        assertNull(enough.packsStillToBuy(offer))
        assertEquals("1.4 kg on hand: enough", stocked(1399.5).onHandText(Strings.English), "half a gram short is nothing to buy")
        // The amount at home is not known (put on hand by hand): no number at all, and the packs for the whole need.
        val unknown = stocked(null)
        assertNull(unknown.onHandText(Strings.English))
        assertEquals("2 × 700 g", unknown.packsStillToBuy(offer)?.text(Strings.English))
        // The need can't be weighed in full: what is at home, without a sum.
        assertEquals("300 g on hand", stocked(300.0, grams = null).onHandText(Strings.English))
        // Ticked: what it said then counted the stock in, so no second amount and the plain name; packs
        // for the whole need as before. A row that needs more says the one amount still to buy.
        val ticked = stocked(800.0).copy(checked = true)
        assertNull(ticked.onHandText(Strings.English))
        assertEquals("Chicken", ticked.nameText(Strings.English))
        assertEquals("2 × 700 g", ticked.packsStillToBuy(offer)?.text(Strings.English))
        val grown = stocked(300.0).copy(checked = true, boughtGrams = 800.0)
        assertEquals("600 g more (800 g bought)", grown.moreNeeded()?.text(Strings.English), "the one number still to buy")
        assertNull(grown.onHandText(Strings.English))
        // The name asks only when nobody knows how much there is, ticked or not.
        assertEquals("Chicken  (on hand?)", unknown.nameText(Strings.English))
        assertEquals("Chicken  (in huis?)", unknown.copy(checked = true).nameText(Strings.Dutch))
        assertEquals("Chicken", part.nameText(Strings.English))
        // Not on hand: nothing, whatever the amount says.
        assertNull(stocked(800.0).copy(onHand = false).onHandText(Strings.English))
        assertEquals("2 × 700 g", stocked(800.0).copy(onHand = false).packsStillToBuy(offer)?.text(Strings.English))
    }

    @Test
    fun theRowGuessesNothingWithoutAWeighedAmountOrAReadablePack() {
        assertNull(packItem(null, null, packOffer("500 g", null)).let { it.packsToBuy(it.offers.single()) })
        val unweighed = packItem(null, 2.0, packOffer("500 g", null), otherAmounts = listOf("1 bag"))
        assertNull(unweighed.packsToBuy(unweighed.offers.single()))
        assertNull(packItem(400.0, null, packOffer(null, null)).let { it.packsToBuy(it.offers.single()) })
    }

    @Test
    fun aDealThatDoesNotFitSavesNothingInTheSums() {
        val fits = packItem(1000.0, null, packOffer("500 g", "1+1 gratis"))
        val tooMuch = packItem(450.0, null, packOffer("500 g", "1+1 gratis"))
        assertEquals(2.0, savingOf(listOf(fits), all, today))
        assertNull(savingOf(listOf(tooMuch), all, today))
        assertEquals(2.0, savingOf(listOf(fits, tooMuch), all, today))
        // Without a readable pack nothing can be said against the saving.
        assertEquals(2.0, savingOf(listOf(packItem(450.0, null, packOffer(null, "1+1 gratis"))), all, today))
    }

    @Test
    fun theSnackbarAddsThePackSizeUnlessTheTitleHasIt() {
        val plain = packItem(450.0, null, packOffer("500 g", "1+1 gratis"))
        assertTrue(plain.dealText(all, today, Strings.English).endsWith("(Chicken, 500 g)"))
        val named = packItem(450.0, null, packOffer("500 g", "1+1 gratis").copy(title = "Kip 500 g"))
        assertTrue(named.dealText(all, today, Strings.English).endsWith("(Kip 500 g)"))
    }
}
