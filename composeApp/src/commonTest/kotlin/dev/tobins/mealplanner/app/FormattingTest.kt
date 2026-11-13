package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.units.Dimension
import kotlin.test.Test
import kotlin.test.assertEquals

class FormattingTest {
    @Test
    fun amountsDropTrailingZerosAndRoundToHundredths() {
        assertEquals("400", formatAmount(400.0))
        assertEquals("1.5", formatAmount(1.5))
        assertEquals("0.33", formatAmount(1.0 / 3))
        assertEquals("0.05", formatAmount(0.05))
        assertEquals("2", formatAmount(1.999))
    }

    @Test
    fun ingredientLinesReadNaturally() {
        fun line(amount: Double?, unit: String?, dimension: Dimension?, base: Double?, item: String) =
            IngredientLine(amount, unit, dimension, base, item, group = null)

        assertEquals("400 g beef", line(400.0, "g", Dimension.WEIGHT, 400.0, "beef").displayText())
        assertEquals("1 onion, large", line(1.0, "onion", Dimension.COUNT, null, "large").displayText())
        assertEquals("2 onion", line(2.0, "onion", Dimension.COUNT, null, "").displayText())
        assertEquals("3 clove garlic", line(3.0, "clove", Dimension.COUNT, null, "garlic").displayText())
        assertEquals("salt to taste", line(null, null, null, null, "salt to taste").displayText())
    }

    @Test
    fun gramsAreWholeAboveTenAndOneDecimalBelow() {
        assertEquals("28 g", formatGrams(28.06))
        assertEquals("302 g", formatGrams(301.65))
        assertEquals("4.6 g", formatGrams(4.59))
        assertEquals("5 g", formatGrams(5.0))
        assertEquals("0 g", formatGrams(null))
    }

    @Test
    fun shoppingQuantitiesRoundPiecesUpAndSwitchToKilograms() {
        fun item(grams: Double?, pieces: Double?) =
            dev.tobins.mealplanner.api.ShoppingItem("k", "x", grams, pieces, "c", emptyList(), onHand = false, checked = false)

        assertEquals("450 g", item(450.2, null).quantityText())
        assertEquals("1.25 kg", item(1250.0, null).quantityText())
        assertEquals("2 × · 165 g", item(165.0, 1.5).quantityText())
        assertEquals("3 ×", item(null, 3.0).quantityText())
        assertEquals("", item(null, null).quantityText())
    }

    @Test
    fun offersAreSummarisedPerStore() {
        fun offer(store: dev.tobins.mealplanner.api.Store, deal: String?, price: Double? = null, was: Double? = null) =
            dev.tobins.mealplanner.api.Offer(store, "x", deal, price, was, validFrom = "a", validUntil = "b")

        assertEquals("AH: €3.49 (was €4.59)", offer(dev.tobins.mealplanner.api.Store.AH, "VOOR 3.49", 3.49, 4.59).shortText())
        assertEquals("AH: 25% korting", offer(dev.tobins.mealplanner.api.Store.AH, "25% KORTING").shortText())
        assertEquals("Jumbo: 1+1 gratis", offer(dev.tobins.mealplanner.api.Store.JUMBO, "1+1 gratis").shortText())
        assertEquals("Lidl: €4.29", offer(dev.tobins.mealplanner.api.Store.LIDL, null, 4.29).shortText())
        assertEquals("AH: 2 voor 1.99, €1.00", offer(dev.tobins.mealplanner.api.Store.AH, "2 voor 1.99", 1.0).shortText())
        assertEquals("Jumbo: Alleen online, €1.09", offer(dev.tobins.mealplanner.api.Store.JUMBO, "Voor 1,09 · alleen online", 1.09).shortText())
        assertEquals("€0.05", formatEuro(0.049))
    }
}
