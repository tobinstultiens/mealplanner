package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/*
 * Server calls that more than one feature makes. A call only one feature makes is in that feature's package.
 * They are extensions of [ApiClient], which keeps the address, the login, the errors and the retries for all of them.
 */

suspend fun ApiClient.day(date: String): DayView = http.get("api/days/$date").body()

suspend fun ApiClient.setMealStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus) {
    http.put("api/plans/$weekStart/meals/$day/${slot.name}/status") {
        contentType(ContentType.Application.Json)
        setBody(status)
    }
}

suspend fun ApiClient.deleteLog(id: Long) {
    http.delete("api/log/$id")
}

suspend fun ApiClient.ingredients(): List<Ingredient> = http.get("api/ingredients").body()

/** Adds an ingredient to the account's catalogue, such as one for a NEVO food picked in the recipe editor. */
suspend fun ApiClient.createIngredient(input: IngredientInput): Ingredient = http.post("api/ingredients") {
    contentType(ContentType.Application.Json)
    setBody(input)
}.body()

suspend fun ApiClient.setOnHand(ingredientId: Long, onHand: Boolean): Pantry =
    (if (onHand) http.put("api/pantry/on-hand/$ingredientId") else http.delete("api/pantry/on-hand/$ingredientId")).body()

suspend fun ApiClient.searchFoods(query: String): List<Food> = http.get("api/foods") { parameter("q", query) }.body()

suspend fun ApiClient.profile(): ProfileState = http.get("api/profile").body()

suspend fun ApiClient.saveProfile(profile: Profile): ProfileState = http.put("api/profile") {
    contentType(ContentType.Application.Json)
    setBody(profile)
}.body()
