package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.app.accounts.AccountShops
import dev.tobins.mealplanner.app.accounts.Login
import dev.tobins.mealplanner.app.accounts.LoginExchange
import dev.tobins.mealplanner.app.accounts.Session
import dev.tobins.mealplanner.app.accounts.loggedOut
import dev.tobins.mealplanner.app.accounts.saveSession
import dev.tobins.mealplanner.app.accounts.saveShops
import dev.tobins.mealplanner.app.accounts.session
import dev.tobins.mealplanner.app.accounts.shops
import dev.tobins.mealplanner.app.recipes.recipes
import dev.tobins.mealplanner.app.shopping.ShoppingCache
import dev.tobins.mealplanner.app.shopping.ShoppingSession
import dev.tobins.mealplanner.app.shopping.plainReason
import dev.tobins.mealplanner.app.shopping.shoppingServer
import dev.tobins.mealplanner.app.shopping.tickShoppingAgain
import dev.tobins.mealplanner.app.shopping.withTicks
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.load
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A server that cannot be reached, as each platform's engine reports it ([unreachableServer]): every
 * place that makes a request sees a lost connection, as on Android, and nothing is left loading or
 * thrown past it. On the web the engine's failure is no `Exception` and used to escape them all (#200).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnreachableServerTest {
    private val monday = "2026-11-16"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val storage = MemorySettingsStorage()

    /** The meal planner's server; while [down] nothing answers. [ticked] has the ticks it took, [requests] every request that reached it. */
    private inner class Server(scope: TestScope) {
        var down = false
        val ticked = mutableListOf<String>()
        val requests = mutableListOf<String>()
        private var list = ShoppingList(monday, listOf("a", "b", "c").map { ShoppingItem(it, it, 100.0, null, "Vegetables", listOf("Soup"), onHand = false, checked = false) }, staples = emptyList())

        // On the test's clock, so that the shopping list's time limits and checks run in virtual time.
        val api = ApiClient(
            ServerSettings("http://localhost"),
            engine = MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(scope.testScheduler)
                    addHandler { request ->
                        if (down) throw unreachableServer()
                        requests += "${request.method.value} ${request.url.encodedPath}"
                        when {
                            request.url.encodedPath == "/api/plans/$monday/shopping" && request.method == HttpMethod.Put -> {
                                val check = Json.decodeFromString(ShoppingCheck.serializer(), (request.body as TextContent).text)
                                ticked += check.key
                                list = list.withTicks(mapOf(check.key to check.checked))
                                respond(Json.encodeToString(ShoppingList.serializer(), list), headers = json)
                            }
                            request.url.encodedPath == "/api/plans/$monday/shopping" -> respond(Json.encodeToString(ShoppingList.serializer(), list), headers = json)
                            else -> respond("""{"error":"Not found"}""", HttpStatusCode.NotFound, json)
                        }
                    }
                },
            ),
        )
    }

    private fun Load<*>.lostConnection(): Load.Failed {
        val failed = assertIs<Load.Failed>(this, "a failure, not $this")
        assertTrue(failed.lostConnection, "a lost connection: $failed")
        assertTrue(failed.technical)
        return failed
    }

    @Test
    fun aRequestFailsAsALostConnectionWithAPlainReason() = runTest {
        val server = Server(this).apply { down = true }
        val failed = load { server.api.recipes() }.lostConnection()
        assertEquals("the server could not be reached", plainReason(failed))
    }

    @Test
    fun aScreenWithNothingYetShowsTheFailureInsteadOfLoading() = runTest {
        val server = Server(this).apply { down = true }
        val recipes = LoadState<List<RecipeSummary>>(ScreenMemory(), "recipes")
        recipes.refresh { server.api.recipes() }
        recipes.value.lostConnection()
        assertEquals(false, recipes.refreshing)
    }

    @Test
    fun aScreenWithContentKeepsItAndOffersARetry() = runTest {
        val server = Server(this).apply { down = true }
        val memory = ScreenMemory().apply { keep("recipes", emptyList<RecipeSummary>()) }
        val recipes = LoadState<List<RecipeSummary>>(memory, "recipes")
        recipes.refresh { server.api.recipes() }
        assertEquals(Load.Ready(emptyList()), recipes.value)
        assertTrue(recipes.refreshFailed, "the line with Retry shows")
    }

    @Test
    fun aRouteAServerFromBeforeLacksIsNotMistakenForOneWhenNothingAnswers() = runTest {
        val server = Server(this).apply { down = true }
        load { server.api.tickShoppingAgain(monday, "a") }.lostConnection()
    }

    @Test
    fun aChoiceOfStoresThatCannotBeSavedGoesBack() = runTest {
        val server = Server(this).apply { down = true }
        val shops = AccountShops(storage, server.api::shops, server.api::saveShops)
        val before = shops.stores
        load { shops.choose(setOf(Store.AH)) }.lostConnection()
        assertEquals(before, shops.stores)
    }

    @Test
    fun aRenewalThatCannotReachTheLoginProviderIsALostConnectionAndKeepsTheSession() = runTest {
        val session = Session("old", "still-good", 0)
        storage.saveSession(session)
        val provider = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath != "/api/auth/config") throw unreachableServer()
                respond(
                    """{"issuer":"https://auth.example.org","clientId":"mealplanner","authorizationEndpoint":"https://auth.example.org/api/oidc/authorization","tokenEndpoint":"https://auth.example.org/api/oidc/token","scopes":["openid"]}""",
                    headers = json,
                )
            },
        ) { install(ContentNegotiation) { json() } }
        val logins = LoginExchange(storage, backgroundScope, Login(storage, provider))
        val api = ApiClient(
            ServerSettings("https://meals.example.nl"),
            session,
            engine = MockEngine { respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Bearer")) },
        ) { logins.renew("https://meals.example.nl") }

        load { api.recipes() }.lostConnection()
        assertEquals(session, storage.session(), "a network failure ends no session")
        assertNull(storage.loggedOut())
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun ShoppingSession.checked() = assertIs<Load.Ready<ShoppingList>>(state).value.items.map { it.checked }

    @Test
    fun theShoppingListSaysItIsOfflineWhenAPollFails() = runTest {
        val server = Server(this)
        val session = ShoppingSession(backgroundScope, server.api.shoppingServer(), ShoppingCache(storage), { "2026-11-18" }, shoppingMode = { true })
        session.inFront(true)
        session.resume()
        wait(1000)
        assertNull(session.offlineSince)

        server.down = true
        wait(ShoppingSession.REFRESH_IN_SHOP.inWholeMilliseconds)
        assertNotNull(session.offlineSince, "the offline line shows")
        assertEquals(listOf(false, false, false), session.checked(), "the stored list stays")
    }

    @Test
    fun aTickMadeWhileTheServerIsDownIsSentWhenItIsBackWithoutAReload() = runTest {
        val server = Server(this)
        val session = ShoppingSession(backgroundScope, server.api.shoppingServer(), ShoppingCache(storage), { "2026-11-18" }, shoppingMode = { true })
        session.inFront(true)
        session.resume()
        wait(1000)

        server.down = true
        session.tick("a", true)
        runCurrent()
        assertNotNull(session.offlineSince, "the tick found no server: the offline line shows")
        assertEquals(listOf(true, false, false), session.checked())

        server.down = false
        wait(ShoppingSession.RECONNECT.inWholeMilliseconds)
        assertEquals(listOf("a"), server.ticked, "the stored tick reaches the server with the next check")
        assertNull(session.offlineSince)
        assertEquals(listOf(true, false, false), session.checked())
        assertEquals(emptyMap(), ShoppingCache(storage).pending(monday))

        session.tick("b", true)
        runCurrent()
        assertEquals(listOf("a", "b"), server.ticked)
    }
}
