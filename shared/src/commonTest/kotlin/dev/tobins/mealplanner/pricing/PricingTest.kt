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
        // Stated prices need the regular price of a pack: 2 for 3.99 against 2.99 each.
        near(1.995 / 2.99, Discounts.factor(offer("2 voor 3,99"), regularPackPrice = 2.99))
        near(4.49 / 5.99, Discounts.factor(offer("voor 4,49"), regularPackPrice = 5.99))
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
