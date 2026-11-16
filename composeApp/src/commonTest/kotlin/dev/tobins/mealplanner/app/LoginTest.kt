package dev.tobins.mealplanner.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
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

    /** A server and token endpoint that answer like the meal planner and Authelia. */
    private fun fakeProvider(forms: MutableList<Map<String, String>>, refuseRefresh: Boolean = false) = HttpClient(
        MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.encodedPath) {
                "/api/auth/config" -> respond(
                    """{"issuer":"https://auth.example.org","clientId":"mealplanner","authorizationEndpoint":"https://auth.example.org/api/oidc/authorization","tokenEndpoint":"https://auth.example.org/api/oidc/token","scopes":["openid","offline_access"]}""",
                    headers = json,
                )
                "/api/oidc/token" -> {
                    val form = (request.body as FormDataContent).formData
                    forms += form.names().associateWith { form[it]!! }
                    if (refuseRefresh && form["grant_type"] == "refresh_token") respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, json)
                    else respond("""{"access_token":"access-${forms.size}","refresh_token":"refresh-${forms.size}","expires_in":3600,"token_type":"bearer"}""", headers = json)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        },
    ) { install(ContentNegotiation) { json() } }

    @Test
    fun logsInWithPkceAndRefreshes() = runTest {
        val storage = MemorySettingsStorage()
        val forms = mutableListOf<Map<String, String>>()
        val login = Login(storage, fakeProvider(forms))

        val page = Url(login.start("https://meals.example.nl", "https://meals.example.nl/app/login"))
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
        Url(login.start("https://meals.example.nl", "https://meals.example.nl/app/login")).parameters["state"]!!

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
        assertEquals(Strings.current(Msg.LOGIN_ANSWER_MISMATCH), refused.exceptionOrNull()?.message)
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
        assertEquals(Strings.current(Msg.LOGIN_ANSWER_MISMATCH), foreign.exceptionOrNull()?.message)

        val refusal = "https://meals.example.nl/app/login?error=access_denied&error_description=The+user+said+no&state=$state"
        assertEquals("The user said no", deliver(login, refusal)!!.exceptionOrNull()?.message)
        assertEquals(null, deliver(login, refusal), "the refusal ended the login; again it is let pass")
        assertEquals(null, storage.session())
        assertTrue(forms.isEmpty())
    }
}
