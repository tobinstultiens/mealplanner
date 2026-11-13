package dev.tobins.mealplanner.server.offers

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Fetches one store's current offers. Throws when the store cannot be read. */
interface OfferSource {
    val store: Store

    suspend fun fetch(): List<Offer>
}

/** A browser-like user agent; the store sites serve their normal pages to it. */
private const val BROWSER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

/**
 * Albert Heijn, through the API its app uses: an anonymous token, the bonus metadata
 * listing the week's sections, then each food section.
 */
class AhOfferSource(private val http: HttpClient, private val baseUrl: String = "https://api.ah.nl") : OfferSource {
    override val store = Store.AH

    override suspend fun fetch(): List<Offer> {
        val tokenBody = http.post("$baseUrl/mobile-auth/v1/auth/token/anonymous") {
            appHeaders()
            contentType(ContentType.Application.Json)
            setBody("""{"clientId":"appie"}""")
        }.ok().bodyAsText()
        val token = Json.parseToJsonElement(tokenBody).jsonObject["access_token"]?.jsonPrimitive?.content
            ?: error("AH returned no access token")

        val metadata = http.get("$baseUrl/mobile-services/bonuspage/v3/metadata") {
            appHeaders()
            bearerAuth(token)
        }.ok().bodyAsText()
        val sections = AhParser.sectionPaths(metadata)
        check(sections.isNotEmpty()) { "AH bonus metadata lists no sections" }

        return sections.flatMap { path ->
            AhParser.section(http.get("$baseUrl/mobile-services/$path") { appHeaders(); bearerAuth(token) }.ok().bodyAsText())
        }.distinctBy { it.url ?: it.title }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appHeaders() {
        header("User-Agent", "Appie/8.60.0")
        header("x-application", "AHWEBSHOP")
    }
}

/** Jumbo, from the data embedded in its offers page. */
class JumboOfferSource(private val http: HttpClient, private val url: String = "https://www.jumbo.com/aanbiedingen/nu") : OfferSource {
    override val store = Store.JUMBO

    override suspend fun fetch(): List<Offer> {
        val page = http.get(url) { browserHeaders() }.ok().bodyAsText()
        return JumboParser.page(page).also { check(it.isNotEmpty()) { "Jumbo offers page held no promotions; its layout may have changed" } }
    }
}

/**
 * Lidl, from the product tiles of its offers page. The page shows only the first batch
 * of tiles (about 50), so later items of a large folder are missed.
 */
class LidlOfferSource(private val http: HttpClient, private val url: String = "https://www.lidl.nl/c/aanbiedingen/a10008785") : OfferSource {
    override val store = Store.LIDL

    override suspend fun fetch(): List<Offer> {
        val page = http.get(url) { browserHeaders() }.ok().bodyAsText()
        check(page.contains("data-grid-data")) { "Lidl offers page held no product tiles; its layout or address may have changed" }
        return LidlParser.page(page)
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.browserHeaders() {
    header("User-Agent", BROWSER_AGENT)
    header("Accept-Language", "nl-NL,nl;q=0.9")
}

private fun io.ktor.client.statement.HttpResponse.ok() = apply {
    check(status.value in 200..299) { "${call.request.url.host} answered ${status.value}" }
}
