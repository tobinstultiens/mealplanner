package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.AccountRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Asking for an account (issue 314): what the form checks, and what it says about each answer the server can give. */
class RegistrationTest {
    private val en = Strings.English
    private val good = RegistrationDraft("Ann Smith", "Ann.Smith", " ann@example.org ", "a good long password", "a good long password")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun theFormChecksEachFieldWithTheServersRules() {
        assertEquals(emptyMap(), registrationProblems(good, en), "the user name is taken in lower case, the address trimmed")
        assertEquals(
            listOf(FormField.DISPLAY_NAME, FormField.USERNAME, FormField.EMAIL, FormField.PASSWORD),
            registrationProblems(RegistrationDraft(), en).keys.toList(),
            "an empty form: every field but the second password, which is the same as the first",
        )
        assertEquals(
            mapOf(FormField.PASSWORD_AGAIN to "The two passwords differ."),
            registrationProblems(good.copy(passwordAgain = "a good long passwort"), en),
        )
        assertEquals(setOf(FormField.USERNAME), registrationProblems(good.copy(username = "ann smith"), en).keys)
        assertEquals(setOf(FormField.EMAIL), registrationProblems(good.copy(email = "ann@exämple.org"), en).keys, "printable ASCII only, as the server")
        assertEquals(
            setOf(FormField.PASSWORD, FormField.PASSWORD_AGAIN),
            registrationProblems(good.copy(password = "too short"), en).keys,
        )
        assertEquals(setOf(FormField.PASSWORD), registrationProblems(good.copy(password = "ann.smith", passwordAgain = "ann.smith"), en).keys, "not the user name")
    }

    @Test
    fun eachProblemSaysWhatTheFieldMustHoldInBothLanguages() {
        val wrong = registrationProblems(RegistrationDraft(password = "short", passwordAgain = "shorter"), en)
        assertEquals("Fill in your name, at most 64 characters.", wrong[FormField.DISPLAY_NAME])
        assertEquals("3 to 32 characters: letters a to z and digits, and . - _ after the first.", wrong[FormField.USERNAME])
        assertEquals("Fill in an e-mail address such as name@example.org, without spaces or accented letters.", wrong[FormField.EMAIL])
        assertEquals("10 to 128 characters, and not your user name.", wrong[FormField.PASSWORD])
        assertEquals("De twee wachtwoorden verschillen.", registrationProblems(RegistrationDraft(password = "a", passwordAgain = "b"), Strings.Dutch)[FormField.PASSWORD_AGAIN])
        assertEquals("10 tot 128 tekens, en niet je gebruikersnaam.", problemOf(FormField.PASSWORD, Strings.Dutch))
    }

    @Test
    fun aDraftPrintsWithoutItsPasswords() {
        val printed = good.toString()
        assertFalse("password" in printed, printed)
        assertTrue("Ann.Smith" in printed)
        assertFalse("password" in good.request().toString(), "nor does the request")
    }

    /** A client of a server that answers every call [status] with [body] and [headers]; [sent] gets each request. */
    private fun answering(status: HttpStatusCode, body: String, sent: MutableList<String> = mutableListOf(), headers: Map<String, String> = emptyMap()) = ApiClient(
        ServerSettings("http://localhost"),
        engine = MockEngine { request ->
            sent += "${request.method.value} ${request.url.encodedPath} ${(request.body as? TextContent)?.text.orEmpty()}".trim()
            respond(body, status, headersOf(*(headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))
        },
    )

    private suspend fun outcome(api: ApiClient, strings: Strings = en): RegistrationOutcome =
        registrationOutcome(strings) { api.register(good.request()) }.also { api.close() }

    @Test
    fun aRequestTakenSaysTheAccountWaitsUnderItsUserName() = runTest {
        val sent = mutableListOf<String>()
        val api = answering(HttpStatusCode.Created, """{"username":"ann.smith"}""", sent)
        assertEquals(RegistrationOutcome.Sent("ann.smith"), outcome(api))
        assertEquals(
            listOf("""POST /api/registration {"username":"Ann.Smith","displayName":"Ann Smith","email":" ann@example.org ","password":"a good long password"}"""),
            sent,
            "the fields as typed, which the server normalises as the form checked them",
        )
    }

    /** The server's refusal with [status], [reason] and the optional [field], as `Errors.kt` writes it. */
    private fun refusal(status: Int, reason: String, field: String? = null, retryAfter: String? = null) = answering(
        HttpStatusCode.fromValue(status),
        """{"error":"The server's own sentence.","reason":"$reason"${field?.let { ""","field":"$it"""" } ?: ""}}""",
        headers = retryAfter?.let { mapOf(HttpHeaders.RetryAfter to it) }.orEmpty(),
    )

    @Test
    fun eachRefusalIsSaidInTheAppsWordsByItsReason() = runTest {
        assertEquals(RegistrationOutcome.Refused("This server takes no requests for an account now."), outcome(refusal(404, "registration_off")))
        assertEquals(
            RegistrationOutcome.Refused("Some fields are not right yet: see the marked ones.", FormField.EMAIL),
            outcome(refusal(400, "invalid", field = "email")),
        )
        assertEquals(
            RegistrationOutcome.Refused("The server did not accept these details. Check every field and try again."),
            outcome(refusal(400, "invalid")),
            "the directory's own check refused it, without naming a field",
        )
        assertEquals(RegistrationOutcome.Refused("This user name or e-mail address cannot be used. Choose another."), outcome(refusal(409, "unavailable")))
        assertEquals(
            RegistrationOutcome.Refused("Too many requests for an account just now. Try again in 3 minutes."),
            outcome(refusal(429, "too_many", retryAfter = "125")),
            "Retry-After in seconds, rounded up to minutes",
        )
        assertEquals(RegistrationOutcome.Refused("Too many requests for an account just now. Try again in 1 minute."), outcome(refusal(429, "too_many", retryAfter = "1")))
        assertEquals(RegistrationOutcome.Refused("Too many requests for an account just now. Try again later."), outcome(refusal(429, "too_many")))
        assertEquals(RegistrationOutcome.Refused("Many requests are waiting for approval already. Try again in a few days."), outcome(refusal(503, "full")))
        assertEquals(
            RegistrationOutcome.Refused("The login server's accounts cannot be reached just now; nothing was made. Try again later."),
            outcome(refusal(503, "directory_down")),
        )
        assertEquals(
            RegistrationOutcome.Refused("Te veel aanvragen voor een account op dit moment. Probeer het over 2 minuten opnieuw."),
            outcome(refusal(429, "too_many", retryAfter = "61"), Strings.Dutch),
        )
    }

    @Test
    fun anAnswerTheFormDoesNotKnowIsSaidAsAnyFailedRequest() = runTest {
        // A server from before registration: no such route.
        assertEquals(RegistrationOutcome.Refused("This server takes no requests for an account now."), outcome(answering(HttpStatusCode.NotFound, "")))
        // A reason from a newer server: its own sentence.
        assertEquals(RegistrationOutcome.Refused("The server's own sentence."), outcome(refusal(400, "something_new")))
        val down = ApiClient(ServerSettings("http://localhost"), engine = MockEngine { throw unreachableServer() })
        assertEquals(RegistrationOutcome.Refused("The server could not be reached."), outcome(down))
    }

    @Test
    fun theFormIsOfferedOnlyWhenTheServerSaysItTakesRequests() = runTest {
        assertTrue(registrationOpen(answering(HttpStatusCode.OK, """{"open":true}""")))
        assertFalse(registrationOpen(answering(HttpStatusCode.OK, """{"open":false}""")))
        assertFalse(registrationOpen(answering(HttpStatusCode.NotFound, """{"error":"Not found"}""")), "a server from before registration")
        assertFalse(registrationOpen(answering(HttpStatusCode.OK, "<html></html>")), "an answer that is not the status")
        assertFalse(registrationOpen(ApiClient(ServerSettings("http://localhost"), engine = MockEngine { throw unreachableServer() })))
    }

    @Test
    fun theOwnersDecisionsGoToTheirRoutesAndAFailureSaysWhatBecameOfTheRequest() = runTest {
        val sent = mutableListOf<String>()
        val waiting = """[{"username":"zoe","displayName":"Zoe","email":"zoe@example.org","requestedAt":"2026-11-20T10:00:00Z","expiresAt":"2026-12-04T10:00:00Z"}]"""
        val api = answering(HttpStatusCode.OK, waiting, sent)
        val decisions = AccountDecisions(api, MemorySettingsStorage())
        assertEquals(listOf("zoe"), decisions.waiting().map(AccountRequest::username))
        decisions.approve("zoe")
        decisions.reject("max")
        assertEquals(listOf("GET /api/registrations", "POST /api/registrations/zoe/approve", "POST /api/registrations/max/reject"), sent)
        api.close()

        fun failed(status: Int) = Load.Failed("The server's own sentence.", status)
        assertEquals("This request no longer waits; the list is up to date again." to true, decisionFailure(failed(404), en))
        assertEquals("This request was decided at the login server already, so it is gone from the list." to true, decisionFailure(failed(409), en))
        assertEquals("The login server's accounts cannot be reached just now; the request stays. Try again later." to false, decisionFailure(failed(503), en))
        assertEquals("Could not decide: The server's own sentence." to false, decisionFailure(failed(500), en))
    }
}
