package dev.tobins.mealplanner.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LabelSourceTest {
    @Test
    fun aSourceIsWrittenAndReadBackWithItsMarkForAChangeByHand() {
        val ah = LabelSource(LabelSource.Origin.AH, "61758")
        assertEquals("ah:61758", ah.toString())
        assertEquals("ah:61758;changed", ah.changed().toString())
        assertEquals(ah, LabelSource.parse("ah:61758"))
        assertEquals(LabelSource(LabelSource.Origin.OPEN_FOOD_FACTS, "8712800035626", changedByHand = true), LabelSource.parse("off:8712800035626;changed"))
    }

    @Test
    fun textOfAnotherFormIsNoSource() {
        listOf(null, "", "AH", "ah:", "nevo:12", "ah:12x", "ah:12;other").forEach { assertNull(LabelSource.parse(it), "$it") }
    }
}
