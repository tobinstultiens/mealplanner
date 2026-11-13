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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    onSave: (ServerSettings) -> Unit,
    onBack: () -> Unit,
) {
    var url by remember { mutableStateOf(current.url) }
    var token by remember { mutableStateOf(current.token) }
    var showToken by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val draft = ServerSettings(url.trim().trimEnd('/'), token.trim())
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
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(selected = theme == mode, onClick = { onTheme(mode) }, label = { Text(mode.label) })
                }
            }
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
            OutlinedTextField(
                token, { token = it; result = null },
                label = { Text("Access token") },
                supportingText = { Text("The server's MEALPLANNER_API_TOKEN") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } },
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
                                is Load.Ready -> when (val auth = load { client.profile() }) {
                                    is Load.Ready -> "Connected to server ${health.value.version}; the token is accepted."
                                    is Load.Failed -> if (auth.status == 401) "The server is reachable, but the token is wrong." else "Server reachable, but: ${auth.message}"
                                    Load.Loading -> null
                                }
                                Load.Loading -> null
                            }
                            client.close()
                            testing = false
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Test connection") }
                Button(enabled = urlValid, onClick = { onSave(draft) }) { Text("Save") }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
