package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.Health
import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodDataset
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.api.UnmappedText
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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

    suspend fun unmapped(): List<UnmappedText> = http.get("api/mappings/unmapped").body()

    suspend fun map(request: MappingRequest): Ingredient = http.put("api/mappings") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    suspend fun searchFoods(query: String): List<Food> = http.get("api/foods") { parameter("q", query) }.body()

    suspend fun foodDataset(): FoodDataset = http.get("api/foods/dataset").body()

    fun close() = http.close()
}
