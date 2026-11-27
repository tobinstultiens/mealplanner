package dev.tobins.mealplanner.app.tracking

import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.LogFoodInput
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.WeightLog
import dev.tobins.mealplanner.app.ApiClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/*
 * The tracking's server calls: the foods logged as eaten and the weights.
 * They are extensions of [ApiClient], which keeps the address, the login, the errors and the retries for all of them.
 */

/** Logs a food of the catalogue as eaten outside the plan (issue 362), which the server counts from its values now. */
suspend fun ApiClient.logPickedFood(input: LogFoodInput): DayView = http.post("api/log/foods") {
    contentType(ContentType.Application.Json)
    setBody(input)
}.body()

/** Logged food [id] in another amount, unit or food, recounted by the server; 409 when its food is gone. */
suspend fun ApiClient.changeLoggedFood(id: Long, food: MealFoodInput): DayView = http.put("api/log/$id/food") {
    contentType(ContentType.Application.Json)
    setBody(food)
}.body()

suspend fun ApiClient.weights(): WeightLog = http.get("api/weights").body()

suspend fun ApiClient.setWeight(date: String, kg: Double): WeightLog = http.put("api/weights/$date") {
    contentType(ContentType.Application.Json)
    setBody(kg)
}.body()

suspend fun ApiClient.deleteWeight(date: String): WeightLog = http.delete("api/weights/$date").body()
