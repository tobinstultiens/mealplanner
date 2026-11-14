package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * App settings: light or dark, which applies at once, and where the server is and the token
 * to use, which can be tested before saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    onSave: (ServerSettings) -> Unit,
    onBack: () -> Unit,
) {
    var url by remember { mutableStateOf(current.url) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val draft = ServerSettings(url.trim().trimEnd('/'))
    val urlValid = draft.url.startsWith("http://") || draft.url.startsWith("https://")

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
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
            loginError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(selected = theme == mode, onClick = { onTheme(mode) }, label = { Text(mode.label) })
                }
            }
            if (updater != null) AppVersionSection(updater)
            Text("Server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                url, { url = it; result = null },
                label = { Text("Server address") },
                supportingText = { Text("For example https://mealplanner.example.nl") },
                isError = url.isNotBlank() && !urlValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    enabled = urlValid && !testing,
                    onClick = {
                        testing = true
                        scope.launch {
                            val client = ApiClient(draft)
                            result = when (val health = load { client.health() }) {
                                is Load.Failed -> "Cannot reach the server: ${health.message}"
                                is Load.Ready -> "Connected to server ${health.value.version}."
                                Load.Loading -> null
                            }
                            client.close()
                            testing = false
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Test connection") }
                Button(enabled = urlValid && draft != current, onClick = { onSave(draft) }) { Text("Save") }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
    Text("App", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    found?.let { update ->
        UpdateDialog(update, updater.currentVersion, onUpdate = { updater.install(update); found = null }, onLater = { found = null })
    }
}
