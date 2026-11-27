package dev.tobins.mealplanner.app.accounts

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.ServerSettings
import dev.tobins.mealplanner.app.ui.ErrorBanner
import dev.tobins.mealplanner.app.ui.Illustrations
import dev.tobins.mealplanner.app.ui.Spacing

/**
 * What the app shows while nobody is logged in: its name, one Log in button and the server it logs in
 * to, with why the last session ended. Usually the login page opens over it by itself (see
 * [LoginExchange.claimAutoOpen]); this screen is what stays when that page is closed or the login
 * fails. Without a known server it asks for the address first. The settings (language, theme) stay one
 * tap away: "Change server" opens them under a known server, "Settings" while the address is asked for.
 * "Register a new account" shows while the server takes requests for an account (issue 314, [onRegister]).
 */
@Composable
fun LoginScreen(
    server: ServerSettings,
    /** Whether [server] is the one to log in to; false when it is only a suggestion to confirm or change (a debug build's emulator address, or none). */
    serverKnown: Boolean,
    reason: LoggedOut?,
    /** Why the last attempt failed. */
    error: String?,
    /** A login is being started or its answer swapped for tokens: the button waits. */
    busy: Boolean,
    onLogin: (ServerSettings) -> Unit,
    onOpenSettings: () -> Unit,
    /** Opens the form that asks for an account; null where the server takes no requests, or is not known yet. */
    onRegister: (() -> Unit)? = null,
) {
    val t = LocalStrings.current
    var url by rememberSaveable(server.url) { mutableStateOf(server.url) }
    val draft = if (serverKnown) server else ServerSettings(url.trim().trimEnd('/'))
    val urlValid = draft.url.startsWith("http://") || draft.url.startsWith("https://")
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Image(Illustrations.Mark, contentDescription = null, Modifier.size(96.dp))
            Text(t(Msg.APP_NAME), style = MaterialTheme.typography.headlineMedium)
            val narrow = Modifier.widthIn(max = 420.dp).fillMaxWidth()
            reason?.message?.let { Text(t(it), narrow, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center) }
            Text(
                t(Msg.LOGIN_EXPLAIN),
                narrow,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!serverKnown) {
                OutlinedTextField(
                    url, { url = it },
                    label = { Text(t(Msg.SERVER_ADDRESS)) },
                    supportingText = { Text(t(Msg.SERVER_ADDRESS_HINT)) },
                    isError = url.isNotBlank() && !urlValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = narrow,
                )
            }
            Button(enabled = urlValid && !busy, onClick = { onLogin(draft) }, modifier = narrow) {
                Text(if (busy) t(Msg.LOGGING_IN) else t(Msg.LOG_IN))
            }
            onRegister?.let { OutlinedButton(onClick = it, modifier = narrow) { Text(t(Msg.REGISTER)) } }
            error?.let { ErrorBanner(t(Msg.COULD_NOT_LOG_IN), it, narrow) }
            if (serverKnown) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        t(Msg.LOGIN_AT, server.url.substringAfter("://")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onOpenSettings) { Text(t(Msg.CHANGE_SERVER)) }
                }
            } else {
                TextButton(onClick = onOpenSettings) { Text(t(Msg.SETTINGS)) }
            }
        }
    }
}

/** The sentence that says why there is no session; none where the user knows already. */
private val LoggedOut.message: Msg?
    get() = when (this) {
        LoggedOut.LOGIN_OPENED -> null
        LoggedOut.BY_USER -> Msg.LOGGED_OUT_BY_USER
        LoggedOut.EXPIRED -> Msg.LOGGED_OUT_EXPIRED
        LoggedOut.NOT_RENEWED -> Msg.LOGGED_OUT_NOT_RENEWED
        LoggedOut.NOT_ALLOWED -> Msg.LOGGED_OUT_NOT_ALLOWED
        LoggedOut.NO_GROUPS -> Msg.LOGGED_OUT_NO_GROUPS
        LoggedOut.WAITING_FOR_APPROVAL -> Msg.LOGGED_OUT_WAITING
    }
