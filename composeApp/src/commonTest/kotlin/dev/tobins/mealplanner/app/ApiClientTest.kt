package dev.tobins.mealplanner.app

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
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

    @Test
    fun aTickSendsGramsOnlyWhenItHasThemSoAServerFromBeforeThemTakesTheRest() = runTest {
        val bodies = mutableListOf<String>()
        val api = ApiClient(
            ServerSettings("http://localhost"),
            engine = MockEngine { request ->
                bodies += (request.body as TextContent).text
                respond("""{"weekStart":"2026-11-16","items":[],"staples":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        api.setShoppingChecked("2026-11-16", "i1", true)
        // A fraction, which every platform writes alike; a whole 1000.0 may come out as 1000.
        api.setShoppingChecked("2026-11-16", "stock:i7", true, grams = 1000.5)
        assertEquals(listOf("""{"key":"i1","checked":true}""", """{"key":"stock:i7","checked":true,"grams":1000.5}"""), bodies)
    }
}
