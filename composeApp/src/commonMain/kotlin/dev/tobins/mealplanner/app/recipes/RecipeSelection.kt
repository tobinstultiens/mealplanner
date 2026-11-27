package dev.tobins.mealplanner.app.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.ui.Spacing

/**
 * The recipes ticked while selecting several, on the Recipes tab and in the bank. [ids] is null while
 * nothing is being selected: a selection just started from the menu is still selecting with nothing
 * ticked, but taking off the last tick ends it, as there is then nothing left to act on.
 */
class Selection(ids: Set<Long>? = null) {
    var ids by mutableStateOf(ids)

    val selecting: Boolean get() = ids != null

    fun start(first: Long? = null) {
        ids = listOfNotNull(first).toSet()
    }

    fun stop() {
        ids = null
    }

    fun contains(id: Long): Boolean = ids?.contains(id) == true

    fun toggle(id: Long) {
        val current = ids ?: return
        val toggled = if (id in current) current - id else current + id
        ids = toggled.takeIf { it.isNotEmpty() }
    }

    fun selectAll(all: Collection<Long>) {
        ids = all.toSet()
    }

    companion object {
        /** Keeps the selection when Android re-creates the screen: whether it is on, then the ids. */
        val Saver = listSaver<Selection, Any>(
            save = { listOf(it.selecting) + it.ids.orEmpty().toList() },
            restore = { saved -> Selection(if (saved[0] == true) saved.drop(1).map { it as Long }.toSet() else null) },
        )
    }
}

/**
 * The bar at the bottom while selecting: how many are selected, "Select all" for what the list shows,
 * and the [actions] as buttons that wrap to a second line when the language or the font needs the room.
 * With [aboveSystemBar] (a screen without a bottom navigation bar) it keeps clear of the system's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectionBar(count: Int, onSelectAll: () -> Unit, aboveSystemBar: Boolean, actions: @Composable FlowRowScope.() -> Unit) {
    val t = LocalStrings.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(
            Modifier.then(if (aboveSystemBar) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
                .padding(horizontal = Spacing.ScreenEdge, vertical = Spacing.Related),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t(Msg.SELECTED_COUNT, count), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onSelectAll) { Text(t(Msg.SELECT_ALL)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) { actions() }
        }
    }
}

/** One button of a [SelectionBar]; off while nothing is selected or a change is under way. */
@Composable
internal fun SelectionAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled) { Text(label) }
}
