package dev.tobins.mealplanner.server.history

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.serverDay
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which rows of the history are one offer, and which offers are earlier offers: one test per row of the table in
 * docs/data-sources.md ("Is it a good deal?", "Which rows are one offer"). No answer may depend on the nights a
 * product was seen, nor on a price the deal's text does not name. Nights from Monday 14 September 2026; today is
 * night 70.
 */
class OfferPeriodsTest {
    private fun night(n: Int): Instant = Instant.parse("2026-09-14T03:00:00Z").plus(Duration.ofDays(n.toLong()))

    private fun day(n: Int) = serverDay(night(n))

    private fun offer(deal: String?, price: Double? = null, originalPrice: Double? = null, packSize: String? = null, from: Int = TODAY, until: Int = TODAY + 6) =
        Offer(Store.AH, "AH Zoete kleine appeltjes zak 1 kilo", deal, price, originalPrice, packSize, validFrom = day(from).toString(), validUntil = day(until).toString())

    /** A row of [offer], seen from night [from] to night [until], dated as [offer] is. */
    private fun row(offer: Offer, from: Int, until: Int): OfferPeriod {
        val read = CurrentOffer(offer)
        return OfferPeriod(read.identity, offer.deal, read.measures, night(from), night(until), read.validFrom, read.validUntil)
    }

    private fun offers(vararg rows: OfferPeriod) = joinOffers(rows.toList()).size

    /** Today's [offer] judged with [rows], the store read every night: its verdict, for which offers are earlier. */
    private fun judged(offer: Offer, vararg rows: OfferPeriod) = judge(CurrentOffer(offer), rows.toList(), (0..TODAY).map(::night), emptyList(), day(TODAY))

    private val weekOne = offer("VOOR 1.99", price = 1.99, from = 7, until = 13)

    // Which rows are one offer.

    @Test
    fun row1SameOfferOnDatesThatShareADayIsOne() = assertEquals(1, offers(row(weekOne, 7, 9), row(weekOne, 10, 13)))

    @Test
    fun row2NightsMissedBetweenRowsDatedAsOneOfferChangeNothing() = assertEquals(1, offers(row(weekOne, 7, 7), row(weekOne, 12, 13)))

    @Test
    fun row3APriceThatMovesWithThePriceBeforeIsTheSameOfferAtItsLowestPrice() {
        // AH: "25% korting" for 2,24 (before 2,99) until Wednesday, for 2,39 (before 3,19) after.
        val cheaper = row(offer("25% korting", price = 2.24, originalPrice = 2.99, from = 7, until = 13), 7, 9)
        val dearer = row(offer("25% korting", price = 2.39, originalPrice = 3.19, from = 7, until = 13), 10, 13)
        val joined = joinOffers(listOf(dearer, cheaper)).single()
        assertEquals(2.24 to 2.99, joined.identity.price to joined.identity.originalPrice)
        assertEquals(night(7) to night(13), joined.firstSeen to joined.lastSeen)
    }

    @Test
    fun row4SameOfferOnDatesThatRunOnIsOne() =
        assertEquals(1, offers(row(weekOne, 7, 13), row(offer("VOOR 1.99", price = 1.99, from = 14, until = 20), 14, 20)))

    @Test
    fun row5NightsMissedBetweenDatesThatRunOnChangeNothing() =
        assertEquals(1, offers(row(weekOne, 7, 9), row(offer("VOOR 1.99", price = 1.99, from = 14, until = 20), 16, 20)))

    @Test
    fun row6SameOfferOnDatesApartIsTwoWhateverTheNights() {
        val weekTwo = offer("VOOR 1.99", price = 1.99, from = 15, until = 21)
        assertEquals(2, offers(row(weekOne, 7, 13), row(weekTwo, 15, 21)), "seen every night it was dated")
        assertEquals(2, offers(row(weekOne, 7, 9), row(weekTwo, 18, 21)), "nights missed")
    }

    @Test
    fun row7AnotherDealOnTheSameDatesIsAnotherOffer() =
        assertEquals(2, offers(row(weekOne, 7, 9), row(offer("1+1 gratis", from = 7, until = 13), 10, 13)))

    @Test
    fun row8AnotherPackOnTheSameDatesIsAnotherOffer() =
        assertEquals(2, offers(row(weekOne, 7, 13), row(offer("VOOR 1.99", price = 1.99, packSize = "2 kg", from = 7, until = 13), 7, 13)))

    @Test
    fun row9WithoutADealTextThePriceIsTheDeal() {
        // Lidl: a price, no deal text. 1,49 and 1,29 on the same dates are two offers.
        val lidl = { price: Double -> offer(null, price = price, from = 7, until = 13) }
        assertEquals(2, offers(row(lidl(1.49), 7, 9), row(lidl(1.29), 10, 13)))
    }

    @Test
    fun row10WithoutADealTextTheSamePriceOnDatesThatRunOnIsOne() =
        assertEquals(1, offers(row(offer(null, price = 1.49, from = 7, until = 13), 7, 11), row(offer(null, price = 1.49, from = 14, until = 20), 15, 20)))

    // Which offers are earlier offers. Each case has one real earlier offer (weeks before, its own dates): a
    // second one would be today's offer counted against itself.

    private val realEarlier = row(offer("VOOR 1.49", price = 1.49, from = 28, until = 34), 28, 34)

    /** Today's offer dated Friday to Thursday, around today. */
    private fun thisWeek(deal: String = "VOOR 1.99", price: Double? = 1.99, originalPrice: Double? = null) =
        offer(deal, price = price, originalPrice = originalPrice, from = TODAY - 3, until = TODAY + 3)

    private fun assertOnlyTheRealEarlierOffer(verdict: DealVerdict) {
        assertEquals(listOf(realEarlier.firstSeen), verdict.earlier.map { it.firstSeen })
        assertEquals(Verdict.NOT_ENOUGH_HISTORY, verdict.verdict)
    }

    @Test
    fun row11aTodaysOfferSplitByMissedNightsIsToday() =
        assertOnlyTheRealEarlierOffer(judged(thisWeek(), realEarlier, row(thisWeek(), TODAY - 3, TODAY - 3), row(thisWeek(), TODAY, TODAY)))

    @Test
    fun row11bTodaysOfferWhosePriceMovedWithThePriceBeforeIsToday() {
        // The reviewer's case: 2,24 (before 2,99) Friday to Sunday, 2,39 (before 3,19) since.
        val first = thisWeek("25% korting", 2.24, 2.99)
        val now = thisWeek("25% korting", 2.39, 3.19)
        val realQuarterOff = row(offer("25% korting", price = 2.24, originalPrice = 2.99, from = 28, until = 34), 28, 34)
        val verdict = judge(CurrentOffer(now), listOf(realQuarterOff, row(first, TODAY - 3, TODAY - 1), row(now, TODAY, TODAY)), (0..TODAY).map(::night), emptyList(), day(TODAY))
        assertEquals(listOf(realQuarterOff.firstSeen), verdict.earlier.map { it.firstSeen })
        assertEquals(Verdict.NOT_ENOUGH_HISTORY, verdict.verdict)
        // Measured at what is paid today, 2,39 for the kilo, against the lowest price before while it ran: 80 %.
        assertEquals(2.39, CurrentOffer(now).withPriceBefore(2.99).measures.perKg!!, 1e-9)
        assertEquals(2.39 / 2.99, CurrentOffer(now).withPriceBefore(2.99).measures.paidShare!!, 1e-9)
    }

    @Test
    fun row11cLastWeeksSameOfferRunningOnIntoTodaysIsTodayWhateverNightsItMissed() {
        val lastWeek = offer("VOOR 1.99", price = 1.99, from = TODAY - 10, until = TODAY - 4)
        // Seen every night, or with its last three nights missed: the same answer.
        assertOnlyTheRealEarlierOffer(judged(thisWeek(), realEarlier, row(lastWeek, TODAY - 10, TODAY - 4), row(thisWeek(), TODAY - 3, TODAY)))
        assertOnlyTheRealEarlierOffer(judged(thisWeek(), realEarlier, row(lastWeek, TODAY - 10, TODAY - 7), row(thisWeek(), TODAY - 3, TODAY)))
        // Even when today's own rows are missing from the history (the store's recording failed).
        assertOnlyTheRealEarlierOffer(judged(thisWeek(), realEarlier, row(lastWeek, TODAY - 10, TODAY - 7)))
    }

    @Test
    fun row12TodaysDealOnDatesThatEndedBeforeTodaysBeganIsEarlier() {
        val twoWeeksAgo = row(offer("VOOR 1.99", price = 1.99, from = TODAY - 17, until = TODAY - 11), TODAY - 17, TODAY - 11)
        assertEquals(2, judged(thisWeek(), realEarlier, twoWeeksAgo, row(thisWeek(), TODAY - 3, TODAY)).earlier.size)
    }

    @Test
    fun row13AnotherDealWhoseDatesEndedIsEarlier() {
        val yesterday = row(offer("1+1 gratis", from = TODAY - 7, until = TODAY - 1), TODAY - 7, TODAY - 1)
        assertEquals(2, judged(thisWeek(), realEarlier, yesterday, row(thisWeek(), TODAY - 3, TODAY)).earlier.size)
    }

    @Test
    fun row14AnotherDealStillDatedToRunIsNoEarlierOfferWhateverTheLastNightSaw() {
        // Jumbo lists next week's offer with this week's; the last refresh happened to miss it.
        val nextWeek = row(offer("VOOR 1.29", price = 1.29, from = TODAY + 4, until = TODAY + 10), TODAY - 2, TODAY - 1)
        assertOnlyTheRealEarlierOffer(judged(thisWeek(), realEarlier, nextWeek, row(thisWeek(), TODAY - 3, TODAY)))
    }

    private companion object {
        const val TODAY = 70
    }
}
