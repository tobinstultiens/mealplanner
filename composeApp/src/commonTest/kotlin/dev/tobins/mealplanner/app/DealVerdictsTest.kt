package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.CheaperPack
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.PastOffer
import dev.tobins.mealplanner.api.PriceRaise
import dev.tobins.mealplanner.api.PriceVerdict
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.VerdictKind
import dev.tobins.mealplanner.api.VerdictMeasure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The verdict's words in both languages, row by row of the wording table the owner read in pull request #280, on the
 * examples it gives.
 */
class DealVerdictsTest {
    private val en = Strings.English
    private val nl = Strings.Dutch

    private fun verdict(kind: VerdictKind, measure: VerdictMeasure? = VerdictMeasure.PER_KG, value: Double? = null, typical: Double? = null) =
        PriceVerdict(kind, measure, value, typical, daysRead = 70, daysNeeded = 56)

    private fun past(from: String, value: Double? = null, deal: String? = null, price: Double? = null, packName: String? = null, endedEarly: Boolean = false) =
        PastOffer(from, from, deal, price, packName, value, endedEarly)

    private fun both(verdict: PriceVerdict) = verdict.line(en) to verdict.line(nl)

    @Test
    fun aRealDeal() {
        val lowest = verdict(VerdictKind.REAL_DEAL, value = 13.30)
        assertEquals("Real deal: lowest so far, €13.30/kg" to "Echte aanbieding: laagste tot nu toe, €13,30/kg", both(lowest))
        assertEquals("Real deal" to "Echte aanbieding", lowest.chipText(en) to lowest.chipText(nl))
        assertEquals(VerdictTone.GOOD, lowest.tone)
        // An earlier offer is named by the days it ran.
        val asLow = lowest.copy(lastAsCheap = PastOffer("2026-10-19", "2026-10-25", value = 13.30))
        assertEquals("Real deal: as low as before (19–25 Oct)" to "Echte aanbieding: even laag als eerder (19–25 okt)", both(asLow))
        // The product came in other packs too: the verdict is on this one.
        assertEquals("Real deal: lowest so far for this pack, €13.30/kg", lowest.copy(thisPack = true).line(en))
        assertEquals("Echte aanbieding: laagste tot nu toe voor deze verpakking, €13,30/kg", lowest.copy(thisPack = true).line(nl))
    }

    @Test
    fun aUsualDealAndOneBetterThanUsual() {
        val usual = verdict(VerdictKind.USUAL_DEAL, VerdictMeasure.PER_PACK, 2.34, 2.34)
        assertEquals("Usual deal: usually €2.34" to "Gewone aanbieding: meestal €2,34", both(usual))
        // An ordinary deal calls for nothing: no words next to it, the line in the detail only (the owner's choice).
        assertNull(usual.chipText(en))
        assertEquals(VerdictTone.PLAIN, usual.tone)
        // Today's price in the same terms as the earlier one, and the earlier one's days.
        val better = verdict(VerdictKind.USUAL_DEAL, value = 2.10, typical = 2.40)
            .copy(betterThanUsual = true, lastAsCheap = PastOffer("2026-10-26", "2026-11-01", value = 1.87))
        assertEquals(
            "Better than usual: now €2.10/kg; was €1.87/kg (26 Oct – 1 Nov)" to "Beter dan meestal: nu €2,10/kg; was €1,87/kg (26 okt – 1 nov)",
            both(better),
        )
        assertNull(better.chipText(nl))
    }

    @Test
    fun aDealWorthWaitingFor() {
        // "Last on" is the last day it could be had, not the first; today's price beside the usual one, in its terms.
        val worse = verdict(VerdictKind.WORSE_THAN_USUAL, VerdictMeasure.PER_PACK, 2.75, 2.34).copy(lastAsCheap = PastOffer("2026-10-26", "2026-11-01", value = 2.34))
        assertEquals("Usually cheaper: €2.34, now €2.75 (last on 1 Nov)" to "Meestal goedkoper: €2,34, nu €2,75 (laatst op 1 nov)", both(worse))
        val perKg = verdict(VerdictKind.WORSE_THAN_USUAL, VerdictMeasure.PER_KG, 9.18, 8.48).copy(lastAsCheap = PastOffer("2026-10-26", "2026-11-01", value = 8.48))
        assertEquals("Usually cheaper: €8.48/kg, now €9.18/kg (last on 1 Nov)", perKg.line(en))
        assertEquals("Usually cheaper" to "Meestal goedkoper", worse.chipText(en) to worse.chipText(nl))
        val always = verdict(VerdictKind.ALWAYS_ON_OFFER, measure = null)
        assertEquals("Always on offer: its normal price" to "Altijd in de aanbieding: gewone prijs", both(always))
        assertNull(always.chipText(en), "its normal price: nothing to act on")
        val raised = verdict(VerdictKind.NOT_A_DEAL, VerdictMeasure.PAID_SHARE, 0.98).copy(raise = PriceRaise(3.99, 4.69))
        assertEquals("No real discount: price went up first (€3.99 → €4.69)" to "Geen echte korting: prijs eerst verhoogd (€3,99 → €4,69)", both(raised))
        assertEquals("No real discount", raised.chipText(en))
        // The raise is the verdict itself here, so it is not said again under it.
        assertEquals(emptyList(), raised.notes(en))
    }

    @Test
    fun cheaperAnotherWayNow() {
        val hamburgers = verdict(VerdictKind.CHEAPER_ANOTHER_WAY, VerdictMeasure.PER_PIECE, 1.60).copy(cheaperNow = past("2026-01-01", 1.50, deal = "2 voor 6,00"))
        assertEquals("Cheaper now with \"2 voor 6,00\": €1.50 each" to "Nu goedkoper met \"2 voor 6,00\": €1,50 per stuk", both(hamburgers))
        assertEquals("Can be cheaper" to "Kan goedkoper", hamburgers.chipText(en) to hamburgers.chipText(nl))
        // Lidl's price without a deal text.
        val lidl = verdict(VerdictKind.CHEAPER_ANOTHER_WAY, value = 4.98).copy(cheaperNow = past("2026-11-19", 4.58, price = 2.29))
        assertEquals("Cheaper now at €2.29" to "Nu goedkoper voor €2,29", both(lidl))
    }

    @Test
    fun tooLittleHistorySaysSoInTheDetailOnly() {
        val young = PriceVerdict(VerdictKind.NOT_ENOUGH_HISTORY, daysRead = 2, daysNeeded = 56)
        assertNull(young.chipText(en))
        assertEquals("Too new to judge (2 of 56 days)" to "Nog te nieuw om te beoordelen (2 van 56 dagen)", both(young))
        val few = young.copy(daysRead = 60)
        assertEquals("Too few earlier offers to judge" to "Te weinig eerdere aanbiedingen", both(few))
        val unknown = verdict(VerdictKind.NOT_COMPARABLE, measure = null)
        assertNull(unknown.chipText(en))
        assertEquals("Can't compare this deal" to "Niet te vergelijken", both(unknown))
    }

    @Test
    fun anotherPackThatIsOrWasCheaper() {
        val lipton = verdict(VerdictKind.USUAL_DEAL, VerdictMeasure.PER_LITRE, 2.0, 2.0)
        val now = lipton.copy(cheaperPack = CheaperPack(past("2026-11-16", 1.33, deal = "2 voor 3,99", packName = "1,5 l"), VerdictMeasure.PER_LITRE, now = true))
        assertEquals(listOf("Cheaper per litre now in the 1,5 l pack: €1.33/l"), now.notes(en))
        assertEquals(listOf("Nu goedkoper per liter in de verpakking van 1,5 l: €1,33/l"), now.notes(nl))
        val before = lipton.copy(cheaperPack = CheaperPack(PastOffer("2026-10-19", "2026-10-25", packName = "1 kg", value = 1.87), VerdictMeasure.PER_KG, now = false))
        assertEquals(listOf("Cheapest per kg: 1 kg pack, €1.87/kg (19–25 Oct)"), before.notes(en))
        assertEquals(listOf("Goedkoopst per kg: verpakking van 1 kg, €1,87/kg (19–25 okt)"), before.notes(nl))
        // A pack without a size to name.
        val unnamed = lipton.copy(cheaperPack = CheaperPack(past("2026-11-16", 1.33), VerdictMeasure.PER_LITRE, now = true))
        assertEquals(listOf("Cheaper per litre now in another pack: €1.33/l"), unnamed.notes(en))
        // A measure this app does not know (read as null) is not guessed at.
        assertEquals(emptyList(), lipton.copy(cheaperPack = CheaperPack(past("2026-11-16", 1.33), null, now = true)).notes(en))
    }

    @Test
    fun whatElseIsOnOfferAndARaiseThatStillSaves() {
        val usual = verdict(VerdictKind.USUAL_DEAL, VerdictMeasure.PAID_SHARE, 0.75, 0.75).copy(
            raise = PriceRaise(2.49, 2.99),
            alongside = listOf(past("2026-11-16", deal = "2 voor 5,50"), past("2026-11-16", price = 2.29), past("2026-11-16", deal = "voor 29,99", packName = "24 cm")),
        )
        assertEquals("Usual deal: usually 75% of the regular price", usual.line(en))
        assertEquals("Gewone aanbieding: meestal 75% van de gewone prijs", usual.line(nl))
        assertEquals(
            listOf("Price went up first: €2.49 → €2.99", "Also on offer: \"2 voor 5,50\"", "Also on offer: €2.29", "Also on offer: \"voor 29,99\" (24 cm)"),
            usual.notes(en),
        )
        assertEquals(
            listOf("Prijs eerst verhoogd: €2,49 → €2,99", "Ook in de aanbieding: \"2 voor 5,50\"", "Ook in de aanbieding: €2,29", "Ook in de aanbieding: \"voor 29,99\" (24 cm)"),
            usual.notes(nl),
        )
    }

    @Test
    fun theHistoryListsEarlierOffersAndOneThatEndedEarly() {
        val ended = PastOffer("2026-11-19", "2026-11-25", null, 2.29, null, 4.58, endedEarly = true)
        assertEquals("19–25 Nov · €2.29 · €4.58/kg · Ended early", ended.historyLine(VerdictMeasure.PER_KG, en))
        assertEquals("19–25 nov · €2,29 · €4,58/kg · Eerder gestopt", ended.historyLine(VerdictMeasure.PER_KG, nl))
        // Across two months, on one day, and in American English, month first.
        assertEquals("26 Oct – 1 Nov · €2.29", PastOffer("2026-10-26", "2026-11-01", price = 2.29).historyLine(null, en))
        assertEquals("19 Oct · €2.29", PastOffer("2026-10-19", "2026-10-19", price = 2.29).historyLine(null, en))
        assertEquals("Nov 19–25 · €2.29", PastOffer("2026-11-19", "2026-11-25", price = 2.29).historyLine(null, Strings.of(AppLanguage.SYSTEM, "en-US")))
        val week = PastOffer("2026-10-19", "2026-10-25", "1+1 gratis", value = 0.5)
        assertEquals("19–25 Oct · \"1+1 gratis\" · 50% of the regular price", week.historyLine(VerdictMeasure.PAID_SHARE, en))
    }

    @Test
    fun aMissingFactGivesTheShortLineNotAWrongOne() {
        // A later server leaving out what the line needs: the chip's words, never "null" or an empty amount.
        assertEquals("Real deal", PriceVerdict(VerdictKind.REAL_DEAL).line(en))
        assertEquals("Usually cheaper", PriceVerdict(VerdictKind.WORSE_THAN_USUAL, typical = 2.0).line(en))
        assertEquals("No real discount", PriceVerdict(VerdictKind.NOT_A_DEAL).line(en))
        assertEquals("Cheaper now with \"2 voor 6,00\"", PriceVerdict(VerdictKind.CHEAPER_ANOTHER_WAY, cheaperNow = past("2026-11-16", deal = "2 voor 6,00")).line(en))
    }

    @Test
    fun theDealsDetailListsEveryOfferAtTheAccountsStoresWithOrWithoutHistory() {
        val today = "2026-11-18"
        fun offer(store: Store, verdict: PriceVerdict?) = Offer(store, "Kipfilet", "25% korting", validFrom = "2026-11-16", validUntil = "2026-11-22", verdict = verdict)
        val young = offer(Store.AH, PriceVerdict(daysRead = 2, daysNeeded = 56))
        val known = offer(Store.JUMBO, verdict(VerdictKind.USUAL_DEAL, value = 6.0, typical = 6.0).copy(earlier = listOf(past("2026-10-19", 6.0))))
        val elsewhere = offer(Store.LIDL, known.verdict)
        val row = ShoppingItem("i1", "Chicken", 500.0, null, "Meat", emptyList(), false, false, listOf(young, known, elsewhere))
        // Each has its own "Not this ingredient", so the one too new to judge is there too; a store not shopped at is left out.
        assertEquals(listOf(young, known), row.dealOffers(setOf(Store.AH, Store.JUMBO), today))
    }

    @Test
    fun theRowsDetailSaysTheVerdictUnderTheDeal() {
        val today = "2026-11-18"
        val offer = Offer(Store.AH, "AH Mager rundergehakt 300 gram", "VOOR 3.99", 3.99, null, validFrom = "2026-11-16", validUntil = "2026-11-22")
        fun row(verdict: PriceVerdict?) = ShoppingItem("i1", "Minced beef", 300.0, null, "Meat", emptyList(), false, false, listOf(offer.copy(verdict = verdict)))
        // What the dialog shows of each offer, line by line ([DealDetail], [VerdictDetail]).
        fun ShoppingItem.detail(strings: Strings) = dealOffers(Store.entries.toSet(), today).single().let {
            listOf(it.dealHeading(today, strings), it.productText()) + it.verdict?.let { v -> listOf(v.line(strings)) + v.notes(strings) }.orEmpty()
        }
        assertEquals(listOf("AH: €3.99", "AH Mager rundergehakt 300 gram"), row(null).detail(en))
        assertEquals(listOf("AH: €3.99", "AH Mager rundergehakt 300 gram", "Too new to judge (3 of 56 days)"), row(PriceVerdict(daysRead = 3, daysNeeded = 56)).detail(en))
        assertEquals(
            listOf("AH: €3,99", "AH Mager rundergehakt 300 gram", "Echte aanbieding: laagste tot nu toe, €13,30/kg", "Ook in de aanbieding: \"2 voor 7,00\""),
            row(verdict(VerdictKind.REAL_DEAL, value = 13.30).copy(alongside = listOf(past("2026-11-16", deal = "2 voor 7,00")))).detail(nl),
        )
    }
}
