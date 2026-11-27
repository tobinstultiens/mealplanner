package dev.tobins.mealplanner.server.ingredients.products

import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.PackSize
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A label's values and a pack's content as AH and Open Food Facts write them (issue 354). */
class LabelTextTest {
    @Test
    fun aValueIsReadWithAPointOrACommaAndLessThanIsTheNumberItself() {
        assertEquals(12.0, labelGrams("12 g"))
        assertEquals(0.68, labelGrams("0.68 g"))
        assertEquals(0.5, labelGrams("<0,5 g"))
        assertEquals(0.5, labelGrams("< 0.5 g"))
        assertEquals(0.45, labelGrams("450 mg"))
        listOf("", "spoor", "0,5", "12 kcal", "1.5 µg", "-1 g").forEach { assertNull(labelGrams(it), it) }
    }

    @Test
    fun anEnergyIsReadInKcalAndKilojoulesAloneAreNotConverted() {
        assertEquals(388.0, labelKcal("1618.0 kJ (388.0 kcal)"))
        assertEquals(388.0, labelKcal("388 kcal"))
        assertEquals(31.0, labelKcal("130 kJ / 31 kcal"))
        assertNull(labelKcal("1618 kJ"))
    }

    @Test
    fun aTablesBasisIsReadOnlyWhenItIs100GramsOr100Millilitres() {
        assertEquals(FoodBasis.PER_100_G, basisOf("100.0 Gram"))
        assertEquals(FoodBasis.PER_100_ML, basisOf("100.0 Milliliter"))
        assertEquals(FoodBasis.PER_100_ML, basisOf("100 ml"))
        assertNull(basisOf("60.0 Gram"))
        assertNull(basisOf("200.0 Milliliter"))
    }

    @Test
    fun aPacksContentIsOfferedAndOfAMultiPackItsSingleItem() {
        assertEquals(PackSize(grams = 50.0), packOf(listOf("50.0 Gram", "50 g")))
        assertEquals(PackSize(ml = 1000.0), packOf(listOf("1.0 Liter")))
        assertEquals(PackSize(ml = 500.0), packOf(listOf("500ml")))
        assertEquals(PackSize(ml = 1500.0), packOf(listOf("1,5 l")))
        assertEquals(PackSize(ml = 250.0, single = true), packOf(listOf("1500.0 Milliliter", "6 x 250 ml")))
        assertEquals(PackSize(grams = 45.0), packOf(listOf("4 stuks", "45 g")))
        assertNull(packOf(listOf("4 stuks")))
        assertNull(packOf(emptyList()))
    }

    @Test
    fun theLimitHoldsBackWhatIsBeyondItsCountInItsTime() {
        var now = Instant.parse("2026-11-26T12:00:00Z")
        val limit = RequestLimit(2, Duration.ofMinutes(1)) { now }
        assertTrue(limit.take())
        assertTrue(limit.take())
        assertFalse(limit.take())
        now = now.plusSeconds(59)
        assertFalse(limit.take())
        now = now.plusSeconds(1)
        assertTrue(limit.take())
    }
}
