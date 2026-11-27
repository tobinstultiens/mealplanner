package dev.tobins.mealplanner.app.recipes

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
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.ui.ErrorBanner
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.Spacing
import dev.tobins.mealplanner.app.ui.failureSentence
import dev.tobins.mealplanner.app.ui.load
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
    title: String? = null,
    submit: suspend (GenerateRequest) -> Unit,
) {
    val t = LocalStrings.current
    var mode by remember { mutableStateOf(GenerationMode.PREFERENCE) }
    var slot by remember { mutableStateOf(fixedSlot ?: MealSlot.DINNER) }
    var wish by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<GenerationError?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title ?: t(Msg.GENERATE_RECIPE)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    FilterChip(selected = mode == GenerationMode.PREFERENCE, onClick = { mode = GenerationMode.PREFERENCE }, label = { Text(t(Msg.TO_MY_WISH)) })
                    FilterChip(selected = mode == GenerationMode.TASTE_BREAKER, onClick = { mode = GenerationMode.TASTE_BREAKER }, label = { Text(t(Msg.SURPRISE_ME)) })
                }
                Text(
                    if (mode == GenerationMode.PREFERENCE) t(Msg.GEN_HINT_WISH) else t(Msg.GEN_HINT_SURPRISE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fixedSlot == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    MealSlot.entries.forEach { s ->
                        FilterChip(selected = slot == s, onClick = { slot = s }, label = { Text(t.slot(s, capitalised = true)) })
                    }
                }
                OutlinedTextField(
                    wish, { wish = it },
                    label = { Text(if (mode == GenerationMode.PREFERENCE) t(Msg.WISH_EXAMPLE) else t(Msg.WISH_OPTIONAL)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.BetweenCards), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(t(Msg.WRITING_RECIPE))
                    }
                }
                error?.let { shown ->
                    if (shown.details == null) Text(shown.summary, color = MaterialTheme.colorScheme.error)
                    else ErrorBanner(shown.summary, shown.details, Modifier.fillMaxWidth())
                }
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
                            error = generationError(t, result)
                        }
                        Load.Loading -> Unit
                    }
                }
            }) { Text(t(Msg.GENERATE)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}

/** What the dialog says about a failed generation: a [summary], and the server's own sentence behind "Details" when the summary is ours. */
internal class GenerationError(val summary: String, val details: String? = null)

/**
 * The server answers 503 both when recipe generation is not set up (`reason` "generation_off") and when
 * a write waited too long for its turn; only the first is "this server does not write recipes", any other
 * failure shows the server's own sentence. The setting's name stays behind "Details": the user cannot act on it.
 */
internal fun generationError(t: Strings, failed: Load.Failed): GenerationError = when {
    failed.status == 503 && failed.reason == GENERATION_OFF -> GenerationError(t(Msg.GEN_OFF), failed.message)
    failed.status == 409 -> GenerationError(t(Msg.GEN_NEEDS_PROFILE))
    else -> GenerationError(failureSentence(failed, Msg.GEN_FAILED))
}

/** The `reason` of the server's 503 for a server that has no recipe generation. */
private const val GENERATION_OFF = "generation_off"
