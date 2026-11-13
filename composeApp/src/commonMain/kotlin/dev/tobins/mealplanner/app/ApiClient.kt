package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.Health
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodDataset
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.api.UnmappedText
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeightLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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

    suspend fun day(date: String): DayView = http.get("api/days/$date").body()

    suspend fun setMealStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus) {
        http.put("api/plans/$weekStart/meals/$day/${slot.name}/status") {
            contentType(ContentType.Application.Json)
            setBody(status)
        }
    }

    suspend fun logFood(entry: LogEntry): DayView = http.post("api/log") {
        contentType(ContentType.Application.Json)
        setBody(entry)
    }.body()

    suspend fun deleteLog(id: Long) {
        http.delete("api/log/$id")
    }

    suspend fun weights(): WeightLog = http.get("api/weights").body()

    suspend fun setWeight(date: String, kg: Double): WeightLog = http.put("api/weights/$date") {
        contentType(ContentType.Application.Json)
        setBody(kg)
    }.body()

    suspend fun deleteWeight(date: String): WeightLog = http.delete("api/weights/$date").body()

    suspend fun ingredients(): List<Ingredient> = http.get("api/ingredients").body()

    suspend fun setStaple(ingredientId: Long, staple: Boolean): Pantry =
        (if (staple) http.put("api/pantry/staples/$ingredientId") else http.delete("api/pantry/staples/$ingredientId")).body()

    suspend fun setOnHand(ingredientId: Long, onHand: Boolean): Pantry =
        (if (onHand) http.put("api/pantry/on-hand/$ingredientId") else http.delete("api/pantry/on-hand/$ingredientId")).body()

    suspend fun pantry(): Pantry = http.get("api/pantry").body()

    suspend fun offers(date: String): OffersState = http.get("api/offers") { parameter("date", date) }.body()

    suspend fun refreshOffers(): OffersState = http.post("api/offers/refresh").body()

    suspend fun shoppingList(weekStart: String): ShoppingList = http.get("api/plans/$weekStart/shopping").body()

    suspend fun setShoppingChecked(weekStart: String, key: String, checked: Boolean): ShoppingList =
        http.put("api/plans/$weekStart/shopping") {
            contentType(ContentType.Application.Json)
            setBody(ShoppingCheck(key, checked))
        }.body()

    suspend fun createRecipe(input: RecipeInput): RecipeDetail = http.post("api/recipes") {
        contentType(ContentType.Application.Json)
        setBody(input)
    }.body()

    suspend fun updateRecipe(id: Long, input: RecipeInput): RecipeDetail = http.put("api/recipes/$id") {
        contentType(ContentType.Application.Json)
        setBody(input)
    }.body()

    suspend fun deleteRecipe(id: Long) {
        http.delete("api/recipes/$id")
    }

    suspend fun updateRecipeSettings(id: Long, settings: RecipeSettings): RecipeDetail = http.put("api/recipes/$id/settings") {
        contentType(ContentType.Application.Json)
        setBody(settings)
    }.body()

    suspend fun unmapped(): List<UnmappedText> = http.get("api/mappings/unmapped").body()

    suspend fun map(request: MappingRequest): Ingredient = http.put("api/mappings") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    suspend fun searchFoods(query: String): List<Food> = http.get("api/foods") { parameter("q", query) }.body()

    suspend fun foodDataset(): FoodDataset = http.get("api/foods/dataset").body()

    suspend fun profile(): ProfileState = http.get("api/profile").body()

    suspend fun saveProfile(profile: Profile): ProfileState = http.put("api/profile") {
        contentType(ContentType.Application.Json)
        setBody(profile)
    }.body()

    suspend fun plan(weekStart: String): WeekPlan = http.get("api/plans/$weekStart").body()

    suspend fun generatePlan(weekStart: String): WeekPlan = http.post("api/plans/$weekStart/generate").body()

    suspend fun rerollMeal(weekStart: String, day: Int, slot: MealSlot): WeekPlan =
        http.post("api/plans/$weekStart/meals/$day/${slot.name}/reroll").body()

    suspend fun setMealLocked(weekStart: String, day: Int, slot: MealSlot, locked: Boolean): WeekPlan {
        val path = "api/plans/$weekStart/meals/$day/${slot.name}/lock"
        return if (locked) http.put(path).body() else http.delete(path).body()
    }

    fun close() = http.close()
}
