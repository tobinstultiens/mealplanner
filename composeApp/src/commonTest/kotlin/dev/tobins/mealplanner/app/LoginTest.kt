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
}
