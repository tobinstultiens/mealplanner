package dev.tobins.mealplanner.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.Collections
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The whole app logged out, on a fake server and a fake login provider: it opens the login page by
 * itself once, shows its login screen when that page is closed, survives a re-created screen in the
 * middle of the token exchange, and ends on the login screen, saying why, when a session is over.
 */
@OptIn(ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LoginFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val backend = FakeBackend()
    private val restoration by lazy { StateRestorationTester(compose) }
    private val storage = MemorySettingsStorage().apply { saveAppLanguage(AppLanguage.ENGLISH) }

    /** The login pages the app opened (the Custom Tab on Android). */
    private val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val launcher = object : LoginLauncher {
        override fun redirectUri(serverUrl: String) = "$serverUrl/app/login"

        override fun open(url: String) {
            opened += url
        }
    }

    /** The token requests the provider got; one waits for [gate]. */
    private val tokenRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val gate = CompletableDeferred<Unit>()
    private val provider = HttpClient(
        MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.encodedPath) {
                "/api/auth/config" -> respond(
                    """{"issuer":"https://auth.example.org","clientId":"mealplanner","authorizationEndpoint":"https://auth.example.org/api/oidc/authorization","tokenEndpoint":"https://auth.example.org/api/oidc/token","scopes":["openid","offline_access"]}""",
                    headers = json,
                )
                "/api/oidc/token" -> {
                    val grant = (request.body as FormDataContent).formData["grant_type"]!!
                    tokenRequests += grant
                    if (grant == "refresh_token") {
                        respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, json)
                    } else {
                        gate.await()
                        respond("""{"access_token":"anna-token","refresh_token":"anna-refresh","expires_in":3600}""", headers = json)
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        },
    )
    private val scope = MainScope()

    /** Outlives every screen of the test, as it outlives every screen of the process on a phone (MainActivity). */
    private val logins = LoginExchange(storage, scope, Login(storage, provider))

    @Before
    fun fixTimeAndLanguage() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        appClock = object : Clock {
            override fun now(): Instant = Instant.parse("${FakeBackend.TODAY}T10:30:00Z")
        }
        RuntimeEnvironment.setQualifiers("en-rGB-w411dp-h900dp")
    }

    @After
    fun tearDown() {
        scope.cancel()
        backend.close()
        appClock = Clock.System
    }

    private fun startApp(builtIn: Boolean = true) {
        restoration.setContent { App(backend.settings, storage, launcher, logins, defaultsBuiltIn = builtIn) }
    }

    /**
     * Waits for [condition], running meanwhile what waits on the main thread outside Compose (the
     * exchange's work, as on a phone): Robolectric runs it only when asked, and Compose's waiting does not.
     */
    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitFor(text: String) =
        waitUntil { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** Logged in: the bottom bar is there, which only the logged-in app has. */
    private fun waitForTabs() =
        waitUntil { compose.onAllNodesWithText("Shopping").fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aLoggedOutStartOpensTheLoginPageOnceAndTheLoginScreenStaysWhenItIsClosed() {
        startApp()
        waitUntil { opened.size == 1 }
        assertEquals("/api/oidc/authorization", Url(opened.single()).encodedPath)
        // The page was closed without logging in: what is under it is the login screen.
        waitFor("Logs in at 127.0.0.1")
        compose.onNodeWithText("Log in").assertIsDisplayed()

        // Rotation, dark mode, "Don't keep activities": the screen is made again, and opens nothing by itself.
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Logs in at 127.0.0.1")
        compose.waitForIdle()
        assertEquals(1, opened.size)

        // The user can still log in.
        compose.onNodeWithText("Log in").performClick()
        waitUntil { opened.size == 2 }
    }

    @Test
    fun aLoginAnsweredWhileTheScreenIsRecreatedEndsLoggedInWithOneTokenRequest() {
        startApp()
        waitUntil { opened.size == 1 }
        val state = Url(opened.single()).parameters["state"]!!

        // The answer arrives and the token request waits; meanwhile Android makes the screen again.
        logins.deliver("${backend.settings.url}/app/login?code=the-code&state=$state")
        waitUntil { logins.progress.value == LoginProgress.Exchanging }
        waitFor("Logging in…")
        restoration.emulateSavedInstanceStateRestore()
        gate.complete(Unit)

        waitForTabs()
        assertEquals(listOf("authorization_code"), tokenRequests.toList())
        assertEquals("anna-token", storage.session()?.accessToken)
        assertEquals(LoginProgress.Idle, logins.progress.value, "the new screen took the login")
    }

    @Test
    fun afterLoggingOutTheLoginScreenSaysSoAndOpensNothing() {
        storage.saveSession(Session("anna-token", expiresAt = Long.MAX_VALUE))
        startApp()
        waitForTabs()
        compose.onAllNodesWithText("Progress")[0].performClick()
        waitUntil { compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor("Log out")
        compose.onNodeWithText("Log out").performScrollTo().performClick()

        waitFor("You are logged out.")
        compose.waitForIdle()
        assertTrue(opened.isEmpty())
        assertEquals(null, storage.session())
    }

    @Test
    fun aSessionTheProviderWillNotRenewEndsOnTheLoginScreenWithTheReason() {
        storage.saveSession(Session(FakeBackend.REFUSED_TOKEN, "revoked", expiresAt = 0))
        startApp()

        waitFor("your login ran out or was ended")
        compose.onNodeWithText("Log in").assertIsDisplayed()
        compose.waitForIdle()
        assertTrue(opened.isEmpty(), "the reason is shown, not covered by the login page")
        assertEquals(null, storage.session())
        assertTrue("refresh_token" in tokenRequests)
    }

    @Test
    fun withoutAKnownServerTheLoginScreenAsksForTheAddressFirst() {
        startApp(builtIn = false)
        waitFor("Server address")
        compose.waitForIdle()
        assertTrue(opened.isEmpty(), "nothing opens before the user confirmed a server")

        // The suggested address is filled in; logging in with it makes it the chosen one.
        compose.onNodeWithText("Log in").performClick()
        waitUntil { opened.size == 1 }
        assertTrue(storage.hasSavedServer())
        assertNotNull(storage.loggedOut())
    }
}
