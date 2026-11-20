package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.AuthConfig
import dev.tobins.mealplanner.api.LoginRefusal
import dev.tobins.mealplanner.api.Me
import dev.tobins.mealplanner.server.db.Database
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.auth.AuthenticationRouteSelector
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.OpenApiRoutePathFormat
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.path
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `MEALPLANNER_OIDC_GROUP` (issue 314): with it set only members of the group get past the login, on every route
 * that needs one; unset, every account the provider knows does, as before. Tokens as [TestTokens] reads them.
 */
class GroupCheckTest {
    private lateinit var app: Application
    private lateinit var db: Database

    private fun ApplicationTestBuilder.start(group: String?) {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null, oidcGroup = group)
        db = openDatabase(config.databaseUrl).second
        application {
            module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens)
            app = this
        }
    }

    private fun ApplicationTestBuilder.json(): HttpClient = createClient { install(ContentNegotiation) { json() } }

    /**
     * Every method and path the server answers only with a login, found in its routing, so a route added later is
     * walked too. Each path parameter is filled with "1": the login is checked before any route reads its parameters.
     */
    private fun protectedCalls(): List<Pair<HttpMethod, String>> = app.plugin(RoutingRoot).getAllRoutes()
        .filter { route -> route.selector is HttpMethodRouteSelector && generateSequence(route) { it.parent }.any { it.selector is AuthenticationRouteSelector } }
        .map { route -> (route.selector as HttpMethodRouteSelector).method to route.path(OpenApiRoutePathFormat).replace(Regex("\\{[^}]*}"), "1") }
        .distinct()

    private suspend fun HttpClient.call(method: HttpMethod, path: String, token: String) = request(path) {
        this.method = method
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody("{}")
    }

    @Test
    fun withTheGroupSetEveryRouteRefusesALoginOutsideIt() = testApplication {
        start(group = "mealplanner")
        val http = json()
        // Started by the first call; the routes are known from then on.
        assertEquals(HttpStatusCode.OK, http.get("/health").status)
        val calls = protectedCalls()
        assertTrue(calls.size > 50, "the walk found the routes: $calls")

        for ((method, path) in calls) {
            for ((token, reason) in listOf("eve~family" to LoginRefusal.NOT_IN_GROUP, "eve~" to LoginRefusal.NOT_IN_GROUP, "eve" to LoginRefusal.NO_GROUPS)) {
                val response = http.call(method, path, token)
                assertEquals(HttpStatusCode.Forbidden, response.status, "$method $path with $token")
                val body = Json.parseToJsonElement(response.bodyAsText()) as JsonObject
                assertEquals(reason, body["reason"]?.jsonPrimitive?.content, "$method $path with $token")
                assertTrue(body["error"]!!.jsonPrimitive.content.endsWith("ask the person who runs this server.", ignoreCase = true), "$method $path: a plain sentence")
            }
        }
        // Refused before the account is looked up: nobody outside the group gets one, nor a seeded catalogue.
        assertNull(db.userQueries.selectBySubject("sub-eve").executeAsOneOrNull())

        // A member gets in, and without any token it is the 401 of before.
        assertEquals(Me("ann", "Ann"), http.get("/api/me") { bearerAuth("ann~family~mealplanner") }.body<Me>())
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/me").status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/me") { bearerAuth("nope") }.status)
    }

    @Test
    fun withTheGroupSetClientsAreToldToAskForTheGroups() = testApplication {
        start(group = "mealplanner")
        assertEquals(LOGIN_SCOPES + "groups", json().get("/api/auth/config").body<AuthConfig>().scopes)
    }

    @Test
    fun aMemberTakenOutOfTheGroupIsRefusedFromTheNextToken() = testApplication {
        start(group = "mealplanner")
        val http = json()
        assertEquals(HttpStatusCode.OK, http.get("/api/me") { bearerAuth("ann~mealplanner") }.status)
        // Authelia reads the groups again for every token it issues, a renewed one too: the next one lacks it.
        val refused = http.get("/api/recipes") { bearerAuth("ann~family") }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(LoginRefusal.NOT_IN_GROUP in refused.bodyAsText())
        // Her data stays: put back in the group, she finds her account as it was.
        assertEquals(HttpStatusCode.OK, http.get("/api/me") { bearerAuth("ann~mealplanner") }.status)
        assertEquals(1L, db.userQueries.selectBySubject("sub-ann").executeAsOne().id)
    }

    @Test
    fun withoutTheSettingEveryAccountGetsInAsBefore() = testApplication {
        start(group = null)
        val http = json()
        assertEquals(LOGIN_SCOPES, http.get("/api/auth/config").body<AuthConfig>().scopes, "no groups scope the provider may not allow")
        // With or without groups, in the group or not: every other server test runs this way too.
        for (token in listOf("t", "eve~", "eve~family", "ann~mealplanner")) {
            assertEquals(HttpStatusCode.OK, http.get("/api/me") { bearerAuth(token) }.status, token)
            assertEquals(HttpStatusCode.OK, http.get("/api/recipes") { bearerAuth(token) }.status, token)
        }
    }

    @Test
    fun theSettingIsReadFromTheEnvironment() {
        assertNull(ServerConfig.fromEnv(emptyMap()).oidcGroup)
        assertNull(ServerConfig.fromEnv(mapOf("MEALPLANNER_OIDC_GROUP" to "  ")).oidcGroup, "blank is unset")
        assertEquals("mealplanner", ServerConfig.fromEnv(mapOf("MEALPLANNER_OIDC_GROUP" to " mealplanner ")).oidcGroup)
    }
}
