package dev.tobins.mealplanner.pricing

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.recipes.HugoRecipeParser
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PricingTest {
    private fun near(expected: Double, actual: Double?, tolerance: Double = 0.001) =
        assertTrue(actual != null && abs(expected - actual) <= tolerance, "expected $expected, got $actual")

    private fun packOffer(packSize: String?, deal: String? = null, title: String = "Product") =
        Offer(Store.AH, title, deal, 4.5, packSize = packSize, validFrom = "2026-01-01", validUntil = "2026-01-07")

    @Test
    fun packsToTakeAreRoundedUp() {
        val plan = PackSize.toBuy(packOffer("700 g"), 1400.0, null)
        assertEquals(PacksToBuy(2, 700.0, null, 1), plan)
        assertEquals(3, PackSize.toBuy(packOffer("700 g"), 1401.0, null)?.packs)
        assertEquals(1, PackSize.toBuy(packOffer("700 g"), 50.0, null)?.packs)
    }

    @Test
    fun aDealThatWantsMorePacksThanNeededDoesNotFit() {
        val plan = PackSize.toBuy(packOffer("500 g", "1+1 gratis"), 450.0, null)
        assertEquals(PacksToBuy(1, 500.0, null, 2), plan)
        assertEquals(false, plan?.dealFits)
        assertEquals(true, PackSize.toBuy(packOffer("500 g", "1+1 gratis"), 900.0, null)?.dealFits)
    }

    @Test
    fun stockingUpTakesThePacksTheDealWants() {
        assertEquals(1, PackSize.stockUpPacks(packOffer("500 g", "25% korting"), 500.0))
        assertEquals(1, PackSize.stockUpPacks(packOffer("3 stuks", null), null))
        assertEquals(2, PackSize.stockUpPacks(packOffer("500 g", "1+1 gratis"), 500.0))
        assertEquals(4, PackSize.stockUpPacks(packOffer("500 g", "3+1 gratis"), 500.0))
        // Too many packs, too heavy, or packs nobody can weigh: not advised.
        assertNull(PackSize.stockUpPacks(packOffer("500 g", "9 voor 5,00"), 500.0))
        assertNull(PackSize.stockUpPacks(packOffer("3 kg", "4 voor 9,00"), 3000.0))
        assertNull(PackSize.stockUpPacks(packOffer("3 stuks", "2 voor 3,00"), null))
    }

    @Test
    fun packsInPiecesAreComparedWithPieces() {
        assertEquals(PacksToBuy(2, null, 3, 1), PackSize.toBuy(packOffer("3 stuks"), null, 4.0))
        // Weights are not guessed from pieces, nor pieces from weights.
        assertNull(PackSize.toBuy(packOffer("3 stuks"), 400.0, null))
        assertNull(PackSize.toBuy(packOffer("500 g"), null, 4.0))
    }

    @Test
    fun packSizeFallsBackToTheTitleAndUnreadableOnesGiveNothing() {
        assertEquals(700.0, PackSize.toBuy(packOffer(null, title = "Kipfilet 700 g"), 700.0, null)?.packGrams)
        assertNull(PackSize.toBuy(packOffer(null, title = "Kipfilet"), 700.0, null))
        assertNull(PackSize.toBuy(packOffer("1 l"), 700.0, null))
        assertNull(PackSize.toBuy(packOffer("500 g"), null, null))
    }

    // Packs of several, from the real offers of 18 to 24 November 2026 (Jumbo, Lidl).

    @Test
    fun aPackOfSeveralIsReadAsTheCountTimesTheSize() {
        assertEquals(4000.0, PackSize.millilitres("4-pack à 1 liter"))
        assertEquals(4000.0, PackSize.grams("4-pack à 1 liter"), "a litre is a kilogram unless told otherwise")
        assertEquals(1000.0, PackSize.millilitres("2 bekers à 500ml"))
        assertEquals(7920.0, PackSize.millilitres("Tray met 24 blikken à 33cl"))
        assertEquals(7920.0, PackSize.millilitres("24 blikken x 33 cl"))
        assertEquals(400.0, PackSize.grams("2 verpakkingen à 200 gram"))
        assertEquals(48, PackSize.pieces("3x 16 stuks"))
        assertEquals(40, PackSize.pieces("Verpakking met 8 pakjes à 5 stuks"))
        assertEquals(30.0, PackSize.grams("6 stuks à 5 g"), "N stuks à X is N times X")
        assertEquals(240.0, PackSize.grams("3x 16 stuks", gramsPerPiece = 5.0))
    }

    @Test
    fun whatReadCorrectlyBeforeReadsTheSame() {
        assertEquals(1500.0, PackSize.millilitres("Alle flessen à 1,5 liter, per fles"), "any bottle of that size is no multiple")
        assertEquals(1000.0, PackSize.millilitres("Alle flessen à 1 liter, per fles"))
        assertEquals(4, PackSize.pieces("Alle verpakkingen à 4 stuks, per verpakking"))
        assertEquals(2, PackSize.pieces("Schaal à 2 stuks"))
        assertEquals(20, PackSize.pieces("Verpakking à 20 stuks"))
        assertEquals(175.0, PackSize.grams("2 verpakkingen à 100-175 gram"), "a range gives no size to multiply")
        assertEquals(300.0, PackSize.grams("3 verpakkingen à 133 - 300 gram"))
        assertEquals(750.0, PackSize.millilitres("Alle pakken à 750 ml - 1 liter of set met 6 pakjes à 200 ml"))
        assertEquals(2100.0, PackSize.grams("6x 350 gram of 6x 400 gram"))
        assertEquals(6000.0, PackSize.millilitres("Tray 24x250ml"))
        assertEquals(300.0, PackSize.grams("2 x 150 g"))
        assertEquals(500.0, PackSize.grams("Zak 500 gram à 2 stuks"), "a weight is not a kind of pack")
        assertNull(PackSize.millilitres("Rivella 1.5 literflessen 4-pack"), "unread before and still")
    }

    @Test
    fun theShoppingListBuysPacksOfTheWholePackOfSeveral() {
        // 900 g of it: two packs of 2 x 200 g are 800 g, three are 1200 g. Read as 200 g it was five.
        assertEquals(PacksToBuy(3, 400.0, null, 1), PackSize.toBuy(packOffer("2 verpakkingen à 200 gram"), 900.0, null))
        // 100 crackers from "8 pakjes à 5 stuks": 3 packs of 40, not 20 of 5.
        assertEquals(PacksToBuy(3, null, 40, 1), PackSize.toBuy(packOffer("Verpakking met 8 pakjes à 5 stuks"), null, 100.0))
        assertEquals(1, PackSize.toBuy(packOffer("3x 16 stuks"), null, 48.0)?.packs)
        // Lidl's "2 x 150 g" was already right.
        assertEquals(2, PackSize.toBuy(packOffer("2 x 150 g"), 500.0, null)?.packs)
    }

    @Test
    fun packsADealNeeds() {
        assertEquals(2, Discounts.packsNeeded("1+1 gratis"))
        assertEquals(4, Discounts.packsNeeded("3+1 GRATIS"))
        assertEquals(2, Discounts.packsNeeded("2 voor 3,99"))
        assertEquals(2, Discounts.packsNeeded("2e halve prijs"))
        assertEquals(2, Discounts.packsNeeded("2 stapelen voor 3.99"))
        assertEquals(3, Discounts.packsNeeded("3 stapelen tot 35%"), "stacking says how many, though not what it saves")
        assertEquals(1, Discounts.packsNeeded("25% korting"))
        assertEquals(1, Discounts.packsNeeded("voor 3,49"))
        assertEquals(1, Discounts.packsNeeded(null))
        // The packs come from the same reading as the kind: a text that is no known form as a whole wants one.
        assertEquals(1, Discounts.packsNeeded("2 voor 3,00 per stuk"))
        assertEquals(1, Discounts.packsNeeded("Gratis beanie bij 2 stuks"))
        assertEquals(2, Discounts.packsNeeded("2 voor 3,00 · Alleen online"))
    }

    @Test
    fun packSizesInWeightVolumeAndPieces() {
        assertEquals(500.0, PackSize.grams("500 g"))
        assertEquals(1500.0, PackSize.grams("1,5 kg"))
        assertEquals(500.0, PackSize.grams("2 x 250 g"))
        assertEquals(660.0, PackSize.grams("ca. 660 g"))
        assertEquals(1030.0, PackSize.grams("1 l", gramsPerMl = 1.03))
        assertEquals(750.0, PackSize.grams("0,75 l"))
        assertEquals(300.0, PackSize.grams("6 stuks", gramsPerPiece = 50.0))
        assertEquals(1000.0, PackSize.grams("1 kilogram"))
        assertEquals(180.0, PackSize.grams("per stuk", gramsPerPiece = 180.0))
        assertNull(PackSize.grams("3 stuks"), "pieces need a weight per piece")
        assertNull(PackSize.grams(null))
    }

    private fun offer(deal: String?, price: Double? = null, originalPrice: Double? = null) =
        Offer(Store.AH, "AH Kipfilet", deal, price, originalPrice, "500 g", null, "2026-11-16", "2026-11-22")

    @Test
    fun dealsBecomeTheShareOfTheRegularPriceStillPaid() {
        near(0.75, Discounts.factor(offer("25% korting"), null))
        near(0.5, Discounts.factor(offer("1 + 1 gratis"), null))
        near(2.0 / 3, Discounts.factor(offer("2+1 gratis"), null))
        near(0.75, Discounts.factor(offer("2e halve prijs"), null))
        near(2.5 / 3, Discounts.factor(offer("3e halve prijs"), null), tolerance = 1e-9)
        near(0.6, Discounts.factor(offer("2 euro korting"), regularPackPrice = 5.0))
        // Stated prices need the regular price of a pack: 2 for 3.99 against 2.99 each.
        near(1.995 / 2.99, Discounts.factor(offer("2 voor 3,99"), regularPackPrice = 2.99))
        near(4.49 / 5.99, Discounts.factor(offer("voor 4,49"), regularPackPrice = 5.99))
        near(1.995 / 2.99, Discounts.factor(offer("2 stapelen voor 3.99"), regularPackPrice = 2.99))
        assertNull(Discounts.factor(offer("2 stapelen tot 35%"), regularPackPrice = 2.99), "up to 35 %: no firm share")
        near(0.8, Discounts.factor(offer("1,00 korting"), regularPackPrice = 5.0))
        near(0.6, Discounts.factor(offer(null, price = 3.0, originalPrice = 5.0), null))
        near(0.5, Discounts.factor(offer(null, price = 2.5), regularPackPrice = 5.0))
    }

    @Test
    fun unusableDealsGiveNoDiscount() {
        assertNull(Discounts.factor(offer("2 voor 3,99"), null), "no regular price to compare with")
        assertNull(Discounts.factor(offer("10% korting · Alleen online"), null), "not in the shop")
        assertNull(Discounts.factor(offer("voor 6,99"), regularPackPrice = 5.0), "dearer than the baseline")
        assertNull(Discounts.factor(offer("Gratis bezorging"), null))
        assertNull(Discounts.factor(offer("voor 1,00 per hamburger"), regularPackPrice = 5.0), "a price per hamburger is no price of the pack")
    }

    private fun ingredient(id: Long, name: String, gramsPerPiece: Double? = null, negligible: Boolean = false) =
        Ingredient(id, name, null, gramsPerPiece, null, null, null, false, negligible)

    private fun lines(vararg text: String) =
        HugoRecipeParser().parse("x/y.md", "```ingredients\n${text.joinToString("\n")}\n```").ingredients

    @Test
    fun costPerServingWithAndWithoutOffers() {
        val chicken = ingredient(1, "Chicken")
        val onion = ingredient(2, "Onion", gramsPerPiece = 100.0)
        val cumin = ingredient(3, "Cumin", negligible = true)
        val water = ingredient(4, "Water")
        val (c, o, s, w) = lines("500 | g | chicken", "2 | onion | large", "1 | tsp | cumin", "500 | ml | water")
        val pricing = Pricing(perKg = mapOf(1L to 12.0, 2L to 2.0, 3L to 80.0, 4L to 0.5), discounts = mapOf(1L to 0.75))

        val cost = pricing.cost(listOf(c to chicken, o to onion, s to cumin, w to water), servings = 2)!!

        // 0.5 kg chicken at 12 and 0.2 kg onion at 2, over two servings; cumin and water are free.
        near((6.0 + 0.4) / 2, cost.perServing)
        near((4.5 + 0.4) / 2, cost.perServingWithOffers)
        assertEquals(emptyList(), cost.estimated)
    }

    @Test
    fun unpricedIngredientsCountAtTheMedianPrice() {
        val pricing = Pricing(perKg = mapOf(1L to 2.0, 2L to 4.0, 3L to 10.0))
        val (line) = lines("250 | g | tofu")

        val cost = pricing.cost(listOf(line to ingredient(9, "Tofu")), servings = 1)!!

        near(1.0, cost.perServing)
        assertEquals(listOf("Tofu"), cost.estimated)
        assertNull(Pricing.NONE.cost(listOf(line to ingredient(9, "Tofu")), servings = 1), "nothing to price with")
    }
}
