package dev.tobins.mealplanner.server.ingredients

import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.ProductLookup
import dev.tobins.mealplanner.api.ProductSearch
import dev.tobins.mealplanner.server.ServerConfig
import dev.tobins.mealplanner.server.TestTokens
import dev.tobins.mealplanner.server.ingredients.products.RecordedSources
import dev.tobins.mealplanner.server.module
import dev.tobins.mealplanner.server.openDatabase
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The routes of the product lookup (issue 354), on the recorded answers of the sources: behind the login, checked input, nothing saved. */
class ProductLookupApiTest {
    private val sources = RecordedSources()

    private fun ApplicationTestBuilder.start(): HttpClient {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        application {
            module(
                config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null,
                verifier = TestTokens, products = sources.finder(),
            )
        }
        return createClient { install(ContentNegotiation) { json() } }
    }

    @Test
    fun anyAccountLooksAProductUpByNameIdAndBarcodeAndNothingIsSaved() = testApplication {
        val http = start()
        val before = http.get("/api/ingredients") { bearerAuth("t") }.body<List<Ingredient>>()

        val search = http.get("/api/products/search") { bearerAuth("t"); parameter("q", "drinkyoghurt") }.body<ProductSearch>()
        assertEquals("Optimel Drinkyoghurt framboos", search.products.first().name)
        val picked = http.get("/api/products/ah/${search.products.first().id}") { bearerAuth("bob") }.body<ProductLookup>()
        assertEquals(FoodBasis.PER_100_ML, picked.product!!.basis)
        val scanned = http.get("/api/products/barcode/8712800035626") { bearerAuth("bob") }.body<ProductLookup>()
        assertEquals("ah:61758", scanned.product!!.source)

        assertEquals(before, http.get("/api/ingredients") { bearerAuth("t") }.body<List<Ingredient>>())
    }

    @Test
    fun theContactForOpenFoodFactsIsASettingAndBlankIsNone() {
        assertEquals("owner@example.org", ServerConfig.fromEnv(mapOf("MEALPLANNER_OPENFOODFACTS_CONTACT" to " owner@example.org ")).openFoodFactsContact)
        assertNull(ServerConfig.fromEnv(mapOf("MEALPLANNER_OPENFOODFACTS_CONTACT" to " ")).openFoodFactsContact)
        assertNull(ServerConfig.fromEnv(emptyMap()).openFoodFactsContact)
    }

    @Test
    fun withoutALoginNothingIsAsked() = testApplication {
        val http = start()
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/products/search") { parameter("q", "drinkyoghurt") }.status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/products/barcode/8712800035626").status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/products/ah/61758").status)
        assertEquals(emptyList(), sources.requests)
    }

    @Test
    fun inputThatIsNoBarcodeIdOrSearchIsRefusedBeforeASourceIsAsked() = testApplication {
        val http = start()
        for (path in listOf("/api/products/barcode/123", "/api/products/barcode/8712800035626%2F..", "/api/products/ah/abc", "/api/products/ah/61758?packOf=x", "/api/products/search?q=a", "/api/products/search")) {
            assertEquals(HttpStatusCode.BadRequest, http.get(path) { bearerAuth("t") }.status, path)
        }
        assertEquals(emptyList(), sources.requests)
    }
}
