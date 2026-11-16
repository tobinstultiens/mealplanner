package dev.tobins.mealplanner.server.history

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which looked-up regular price is an offer's own product's: only that one tells of a raise. */
class RegularPricesTest {
    @Test
    fun theOfferMayAddTheSizeToTheProductsName() {
        // AH's search and AH's offers, the same product (dev database, 2026-11-16).
        assertTrue(isSameProduct("AH Snoepgroente tomaat", "500 g", "AH Snoepgroente tomaat 500 gram", null))
        assertTrue(isSameProduct("AH Halfvolle melk", "1,5 l", "AH Halfvolle melk", "1,5 liter"))
        assertTrue(isSameProduct("AH Scharreleieren", "10 stuks", "AH scharreleieren 10 stuks", null))
    }

    @Test
    fun anotherProductOfTheSameNameIsNot() {
        assertFalse(isSameProduct("AH Kipfilet", "300 g", "AH Kipfilet blokjes 300 gram", null), "another product that begins the same")
        assertFalse(isSameProduct("AH Snoepgroente tomaat", "250 g", "AH Snoepgroente tomaat 500 gram", null), "another pack")
        assertFalse(isSameProduct("AH Halfvolle melk", "1 l", "AH Halfvolle melk", null), "no size on the offer: the pack can't be told")
        assertFalse(isSameProduct("AH Halfvolle melk", null, "AH Halfvolle melk 1 liter", null), "no size looked up")
        assertFalse(isSameProduct("AH Volle melk", "1 l", "AH Halfvolle melk 1 liter", null))
    }
}
