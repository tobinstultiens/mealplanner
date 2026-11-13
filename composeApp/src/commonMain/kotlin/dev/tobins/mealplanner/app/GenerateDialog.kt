package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.GenerationMode
import dev.tobins.mealplanner.api.MealSlot
import kotlinx.coroutines.launch

/**
 * Asks for a new recipe: what kind, for which meal (unless [fixedSlot] decides it), and an
 * optional wish. [submit] does the request and throws on failure; the dialog shows the error.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenerateDialog(
    onDismiss: () -> Unit,
    fixedSlot: MealSlot? = null,
    title: String = "Generate a recipe",
    submit: suspend (GenerateRequest) -> Unit,
) {
    var mode by remember { mutableStateOf(GenerationMode.PREFERENCE) }
    var slot by remember { mutableStateOf(fixedSlot ?: MealSlot.DINNER) }
    var wish by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == GenerationMode.PREFERENCE, onClick = { mode = GenerationMode.PREFERENCE }, label = { Text("To my wish") })
                    FilterChip(selected = mode == GenerationMode.TASTE_BREAKER, onClick = { mode = GenerationMode.TASTE_BREAKER }, label = { Text("Surprise me") })
                }
                Text(
                    if (mode == GenerationMode.PREFERENCE) "A recipe that fits your targets and what you ask for."
                    else "Something unlike your recent meals, using what is at home and on offer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fixedSlot == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealSlot.entries.forEach { s ->
                        FilterChip(selected = slot == s, onClick = { slot = s }, label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
                OutlinedTextField(
                    wish, { wish = it },
                    label = { Text(if (mode == GenerationMode.PREFERENCE) "Wish (e.g. Korean, spicy, quick)" else "Wish (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text("Writing the recipe; this can take a minute.")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    when (val result = load { submit(GenerateRequest(mode, slot, wish.trim())) }) {
                        is Load.Ready -> Unit
                        is Load.Failed -> {
                            busy = false
                            error = when (result.status) {
                                503 -> "Recipe generation is off on the server: it needs an ANTHROPIC_API_KEY."
                                409 -> "Set up your profile first; recipes are sized to your targets."
                                else -> "Could not generate: ${result.message}"
                            }
                        }
                        Load.Loading -> Unit
                    }
                }
            }) { Text("Generate") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
