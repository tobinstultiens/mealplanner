package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ManyAddedInput
import dev.tobins.mealplanner.api.RecipeSummary
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/**
 * Adds every recipe in the account's bank to its recipes, in the rotation: what a sync did by itself before
 * the bank (issue 205), for tests about what comes after the sync rather than about the bank.
 */
suspend fun HttpClient.addWholeBank() {
    // Read as text, so that it works whatever JSON settings the test's client has.
    val bank = Json { ignoreUnknownKeys = true }.decodeFromString<List<RecipeSummary>>(get("/api/recipes/bank").bodyAsText())
    val answer = put("/api/recipes/added") {
        contentType(ContentType.Application.Json)
        setBody(Json.encodeToString(ManyAddedInput(bank.map { it.id }, added = true)))
    }
    check(answer.status.isSuccess()) { "adding the bank: ${answer.status} ${answer.bodyAsText()}" }
}
