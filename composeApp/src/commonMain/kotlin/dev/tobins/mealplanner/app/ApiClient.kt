package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.Health
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SyncResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.serialization.kotlinx.json.json

/** Where the server is and how to authenticate to it. */
data class ServerSettings(val url: String, val token: String)

/** Talks to the meal planner server. Calls throw on network errors and non-2xx responses. */
class ApiClient(settings: ServerSettings) {
    private val http = HttpClient {
        expectSuccess = true
        install(ContentNegotiation) { json() }
        defaultRequest {
            url(settings.url.trimEnd('/') + "/")
            if (settings.token.isNotBlank()) bearerAuth(settings.token)
        }
    }

    suspend fun health(): Health = http.get("health").body()

    suspend fun recipes(): List<RecipeSummary> = http.get("api/recipes").body()

    suspend fun recipe(id: Long): RecipeDetail = http.get("api/recipes/$id").body()

    suspend fun syncCookingSite(): SyncResult = http.post("api/sync/cooking").body()

    fun close() = http.close()
}
