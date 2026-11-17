package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.ShoppingList
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.JsonConvertException
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

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
    fun anUnknownEnumValueBecomesTheFieldsDefault() = runTest {
        val plan = client(
            """{"weekStart":"2026-11-16","targets":{"kcal":2000,"proteinG":100},"emptySlots":[],"days":[{"date":"2026-11-16","kcal":0,"proteinG":0,
                "meals":[{"day":0,"slot":"DINNER","recipeId":1,"title":"Soup","portions":1,"kcal":500,"proteinG":30,"status":"COOKED_BY_SOMEONE_ELSE"}]}]}""",
        ).plan("2026-11-16")
        assertEquals(MealStatus.PLANNED, plan.days.single().meals.single().status)
    }

    @Test
    fun anUnknownEnumValueWithoutADefaultStillFails() = runTest {
        assertFailsWith<JsonConvertException> {
            client(
                """[{"id":1,"title":"Soup","section":"Dinner","tags":[],"servings":2,"source":"IMPORTED","kind":"MEAL","plannable":true}]""",
            ).recipes()
        }
    }

    @Test
    fun anUnknownFieldInANestedObjectIsIgnored() = runTest {
        val recipes = client(
            """[{"id":1,"title":"Soup","section":"Dinner","tags":[],"servings":2,"source":"MANUAL","kind":"MEAL",
                "plannable":true,"addedLater":{"x":1},"cost":{"perServing":1.5,"perServingWithOffers":1.2,"addedLater":true}}]""",
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

    /** A client whose server answers every request with [status] and [body], noting each path asked for. */
    private fun answering(status: HttpStatusCode, body: String, paths: MutableList<String> = mutableListOf()) = ApiClient(
        ServerSettings("http://localhost"),
        engine = MockEngine { request ->
            paths += "${request.method.value} ${request.url.encodedPath}"
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ).shoppingServer()

    @Test
    fun aServerWithoutTheOneStepRoutesIsToldApartByItsBare404() = runTest {
        val paths = mutableListOf<String>()
        // Ktor's answer to a path it has no route for: 404 and nothing else.
        val old = answering(HttpStatusCode.NotFound, "", paths)
        assertNull(old.tickAgain("2026-11-16", "i1"))
        assertNull(old.useUp("2026-11-16", "i7"))
        assertEquals(listOf("PUT /api/plans/2026-11-16/shopping/again", "POST /api/plans/2026-11-16/shopping/use-up"), paths)
    }

    @Test
    fun aRefusalOfTheOneStepRoutesIsAFailureWithTheServersReason() = runTest {
        val refused = answering(HttpStatusCode.NotFound, """{"error":"No tick of i1 in the week of 2026-11-16"}""")
        assertEquals(Load.Failed("No tick of i1 in the week of 2026-11-16", 404), refused.tickAgain("2026-11-16", "i1"))
        val conflict = answering(HttpStatusCode.Conflict, """{"error":"The meals of the week of 2026-11-16 need i7 again"}""")
        assertEquals(Load.Failed("The meals of the week of 2026-11-16 need i7 again", 409), conflict.useUp("2026-11-16", "i7"))
        val list = answering(HttpStatusCode.OK, """{"weekStart":"2026-11-16","items":[],"staples":[]}""")
        assertEquals("2026-11-16", assertIs<Load.Ready<ShoppingList>>(list.tickAgain("2026-11-16", "i1")).value.weekStart)
    }

    @Test
    fun choosingARecipeSendsItsIdAndTellsAServerFromBeforeTheRouteByItsBare404() = runTest {
        val sent = mutableListOf<String>()
        val plan = """{"weekStart":"2026-11-16","targets":{"kcal":2100,"proteinG":130},"days":[],"emptySlots":[]}"""
        val current = ApiClient(
            ServerSettings("http://localhost"),
            engine = MockEngine { request ->
                sent += "${request.method.value} ${request.url.encodedPath} ${(request.body as TextContent).text}"
                respond(plan, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        assertEquals("2026-11-16", current.chooseRecipe("2026-11-16", 4, MealSlot.DINNER, 12)?.weekStart)
        assertEquals(listOf("""PUT /api/plans/2026-11-16/meals/4/DINNER {"recipeId":12}"""), sent)

        // Ktor's answer to a route it does not have: 404 and nothing else.
        val old = ApiClient(ServerSettings("http://localhost"), engine = MockEngine { respond("", HttpStatusCode.NotFound) })
        assertNull(old.chooseRecipe("2026-11-16", 4, MealSlot.DINNER, 12))
        // The route's own 404 says what was not found: that is a failure, not an old server.
        val refused = ApiClient(
            ServerSettings("http://localhost"),
            engine = MockEngine { respond("""{"error":"No recipe 12"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json")) },
        )
        assertFailsWith<ClientRequestException> { refused.chooseRecipe("2026-11-16", 4, MealSlot.DINNER, 12) }
    }

    private val bankRecipe =
        """{"id":8,"title":"Miso aubergine","section":"Dinner","tags":[],"servings":2,"source":"COOKING_SITE","kind":"MEAL","plannable":true,"added":false,"inRotation":false}"""

    /** A client whose server answers [status] and [body] to everything, and which remembers what it was asked. */
    private fun recipeServer(status: HttpStatusCode, body: String, asked: MutableList<String> = mutableListOf()) = ApiClient(
        ServerSettings("http://localhost"),
        engine = MockEngine { request ->
            asked += "${request.method.value} ${request.url.encodedPath} ${(request.body as? TextContent)?.text.orEmpty()}"
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    @Test
    fun theBankIsReadWithItsFlags() = runTest {
        val bank = client("[$bankRecipe]").recipeBank()!!
        assertEquals(listOf(8L), bank.map { it.id })
        assertEquals(false, bank.single().added)
        assertEquals(false, bank.single().inRotation)
    }

    @Test
    fun aRecipeWithoutTheFlagsIsMineAndInTheRotationAsOnAServerFromBeforeTheBank() = runTest {
        val recipe = client(
            """[{"id":1,"title":"Soup","section":"Dinner","tags":[],"servings":2,"source":"COOKING_SITE","kind":"MEAL","plannable":true}]""",
        ).recipes().single()
        assertEquals(true, recipe.added)
        assertEquals(true, recipe.inRotation)
        assertEquals(false, recipe.removedFromSite)
    }

    @Test
    fun aServerFromBeforeTheBankHasNoBank() = runTest {
        // It reads `bank` as a recipe id and refuses: 400 with its sentence (or a plain 404).
        assertNull(recipeServer(HttpStatusCode.BadRequest, """{"error":"Recipe id must be a number"}""").recipeBank())
        assertNull(recipeServer(HttpStatusCode.NotFound, "").recipeBank())
        // Any other failure is a failure, not "no bank".
        assertFailsWith<ServerResponseException> { recipeServer(HttpStatusCode.InternalServerError, "{}").recipeBank() }
    }

    @Test
    fun addingAndTheRotationSendTheirBodiesToTheirRoutes() = runTest {
        val detail = """{"summary":$bankRecipe,"ingredients":[],"instructions":"","problems":[]}"""
        val asked = mutableListOf<String>()
        val api = recipeServer(HttpStatusCode.OK, detail, asked)
        api.setRecipeAdded(8, added = true, inRotation = false)
        api.setRecipeRotation(8, inRotation = true)
        val many = recipeServer(HttpStatusCode.OK, "[$bankRecipe]", asked)
        many.setRecipesAdded(listOf(8, 9), added = true)
        many.setRecipesRotation(listOf(1, 2), inRotation = false)
        assertEquals(
            listOf(
                """PUT /api/recipes/8/added {"added":true,"inRotation":false}""",
                """PUT /api/recipes/8/rotation {"inRotation":true}""",
                """PUT /api/recipes/added {"ids":[8,9],"added":true,"inRotation":true}""",
                """PUT /api/recipes/rotation {"ids":[1,2],"inRotation":false}""",
            ),
            asked,
        )
    }

    @Test
    fun aRefusalCarriesTheServersSentence() = runTest {
        val api = recipeServer(HttpStatusCode.Conflict, """{"error":"“Lentil soup” is planned on Fri 20 Nov"}""")
        val failed = load { api.setRecipeAdded(5, added = false) } as Load.Failed
        assertEquals(409, failed.status)
        assertEquals("“Lentil soup” is planned on Fri 20 Nov", failed.message)
    }
}
