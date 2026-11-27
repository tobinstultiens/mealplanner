package dev.tobins.mealplanner.server.prices

import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.RefusingWrites
import dev.tobins.mealplanner.server.accounts.Identity
import dev.tobins.mealplanner.server.accounts.UserRepository
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.server.prices.offers.OfferSource
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        // The pictures were recorded on 2026-11-15; the 200 px one of the five sizes.
        assertEquals(
            "https://static.ah.nl/dam/product/AHI_43545239393132353833?revLabel=1&rendition=200x200_WEBP&fileType=binary",
            products.first().imageUrl,
        )

        // Fields as AH's search gave them on 2026-11-15; the sub-category places flour on the baking shelf.
        val flour = AhProductParser.products(
            """{"products":[{"title":"AH Tarwebloem","salesUnitSize":"1 kg","priceBeforeBonus":0.99,"mainCategory":"Bakkerij","subCategory":"Tarwebloem"}]}""",
        ).single()
        assertEquals("Bakkerij", flour.category)
        assertEquals("Tarwebloem", flour.subCategory)
        assertNull(flour.imageUrl, "no pictures listed")
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
        val foods = FoodRepository(db)
        val users = UserRepository(db)
        val ingredients = IngredientRepository(db, foods, users.resolve(Identity("a", "alice")).id)
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
        val prices = PriceRepository(db, store, foods, offers)

        prices.refresh(pause = Duration.ZERO)
        val state = prices.state(ingredients)

        // The middle of the three cheapest chicken fillets per kg; one search per name, duplicates once.
        val chickenPrice = state.prices.single { it.ingredientId == chicken.id }
        assertEquals(12.0, chickenPrice.eurosPerKg)
        assertEquals("AH Kipfilet", chickenPrice.product)
        assertEquals(6.0, state.prices.single { it.ingredientId == tofu.id }.eurosPerKg, "set by hand")
        assertEquals(listOf("Onion", "Saffron"), state.unpriced)
        assertTrue("kipfilet" in store.searched && "chicken fillet" in store.searched)
        assertTrue(state.fetchedAt != null)

        val pricing = prices.pricing(ingredients, week, week.plusDays(6))
        assertEquals(0.75, pricing.discounts[chicken.id])
        assertNull(pricing.discounts[onion.id])
        // The offer is AH's: for an account that doesn't shop there it saves nothing, though the
        // offer itself still says what it takes off.
        assertNull(prices.pricing(ingredients, week, week.plusDays(6), setOf(Store.JUMBO, Store.LIDL)).discounts[chicken.id])
        assertEquals(0.75, prices.offers(ingredients, week, week.plusDays(6)).getValue(chicken.id).single().paidShare)

        // A second call within the week does nothing.
        store.searched.clear()
        prices.refresh(olderThan = Duration.ofDays(7), pause = Duration.ZERO)
        assertEquals(emptyList(), store.searched)

        // Prices are shared: another account's ingredient of the same name gets the same one,
        // and its own hand-set price stays its own.
        val bob = IngredientRepository(db, foods, users.resolve(Identity("b", "bob")).id)
        val bobsChicken = bob.create(IngredientInput("chicken fillet"))
        bob.create(IngredientInput("Tofu", pricePerKg = 9.0))
        assertEquals(12.0, prices.state(bob).prices.single { it.ingredientId == bobsChicken.id }.eurosPerKg)
        assertEquals(6.0, prices.state(ingredients).prices.single { it.ingredientId == tofu.id }.eurosPerKg, "alice's tofu is unchanged")
    }

    @Test
    fun theLookupKeepsThePricedProductsPictureFromTheStoresImageHostOnly() = runBlocking {
        val db = openDatabase("jdbc:sqlite::memory:").second
        val foods = FoodRepository(db)
        val users = UserRepository(db)
        val alice = IngredientRepository(db, foods, users.resolve(Identity("a", "alice")).id)
        val chicken = alice.create(IngredientInput("Kipfilet"))
        val onion = alice.create(IngredientInput("Uien"))
        val tofu = alice.create(IngredientInput("Tofu", pricePerKg = 6.0))
        val chickenPicture = "https://static.ah.nl/dam/product/AHI_4354523130303237393132?revLabel=1&rendition=200x200_JPG&fileType=binary"
        val tofuPicture = "https://static.ah.nl/dam/product/AHI_4354523130303135363739?revLabel=1&rendition=200x200_JPG&fileType=binary"
        val store = FakeStore(
            mapOf(
                "kipfilet" to listOf(StoreProduct("AH Kipfilet", "500 g", 6.0, null, null, imageUrl = chickenPicture)),
                "uien" to listOf(StoreProduct("AH Uien", "1 kg", 1.5, null, null, imageUrl = "https://tracker.example/uien.png")),
                "tofu" to listOf(StoreProduct("AH Tofu naturel", "375 g", 2.5, null, null, imageUrl = tofuPicture)),
            ),
        )
        PriceRepository(db, store, foods, OfferRepository(db, emptyList())).refresh(pause = Duration.ZERO)
        val prices = PriceRepository(db, null, foods, OfferRepository(db, emptyList()))

        assertEquals(mapOf(chicken.id to chickenPicture), prices.productImages(alice), "not the onion's foreign picture, nor tofu priced by hand")
        assertEquals(null, db.priceQueries.selectPrices().executeAsList().single { it.name == "Uien" }.imageUrl, "never stored")
        // Shared like the price: Bob's tofu has no hand price, so the product's picture is his.
        val bob = IngredientRepository(db, foods, users.resolve(Identity("b", "bob")).id)
        val bobsTofu = bob.create(IngredientInput("Tofu"))
        assertEquals(tofuPicture, prices.productImages(bob)[bobsTofu.id])
        assertNull(prices.productImages(alice)[tofu.id], "Alice's hand price still holds for her")
        assertNull(prices.productImages(bob)[onion.id], "Alice's ingredient ids mean nothing in Bob's catalogue")
    }

    @Test
    fun aStoreThatKeepsFailingStopsTheLookup() = runBlocking {
        val db = openDatabase("jdbc:sqlite::memory:").second
        val foods = FoodRepository(db)
        val ingredients = IngredientRepository(db, foods, UserRepository(db).resolve(Identity("a")).id)
        (1..8).forEach { ingredients.create(IngredientInput("Thing $it")) }
        val broken = object : PriceSource {
            override val store = Store.AH
            var calls = 0
            override suspend fun search(term: String): List<StoreProduct> {
                calls++
                error("AH answered 503")
            }
        }
        val prices = PriceRepository(db, broken, foods, OfferRepository(db, emptyList()))

        prices.refresh(now = Instant.parse("2026-11-13T10:00:00Z"), pause = Duration.ZERO)
        val state = prices.state(ingredients)

        assertEquals(5, broken.calls)
        assertNull(state.fetchedAt, "an aborted lookup is not recorded as done")
    }

    /** Issue 285: one price that cannot be saved is skipped like a failed search, and the others are still stored. */
    @Test
    fun aPriceThatCannotBeSavedDoesNotEndTheLookup() = runBlocking {
        val opened = openDatabase("jdbc:sqlite::memory:")
        val writes = RefusingWrites(opened.first)
        val db = writes.database
        val (ingredients, products) = tenNames(db)
        val prices = PriceRepository(db, FakeStore(products), FoodRepository(db), OfferRepository(db, emptyList()))
        // Only the first save finds the database busy.
        var first = true
        writes.refuse = { sql -> "INSERT" in sql && "price" in sql.lowercase() && first.also { first = false } }

        prices.refresh(now = Instant.parse("2026-11-13T10:00:00Z"), pause = Duration.ZERO)

        assertEquals(9, prices.state(ingredients).prices.size, "the nine others are priced")
        assertNotNull(prices.state(ingredients).fetchedAt, "the lookup ran to its end")
        opened.first.close()
    }

    private fun tenNames(db: dev.tobins.mealplanner.server.db.Database): Pair<IngredientRepository, Map<String, List<StoreProduct>>> {
        val ingredients = IngredientRepository(db, FoodRepository(db), UserRepository(db).resolve(Identity("a")).id)
        val names = listOf("Kipfilet", "Uien", "Wortel", "Prei", "Courgette", "Paprika", "Tomaat", "Komkommer", "Spinazie", "Champignons")
        names.forEach { ingredients.create(IngredientInput(it)) }
        return ingredients to names.associate { it.lowercase() to listOf(StoreProduct("AH $it", "1 kg", 2.0, null, null, imageUrl = "https://static.ah.nl/dam/product/$it.png")) }
    }

    @Test
    fun aSearchThatFindsNothingForAnyNameKeepsEveryPriceAndDoesNotMoveFetchedAt() = runBlocking {
        val db = openDatabase("jdbc:sqlite::memory:").second
        val foods = FoodRepository(db)
        val (ingredients, products) = tenNames(db)
        PriceRepository(db, FakeStore(products), foods, OfferRepository(db, emptyList())).refresh(now = Instant.parse("2026-11-13T10:00:00Z"), pause = Duration.ZERO)
        val before = db.priceQueries.selectPrices().executeAsList()
        assertEquals(10, before.size)

        val prices = PriceRepository(db, FakeStore(emptyMap()), foods, OfferRepository(db, emptyList()))
        prices.refresh(now = Instant.parse("2026-11-14T10:00:00Z"), pause = Duration.ZERO)

        assertEquals(before, db.priceQueries.selectPrices().executeAsList(), "prices, aisles and pictures as they were")
        assertEquals("2026-11-13T10:00:00Z", prices.state(ingredients).fetchedAt, "the next round tries again")
    }

    @Test
    fun aNameThatFindsNothingWhileMostDoLosesItsPrice() = runBlocking {
        val db = openDatabase("jdbc:sqlite::memory:").second
        val foods = FoodRepository(db)
        val (_, products) = tenNames(db)
        PriceRepository(db, FakeStore(products), foods, OfferRepository(db, emptyList())).refresh(now = Instant.parse("2026-11-13T10:00:00Z"), pause = Duration.ZERO)

        PriceRepository(db, FakeStore(products - "uien"), foods, OfferRepository(db, emptyList())).refresh(now = Instant.parse("2026-11-14T10:00:00Z"), pause = Duration.ZERO)

        assertEquals(9, db.priceQueries.selectPrices().executeAsList().size)
        assertTrue(db.priceQueries.selectPrices().executeAsList().none { it.name == "Uien" })
    }

    private fun assertEquals(expected: Double, actual: Double?, message: String? = null) =
        assertTrue(actual != null && abs(expected - actual) < 1e-9, "${message ?: ""} expected $expected, got $actual")
}
