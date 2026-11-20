package dev.tobins.mealplanner.server.history

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.serverDay
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The verdict's rules, each on a made-up history of one product at AH: nights from Monday 14 September 2026,
 * the store read every night unless a test says otherwise, and today night 70 (Monday 23 November).
 */
class DealVerdictTest {
    private fun night(n: Int): Instant = Instant.parse("2026-09-14T03:00:00Z").plus(Duration.ofDays(n.toLong()))

    private fun day(n: Int) = serverDay(night(n)).toString()

    private fun offer(deal: String?, price: Double? = null, originalPrice: Double? = null, packSize: String? = null, title: String = "AH Kipfilet 500 gram", store: Store = Store.AH) =
        Offer(store, title, deal, price, originalPrice, packSize, validFrom = day(TODAY), validUntil = day(TODAY + 6))

    /** The product offered with [offer]'s deal from night [from] to night [until], with the store's dates for those days. */
    private fun run(offer: Offer, from: Int, until: Int, validFrom: Int = from, validUntil: Int = until): OfferPeriod {
        val seen = CurrentOffer(offer)
        return OfferPeriod(seen.identity, offer.deal, seen.measures, night(from), night(until), serverDay(night(validFrom)), serverDay(night(validUntil)))
    }

    /** The offer of today, running since night [since], and its own period in the history. */
    private fun today(offer: Offer, since: Int = TODAY) = CurrentOffer(offer) to run(offer, since, TODAY)

    private fun verdict(
        now: Pair<CurrentOffer, OfferPeriod>,
        vararg earlier: OfferPeriod,
        read: List<Int> = (0..TODAY).toList(),
        regular: List<RegularPrice> = emptyList(),
    ) = judge(now.first, earlier.toList() + now.second, read.map(::night), regular, serverDay(night(TODAY)))

    private val onePlusOne = offer("1+1 gratis")
    private val twoPlusOne = offer("2+1 gratis")
    private val quarterOff = offer("25% korting")

    @Test
    fun tooFewDaysReadIsNotEnoughHistoryWhateverTheOffersSay() {
        val verdict = verdict(today(twoPlusOne), run(onePlusOne, 7, 13), run(onePlusOne, 21, 27), run(onePlusOne, 35, 39), read = (0..TODAY).filter { it >= 30 })

        assertEquals(Verdict.NOT_ENOUGH_HISTORY, verdict.verdict)
        assertNull(verdict.measure)
        assertEquals(41, verdict.daysRead)
        assertEquals("Not enough history yet: Albert Heijn was read on 41 days since 14 Oct 2026; a verdict needs 56.", verdict.summary("Albert Heijn"))
    }

    @Test
    fun nightsTheStoreWasNotReadAreNoHistory() {
        // Ten weeks of calendar, but the store read only every other night: 36 days watched.
        val verdict = verdict(today(twoPlusOne), run(onePlusOne, 8, 12), run(onePlusOne, 30, 34), read = (0..TODAY step 2).toList())
        assertEquals(Verdict.NOT_ENOUGH_HISTORY, verdict.verdict)
        assertEquals(36, verdict.daysRead)
    }

    @Test
    fun oneEarlierOfferIsNotEnoughToCompareWith() {
        val verdict = verdict(today(twoPlusOne), run(onePlusOne, 21, 27))

        assertEquals(Verdict.NOT_ENOUGH_HISTORY, verdict.verdict)
        assertEquals(Measure.PAID_SHARE to 1, verdict.measure to verdict.compared)
        assertEquals(
            "Not enough history yet: on offer once before since 14 Sep 2026, 1 of them with a share of the regular price; " +
                "a verdict needs 2 earlier offers to compare with.",
            verdict.summary("Albert Heijn"),
        )
        assertEquals(
            "Not enough history yet: no earlier offer of it ended since 14 Sep 2026; a verdict needs 2 earlier offers to compare with.",
            verdict(today(twoPlusOne)).summary("Albert Heijn"),
        )
    }

    /** The owner's example: is this 2+1 a good deal, or does the product regularly go 1+1? */
    @Test
    fun aTwoPlusOneWhenItRegularlyGoesOnePlusOneIsWorseThanUsual() {
        val last = run(onePlusOne, 49, 55)
        val verdict = verdict(today(twoPlusOne), run(onePlusOne, 7, 13), run(onePlusOne, 28, 34), last)

        assertEquals(Verdict.WORSE_THAN_USUAL, verdict.verdict)
        assertEquals(Measure.PAID_SHARE, verdict.measure)
        assertEquals(0.5, verdict.typical)
        assertEquals(last, verdict.lastAsCheap)
        assertEquals(
            "Worse than usual: 67% of the regular price, where it usually goes for 50% of the regular price; " +
                "last cheaper 50% of the regular price with \"1+1 gratis\" from 2 Nov 2026 to 8 Nov 2026, " +
                "lowest 50% of the regular price with \"1+1 gratis\" from 21 Sep 2026 to 27 Sep 2026; on offer 3 times before since 14 Sep 2026.",
            verdict.summary("Albert Heijn"),
        )
    }

    @Test
    fun atTheLowestSeenAndBelowMostEarlierOffersIsARealDeal() {
        val lowest = run(onePlusOne, 28, 34)
        val verdict = verdict(today(onePlusOne), run(quarterOff, 7, 13), lowest, run(quarterOff, 49, 55))

        assertEquals(Verdict.REAL_DEAL, verdict.verdict)
        assertEquals(lowest to lowest, verdict.lowest to verdict.lastAsCheap)
        assertEquals(
            "A real deal: 50% of the regular price is as low as it has been (50% of the regular price with \"1+1 gratis\" from 12 Oct 2026 to 18 Oct 2026); " +
                "on offer 3 times before since 14 Sep 2026, usually at 75% of the regular price.",
            verdict.summary("Albert Heijn"),
        )
    }

    @Test
    fun lessThanEverIsARealDeal() {
        val verdict = verdict(today(onePlusOne), run(quarterOff, 7, 13), run(quarterOff, 28, 34))

        assertEquals(Verdict.REAL_DEAL, verdict.verdict)
        assertNull(verdict.lastAsCheap)
        assertTrue(verdict.summary("Albert Heijn").startsWith("A real deal: 50% of the regular price is less than ever since 14 Sep 2026 (lowest before: 75%"))
    }

    @Test
    fun theLowestSeenIsNoRealDealWhenItAlwaysGoesForThat() {
        assertEquals(Verdict.USUAL_DEAL, verdict(today(onePlusOne), run(onePlusOne, 7, 13), run(onePlusOne, 28, 34)).verdict)
    }

    @Test
    fun anotherDealPayingTheSameShareIsTheUsualDeal() {
        val halfOff = offer("50% korting")
        val verdict = verdict(today(onePlusOne), run(halfOff, 7, 13), run(halfOff, 28, 34), run(halfOff, 49, 55))

        assertEquals(Verdict.USUAL_DEAL, verdict.verdict)
        assertEquals(0.5 to 0.5, verdict.value to verdict.typical)
        assertTrue(verdict.summary("Albert Heijn").startsWith("The usual deal: 50% of the regular price; on offer 3 times before since 14 Sep 2026, usually at 50%"))
    }

    @Test
    fun betweenTheLowestAndTheUsualIsTheUsualDealAndSaysSo() {
        val twoForOne = offer("2+1 gratis")
        val verdict = verdict(today(twoForOne), run(onePlusOne, 7, 13), run(quarterOff, 28, 34), run(quarterOff, 49, 55))
        assertEquals(Verdict.USUAL_DEAL, verdict.verdict, "0.67: below the usual 0.75, above the lowest 0.5")
        assertEquals(
            "Better than usual, but it has been cheaper: 67% of the regular price, where it usually goes for 75% of the regular price; " +
                "last cheaper 50% of the regular price with \"1+1 gratis\" from 21 Sep 2026 to 27 Sep 2026, " +
                "lowest 50% of the regular price with \"1+1 gratis\" from 21 Sep 2026 to 27 Sep 2026; on offer 3 times before since 14 Sep 2026.",
            verdict.summary("Albert Heijn"),
        )
    }

    @Test
    fun pricesPerKgCompareAcrossPackSizes() {
        fun jumbo(deal: String, packSize: String) = offer(deal, packSize = packSize, title = "Jumbo Kipfilet", store = Store.JUMBO)
        val kilo = jumbo("voor 8,00", "1 kg")
        // 1,80 for 300 gram is 6,00 per kg, where the kilo pack went for 8,00.
        val verdict = verdict(today(jumbo("voor 1,80", "300 gram")), run(kilo, 7, 13), run(kilo, 28, 34))

        assertEquals(Verdict.REAL_DEAL, verdict.verdict)
        assertEquals(Measure.PER_KG, verdict.measure)
        assertEquals(6.0, verdict.value!!, 1e-9)
        assertTrue(verdict.summary("Jumbo").startsWith("A real deal: €6.00 per kg is less than ever"), verdict.summary("Jumbo"))
    }

    @Test
    fun aFourPackOfSpaTouchCostsTheSamePerLitreAsTheSingleBottle() {
        // Jumbo, 18 to 24 November 2026: the 4-pack à 1 liter for 4,00 and every 1 liter bottle for 1,00.
        fun spa(packSize: String, deal: String) = offer(deal, packSize = packSize, title = "Spa Touch", store = Store.JUMBO)
        val fourPack = spa("4-pack à 1 liter", "voor 4,00")
        val bottle = spa("Alle flessen à 1 liter, per fles", "voor 1,00")
        val verdict = verdict(today(fourPack), run(bottle, TODAY - 2, TODAY, TODAY - 2, TODAY + 4))

        assertEquals(1.0, CurrentOffer(fourPack).measures.perLitre!!, 1e-9)
        assertEquals(1.0, CurrentOffer(bottle).measures.perLitre!!, 1e-9)
        assertNull(verdict.cheaperNow, "a bottle at 1,00 a litre is no cheaper than the 4-pack at 1,00 a litre")
        assertTrue(verdict.verdict != Verdict.CHEAPER_ANOTHER_WAY, verdict.summary("Jumbo"))
    }

    @Test
    fun theMeasureIsTheOneMostEarlierOffersHave() {
        // Today's offer has a share and a price per kg; three earlier ones only a share, one only a price per kg.
        val now = offer("VOOR 4.00", price = 4.0, originalPrice = 8.0)
        val perKgOnly = offer("voor 5,00")
        val verdict = verdict(today(now), run(onePlusOne, 7, 13), run(onePlusOne, 21, 27), run(onePlusOne, 35, 41), run(perKgOnly, 49, 55))
        assertEquals(Measure.PAID_SHARE to 3, verdict.measure to verdict.compared)
    }

    @Test
    fun aGroupOffersPriceBeforeIsNoRegularPrice() {
        // AH's price before of "Alle …" is that of whichever product of the group it shows.
        fun group(deal: String, price: Double, priceBefore: Double) = offer(deal, price = price, originalPrice = priceBefore, title = "Alle AH Gerookte zalm")
        val earlier = run(group("VOOR 2.99", 2.99, 3.99), TODAY - 20, TODAY - 14)
        val verdict = verdict(today(group("VOOR 3.99", 3.99, 5.49)), earlier)
        assertNull(verdict.raise)
        assertEquals(Verdict.NOT_ENOUGH_HISTORY, verdict.verdict)
        assertTrue(isGroupOffer("Alle Santa Maria, La Morena en Knorr wraps"))
        assertTrue(!isGroupOffer("Allerhande kookboek") && !isGroupOffer("AH Ananas zonder kroon"), "the whole word, at the start")
    }

    @Test
    fun aSingleProductWithoutASizeStillShowsARaise() {
        // AH writes most products without a pack size; this one has no size in its title either (dev database).
        fun pineapple(deal: String, price: Double, priceBefore: Double) = offer(deal, price = price, originalPrice = priceBefore, title = "AH Ananas zonder kroon")
        val verdict = verdict(today(pineapple("VOOR 1.99", 1.99, 2.49)), run(pineapple("VOOR 1.79", 1.79, 1.99), TODAY - 20, TODAY - 14))
        assertEquals(Verdict.NOT_A_DEAL, verdict.verdict)
        assertEquals(RegularRaise(1.99, serverDay(night(TODAY - 14)), 2.49), verdict.raise)
    }

    @Test
    fun aProductAlwaysOnOfferThisWayIsAtItsUsualPrice() {
        val always = offer("voor 1,99", price = 1.99)
        // On offer from the first night, with a pause of three nights: 68 of 71 days.
        val verdict = verdict(today(always, since = 34), run(always, 0, 30))

        assertEquals(Verdict.ALWAYS_ON_OFFER, verdict.verdict)
        assertEquals(68 to 71, verdict.daysThisDeal to verdict.daysRead)
        assertEquals("The usual price, not a deal: Albert Heijn dated it on offer this way on 68 of the 71 days it was read since 14 Sep 2026.", verdict.summary("Albert Heijn"))
    }

    @Test
    fun alwaysOnOfferNeedsEnoughHistoryToo() {
        val always = offer("voor 1,99", price = 1.99)
        val verdict = verdict(today(always, since = 50), read = (50..TODAY).toList())
        assertEquals(Verdict.NOT_ENOUGH_HISTORY, verdict.verdict)
    }

    @Test
    fun aBetterDealOfAProductUsuallyOnOfferIsComparedAsAnyOther() {
        // "voor 1,99" (80 % of 2,49) nearly all the time, with a pause of three nights; now 1+1 for five days.
        val usual = offer("voor 1,99", price = 1.99, originalPrice = 2.49)
        val verdict = verdict(today(onePlusOne, since = 66), run(usual, 0, 30), run(usual, 34, 65))
        assertEquals(Verdict.REAL_DEAL to 2, verdict.verdict to verdict.compared)
    }

    @Test
    fun aRegularPriceRaisedJustBeforeTheOfferMakesItNoDeal() {
        // AH asks 2,49 for the product until a week before; now "2,99", and on offer for 2,49.
        val raised = offer("VOOR 2.49", price = 2.49, originalPrice = 2.99)
        val regular = RegularPrice(2.49, night(TODAY - 40), night(TODAY - 7))
        // Even with five days of history: two prices seen are enough to tell.
        val verdict = verdict(today(raised), read = (TODAY - 4..TODAY).toList(), regular = listOf(regular))

        assertEquals(Verdict.NOT_A_DEAL, verdict.verdict)
        assertEquals(RegularRaise(2.49, serverDay(night(TODAY - 7)), 2.99), verdict.raise)
        assertEquals(1.0, verdict.value!!, 1e-9)
        assertEquals(
            "Not a deal: its regular price went up from €2.49 (seen 16 Nov 2026) to €2.99, and with this deal you pay 100% of the price before.",
            verdict.summary("Albert Heijn"),
        )
    }

    @Test
    fun anEarlierOffersPriceBeforeIsARegularPriceToo() {
        val raised = offer("VOOR 2.49", price = 2.49, originalPrice = 2.99)
        val before = run(offer("VOOR 1.99", price = 1.99, originalPrice = 2.49), TODAY - 20, TODAY - 14)
        assertEquals(Verdict.NOT_A_DEAL, verdict(today(raised), before).verdict)
    }

    @Test
    fun aRaisedRegularPriceShrinksTheDealsShare() {
        // 25 % off 2,99 is 2,24: 90 % of the 2,49 it cost before the raise, where it usually pays 75 %.
        val raised = offer("25% korting", originalPrice = 2.99)
        val regular = RegularPrice(2.49, night(TODAY - 40), night(TODAY - 7))
        val verdict = verdict(today(raised), run(quarterOff, 7, 13), run(quarterOff, 28, 34), regular = listOf(regular))

        assertEquals(Verdict.WORSE_THAN_USUAL, verdict.verdict)
        assertEquals(0.75 * 2.99 / 2.49, verdict.value!!, 1e-9)
        assertTrue(verdict.summary("Albert Heijn").endsWith(" Its regular price went up from €2.49 (seen 16 Nov 2026) to €2.99; its share is measured against €2.49."))

        // Without a price before on the offer, the looked-up regular prices tell the raise: 1+1 of 3,49 is 63 % of 2,79.
        val looked = listOf(RegularPrice(2.79, night(0), night(TODAY - 10)), RegularPrice(3.49, night(TODAY - 9), night(TODAY)))
        val onePlusOneRaised = verdict(today(onePlusOne), run(quarterOff, 7, 13), run(quarterOff, 28, 34), regular = looked)
        assertEquals(RegularRaise(2.79, serverDay(night(TODAY - 10)), 3.49), onePlusOneRaised.raise)
        assertEquals(0.5 * 3.49 / 2.79, onePlusOneRaised.value!!, 1e-9)
        assertEquals(Verdict.REAL_DEAL, onePlusOneRaised.verdict)
    }

    @Test
    fun aRaiseLongerAgoThanThirtyDaysIsTheRegularPrice() {
        val raised = offer("VOOR 2.49", price = 2.49, originalPrice = 2.99)
        val regular = RegularPrice(2.49, night(0), night(TODAY - REFERENCE_DAYS.toInt() - 1))
        assertNull(verdict(today(raised), regular = listOf(regular)).raise)
        // A price of another pack says nothing about this one's.
        val otherPack = run(offer("VOOR 1.99", price = 1.99, originalPrice = 2.49, packSize = "1 kg"), TODAY - 20, TODAY - 14)
        assertNull(verdict(today(raised), otherPack).raise)
    }

    @Test
    fun aDealWithNothingToCompareByIsNotComparable() {
        val verdict = verdict(today(offer("2 stapelen tot 35%", title = "Alle AH Verse pizza's")))
        assertEquals(Verdict.NOT_COMPARABLE, verdict.verdict)
        assertEquals(
            "Can't be compared: the deal gives no share of the regular price and no price per kg, litre, piece or pack; no earlier offer of it ended since 14 Sep 2026.",
            verdict.summary("Albert Heijn"),
        )
    }

    @Test
    fun aProductWithoutASizeIsComparedByWhatAPackCosts() {
        // Lidl gives a price and no pack size; AH's "2 voor" names no size either.
        fun lidl(price: Double, packSize: String? = null) = offer(null, price = price, packSize = packSize, title = "Bio flespompoen", store = Store.LIDL)
        val verdict = verdict(today(lidl(1.49)), run(lidl(1.99), 7, 13), run(lidl(1.99), 28, 34))
        assertEquals(Measure.PER_PACK to Verdict.REAL_DEAL, verdict.measure to verdict.verdict)
        assertTrue(verdict.summary("Lidl").startsWith("A real deal: €1.49 a pack is less than ever"), verdict.summary("Lidl"))
        assertEquals(
            Verdict.USUAL_DEAL,
            verdict(today(offer("2 voor 5.50")), run(offer("2 voor 5.49"), 7, 13), run(offer("Voor 2.75"), 28, 34)).verdict,
            "2,75 a pack, however it is worded",
        )
        // What a pack of another size cost says nothing.
        val otherPack = verdict(today(lidl(1.49)), run(lidl(1.99, "2 stuks"), 7, 13), run(lidl(1.99, "2 stuks"), 28, 34))
        assertEquals(Verdict.NOT_ENOUGH_HISTORY to 0, otherPack.verdict to otherPack.compared)
    }

    private companion object {
        const val TODAY = 70
    }
}
