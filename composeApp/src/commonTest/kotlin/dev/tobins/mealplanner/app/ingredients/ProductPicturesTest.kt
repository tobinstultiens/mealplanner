package dev.tobins.mealplanner.app.ingredients

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.shopping.bestOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProductPicturesTest {
    private val today = "2026-01-02"
    private val all = Store.entries.toSet()

    private val ah = "https://static.ah.nl/dam/product/p.webp"
    private val jumbo = "https://www.jumbo.com/INTERSHOP/static/Week%2046/p.png"
    private val lidl = "https://www.lidl.nl/assets/gcp1.png"

    @Test
    fun theAppLoadsEveryStoresPictures() {
        listOf(ah, jumbo, lidl).forEach { assertEquals(it, loadablePictureUrl(it, browser = false)) }
    }

    @Test
    fun theBrowserCannotDrawJumbosPicturesButTheOthers() {
        assertNull(loadablePictureUrl(jumbo, browser = true))
        assertEquals(ah, loadablePictureUrl(ah, browser = true))
        assertEquals(lidl, loadablePictureUrl(lidl, browser = true))
    }

    @Test
    fun noAddressMeansNoPicture() {
        assertNull(loadablePictureUrl(null, browser = false))
        assertNull(loadablePictureUrl("  ", browser = false))
    }

    @Test
    fun theLetterOfAProductStandsInForItsPicture() {
        assertEquals("R", pictureInitial("rode paprika"))
        assertEquals("É", pictureInitial("  énkele boon"))
        assertEquals("5", pictureInitial("5 kruiden"))
        assertEquals("•", pictureInitial(""))
    }

    @Test
    fun aShoppingRowShowsThePictureOfTheOfferItsBadgeIsFor() {
        fun offer(store: Store, price: Double, image: String?) =
            Offer(store, "Pear", null, price, validFrom = "2026-01-01", validUntil = "2026-01-07", imageUrl = image)
        val ahOffer = offer(Store.AH, 1.49, ah)
        val lidlOffer = offer(Store.LIDL, 1.99, lidl)
        // The item's own picture is the cheapest offer's, Lidl's here; the AH-only view shows AH's badge.
        val item = ShoppingItem("pear", "Pear", null, null, "Fruit", emptyList(), false, false, listOf(ahOffer, lidlOffer), imageUrl = lidl)

        assertEquals(ah, item.pictureUrl(item.bestOffer(setOf(Store.AH), today)))
        assertEquals(lidl, item.pictureUrl(null))
        assertEquals(lidl, item.pictureUrl(offer(Store.AH, 1.49, null)))
    }

    @Test
    fun theItemsOwnPictureIsLeftOutWhenItComesFromAStoreSwitchedOff() {
        fun offer(store: Store, price: Double, image: String?) =
            Offer(store, "Pear", null, price, validFrom = "2026-01-01", validUntil = "2026-01-07", imageUrl = image)
        val item = ShoppingItem("pear", "Pear", null, null, "Fruit", emptyList(), false, false, listOf(offer(Store.AH, 1.49, null), offer(Store.LIDL, 1.19, lidl)), imageUrl = lidl)
        val withoutLidl = setOf(Store.AH, Store.JUMBO)

        assertNull(item.pictureUrl(item.bestOffer(withoutLidl, today), withoutLidl))
        assertEquals(lidl, item.pictureUrl(item.bestOffer(all, today), Store.entries.toSet()))
    }
}
