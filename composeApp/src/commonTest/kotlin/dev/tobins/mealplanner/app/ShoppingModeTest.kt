package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store
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
}
