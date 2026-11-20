package dev.tobins.mealplanner.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OfferExclusionTest {
    private fun key(title: String) = OfferExclusion.titleKey(title)

    @Test
    fun aPackSizeInTheTitleIsNotPartOfTheKey() {
        // AH writes the pack into the title (offers of 16 November 2026); another week's pack is the same product.
        assertEquals("ah blauwe bessen", key("AH Blauwe bessen 150 gram"))
        assertEquals(key("AH Blauwe bessen 150 gram"), key("AH Blauwe bessen 300 gram"))
        assertEquals("ah mandarijnen", key("AH Mandarijnen 1 kilo"))
        assertEquals("ah witlof", key("AH Witlof 4 stuks"))
        assertEquals("beemster van de zelfbedieningsafdeling", key("Alle Beemster van de zelfbedieningsafdeling 125-150 gram"))
        assertEquals("rivella", key("Rivella 1.5 literflessen 4-pack"))
        assertEquals(
            "hak witte bonen in tomatensaus of sperziebonen",
            key("Hak witte bonen in tomatensaus 360 gram of sperziebonen 340 gram"),
        )
    }

    @Test
    fun aFirstAlleCaseAndPunctuationDoNotCount() {
        assertEquals(key("Lipton Ice tea 1.5 literflessen 6-pack"), key("Alle Lipton Ice Tea 1.5 liter"))
        assertEquals("jumbo s verse pasta of pastasauzen", key("Alle Jumbo's verse pasta of pastasauzen"))
        assertEquals("croky", key("Alle Croky*"))
        assertEquals("hutspot zuurkool of stamppot aardappelen", key("Hutspot,  zuurkool of stamppot aardappelen"))
        assertEquals("calvé pindakaas", key("Alle Calvé pindakaas 350 gram"), "letters with accents are letters")
    }

    @Test
    fun aNumberWithoutAUnitIsPartOfTheProduct() {
        assertNotEquals(key("Heineken"), key("Heineken 0.0"))
        assertEquals("ah hollandse keuken 3 vaks maaltijden", key("Alle AH Hollandse Keuken 3-vaks-maaltijden"))
        assertEquals("1fruit", key("Alle 1FRUIT"))
    }
}
