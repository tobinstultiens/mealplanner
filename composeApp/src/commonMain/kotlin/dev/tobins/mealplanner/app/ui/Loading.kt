package dev.tobins.mealplanner.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.shopping.plainReason
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/**
 * A failure whose [message] is a sentence the app wrote for the user (a login answer that does not match,
 * a refusal the picker passes on). It stays as it is, while any other exception, a library's
 * [IllegalStateException] included, is told in plain words by [failedFrom]. Throw this, not `error(...)`, for such sentences.
 */
internal class AppError(override val message: String) : IllegalStateException(message)

/** Result of loading something from the server. */
internal sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    /**
     * [status] is the HTTP status when the server answered with an error. [technical]: [message] is
     * the text of an exception, such as "unexpected end of stream on http://...", not a sentence for
     * the user. [unreadable]: the server answered, but the app could not read the answer.
     * [lostConnection]: the request could not connect, timed out or was cut off. [reason] is the
     * server's optional machine-readable `reason` next to its `error` sentence, for a screen that must
     * tell two answers with the same status apart (503: generation is off, or a write is busy), and [detail] the optional
     * `detail` that says more about it (a refused login whose request for an account waits, issue 314).
     */
    data class Failed(
        val message: String,
        val status: Int? = null,
        val technical: Boolean = false,
        val unreadable: Boolean = false,
        val lostConnection: Boolean = false,
        val reason: String? = null,
        val detail: String? = null,
    ) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

internal suspend fun <T> load(block: suspend () -> T): Load<T> = try {
    Load.Ready(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    failedFrom(e)
}

/** What [e], thrown by a request, means for the user: the server's explanation, or a technical failure flagged as such. */
internal suspend fun failedFrom(e: Throwable): Load.Failed {
    if (e is AppError) return Load.Failed(e.message)
    if (e is ResponseException) {
        // The server explains errors as {"error": "...", "reason": "...", "detail": "..."} (reason and detail are optional); fall back to the status line when there is no sentence or it is empty.
        val body = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(e.response.bodyAsText()) as? kotlinx.serialization.json.JsonObject }.getOrNull()
        fun field(name: String) = (body?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val status = e.response.status
        return Load.Failed(field("error") ?: Strings.current(CommonMsg.SERVER_ANSWERED, status.value, status.description), status.value, reason = field("reason"), detail = field("detail"))
    }
    return Load.Failed(
        e.message ?: Strings.current(CommonMsg.UNKNOWN_ERROR),
        technical = e.message != null,
        unreadable = e is SerializationException || e is ContentConvertException,
        lostConnection = e is IOException || e is HttpRequestTimeoutException,
    )
}

/** [text] as a sentence: capitalised and with a full stop unless it ends in one already; blank stays as it is. */
internal fun sentence(text: String): String =
    if (text.isBlank()) text else text.replaceFirstChar { it.uppercase() }.let { if (it.last() in ".!?") it else "$it." }

/**
 * What a snackbar says about [failed], as a sentence: the server's own explanation, or for a technical
 * failure a plain reason (never the exception's text). With [msg] the reason is filled into it ("Could not refresh: {0}").
 * Every place that shows a failed request in a sentence goes through here, so none shows raw text.
 */
internal fun failureSentence(failed: Load.Failed, msg: Msg? = null): String {
    val reason = reasonOf(failed)
    return sentence(if (msg == null) reason else Strings.current(msg, reason))
}

/** Runs [action]; null when it worked, else the sentence for a snackbar. */
internal suspend fun failureOf(action: suspend () -> Unit): String? = (load(action) as? Load.Failed)?.let { failureSentence(it) }

/**
 * Why [e], caught where the app did something other than [load], failed, as a fragment for inside a sentence:
 * an [AppError]'s own sentence, else the plain reason from [failedFrom], so an exception's text
 * ("unexpected end of stream on http://...") is never shown.
 */
internal suspend fun reasonOf(e: Throwable): String = reasonOf(failedFrom(e))

/** Why [failed] failed, as a fragment for inside a sentence: the server's explanation or a plain reason. */
internal fun reasonOf(failed: Load.Failed): String = if (failed.technical) plainReason(failed) else failed.message

@Composable
internal fun ErrorMessage(title: String, failed: Load.Failed, onRetry: () -> Unit) {
    val t = LocalStrings.current
    val detail = failed.message
    val openServer = LocalOpenServerSettings.current
    Column(
        Modifier.fillMaxSize().padding(Spacing.Message),
        verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        // An exception's own text ("unexpected end of stream on http://...") is no sentence for the user:
        // a plain one says what happened and the text stays behind "Details".
        if (failed.technical) {
            val plain = plainReason(failed)
            ErrorBanner(summary = sentence(plain), details = detail, modifier = Modifier.fillMaxWidth())
        } else if (detail.length <= 100 && '\n' !in detail) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ErrorBanner(summary = detail.lineSequence().first().take(100) + "…", details = detail, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            Button(onClick = onRetry) { Text(t(CommonMsg.TRY_AGAIN)) }
            if (openServer != null) TextButton(onClick = openServer) { Text(t(CommonMsg.SERVER_SETTINGS)) }
        }
    }
}

/** Opens the server settings; provided by [App] so any error message can offer it. */
val LocalOpenServerSettings = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }
