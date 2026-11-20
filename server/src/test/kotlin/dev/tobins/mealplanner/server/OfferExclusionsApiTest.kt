package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferExclusionRequest
import dev.tobins.mealplanner.api.OfferExclusions
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.offers.OfferSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Not this ingredient" (issue 316): the routes that keep an account's exclusions, and what `GET /api/offers` makes of them. */
class OfferExclusionsApiTest {
    private class Offers(override val store: Store, var offers: List<Offer>) : OfferSource {
        override suspend fun fetch() = offers
    }

    private val today = serverToday()

    private fun offer(store: Store, title: String, pack: String? = null, weeks: Long = 0) = Offer(
        store, title, "25% korting", packSize = pack,
        validFrom = today.plusWeeks(weeks).toString(), validUntil = today.plusWeeks(weeks).plusDays(6).toString(),
    )

    /** Recorded on 18 November 2026: a curry sauce, matched as curry powder by its word "curry". */
    private val jumbo = Offers(Store.JUMBO, listOf(offer(Store.JUMBO, "Hela curry", "Fles 800 ml"), offer(Store.JUMBO, "Verstegen curry")))
    private val ah = Offers(Store.AH, listOf(offer(Store.AH, "Hela curry")))

    private fun ApplicationTestBuilder.start(): HttpClient {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        application { module(config, openDatabase(config.databaseUrl).second, listOf(jumbo, ah), priceSource = null, generator = null, verifier = TestTokens) }
        return createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
    }

    private suspend fun HttpClient.curryPowder(): Ingredient =
        post("/api/ingredients") { setBody(IngredientInput("Curry powder")) }.body<Ingredient>().also {
            put("/api/mappings") { setBody(MappingRequest("curry", ingredientId = it.id)) }
        }

    private suspend fun HttpClient.matched(): List<Triple<Store, String, String?>> =
        get("/api/offers").body<OffersState>().offers.map { Triple(it.store, it.title, it.ingredientName) }.sortedBy { "${it.first}${it.second}" }

    private suspend fun HttpClient.exclude(request: OfferExclusionRequest): HttpResponse = put("/api/offers/exclusions") { setBody(request) }

    private suspend fun HttpClient.takeBack(store: String, title: String, ingredientId: Any): HttpResponse = delete("/api/offers/exclusions") {
        url.parameters.append("store", store)
        url.parameters.append("title", title)
        url.parameters.append("ingredientId", ingredientId.toString())
    }

    @Test
    fun anExcludedOfferIsNoLongerTheIngredientAtThatStoreInAnyPackOrWeekUntilTakenBack() = testApplication {
        val http = start()
        http.post("/api/offers/refresh")
        val curry = http.curryPowder()
        val all = listOf<Triple<Store, String, String?>>(
            Triple(Store.AH, "Hela curry", "Curry powder"),
            Triple(Store.JUMBO, "Hela curry", "Curry powder"),
            Triple(Store.JUMBO, "Verstegen curry", "Curry powder"),
        )
        assertEquals(all, http.matched())
        val before = Instant.now()

        val listed = http.exclude(OfferExclusionRequest(Store.JUMBO, "Hela curry", curry.id)).body<OfferExclusions>().exclusions.single()

        assertEquals(listOf(Store.JUMBO, "Hela curry", "hela curry", curry.id, "Curry powder"), listOf(listed.store, listed.title, listed.titleKey, listed.ingredientId, listed.ingredientName))
        assertTrue(Instant.parse(listed.excludedAt) >= before.minusSeconds(1), listed.excludedAt)
        assertEquals(listOf(listed), http.get("/api/offers/exclusions").body<OfferExclusions>().exclusions)
        assertEquals(listOf(all[0], all[1].copy(third = null), all[2]), http.matched(), "Jumbo's Hela curry only")
        // Next week Jumbo sells it in another bottle, with the pack in the title: the same product.
        jumbo.offers = listOf(offer(Store.JUMBO, "Alle Hela Curry 500 ml", weeks = 1), offer(Store.JUMBO, "Verstegen curry", weeks = 1))
        http.post("/api/offers/refresh")
        assertEquals(null, http.get("/api/offers?date=${today.plusWeeks(1)}").body<OffersState>().offers.single { it.title.startsWith("Alle Hela") }.ingredientId)

        // Taken back by the title of either week; again, it changes nothing.
        assertEquals(emptyList(), http.takeBack("JUMBO", "Alle Hela Curry 500 ml", curry.id).body<OfferExclusions>().exclusions)
        assertEquals(HttpStatusCode.OK, http.takeBack("JUMBO", "Hela curry", curry.id).status)
        assertEquals("Curry powder", http.get("/api/offers?date=${today.plusWeeks(1)}").body<OffersState>().offers.single { it.title.startsWith("Alle Hela") }.ingredientName)
    }

    @Test
    fun anExclusionSentTwiceOrLateKeepsTheFirst() = testApplication {
        val http = start()
        http.post("/api/offers/refresh")
        val curry = http.curryPowder()
        val first = http.exclude(OfferExclusionRequest(Store.JUMBO, "Hela curry", curry.id)).body<OfferExclusions>()

        // The phone sends it again, then once more from the queue it kept offline, with next week's title.
        assertEquals(first, http.exclude(OfferExclusionRequest(Store.JUMBO, "Hela curry", curry.id)).body<OfferExclusions>())
        assertEquals(first, http.exclude(OfferExclusionRequest(Store.JUMBO, "  Alle Hela Curry 500 ml ", curry.id)).body<OfferExclusions>())
        // Another store's, and another ingredient's, are exclusions of their own; the latest first.
        val other = http.post("/api/ingredients") { setBody(IngredientInput("Ketchup")) }.body<Ingredient>()
        val three = http.exclude(OfferExclusionRequest(Store.AH, "Hela curry", curry.id)).body<OfferExclusions>().exclusions
        assertEquals(listOf(Store.AH, Store.JUMBO), three.map { it.store })
        val four = http.exclude(OfferExclusionRequest(Store.AH, "Hela curry", other.id)).body<OfferExclusions>().exclusions
        assertEquals(listOf("Ketchup" to Store.AH, "Curry powder" to Store.AH, "Curry powder" to Store.JUMBO), four.map { it.ingredientName to it.store })
    }

    @Test
    fun whatIsNoExclusionIsRefused() = testApplication {
        val http = start()
        val curry = http.curryPowder()
        assertEquals(HttpStatusCode.NotFound, http.exclude(OfferExclusionRequest(Store.JUMBO, "Hela curry", curry.id + 100)).status, "no such ingredient")
        assertEquals(HttpStatusCode.BadRequest, http.exclude(OfferExclusionRequest(Store.JUMBO, " 500 gram ", curry.id)).status, "no words")
        assertEquals(HttpStatusCode.BadRequest, http.exclude(OfferExclusionRequest(Store.JUMBO, "x".repeat(OfferExclusionRequest.MAX_TITLE_LENGTH + 1), curry.id)).status)
        assertEquals(HttpStatusCode.BadRequest, http.put("/api/offers/exclusions") { setBody("""{"store":"PLUS","title":"Hela curry","ingredientId":${curry.id}}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, http.takeBack("PLUS", "Hela curry", curry.id).status)
        assertEquals(HttpStatusCode.BadRequest, http.takeBack("JUMBO", "Hela curry", "one").status)
        assertEquals(HttpStatusCode.BadRequest, http.delete("/api/offers/exclusions?store=JUMBO&ingredientId=${curry.id}").status, "no title")
        assertEquals(emptyList(), http.get("/api/offers/exclusions").body<OfferExclusions>().exclusions)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/offers/exclusions").status)
    }
}
