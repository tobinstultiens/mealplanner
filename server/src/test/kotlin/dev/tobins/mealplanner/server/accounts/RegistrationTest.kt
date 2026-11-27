package dev.tobins.mealplanner.server.accounts

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import dev.tobins.mealplanner.api.AccountRequest
import dev.tobins.mealplanner.api.LoginRefusal
import dev.tobins.mealplanner.api.Me
import dev.tobins.mealplanner.api.NotificationFacts
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.RegistrationReceipt
import dev.tobins.mealplanner.api.RegistrationRefusal
import dev.tobins.mealplanner.api.RegistrationRequest
import dev.tobins.mealplanner.api.RegistrationStatus
import dev.tobins.mealplanner.server.ServerConfig
import dev.tobins.mealplanner.server.TestTokens
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.module
import dev.tobins.mealplanner.server.openDatabase
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Requests for an account (issue 314) through the API: off unless switched on, made in the directory through the helper
 * ([FakeDirectory]), refused as the limits say, waiting until the owner decides, and the password nowhere on the server.
 */
class RegistrationTest {
    private val directory = FakeDirectory()
    private lateinit var db: Database

    /** Each request from an address of its own unless a test says otherwise, so that only the limit a test is about applies. */
    private var lastAddress = 0

    private fun ApplicationTestBuilder.start(
        registration: Boolean = true,
        group: String? = "mealplanner",
        owner: String? = null,
        helper: DirectoryHelper? = directory,
        verifier: TokenVerifier = TestTokens,
        databaseUrl: String = "jdbc:sqlite::memory:",
    ): HttpClient {
        val config = ServerConfig(port = 0, databaseUrl = databaseUrl, cookingRepo = null, oidcGroup = group, registration = registration, owner = owner)
        db = openDatabase(databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = verifier, directory = helper) }
        return createClient { install(ContentNegotiation) { json() } }
    }

    private suspend fun HttpClient.register(
        username: String,
        email: String = "$username@example.org",
        password: String = PASSWORD,
        address: String = "203.0.113.${++lastAddress}",
    ): HttpResponse = post("/api/registration") {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.XForwardedFor, address)
        setBody(RegistrationRequest(username, username.replaceFirstChar(Char::uppercase), email, password))
    }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()) as JsonObject

    private suspend fun HttpResponse.reason() = json()["reason"]?.jsonPrimitive?.content

    private fun waitingNames() = db.registrationQueries.selectRequests().executeAsList().map { it.username }

    @Test
    fun registrationIsOffUnlessSwitchedOnAndSetUp() {
        for ((registration, group, helper) in listOf(Triple(false, "mealplanner", directory), Triple(true, null, directory), Triple(true, "mealplanner", null))) {
            testApplication {
                val http = start(registration, group, helper = helper)
                assertEquals(RegistrationStatus(open = false), http.get("/api/registration").body<RegistrationStatus>(), "$registration $group $helper")
                val refused = http.register("anna")
                assertEquals(HttpStatusCode.NotFound, refused.status)
                assertEquals(RegistrationRefusal.OFF, refused.reason())
            }
        }
        assertEquals(emptyList(), directory.calls, "nothing reached the directory")
        assertFalse(ServerConfig.fromEnv(emptyMap()).registration, "off by default")
        assertTrue(ServerConfig.fromEnv(mapOf("MEALPLANNER_REGISTRATION" to " On ")).registration)
        assertNull(HttpDirectoryHelper.fromEnv(mapOf("MEALPLANNER_REGISTRATION_HELPER_URL" to "http://helper:8091")), "no helper without its secret")
        assertNotNull(HttpDirectoryHelper.fromEnv(mapOf("MEALPLANNER_REGISTRATION_HELPER_URL" to "http://helper:8091", "MEALPLANNER_REGISTRATION_HELPER_SECRET" to "s")))
    }

    @Test
    fun aRequestMakesTheAccountInNoGroupAndWaitsForTheOwner() = testApplication {
        val http = start()
        assertEquals(RegistrationStatus(open = true), http.get("/api/registration").body<RegistrationStatus>())

        val answer = http.post("/api/registration") {
            contentType(ContentType.Application.Json)
            setBody(RegistrationRequest("  Anna ", " Anna de Vries ", " anna@example.org ", PASSWORD))
        }
        assertEquals(HttpStatusCode.Created, answer.status)
        assertEquals(RegistrationReceipt("anna"), answer.body<RegistrationReceipt>(), "the user name in lower case, as the directory keeps it")
        assertEquals(emptyList<String>(), directory.users["anna"]?.toList(), "in the directory, in no group")
        assertEquals(PASSWORD, directory.passwords["anna"], "with the password chosen")
        val row = db.registrationQueries.selectRequest("anna").executeAsOne()
        assertEquals(listOf("Anna de Vries", "anna@example.org"), listOf(row.displayName, row.email))
    }

    @Test
    fun aRefusedFieldIsNamed() = testApplication {
        val http = start()
        val refused = http.register("anna", email = "not an address")
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(RegistrationRefusal.INVALID to "email", refused.reason() to refused.json()["field"]?.jsonPrimitive?.content)
        for (body in listOf("{", "[]", """{"username": "anna"}""", """{"username": "a", "displayName": "A", "email": "a@b.nl", "password": "p", "groups": ["x"]}""")) {
            val answer = http.post("/api/registration") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest to RegistrationRefusal.INVALID, answer.status to answer.reason(), body)
        }
        assertEquals(emptyList(), directory.calls)
    }

    @Test
    fun aUserNameInUseIsRefusedWithOneAnswerWhateverUsesIt() = testApplication {
        val http = start()
        // Bob has a meal planner account: a new person under his name would log in to his data.
        http.get("/api/me") { bearerAuth("bob~mealplanner") }
        directory.users["carl"] = mutableListOf("family")
        http.register("dora")
        val answers = listOf(http.register("BOB"), http.register("carl"), http.register("dora"))
        assertEquals(List(3) { HttpStatusCode.Conflict }, answers.map { it.status })
        assertEquals(1, answers.map { it.bodyAsText() }.distinct().size, "one answer, so the form tells nobody which")
        assertEquals(RegistrationRefusal.UNAVAILABLE, answers.first().reason())
        assertEquals(listOf("register dora", "register carl"), directory.calls, "the account's name and the waiting one never reach the directory")
        assertEquals(listOf("dora"), waitingNames())
        assertEquals(listOf("family"), directory.users["carl"]?.toList(), "carl as he was")
    }

    @Test
    fun thePasswordIsInNoTableLogOrErrorReport() {
        val file = File(createTempDirectory("registration").toFile(), "mealplanner.db")
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val log = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(log)
        val answers = mutableListOf<String>()
        try {
            testApplication {
                val http = start(databaseUrl = "jdbc:sqlite:${file.path}")
                suspend fun keep(response: HttpResponse) = answers.add(response.bodyAsText())
                http.get("/api/me") { bearerAuth("boss~mealplanner") }
                keep(http.register("anna"))
                keep(http.register("anna", email = "other@example.org"))
                keep(http.register("bob", email = "no address"))
                keep(http.post("/api/registration") { contentType(ContentType.Application.Json); setBody("""{"username": "carl", "password": "$PASSWORD"""") })
                keep(http.post("/api/registration") { contentType(ContentType.Application.Json); setBody("""{"username": "carl", "password": "$PASSWORD", "x": 1}""") })
                directory.failing = HelperAnswer.FAILED
                keep(http.register("dora"))
                directory.failing = HelperAnswer.UNSURE
                keep(http.register("emma"))
                directory.failing = null
                keep(http.post("/api/registrations/anna/approve") { bearerAuth("anna~mealplanner") })
                keep(http.post("/api/registrations/anna/approve") { bearerAuth("boss~mealplanner") })
            }
        } finally {
            root.detachAppender(log)
        }
        assertEquals(PASSWORD, directory.passwords["anna"], "the password reached the directory")
        val logged = log.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.let(ThrowableProxyUtil::asString) ?: "") }
        assertTrue("Account requested: anna" in logged, logged)
        assertFalse(PASSWORD in logged, "in the log:\n$logged")
        assertFalse(answers.any { PASSWORD in it }, "in an answer: $answers")
        // The database's file and its write-ahead log, as bytes: no table, index or free page holds it.
        val files = file.parentFile.listFiles()!!.filter { it.name.startsWith(file.name) }
        assertTrue(files.isNotEmpty())
        for (stored in files) assertFalse(PASSWORD.toByteArray() in stored.readBytes(), "in ${stored.name}")
    }

    @Test
    fun theLimitPerAddressCountsTheAddressTheProxySaw() = testApplication {
        val http = start()
        repeat(Registrations.PER_ADDRESS) { http.register("user$it", address = "198.51.100.7") }
        // What the client wrote before the proxy's address does not make it someone else.
        val refused = http.register("eve", address = "192.0.2.1, 198.51.100.7")
        assertEquals(HttpStatusCode.TooManyRequests to RegistrationRefusal.TOO_MANY, refused.status to refused.reason())
        val retryAfter = refused.headers[HttpHeaders.RetryAfter]?.toLong()
        assertTrue(retryAfter != null && retryAfter in 3500..3600, "try again within the hour: $retryAfter")
        assertEquals(HttpStatusCode.Created, http.register("eve", address = "198.51.100.8").status, "another address may")
        assertFalse("register eve" in directory.calls.dropLast(1), "refused before the directory")
    }

    @Test
    fun theCapOnWaitingRequests() = testApplication {
        val http = start()
        repeat(Registrations.MAX_WAITING) { assertEquals(HttpStatusCode.Created, http.register("user$it").status) }
        val refused = http.register("late")
        assertEquals(HttpStatusCode.ServiceUnavailable to RegistrationRefusal.FULL, refused.status to refused.reason())
        assertNull(directory.users["late"])
        // Once the owner decides on one, there is room again.
        http.get("/api/me") { bearerAuth("boss~mealplanner") }
        http.post("/api/registrations/user0/reject") { bearerAuth("boss~mealplanner") }
        assertEquals(HttpStatusCode.Created, http.register("late").status)
    }

    @Test
    fun theDirectoryDownLeavesNothing() = testApplication {
        val http = start()
        directory.failing = HelperAnswer.FAILED
        val refused = http.register("anna")
        assertEquals(HttpStatusCode.ServiceUnavailable to RegistrationRefusal.DIRECTORY_DOWN, refused.status to refused.reason())
        assertEquals(emptyList(), waitingNames())
        // Not sure whether it made the account: the name is rejected at once, which deletes it if it did.
        directory.failing = HelperAnswer.UNSURE
        http.register("bob")
        assertEquals(listOf("register anna", "register bob", "reject bob"), directory.calls)
        assertEquals(listOf("bob"), waitingNames(), "the reject was not sure either: kept for the owner and the expiry")
        directory.failing = null
        assertEquals(HttpStatusCode.Created, http.register("anna").status, "anna may try again")
    }

    /** Should the helper's rules and the server's drift apart (RegistrationRulesAgreementTest keeps them alike), the person hears what is wrong. */
    @Test
    fun aRequestTheHelperRefusesAsInvalidIsNotTheDirectoryBeingDown() = testApplication {
        val http = start()
        directory.failing = HelperAnswer.INVALID
        val refused = http.register("anna")
        assertEquals(HttpStatusCode.BadRequest to RegistrationRefusal.INVALID, refused.status to refused.reason())
        assertNull(refused.json()["field"], "the helper does not say which field")
        assertEquals(emptyList(), waitingNames())
        directory.failing = null
        assertEquals(HttpStatusCode.Created, http.register("anna").status, "the name is free again")
    }

    @Test
    fun beforeApprovalTheLoginIsRefusedAsWaitingAtEveryRenewal() = testApplication {
        val http = start(verifier = NamelessTokens)
        http.register("zoe")
        // Each token is one renewal: the groups come from the directory again, still none.
        for (token in listOf("zoe~", "zoe~family")) {
            val refused = http.get("/api/me") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            val body = refused.json()
            assertEquals("Your request is waiting for approval.", body["error"]?.jsonPrimitive?.content)
            assertEquals(LoginRefusal.NOT_IN_GROUP, body["reason"]?.jsonPrimitive?.content, "what installed apps end the session on")
            assertEquals(LoginRefusal.WAITING_FOR_APPROVAL, body["detail"]?.jsonPrimitive?.content)
        }
        // Someone else outside the group is told what they were before.
        val other = http.get("/api/me") { bearerAuth("eve~") }.json()
        assertNull(other["detail"])
        assertEquals(LoginRefusal.NOT_IN_GROUP, other["reason"]?.jsonPrimitive?.content)
        assertNull(db.userQueries.selectBySubject("sub-zoe").executeAsOneOrNull(), "no account before approval")
    }

    @Test
    fun theOwnerApprovesAndThePersonGetsAnEmptyAccountOfTheirOwn() = testApplication {
        val http = start()
        val owner = "boss~mealplanner"
        assertEquals(Me("boss", "Boss", approvesRequests = true), http.get("/api/me") { bearerAuth(owner) }.body<Me>())
        http.register("zoe")
        http.register("max")
        assertEquals(listOf("zoe", "max"), http.get("/api/notifications") { bearerAuth(owner) }.body<NotificationFacts>().accountRequests)
        val waiting = http.get("/api/registrations") { bearerAuth(owner) }.body<List<AccountRequest>>()
        assertEquals(listOf("zoe", "max"), waiting.map { it.username })
        val zoe = waiting.first()
        assertEquals(java.time.Instant.parse(zoe.requestedAt).plus(Registrations.EXPIRES_AFTER).toString(), zoe.expiresAt)

        val left = http.post("/api/registrations/zoe/approve") { bearerAuth(owner) }.body<List<AccountRequest>>()
        assertEquals(listOf("max"), left.map { it.username })
        assertEquals(listOf("mealplanner"), directory.users["zoe"]?.toList())
        // Her next token lists the group: she gets in, to an account of her own with nothing in it.
        assertEquals(Me("zoe", "Zoe"), http.get("/api/me") { bearerAuth("zoe~mealplanner") }.body<Me>())
        assertEquals(emptyList(), http.get("/api/recipes") { bearerAuth("zoe~mealplanner") }.body<List<RecipeSummary>>())

        assertEquals(emptyList(), http.post("/api/registrations/max/reject") { bearerAuth(owner) }.body<List<AccountRequest>>())
        assertNull(directory.users["max"], "rejected: gone from the directory")
        assertEquals(HttpStatusCode.NotFound, http.post("/api/registrations/max/approve") { bearerAuth(owner) }.status, "decided already")
        assertEquals(emptyList(), http.get("/api/notifications") { bearerAuth(owner) }.body<NotificationFacts>().accountRequests)
    }

    @Test
    fun aRequestDecidedInTheDirectoryByHandLeavesTheList() = testApplication {
        val http = start()
        val owner = "boss~mealplanner"
        http.get("/api/me") { bearerAuth(owner) }
        http.register("zoe")
        http.register("max")
        directory.users.getValue("zoe") += "mealplanner"
        val refused = http.post("/api/registrations/zoe/reject") { bearerAuth(owner) }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(listOf("mealplanner"), directory.users["zoe"]?.toList(), "the helper leaves an account in a group alone")
        assertEquals(listOf("max"), waitingNames())

        directory.failing = HelperAnswer.FAILED
        assertEquals(HttpStatusCode.ServiceUnavailable, http.post("/api/registrations/max/approve") { bearerAuth(owner) }.status)
        assertEquals(listOf("max"), waitingNames(), "kept, to try again")
    }

    @Test
    fun withTheOwnerNamedOnlyThatAccountDecides() = testApplication {
        val http = start(owner = "boss")
        // On a new database the first login is account 1; with MEALPLANNER_OWNER set, that is not enough.
        assertEquals(Me("anna", "Anna", approvesRequests = false), http.get("/api/me") { bearerAuth("anna~mealplanner") }.body<Me>())
        val refused = http.get("/api/registrations") { bearerAuth("anna~mealplanner") }
        assertEquals(HttpStatusCode.Forbidden to RegistrationRefusal.NOT_OWNER, refused.status to refused.reason())
    }

    @Test
    fun withoutTheHelperNobodyDecides() = testApplication {
        val http = start(registration = false, helper = null)
        assertEquals(Me("boss", "Boss", approvesRequests = false), http.get("/api/me") { bearerAuth("boss~mealplanner") }.body<Me>())
        assertEquals(HttpStatusCode.Forbidden, http.get("/api/registrations") { bearerAuth("boss~mealplanner") }.status)
    }

    @Test
    fun aRequestRacingAnotherOrADecisionWaitsForItsTurn() = testApplication {
        val http = start()
        val owner = "boss~mealplanner"
        http.get("/api/me") { bearerAuth(owner) }
        directory.takes = 200.milliseconds
        // Two people asking for one name at once: one gets it, the directory is asked once.
        val asked = coroutineScope { listOf(async { http.register("zoe") }, async { http.register("zoe", email = "other@example.org") }).awaitAll() }
        assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.Conflict), asked.map { it.status }.sortedBy { it.value })
        assertEquals(1, directory.calls.count { it == "register zoe" })
        // Approving and rejecting at once (two of the owner's devices): one decides, the other finds it decided.
        val decided = coroutineScope {
            listOf("approve", "reject").map { what -> async { http.post("/api/registrations/zoe/$what") { bearerAuth(owner) } } }.awaitAll()
        }
        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.NotFound), decided.map { it.status }.sortedBy { it.value })
        assertEquals(1, directory.calls.count { it == "approve zoe" || it == "reject zoe" })
    }

    companion object {
        const val PASSWORD = "Tr0ub4dor-and-3-horses"
    }
}

/** Authelia's access tokens as it makes them by default: no user name in them, only at the userinfo endpoint. */
private object NamelessTokens : TokenVerifier {
    override suspend fun verify(token: String) = TestTokens.verify(token)?.copy(username = null, displayName = null)

    override suspend fun userinfo(token: String) = TestTokens.verify(token)

    override suspend fun clientConfig() = TestTokens.clientConfig()
}

/** Whether [bytes] occur in this array. */
private operator fun ByteArray.contains(bytes: ByteArray): Boolean =
    (0..size - bytes.size).any { start -> bytes.indices.all { this[start + it] == bytes[it] } }
