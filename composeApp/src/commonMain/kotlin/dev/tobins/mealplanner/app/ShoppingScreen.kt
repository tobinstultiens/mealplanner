package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** `3 × · 330 g`, `450 g`, `2 ×`, or empty when the recipes give no quantity. */
fun ShoppingItem.quantityText(): String = listOfNotNull(
    // Pieces are rounded up: nobody buys 1.5 onions.
    pieces?.let { "${ceil(it - 1e-9).toInt()} ×" },
    grams?.let { if (it >= 1000) "${formatAmount(it / 1000)} kg" else "${it.roundToInt()} g" },
).joinToString(" · ")

/** What to buy for a week's plan, grouped by shop section, with items to tick off. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(api: ApiClient, onOpenPantry: () -> Unit) {
    var weekStart by rememberSaveable { mutableStateOf(mondayOf(todayIso())) }
    var state by remember { mutableStateOf<Load<ShoppingList>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(weekStart, reload) {
        state = Load.Loading
        state = load { api.shoppingList(weekStart) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shopping ${dayLabel(weekStart).substringAfter(' ')}") },
                actions = {
                    TextButton(onClick = onOpenPantry) { Text("Pantry") }
                    TextButton(onClick = { weekStart = plusDays(weekStart, -7) }) { Text("‹") }
                    TextButton(onClick = { weekStart = plusDays(weekStart, 7) }) { Text("›") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            Load.Loading -> CenteredBox(Modifier.padding(padding)) { CircularProgressIndicator() }
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage("Could not load the shopping list", current.message) { reload++ }
            }
            is Load.Ready -> {
                val list = current.value
                if (list.items.isEmpty()) {
                    CenteredBox(Modifier.padding(padding)) {
                        Text("Nothing to buy: no meals are planned for this week.", modifier = Modifier.padding(24.dp))
                    }
                    return@Scaffold
                }
                LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        val left = list.items.count { !it.checked }
                        Text(
                            if (left == 0) "All ${list.items.size} items ticked off." else "$left of ${list.items.size} items left",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    list.items.groupBy { it.category }.forEach { (category, items) ->
                        item(key = "h:$category") {
                            Text(
                                category,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(items, key = { it.key }) { item ->
                            ShoppingRow(item) {
                                // Show the tick at once; the server's answer then confirms or corrects it.
                                state = Load.Ready(list.copy(items = list.items.map { if (it.key == item.key) it.copy(checked = !item.checked) else it }))
                                scope.launch {
                                    when (val result = load { api.setShoppingChecked(weekStart, item.key, !item.checked) }) {
                                        is Load.Ready -> state = result
                                        is Load.Failed -> {
                                            state = Load.Ready(list)
                                            snackbar.showSnackbar("Could not save the tick: ${result.message}")
                                        }
                                        Load.Loading -> Unit
                                    }
                                }
                            }
                        }
                    }
                    if (list.staples.isNotEmpty()) {
                        item {
                            Text(
                                "Staples this plan uses (not on the list): ${list.staples.joinToString()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShoppingRow(item: ShoppingItem, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                item.name + if (item.onHand) "  (on hand?)" else "",
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.recipes.joinToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(item.quantityText(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp))
    }
}
