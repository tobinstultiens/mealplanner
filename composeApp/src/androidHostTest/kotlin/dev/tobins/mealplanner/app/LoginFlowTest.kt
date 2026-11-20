package dev.tobins.mealplanner.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
import kotlinx.coroutines.runBlocking
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The whole app logged out, on a fake server and a fake login provider: it opens the login page by
 * itself once, shows its login screen when that page is closed, survives a re-created screen in the
 * middle of the token exchange, drops that exchange when another server is saved during it, ends on the
 * login screen, saying why, when a session is over, and reaches the settings while it asks for the address.
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

    /** The token requests the provider got; a login's waits for [gate], a renewal is answered [refreshStatus]. */
    private val tokenRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val gate = CompletableDeferred<Unit>()
    @Volatile private var refreshStatus = HttpStatusCode.BadRequest

    /** The access token a login gets; with [renewedTo] set, a renewal works and gets that one. */
    @Volatile private var loginToken = "anna-token"
    @Volatile private var renewedTo: String? = null

    /** What the login takes for now, to count how long renewing has failed. */
    @Volatile private var now = Instant.parse("${FakeBackend.TODAY}T10:30:00Z")
    private val loginClock = object : Clock {
        override fun now() = now
    }
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
                    val renewed = renewedTo
                    if (grant == "refresh_token" && renewed != null) {
                        respond("""{"access_token":"$renewed","refresh_token":"anna-refresh-2","expires_in":3600}""", headers = json)
                    } else if (grant == "refresh_token") {
                        respond("""{"error":"invalid_grant"}""", refreshStatus, json)
                    } else {
                        gate.await()
                        respond("""{"access_token":"$loginToken","refresh_token":"anna-refresh","expires_in":3600}""", headers = json)
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        },
    )
    private val scope = MainScope()

    /** Outlives every screen of the test, as it outlives every screen of the process on a phone (MainActivity). */
    private val logins = LoginExchange(storage, scope, Login(storage, provider, loginClock))

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
    fun aLoginOutsideTheServersGroupEndsOnTheLoginScreenWithTheSentence() {
        loginToken = FakeBackend.NOT_IN_GROUP_TOKEN
        startApp()
        waitUntil { opened.size == 1 }
        val state = Url(opened.single()).parameters["state"]!!
        gate.complete(Unit)
        logins.deliver("${backend.settings.url}/app/login?code=the-code&state=$state")

        // The provider let the login through; the server's first answer ends it.
        waitFor("This account may not use the meal planner yet. Ask the person who runs this server.")
        compose.onNodeWithText("Log in").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(null, storage.session())
        assertEquals(LoggedOut.NOT_ALLOWED, storage.loggedOut())
        assertEquals(1, opened.size, "no login page opened over the sentence")
    }

    @Test
    fun aSessionWhoseGroupWasTakenAwayEndsAtItsNextRenewalWithTheSentence() {
        // The access token ran out; the provider renews it, but the account is no longer in the group.
        storage.saveSession(Session(FakeBackend.REFUSED_TOKEN, "anna-refresh", expiresAt = 0))
        renewedTo = FakeBackend.NOT_IN_GROUP_TOKEN
        startApp()

        waitFor("This account may not use the meal planner yet. Ask the person who runs this server.")
        compose.onNodeWithText("Log in").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(listOf("refresh_token"), tokenRequests.toList())
        assertEquals(null, storage.session())
        assertEquals(LoggedOut.NOT_ALLOWED, storage.loggedOut())
        assertTrue(opened.isEmpty(), "the reason is shown, not covered by the login page")
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

    /** The same fake server under another address: another server to the app, with nothing to fetch from the internet. */
    private val otherServer by lazy { backend.settings.url.replace("127.0.0.1", "localhost") }

    /** "Change server" on the login screen, then [inSettings] there, a new address, Save. */
    private fun changeServerTo(url: String, inSettings: () -> Unit = {}) {
        compose.onNodeWithText("Change server").performClick()
        waitFor("Test connection")
        inSettings()
        compose.onNode(hasSetTextAction()).performTextReplacement(url)
        compose.onNodeWithText("Save").performClick()
    }

    /** Gives what might still come from another thread (a provider's answer) time to arrive, before checking that it did not. */
    private fun settle() = repeat(20) {
        Thread.sleep(25)
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
    }

    @Test
    fun anotherServerSavedDuringTheTokenExchangeDropsThatLogin() {
        startApp()
        waitUntil { opened.size == 1 }
        val state = Url(opened.single()).parameters["state"]!!
        logins.deliver("${backend.settings.url}/app/login?code=the-code&state=$state")
        waitUntil { logins.progress.value == LoginProgress.Exchanging }

        // While the old server's provider answers, the user saves another server; Log in there waits meanwhile.
        changeServerTo(otherServer) { compose.onNodeWithText("Logging in…").performScrollTo().assertIsNotEnabled() }
        waitFor("Logs in at ${otherServer.substringAfter("://")}")
        gate.complete(Unit)
        settle()

        assertEquals(null, storage.session(), "the old server's login was dropped")
        assertEquals(LoginProgress.Idle, logins.progress.value)
        compose.onNodeWithText("Log in").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Shopping").fetchSemanticsNodes().isEmpty(), "not logged in")
        assertEquals(listOf("authorization_code"), tokenRequests.toList(), "no old refresh token went anywhere")
    }

    @Test
    fun anotherServerChosenBeforeEverLoggingInSaysNothingAboutALogOut() {
        startApp()
        waitUntil { opened.size == 1 }
        waitFor("Logs in at 127.0.0.1")

        changeServerTo(otherServer)
        waitFor("Logs in at ${otherServer.substringAfter("://")}")
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("You are logged out.").fetchSemanticsNodes().isEmpty())
        assertEquals(1, opened.size, "nothing opens by itself")
    }

    @Test
    fun aTokenEndpointThatKeepsFailingEndsOnTheLoginScreenWithTheReason() {
        refreshStatus = HttpStatusCode.NotFound
        storage.saveSession(Session(FakeBackend.REFUSED_TOKEN, "still-good", expiresAt = 0))
        // Twenty minutes ago renewing failed twice: every screen said so, and the session stayed.
        runBlocking { repeat(2) { logins.renew(backend.settings.url) } }
        assertNotNull(storage.session())
        now += 20.minutes

        startApp()
        waitFor("could not be renewed for a while")
        compose.onNodeWithText("Log in").assertIsDisplayed()
        compose.waitForIdle()
        assertTrue(opened.isEmpty(), "the reason is shown, not covered by the login page")
        assertEquals(null, storage.session())
    }

    @Test
    fun whileTheAddressIsAskedForTheOtherSettingsAreOneTapAway() {
        startApp(builtIn = false)
        waitFor("Server address")
        compose.onNode(hasSetTextAction()).performTextReplacement("https://meals.example.org")

        compose.onNodeWithText("Settings").performClick()
        waitFor("Appearance")
        compose.onNodeWithText("Nederlands").performScrollTo().performClick()
        waitFor("Instellingen")
        compose.onNodeWithContentDescription("Terug").performClick()

        // Back on the login screen, in Dutch, with the address typed before.
        waitFor("Serveradres")
        compose.onNodeWithText("https://meals.example.org").assertIsDisplayed()
        assertEquals(AppLanguage.DUTCH, storage.appLanguage())
        assertTrue(opened.isEmpty())
    }
}
