package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.offers.OfferSource
import dev.tobins.mealplanner.server.prices.AhProductParser
import dev.tobins.mealplanner.server.prices.PriceSource
import dev.tobins.mealplanner.server.prices.StoreProduct
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PricesTest {
    @Test
    fun ahSearchResultsGiveTheRegularPricePerPack() {
        val products = AhProductParser.products(javaClass.getResource("/prices/ah-search.json")!!.readText())

        // The product without a regular price (sold online only) is left out.
        assertEquals(5, products.size)
        val onOffer = products.single { it.title == "AH Scharrel roasted kipfilet 4-pack" }
        assertEquals(15.16, onOffer.regularPrice, "the price before the offer")
        assertEquals("4 stuks", onOffer.packSize)
        assertEquals("Vleeswaren", onOffer.category)
        assertEquals("prijs per kg €16.60", products.first().unitPrice)
    }

    private class FakeStore(val products: Map<String, List<StoreProduct>>) : PriceSource {
        override val store = Store.AH
        val searched = mutableListOf<String>()

        override suspend fun search(term: String): List<StoreProduct> {
            searched += term
            return products[term].orEmpty()
        }
    }

    private class FixedOffers(val offers: List<Offer>) : OfferSource {
        override val store = Store.AH
        override suspend fun fetch() = offers
    }

    private fun product(title: String, size: String, price: Double, category: String? = null) =
        StoreProduct(title, size, price, null, category)

    @Test
    fun lookupPricesIngredientsAndOffersLowerTheirCost() = runBlocking {
        val db = openDatabase("jdbc:sqlite::memory:").second
        val ingredients = IngredientRepository(db, FoodRepository(db))
        val chicken = ingredients.create(IngredientInput("Chicken fillet"))
        ingredients.map(MappingRequest("kipfilet", ingredientId = chicken.id))
        val onion = ingredients.create(IngredientInput("Onion", gramsPerPiece = 100.0))
        ingredients.map(MappingRequest("ui", ingredientId = onion.id))
        val tofu = ingredients.create(IngredientInput("Tofu", pricePerKg = 6.0))
        ingredients.create(IngredientInput("Saffron"))
        val store = FakeStore(
            mapOf(
                "kipfilet" to listOf(
                    product("AH Kipfilet", "500 g", 6.0), // 12/kg
                    product("AH Scharrel kipfilet", "1 kg", 10.0), // 10/kg
                    product("AH Biologisch kipfilet", "300 g", 6.0), // 20/kg
                    product("AH Kipfilet vleeswaren", "150 g", 2.0, category = "Vleeswaren"), // cold cuts: not counted
                    product("AH Kipfilet pastasaus", "400 g", 1.0), // a sauce: not counted
                    product("AH Broodje kipfilet", "100 g", 0.5, category = "Bakkerij"), // 5/kg, but a stray aisle
                ),
                "chicken fillet" to listOf(product("AH Kipfilet", "500 g", 6.0)),
            ),
        )
        val week = LocalDate.of(2026, 11, 16)
        val offers = OfferRepository(
            db,
            listOf(FixedOffers(listOf(Offer(Store.AH, "AH Kipfilet", "25% korting", validFrom = "2026-11-16", validUntil = "2026-11-22")))),
        )
        offers.refresh()
        val prices = PriceRepository(db, store, ingredients, offers)

        val state = prices.refresh(pause = Duration.ZERO)

        // The middle of the three cheapest chicken fillets per kg; one search per name, duplicates once.
        val chickenPrice = state.prices.single { it.ingredientId == chicken.id }
        assertEquals(12.0, chickenPrice.eurosPerKg)
        assertEquals("AH Kipfilet", chickenPrice.product)
        assertEquals(6.0, state.prices.single { it.ingredientId == tofu.id }.eurosPerKg, "set by hand")
        assertEquals(listOf("Onion", "Saffron"), state.unpriced)
        assertTrue("kipfilet" in store.searched && "chicken fillet" in store.searched)
        assertTrue(state.fetchedAt != null)

        val pricing = prices.pricing(week, week.plusDays(6))
        assertEquals(0.75, pricing.discounts[chicken.id])
        assertNull(pricing.discounts[onion.id])

        // A second call within the week does nothing.
        store.searched.clear()
        prices.refresh(olderThan = Duration.ofDays(7), pause = Duration.ZERO)
        assertEquals(emptyList(), store.searched)
    }

    @Test
    fun aStoreThatKeepsFailingStopsTheLookup() = runBlocking {
        val db = openDatabase("jdbc:sqlite::memory:").second
        val ingredients = IngredientRepository(db, FoodRepository(db))
        (1..8).forEach { ingredients.create(IngredientInput("Thing $it")) }
        val broken = object : PriceSource {
            override val store = Store.AH
            var calls = 0
            override suspend fun search(term: String): List<StoreProduct> {
                calls++
                error("AH answered 503")
            }
        }
        val prices = PriceRepository(db, broken, ingredients, OfferRepository(db, emptyList()))

        val state = prices.refresh(now = Instant.parse("2026-11-13T10:00:00Z"), pause = Duration.ZERO)

        assertEquals(5, broken.calls)
        assertNull(state.fetchedAt, "an aborted lookup is not recorded as done")
    }

    private fun assertEquals(expected: Double, actual: Double?, message: String? = null) =
        assertTrue(actual != null && abs(expected - actual) < 1e-9, "${message ?: ""} expected $expected, got $actual")
}
