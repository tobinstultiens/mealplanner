package dev.tobins.mealplanner.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountTest {
    @Test
    fun parsesDecimalsFractionsAndMixedNumbers() {
        assertEquals(400.0, parseAmount("400"))
        assertEquals(1.5, parseAmount("1.5"))
        assertEquals(0.25, parseAmount("1/4"))
        assertEquals(1.5, parseAmount("1 1/2"))
        assertEquals(1.5, parseAmount("  1   1/2 "))
    }

    @Test
    fun rejectsRangesAndMalformedInput() {
        for (bad in listOf("", "2-3", "1/0", "a", "1 2", "1/2 1", "1e3", "-1", "1 1/2 3")) {
            assertNull(parseAmount(bad), "expected null for '$bad'")
        }
    }
}
