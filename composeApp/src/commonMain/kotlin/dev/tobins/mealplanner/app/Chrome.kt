package dev.tobins.mealplanner.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
fun BackButton(onClick: () -> Unit) = BarAction(AppIcons.Back, LocalStrings.current(Msg.BACK), onClick)

/** One entry of an [OverflowMenu]. */
class MenuAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** The "⋮" button for the actions that don't fit in a top bar, each shown with icon and label. */
@Composable
fun OverflowMenu(vararg actions: MenuAction) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarAction(AppIcons.More, LocalStrings.current(Msg.MORE), onClick = { open = true })
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
fun ErrorBanner(summary: String, details: String, modifier: Modifier = Modifier) =
    DetailsBanner(summary, details, AppIcons.Error, MaterialTheme.colorScheme.error, modifier)

/** Like [ErrorBanner], in the warning colour: for something that works but may not be right. */
@Composable
fun WarningBanner(summary: String, details: String, modifier: Modifier = Modifier) =
    DetailsBanner(summary, details, AppIcons.Warning, MaterialTheme.warning, modifier)

@Composable
private fun DetailsBanner(summary: String, details: String, icon: ImageVector, tint: Color, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = tint)
        Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = tint)
        TextButton(onClick = { open = true }) { Text(LocalStrings.current(Msg.DETAILS)) }
    }
    if (open) DetailsDialog(summary, details) { open = false }
}

/** The full [details] of a failure under its [title], for the technical text the summary leaves out. */
@Composable
fun DetailsDialog(title: String, details: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(details, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onClose) { Text(LocalStrings.current(Msg.CLOSE)) } },
    )
}

/** A small icon with a short [text], for a warning that doesn't need a sentence. */
@Composable
fun WarningBadge(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Icon(AppIcons.Warning, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.warning)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.warning)
    }
}

/**
 * A planned meal's numbers on one line: [macros] on the left, which may wrap, and the [cost]
 * on the right, which never does, so neither is cut off at a large font size.
 */
@Composable
fun MealNumbers(macros: String, cost: String?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.BetweenCards), verticalAlignment = Alignment.Top) {
        Text(
            macros,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.numeric(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        cost?.let { Text(it, style = MaterialTheme.typography.bodySmall.numeric(), softWrap = false) }
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

/**
 * A screen opened from a tab: a top bar with the back arrow and the [title], optional
 * [actions] and [snackbar] host, around the [content]. The content gets the insets to pad with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /** Shown at the bottom with the snackbars, for a line that stays (see [RefreshNotice]). */
    notice: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = actions,
            )
        },
        snackbarHost = { Column { snackbar?.let { SnackbarHost(it) }; notice() } },
        content = content,
    )
}

/** The heading of a group of rows or cards, in the accent colour so groups stand out when scrolling. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleSmall) {
    Text(text, modifier, style = style, color = MaterialTheme.colorScheme.primary)
}

/**
 * What a screen shows when there is nothing in it yet: the [icon], one [message] sentence
 * and, when there is something to do about it, a button for the [action].
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    EmptyStateLayout(message, modifier, actionLabel, onAction) {
        Icon(icon, contentDescription = null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** An [EmptyState] with a [spot] illustration, which brings its own colours, in place of the plain icon. */
@Composable
fun EmptyState(
    spot: Spot,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    EmptyStateLayout(message, modifier, actionLabel, onAction) {
        Image(Illustrations.spot(spot), contentDescription = null, Modifier.size(96.dp))
    }
}

@Composable
private fun EmptyStateLayout(
    message: String,
    modifier: Modifier,
    actionLabel: String?,
    onAction: () -> Unit,
    art: @Composable () -> Unit,
) {
    Column(
        modifier.fillMaxSize().padding(Spacing.Message),
        verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        art()
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (actionLabel != null) Button(onClick = onAction) { Text(actionLabel) }
    }
}

/** What a screen shows while its data loads: a spinner with a word, so it isn't a blank screen. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Related, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(LocalStrings.current(Msg.LOADING), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
