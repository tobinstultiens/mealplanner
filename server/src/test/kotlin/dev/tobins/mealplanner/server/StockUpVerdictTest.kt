package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.PriceVerdict
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.VerdictKind
import dev.tobins.mealplanner.shopping.ShopSection
import kotlin.test.Test
import kotlin.test.assertEquals

/** Stock-up advice goes by the price verdict on an offer (issue 118, step d), with made-up verdicts. */
class StockUpVerdictTest {
    private fun staple(id: Long) = Ingredient(id, "Staple $id", null, null, null, null, null, isStaple = true)

    /** A 1 kg pack at [share] of its price, with [kind]'s verdict (none when null). */
    private fun offer(kind: VerdictKind?, share: Double = 0.75, store: Store = Store.AH) = Offer(
        store, "Pack", "${((1 - share) * 100).toInt()}% korting", packSize = "1 kg", validFrom = "2026-11-23", validUntil = "2026-11-29",
        paidShare = share, verdict = kind?.let { PriceVerdict(verdict = it) },
    )

    /** What is advised for staples 1 to [count], as name and the verdict on the offer shown. */
    private fun advice(offers: Map<Long, List<Offer>>, count: Int = offers.size) = ShoppingAdvisor(
        offers = offers,
        perKg = offers.keys.associateWith { 4.0 },
        sectionOf = offers.keys.associateWith { ShopSection.OTHER },
        keeps = offers.keys.associateWith { true },
        productImages = emptyMap(),
        checked = emptySet(),
    ).stockUp((1L..count).map(::staple), emptySet(), emptySet(), emptySet()).map { it.name to it.offers.single().verdict?.verdict }

    @Test
    fun noRealDiscountOrUsuallyCheaperIsNotAdvisedAndWithoutAVerdictItIsAsBefore() {
        val kinds = listOf(
            VerdictKind.REAL_DEAL, VerdictKind.USUAL_DEAL, VerdictKind.NOT_ENOUGH_HISTORY, VerdictKind.NOT_COMPARABLE, null,
            VerdictKind.NOT_A_DEAL, VerdictKind.WORSE_THAN_USUAL, VerdictKind.ALWAYS_ON_OFFER, VerdictKind.CHEAPER_ANOTHER_WAY,
        )
        val advised = advice(kinds.withIndex().associate { (i, kind) -> i + 1L to listOf(offer(kind)) })
        assertEquals(
            listOf(VerdictKind.REAL_DEAL, VerdictKind.USUAL_DEAL, VerdictKind.NOT_ENOUGH_HISTORY, VerdictKind.NOT_COMPARABLE, null),
            advised.map { it.second },
        )
    }

    @Test
    fun anIngredientIsAdvisedByItsNextBestOfferWhenTheBestOneIsNoRealDiscount() {
        val best = offer(VerdictKind.WORSE_THAN_USUAL, share = 0.5, store = Store.AH)
        val other = offer(VerdictKind.USUAL_DEAL, share = 0.8, store = Store.JUMBO)
        assertEquals(listOf("Staple 1" to VerdictKind.USUAL_DEAL), advice(mapOf(1L to listOf(best, other))))
        assertEquals(emptyList(), advice(mapOf(1L to listOf(best))))
    }

    @Test
    fun realDealsTakeTheSixPlacesBeforeBiggerSavingsOfOrdinaryDeals() {
        // Seven staples: six ordinary deals save most (half off 4 euros per kg), the real one least (a tenth off).
        val offers = (1L..6L).associateWith { listOf(offer(VerdictKind.USUAL_DEAL, share = 0.5)) } + (7L to listOf(offer(VerdictKind.REAL_DEAL, share = 0.9)))
        val advised = advice(offers)
        assertEquals(ShoppingAdvisor.MAX_STOCK_UP, advised.size)
        assertEquals(VerdictKind.REAL_DEAL, advised.single { it.first == "Staple 7" }.second)
        // Without verdicts the six that save most win, as before.
        assertEquals((1..6).map { "Staple $it" }, advice(offers.mapValues { (_, o) -> o.map { it.copy(verdict = null) } }).map { it.first })
    }
}
