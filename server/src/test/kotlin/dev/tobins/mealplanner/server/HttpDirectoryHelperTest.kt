package dev.tobins.mealplanner.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.tobins.mealplanner.api.RegistrationRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The server's side of the helper's protocol (`scripts/registration-helper.py`, whose own test checks the other side). */
class HttpDirectoryHelperTest {
    private val sent = mutableListOf<HttpRequestData>()

    private fun helper(answer: (HttpRequestData) -> Pair<HttpStatusCode, String>) = HttpDirectoryHelper(
        "http://helper:8091/",
        "the-shared-secret",
        HttpClient(MockEngine { request ->
            sent += request
            val (status, body) = answer(request)
            respond(body, status)
        }),
    )

    private fun HttpRequestData.json() = Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()) as JsonObject

    @Test
    fun eachOperationIsAPostWithTheSecretAndOnlyItsFields() = runBlocking {
        val helper = helper { HttpStatusCode.OK to "{}" }
        assertEquals(HelperAnswer.DONE, helper.register(RegistrationRequest("anna", "Anna", "anna@example.org", "a long password")))
        assertEquals(HelperAnswer.DONE, helper.approve("anna"))
        assertEquals(HelperAnswer.DONE, helper.reject("anna"))
        assertEquals(listOf("/register", "/approve", "/reject"), sent.map { it.url.encodedPath })
        assertEquals(List(3) { "Bearer the-shared-secret" }, sent.map { it.headers["Authorization"] })
        assertEquals(setOf("username", "displayName", "email", "password"), sent[0].json().keys)
        assertEquals("a long password", sent[0].json()["password"].toString().trim('"'))
        assertEquals(setOf("username"), sent[1].json().keys)
    }

    @Test
    fun theHelpersAnswers() = runBlocking {
        val cases = listOf(
            HttpStatusCode.Conflict to """{"error": "taken"}""" to HelperAnswer.TAKEN,
            HttpStatusCode.Conflict to """{"error": "in_group"}""" to HelperAnswer.IN_GROUP,
            HttpStatusCode.NotFound to """{"error": "unknown"}""" to HelperAnswer.UNKNOWN,
            HttpStatusCode.NotFound to "not the helper" to HelperAnswer.FAILED,
            HttpStatusCode.Unauthorized to """{"error": "unauthorised"}""" to HelperAnswer.FAILED,
            HttpStatusCode.BadRequest to """{"error": "invalid"}""" to HelperAnswer.FAILED,
            HttpStatusCode.BadGateway to """{"error": "directory"}""" to HelperAnswer.FAILED,
            HttpStatusCode.InternalServerError to """{"error": "unsure"}""" to HelperAnswer.UNSURE,
            HttpStatusCode.GatewayTimeout to "" to HelperAnswer.UNSURE,
        )
        for ((answer, expected) in cases) assertEquals(expected, helper { answer }.approve("anna"), answer.toString())
    }

    @Test
    fun notReachingItIsNothingDoneButNoAnswerInTimeIsNotKnown() = runBlocking {
        assertEquals(HelperAnswer.FAILED, helper { throw ConnectException("Connection refused") }.reject("anna"))
        assertEquals(HelperAnswer.UNSURE, helper { throw HttpRequestTimeoutException("http://helper:8091/register", 45_000) }.reject("anna"))
    }

    @Test
    fun nothingItSendsIsLogged() = runBlocking {
        val log = LoggerFactory.getLogger(HttpDirectoryHelper::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        log.addAppender(appender)
        try {
            for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.InternalServerError)) {
                helper { status to "{}" }.register(RegistrationRequest("anna", "Anna", "anna@example.org", "a long password"))
            }
            helper { throw HttpRequestTimeoutException("http://helper:8091/register", 45_000) }.register(RegistrationRequest("anna", "Anna", "anna@example.org", "a long password"))
        } finally {
            log.detachAppender(appender)
        }
        val logged = appender.list.joinToString("\n") { it.formattedMessage }
        assertEquals(4, appender.list.size, logged)
        for (secret in listOf("a long password", "anna@example.org", "the-shared-secret")) assertFalse(secret in logged, logged)
    }
}
