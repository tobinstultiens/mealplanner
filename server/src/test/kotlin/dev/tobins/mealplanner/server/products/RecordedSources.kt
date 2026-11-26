package dev.tobins.mealplanner.server.products

import dev.tobins.mealplanner.server.offers.AhApp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant

/**
 * AH and Open Food Facts as they answered on 26 November 2026 (issue 339, step 0): trimmed copies of their answers under
 * `/products`, served by a fake HTTP client, so that no test asks a real source. A product, barcode or search with no
 * recording is one the source does not have. [requests] has each request as `host path`.
 */
class RecordedSources {
    val requests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** The `User-Agent` of each request to Open Food Facts. */
    val offAgents: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Barcodes AH answers 404 for, although it has a recording of them. */
    val unknownAtAh = mutableSetOf<String>()

    /** While set, AH answers every request after the token with this status. */
    var ahStatus: HttpStatusCode? = null

    /** Changes a recorded answer of AH before it is served, as AH could have written it. */
    var ahEdit: (String) -> String = { it }

    /** The time [limit] goes by; moved on by a test. */
    var now: Instant = Instant.parse("2026-11-26T12:00:00Z")

    val limit = RequestLimit(15, Duration.ofMinutes(1)) { now }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val engine = MockEngine { request ->
        requests += "${request.url.host} ${request.url.encodedPath}"
        if (request.url.host == "world.openfoodfacts.org") off(request) else ah(request)
    }

    private val http = HttpClient(engine)

    val ah = AhProducts(AhApp(http))

    fun off(contact: String = "owner@example.org") = OpenFoodFacts(http, contact, limit)

    fun finder(withOff: Boolean = true) = ProductFinder(ah, if (withOff) off() else null)

    private fun MockRequestHandleScope.ah(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        if (path.endsWith("/token/anonymous")) return respond("""{"access_token":"tok"}""", headers = json)
        ahStatus?.let { return respond("", it) }
        if (request.headers[HttpHeaders.Authorization] != "Bearer tok") return respond("", HttpStatusCode.Unauthorized)
        val answer = when {
            path == "/graphql" -> {
                val sent = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val id = sent.getValue("variables").jsonObject.getValue("productId").jsonPrimitive.int
                recording("ah-graphql-product-$id.json") ?: recording("ah-graphql-unknown-id.json")
            }
            path == "/mobile-services/product/search/v2" -> recording("ah-search-drinkyoghurt.json")
            path.startsWith("/mobile-services/product/search/v1/gtin/") -> {
                val code = path.substringAfterLast('/')
                if (code in unknownAtAh) null else recording("ah-gtin-$code.json")
            }
            else -> null
        }
        return answer?.let { respond(ahEdit(it), headers = json) } ?: respond("", HttpStatusCode.NotFound)
    }

    private fun MockRequestHandleScope.off(request: HttpRequestData): HttpResponseData {
        offAgents += request.headers[HttpHeaders.UserAgent].orEmpty()
        val code = request.url.encodedPath.removePrefix("/api/v3/product/")
        return recording("off-v3-product-$code.json")?.let { respond(it, headers = json) }
            ?: respond(recording("off-v3-unknown.json")!!.replace("8710000000005", code), HttpStatusCode.NotFound, json)
    }

    companion object {
        fun recording(name: String): String? = RecordedSources::class.java.getResource("/products/$name")?.readText()
    }
}
