package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnreadDealsTest {
    private fun offer(deal: String?, price: Double? = null) =
        Offer(Store.JUMBO, "Product", deal, price, null, null, validFrom = "2026-11-16", validUntil = "2026-11-22")

    @Test
    fun nothingIsSaidWhenEveryDealTextIsRead() {
        assertNull(OfferRepository.unreadDeals(Store.JUMBO, listOf(offer("25% korting"), offer("voor 1,00"), offer(null, price = 2.0))))
    }

    @Test
    fun theUnreadTextsAreCountedAgainstAllOffersOfTheStore() {
        val message = OfferRepository.unreadDeals(
            Store.JUMBO,
            listOf(offer("25% korting"), offer("Tot 25% korting"), offer("Tot 25% korting"), offer("Gratis bezorging"), offer(null)),
        )!!
        assertTrue("4 of 5 offers from ${Store.JUMBO.label}" in message, message)
        assertTrue("\"Tot 25% korting\" x2" in message, message)
        assertTrue("\"Gratis bezorging\" x1" in message, message)
        assertTrue("(no deal, no price) x1" in message, message)
    }

    @Test
    fun onlyTheMostCommonTextsAreListed() {
        val offers = (1..8).map { offer("Wording $it") }
        assertEquals(5, OfferRepository.unreadDeals(Store.JUMBO, offers)!!.split(" x1").size - 1)
    }
}
