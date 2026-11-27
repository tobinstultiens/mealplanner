package dev.tobins.mealplanner.server.prices.offers

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
    fun sausagesSpreadsAndReadyMadeMeatAreNotTheMeatTheyAreMadeFrom() {
        // Offers recorded in the week of 16 November 2026.
        assertEquals(null, match("Kips snijleverworst"), "liver sausage of the brand Kips")
        assertEquals(null, match("Kips kleintje smeerleverworst"))
        assertEquals(null, match("Kips roompaté"), "a spread")
        assertEquals(null, match("Kip schnitzel, kip cordon bleu, kip burger of kip gehaktbal"))
        assertEquals(null, match("Alle Easy Chicken Tonight of Easy Asia Tonight"), "cooking sauces")
        assertEquals(null, match("Gehakt cordon bleu", category = "Diepvriesvlees & gevogelte"))
        assertEquals(null, match("Optimel drinkyoghurt"), "a drink, not the plain yoghurt")
        assertEquals(chicken, match("Jumbo kip of kippen"), "the plural of kip is kippen")
        assertEquals(mince, match("Plakjes Jumbo gehaktbal of grillgehakt"))
    }

    @Test
    fun anIngredientThatIsItselfASausageStillMatches() {
        val sausage = 20L
        val products = OfferMatcher(mapOf(sausage to listOf("Smoked sausage", "rookworst")))
        assertEquals(sausage, products.match("Unox Rookworst", null))
    }

    @Test
    fun sauzenIsThePluralOfSausAndKazenOfKaas() {
        val pasta = 30L
        val tomatoSauce = 31L
        val cheese = 32L
        val products = OfferMatcher(
            mapOf(pasta to listOf("Pasta", "pasta"), tomatoSauce to listOf("Tomato sauce", "tomatensaus"), cheese to listOf("Cheese", "kaas")),
        )
        // Recorded on 18 November 2026: fresh pasta, or sauces for it; a title of two products is no plain pasta.
        assertEquals(null, products.match("Alle Jumbo's verse pasta of pastasauzen", null))
        assertEquals(tomatoSauce, products.match("Grand'Italia tomatensauzen", null), "the ingredient is itself a sauce")
        assertEquals(cheese, products.match("Alle Franse kazen", null))
        assertEquals(pasta, products.match("Jumbo verse pasta", null))
    }

    @Test
    fun aCompoundWhoseFrontIsAFoodOfItsOwnIsNotTheIngredientOfItsEnd() {
        val beefSteak = 40L
        val roll = 41L
        val cinnamon = 42L
        val tomato = 43L
        val ketchup = 44L
        val venison = 45L
        val products = OfferMatcher(
            mapOf(
                beefSteak to listOf("Beef steak", "biefstuk"),
                roll to listOf("White bread roll", "bolletje", "broodje"),
                cinnamon to listOf("Cinnamon", "kaneel"),
                tomato to listOf("Tomato", "tomaat"),
                ketchup to listOf("Tomato ketchup", "ketchup"),
                mince to listOf("Minced beef", "gehakt"),
                chicken to listOf("Chicken", "kip"),
            ),
        )
        // Recorded in the week of 16 November 2026.
        assertEquals(null, products.match("AH Excellent hertenbiefstuk", null), "venison, an animal before the steak")
        assertEquals(null, products.match("Kaneelbroodjes", null), "cinnamon buns: another of the catalogue's ingredients before the roll")
        assertEquals(null, products.match("Jumbo kipgehakt", null), "chicken mince is no minced beef")
        assertEquals(roll, products.match("Tijgerbroodje", null), "a tiger roll is a white roll: \"tijger\" is no food")
        assertEquals(mince, products.match("Plakjes Jumbo gehaktbal of grillgehakt", null))
        assertEquals(ketchup, products.match("Oliehoorn Tomatenketchup", null), "tomato is what the ingredient's own name says it is made of")
        assertEquals(beefSteak, products.match("AH Biefstuk", null))
        // An ingredient known by the compound itself still matches it.
        val withVenison = OfferMatcher(mapOf(beefSteak to listOf("Beef steak", "biefstuk"), venison to listOf("Venison steak", "hertenbiefstuk")))
        assertEquals(venison, withVenison.match("AH Excellent hertenbiefstuk", null))
    }

    @Test
    fun zoeteAardappelIsNoPotatoUnlessItIsTheIngredientsOwnName() {
        val potato = 50L
        val sweetPotato = 51L
        val sprouts = 52L
        val apple = 53L
        val terms = mapOf(potato to listOf("Potato", "aardappel"), sprouts to listOf("Brussels sprouts", "spruiten"), apple to listOf("Apple", "appel"))
        val products = OfferMatcher(terms)
        // Recorded at Lidl on 16 November 2026.
        assertEquals(null, products.match("Zoete aardappels", null))
        assertEquals(sprouts, products.match("Spruiten of zoete aardappels", null), "the rest of the title still counts")
        assertEquals(potato, products.match("Spruiten of kruimige of vastkokende aardappelen", null))
        assertEquals(apple, products.match("AH Zoete appels", null), "\"zoete\" alone is no other food")
        val withSweetPotato = OfferMatcher(terms + (sweetPotato to listOf("Sweet potato", "zoete aardappel")))
        assertEquals(sweetPotato, withSweetPotato.match("Zoete aardappels", null))
        val knownAsPotato = OfferMatcher(mapOf(potato to listOf("Potato", "aardappel", "zoete aardappel")))
        assertEquals(potato, knownAsPotato.match("Zoete aardappels", null), "the account's own alias")
    }

    @Test
    fun anEnglishAliasOfThreeLettersMatchesItsPluralButKipsIsNoChicken() {
        val fig = 60L
        val rib = 61L
        val nut = 62L
        val products = OfferMatcher(mapOf(fig to listOf("Fig", "fig"), rib to listOf("Pork ribs", "rib"), nut to listOf("Mixed nuts", "nut"), chicken to listOf("Chicken", "kip")))
        assertEquals(fig, products.match("Dried figs", null))
        assertEquals(rib, products.match("Jumbo spare ribs", null))
        assertEquals(nut, products.match("Salted nuts", null))
        assertEquals(null, products.match("Kips leverkaas", null), "Dutch \"kip\" takes no plain -s: Kips is a brand")
        assertEquals(chicken, products.match("Jumbo kippen", null))
    }

    @Test
    fun anOfferTheAccountSaidIsNotTheIngredientIsNotItInAnyPackOrWeekAtThatStore() {
        val curryPowder = 70L
        val currySauce = 71L
        val terms = mapOf(curryPowder to listOf("Curry powder", "curry"), chicken to listOf("Chicken", "kip"))
        val excluded = setOf(ExcludedMatch(Store.JUMBO, "hela curry", curryPowder), ExcludedMatch(Store.AH, "ah kipfilet", chicken))
        val products = OfferMatcher(OfferTerms(terms, excluded))
        fun offer(store: Store, title: String) = Offer(store, title, null, validFrom = "2026-11-18", validUntil = "2026-11-24")

        assertEquals(null, products.match(offer(Store.JUMBO, "Hela curry")))
        assertEquals(null, products.match(offer(Store.JUMBO, "Alle Hela Curry 500 ml")), "another pack, another week: the same title's key")
        assertEquals(curryPowder, products.match(offer(Store.AH, "Hela curry")), "at another store it is another offer")
        assertEquals(curryPowder, products.match(offer(Store.JUMBO, "Verstegen curry")))
        assertEquals(curryPowder, OfferMatcher(terms).match(offer(Store.JUMBO, "Hela curry")), "without the account's exclusions")
        assertEquals(null, products.match("Hela curry", null, store = Store.JUMBO), "a title read without an offer, as the history's rows")
        // Another ingredient of the title still matches: the offer is only not the one excluded.
        val withSauce = OfferMatcher(OfferTerms(terms + (currySauce to listOf("Curry sauce", "hela curry")), setOf(ExcludedMatch(Store.JUMBO, "hela curry", currySauce))))
        assertEquals(curryPowder, withSauce.match(offer(Store.JUMBO, "Hela curry")))
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
