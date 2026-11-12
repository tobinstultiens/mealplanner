package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Phase 0 screen: proves the app can reach the server. Replaced by the real
 * navigation (Today / Plan / Shopping / Recipes / Progress) in later phases.
 */
@Composable
fun App(defaultServerUrl: String) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            var serverUrl by remember { mutableStateOf(defaultServerUrl) }
            var result by remember { mutableStateOf("Not checked yet") }
            val scope = rememberCoroutineScope()

            Column(
                Modifier.safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Meal planner", style = MaterialTheme.typography.headlineMedium)
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    result = "Checking…"
                    scope.launch {
                        val client = ApiClient(serverUrl, token = "")
                        result = try {
                            val health = client.health()
                            "Server ${health.status}, version ${health.version}"
                        } catch (e: Exception) {
                            "Could not reach server: ${e.message}"
                        } finally {
                            client.close()
                        }
                    }
                }) { Text("Check connection") }
                Text(result)
            }
        }
    }
}
