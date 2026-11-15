package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * The pieces every screen's top bar is built from, so that bars look and behave the same
 * everywhere: icon actions with a spoken description, the back arrow, the overflow menu
 * and the stepper through days or weeks.
 */

/**
 * An icon action in a top bar or a row. [description] is what a screen reader says, so it
 * names the action ("Sync cooking site"), not the picture. While [busy] the icon makes way
 * for a small spinner and the action can't be started again.
 */
@Composable
fun BarAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    IconButton(onClick = onClick, enabled = enabled && !busy) {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(20.dp).semantics { contentDescription = description },
                strokeWidth = 2.dp,
            )
        } else {
            Icon(icon, contentDescription = description)
        }
    }
}

/** The arrow that leaves a sub-screen. */
@Composable
fun BackButton(onClick: () -> Unit) = BarAction(AppIcons.Back, "Back", onClick)

/** One entry of an [OverflowMenu]. */
class MenuAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** The "⋮" button for the actions that don't fit in a top bar, each shown with icon and label. */
@Composable
fun OverflowMenu(vararg actions: MenuAction) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarAction(AppIcons.More, "More", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    leadingIcon = { Icon(action.icon, contentDescription = null) },
                    onClick = {
                        open = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

/**
 * A failure in a line or two: the error icon, a short [summary] and a "Details" button that
 * shows the full [details] (often a long technical message) in a dialog.
 */
@Composable
fun ErrorBanner(summary: String, details: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(AppIcons.Error, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
        Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { open = true }) { Text("Details") }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(summary) },
            text = { Text(details, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

/** A small icon with a short [text], for a warning that doesn't need a sentence. */
@Composable
fun WarningBadge(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(AppIcons.Warning, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
    }
}

/**
 * A planned meal's numbers on one line: [macros] on the left, which may wrap, and the [cost]
 * on the right, which never does, so neither is cut off at a large font size.
 */
@Composable
fun MealNumbers(macros: String, cost: String?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(
            macros,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        cost?.let { Text(it, style = MaterialTheme.typography.bodySmall, softWrap = false) }
    }
}

/**
 * Steps through days or weeks: chevrons around the [label] of the one shown. Meant as a top
 * bar's title; it shifts left so the first chevron lines up with the content below.
 * [previous] and [next] describe the steps, e.g. "Previous week".
 */
@Composable
fun PeriodStepper(label: String, previous: String, next: String, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
        BarAction(AppIcons.ChevronLeft, previous, onPrevious)
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // Wide enough for a date, so the second chevron stays put while stepping through days.
            modifier = Modifier.weight(1f, fill = false).widthIn(min = 96.dp),
        )
        BarAction(AppIcons.ChevronRight, next, onNext)
    }
}
