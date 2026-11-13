package dev.tobins.mealplanner.server.prices

import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.offers.ahHeaders
import dev.tobins.mealplanner.server.offers.ahToken
import dev.tobins.mealplanner.server.offers.ok
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant

/** A product as a store's search lists it, with its regular (not discounted) price. */
data class StoreProduct(
    val title: String,
    /** As written by the store: "500 g", "3 stuks". */
    val packSize: String?,
    /** Price of one pack without any offer. */
    val regularPrice: Double?,
    /** The store's own unit price text, e.g. "prijs per kg €16.60". */
    val unitPrice: String?,
    val category: String?,
)

/** Searches one store's products. Throws when the store cannot be read. */
interface PriceSource {
    val store: Store

    suspend fun search(term: String): List<StoreProduct>
}

/** Albert Heijn's product search, through the API its app uses. */
class AhPriceSource(private val http: HttpClient, private val baseUrl: String = "https://api.ah.nl") : PriceSource {
    override val store = Store.AH

    private val lock = Mutex()
    private var token: Pair<String, Instant>? = null

    override suspend fun search(term: String): List<StoreProduct> {
        val body = http.get("$baseUrl/mobile-services/product/search/v2") {
            ahHeaders()
            bearerAuth(token())
            parameter("query", term)
            parameter("sortOn", "RELEVANCE")
            parameter("size", 15)
        }.ok().bodyAsText()
        return AhProductParser.products(body)
    }

    /** Anonymous tokens last a while; one per half hour is plenty. */
    private suspend fun token(): String = lock.withLock {
        token?.takeIf { Duration.between(it.second, Instant.now()) < Duration.ofMinutes(30) }?.first
            ?: ahToken(http, baseUrl).also { token = it to Instant.now() }
    }
}

object AhProductParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Products of a search response. `priceBeforeBonus` is the regular price, also while a
     * product is on offer; products without one (only sold online, sold out) are left out.
     */
    fun products(body: String): List<StoreProduct> =
        json.parseToJsonElement(body).jsonObject["products"]?.jsonArray.orEmpty().mapNotNull { element ->
            val p = element as? JsonObject ?: return@mapNotNull null
            val title = p.string("title") ?: return@mapNotNull null
            StoreProduct(
                title = title,
                packSize = p.string("salesUnitSize"),
                regularPrice = p["priceBeforeBonus"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                unitPrice = p.string("unitPriceDescription"),
                category = p.string("mainCategory"),
            )
        }

    private fun JsonObject.string(key: String) = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
}
