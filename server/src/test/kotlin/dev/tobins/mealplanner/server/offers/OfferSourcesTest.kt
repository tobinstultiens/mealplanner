package dev.tobins.mealplanner.server.offers

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OfferSourcesTest {
    @Test
    fun ahFetchesTokenThenMetadataThenEachSection() = runBlocking {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.encodedPath
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.encodedPath.endsWith("/token/anonymous") -> respond("""{"access_token":"tok"}""", headers = json)
                request.headers[HttpHeaders.Authorization] != "Bearer tok" -> respond("", HttpStatusCode.Unauthorized)
                request.url.encodedPath.endsWith("/metadata") -> respond(fixture("ah-metadata.json"), headers = json)
                request.url.parameters["category"] == "Vlees" -> respond(fixture("ah-section.json"), headers = json)
                else -> respond("""{"bonusGroupOrProducts":[]}""", headers = json)
            }
        }
        val offers = AhOfferSource(HttpClient(engine)).fetch()

        assertEquals(4, offers.size)
        assertEquals(4, requests.size, "token, metadata and two sections")
    }

    @Test
    fun aFailingStoreThrowsWithItsStatus() = runBlocking {
        val engine = MockEngine { respond("busy", HttpStatusCode.ServiceUnavailable) }
        val error = assertFailsWith<IllegalStateException> { JumboOfferSource(HttpClient(engine)).fetch() }
        assertTrue("503" in error.message!!)

        val emptyPage = MockEngine { respond("<html></html>") }
        assertFailsWith<IllegalStateException> { LidlOfferSource(HttpClient(emptyPage)).fetch() }
        assertFailsWith<IllegalStateException> { JumboOfferSource(HttpClient(emptyPage)).fetch() }
    }

    /** Fetches from the real stores. Run with LIVE_OFFERS=1; skipped otherwise. */
    @Test
    fun liveStores() = runBlocking {
        if (System.getenv("LIVE_OFFERS") != "1") return@runBlocking
        val http = HttpClient()
        for (source in listOf(AhOfferSource(http), JumboOfferSource(http), LidlOfferSource(http))) {
            val offers = source.fetch()
            println("${source.store}: ${offers.size} offers, e.g. ${offers.take(3).map { "${it.title} (${it.deal})" }}")
            assertTrue(offers.size > 10, "${source.store} returned only ${offers.size} offers")
        }
    }
}
