package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.WeightLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/** The profile's weight follows the newest weigh-in, whether it is added, changed or removed. */
class WeightProfileTest {
    private fun ApplicationTestBuilder.clientFor(token: String): HttpClient = createClient {
        install(ContentNegotiation) { json() }
        defaultRequest {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
        }
    }

    private suspend fun HttpClient.withProfile(kg: Double = 80.0): HttpClient = apply {
        put("/api/profile") { setBody(Profile(kg, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT)) }
    }

    private fun ApplicationTestBuilder.start(): HttpClient {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        return clientFor("alice")
    }

    private suspend fun HttpClient.weigh(date: String, kg: Double) = put("/api/weights/$date") { setBody(kg) }.body<WeightLog>()
    private suspend fun HttpClient.profileKg() = get("/api/profile").body<ProfileState>().profile?.weightKg
    private suspend fun HttpClient.kcal() = get("/api/profile").body<ProfileState>().targets?.kcal

    @Test
    fun removingTheNewestWeighInPutsTheNewestRemainingOneBack() = testApplication {
        val http = start().withProfile()
        http.weigh("2026-11-10", 80.0)
        val before = http.kcal()
        http.weigh("2026-11-12", 170.0)
        assertEquals(170.0, http.profileKg())

        assertEquals(1, http.delete("/api/weights/2026-11-12").body<WeightLog>().entries.size)
        assertEquals(80.0, http.profileKg())
        assertEquals(before, http.kcal(), "the targets are those of the remaining weigh-in")
    }

    @Test
    fun removingAnOlderWeighInChangesNothing() = testApplication {
        val http = start().withProfile()
        http.weigh("2026-11-10", 85.0)
        http.weigh("2026-11-12", 82.0)
        http.put("/api/profile") { setBody(Profile(81.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT)) }

        http.delete("/api/weights/2026-11-10")
        assertEquals(81.0, http.profileKg(), "a weight the person set by hand stays")
    }

    @Test
    fun removingTheOnlyWeighInKeepsTheProfileWeight() = testApplication {
        val http = start().withProfile()
        http.weigh("2026-11-12", 170.0)
        assertEquals(emptyList(), http.delete("/api/weights/2026-11-12").body<WeightLog>().entries)
        assertEquals(170.0, http.profileKg(), "the weight before the first weigh-in is not stored")
    }

    @Test
    fun changingTheNewestWeighInFollowsAndChangingAnOlderOneDoesNot() = testApplication {
        val http = start().withProfile()
        http.weigh("2026-11-10", 85.0)
        http.weigh("2026-11-12", 82.0)
        http.weigh("2026-11-12", 81.5)
        assertEquals(81.5, http.profileKg())
        http.weigh("2026-11-10", 84.0)
        assertEquals(81.5, http.profileKg())
    }

    @Test
    fun aWeighInForAnEarlierDayDoesNotOverwriteTheProfileWeight() = testApplication {
        val http = start().withProfile()
        http.weigh("2026-11-12", 82.0)
        http.put("/api/profile") { setBody(Profile(81.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT)) }
        http.weigh("2026-11-01", 90.0)
        assertEquals(81.0, http.profileKg())
    }

    @Test
    fun twoWeighInsOnOneDayLeaveTheLastOne() = testApplication {
        val http = start().withProfile()
        http.weigh("2026-11-12", 82.0)
        val log = http.weigh("2026-11-12", 83.0)
        assertEquals(listOf(83.0), log.entries.map { it.kg })
        assertEquals(83.0, http.profileKg())
        http.delete("/api/weights/2026-11-12")
        assertEquals(83.0, http.profileKg())
    }

    @Test
    fun removingAWeighInWithoutAProfileIsFine() = testApplication {
        val http = start()
        http.weigh("2026-11-12", 82.0)
        assertEquals(emptyList(), http.delete("/api/weights/2026-11-12").body<WeightLog>().entries)
        assertEquals(null, http.profileKg())
    }

    @Test
    fun anotherAccountsWeighInsAndProfileStayUntouched() = testApplication {
        val alice = start().withProfile()
        val bob = clientFor("bob").withProfile(70.0)
        alice.weigh("2026-11-10", 80.0)
        bob.weigh("2026-11-11", 71.0)
        alice.weigh("2026-11-12", 170.0)

        alice.delete("/api/weights/2026-11-12")
        alice.delete("/api/weights/2026-11-11")
        assertEquals(80.0, alice.profileKg())
        assertEquals(71.0, bob.profileKg())
        assertEquals(listOf(71.0), bob.get("/api/weights").body<WeightLog>().entries.map { it.kg })
    }
}
