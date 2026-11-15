package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
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

    @Test
    fun thisWeeksPartGetsATitleOnlyWhenThereIsAdvice() {
        assertFalse(week.hasAdvice(), "a list as before stays as before")
        assertTrue(week.copy(buyAhead = listOf(beef)).hasAdvice())
        assertTrue(week.copy(stockUpDismissed = true).hasAdvice(), "the line that shows it again")
        assertEquals(listOf(beef), AdviceTexts.BUY_AHEAD.items(week.copy(buyAhead = listOf(beef), stockUp = listOf(rice))))
        assertEquals(listOf(rice), AdviceTexts.STOCK_UP.items(week.copy(buyAhead = listOf(beef), stockUp = listOf(rice))))
    }

    @Test
    fun atAStoreOnlyItsOwnOffersAreAdvised() {
        assertEquals(listOf(beef, spinach), adviceAt(listOf(beef, spinach), store = null, all))
        assertEquals(listOf(beef), adviceAt(listOf(beef, spinach), Store.JUMBO, all))
        assertEquals(emptyList(), adviceAt(listOf(beef, spinach), Store.LIDL, all))
    }

    @Test
    fun theHintSaysWhatTheGroupsOffersSave() {
        // 10 × 0.4 for the beef and 2 × 0.25 for the spinach.
        assertEquals(
            "It keeps, and the offer ends this week. Ticked, it counts as on hand for next week's plan. Saves about €4.50.",
            adviceHint(AdviceTexts.BUY_AHEAD, listOf(beef, spinach), all, Strings.English),
        )
        val unknown = item("stock:i5", offer(Store.AH, 1.0, 0.5), regularPrice = null)
        assertEquals(Strings.English(Msg.STOCK_UP_HINT), adviceHint(AdviceTexts.STOCK_UP, listOf(unknown), all, Strings.English))
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
        cache.tickOffline(week.weekStart, beef.key, true)
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
            send = { key, checked -> sent += key to checked; Load.Ready(server.withTicks(mapOf(key to checked))) },
            isOffline = { false },
            show = { shown = it },
            saved = {},
            offline = { _, _, _ -> },
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
