package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.shopping.bestOffer
import dev.tobins.mealplanner.shopping.packsToBuy
import dev.tobins.mealplanner.shopping.saving
import dev.tobins.mealplanner.shopping.savingOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals(emptyList(), last.dealOffers(all, monday))

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
        fun ShoppingItem.headings(strings: Strings) = dealOffers(all, monday).map { it.dealHeading(monday, strings) }
        assertEquals(listOf("AH: €2.00", "Lidl: €1.00 from Thu until 25 Nov"), milk.headings(Strings.English))
        // Within the week the end is a day name too, in either language.
        val short = milk.copy(offers = listOf(dated(Store.LIDL, 1.0, 0.4, "2026-11-19", "2026-11-22")))
        assertEquals(listOf("Lidl: €1.00 from Thu until Sun"), short.headings(Strings.English))
        assertEquals(listOf("Lidl: €1,00 vanaf do t/m zo"), short.headings(Strings.Dutch))
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
    fun aSmallerOfferThatFitsTakesTheRowWhenTheDeepestDealWantsTwoPacks() {
        // Issue 105, switched on by the owner: 450 g is one pack of 500 g, so Jumbo's "1+1 gratis" saves nothing on it;
        // AH's 300 g at a quarter off does, so the row goes by AH's: its chip, its packs and its store group.
        val jumbo = packOffer("500 g", "1+1 gratis").copy(store = Store.JUMBO)
        val ah = packOffer("300 g", "25% korting", 0.75)
        val row = packItem(450.0, null, jumbo).copy(offers = listOf(jumbo, ah))
        assertEquals(ah, row.chipOffer(all, "2026-01-02"))
        assertEquals("2 × 300 g", row.packsToBuy(ah)?.text(Strings.English))
        val overview = storeOverview(listOf(row), all, "2026-01-02")
        assertEquals(listOf(Store.AH), overview.stores.map { it.store })
        assertEquals(1.0, overview.stores.single().saving!!, 1e-9)
    }

    @Test
    fun aStockUpRowOfADealForTwoPacksSaysTwoPacksAndCountsItsSaving() {
        // As the server advises it: both packs' weight and the regular price of both.
        val offer = packOffer("1 kg", "1+1 gratis")
        val row = packItem(2000.0, null, offer).copy(regularPrice = 8.0)
        assertEquals("2 × 1 kg", row.packsToBuy(offer)?.text(Strings.English))
        assertEquals(4.0, savingOf(listOf(row), setOf(Store.AH), "2026-01-02")!!, 1e-9)
    }

    @Test
    fun theRowSaysHowMuchIsOnHandAndWhatIsLeftToBuy() {
        val offer = packOffer("700 g", "25% korting", 0.75)
        // As the server sends it with nothing eaten yet: the meals to come need the whole week's amount.
        fun stocked(left: Double?, grams: Double? = 1400.0) = packItem(grams, null, offer).copy(onHand = true, onHandGrams = left, toComeGrams = grams)

        // Part of it at home: what is left to buy, and the packs for that alone.
        val part = stocked(800.0)
        assertEquals("800 g on hand, buy 600 g", part.toBuyText(Strings.English))
        assertEquals("800 g in huis, koop 600 g", part.toBuyText(Strings.Dutch))
        assertEquals("1 × 700 g", part.packsStillToBuy(offer)?.text(Strings.English))
        // All of it at home: enough, and nothing to pick up.
        val enough = stocked(1500.0)
        assertEquals("1,5 kg in huis: genoeg", enough.toBuyText(Strings.Dutch))
        assertEquals("1.5 kg on hand: enough", enough.toBuyText(Strings.English))
        assertNull(enough.packsStillToBuy(offer))
        assertEquals("1.4 kg on hand: enough", stocked(1399.5).toBuyText(Strings.English), "half a gram short is nothing to buy")
        // The amount at home is not known (put on hand by hand): no number at all, and the packs for the whole need.
        val unknown = stocked(null)
        assertNull(unknown.toBuyText(Strings.English))
        assertEquals("2 × 700 g", unknown.packsStillToBuy(offer)?.text(Strings.English))
        // The need can't be weighed in full: what is at home, without a sum.
        assertEquals("300 g on hand", stocked(300.0, grams = null).toBuyText(Strings.English))
        // Ticked: what it said then counted the stock in, so no second amount and the plain name, and
        // no packs, as what was bought is not known in packs. A row that needs more says the one
        // amount still to buy.
        val ticked = stocked(800.0).copy(checked = true)
        assertNull(ticked.toBuyText(Strings.English))
        assertEquals("Chicken", ticked.nameText(Strings.English))
        assertNull(ticked.packsStillToBuy(offer))
        val grown = stocked(300.0).copy(checked = true, boughtGrams = 800.0)
        assertEquals("600 g more (800 g bought)", grown.moreNeeded()?.text(Strings.English), "the one number still to buy")
        assertNull(grown.toBuyText(Strings.English))
        // The name asks only when nobody knows how much there is, ticked or not.
        assertEquals("Chicken  (on hand?)", unknown.nameText(Strings.English))
        assertEquals("Chicken  (in huis?)", unknown.copy(checked = true).nameText(Strings.Dutch))
        assertEquals("Chicken", part.nameText(Strings.English))
        // Not on hand, nothing eaten: the whole need is to buy, so nothing to say, and the packs for all of it.
        val notOnHand = packItem(1400.0, null, offer).copy(toComeGrams = 1400.0)
        assertNull(notOnHand.toBuyText(Strings.English))
        assertEquals("1.4 kg", notOnHand.amountText(FormatLocale.English))
        assertEquals("2 × 700 g", notOnHand.packsStillToBuy(offer)?.text(Strings.English))
    }

    @Test
    fun theMealsToComeKeepTheirPieceCountAndAreTheOnlyMealsNamed() {
        val offer = packOffer("400 g", "25% korting", 0.75)
        // Ten pieces and 1.3 kg for the week; the meals to come need 680 g of it, in six pieces.
        val part = packItem(1304.0, 10.0, offer).copy(toComeGrams = 680.0, toComePieces = 5.2, recipes = listOf("Eaten", "Cooked later"), toComeRecipes = listOf("Cooked later"))
        assertEquals("6 × · 680 g", part.amountText(FormatLocale.English))
        assertEquals("10 × · 1.3 kg", part.copy(checked = true).amountText(FormatLocale.English), "a ticked row keeps the week's need")
        assertEquals(listOf("Cooked later"), part.sourceMeals)
        // A server from before says no pieces and no meals: the weight alone and the week's meals.
        val old = part.copy(toComePieces = null, toComeRecipes = null)
        assertEquals("680 g", old.amountText(FormatLocale.English))
        assertEquals(listOf("Eaten", "Cooked later"), old.sourceMeals)
        // Nothing left to cook: no meal to name.
        assertEquals(emptyList(), part.copy(toComeGrams = 0.0, toComeRecipes = emptyList()).sourceMeals)
        // Other amounts follow the weight, so the count would be told twice.
        assertEquals("680 g", part.copy(otherAmounts = listOf("1 bag")).amountText(FormatLocale.English))
    }

    @Test
    fun onceMealsAreDoneTheAmountAndTheLineAreForTheMealsToCome() {
        val offer = packOffer("400 g", "25% korting", 0.75)
        // 1.3 kg for the week, 800 g at home; a dinner and its leftovers ate 624 g of it. The meals to come
        // need 680 g: the column says so, and the line adds up with it.
        val part = packItem(1304.0, null, offer).copy(onHand = true, onHandGrams = 176.0, toComeGrams = 680.0)
        assertEquals("680 g", part.amountText(FormatLocale.English))
        assertEquals("176 g on hand, buy 504 g for the meals to come", part.toBuyText(Strings.English))
        assertEquals("176 g in huis, koop 504 g voor de maaltijden die nog komen", part.toBuyText(Strings.Dutch))
        assertEquals("2 × 400 g", part.packsStillToBuy(offer)?.text(Strings.English))
        assertFalse(part.covered)
        // What is at home covers the meals to come.
        val enough = part.copy(onHandGrams = 169.0, toComeGrams = 100.0)
        assertEquals("100 g", enough.amountText(FormatLocale.English))
        assertEquals("169 g on hand: enough for the meals to come", enough.toBuyText(Strings.English))
        assertEquals("169 g in huis: genoeg voor de maaltijden die nog komen", enough.toBuyText(Strings.Dutch))
        assertNull(enough.packsStillToBuy(offer))
        assertTrue(enough.covered)
        // Nothing at home: the column is what to buy, and the line says what for.
        val noStock = packItem(1200.0, null, offer).copy(toComeGrams = 600.0)
        assertEquals("600 g", noStock.amountText(FormatLocale.English))
        assertEquals("For the meals to come", noStock.toBuyText(Strings.English))
        assertEquals("Voor de maaltijden die nog komen", noStock.toBuyText(Strings.Dutch))
        assertEquals("2 × 400 g", noStock.packsStillToBuy(offer)?.text(Strings.English))
        // Every meal that needs it done: nothing to buy, and an empty column.
        val allDone = noStock.copy(toComeGrams = 0.0)
        assertEquals("", allDone.amountText(FormatLocale.English))
        assertEquals("Nothing to buy: the meals that need it are done", allDone.toBuyText(Strings.English))
        assertEquals("Niets te kopen: de maaltijden die het nodig hebben zijn klaar", allDone.toBuyText(Strings.Dutch))
        assertTrue(allDone.covered)
        // Ticked, it is done whatever was eaten since, and keeps the week's need its "more" line counts from.
        assertFalse(allDone.copy(checked = true).covered)
        assertNull(allDone.copy(checked = true).toBuyText(Strings.English))
        assertEquals("1.2 kg", allDone.copy(checked = true).amountText(FormatLocale.English))
        // Without the server's number (advice, own items) nothing changes.
        assertFalse(packItem(1200.0, null, offer).covered)
        assertEquals("1.2 kg", packItem(1200.0, null, offer).amountText(FormatLocale.English))
    }

    @Test
    fun aNeedUnderAGramWithNothingAtHandIsStillToBuy() {
        val offer = packOffer("10 g", null)
        // Dried oregano: under a gram for a meal still to cook, none at home. The scrap only forgives what a stock leaves over.
        val oregano = packItem(0.86, null, offer).copy(toComeGrams = 0.86)
        assertFalse(oregano.covered)
        assertNull(oregano.toBuyText(Strings.English))
        assertEquals("1 × 10 g", oregano.packsStillToBuy(offer)?.text(Strings.English))
        assertEquals("0 of 1 · 1 section to go", shoppingProgress(listOf(oregano), Strings.English))
        // A meal of two done: the line says the rest is for the meals to come, never that they are done.
        val rest = packItem(2.0, null, offer).copy(toComeGrams = 0.86)
        assertFalse(rest.covered)
        assertEquals("For the meals to come", rest.toBuyText(Strings.English))
        assertEquals("1 g", rest.amountText(FormatLocale.English))
        // On hand, a remainder under a gram is enough, and says only that.
        val stocked = rest.copy(onHand = true, onHandGrams = 0.5)
        assertTrue(stocked.covered)
        assertEquals("1 g on hand: enough for the meals to come", stocked.toBuyText(Strings.English))
    }

    @Test
    fun aRowWithoutAWeightIsDoneWithOnceNoMealToComeNeedsIt() {
        val offer = packOffer("3 stuks", null, 0.75)
        // Counted in pieces, and a text row without an amount: the server weighs neither, but says when no meal to come needs them.
        val peppers = packItem(null, 3.0, offer).copy(toComeGrams = 0.0)
        val pesto = ShoppingItem("t:pesto", "A bit of pesto", null, null, "Other", listOf("Pesto toast"), false, false, toComeGrams = 0.0)
        for (row in listOf(peppers, pesto)) {
            assertTrue(row.covered, row.name)
            assertEquals("Nothing to buy: the meals that need it are done", row.toBuyText(Strings.English))
            assertEquals("", row.amountText(FormatLocale.English))
        }
        assertNull(peppers.packsStillToBuy(offer))
        assertEquals("Nothing left to buy", shoppingProgress(listOf(peppers, pesto), Strings.English))
        // Still to cook, they stay open as they were.
        val toCook = listOf(peppers.copy(toComeGrams = null), pesto.copy(toComeGrams = null))
        assertTrue(toCook.none { it.covered })
        assertEquals("1 × 3 pieces", toCook[0].packsStillToBuy(offer)?.text(Strings.English))
        assertEquals("0 of 2 · 2 sections to go", shoppingProgress(toCook, Strings.English))
        // Ticked and its meals done: nothing more, whatever the pieces said.
        assertNull(peppers.copy(checked = true, pieces = 5.0, boughtPieces = 3.0).moreNeeded())
    }

    @Test
    fun aCoveredRowIsInNoStoreGroupAndSavesNothing() {
        val lidl = Offer(Store.LIDL, "Yoghurt", "25% korting", 1.19, validFrom = "2026-01-01", validUntil = "2026-01-07", paidShare = 0.75)
        // 500 g for the meals to come: 650 g at home covers it, or nothing at home.
        val covered = ShoppingItem("covered", "covered", 500.0, null, "Dairy", emptyList(), onHand = true, checked = false, offers = listOf(lidl), regularPrice = 2.0, onHandGrams = 650.0, toComeGrams = 500.0)
        val open = covered.copy(key = "open", onHand = false, onHandGrams = null)
        val salt = ShoppingItem("salt", "salt", 500.0, null, "Tins", emptyList(), onHand = false, checked = false, toComeGrams = 500.0)
        val done = salt.copy(key = "done", toComeGrams = 0.0)
        val items = listOf(covered, open, salt, done)

        // The headers count what the progress line counts; the covered rows have a group of their own.
        val overview = storeOverview(items, all, today)
        val group = overview.stores.single()
        assertEquals(listOf("open"), keys(group.items))
        assertEquals("Lidl · 1 item on offer", group.title(Strings.English))
        assertEquals(0.5, group.saving!!, 1e-9)
        assertEquals(listOf("salt"), keys(overview.anyStore))
        assertEquals(listOf("covered", "done"), keys(overview.covered))
        assertEquals("0 of 2 · 2 sections to go", shoppingProgress(items, Strings.English))
        assertEquals(2, overview.stores.sumOf { it.items.size } + overview.anyStore.size)
        // Not the progress line's own words, which sit right above it when everything is covered.
        assertEquals("Covered · 2 items", Strings.English.plural(Plural.NOTHING_LEFT_ITEMS, 2))
        assertEquals("Gedekt · 2 producten", Strings.Dutch.plural(Plural.NOTHING_LEFT_ITEMS, 2))
        assertNull(savingOf(listOf(covered), all, today))
        assertEquals(0.0, covered.saving(lidl))
        // At another store it is not "cheaper elsewhere": there is nothing to buy of it.
        val visit = storeVisit(listOf(covered, open), Store.AH, all, today, shoppingMode = true)
        assertEquals(listOf("open"), visit.elsewhere.map { it.item.key })
        assertEquals(listOf("covered"), keys(visit.sections.single().done))
        // Ticked, it is in its store's group again, as bought there, and counted as ticked.
        assertEquals(listOf("covered", "open"), keys(storeOverview(listOf(covered.copy(checked = true), open), all, today).stores.single().items))
    }

    @Test
    fun theSavingGoesByWhatIsStillToBuyTickedOrNot() {
        val offer = packOffer("500 g", "25% korting", 0.75)
        // €4 for 1 kg; the meals to come need 400 g of it: a quarter off those 400 g.
        val row = packItem(1000.0, null, offer).copy(toComeGrams = 400.0)
        assertEquals(0.4, row.saving(offer)!!, 1e-9)
        assertEquals(0.4, savingOf(listOf(row), all, today)!!, 1e-9)
        assertEquals(0.4, savingOf(listOf(row.copy(checked = true)), all, today)!!, 1e-9, "ticking it changes nothing")
        // The same with nothing eaten and 600 g at home.
        assertEquals(0.4, packItem(1000.0, null, offer).copy(toComeGrams = 1000.0, onHand = true, onHandGrams = 600.0).saving(offer)!!, 1e-9)
        // A deal is checked against the packs of what is still to buy: "1+1 gratis" wants two packs, 400 g needs one.
        val deal = packItem(1000.0, null, packOffer("500 g", "1+1 gratis"))
        assertEquals(2.0, savingOf(listOf(deal), all, today))
        assertNull(savingOf(listOf(deal.copy(toComeGrams = 400.0)), all, today))
        // Without the server's number (advice, own items) the whole need counts, as before.
        assertEquals(1.0, packItem(1000.0, null, offer).saving(offer)!!, 1e-9)
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
    fun theDealsDetailAddsThePackSizeUnlessTheTitleHasIt() {
        val plain = packItem(450.0, null, packOffer("500 g", "1+1 gratis"))
        assertEquals(listOf("Chicken, 500 g"), plain.dealOffers(all, today).map { it.productText() })
        val named = packItem(450.0, null, packOffer("500 g", "1+1 gratis").copy(title = "Kip 500 g"))
        assertEquals(listOf("Kip 500 g"), named.dealOffers(all, today).map { it.productText() })
    }

    /** A quarter off a kilo pack at Jumbo, until 7 January. */
    private val jumboDeal = Offer(Store.JUMBO, "Chicken", "25% korting", null, packSize = "1 kg", validFrom = "2026-01-01", validUntil = "2026-01-07", paidShare = 0.75)

    /**
     * A kilo of chicken at €4 regular, ticked when its Jumbo offer saved €1 on all of it; since then half
     * its meals were eaten, so bought now it would save €0.50.
     */
    private val bought = ShoppingItem(
        "chicken", "Chicken", 1000.0, null, "Meat", emptyList(), onHand = false, checked = true, offers = listOf(jumboDeal),
        regularPrice = 4.0, toComeGrams = 500.0, boughtSaving = 1.0, boughtOfferStore = Store.JUMBO,
    )

    @Test
    fun aBoughtRowCountsWhatItSavedWhenItWasTicked() {
        assertEquals(1.0, storeOverview(listOf(bought), all, today).stores.single().saving)
        assertEquals(1.0, savingOf(listOf(bought.copy(toComeGrams = 0.0)), all, today), "all its meals eaten")
        // With a row still to buy at the same store: what was saved and what will be.
        val open = bought.copy(key = "open", checked = false, toComeGrams = 1000.0, boughtSaving = null, boughtOfferStore = null)
        assertEquals(2.0, storeOverview(listOf(bought, open), all, today).stores.single().saving)
        // Ticked when it saved nothing (the offer had ended, or only started later): nothing, whatever offer there is now.
        assertNull(savingOf(listOf(bought.copy(boughtSaving = 0.0, boughtOfferStore = null)), all, today))
    }

    @Test
    fun withoutAKeptSavingARowSavesWhatItWouldNowAsBefore() {
        // A server from before it, or a tick not sent yet.
        assertEquals(0.5, savingOf(listOf(bought.copy(boughtSaving = null, boughtOfferStore = null)), all, today)!!, 1e-9)
        // Un-ticked, though the list still has the saving of its tick (an un-tick waiting to be sent).
        assertEquals(0.5, savingOf(listOf(bought.copy(checked = false)), all, today)!!, 1e-9)
    }

    @Test
    fun aBoughtRowStaysUnderTheStoreItWasBoughtAt() {
        // Its offer has ended: still Jumbo's, with what it saved.
        val afterwards = storeOverview(listOf(bought), all, "2026-01-08").stores.single()
        assertEquals(Triple(Store.JUMBO, listOf("chicken"), 1.0), Triple(afterwards.store, keys(afterwards.items), afterwards.saving))
        // A better offer at AH since: still Jumbo's.
        val better = bought.copy(offers = listOf(jumboDeal, jumboDeal.copy(store = Store.AH, paidShare = 0.5)))
        assertEquals(Store.AH, better.bestOffer(all, today)?.store)
        assertEquals(listOf(Store.JUMBO to 1.0), storeOverview(listOf(better), all, today).stores.map { it.store to it.saving })
        // Jumbo no longer in use: the row goes by today's offers, and what it saved goes with the store.
        val withoutJumbo = storeOverview(listOf(better), setOf(Store.AH, Store.LIDL), today)
        assertEquals(listOf(Store.AH), withoutJumbo.stores.map { it.store })
        assertNull(withoutJumbo.stores.single().saving)
    }

    @Test
    fun aVisitCountsWhatWasBoughtAtThatStoreOnly() {
        assertEquals(1.0, storeVisit(listOf(bought), Store.JUMBO, all, today, shoppingMode = false).saving)
        // At AH it stays in its sections, not "cheaper elsewhere", and counts nothing there.
        val atAh = storeVisit(listOf(bought), Store.AH, all, today, shoppingMode = false)
        assertTrue(atAh.elsewhere.isEmpty())
        assertEquals(listOf("chicken"), keys(atAh.sections.flatMap { it.open + it.done }))
        assertNull(atAh.saving)
    }

    @Test
    fun aBoughtRowWhoseOfferEndedDoesNotShowAnotherStoresDeal() {
        // Bought at Jumbo; its offer has ended and AH has one running.
        val ahDeal = jumboDeal.copy(store = Store.AH, validUntil = "2026-01-20", paidShare = 0.5)
        val row = bought.copy(offers = listOf(jumboDeal.copy(validUntil = "2026-01-05"), ahDeal))
        val day = "2026-01-10"
        assertEquals(Store.AH, row.bestOffer(all, day)?.store)
        assertNull(row.chipOffer(all, day))
        assertEquals(Store.JUMBO, storeOverview(listOf(row), all, day).stores.single().store)
        // While Jumbo's offer lasts the chip is Jumbo's, even if AH's saves more.
        assertEquals(Store.JUMBO, bought.copy(offers = listOf(jumboDeal, ahDeal.copy(paidShare = 0.1))).chipOffer(all, today)?.store)
        // At AH's visit it is not listed under "Cheaper elsewhere"; a row still to buy with an AH deal is not affected.
        val open = row.copy(key = "open", checked = false, boughtSaving = null, boughtOfferStore = null)
        val visit = storeVisit(listOf(row, open), Store.LIDL, all, day, shoppingMode = false)
        assertEquals(listOf("open"), keys(visit.elsewhere.map { it.item }))
        assertEquals(setOf("chicken"), keys(visit.sections.flatMap { it.open + it.done }).toSet())
        // A row not bought keeps today's best offer.
        assertEquals(Store.AH, open.chipOffer(all, day)?.store)
    }
}
