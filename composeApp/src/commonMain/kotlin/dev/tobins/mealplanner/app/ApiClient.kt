package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.Health
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json

/** Talks to the meal planner server. One instance per (baseUrl, token) pair. */
class ApiClient(baseUrl: String, token: String) {
    private val http = HttpClient {
        install(ContentNegotiation) { json() }
        defaultRequest {
            url(baseUrl.trimEnd('/') + "/")
            if (token.isNotBlank()) bearerAuth(token)
        }
    }

    suspend fun health(): Health = http.get("health").body()

    fun close() = http.close()
}
