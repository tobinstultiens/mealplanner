package dev.tobins.mealplanner.server.prices.offers

import dev.tobins.mealplanner.server.prices.AhPriceSource
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/**
 * AH's app API with one anonymous token, shared by what the server asks it at any time: the regular prices
 * ([dev.tobins.mealplanner.server.prices.AhPriceSource]) and the product lookup (issue 354). The weekly offers fetch a
 * token of their own ([AhOfferSource]). [path] is below [baseUrl], starting with a slash.
 */
class AhApp(private val http: HttpClient, private val baseUrl: String = "https://api.ah.nl") {
    private val lock = Mutex()
    private var token: Pair<String, Instant>? = null

    suspend fun get(path: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val token = token()
        return http.get("$baseUrl$path") { signed(token); block() }
    }

    suspend fun post(path: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val token = token()
        return http.post("$baseUrl$path") { signed(token); block() }
    }

    private fun HttpRequestBuilder.signed(token: String) {
        ahHeaders()
        bearerAuth(token)
    }

    /** Anonymous tokens last a while; one per half hour is plenty. */
    private suspend fun token(): String = lock.withLock {
        token?.takeIf { Duration.between(it.second, Instant.now()) < Duration.ofMinutes(30) }?.first
            ?: ahToken(http, baseUrl).also { token = it to Instant.now() }
    }
}
