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
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.contentType
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ActivityLevel
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
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        application { module(config, openDatabase(config.databaseUrl).second, listOf(ah, jumbo, lidl), verifier = TestTokens) }
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

    @Test
    fun shoppingListShowsOffersForItsIngredients() = testApplication {
        val nevo = listOf(
            "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
            "NEVO-Online 2025 9.0|Vlees|Meat|1405|Rundergehakt rauw|Minced beef raw||per 100g|200|20|13|5|0|0|0|60",
        ).joinToString("\r\n")
        val repo = kotlin.io.path.createTempDirectory("cooking").toFile()
        java.io.File(repo, "content/recipes/chili.md").apply { parentFile.mkdirs() }
            .writeText("---\ntitle: Chili\n---\n```ingredients\nservings: 4\n1200 | g | ground beef\n```")
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = repo,
            nevoFile = kotlin.io.path.createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        val ah = FakeSource(Store.AH, listOf(
            Offer(Store.AH, "AH Gemengd gehakt 500 gram", "VOOR 3.49", price = 3.49, category = "Vlees", validFrom = "2026-11-09", validUntil = "2026-11-15"),
            Offer(Store.AH, "Kwekkeboom gehaktballen", "2 voor 5.99", category = "Diepvries", validFrom = "2026-11-09", validUntil = "2026-11-15"),
        ))
        val lidl = FakeSource(Store.LIDL, listOf(Offer(Store.LIDL, "Rundergehakt", null, price = 4.29, validFrom = "2026-11-17", validUntil = "2026-11-22")))
        application { module(config, openDatabase(config.databaseUrl).second, listOf(ah, lidl), verifier = TestTokens) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(io.ktor.http.ContentType.Application.Json)
            }
        }
        http.post("/api/sync/cooking")
        http.post("/api/offers/refresh")
        http.put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        http.post("/api/plans/2026-11-09/generate")

        val mince = http.get("/api/plans/2026-11-09/shopping").body<ShoppingList>().items.single { it.name == "Minced beef" }
        assertEquals(listOf("AH Gemengd gehakt 500 gram"), mince.offers.map { it.title }, "frozen meatballs are not mince; Lidl's offer is next week")

        http.post("/api/plans/2026-11-16/generate")
        val nextWeek = http.get("/api/plans/2026-11-16/shopping").body<ShoppingList>().items.single { it.name == "Minced beef" }
        assertEquals(listOf(Store.LIDL), nextWeek.offers.map { it.store }, "the AH offer ends on the 15th")
    }
}
