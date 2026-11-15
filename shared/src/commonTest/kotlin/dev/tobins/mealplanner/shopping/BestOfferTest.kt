package dev.tobins.mealplanner.shopping

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BestOfferTest {
    private fun offer(store: Store, price: Double?, share: Double?, packSize: String? = null) =
        Offer(store, "Chicken", if (price == null) "1+1 gratis" else "korting", price, packSize = packSize, validFrom = "2026-11-16", validUntil = "2026-11-22", paidShare = share)

    @Test
    fun theOfferThatSavesMostWinsNotTheCheaperPack() {
        val small = offer(Store.AH, 3.49, 0.9, "300 g")
        val big = offer(Store.JUMBO, 8.99, 0.7, "1 kg")
        assertEquals(big, bestOffer(listOf(small, big)))
        assertEquals(big, bestOffer(listOf(big, small)))
    }

    @Test
    fun anOfferWithoutAShareLosesToAnyWithOne() {
        val unknown = offer(Store.AH, 0.5, null)
        val weak = offer(Store.LIDL, 5.0, 0.96)
        assertEquals(weak, bestOffer(listOf(unknown, weak)))
    }

    @Test
    fun amongOffersWithoutAShareOneWithAPriceComesFirst() {
        val dealOnly = offer(Store.AH, null, null)
        val priced = offer(Store.JUMBO, 2.0, null)
        assertEquals(priced, bestOffer(listOf(dealOnly, priced)))
        assertEquals(dealOnly, bestOffer(listOf(dealOnly, offer(Store.LIDL, null, null))))
    }

    @Test
    fun tiesKeepTheServersOrder() {
        val first = offer(Store.LIDL, 2.0, 0.8)
        assertEquals(first, bestOffer(listOf(first, offer(Store.AH, 3.0, 0.8))))
    }

    @Test
    fun noOffersNoBest() {
        assertNull(bestOffer(emptyList()))
    }
}
