package dev.tobins.mealplanner.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The web client keeps its login on the page's main thread (Main.kt): that dispatcher must be there and do the work. */
class WebLoginTest {
    private val provider = HttpClient(
        MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.encodedPath) {
                "/api/auth/config" -> respond(
                    """{"issuer":"https://auth.example.org","clientId":"mealplanner","authorizationEndpoint":"https://auth.example.org/api/oidc/authorization","tokenEndpoint":"https://auth.example.org/api/oidc/token","scopes":["openid"]}""",
                    headers = json,
                )
                "/api/oidc/token" -> respond("""{"access_token":"web-token","expires_in":3600}""", headers = json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        },
    )

    @Test
    fun theAnswerOnAPageLoadLogsInOnThePagesMainThread() = runTest {
        val storage = MemorySettingsStorage()
        val page = MainScope()
        val logins = LoginExchange(storage, page, Login(storage, provider))
        val state = Url(logins.start("https://meals.example.nl", "https://meals.example.nl/")!!).parameters["state"]!!

        logins.deliver("https://meals.example.nl/?code=the-code&state=$state")
        logins.progress.first { it is LoginProgress.Done }
        assertEquals("web-token", storage.session()?.accessToken)
        assertFalse(logins.claimAutoOpen(serverKnown = true), "logged in: nothing to open")
        page.cancel()
    }
}
