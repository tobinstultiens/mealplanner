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
}
