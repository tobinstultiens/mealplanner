package dev.tobins.mealplanner.server.history

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class PriceChangesTest {
    private val today = LocalDate.parse("2026-11-17")

    private fun offer(title: String, deal: String? = "1+1 gratis", price: Double? = null, from: String = "2026-11-16", until: String = "2026-11-22", packSize: String? = null) =
        Offer(Store.JUMBO, title, deal, price, packSize = packSize, validFrom = from, validUntil = until)

    private fun saw(vararg offers: Offer) = OfferSighting.of(offers.toList(), today)

    @Test
    fun whatIsSeenAgainUnchangedContinuesItsPeriodAndAnythingElseStartsOne() {
        val before = saw(offer("Kaas", price = 3.0), offer("Ham", "2+1 gratis"), offer("Brood"), offer("Melk"))
        val open = before.mapIndexed { i, s -> i.toLong() + 1 to s.identity }

        val changes = changes(open, saw(offer("Kaas", price = 2.5), offer("Ham", "1+1 gratis"), offer("Brood"), offer("Appels"))) { it.identity }

        assertEquals(listOf(3L to "Brood"), changes.continued.map { (id, s) -> id to s.offer.title })
        assertEquals(listOf("Kaas", "Ham", "Appels"), changes.started.map { it.offer.title }, "a new price, a new deal, a new product")
        // Melk was not seen: it is neither continued nor started, its period is over.
    }

    @Test
    fun aProductThatWasNotOpenStartsAgainThoughNothingChanged() {
        val changes = changes(emptyList<Pair<Long, OfferIdentity>>(), saw(offer("Brood"))) { it.identity }
        assertEquals(listOf("Brood"), changes.started.map { it.offer.title })
    }

    @Test
    fun storesSpellingOfTheSameOfferIsNoChange() {
        val open = saw(offer("AH  Spruitjes", "VOOR 0.89", packSize = "500 G")).map { 1L to it.identity }
        val changes = changes(open, saw(offer("ah spruitjes ", "Voor 0,89", packSize = "500 g"))) { it.identity }
        assertEquals(listOf(1L), changes.continued.map { it.first })
    }

    @Test
    fun anotherPackIsAnotherProduct() {
        val open = saw(offer("Kaas", packSize = "500 g")).map { 1L to it.identity }
        val changes = changes(open, saw(offer("Kaas", packSize = "1 kg"))) { it.identity }
        assertEquals(listOf("1 kg"), changes.started.map { it.offer.packSize })
    }

    @Test
    fun theSameOfferListedTwiceIsOneSightingOverBothWeeks() {
        val seen = saw(offer("Mango", "voor 1,00", 1.0), offer("Mango", "voor 1,00", 1.0, "2026-11-23", "2026-11-29"))
        assertEquals(listOf("2026-11-16" to "2026-11-29"), seen.map { it.offer.validFrom to it.offer.validUntil })
    }

    @Test
    fun theSameProductOfferedTwoWaysIsTwoSightings() {
        // Jumbo on 2026-11-15: this week's mandarins for 1,29 and next week's for 1,89.
        val seen = saw(offer("Mandarijnen", "voor 1,29", 1.29, packSize = "Net 1 kilo"), offer("Mandarijnen", "voor 1,89", 1.89, "2026-11-23", "2026-11-29", "Net 1 kilo"))
        assertEquals(listOf(1.29, 1.89), seen.map { it.offer.price })
    }

    @Test
    fun anOfferThatHasEndedIsNotSeen() {
        assertEquals(listOf("Kaas"), saw(offer("Kaas"), offer("Ham", from = "2026-11-09", until = "2026-11-16")).map { it.offer.title })
    }

    @Test
    fun ofTwoOpenPeriodsAlikeTheLaterContinues() {
        val identity = saw(offer("Kaas")).single().identity
        val changes = changes(listOf(4L to identity, 9L to identity), saw(offer("Kaas"))) { it.identity }
        assertEquals(listOf(9L), changes.continued.map { it.first })
    }
}
