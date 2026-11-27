package dev.tobins.mealplanner.app.shopping

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.app.MemorySettingsStorage
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.ui.Load
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The buy-ahead and stock-up groups below this week's list. */
class ShoppingAdviceTest {
    private fun offer(store: Store, price: Double, paidShare: Double) =
        Offer(store, "Product", "25% korting", price, validFrom = "2026-11-16", validUntil = "2026-11-22", paidShare = paidShare)

    private fun item(key: String, vararg offers: Offer, regularPrice: Double? = 10.0, checked: Boolean = false) =
        ShoppingItem(key, key, 500.0, null, "Meat", listOf("Chilli"), onHand = false, checked = checked, offers = offers.toList(), regularPrice = regularPrice)

    private val week = ShoppingList("2026-11-16", listOf(item("i1")), staples = emptyList())
    private val beef = item("ahead:i2", offer(Store.JUMBO, 3.5, 0.6))
    private val spinach = item("ahead:i3", offer(Store.AH, 1.1, 0.75), regularPrice = 2.0)
    private val rice = item("stock:i4", offer(Store.LIDL, 2.3, 0.5), regularPrice = 4.6)
    private val all = Store.entries.toSet()
    private val today = "2026-11-18"

    @Test
    fun thisWeeksPartGetsATitleOnlyWhenAnAdviceGroupFollows() {
        assertFalse(week.hasAdvice(null, all, today), "a list as before stays as before")
        assertTrue(week.copy(buyAhead = listOf(beef)).hasAdvice(null, all, today))
        assertTrue(week.copy(stockUpDismissed = true).hasAdvice(null, all, today), "the line that shows it again")
        assertEquals(listOf(beef), AdviceTexts.BUY_AHEAD.items(week.copy(buyAhead = listOf(beef), stockUp = listOf(rice))))
        assertEquals(listOf(rice), AdviceTexts.STOCK_UP.items(week.copy(buyAhead = listOf(beef), stockUp = listOf(rice))))
    }

    @Test
    fun atAStoreWithNoAdviceOnOfferThereThereIsNoTitle() {
        val list = week.copy(buyAhead = listOf(beef))
        assertTrue(list.hasAdvice(Store.JUMBO, all, today))
        assertFalse(list.hasAdvice(Store.AH, all, today), "the beef is on offer at Jumbo only")
        assertTrue(list.copy(buyAhead = listOf(beef), buyAheadDismissed = true).hasAdvice(Store.AH, all, today), "a hidden group still has its line")
    }

    @Test
    fun atAStoreOnlyItsOwnOffersAreAdvised() {
        assertEquals(listOf(beef, spinach), adviceAt(listOf(beef, spinach), store = null, all, today))
        assertEquals(listOf(beef), adviceAt(listOf(beef, spinach), Store.JUMBO, all, today))
        assertEquals(emptyList(), adviceAt(listOf(beef, spinach), Store.LIDL, all, today))
    }

    @Test
    fun adviceWithoutAnOfferAtTheStoresInUseIsLeftOutInEveryView() {
        val withoutJumbo = all - Store.JUMBO
        assertEquals(listOf(spinach), adviceAt(listOf(beef, spinach), store = null, withoutJumbo, today))
        assertEquals(emptyList(), adviceAt(listOf(beef, spinach), Store.JUMBO, withoutJumbo, today))
        assertEquals(emptyList(), adviceAt(listOf(item("ahead:i9")), store = null, all, today), "no offer at all")
        assertFalse(week.copy(buyAhead = listOf(beef)).hasAdvice(null, withoutJumbo, today), "so no title for it either")
    }

    @Test
    fun theAdviceIsSplitByStoreLikeTheRestOfTheStoreView() {
        val overview = storeOverview(listOf(beef, spinach, rice), all, today)
        assertEquals(listOf(Store.AH, Store.JUMBO, Store.LIDL), overview.stores.map { it.store })
        assertEquals(listOf(listOf(spinach), listOf(beef), listOf(rice)), overview.stores.map { it.items })
        assertEquals(emptyList(), overview.anyStore)
    }

    @Test
    fun theHintSaysWhatTheGroupsOffersSave() {
        // 10 × 0.4 for the beef and 2 × 0.25 for the spinach.
        assertEquals(
            "It keeps, and the offer ends this week. Ticked, it counts as on hand for next week's plan. Saves about €4.50.",
            adviceHint(AdviceTexts.BUY_AHEAD, listOf(beef, spinach), all, today, Strings.English),
        )
        // The hint adds up the offers the server chose by: the one that saves most, not the cheapest pack.
        val chicken = item("ahead:i6", offer(Store.AH, 3.49, 0.9), offer(Store.AH, 8.99, 0.7))
        assertEquals(chicken.regularPrice!! * 0.3, savingOf(listOf(chicken), all, today)!!, 1e-9)
        val unknown = item("stock:i5", offer(Store.AH, 1.0, 0.5), regularPrice = null)
        assertEquals(Strings.English(ShoppingMsg.STOCK_UP_HINT), adviceHint(AdviceTexts.STOCK_UP, listOf(unknown), all, today, Strings.English))
    }

    @Test
    fun theHintSaysWhenNextWeekIsNotPlannedYet() {
        assertEquals(
            "Next week is not planned yet: these are dinners it could have. It keeps, and the offer ends this week. Ticked, next week's plan prefers meals that use it. Saves about €4.50.",
            adviceHint(AdviceTexts.BUY_AHEAD, listOf(beef, spinach), all, today, Strings.English, provisional = true),
        )
        assertEquals(
            "Volgende week is nog niet gepland: dit zijn maaltijden die het kan krijgen. Het blijft goed en de aanbieding stopt deze week. Afgevinkt kiest het plan van volgende week liever maaltijden die het gebruiken. Bespaart ongeveer €4,50.",
            adviceHint(AdviceTexts.BUY_AHEAD, listOf(beef, spinach), all, today, Strings.Dutch, provisional = true),
        )
        // Stock-up advice has no meals, so nothing changes for it.
        assertEquals(
            adviceHint(AdviceTexts.STOCK_UP, listOf(rice), all, today, Strings.English),
            adviceHint(AdviceTexts.STOCK_UP, listOf(rice), all, today, Strings.English, provisional = true),
        )
        assertEquals("for e.g. Beef chilli, Lasagne", Strings.English(ShoppingMsg.SUGGESTED_MEALS, "Beef chilli, Lasagne"))
        assertEquals("bijv. voor Beef chilli, Lasagne", Strings.Dutch(ShoppingMsg.SUGGESTED_MEALS, "Beef chilli, Lasagne"))
    }

    @Test
    fun ticksAreLaidOverTheAdviceToo() {
        val list = week.copy(buyAhead = listOf(beef), stockUp = listOf(rice))
        val ticked = list.withTicks(mapOf("ahead:i2" to true, "stock:i4" to true, "i1" to true))
        assertEquals(listOf(true, true, true), listOf(ticked.items.single(), ticked.buyAhead.single(), ticked.stockUp.single()).map { it.checked })
        assertEquals(list, list.withTicks(emptyMap()))
    }

    @Test
    fun anOfflineTickOfAdviceIsKeptAndShown() {
        val cache = ShoppingCache(MemorySettingsStorage())
        cache.save(week.copy(buyAhead = listOf(beef)), "earlier")
        cache.choose(week.weekStart, beef.key, true)
        assertTrue(cache.load(week.weekStart)!!.first.buyAhead.single().checked)
        assertEquals(mapOf(beef.key to true), cache.pending(week.weekStart))
    }

    @Test
    fun aTapOnAdviceShowsAtOnceAndReachesTheServer() = runTest {
        val sent = mutableListOf<Pair<String, Boolean>>()
        var shown: ShoppingList? = null
        val server = week.copy(stockUp = listOf(rice))
        val ticks = ShoppingTicks(
            this,
            server.weekStart,
            ShoppingCache(MemorySettingsStorage()).also { it.save(server, "earlier") },
            send = { key, checked -> sent += key to checked; Load.Ready(server.withTicks(mapOf(key to checked))) },
            isOffline = { false },
            show = { shown = it },
            offline = { _, _ -> },
            rejected = {},
        )
        ticks.reset(server)
        ticks.choose(rice.key, true)
        assertTrue(shown!!.stockUp.single().checked, "before the server answered")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(rice.key to true), sent)
        assertTrue(shown!!.stockUp.single().checked)
    }
}
