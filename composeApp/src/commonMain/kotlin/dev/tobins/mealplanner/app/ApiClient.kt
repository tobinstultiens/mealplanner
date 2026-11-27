package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.LoginRefusal
import dev.tobins.mealplanner.app.accounts.Session
import dev.tobins.mealplanner.app.ui.failedFrom
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A 403 the server answered to the login itself, not to what was asked: the access token it was sent with, and why. */
private class LoginRefused(val token: String?, val reason: String, val detail: String?)

/**
 * The [LoginRefused] of [cause]; null for any other failure. The reason is in the body, as
 * `{"error": "...", "reason": "not_in_group"}`, with an optional `detail` ([LoginRefusal.WAITING_FOR_APPROVAL]).
 */
private suspend fun loginRefusal(cause: Throwable): LoginRefused? {
    if (cause !is ClientRequestException || cause.response.status != HttpStatusCode.Forbidden) return null
    val failed = failedFrom(cause)
    val reason = failed.reason?.takeIf { it == LoginRefusal.NOT_IN_GROUP || it == LoginRefusal.NO_GROUPS } ?: return null
    return LoginRefused(cause.response.call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer "), reason, failed.detail)
}

/** Where the server is. */
data class ServerSettings(val url: String)

/**
 * Ktor's default JSON settings, but tolerant of what this app version does not know, so the server
 * can grow a response without breaking installed apps: a field it does not know is skipped
 * (`ignoreUnknownKeys`), and an enum value it does not know becomes the field's default
 * (`coerceInputValues`). A field of an enum type without a default still fails on an unknown
 * value, and so does an enum inside a list or a map, which coercion does not reach: enum fields
 * added from now on need a default (or are nullable, `= null`), and a new value of an enum that
 * is already in use waits for a release (README, "API compatibility").
 */
private val lenientJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    isLenient = true
    allowSpecialFloatingPointValues = true
    allowStructuredMapKeys = true
    prettyPrint = false
    useArrayPolymorphism = false
}

/**
 * Talks to the meal planner server as the logged-in account. Calls throw on network errors (an
 * IOException on every platform) and non-2xx responses. An expired access token is renewed with
 * [refresh], which is told the access token the server refused, and the call retried. A login the
 * server refuses as such ([LoginRefusal], a 403) is told to [loginRefused], with the access token it
 * was refused for, the reason and its detail, if any; the call fails all the same.
 */
class ApiClient(
    settings: ServerSettings,
    session: Session? = null,
    engine: HttpClientEngine? = null,
    loginRefused: suspend (refused: String?, reason: String, detail: String?) -> Unit = { _, _, _ -> },
    refresh: suspend (refused: String?) -> Session? = { null },
) {
    private val setUp: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = true
        HttpResponseValidator {
            handleResponseExceptionWithRequest { cause, _ ->
                // A request that cannot reach the server fails as an IOException on every platform, the
                // lost connection the screens catch; the login's renewal fails through here too (connectionFailure).
                connectionFailure(cause)?.let { throw it }
                // Told with the token the request was sent with, which a renewal meanwhile may have replaced.
                loginRefusal(cause)?.let { loginRefused(it.token, it.reason, it.detail) }
            }
        }
        install(ContentNegotiation) { json(lenientJson) }
        install(HttpTimeout)
        if (session != null) {
            install(Auth) {
                bearer {
                    loadTokens { BearerTokens(session.accessToken, session.refreshToken) }
                    refreshTokens { refresh(oldTokens?.accessToken)?.let { BearerTokens(it.accessToken, it.refreshToken) } }
                    sendWithoutRequest { true }
                }
            }
        }
        defaultRequest { url(settings.url.trimEnd('/') + "/") }
    }

    /** What every call of a feature's package (`PlanCalls.kt`, ...) is made with; they are extensions of this class. */
    internal val http = if (engine == null) HttpClient(setUp) else HttpClient(engine, setUp)

    /**
     * What [request] answers, or null when the server has no such route: a server from before it
     * answers 404 without an error, as Ktor does for any path it does not know, where the route's own
     * 404 says what was not found. The caller then does what it did before the route.
     */
    internal suspend fun <T : Any> unlessUnknown(request: suspend () -> T): T? = try {
        request()
    } catch (e: ClientRequestException) {
        if (e.response.status != HttpStatusCode.NotFound || saysWhy(e.response.bodyAsText())) throw e
        null
    }

    /** Whether [body] is the server's own explanation of an error, `{"error": "..."}`. */
    private fun saysWhy(body: String): Boolean =
        runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.containsKey("error") == true }.getOrDefault(false)

    fun close() = http.close()
}
