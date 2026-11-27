package dev.tobins.mealplanner.app.accounts

import dev.tobins.mealplanner.api.LoginRefusal
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.MemorySettingsStorage
import dev.tobins.mealplanner.app.NotificationKind
import dev.tobins.mealplanner.app.NotificationPlanner
import dev.tobins.mealplanner.app.ServerSettings
import dev.tobins.mealplanner.app.SettingsStorage
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.approvesRequests
import dev.tobins.mealplanner.app.askWhatIsDue
import dev.tobins.mealplanner.app.askWhatIsDueIfWanted
import dev.tobins.mealplanner.app.logOut
import dev.tobins.mealplanner.app.saveApprovesRequests
import dev.tobins.mealplanner.app.saveNotificationOn
import dev.tobins.mealplanner.app.shopping.ShoppingMsg
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.load
import dev.tobins.mealplanner.app.ui.sentence
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)
class LoginTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun sha256MatchesTheStandardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(Sha256.digest(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(Sha256.digest("abc".encodeToByteArray())))
        // Two blocks after padding.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
    }

    @Test
    fun pkceChallengeMatchesRfc7636() {
        val challenge = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode(Sha256.digest("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk".encodeToByteArray()))
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }

    @Test
    fun recognisesTheRedirectBack() {
        val login = Login(MemorySettingsStorage())
        assertTrue(login.isCallback("dev.tobins.mealplanner://login?code=abc&state=xyz"))
        assertTrue(login.isCallback("https://meals.example.nl/?error=access_denied&state=xyz"))
        assertFalse(login.isCallback("https://meals.example.nl/"))
    }

    @Test
    fun sessionsAreStoredAndCleared() {
        val storage = MemorySettingsStorage()
        storage.saveSession(Session("access", "refresh", 123))
        assertEquals(Session("access", "refresh", 123), storage.session())
        storage.saveSession(null)
        assertEquals(null, storage.session())
    }

    /**
     * A server and token endpoint that answer like the meal planner and Authelia. A renewal gets
     * [refreshStatus] instead of tokens when set, or no answer at all when [offline]; the token exchange of
     * a login fails with [exchangeFailure] when set. A token request waits for [gate] when there is one, and
     * a server's login settings for its host's gate in [configGates]. The answers are made on [dispatcher]
     * when given: the test's own, so that a test knows when they are all made.
     */
    private fun fakeProvider(
        forms: MutableList<Map<String, String>>,
        refuseRefresh: Boolean = false,
        refreshStatus: HttpStatusCode? = null,
        offline: Boolean = false,
        gate: CompletableDeferred<Unit>? = null,
        configGates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
        exchangeFailure: Throwable? = null,
        dispatcher: CoroutineDispatcher? = null,
    ) = HttpClient(
        MockEngine(
            MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { request ->
                    val json = headersOf(HttpHeaders.ContentType, "application/json")
                    when (request.url.encodedPath) {
                        "/api/auth/config" -> {
                            configGates[request.url.host]?.await()
                            respond(
                                """{"issuer":"https://auth.example.org","clientId":"mealplanner","authorizationEndpoint":"https://auth.example.org/api/oidc/authorization","tokenEndpoint":"https://auth.example.org/api/oidc/token","scopes":["openid","offline_access"]}""",
                                headers = json,
                            )
                        }
                        "/api/oidc/token" -> {
                            gate?.await()
                            val form = (request.body as FormDataContent).formData
                            val renewal = form["grant_type"] == "refresh_token"
                            if (renewal && offline) throw IllegalStateException("No network")
                            if (!renewal && exchangeFailure != null) throw exchangeFailure
                            forms += form.names().associateWith { form[it]!! }
                            if (refuseRefresh && form["grant_type"] == "refresh_token") respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, json)
                            else if (renewal && refreshStatus != null) respond("""{"error":"server_error"}""", refreshStatus, json)
                            else respond("""{"access_token":"access-${forms.size}","refresh_token":"refresh-${forms.size}","expires_in":3600,"token_type":"bearer"}""", headers = json)
                        }
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }
            },
        ),
    ) { install(ContentNegotiation) { json() } }

    @Test
    fun logsInWithPkceAndRefreshes() = runTest {
        val storage = MemorySettingsStorage()
        val forms = mutableListOf<Map<String, String>>()
        val login = Login(storage, fakeProvider(forms))

        val page = Url(login.start("https://meals.example.nl", "https://meals.example.nl/app/login")!!)
        assertEquals("/api/oidc/authorization", page.encodedPath)
        assertEquals("S256", page.parameters["code_challenge_method"])
        assertEquals("openid offline_access", page.parameters["scope"])
        val state = page.parameters["state"]!!

        val session = login.finish("dev.tobins.mealplanner://login?code=the-code&state=$state")
        assertEquals("access-1", session.accessToken)
        assertEquals(session, storage.session())
        val exchange = forms.single()
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("the-code", exchange["code"])
        assertEquals("mealplanner", exchange["client_id"])
        // The verifier sent now is the one the challenge was made from.
        val verifier = exchange["code_verifier"]!!
        assertEquals(page.parameters["code_challenge"], Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(Sha256.digest(verifier.encodeToByteArray())))

        val renewed = login.refresh("https://meals.example.nl", session)!!
        assertEquals("access-2", renewed.accessToken)
        assertEquals("refresh-1", forms.last()["refresh_token"])
        assertEquals(renewed, storage.session())
    }

    @Test
    fun aForeignOrRefusedAnswerEndsTheLogin() = runTest {
        val storage = MemorySettingsStorage()
        val login = Login(storage, fakeProvider(mutableListOf(), refuseRefresh = true))
        login.start("https://meals.example.nl", "https://meals.example.nl/app/login")
        assertTrue(runCatching { login.finish("dev.tobins.mealplanner://login?code=x&state=forged") }.isFailure, "state must match")

        storage.saveSession(Session("old", "stale", 0))
        assertEquals(null, login.refresh("https://meals.example.nl", storage.session()!!))
        assertEquals(null, storage.session(), "a refused refresh logs out")
    }

    /** What the app does with a redirect it receives (App's login collector): null when it lets it pass unseen. */
    private suspend fun deliver(login: Login, url: String): Result<Session>? =
        if (login.isAwaitedAnswer(url)) runCatching { login.finish(url) } else null

    private suspend fun startedLogin(login: Login) =
        Url(login.start("https://meals.example.nl", "https://meals.example.nl/app/login")!!).parameters["state"]!!

    @Test
    fun theSameAnswerTwiceLogsInOnce() = runTest {
        val storage = MemorySettingsStorage()
        val forms = mutableListOf<Map<String, String>>()
        val login = Login(storage, fakeProvider(forms))
        val answer = "dev.tobins.mealplanner://login?code=the-code&state=${startedLogin(login)}"

        val session = deliver(login, answer)!!.getOrThrow()
        assertEquals(null, deliver(login, answer), "the second delivery is let pass: no error on screen")
        assertEquals(session, storage.session())
        assertEquals(1, forms.size, "the code went to the provider once")
        // Asked directly, the used answer is still refused: nothing about it was loosened.
        assertTrue(runCatching { login.finish(answer) }.isFailure)
        assertEquals(1, forms.size)
    }

    @Test
    fun aRecreatedScreenAfterAFinishedLoginChangesNothing() = runTest {
        val storage = MemorySettingsStorage()
        val forms = mutableListOf<Map<String, String>>()
        val first = Login(storage, fakeProvider(forms))
        val answer = "dev.tobins.mealplanner://login?code=the-code&state=${startedLogin(first)}"
        val session = deliver(first, answer)!!.getOrThrow()

        // The re-created screen makes a Login of its own over the same storage and gets the old redirect.
        val again = Login(storage, fakeProvider(forms))
        assertEquals(null, deliver(again, answer))
        assertEquals(session, storage.session(), "still logged in, with the same tokens")
        assertEquals(1, forms.size)
    }

    @Test
    fun aNewLoginIsAnsweredAfterAnOldOne() = runTest {
        val storage = MemorySettingsStorage()
        val forms = mutableListOf<Map<String, String>>()
        val login = Login(storage, fakeProvider(forms))
        val oldAnswer = "dev.tobins.mealplanner://login?code=old-code&state=${startedLogin(login)}"
        val first = deliver(login, oldAnswer)!!.getOrThrow()

        val newAnswer = "dev.tobins.mealplanner://login?code=new-code&state=${startedLogin(login)}"
        // While the new login waits, the old answer is another login's: refused, and nothing is ended by it.
        val refused = deliver(login, oldAnswer)!!
        assertEquals(Strings.current(AccountsMsg.LOGIN_ANSWER_MISMATCH), refused.exceptionOrNull()?.message)
        assertEquals(first, storage.session(), "a refused answer does not log out")

        val second = deliver(login, newAnswer)!!.getOrThrow()
        assertEquals(second, storage.session())
        assertEquals(listOf("old-code", "new-code"), forms.map { it["code"] })
        assertTrue(forms[0]["code_verifier"] != forms[1]["code_verifier"], "each login has its own PKCE secret")
    }

    @Test
    fun aRefusalFromTheProviderIsShownOnce() = runTest {
        val storage = MemorySettingsStorage()
        val forms = mutableListOf<Map<String, String>>()
        val login = Login(storage, fakeProvider(forms))
        val state = startedLogin(login)

        // An error with another login's state is not the provider's word on ours: its text is not shown, and ours still waits.
        val foreign = deliver(login, "https://meals.example.nl/app/login?error=access_denied&error_description=Pay+here&state=forged")!!
        assertEquals(Strings.current(AccountsMsg.LOGIN_ANSWER_MISMATCH), foreign.exceptionOrNull()?.message)

        val refusal = "https://meals.example.nl/app/login?error=access_denied&error_description=The+user+said+no&state=$state"
        assertEquals("The user said no", deliver(login, refusal)!!.exceptionOrNull()?.message)
        assertEquals(null, deliver(login, refusal), "the refusal ended the login; again it is let pass")
        assertEquals(null, storage.session())
        assertTrue(forms.isEmpty())
    }

    // --- The login as the app goes through it: LoginExchange, which the platform keeps longer than the screen.

    private fun TestScope.exchange(storage: SettingsStorage, client: HttpClient, clock: Clock = Clock.System) =
        LoginExchange(storage, backgroundScope, Login(storage, client, clock))

    private suspend fun startedLogin(exchange: LoginExchange) =
        Url(exchange.start("https://meals.example.nl", "https://meals.example.nl/app/login")!!).parameters["state"]!!

    private suspend fun LoginExchange.outcome() = progress.first { it is LoginProgress.Done || it is LoginProgress.Failed }

    @Test
    fun aLoggedOutAppOpensTheLoginPageByItselfOnce() = runTest {
        val storage = MemorySettingsStorage()
        val logins = exchange(storage, fakeProvider(mutableListOf()))
        assertFalse(logins.claimAutoOpen(serverKnown = false), "not without a server to log in to")
        assertTrue(logins.claimAutoOpen(serverKnown = true))
        // The user closed the page: Back, a rotation or the app coming to the front again opens nothing.
        assertFalse(logins.claimAutoOpen(serverKnown = true))
        // Neither does a re-created screen with "Don't keep activities", nor a restarted app: both read the same storage.
        assertFalse(exchange(storage, fakeProvider(mutableListOf())).claimAutoOpen(serverKnown = true))
        assertEquals(LoggedOut.LOGIN_OPENED, storage.loggedOut())
        assertEquals(LoginProgress.Idle, logins.progress.value, "a closed page is no failure")
    }

    @Test
    fun aLoginTheUserStartedAndClosedOpensNothingByItself() = runTest {
        val storage = MemorySettingsStorage()
        val forms = mutableListOf<Map<String, String>>()
        val logins = exchange(storage, fakeProvider(forms))
        // No server known yet: the user typed one and tapped Log in, then closed the page.
        startedLogin(logins)
        assertFalse(logins.claimAutoOpen(serverKnown = true))
        // Tapping Log in again starts afresh, and that login's answer logs in.
        val state = startedLogin(logins)
        logins.deliver("https://meals.example.nl/app/login?code=the-code&state=$state")
        assertTrue(logins.outcome() is LoginProgress.Done)
        assertEquals(1, forms.size)
    }

    @Test
    fun aDeliberateLogOutOpensNothingAndSaysWhy() = runTest {
        val storage = MemorySettingsStorage()
        val logins = exchange(storage, fakeProvider(mutableListOf()))
        logins.deliver("https://meals.example.nl/app/login?code=c&state=${startedLogin(logins)}")
        val done = logins.outcome() as LoginProgress.Done
        assertEquals(null, storage.loggedOut(), "a login forgets why the last session ended")

        logins.logOut()
        assertEquals(null, storage.session())
        assertEquals(LoggedOut.BY_USER, storage.loggedOut())
        assertFalse(logins.claimAutoOpen(serverKnown = true))
        // A login nobody took before the logout cannot come back on a later screen.
        assertEquals(LoginProgress.Idle, logins.progress.value)
        logins.acknowledge(done)
        assertEquals(LoginProgress.Idle, logins.progress.value)
    }

    @Test
    fun theTokenExchangeOutlivesARecreatedScreen() = runTest {
        val storage = MemorySettingsStorage()
        val forms = mutableListOf<Map<String, String>>()
        val gate = CompletableDeferred<Unit>()
        val logins = exchange(storage, fakeProvider(forms, gate = gate))
        val answer = "https://meals.example.nl/app/login?code=the-code&state=${startedLogin(logins)}"

        // The screen that received the answer goes (rotation, dark mode) while the token request waits.
        val firstScreen = launch { logins.progress.collect {} }
        logins.deliver(answer)
        logins.progress.first { it == LoginProgress.Exchanging }
        firstScreen.cancel()
        gate.complete(Unit)

        // The new screen finds the login done, saved, from one token request.
        val done = logins.outcome() as LoginProgress.Done
        assertEquals(done.session, storage.session())
        assertEquals(1, forms.size)
        logins.acknowledge(done)
        assertEquals(LoginProgress.Idle, logins.progress.value, "taken once: a later screen does not take it again")
        // The answer is used up: handed over again it asks nothing, and on its own it is still refused.
        logins.deliver(answer)
        assertEquals(LoginProgress.Idle, logins.progress.value)
        assertEquals(1, forms.size)
        assertTrue(runCatching { logins.login.finish(answer) }.isFailure)
    }

    @Test
    fun theSameAnswerHandedOverTwiceAtOnceIsExchangedOnce() = runTest {
        val storage = MemorySettingsStorage()
        val forms = mutableListOf<Map<String, String>>()
        val logins = exchange(storage, fakeProvider(forms))
        val answer = "dev.tobins.mealplanner://login?code=the-code&state=${startedLogin(logins)}"
        val seen = mutableListOf<LoginProgress>()
        backgroundScope.launch { logins.progress.collect { seen += it } }

        logins.deliver(answer)
        logins.deliver(answer)
        assertTrue(logins.outcome() is LoginProgress.Done)
        advanceUntilIdle()
        assertEquals(1, forms.size)
        assertTrue(seen.none { it is LoginProgress.Failed }, "the second one is let pass, not shown as an error: $seen")
    }

    @Test
    fun aForeignAnswerWhileALoginWaitsIsRefusedAndTheLoginStillWaits() = runTest {
        val storage = MemorySettingsStorage()
        val forms = mutableListOf<Map<String, String>>()
        val logins = exchange(storage, fakeProvider(forms))
        val state = startedLogin(logins)

        logins.deliver("https://meals.example.nl/app/login?code=stolen&state=forged")
        assertEquals(LoginProgress.Failed(sentence(Strings.current(AccountsMsg.LOGIN_ANSWER_MISMATCH))), logins.outcome())
        assertEquals(null, storage.session())
        assertTrue(forms.isEmpty(), "a foreign code never reaches the provider")

        logins.deliver("https://meals.example.nl/app/login?code=the-code&state=$state")
        assertTrue(logins.progress.first { it is LoginProgress.Done } is LoginProgress.Done)
        assertEquals(listOf("the-code"), forms.map { it["code"] })
    }

    @Test
    fun aFailureShownGoesWhenTheNextLoginStarts() = runTest {
        val storage = MemorySettingsStorage()
        val logins = exchange(storage, fakeProvider(mutableListOf()))
        val state = startedLogin(logins)
        logins.deliver("https://meals.example.nl/app/login?error=access_denied&error_description=No&state=$state")
        assertEquals(LoginProgress.Failed("No."), logins.outcome())
        startedLogin(logins)
        assertEquals(LoginProgress.Idle, logins.progress.value)
    }

    @Test
    fun aPageLoadedWithAnAnswerOpensNoLoginPageByItself() = runTest {
        // The web: a cancelled login comes back to the page with an error, also when nothing waits for it
        // (the site's storage was cleared meanwhile). Showing the login screen, not going round again.
        val storage = MemorySettingsStorage()
        val logins = exchange(storage, fakeProvider(mutableListOf()))
        logins.deliver("https://meals.example.nl/?error=access_denied&state=xyz")
        assertFalse(logins.claimAutoOpen(serverKnown = true))
        advanceUntilIdle()
        assertEquals(LoginProgress.Idle, logins.progress.value, "an answer nothing waits for is let pass")
    }

    @Test
    fun aRefusedRenewalEndsTheSessionAndSaysWhy() = runTest {
        val storage = MemorySettingsStorage()
        val logins = exchange(storage, fakeProvider(mutableListOf(), refreshStatus = HttpStatusCode.BadRequest))
        storage.saveSession(Session("old", "revoked", 0))
        assertEquals(null, logins.renew("https://meals.example.nl"))
        assertEquals(null, storage.session())
        assertEquals(LoggedOut.EXPIRED, storage.loggedOut())
        assertFalse(logins.claimAutoOpen(serverKnown = true), "the login screen says why, the page does not cover it")
    }

    @Test
    fun aSessionThatCannotBeRenewedEndsWhenItsTokenIsRefused() = runTest {
        val storage = MemorySettingsStorage()
        val forms = mutableListOf<Map<String, String>>()
        val logins = exchange(storage, fakeProvider(forms))
        storage.saveSession(Session("old", refreshToken = null))
        assertEquals(null, logins.renew("https://meals.example.nl"))
        assertEquals(null, storage.session())
        assertEquals(LoggedOut.EXPIRED, storage.loggedOut())
        assertTrue(forms.isEmpty())
    }

    @Test
    fun aNetworkErrorOrAProviderInTroubleKeepsTheSession() = runTest {
        val session = Session("old", "still-good", 0)
        for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.TooManyRequests, HttpStatusCode.NotFound)) {
            val storage = MemorySettingsStorage().apply { saveSession(session) }
            val logins = exchange(storage, fakeProvider(mutableListOf(), refreshStatus = status))
            assertEquals(null, logins.renew("https://meals.example.nl"), "$status")
            assertEquals(session, storage.session(), "$status ends nothing")
            assertEquals(null, storage.loggedOut())
        }
        val storage = MemorySettingsStorage().apply { saveSession(session) }
        val offline = exchange(storage, fakeProvider(mutableListOf(), offline = true))
        assertTrue(runCatching { offline.renew("https://meals.example.nl") }.isFailure, "the request fails as any other without a network")
        assertEquals(session, storage.session())
        assertEquals(null, storage.loggedOut())
    }

    @Test
    fun aLogOutWhileARenewalIsRefusedKeepsItsOwnReason() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "revoked", 0)) }
        val gate = CompletableDeferred<Unit>()
        val logins = exchange(storage, fakeProvider(mutableListOf(), refreshStatus = HttpStatusCode.BadRequest, gate = gate))
        val renewal = async { logins.renew("https://meals.example.nl") }
        advanceUntilIdle()
        logins.logOut()
        gate.complete(Unit)
        assertEquals(null, renewal.await())
        assertEquals(LoggedOut.BY_USER, storage.loggedOut())
    }

    // --- A log-out or another server calls off a login under way (#198).

    @Test
    fun aLogOutDuringTheTokenExchangeDropsItsResult() = runTest {
        val storage = MemorySettingsStorage()
        val forms = mutableListOf<Map<String, String>>()
        val gate = CompletableDeferred<Unit>()
        val logins = exchange(storage, fakeProvider(forms, gate = gate, dispatcher = StandardTestDispatcher(testScheduler)))
        logins.deliver("https://meals.example.nl/app/login?code=the-code&state=${startedLogin(logins)}")
        // runCurrent, not advanceUntilIdle: the exchange is background work, which only runCurrent runs to the end.
        runCurrent()
        assertEquals(LoginProgress.Exchanging, logins.progress.value)

        // Another server saved (or Log out) while the provider answers.
        logins.logOut()
        assertEquals(LoginProgress.Idle, logins.progress.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(null, storage.session(), "the exchange called off saved nothing")
        assertEquals(LoginProgress.Idle, logins.progress.value, "and left nothing for a screen to take")

        // The next login is answered as usual.
        logins.deliver("https://meals.example.nl/app/login?code=next-code&state=${startedLogin(logins)}")
        assertTrue(logins.outcome() is LoginProgress.Done)
        assertEquals("next-code", forms.last()["code"])
    }

    @Test
    fun aFailureReportedAsACancellationIsShownAsAFailure() = runTest {
        // A network layer may report an ordinary failure (a timeout) as a cancellation. Only a log-out or
        // another server calls a login off; anything else must not leave the screen on "Logging in…".
        val storage = MemorySettingsStorage()
        val failure = CancellationException("Request timeout has expired [url=https://auth.example.org/api/oidc/token]")
        val logins = exchange(storage, fakeProvider(mutableListOf(), exchangeFailure = failure, dispatcher = StandardTestDispatcher(testScheduler)))
        logins.deliver("https://meals.example.nl/app/login?code=the-code&state=${startedLogin(logins)}")
        runCurrent()
        assertEquals(LoginProgress.Failed(sentence(Strings.current(ShoppingMsg.WENT_WRONG))), logins.progress.value, "a failure, in a plain sentence")
        assertEquals(null, storage.session())
    }

    @Test
    fun aLoginPageStillOpenAtALogOutAnswersNothing() = runTest {
        val storage = MemorySettingsStorage()
        val forms = mutableListOf<Map<String, String>>()
        val logins = exchange(storage, fakeProvider(forms))
        val state = startedLogin(logins)
        logins.logOut()

        // The page was left open in the browser and answered afterwards.
        logins.deliver("https://meals.example.nl/app/login?code=late-code&state=$state")
        runCurrent()
        assertEquals(LoginProgress.Idle, logins.progress.value, "let pass, not shown as an error")
        assertEquals(null, storage.session())
        assertTrue(forms.isEmpty(), "its code never reached the provider")
    }

    @Test
    fun aLoginCalledOffWhileItStartsOpensNoPage() = runTest {
        val storage = MemorySettingsStorage()
        val configGate = CompletableDeferred<Unit>()
        val logins = exchange(storage, fakeProvider(mutableListOf(), configGates = mapOf("meals.example.nl" to configGate), dispatcher = StandardTestDispatcher(testScheduler)))
        val page = async { logins.start("https://meals.example.nl", "https://meals.example.nl/app/login") }
        advanceUntilIdle()

        // Another server saved while the server is asked how to log in.
        logins.logOut()
        configGate.complete(Unit)
        assertEquals(null, page.await(), "nothing to open")
        assertFalse(logins.login.isAwaitedAnswer("https://meals.example.nl/app/login?code=c&state=s"), "and no login waits for an answer")
    }

    @Test
    fun twoLoginsStartedAtOnceLeaveTheLastOneWaiting() = runTest {
        // The screens let one login start at a time (Log in waits meanwhile); LoginExchange must hold without
        // that too, whichever server answers first: the last login started is the one that may log in.
        for (newerAnswersFirst in listOf(true, false)) {
            val storage = MemorySettingsStorage()
            val older = CompletableDeferred<Unit>()
            val newer = CompletableDeferred<Unit>()
            val gates = mapOf("meals.example.nl" to older, "meals.example.org" to newer)
            val logins = exchange(storage, fakeProvider(mutableListOf(), configGates = gates, dispatcher = StandardTestDispatcher(testScheduler)))
            val olderPage = async { logins.start("https://meals.example.nl", "https://meals.example.nl/app/login") }
            val newerPage = async { logins.start("https://meals.example.org", "https://meals.example.org/app/login") }
            advanceUntilIdle()
            for (gate in if (newerAnswersFirst) listOf(newer, older) else listOf(older, newer)) {
                gate.complete(Unit)
                advanceUntilIdle()
            }

            assertEquals(null, olderPage.await(), "the older login opens no page (newer answered first: $newerAnswersFirst)")
            val answer = "https://meals.example.org/app/login?code=the-code&state=${Url(newerPage.await()!!).parameters["state"]}"
            assertTrue(logins.login.isAwaitedAnswer(answer), "the newer login still waits for its answer (newer answered first: $newerAnswersFirst)")
            logins.deliver(answer)
            assertTrue(logins.outcome() is LoginProgress.Done)
        }
    }

    @Test
    fun aLoginNoLongerWantedOnceTheServerAnsweredLeavesTheWaitingOneAlone() = runTest {
        val login = Login(MemorySettingsStorage(), fakeProvider(mutableListOf()))
        val waiting = login.start("https://meals.example.org", "https://meals.example.org/app/login")!!
        val answer = "https://meals.example.org/app/login?code=c&state=${Url(waiting).parameters["state"]}"
        assertEquals(null, login.start("https://meals.example.nl", "https://meals.example.nl/app/login") { false }, "nothing to open")
        assertTrue(login.isAwaitedAnswer(answer), "the login waiting before still waits")
        login.abandon()
        assertFalse(login.isAwaitedAnswer(answer))
    }

    @Test
    fun aRenewalAnsweredAfterALogOutIsDropped() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        val gate = CompletableDeferred<Unit>()
        val logins = exchange(storage, fakeProvider(mutableListOf(), gate = gate))
        val renewal = async { logins.renew("https://meals.example.nl") }
        advanceUntilIdle()

        logins.logOut()
        gate.complete(Unit)
        assertEquals(null, renewal.await(), "the old client gets no token")
        assertEquals(null, storage.session(), "the session the user ended does not come back")
        assertEquals(LoggedOut.BY_USER, storage.loggedOut())
    }

    @Test
    fun aRefusedRenewalOfAnOldSessionLeavesTheNewOneAlone() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "revoked", 0)) }
        val gate = CompletableDeferred<Unit>()
        val logins = exchange(storage, fakeProvider(mutableListOf(), refreshStatus = HttpStatusCode.BadRequest, gate = gate))
        val renewal = async { logins.renew("https://meals.example.nl") }
        advanceUntilIdle()

        // Logged out and in again, perhaps as someone else, while the old renewal waited.
        val next = Session("new", "fresh", 0)
        storage.saveSession(next)
        gate.complete(Unit)
        assertEquals(null, renewal.await())
        assertEquals(next, storage.session())
        assertEquals(null, storage.loggedOut())
    }

    @Test
    fun anotherServerChosenWithoutASessionSaysNothingAboutALogOut() = runTest {
        val storage = MemorySettingsStorage()
        val logins = exchange(storage, fakeProvider(mutableListOf(), refreshStatus = HttpStatusCode.BadRequest))
        // A fresh install: the login page opened by itself and was closed; then another server is saved.
        assertTrue(logins.claimAutoOpen(serverKnown = true))
        logins.logOut()
        assertEquals(LoggedOut.LOGIN_OPENED, storage.loggedOut(), "no sentence about logging out")
        assertFalse(logins.claimAutoOpen(serverKnown = true), "and nothing opens by itself")

        // Neither does the reason the old server's session ended stay: it is not about the new one.
        storage.saveSession(Session("old", "revoked", 0))
        logins.renew("https://meals.example.nl")
        assertEquals(LoggedOut.EXPIRED, storage.loggedOut())
        logins.logOut()
        assertEquals(LoggedOut.LOGIN_OPENED, storage.loggedOut())
    }

    // --- A token endpoint that never refuses properly ends the session after a while (#198).

    private val firstFailure = Instant.parse("2026-11-18T10:00:00Z")

    /**
     * Renews the stored session [at] after [firstFailure], with a provider that answers [status] (new
     * tokens when null), or nothing at all when [offline].
     */
    private suspend fun TestScope.renewAt(storage: SettingsStorage, at: Duration, status: HttpStatusCode? = null, offline: Boolean = false): Session? {
        val clock = object : Clock {
            override fun now() = firstFailure + at
        }
        val logins = exchange(storage, fakeProvider(mutableListOf(), refreshStatus = status, offline = offline), clock)
        if (!offline) return logins.renew("https://meals.example.nl")
        assertTrue(runCatching { logins.renew("https://meals.example.nl") }.isFailure)
        return null
    }

    @Test
    fun aTokenEndpointThatKeepsAnsweringAnotherClientErrorEndsTheSessionAfterAQuarterOfAnHour() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound)) {
            val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
            // Three in a row within minutes, as a proxy's 404 while the provider restarts: the session stays.
            for (at in listOf(0.minutes, 1.minutes, 14.minutes)) {
                assertEquals(null, renewAt(storage, at, status))
                assertNotNull(storage.session(), "$status after $at ends nothing")
            }
            assertEquals(null, storage.loggedOut())

            renewAt(storage, 15.minutes, status)
            assertEquals(null, storage.session(), "$status for a quarter of an hour ends the session")
            assertEquals(LoggedOut.NOT_RENEWED, storage.loggedOut())
            assertFalse(exchange(storage, fakeProvider(mutableListOf())).claimAutoOpen(serverKnown = true), "the login screen says why")
        }
    }

    @Test
    fun aProviderInTroubleEndsTheSessionOnlyAfterADay() = runTest {
        for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.InternalServerError, HttpStatusCode.TooManyRequests)) {
            val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
            for (at in listOf(0.minutes, 15.minutes, 1.hours, 23.hours + 59.minutes)) {
                renewAt(storage, at, status)
                assertNotNull(storage.session(), "$status after $at ends nothing")
            }
            renewAt(storage, 24.hours, status)
            assertEquals(null, storage.session(), "$status for a day ends the session")
            assertEquals(LoggedOut.NOT_RENEWED, storage.loggedOut())
        }
    }

    @Test
    fun twoFailedRenewalsEndNothingHoweverFarApart() = runTest {
        // A token unused for a week expired a week ago: what counts is how long and how often renewing failed.
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        renewAt(storage, 0.minutes, HttpStatusCode.NotFound)
        renewAt(storage, 7.days, HttpStatusCode.NotFound)
        assertNotNull(storage.session())
        renewAt(storage, 7.days + 1.minutes, HttpStatusCode.NotFound)
        assertEquals(null, storage.session(), "the third one, a week after the first, ends it")
    }

    @Test
    fun aNetworkErrorNeitherCountsNorStartsTheFailuresAfresh() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        renewAt(storage, 0.minutes, HttpStatusCode.NotFound)
        for (at in listOf(1.minutes, 2.minutes, 20.minutes)) renewAt(storage, at, offline = true)
        assertNotNull(storage.session(), "no answer says nothing about the session")
        renewAt(storage, 20.minutes, HttpStatusCode.NotFound)
        assertNotNull(storage.session(), "the second failure with an answer")
        renewAt(storage, 21.minutes, HttpStatusCode.NotFound)
        assertEquals(null, storage.session(), "the third, counted from the first")
        assertEquals(LoggedOut.NOT_RENEWED, storage.loggedOut())
    }

    @Test
    fun aRenewedSessionStartsTheFailuresAfresh() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        for (at in listOf(0.minutes, 1.minutes, 2.minutes)) renewAt(storage, at, HttpStatusCode.NotFound)
        assertNotNull(renewAt(storage, 3.minutes), "renewed")

        for (at in listOf(16.minutes, 17.minutes, 18.minutes)) {
            renewAt(storage, at, HttpStatusCode.NotFound)
            assertNotNull(storage.session(), "a new run of failures after $at")
        }
        renewAt(storage, 31.minutes, HttpStatusCode.NotFound)
        assertEquals(null, storage.session())
    }

    @Test
    fun aDayOfAProviderInTroubleIsWaitedOutWhicheverErrorComesLast() = runTest {
        // From the review of #237: a day of 503s and then one 404 ended the session under the 15-minute rule.
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        renewAt(storage, 0.minutes, HttpStatusCode.ServiceUnavailable)
        renewAt(storage, 12.hours, HttpStatusCode.ServiceUnavailable)
        renewAt(storage, 23.hours, HttpStatusCode.NotFound)
        assertNotNull(storage.session(), "a 404 after 503s waits the day a provider in trouble is given")
        renewAt(storage, 24.hours, HttpStatusCode.NotFound)
        assertEquals(null, storage.session(), "a day after the first failure")
        assertEquals(LoggedOut.NOT_RENEWED, storage.loggedOut())
    }

    @Test
    fun aRunThatBeganWithAnotherClientErrorWaitsADayOnceTheProviderIsInTrouble() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        renewAt(storage, 0.minutes, HttpStatusCode.NotFound)
        renewAt(storage, 1.minutes, HttpStatusCode.ServiceUnavailable)
        for (at in listOf(20.minutes, 23.hours + 59.minutes)) {
            renewAt(storage, at, HttpStatusCode.NotFound)
            assertNotNull(storage.session(), "one 503 in the run after $at: it waits a day")
        }
        renewAt(storage, 24.hours, HttpStatusCode.NotFound)
        assertEquals(null, storage.session(), "counted from the first failure, the 404")
        assertEquals(LoggedOut.NOT_RENEWED, storage.loggedOut())
    }

    @Test
    fun aRunStoredByAnOlderAppIsTakenAsAProviderInTrouble() = runTest {
        // An app from before the kinds of error were kept did not say which its two failures were.
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        storage.set("auth.failedRenewals", """{"count":2,"since":${firstFailure.epochSeconds}}""")
        renewAt(storage, 20.minutes, HttpStatusCode.NotFound)
        assertNotNull(storage.session(), "it ends no sooner than the rule promises, whatever they were")
        renewAt(storage, 24.hours, HttpStatusCode.NotFound)
        assertEquals(null, storage.session())
        assertEquals(LoggedOut.NOT_RENEWED, storage.loggedOut())
    }

    // --- The background check of the notifications (Android, issue 225): the same renewals, quietly.

    private val server = ServerSettings("https://meals.example.nl")

    /**
     * The meal planner's API: what is due, for the access token [accepted] says, else 401 as the server
     * answers an expired token; [answering] runs as it answers.
     */
    private fun api(accepted: () -> String?, answering: () -> Unit = {}) = MockEngine { request ->
        if (request.headers[HttpHeaders.Authorization] != "Bearer ${accepted()}") {
            respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Bearer"))
        } else {
            answering()
            respond("""{"date":"2026-11-21","mealsToMark":2}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    @Test
    fun aLoggedOutAppAsksNothingInTheBackground() = runTest {
        val storage = MemorySettingsStorage()
        var asked = false
        val logins = exchange(storage, fakeProvider(mutableListOf()))
        assertEquals(null, askWhatIsDue(storage, logins, server, api({ asked = true; null })))
        assertFalse(asked)
    }

    /** A planner whose device clock shows 09:00, inside the windows of the kinds that are on by default. */
    private fun morning(storage: SettingsStorage) = NotificationPlanner(
        storage,
        object : Clock {
            override fun now() = Instant.parse("2026-11-21T09:00:00Z")
        },
        kotlinx.datetime.TimeZone.UTC,
    )

    @Test
    fun anAccountThatDecidesOnNoRequestsAndSwitchedOffEveryVisibleKindAsksNothingInTheBackground() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", Long.MAX_VALUE)) }
        NotificationKind.shown(approvesRequests = false).forEach { storage.saveNotificationOn(it, false) }
        var asked = 0
        val logins = exchange(storage, fakeProvider(mutableListOf()))
        assertEquals(null, askWhatIsDueIfWanted(morning(storage), storage, logins, server, api({ asked++; "old" })))
        assertEquals(0, asked, "the hidden switch of account requests is on, but this account never gets them")

        // The owner's account, with the same switches off but that one on, is asked.
        storage.saveApprovesRequests(true)
        assertNotNull(askWhatIsDueIfWanted(morning(storage), storage, logins, server, api({ asked++; "old" })))
        assertEquals(1, asked)
        // And a log-out forgets that the account was the owner's.
        storage.logOut()
        assertFalse(storage.approvesRequests())
    }

    @Test
    fun theBackgroundRenewsAnExpiredTokenAndKeepsTheNewOne() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        val forms = mutableListOf<Map<String, String>>()
        val logins = exchange(storage, fakeProvider(forms))
        assertEquals(2, askWhatIsDue(storage, logins, server, api({ "access-1" }))?.mealsToMark)
        assertEquals("access-1", storage.session()?.accessToken)
        assertEquals(1, forms.size)
    }

    @Test
    fun aRefusalInTheBackgroundEndsNoSession() = runTest {
        for (session in listOf(Session("old", "revoked", 0), Session("old", refreshToken = null))) {
            val storage = MemorySettingsStorage().apply { saveSession(session) }
            val logins = exchange(storage, fakeProvider(mutableListOf(), refuseRefresh = true))
            assertEquals(null, askWhatIsDue(storage, logins, server, api({ "access-1" })))
            assertEquals(session, storage.session(), "the screens find out, and say why")
            assertEquals(null, storage.loggedOut())
        }
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        val offline = exchange(storage, fakeProvider(mutableListOf(), offline = true))
        assertEquals(null, askWhatIsDue(storage, offline, server, api({ "access-1" })), "no answer: nothing to tell, and no error")
        assertNotNull(storage.session())
    }

    @Test
    fun failedRenewalsInTheBackgroundDoNotCountTowardsEndingTheSession() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        for (at in listOf(0.minutes, 1.minutes, 2.minutes, 30.minutes)) {
            val clock = object : Clock {
                override fun now() = firstFailure + at
            }
            val logins = exchange(storage, fakeProvider(mutableListOf(), refreshStatus = HttpStatusCode.NotFound), clock)
            assertEquals(null, askWhatIsDue(storage, logins, server, api({ "access-1" })))
        }
        // Had they counted, the screens' first failure after them would end the session; it takes three in a row of their own.
        renewAt(storage, 31.minutes, HttpStatusCode.NotFound)
        renewAt(storage, 32.minutes, HttpStatusCode.NotFound)
        assertNotNull(storage.session())
        assertEquals(null, storage.loggedOut())
    }

    @Test
    fun aRenewalInTheBackgroundEndsARunOfFailuresAsAnyRenewalThatWorks() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        for (at in listOf(0.minutes, 1.minutes)) renewAt(storage, at, HttpStatusCode.NotFound)
        assertNotNull(askWhatIsDue(storage, exchange(storage, fakeProvider(mutableListOf())), server, api({ "access-1" })))
        renewAt(storage, 20.minutes, HttpStatusCode.NotFound)
        assertNotNull(storage.session(), "a new run of failures")
    }

    @Test
    fun aTokenRefusedTwiceAtOnceIsRenewedOnce() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        val forms = mutableListOf<Map<String, String>>()
        val gate = CompletableDeferred<Unit>()
        val logins = exchange(storage, fakeProvider(forms, gate = gate))
        // The screens and the background check both had "old" refused, at the same moment.
        val screens = async { logins.renew(server.url, refused = "old") }
        val background = async { logins.renew(server.url, refused = "old", quietly = true) }
        advanceUntilIdle()
        gate.complete(Unit)
        assertEquals("access-1", screens.await()?.accessToken)
        assertEquals("access-1", background.await()?.accessToken)
        assertEquals(1, forms.size, "a rotated refresh token is sent once")
    }

    @Test
    fun theScreensTakeTheTokenTheBackgroundRenewed() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        val forms = mutableListOf<Map<String, String>>()
        val logins = exchange(storage, fakeProvider(forms))
        // The screens' client was made with the session as it was when the app opened.
        val screens = ApiClient(server, storage.session(), api({ storage.session()?.accessToken })) { logins.renew(server.url, it) }
        assertNotNull(askWhatIsDue(storage, logins, server, api({ "access-1" })))
        assertEquals(2, screens.notifications().mealsToMark)
        assertEquals(1, forms.size, "renewed once, in the background")
        screens.close()
    }

    @Test
    fun anAnswerForASessionThatChangedMeanwhileIsDropped() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("alice", "good", 0)) }
        val logins = exchange(storage, fakeProvider(mutableListOf()))
        // Logged out and in again, perhaps as someone else, while the server answered for the old session.
        val answer = askWhatIsDue(storage, logins, server, api({ "alice" }, answering = { storage.saveSession(Session("bob", "his", 0)) }))
        assertEquals(null, answer)
    }

    // --- Two holders of a token, the screens' client and the background's: a token taken from the other only while it works.

    /** A clock the test moves on. */
    private class MovingClock(var now: Instant) : Clock {
        override fun now() = now
    }

    /** The API as the server answers it at [clock]'s time: the stored token only, and only until it runs out. */
    private fun liveApi(storage: SettingsStorage, clock: MovingClock) =
        api({ storage.session()?.takeIf { it.expiresAt > clock.now.epochSeconds }?.accessToken })

    /**
     * The background renews "old" at [firstFailure] (the provider gives an hour); the screens' client, made with
     * "old", asks [later]. Answers what the screens got and how often the provider was asked.
     */
    private suspend fun TestScope.screensAfterABackgroundRenewal(later: Duration): Pair<Result<Int>, Int> {
        val storage = MemorySettingsStorage().apply { saveSession(Session("old", "good", 0)) }
        val forms = mutableListOf<Map<String, String>>()
        val clock = MovingClock(firstFailure)
        val logins = exchange(storage, fakeProvider(forms), clock)
        val screens = ApiClient(server, storage.session(), liveApi(storage, clock)) { logins.renew(server.url, it) }
        assertNotNull(askWhatIsDue(storage, logins, server, liveApi(storage, clock)))
        assertEquals(1, forms.size, "renewed in the background")
        clock.now = firstFailure + later
        val answer = runCatching { screens.notifications().mealsToMark }
        screens.close()
        return answer to forms.size
    }

    @Test
    fun theScreensRenewWhenTheTokenTheBackgroundRenewedHasRunOutToo() = runTest {
        // Two hours on, the background's token has run out as well: handing it over would fail the request.
        val (answer, renewals) = screensAfterABackgroundRenewal(2.hours)
        assertEquals(2, answer.getOrThrow(), "the first request in front works")
        assertEquals(2, renewals, "renewed for real")
    }

    @Test
    fun aTokenAboutToRunOutIsRenewedRatherThanHandedOver() = runTest {
        // Within the margin before it runs out the request it is retried with may not make it in time.
        val (inMargin, renewedThen) = screensAfterABackgroundRenewal(1.hours - 2.minutes)
        assertEquals(2, inMargin.getOrThrow())
        assertEquals(2, renewedThen)
        // Well before, it is taken as it is.
        val (early, renewedEarly) = screensAfterABackgroundRenewal(30.minutes)
        assertEquals(2, early.getOrThrow())
        assertEquals(1, renewedEarly, "no second renewal")
    }

    @Test
    fun aStoredSessionThatDoesNotSayWhenItRunsOutIsRenewed() = runTest {
        val storage = MemorySettingsStorage().apply { saveSession(Session("new", "good", expiresAt = 0)) }
        val forms = mutableListOf<Map<String, String>>()
        val logins = exchange(storage, fakeProvider(forms))
        assertEquals("access-1", logins.renew(server.url, refused = "old")?.accessToken)
        assertEquals(1, forms.size)
    }

    @Test
    fun theStoredTokenRefusedIsRenewedHoweverLongItLooksValid() = runTest {
        // The server's clock ahead of the device's, or the token revoked: the device's view of its expiry says nothing then.
        val clock = MovingClock(firstFailure)
        val storage = MemorySettingsStorage().apply { saveSession(Session("stored", "good", expiresAt = (firstFailure + 1.hours).epochSeconds)) }
        val forms = mutableListOf<Map<String, String>>()
        val logins = exchange(storage, fakeProvider(forms), clock)
        assertEquals("access-1", logins.renew(server.url, refused = "stored")?.accessToken)
        assertEquals(1, forms.size)
    }

    // --- A login the server refuses: not in the group that may use it (issue 314).

    /**
     * The meal planner's API as a server with `MEALPLANNER_OIDC_GROUP` answers a login outside the group: 403 on
     * every request, with [reason] next to the sentence (none: a 403 that is not about the login), and [detail] when given.
     */
    private fun refusing(reason: String?, asked: MutableList<String?> = mutableListOf(), detail: String? = null) = MockEngine { request ->
        asked += request.headers[HttpHeaders.Authorization]
        val because = (reason?.let { ""","reason":"$it"""" } ?: "") + (detail?.let { ""","detail":"$it"""" } ?: "")
        respond(
            """{"error":"This account may not use the meal planner yet. Ask the person who runs this server."$because}""",
            HttpStatusCode.Forbidden,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    @Test
    fun aLoginTheServerRefusesEndsTheSessionSayingWhy() = runTest {
        for ((reason, why) in listOf(LoginRefusal.NOT_IN_GROUP to LoggedOut.NOT_ALLOWED, LoginRefusal.NO_GROUPS to LoggedOut.NO_GROUPS)) {
            val storage = MemorySettingsStorage().apply { saveSession(Session("ann", "good", Long.MAX_VALUE)) }
            val forms = mutableListOf<Map<String, String>>()
            val logins = exchange(storage, fakeProvider(forms))
            var ended = false
            val api = ApiClient(server, storage.session(), refusing(reason), loginRefused = { token, r, detail -> ended = logins.loginRefused(token, r, detail) }) {
                logins.renew(server.url, it)
            }

            val failed = load { api.me() } as Load.Failed
            assertEquals(403, failed.status, reason)
            assertTrue(ended, reason)
            assertEquals(null, storage.session(), reason)
            assertEquals(why, storage.loggedOut(), reason)
            assertTrue(forms.isEmpty(), "$reason: a 403 is not renewed, it is the account")
            assertFalse(logins.claimAutoOpen(serverKnown = true), "$reason: the login screen says why, under no login page")
            api.close()
        }
    }

    @Test
    fun aLoginWhoseRequestForAnAccountWaitsEndsTheSessionSayingSo() = runTest {
        // Issue 314: the server refuses a waiting person as anyone outside the group, and says why in the detail.
        val storage = MemorySettingsStorage().apply { saveSession(Session("zoe", "good", Long.MAX_VALUE)) }
        val logins = exchange(storage, fakeProvider(mutableListOf()))
        val api = ApiClient(
            server, storage.session(), refusing(LoginRefusal.NOT_IN_GROUP, detail = LoginRefusal.WAITING_FOR_APPROVAL), loginRefused = logins::loginRefused,
        ) { logins.renew(server.url, it) }

        assertEquals(403, (load { api.me() } as Load.Failed).status)
        assertEquals(null, storage.session())
        assertEquals(LoggedOut.WAITING_FOR_APPROVAL, storage.loggedOut())
        assertFalse(logins.claimAutoOpen(serverKnown = true), "the login screen says it waits, under no login page")
        api.close()

        // A detail this app does not know is the plain refusal.
        storage.saveSession(Session("max", "good", Long.MAX_VALUE))
        val other = ApiClient(server, storage.session(), refusing(LoginRefusal.NOT_IN_GROUP, detail = "something_new"), loginRefused = logins::loginRefused)
        load { other.me() }
        assertEquals(LoggedOut.NOT_ALLOWED, storage.loggedOut())
        other.close()
    }

    @Test
    fun aRefusalOfAnotherSessionOrAnotherForbiddenAnswerEndsNothing() = runTest {
        // Logged in again meanwhile, perhaps as someone else: the answer for the old token leaves the new session alone.
        val storage = MemorySettingsStorage().apply { saveSession(Session("bob", "his", Long.MAX_VALUE)) }
        val logins = exchange(storage, fakeProvider(mutableListOf()))
        val old = ApiClient(server, Session("ann", "hers", Long.MAX_VALUE), refusing(LoginRefusal.NOT_IN_GROUP), loginRefused = logins::loginRefused)
        assertEquals(403, (load { old.me() } as Load.Failed).status)
        assertEquals("bob", storage.session()?.accessToken)
        assertEquals(null, storage.loggedOut())

        // A 403 that is about what was asked, not about the login, is shown where it was asked.
        for (reason in listOf(null, "something_else")) {
            val api = ApiClient(server, storage.session(), refusing(reason), loginRefused = logins::loginRefused)
            assertEquals("This account may not use the meal planner yet. Ask the person who runs this server.", (load { api.me() } as Load.Failed).message)
            assertEquals("bob", storage.session()?.accessToken, "$reason")
            api.close()
        }
        old.close()
    }

    @Test
    fun theBackgroundCheckEndsNoSessionTheServerRefuses() = runTest {
        // Nobody is there to read why: the screens find out at their next request, and end it then.
        val storage = MemorySettingsStorage().apply { saveSession(Session("ann", "good", Long.MAX_VALUE)) }
        val asked = mutableListOf<String?>()
        val logins = exchange(storage, fakeProvider(mutableListOf()))
        assertEquals(null, askWhatIsDue(storage, logins, server, refusing(LoginRefusal.NOT_IN_GROUP, asked)))
        assertEquals(listOf<String?>("Bearer ann"), asked)
        assertEquals("ann", storage.session()?.accessToken)
    }
}
