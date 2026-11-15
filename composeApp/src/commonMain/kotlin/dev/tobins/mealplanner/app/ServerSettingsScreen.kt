package dev.tobins.mealplanner.app

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * App settings in cards: the server address (first while not logged in, since nothing works
 * without it; it can be tested before saving), the account, light or dark and (on Android 12+) the wallpaper colours, which apply at
 * once, and the app version.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ServerSettingsScreen(
    current: ServerSettings,
    /** Who is logged in, when known. */
    account: String?,
    loggedIn: Boolean,
    loginError: String?,
    /** Saves [ServerSettings] and opens the login page of that server's identity provider. */
    onLogin: (ServerSettings) -> Unit,
    onLogout: () -> Unit,
    updater: UpdateInstaller?,
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    /** Whether the colours come from the wallpaper; the switch shows only where the device has them. */
    systemColors: Boolean,
    onSystemColors: (Boolean) -> Unit,
    onSave: (ServerSettings) -> Unit,
    onBack: () -> Unit,
) {
    var url by remember { mutableStateOf(current.url) }
    var result by remember { mutableStateOf<ConnectionResult?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val draft = ServerSettings(url.trim().trimEnd('/'))
    val urlValid = draft.url.startsWith("http://") || draft.url.startsWith("https://")

    SubScreen("Settings", onBack) { padding ->
        // Without an account the server comes first: nothing else works until it is set.
        val server: @Composable () -> Unit = {
            SettingsCard("Server") {
                OutlinedTextField(
                    url, { url = it; result = null },
                    label = { Text("Server address") },
                    supportingText = { Text("For example https://mealplanner.example.nl") },
                    isError = url.isNotBlank() && !urlValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.BetweenCards)) {
                    OutlinedButton(
                        enabled = urlValid && !testing,
                        onClick = {
                            testing = true
                            scope.launch {
                                val client = ApiClient(draft)
                                result = when (val health = load { client.health() }) {
                                    is Load.Failed -> ConnectionResult(false, health.message)
                                    is Load.Ready -> ConnectionResult(true, "Connected to server ${health.value.version}.")
                                    Load.Loading -> null
                                }
                                client.close()
                                testing = false
                            }
                        },
                    ) { Text(if (testing) "Testing…" else "Test connection") }
                    Button(enabled = urlValid && draft != current, onClick = { onSave(draft) }) { Text("Save") }
                }
                result?.let {
                    if (it.ok) Text(it.text, style = MaterialTheme.typography.bodyMedium)
                    else ErrorBanner("Cannot reach the server", it.text)
                }
            }
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!loggedIn) server()
            SettingsCard("Account") {
                if (loggedIn) {
                    Text(if (account != null) "Logged in as $account." else "Logged in.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onLogout) { Text("Log out") }
                } else {
                    Text(
                        "Log in with your account on the server's login page (Authelia). Each account has its own recipes, plans and progress.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(enabled = urlValid, onClick = { onLogin(draft) }) { Text("Log in") }
                }
                loginError?.let { ErrorBanner("Could not log in", it) }
            }
            SettingsCard("Appearance") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(selected = theme == mode, onClick = { onTheme(mode) }, label = { Text(mode.label) })
                    }
                }
                if (systemColorsAvailable()) {
                    // The whole row switches, so the label is as good a target as the switch itself.
                    Row(
                        Modifier.fillMaxWidth().toggleable(systemColors, role = Role.Switch, onValueChange = onSystemColors),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Related),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Use system colours", style = MaterialTheme.typography.bodyMedium)
                            Text("Take the colours from your wallpaper.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = systemColors, onCheckedChange = null)
                    }
                }
            }
            if (updater != null) AppVersionSection(updater)
            if (loggedIn) server()
        }
    }
}

/** The outcome of "Test connection": [text] is the server version, or the error. */
private class ConnectionResult(val ok: Boolean, val text: String)

/** One group of settings: a card with its title. */
@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** The installed version and a manual "Check for updates". */
@Composable
private fun AppVersionSection(updater: UpdateInstaller) {
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<AppUpdate?>(null) }
    val scope = rememberCoroutineScope()
    SettingsCard("App") {
        Text("Version ${updater.currentVersion}", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(enabled = !checking, onClick = {
            checking = true
            message = null
            scope.launch {
                runCatching { AppUpdates.check(updater.currentVersion) }
                    .onSuccess { if (it == null) message = "You have the latest version." else found = it }
                    .onFailure { message = "Could not check: ${it.message}" }
                checking = false
            }
        }) { Text(if (checking) "Checking…" else "Check for updates") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    found?.let { update ->
        UpdateDialog(update, updater.currentVersion, onUpdate = { updater.install(update); found = null }, onLater = { found = null })
    }
}
