package dev.tobins.mealplanner.pricing

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DealKindTest {
    private fun near(expected: Double, actual: Double?, tolerance: Double = 0.001) =
        assertTrue(actual != null && abs(expected - actual) <= tolerance, "expected $expected, got $actual")

    private fun offer(deal: String?, price: Double? = null, originalPrice: Double? = null, packSize: String? = null, title: String = "Product") =
        Offer(Store.AH, title, deal, price, originalPrice, packSize, validFrom = "2026-11-16", validUntil = "2026-11-22")

    /** Deal texts of AH, Jumbo and Lidl offers in the dev database on 2026-11-15, one of each form. */
    @Test
    fun theStoresDealTextsAreReadAsTheirKind() {
        val expected = mapOf(
            "25% korting" to DealKind.PercentOff(25.0),
            "20% KORTING" to DealKind.PercentOff(20.0),
            "10% korting · Alleen online" to DealKind.PercentOff(10.0),
            "1+1 gratis" to DealKind.FreeItems(1, 1),
            "1 + 1 gratis" to DealKind.FreeItems(1, 1),
            "2 + 1 gratis" to DealKind.FreeItems(2, 1),
            "10+2 gratis" to DealKind.FreeItems(10, 2),
            "2+1 gratis · Alleen in de winkel" to DealKind.FreeItems(2, 1),
            "2 voor 3,99" to DealKind.MultiPrice(2, 3.99),
            "2 VOOR 1.99" to DealKind.MultiPrice(2, 1.99),
            "8 voor 4.69" to DealKind.MultiPrice(8, 4.69),
            "2e halve prijs" to DealKind.NthHalfPrice(2),
            "3e halve prijs" to DealKind.NthHalfPrice(3),
            "voor 1,00" to DealKind.LowerPrice(1.0),
            "VOOR 2.99" to DealKind.LowerPrice(2.99),
            "voor 18,99 · Alleen online" to DealKind.LowerPrice(18.99),
            "14,99 · OP=OP" to DealKind.LowerPrice(14.99),
            "1,00 korting" to DealKind.AmountOff(1.0),
            "2 euro korting" to DealKind.AmountOff(2.0),
            "4,00 korting · Alleen in de slijterij" to DealKind.AmountOff(4.0),
            "1,00 per kilo · Alleen in de winkel" to DealKind.PricePerKg(1.0),
            "1,09 per 500 gram · Alleen in de winkel" to DealKind.PricePerKg(2.18),
            "1,00 per 100 gram" to DealKind.PricePerKg(10.0),
            "100 gram voor 1.59" to DealKind.PricePerKg(15.9),
            "2 stapelen voor 3.99" to DealKind.MultiPrice(2, 3.99),
        )
        for ((text, kind) in expected) {
            val read = Discounts.kind(text)
            if (kind is DealKind.PricePerKg && read is DealKind.PricePerKg) near(kind.euros, read.euros) else assertEquals(kind, read, text)
        }
    }

    /** Wordings with extra words that cost the customer the same as the form inside them. */
    @Test
    fun extraWordsThatChangeNothingAreRead() {
        val expected = mapOf(
            "25% korting!" to DealKind.PercentOff(25.0),
            "Nu 25% korting" to DealKind.PercentOff(25.0),
            "NU 25% KORTING!!" to DealKind.PercentOff(25.0),
            "Nu 1+1 gratis!" to DealKind.FreeItems(1, 1),
            "2e halve prijs!" to DealKind.NthHalfPrice(2),
            "Tweede halve prijs" to DealKind.NthHalfPrice(2),
            "Derde halve prijs" to DealKind.NthHalfPrice(3),
            "25% korting op alles" to DealKind.PercentOff(25.0),
            "25% korting op alles! · Alleen online" to DealKind.PercentOff(25.0),
            "25% korting bij 2 stuks" to DealKind.PercentOff(25.0),
            "1+1 gratis bij 2 stuks" to DealKind.FreeItems(1, 1),
            "2 voor 3,00 bij 2 stuks" to DealKind.MultiPrice(2, 3.0),
            "2e halve prijs bij 2 stuks" to DealKind.NthHalfPrice(2),
            "Nu 25% korting op alles bij 3 stuks!" to DealKind.PercentOff(25.0),
        )
        for ((text, kind) in expected) assertEquals(kind, Discounts.kind(text), text)
    }

    @Test
    fun aPercentageBoughtAtLeastThatManyWantsThatManyPacks() {
        assertEquals(2, Discounts.packsNeeded("25% korting bij 2 stuks"))
        assertEquals(3, Discounts.packsNeeded("Nu 25% korting op alles bij 3 stuks!"))
        assertEquals(1, Discounts.packsNeeded("Nu 25% korting"))
        assertEquals(2, Discounts.packsNeeded("1+1 gratis bij 2 stuks"))
        val terms = DealTerms.of(offer("25% korting bij 2 stuks", originalPrice = 2.0, packSize = "500 g"))
        assertEquals(2, terms.packs)
        near(0.75, terms.paidShare)
    }

    @Test
    fun extraWordsThatMayChangeWhatIsPaidStayUnknown() {
        for (text in listOf(
            "Tot 25% korting",
            "Tot 25% korting bij 2 stuks",
            "Nu tot 25% korting!",
            // The count of the deal and the count it is bought at disagree, or a price may be each pack or all.
            "1+1 gratis bij 4 stuks",
            "2 voor 3,00 bij 3 stuks",
            "voor 1,99 bij 2 stuks",
            "1,00 korting bij 2 stuks",
            "1,00 korting op alles",
            "voor 1,99 op alles",
            "2 voor 3,00 per stuk",
            "25% korting op alles van dit merk",
            "25% korting bij 2 producten",
        )) {
            assertEquals(DealKind.Unknown(text), Discounts.kind(text), text)
            assertEquals(1, Discounts.packsNeeded(text), text)
        }
    }

    @Test
    fun formsThatAreNotKnownStayUnknownWithTheirText() {
        for (text in listOf(
            "2 stapelen tot 35%",
            "voor 1,00 per hamburger",
            "Gratis beanie bij 2 stuks · Alleen online - OP=OP",
            "2 gratis blikjes bij 1 multipack · Alleen online - OP=OP",
        )) {
            assertEquals(DealKind.Unknown(text), Discounts.kind(text))
            val terms = DealTerms.of(offer(text, price = 2.0, originalPrice = 3.0))
            assertNull(terms.paidShare, "no share for $text")
            assertNull(terms.eurosPerKg)
        }
        assertEquals(DealKind.Unknown(null), Discounts.kind(null))
    }

    @Test
    fun anOfferWithoutADealTextIsItsLowerPrice() {
        assertEquals(DealKind.LowerPrice(4.29), Discounts.kind(null, 4.29))
        val lidl = DealTerms.of(offer(null, price = 4.29, packSize = "500 g"))
        near(8.58, lidl.eurosPerKg)
        assertNull(lidl.paidShare, "no price before")
    }

    @Test
    fun eachKindHasItsPacksAndTheShareItPays() {
        fun terms(deal: String, originalPrice: Double? = null, price: Double? = null) = DealTerms.of(offer(deal, price, originalPrice, "500 g"))
        // The share follows from the deal alone.
        terms("1+1 gratis").let { assertEquals(2, it.packs); near(0.5, it.paidShare) }
        terms("2+1 gratis").let { assertEquals(3, it.packs); near(0.667, it.paidShare) }
        terms("2e halve prijs").let { assertEquals(2, it.packs); near(0.75, it.paidShare) }
        terms("25% korting").let { assertEquals(1, it.packs); near(0.75, it.paidShare) }
        // A price needs the price before; without it, only the price per kg is known.
        terms("2 voor 3,00").let { assertEquals(2, it.packs); assertNull(it.paidShare); near(3.0, it.eurosPerKg) }
        near(0.75, terms("2 voor 3,00", originalPrice = 2.0).paidShare)
        terms("voor 1,50").let { assertEquals(1, it.packs); assertNull(it.paidShare); near(3.0, it.eurosPerKg) }
        near(0.6, terms("VOOR 0.89", originalPrice = 1.49, price = 0.89).paidShare, 0.01)
        near(0.5, terms("1,00 korting", originalPrice = 2.0).paidShare)
        terms("1,09 per 500 gram").let { assertEquals(1, it.packs); assertNull(it.paidShare); near(2.18, it.eurosPerKg) }
    }

    @Test
    fun thePricePerKgOrPerPieceIsAtTheDealsShare() {
        near(4.0, DealTerms.of(offer("1+1 gratis", originalPrice = 4.0, packSize = "500 g")).eurosPerKg)
        // A store's price with a free-packs deal may be either price: it is not used.
        assertNull(DealTerms.of(offer("1+1 gratis", price = 4.0, packSize = "500 g")).eurosPerKg)
        near(0.5, DealTerms.of(offer("voor 2,00", packSize = "4 stuks")).eurosPerPiece)
        // The size is read from the title when the pack size gives none; volumes are no weight.
        near(1.78, DealTerms.of(offer("VOOR 0.89", title = "AH Spruitjes 500 gram")).eurosPerKg)
        assertNull(DealTerms.of(offer("voor 3,96", packSize = "1 liter")).eurosPerKg)
    }

    @Test
    fun aPackWrittenAsAVolumeHasAPricePerLitre() {
        DealTerms.of(offer(null, price = 2.99, packSize = "1,5 l")).let { near(1.993, it.eurosPerLitre); assertNull(it.eurosPerKg) }
        // Both packs of the deal together: 7,00 for 2 x 1,98 l.
        near(7.0 / 3.96, DealTerms.of(offer("2 voor 7,00", packSize = "6 x 330 ml")).eurosPerLitre)
        near(4.0, DealTerms.of(offer("voor 3,00", packSize = "75 cl")).eurosPerLitre)
        // At the deal's share, like the price per kg; from the title when the pack size gives no size.
        near(1.0, DealTerms.of(offer("1+1 gratis", originalPrice = 2.0, title = "AH Halfvolle melk 1 liter")).eurosPerLitre)
    }

    @Test
    fun aPackWithoutAVolumeHasNoPricePerLitre() {
        DealTerms.of(offer("voor 2,00", packSize = "500 g")).let { near(4.0, it.eurosPerKg); assertNull(it.eurosPerLitre) }
        assertNull(DealTerms.of(offer("voor 2,00", packSize = "4 stuks")).eurosPerLitre)
        // The pack size is a weight: a volume in the title does not make it one.
        assertNull(DealTerms.of(offer("voor 2,00", packSize = "500 g", title = "Saus 1 l")).eurosPerLitre)
        // A price by weight has no price per pack to divide.
        assertNull(DealTerms.of(offer("1,00 per kilo", packSize = "1 l")).eurosPerLitre)
        assertNull(DealTerms.of(offer("1+1 gratis", price = 2.0, packSize = "1 l")).eurosPerLitre, "no price before")
    }

    @Test
    fun aVolumeIsReadInMillilitresAndAWeightIsNot() {
        assertEquals(1500.0, PackSize.millilitres("1,5 l"))
        assertEquals(1980.0, PackSize.millilitres("6 x 330 ml"))
        assertEquals(750.0, PackSize.millilitres("75 cl"))
        assertEquals(1000.0, PackSize.millilitres("1 liter"))
        assertEquals(500.0, PackSize.millilitres("500 g, 500 ml"), "the volume, not the weight before it")
        assertNull(PackSize.millilitres("500 g"))
        assertNull(PackSize.millilitres("4 stuks"))
        assertNull(PackSize.millilitres(null))
    }

    @Test
    fun aShareAboveTheFullPriceIsNotKept() {
        assertNull(DealTerms.of(offer("voor 5,00", originalPrice = 4.0)).paidShare)
    }

    /** A store's page can hold any digits: no text makes the readers throw, an unreadable number is an unknown deal. */
    @Test
    fun aNumberTooLongToReadMakesTheDealUnknownNeverAnException() {
        val long = "9".repeat(400)
        val texts = listOf(
            "25% korting bij 99999999999 stuks", "25% korting bij 0 stuks", "25% korting bij $long stuks",
            "99999999999+1 gratis", "1+99999999999 gratis", "0+0 gratis", "$long+1 gratis",
            "99999999999 voor 3,99", "$long voor 3,99", "2 voor $long", "0 voor 3,99",
            "99999999999e halve prijs", "0e halve prijs",
            "$long% korting", "$long korting", "$long euro korting", "voor $long", long,
            "$long per kilo", "$long per 500 gram", "1,09 per $long gram", "$long gram voor 1,59", "0 gram voor 1,59",
        )
        for (text in texts) {
            assertEquals(DealKind.Unknown(text), Discounts.kind(text), text)
            assertEquals(1, Discounts.packsNeeded(text), text)
            Discounts.factor(offer(text, price = 1.0, originalPrice = 2.0), 1.0)
            DealTerms.of(offer(text))
        }
        // "N stapelen" is read on its own too, with the same guard.
        assertEquals(1, Discounts.packsNeeded("99999999999 stapelen tot 35%"))
        assertEquals(1, Discounts.packsNeeded("0 stapelen tot 35%"))
    }

    @Test
    fun aPackSizeWithANumberTooLongToReadIsNoSizeNeverAnException() {
        val long = "9".repeat(400)
        for (text in listOf("$long g", "$long x 330 ml", "6 x $long ml", "$long stuks", "99999999999 stuks", "$long,5 l", "${"9".repeat(300)} x ${"9".repeat(300)} kg")) {
            assertNull(PackSize.grams(text, gramsPerPiece = 50.0), text)
            assertNull(PackSize.pieces(text), text)
            assertNull(PackSize.millilitres(text), text)
            PackSize.toBuy(offer("2 voor 3,99", packSize = text, title = text), 500.0, 3.0)
        }
    }

    /** A size that is finite but absurd (a denormal) gave an infinite price per kg and 2147483647 packs. */
    @Test
    fun aPackSizeNoPackCanHaveGivesNoSizeAndNoInfinitePrice() {
        val tiny = "0." + "0".repeat(310) + "1"
        for (text in listOf("$tiny g", "$tiny kg", "$tiny l", "$tiny ml", "2 x $tiny g", "2 x $tiny l", "0,0001 g", "2000000000 kg")) {
            assertNull(PackSize.grams(text), text)
            assertNull(PackSize.millilitres(text), text)
            assertNull(PackSize.toBuy(offer("2 voor 3,99", packSize = text, title = "Product"), 500.0, 3.0), text)
            val terms = DealTerms.of(offer("2 voor 3,99", packSize = text, originalPrice = 2.0))
            for (value in listOf(terms.eurosPerKg, terms.eurosPerPiece, terms.eurosPerLitre)) assertNull(value, text)
        }
        // A real small pack is still read.
        assertEquals(0.5, PackSize.grams("0,5 g"))
        assertEquals(1, PackSize.toBuy(offer(null, packSize = "500 g"), 500.0, null)?.packs)
    }

    @Test
    fun aLargeButPossibleCountIsStillRead() {
        assertEquals(DealKind.MultiPrice(1000, 3.99), Discounts.kind("1000 voor 3,99"))
        assertEquals(1000, Discounts.packsNeeded("25% korting bij 1000 stuks"))
    }
}
