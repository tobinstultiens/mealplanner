package dev.tobins.mealplanner.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.tobins.mealplanner.api.AccountRequest
import dev.tobins.mealplanner.api.RegistrationReceipt
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Asking for an account (issue 314), the whole app on a fake server: the login screen offers the form only while the
 * server takes requests, the form sends a request and says it waits, the passwords never reach storage or the saved
 * state, a waiting person's login ends saying so, and only the owner's Settings decide on the requests.
 */
@OptIn(ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RegistrationScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val backend = FakeBackend()
    private val restoration by lazy { StateRestorationTester(compose) }

    /** The device's storage, keeping every value ever written to it, for a test to look for what must never be there. */
    private val written: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val storage = object : SettingsStorage {
        private val inner = MemorySettingsStorage()

        override fun get(key: String) = inner.get(key)

        override fun set(key: String, value: String) {
            written += "$key=$value"
            inner.set(key, value)
        }

        override fun remove(key: String) = inner.remove(key)
    }.apply { saveAppLanguage(AppLanguage.ENGLISH) }

    private val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val launcher = object : LoginLauncher {
        override fun redirectUri(serverUrl: String) = "$serverUrl/app/login"

        override fun open(url: String) {
            opened += url
        }
    }

    /** The access token the login page gives: a person whose request waits, unless a test says otherwise. */
    @Volatile private var loginToken = FakeBackend.WAITING_TOKEN
    private val provider = HttpClient(
        MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.encodedPath) {
                "/api/auth/config" -> respond(
                    """{"issuer":"https://auth.example.org","clientId":"mealplanner","authorizationEndpoint":"https://auth.example.org/api/oidc/authorization","tokenEndpoint":"https://auth.example.org/api/oidc/token","scopes":["openid","groups"]}""",
                    headers = json,
                )
                "/api/oidc/token" -> respond("""{"access_token":"$loginToken","refresh_token":"r","expires_in":3600}""", headers = json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        },
    )
    private val scope = MainScope()
    private val logins = LoginExchange(storage, scope, Login(storage, provider))
    private val notificationAccess = object : NotificationAccess {
        override val allowed = MutableStateFlow(true)

        override fun ask() = Unit

        override fun openSettings() = Unit
    }

    @Before
    fun fixTimeAndLanguage() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        appClock = object : Clock {
            override fun now(): Instant = Instant.parse("${FakeBackend.TODAY}T10:30:00Z")
        }
        RuntimeEnvironment.setQualifiers("en-rGB-w411dp-h1400dp")
    }

    @After
    fun tearDown() {
        scope.cancel()
        backend.close()
        appClock = Clock.System
    }

    private fun startApp() {
        restoration.setContent { App(backend.settings, storage, launcher, logins, notificationAccess = notificationAccess) }
    }

    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitFor(text: String) = waitUntil { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** The text field labelled [label]. */
    private fun field(label: String): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasText(label))

    private val password = "correct horse battery"

    /** Fills in the whole form, both passwords the same. */
    private fun fillIn() {
        field("Your name").performTextInput("Zoe Example")
        field("User name").performTextInput("Zoe")
        field("E-mail address").performTextInput("zoe@example.org")
        field("Password").performTextInput(password)
        field("Password again").performTextInput(password)
    }

    /** The login screen, after the login page that opens by itself was closed, with "Register a new account" offered. */
    private fun openForm() {
        startApp()
        waitFor("Register a new account")
        compose.onNodeWithText("Register a new account").performClick()
        waitFor("Send request")
    }

    /** The login screen of a server whose registration status is [open] (null: a server from before it) offers no form. */
    private fun offersNothingWhen(open: Boolean?) {
        backend.registrationOpen = open
        startApp()
        waitFor("Logs in at 127.0.0.1")
        waitUntil { "GET /api/registration" in backend.answered }
        compose.waitForIdle()
        assertFalse(shows("Register a new account"))
        compose.onNodeWithText("Log in").assertExists()
    }

    @Test
    fun aServerFromBeforeRegistrationOffersNothing() = offersNothingWhen(null)

    @Test
    fun aServerThatTakesNoRequestsOffersNothing() = offersNothingWhen(false)

    @Test
    fun aRequestIsSentAndTheFormSaysItWaitsWithThePasswordNeverStored() {
        backend.registrationOpen = true
        openForm()
        fillIn()
        compose.onNodeWithText("Send request").performScrollTo().performClick()

        waitFor("Request sent")
        assertTrue(shows("Your request for the account zoe is waiting for approval"))
        assertEquals(
            listOf("""{"username":"Zoe","displayName":"Zoe Example","email":"zoe@example.org","password":"$password"}"""),
            backend.registered.toList(),
        )
        assertTrue(written.none { password in it }, "the password is in no stored value: $written")

        compose.onNodeWithText("Back to log in").performClick()
        waitFor("Logs in at 127.0.0.1")
        assertTrue(written.none { password in it })
    }

    @Test
    fun theFormSaysWhatIsWrongAndSendsNothingUntilItIsRight() {
        backend.registrationOpen = true
        openForm()
        field("User name").performTextInput("zoe example")
        // Left with something typed in it: said at once.
        field("E-mail address").performTextInput("zoe@")
        waitFor("letters a to z and digits")
        compose.onNodeWithText("Send request").performScrollTo().performClick()
        waitFor("Fill in your name")
        assertTrue(shows("Some fields are not right yet: see the marked ones."))
        assertTrue(shows("Fill in an e-mail address such as name@example.org"))
        assertTrue(shows("10 to 128 characters, and not your user name."))
        compose.waitForIdle()
        assertTrue(backend.registered.isEmpty(), "nothing is sent while a field is wrong")
    }

    @Test
    fun aRefusalIsSaidOnTheFormWhichKeepsWhatWasTyped() {
        backend.registrationOpen = true
        backend.registrationRefusal = 409 to "unavailable"
        openForm()
        fillIn()
        compose.onNodeWithText("Send request").performScrollTo().performClick()

        waitFor("This user name or e-mail address cannot be used. Choose another.")
        compose.onNodeWithText("Zoe Example").assertExists()
        // The server's own sentence is English only; the app says it by its reason.
        assertFalse(shows("The server's own sentence."))
    }

    @Test
    fun aReCreatedScreenKeepsTheFormButNotThePasswords() {
        backend.registrationOpen = true
        openForm()
        fillIn()

        // Rotation, dark mode, "Don't keep activities": what was typed comes back from the saved state, the passwords do not.
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Send request")
        compose.onNodeWithText("Zoe Example").assertExists()
        compose.onNodeWithText("Send request").performScrollTo().performClick()
        waitFor("10 to 128 characters, and not your user name.")
        compose.waitForIdle()
        assertTrue(backend.registered.isEmpty(), "sent nothing: the password is empty again")
    }

    @Test
    fun thePasswordsAreNeverInTheSavedState() {
        // Android writes what the registry saves into the activity's bundle: the form registers in one the test reads.
        val registry = SaveableStateRegistry(restoredValues = null) { true }
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                RegistrationScreen(send = { RegistrationReceipt(it.username) }, onBack = {})
            }
        }
        fillIn()
        compose.waitForIdle()

        val saved = registry.performSave().toString()
        assertTrue("Zoe Example" in saved, "the other fields are saved, so this looks in the right place: $saved")
        assertFalse(password in saved, saved)
    }

    @Test
    fun aLoginWhoseRequestWaitsEndsOnTheLoginScreenSayingSo() {
        backend.registrationOpen = true
        startApp()
        waitUntil { opened.size == 1 }
        val state = Url(opened.single()).parameters["state"]!!
        logins.deliver("${backend.settings.url}/app/login?code=the-code&state=$state")

        waitFor("Your request is waiting for approval.")
        assertFalse(shows("This account may not use the meal planner yet"))
        assertEquals(LoggedOut.WAITING_FOR_APPROVAL, storage.loggedOut())
        assertEquals(null, storage.session())
        compose.waitForIdle()
        assertEquals(1, opened.size, "no login page opened over the sentence")
    }

    private val zoe = AccountRequest("zoe", "Zoe Example", "zoe@example.org", "2026-11-17T09:12:00Z", "2026-12-01T09:12:00Z")
    private val max = AccountRequest("max", "Max Muster", "max@example.org", "2026-11-18T08:00:00Z", "2026-12-02T08:00:00Z")

    /** Logged in, Settings opened from Progress. */
    private fun openSettings() {
        storage.saveSession(Session("anna-token", expiresAt = Long.MAX_VALUE))
        startApp()
        waitUntil { compose.onAllNodesWithText("Progress").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Progress")[0].performClick()
        waitUntil { compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor("Log out")
    }

    @Test
    fun theOwnerApprovesAndRejectsAndRejectingAsksFirst() {
        backend.registrationOpen = true
        backend.owner = true
        backend.accountRequests += listOf(zoe, max)
        openSettings()
        waitFor("2 requests wait for your decision.")
        assertTrue(shows("zoe · zoe@example.org"))
        assertTrue(shows("Asked yesterday 09:12 · rejected by itself on Tue 1 Dec"))
        assertTrue(shows("Account requests"), "the owner's notification switch")

        compose.onAllNodesWithText("Approve")[0].performClick()
        waitUntil { "POST /api/registrations/zoe/approve" in backend.changes }
        waitFor("1 request waits for your decision.")
        waitFor("Zoe Example can log in now.")

        compose.onNodeWithText("Reject").performClick()
        waitFor("Reject the request of Max Muster?")
        assertTrue(shows("This deletes the account max at the login server."))
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertFalse(backend.changes.any { "reject" in it }, "asked first: Cancel rejects nothing")

        compose.onNodeWithText("Reject").performClick()
        waitFor("Reject the request of Max Muster?")
        compose.onNode(hasText("Reject") and hasAnyAncestor(isDialog())).performClick()
        waitUntil { "POST /api/registrations/max/reject" in backend.changes }
        waitFor("No requests wait.")
    }

    @Test
    fun aRequestDecidedElsewhereSaysSoAndTheListIsAskedAgain() {
        backend.registrationOpen = true
        backend.owner = true
        backend.accountRequests += zoe
        openSettings()
        waitFor("1 request waits for your decision.")
        // Decided on another device meanwhile.
        backend.accountRequests.clear()
        compose.onNodeWithText("Approve").performClick()
        waitFor("This request no longer waits; the list is up to date again.")
        waitFor("No requests wait.")
    }

    @Test
    fun anotherAccountSeesNoRequestsNorTheirSwitch() {
        backend.registrationOpen = true
        backend.accountRequests += zoe
        openSettings()
        waitFor("Notifications")
        compose.waitForIdle()
        assertFalse(shows("Requests for an account"))
        assertFalse(shows("Zoe Example"))
        assertFalse(shows("Account requests"), "no switch for what this account never gets")
        assertFalse("GET /api/registrations" in backend.requests, "not even asked")
    }
}
