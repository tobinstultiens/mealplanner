package dev.tobins.mealplanner.server.accounts

import dev.tobins.mealplanner.api.RegistrationRequest
import dev.tobins.mealplanner.server.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/** What the registration helper answered, as far as the server acts on it. */
enum class HelperAnswer {
    /** Done as asked. */
    DONE,

    /** Register: the user name or e-mail address is in use in the directory. */
    TAKEN,

    /** Approve, reject: not a user the helper made that still waits; gone from the directory, or never its own. */
    UNKNOWN,

    /** Approve, reject: the user is in a group already, put there in the directory by hand. */
    IN_GROUP,

    /**
     * The helper's own check of the fields refused them, and did nothing. Its rules are the server's
     * (`scripts/registration-rules-cases.json` keeps them alike), so this means they have drifted apart.
     */
    INVALID,

    /** Nothing was done: the helper or the directory could not be reached, or refused the call. */
    FAILED,

    /** Not known what was done: no answer in time, or the helper said it is not sure. */
    UNSURE,
}

/**
 * The registration helper next to the directory (`scripts/registration-helper.py`, issue 314). It holds the directory's
 * admin credential, so the server does not; the server can only ask it these three things, and it does them only to
 * users it made that are still in no group, in the group of its own configuration.
 */
interface DirectoryHelper {
    /** Makes the account in the directory, in no group, with the request's password. */
    suspend fun register(request: RegistrationRequest): HelperAnswer

    /** Puts the user in the meal planner's group. */
    suspend fun approve(username: String): HelperAnswer

    /** Deletes the user from the directory. */
    suspend fun reject(username: String): HelperAnswer
}

/**
 * The helper at [url], called with the shared [secret]. Logs no body, sent or answered: a request for an account holds
 * the password. Only the operation and the status or the kind of failure.
 */
class HttpDirectoryHelper(
    private val url: String,
    private val secret: String,
    private val http: HttpClient = HttpClient(OkHttp) {
        // The helper logs in to the directory and makes a few calls there, each with a time limit of its own.
        install(HttpTimeout) { requestTimeoutMillis = 45_000 }
    },
) : DirectoryHelper {
    private val log = LoggerFactory.getLogger(HttpDirectoryHelper::class.java)

    override suspend fun register(request: RegistrationRequest) = call("register", Json.encodeToString(request))

    override suspend fun approve(username: String) = call("approve", nameBody(username))

    override suspend fun reject(username: String) = call("reject", nameBody(username))

    private fun nameBody(username: String) = JsonObject(mapOf("username" to JsonPrimitive(username))).toString()

    private suspend fun call(operation: String, body: String): HelperAnswer {
        val response = try {
            http.post(url.trimEnd('/') + "/" + operation) {
                bearerAuth(secret)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not connected at all is nothing done; anything later (a time-out while it worked) may have done it.
            val reachedIt = e !is ConnectException && e !is UnknownHostException && e !is ConnectTimeoutException
            log.warn("The registration helper did not answer $operation: ${e::class.simpleName}")
            return if (reachedIt) HelperAnswer.UNSURE else HelperAnswer.FAILED
        }
        val error = runCatching { Json.parseToJsonElement(response.bodyAsText()).let { (it as JsonObject)["error"]?.jsonPrimitive?.content } }.getOrNull()
        return answerOf(response.status, error).also {
            if (it in setOf(HelperAnswer.FAILED, HelperAnswer.UNSURE, HelperAnswer.INVALID)) log.warn("The registration helper answered $operation with ${response.status.value} ${error.orEmpty()}")
            if (response.status == HttpStatusCode.Unauthorized) log.error("The registration helper refused the server's secret: MEALPLANNER_REGISTRATION_HELPER_SECRET is not its HELPER_SECRET")
        }
    }

    companion object {
        /**
         * The helper at `MEALPLANNER_REGISTRATION_HELPER_URL` with `MEALPLANNER_REGISTRATION_HELPER_SECRET`; null without
         * both. Read here and not into [ServerConfig], whose text would show the secret.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): HttpDirectoryHelper? {
            val url = env["MEALPLANNER_REGISTRATION_HELPER_URL"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val secret = env["MEALPLANNER_REGISTRATION_HELPER_SECRET"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return HttpDirectoryHelper(url, secret)
        }
    }

    /** The helper's answers, as its script documents them; anything else is a failure, or unsure when it may have acted. */
    private fun answerOf(status: HttpStatusCode, error: String?): HelperAnswer = when {
        status.isSuccess() -> HelperAnswer.DONE
        status == HttpStatusCode.Conflict && error == "taken" -> HelperAnswer.TAKEN
        status == HttpStatusCode.Conflict && error == "in_group" -> HelperAnswer.IN_GROUP
        status == HttpStatusCode.NotFound && error == "unknown" -> HelperAnswer.UNKNOWN
        status == HttpStatusCode.BadRequest && error == "invalid" -> HelperAnswer.INVALID
        status.value in 400..499 || status == HttpStatusCode.BadGateway -> HelperAnswer.FAILED
        else -> HelperAnswer.UNSURE
    }
}
