package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.offers.OfferSource
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OffersApiTest {
    private class FakeSource(override val store: Store, var offers: List<Offer>? = null) : OfferSource {
        var calls = 0

        override suspend fun fetch(): List<Offer> {
            calls++
            return offers ?: error("${store.label} is down")
        }
    }

    private fun offer(store: Store, title: String, from: String = "2026-11-09", until: String = "2026-11-15") =
        Offer(store, title, "25% korting", validFrom = from, validUntil = until)

    @Test
    fun refreshStoresOffersPerStoreAndKeepsOldOnesWhenAStoreFails() = testApplication {
        val ah = FakeSource(Store.AH, listOf(offer(Store.AH, "Kipfilet"), offer(Store.AH, "Next week", "2026-11-16", "2026-11-22")))
        val jumbo = FakeSource(Store.JUMBO, listOf(offer(Store.JUMBO, "Zalm")))
        val lidl = FakeSource(Store.LIDL)
        val config = ServerConfig(port = 0, apiToken = "t", databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        application { module(config, openDatabase(config.databaseUrl).second, listOf(ah, jumbo, lidl)) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { bearerAuth("t") }
        }

        var state = http.get("/api/offers?date=2026-11-12").body<OffersState>()
        assertEquals(emptyList(), state.offers)
        assertEquals(listOf(0, 0, 0), state.sources.map { it.offerCount })

        http.post("/api/offers/refresh")
        state = http.get("/api/offers?date=2026-11-12").body()
        assertEquals(listOf("Kipfilet", "Zalm"), state.offers.map { it.title }, "only offers valid on the date")
        val (ahStatus, jumboStatus, lidlStatus) = state.sources
        assertEquals(2, ahStatus.offerCount)
        assertNotNull(jumboStatus.fetchedAt)
        assertNull(lidlStatus.fetchedAt)
        assertEquals("Lidl is down", lidlStatus.error)

        // A later failure keeps the last good offers.
        jumbo.offers = null
        state = http.post("/api/offers/refresh").body()
        assertEquals(1, state.sources[1].offerCount)
        assertEquals("Jumbo is down", state.sources[1].error)
        assertEquals(listOf("Next week"), http.get("/api/offers?date=2026-11-20").body<OffersState>().offers.map { it.title })
        assertEquals(HttpStatusCode.BadRequest, http.get("/api/offers?date=soon").status)
    }

    @Test
    fun scheduledRefreshSkipsStoresFetchedRecently() = runBlocking {
        val ah = FakeSource(Store.AH, listOf(offer(Store.AH, "Kipfilet")))
        val lidl = FakeSource(Store.LIDL)
        val repository = OfferRepository(openDatabase("jdbc:sqlite::memory:").second, listOf(ah, lidl))
        val now = Instant.parse("2026-11-12T10:00:00Z")

        repository.refresh(olderThan = Duration.ofHours(24), now = now)
        repository.refresh(olderThan = Duration.ofHours(24), now = now.plusSeconds(3600))
        assertEquals(1, ah.calls, "fetched an hour ago, so not again")
        assertEquals(2, lidl.calls, "never fetched successfully, so tried every time")

        repository.refresh(olderThan = Duration.ofHours(24), now = now.plus(Duration.ofHours(25)))
        assertEquals(2, ah.calls)
    }
}
