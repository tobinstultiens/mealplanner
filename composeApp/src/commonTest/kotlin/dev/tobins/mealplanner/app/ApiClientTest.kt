package dev.tobins.mealplanner.app

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiClientTest {
    private fun client(body: String) = ApiClient(
        ServerSettings("http://localhost"),
        engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    @Test
    fun anUnknownTopLevelFieldIsIgnored() = runTest {
        val health = client("""{"status":"ok","version":"1.0","added":"later"}""").health()
        assertEquals("ok", health.status)
    }

    @Test
    fun anUnknownFieldInANestedObjectIsIgnored() = runTest {
        val recipes = client(
            """[{"id":1,"title":"Soup","section":"Dinner","tags":[],"servings":2,"source":"MANUAL","kind":"MEAL",
                "plannable":true,"added":{"x":1},"cost":{"perServing":1.5,"perServingWithOffers":1.2,"added":true}}]""",
        ).recipes()
        assertEquals("Soup", recipes.single().title)
    }
}
