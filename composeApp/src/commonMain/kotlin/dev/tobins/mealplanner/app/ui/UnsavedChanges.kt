package dev.tobins.mealplanner.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.app.BackHandler
import dev.tobins.mealplanner.app.LocalStrings

/**
 * Makes leaving a form safe: while [dirty] (the form differs from what was loaded or saved), going
 * back asks "Discard your changes?" first and calls [onLeave] only on "Discard"; without changes it
 * leaves at once. Covers the system back (the gesture, the browser's back) as well as the screen's
 * own arrow, which is the returned function: call it instead of [onLeave].
 *
 * The one place that handles the system back for such a screen: the screen must not also sit under
 * a [BackHandler] of its own, as the browser's one back would then reach both.
 */
@Composable
fun UnsavedChangesGuard(dirty: Boolean, onLeave: () -> Unit): () -> Unit {
    // Kept over a re-created activity: a question that was open is still open.
    var asking by rememberSaveable { mutableStateOf(false) }
    var rearm by remember { mutableIntStateOf(0) }
    fun back() = if (dirty) asking = true else onLeave()
    BackHandler(enabled = true, rearm = rearm) {
        // The browser used up its history entry on this back; the next one must ask again.
        if (dirty) rearm++
        back()
    }
    if (asking) {
        DiscardDialog(onKeep = { asking = false }, onDiscard = { asking = false; onLeave() })
    }
    return ::back
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onKeep) {
        Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            DiscardQuestion(onKeep, onDiscard)
        }
    }
}

/** The dialog's content: a dialog is a window of its own, so the screenshot test draws this part (see `Shot`). */
@Composable
internal fun DiscardQuestion(onKeep: () -> Unit, onDiscard: () -> Unit) {
    val t = LocalStrings.current
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(t(CommonMsg.DISCARD_CHANGES_TITLE), style = MaterialTheme.typography.headlineSmall)
        Text(t(CommonMsg.DISCARD_CHANGES_TEXT), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
            TextButton(onClick = onKeep) { Text(t(CommonMsg.KEEP_EDITING)) }
            TextButton(onClick = onDiscard) { Text(t(CommonMsg.DISCARD)) }
        }
    }
}
