package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.shopping.bestOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShoppingModeTest {
    private val today = "2026-01-02"
    private val all = Store.entries.toSet()

    private fun item(key: String, category: String, checked: Boolean = false, offers: List<Offer> = emptyList()) =
        ShoppingItem(key, key, null, null, category, emptyList(), false, checked, offers)

    private fun offer(store: Store, price: Double?, deal: String? = null, share: Double? = null) =
        Offer(store, "Product", deal, price, validFrom = "2026-01-01", validUntil = "2026-01-07", paidShare = share)

    private val items = listOf(
        item("apple", "Fruit & veg", checked = true),
        item("leek", "Fruit & veg"),
        item("pear", "Fruit & veg", checked = true),
        item("milk", "Dairy", checked = true),
        item("rice", "Tins"),
    )

    @Test
    fun theCountIsOfThisWeeksRowsAndATickedBuyAheadRowDoesNotMoveIt() {
        val ahead = item("ahead:rice", "Tins")
        // One row ticked of two, plus a buy-ahead and a stock-up row: the advice rows are not counted either way.
        val list = ShoppingList("2026-11-16", listOf(item("leek", "Fruit & veg", checked = true), item("rice", "Tins")), staples = emptyList())
        assertEquals("1 of 2 · 1 section to go", shoppingProgress(list, Strings.English))
        val withAdvice = list.copy(buyAhead = listOf(ahead.copy(checked = true)), stockUp = listOf(item("stock:oil", "Oils")))
        assertEquals("1 of 2 · 1 section to go", shoppingProgress(withAdvice, Strings.English))
        assertEquals("1 van 2 · nog 1 afdeling", shoppingProgress(withAdvice, Strings.Dutch))
        // An untouched buy-ahead row does not keep a finished list from saying so.
        val done = ShoppingList("2026-11-16", items.map { it.copy(checked = true) }, staples = emptyList(), buyAhead = listOf(ahead))
        assertEquals("All 5 ticked off", shoppingProgress(done, Strings.English))
    }

    @Test
    fun outsideShoppingModeNothingMoves() {
        val sections = shoppingSections(items, shoppingMode = false)
        assertEquals(listOf("Fruit & veg", "Dairy", "Tins"), sections.map { it.title })
        assertEquals(listOf("apple", "leek", "pear"), sections[0].open.map { it.key })
        assertTrue(sections.all { it.done.isEmpty() && !it.finished })
    }

    @Test
    fun inShoppingModeTickedItemsLeaveTheRemainingOnesUnbroken() {
        val fruit = shoppingSections(items, shoppingMode = true).first()
        assertEquals(listOf("leek"), fruit.open.map { it.key })
        assertEquals(listOf("apple", "pear"), fruit.done.map { it.key })
        assertFalse(fruit.finished)
    }

    @Test
    fun aSectionWithEverythingTickedIsFinished() {
        val sections = shoppingSections(items, shoppingMode = true)
        assertTrue(sections[1].finished)
        assertFalse(sections[2].finished)
    }

    @Test
    fun progressCountsTickedItemsAndSectionsLeft() {
        assertEquals("3 of 5 · 2 sections to go", shoppingProgress(items, Strings.English))
        assertEquals("4 of 5 · 1 section to go", shoppingProgress(items.map { if (it.key == "leek") it.copy(checked = true) else it }, Strings.English))
        assertEquals("All 5 ticked off", shoppingProgress(items.map { it.copy(checked = true) }, Strings.English))
    }

    @Test
    fun theBestOfferIsTheOneThatSavesMost() {
        val best = offer(Store.JUMBO, 1.49, share = 0.6)
        val withOffers = item("x", "Dairy", offers = listOf(offer(Store.AH, 0.99, share = 0.9), offer(Store.LIDL, null, "1+1 gratis"), best))
        assertEquals(best, withOffers.bestOffer(all, today))
        assertEquals("1+1 gratis", item("y", "Dairy", offers = listOf(offer(Store.LIDL, null, "1+1 gratis"))).bestOffer(all, today)?.deal)
        assertNull(item("z", "Dairy").bestOffer(all, today))
    }

    @Test
    fun theBadgeNamesTheStoreAndThePriceOrTheDeal() {
        assertEquals("AH €1.99", offer(Store.AH, 1.99).badgeText(Strings.English, today))
        assertEquals("AH €1,99", offer(Store.AH, 1.99).badgeText(Strings.Dutch, today))
        assertEquals("Jumbo 1+1 gratis", offer(Store.JUMBO, null, "1+1 gratis").badgeText(Strings.English, today))
        assertEquals("AH 2 voor 2.49", offer(Store.AH, null, "2 VOOR 2.49").badgeText(Strings.English, today))
        assertEquals("Lidl offer", offer(Store.LIDL, null).badgeText(Strings.English, today))
    }

    private fun bought(grams: Double? = null, pieces: Double? = null, boughtGrams: Double? = null, boughtPieces: Double? = null, knownGrams: Double? = null, checked: Boolean = true) =
        ShoppingItem("x", "x", grams, pieces, "Meat", emptyList(), onHand = false, checked = checked, knownGrams = knownGrams, boughtGrams = boughtGrams, boughtPieces = boughtPieces)

    @Test
    fun aNeedWithinARoundingStepStaysTicked() {
        // 5 % of 1000 g is 50 g: 1049 g is within it, and 20 g more is under 25 g whatever the share.
        assertNull(bought(grams = 1049.0, boughtGrams = 1000.0).moreNeeded())
        assertNull(bought(grams = 270.0, boughtGrams = 250.0).moreNeeded())
        assertNull(bought(grams = 500.0, boughtGrams = 600.0).moreNeeded(), "needing less is no more")
        assertTrue(bought(grams = 1049.0, boughtGrams = 1000.0).done)
    }

    @Test
    fun aGrownWeightIsOpenWithWhatIsLeftToBuy() {
        val more = bought(grams = 1200.0, boughtGrams = 600.0).moreNeeded()
        assertEquals(600.0, more?.grams)
        assertEquals(600.0, more?.boughtGrams)
        assertEquals("600 g more (600 g bought)", more?.text(Strings.English))
        assertEquals("600 g extra nodig (600 g gekocht)", more?.text(Strings.Dutch))
        assertEquals(400.0, bought(knownGrams = 1000.0, boughtGrams = 600.0).moreNeeded()?.grams, "the known weight counts when not all could be weighed")
    }

    @Test
    fun piecesLikewiseGoByWholePieces() {
        assertEquals("2 more (3 bought)", bought(pieces = 5.0, boughtPieces = 3.0).moreNeeded()?.text(Strings.English))
        assertNull(bought(pieces = 4.0, boughtPieces = 3.2).moreNeeded(), "both are 4 to buy")
        assertNull(bought(pieces = 3.0, boughtPieces = 3.0).moreNeeded())
    }

    @Test
    fun aRowWithoutBoughtAmountsIsPlainTicked() {
        assertNull(bought(grams = 5000.0).moreNeeded())
        assertTrue(bought(grams = 5000.0).done)
        assertNull(bought(grams = 1200.0, boughtGrams = 600.0, checked = false).moreNeeded(), "an open row has nothing to add")
    }

    @Test
    fun anOwnItemNeverNeedsMore() {
        assertNull(bought(grams = 1200.0, boughtGrams = 600.0).copy(key = "x:4").moreNeeded())
    }

    @Test
    fun aRowWithNothingLeftToBuyIsNoOpenItem() {
        // The stock covers what the meals still to cook need: not ticked, but nothing to buy.
        val covered = ShoppingItem("yoghurt", "yoghurt", 500.0, null, "Dairy", emptyList(), onHand = true, checked = false, onHandGrams = 650.0, toComeGrams = 500.0)
        val withIt = items + covered
        assertEquals("3 of 5 · 2 sections to go", shoppingProgress(withIt, Strings.English), "neither ticked nor to go")
        assertEquals("All 5 ticked off", shoppingProgress(withIt.map { if (it.key == "yoghurt") it else it.copy(checked = true) }, Strings.English))
        assertEquals("Nothing left to buy", shoppingProgress(listOf(covered), Strings.English))
        assertEquals("Niets meer te kopen", shoppingProgress(listOf(covered), Strings.Dutch))
        // Ticked, it counts as any ticked row.
        assertEquals("4 of 6 · 2 sections to go", shoppingProgress(items + covered.copy(checked = true), Strings.English))

        // In shopping mode it waits with the done ones, so the section is finished, but its box stays empty.
        val dairy = shoppingSections(withIt, shoppingMode = true).single { it.title == "Dairy" }
        assertEquals(listOf("milk", "yoghurt"), dairy.done.map { it.key })
        assertTrue(dairy.finished)
        assertFalse(covered.done)
        assertEquals(listOf("milk", "yoghurt"), shoppingSections(withIt, shoppingMode = false).single { it.title == "Dairy" }.open.map { it.key })
    }

    @Test
    fun aTickedRowNeedsNoMoreThanTheMealsStillToCook() {
        // Bought 800 g, the plan needs 1.4 kg since: 600 g more, unless meals were eaten since.
        assertEquals(600.0, bought(grams = 1400.0, boughtGrams = 800.0).copy(toComeGrams = 1100.0).moreNeeded()?.grams)
        assertEquals(200.0, bought(grams = 1400.0, boughtGrams = 800.0).copy(toComeGrams = 200.0).moreNeeded()?.grams)
        // The ceiling takes off the stock too: 500 g to come, 400 g at home.
        assertEquals(100.0, bought(grams = 1400.0, boughtGrams = 800.0).copy(toComeGrams = 500.0, onHand = true, onHandGrams = 400.0).moreNeeded()?.grams)
        // Every meal that needs it eaten: nothing more, and done.
        val eaten = bought(grams = 1400.0, boughtGrams = 800.0, pieces = 7.0, boughtPieces = 4.0).copy(toComeGrams = 0.0)
        assertNull(eaten.moreNeeded())
        assertTrue(eaten.done)
        // A meal eaten while the need stays as bought changes nothing.
        assertNull(bought(grams = 800.0, boughtGrams = 800.0).copy(toComeGrams = 400.0).moreNeeded())
    }

    @Test
    fun aRowThatNeedsMoreIsOpenNotDoneAndItsSectionNotFinished() {
        val chicken = bought(grams = 1400.0, boughtGrams = 800.0).copy(key = "chicken")
        val sections = shoppingSections(listOf(chicken), shoppingMode = true)
        assertEquals(listOf("chicken"), sections.single().open.map { it.key })
        assertTrue(sections.single().done.isEmpty())
        assertFalse(sections.single().finished)
        assertEquals("0 of 1 · 1 section to go", shoppingProgress(listOf(chicken), Strings.English))
    }
}
