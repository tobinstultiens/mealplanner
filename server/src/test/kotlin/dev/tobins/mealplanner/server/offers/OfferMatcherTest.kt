package dev.tobins.mealplanner.server.offers

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import kotlin.test.Test
import kotlin.test.assertEquals

class OfferMatcherTest {
    private val chicken = 1L
    private val chickenFillet = 2L
    private val mince = 3L
    private val banana = 4L
    private val mackerel = 5L
    private val cucumber = 6L

    private val matcher = OfferMatcher(
        mapOf(
            chicken to listOf("Chicken (with skin)", "chicken", "kip"),
            chickenFillet to listOf("Chicken fillet", "kipfilet"),
            mince to listOf("Minced beef", "gehakt", "rundergehakt"),
            banana to listOf("Banana", "banaan"),
            mackerel to listOf("Mackerel", "makreelfilet"),
            cucumber to listOf("Cucumber", "komkommer", "ui"),
        ),
    )

    private fun match(title: String, category: String? = null) =
        matcher.match(Offer(Store.AH, title, null, category = category, validFrom = "2026-11-09", validUntil = "2026-11-15"))

    @Test
    fun matchesWholeWordsPluralsAndCompoundEndings() {
        assertEquals(mince, match("AH Gemengd gehakt 500 gram"))
        assertEquals(chicken, match("AH Excellent vrije uitloop kip van de versafdeling"))
        assertEquals(banana, match("Chiquita Ready to eat en Later lekker bananen"))
        assertEquals(mackerel, match("Alle AH Gerookte Atka-makreelfilet"))
        assertEquals(chickenFillet, match("AH Scharrel kipfilet 1 kilo"))
        assertEquals(cucumber, match("AH Biologisch komkommer"))
        assertEquals(chicken, match("Jumbo verse kippen"))
    }

    @Test
    fun doesNotMatchInsideOtherWordsOrNonFoodCategories() {
        assertEquals(null, match("Alle AH Scharrelkipfiletvleeswaren van de versafdeling"), "cold cuts, not fillet")
        assertEquals(null, match("AH Kipkerrie salade"), "short terms only match whole words")
        assertEquals(null, match("Alle Kwekkeboom kip snacks", category = "Diepvries"))
        assertEquals(null, match("Kesbeke Leverworst met komkommer", category = "Vleeswaren"))
        assertEquals(null, match("Banaan roomvlaaitje", category = "Zoet gebak"))
        assertEquals(null, match("Page Compleet Schoon"))
        assertEquals(null, match("AH Uien"), "\"ui\" is under three letters and ignored")
    }

    @Test
    fun sideIngredientsAndPreparedProductsAreNotTheIngredient() {
        val olives = 10L
        val anchovy = 11L
        val basil = 12L
        val soySauce = 13L
        val tuna = 14L
        val products = OfferMatcher(
            mapOf(
                olives to listOf("Olives", "olijven"),
                anchovy to listOf("Anchovy", "ansjovis"),
                basil to listOf("Fresh basil", "basilicum"),
                soySauce to listOf("Soy sauce", "sojasaus", "ketjap asin"),
                tuna to listOf("Tuna", "tonijn"),
            ),
        )

        assertEquals(olives, products.match("Amogusti Olijven gevuld met ansjovis", null))
        assertEquals(tuna, products.match("AH Tonijn met olijven", null), "the longer term comes after \"met\"")
        assertEquals(null, products.match("AH Pastasaus basilicum", null), "a sauce, not basil")
        assertEquals(basil, products.match("AH Basilicum", null))
        assertEquals(soySauce, products.match("Kikkoman Sojasaus", null), "the ingredient is itself a sauce")
        assertEquals(null, products.match("Nutrilon Groente met ansjovis", "Baby en kind"))
        assertEquals(null, products.match("AH Basilicum smaak chips", null), "flavoured snacks are not the ingredient")
    }

    @Test
    fun theLongestTermWins() {
        assertEquals(chickenFillet, match("Kipfilet van de kip"))
        assertEquals(
            mapOf(mince to listOf("Gehakt"), banana to listOf("Bananen")),
            matcher.group(listOf("Gehakt", "Bananen", "Wasmiddel").map { Offer(Store.JUMBO, it, null, validFrom = "x", validUntil = "y") })
                .mapValues { (_, offers) -> offers.map { it.title } },
        )
    }
}
