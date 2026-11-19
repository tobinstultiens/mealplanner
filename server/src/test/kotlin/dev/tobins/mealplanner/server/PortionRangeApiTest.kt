package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.Sex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The profile's portion range (issue 227): stored, validated, and kept by an app that does not know it. */
class PortionRangeApiTest {
    private val profile = Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2200)

    private fun ApplicationTestBuilder.start(): HttpClient {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        return createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
            }
        }
    }

    private suspend fun HttpClient.stored(): Profile = get("/api/profile").body<ProfileState>().profile!!

    @Test
    fun aProfileWithoutARangeHasTheDefaultsAndAChosenOneIsStored() = testApplication {
        val http = start()
        http.put("/api/profile") { setBody(profile) }
        assertEquals(0.75 to 1.5, http.stored().let { it.minPortions to it.maxPortions })

        val saved = http.put("/api/profile") { setBody(profile.copy(minPortions = 1.0, maxPortions = 2.25)) }.body<ProfileState>()
        assertEquals(1.0 to 2.25, saved.profile!!.let { it.minPortions to it.maxPortions })
        assertEquals(profile.copy(minPortions = 1.0, maxPortions = 2.25), http.stored())
    }

    @Test
    fun aRangeOutsideAQuarterToThreeOrNotInQuartersOrUpsideDownIsRefused() = testApplication {
        val http = start()
        http.put("/api/profile") { setBody(profile.copy(minPortions = 1.0, maxPortions = 2.0)) }
        for ((min, max) in listOf(0.0 to 1.5, 0.75 to 3.25, 0.8 to 1.5, 0.75 to 1.6, 1.5 to 1.0)) {
            val response = http.put("/api/profile") { setBody(profile.copy(minPortions = min, maxPortions = max)) }
            assertEquals(HttpStatusCode.BadRequest, response.status, "$min to $max")
            assertTrue("Portions" in response.bodyAsText(), response.bodyAsText())
        }
        assertEquals(1.0 to 2.0, http.stored().let { it.minPortions to it.maxPortions }, "a refused save changes nothing")
        // The bounds themselves are fine, and so is one size only.
        assertEquals(HttpStatusCode.OK, http.put("/api/profile") { setBody(profile.copy(minPortions = 0.25, maxPortions = 3.0)) }.status)
        assertEquals(HttpStatusCode.OK, http.put("/api/profile") { setBody(profile.copy(minPortions = 1.0, maxPortions = 1.0)) }.status)
    }

    @Test
    fun theInstalledAppsProfileSaveKeepsTheStoredRange() = testApplication {
        val http = start()
        http.put("/api/profile") { setBody(profile.copy(minPortions = 1.0, maxPortions = 2.0)) }

        // What an app that never knew the range sends: written by hand, so it stays true whichever
        // released version is frozen.
        val body = """{"weightKg":85.0,"heightCm":180.0,"ageYears":30,"sex":"MALE","activity":"LIGHT","kcalOverride":2200}"""
        assertEquals(HttpStatusCode.OK, http.put("/api/profile") { setBody(body) }.status)

        assertEquals(profile.copy(weightKg = 85.0, minPortions = 1.0, maxPortions = 2.0), http.stored(), "the weight changed, the range stayed")
        // What the installed app reads back is a profile it understands.
        releasedJson.decodeFromString<dev.tobins.mealplanner.released.api.ProfileState>(http.get("/api/profile").bodyAsText())
    }

    @Test
    fun anOlderAppsFirstProfileGetsTheDefaults() = testApplication {
        val http = start()
        // Saved without the fields by an app that never knew them, on an account with no range stored yet.
        http.put("/api/profile") { setBody("""{"weightKg":80.0,"heightCm":180.0,"ageYears":30,"sex":"MALE","activity":"LIGHT"}""") }
        assertEquals(0.75 to 1.5, http.stored().let { it.minPortions to it.maxPortions })
    }
}
