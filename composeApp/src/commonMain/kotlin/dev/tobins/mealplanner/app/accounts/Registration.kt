package dev.tobins.mealplanner.app.accounts

import dev.tobins.mealplanner.accounts.RegistrationField
import dev.tobins.mealplanner.accounts.RegistrationRules
import dev.tobins.mealplanner.api.RegistrationReceipt
import dev.tobins.mealplanner.api.RegistrationRefusal
import dev.tobins.mealplanner.api.RegistrationRequest
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Plural
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.ui.failedFrom
import dev.tobins.mealplanner.app.ui.failureSentence
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/*
 * Asking for an account on the login screen (issue 314): what the form checks, and what it says about the server's
 * answer. The rules are the shared RegistrationRules, which the server checks again.
 */

/** The fields of the form, in its order: those of [RegistrationRequest], and the password typed again. */
enum class FormField(val rule: RegistrationField?) {
    DISPLAY_NAME(RegistrationField.DISPLAY_NAME),
    USERNAME(RegistrationField.USERNAME),
    EMAIL(RegistrationField.EMAIL),
    PASSWORD(RegistrationField.PASSWORD),
    PASSWORD_AGAIN(null),
}

/**
 * What is typed in the form. Held only in the form's state while it is open (see [RegistrationScreen]): never in storage
 * or the saved instance state, and [toString] leaves the passwords out, so that a log line or an error that prints it
 * cannot hold them.
 */
data class RegistrationDraft(
    val displayName: String = "",
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val passwordAgain: String = "",
) {
    /** What is sent: the fields as typed, which the server takes as [RegistrationRules.normalised] makes them. */
    fun request() = RegistrationRequest(username, displayName, email, password)

    override fun toString() = "RegistrationDraft(displayName=$displayName, username=$username, email=$email)"
}

/** What is wrong with each field of [draft], in [t]'s words, in the form's order; empty when it may be sent. */
fun registrationProblems(draft: RegistrationDraft, t: Strings): Map<FormField, String> {
    val broken = RegistrationRules.problems(draft.request()).toSet()
    return FormField.entries.mapNotNull { field ->
        val wrong = if (field.rule == null) draft.passwordAgain != draft.password else field.rule in broken
        if (wrong) field to problemOf(field, t) else null
    }.toMap()
}

/** What [field] must hold, as the form says it when it does not. */
fun problemOf(field: FormField, t: Strings): String = when (field) {
    FormField.DISPLAY_NAME -> t(Msg.PROBLEM_DISPLAY_NAME, RegistrationRules.DISPLAY_NAME_MAX)
    FormField.USERNAME -> t(Msg.PROBLEM_USERNAME, RegistrationRules.USERNAME_MIN, RegistrationRules.USERNAME_MAX)
    FormField.EMAIL -> t(Msg.PROBLEM_EMAIL)
    FormField.PASSWORD -> t(Msg.PROBLEM_PASSWORD, RegistrationRules.PASSWORD_MIN, RegistrationRules.PASSWORD_MAX)
    FormField.PASSWORD_AGAIN -> t(Msg.PROBLEM_PASSWORD_AGAIN)
}

/** What came of sending a request for an account. */
sealed interface RegistrationOutcome {
    /** Taken: the account [username] (in lower case, as the server keeps it) waits for the owner's approval. */
    data class Sent(val username: String) : RegistrationOutcome

    /** Not taken: [message] says why in the app's language; [field] is the one the server named, if it did. */
    data class Refused(val message: String, val field: FormField? = null) : RegistrationOutcome
}

/**
 * Sends a request for an account with [send] and says what came of it. Each refusal the server gives is told by its
 * `reason` ([RegistrationRefusal]) in [t]'s words, not in the server's English sentence; anything else (no connection,
 * an answer the app does not know) as any failed request is ([failureSentence]).
 */
suspend fun registrationOutcome(t: Strings, send: suspend () -> RegistrationReceipt): RegistrationOutcome = try {
    RegistrationOutcome.Sent(send().username)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    refusalOf(e, t)
}

private suspend fun refusalOf(e: Exception, t: Strings): RegistrationOutcome.Refused {
    val failed = failedFrom(e)
    if (e !is ResponseException) return RegistrationOutcome.Refused(failureSentence(failed))
    val message = when (failed.reason) {
        RegistrationRefusal.OFF -> t(Msg.REFUSED_OFF)
        RegistrationRefusal.INVALID -> {
            val field = fieldOf(e)
            return if (field == null) RegistrationOutcome.Refused(t(Msg.REFUSED_INVALID)) else RegistrationOutcome.Refused(t(Msg.REGISTER_FIX_FIELDS), field)
        }
        RegistrationRefusal.UNAVAILABLE -> t(Msg.REFUSED_UNAVAILABLE)
        RegistrationRefusal.TOO_MANY -> minutesToWait(e)?.let { t.plural(Plural.REFUSED_TOO_MANY, it) } ?: t(Msg.REFUSED_TOO_MANY_LATER)
        RegistrationRefusal.FULL -> t(Msg.REFUSED_FULL)
        RegistrationRefusal.DIRECTORY_DOWN -> t(Msg.REFUSED_DIRECTORY_DOWN)
        // A server from before the route answers 404 without a reason: it takes no requests either.
        else -> if (failed.reason == null && failed.status == 404) t(Msg.REFUSED_OFF) else failureSentence(failed)
    }
    return RegistrationOutcome.Refused(message)
}

/** The field a 400 names (`"field": "email"`), as the form's; null when it names none, or one this app does not know. */
private suspend fun fieldOf(e: ResponseException): FormField? {
    val body = runCatching { Json.parseToJsonElement(e.response.bodyAsText()) as? JsonObject }.getOrNull()
    val key = (body?.get("field") as? JsonPrimitive)?.content ?: return null
    return FormField.entries.firstOrNull { it.rule?.key == key }
}

/** `Retry-After` (seconds, as the server sends it) in whole minutes, rounded up; null without one the app can read. */
private fun minutesToWait(e: ResponseException): Int? =
    e.response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { ((it + 59) / 60).toInt() }

/**
 * Whether the server at [api] takes requests for an account now, so that the login screen offers the form. Any failure
 * means no: a server from before the route answers 404, and an unreachable one offers nothing to send to either.
 */
suspend fun registrationOpen(api: ApiClient): Boolean = try {
    api.registrationStatus().open
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    false
}
