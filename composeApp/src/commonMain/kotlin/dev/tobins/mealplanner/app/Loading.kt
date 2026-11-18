package dev.tobins.mealplanner.app

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
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/** Result of loading something from the server. */
internal sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    /**
     * [status] is the HTTP status when the server answered with an error. [technical]: [message] is
     * the text of an exception, such as "unexpected end of stream on http://...", not a sentence for
     * the user. [unreadable]: the server answered, but the app could not read the answer.
     * [lostConnection]: the request could not connect, timed out or was cut off.
     */
    data class Failed(
        val message: String,
        val status: Int? = null,
        val technical: Boolean = false,
        val unreadable: Boolean = false,
        val lostConnection: Boolean = false,
    ) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

internal suspend fun <T> load(block: suspend () -> T): Load<T> = try {
    Load.Ready(block())
} catch (e: CancellationException) {
    throw e
} catch (e: ResponseException) {
    // The server explains errors as {"error": "..."}; fall back to the status line.
    val reason = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(e.response.bodyAsText())
            .let { it as? kotlinx.serialization.json.JsonObject }?.get("error")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }.getOrNull()
    Load.Failed(reason ?: Strings.current(Msg.SERVER_ANSWERED, e.response.status.value, e.response.status.description), e.response.status.value)
} catch (e: Exception) {
    Load.Failed(
        e.message ?: Strings.current(Msg.UNKNOWN_ERROR),
        technical = e.message != null,
        unreadable = e is SerializationException || e is ContentConvertException,
        lostConnection = e is IOException || e is HttpRequestTimeoutException,
    )
}

/** [text] as a sentence: capitalised and with a full stop unless it ends in one already. */
internal fun sentence(text: String): String =
    text.replaceFirstChar { it.uppercase() }.let { if (it.last() in ".!?") it else "$it." }

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
            Button(onClick = onRetry) { Text(t(Msg.TRY_AGAIN)) }
            if (openServer != null) TextButton(onClick = openServer) { Text(t(Msg.SERVER_SETTINGS)) }
        }
    }
}

/** Opens the server settings; provided by [App] so any error message can offer it. */
val LocalOpenServerSettings = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }
