package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Pantry
import kotlinx.coroutines.launch

/**
 * What is at home. A staple is always there and never goes on the shopping list;
 * an on-hand item is a perishable to use up, which the planner favours.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryScreen(api: ApiClient, onBack: () -> Unit) {
    var state by remember { mutableStateOf<Load<Pair<List<Ingredient>, Pantry>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        state = Load.Loading
        state = load { api.ingredients() to api.pantry() }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Pantry") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            Load.Loading -> CenteredBox(Modifier.padding(padding)) { CircularProgressIndicator() }
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage("Could not load the pantry", current.message) { reload++ }
            }
            is Load.Ready -> {
                val (ingredients, pantry) = current.value
                val staples = pantry.staples.mapTo(HashSet()) { it.id }
                val onHand = pantry.onHand.mapTo(HashSet()) { it.id }

                fun update(action: suspend () -> Pantry) {
                    scope.launch {
                        when (val result = load(action)) {
                            is Load.Ready -> state = Load.Ready(ingredients to result.value)
                            is Load.Failed -> snackbar.showSnackbar(result.message)
                            Load.Loading -> Unit
                        }
                    }
                }
                // What is already in the pantry comes first, then the rest by name.
                val shown = ingredients
                    .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                    .sortedWith(compareBy({ it.id !in onHand }, { it.id !in staples }, { it.name.lowercase() }))

                Column(Modifier.padding(padding).fillMaxSize()) {
                    Text(
                        "Staples: ${staples.size} (always at home, never on the shopping list) · " +
                            "On hand: ${onHand.size} (to use up; the planner favours them)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    OutlinedTextField(
                        query, { query = it },
                        label = { Text("Search ingredients") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(shown, key = { it.id }) { ingredient ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(ingredient.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                FilterChip(
                                    selected = ingredient.id in staples,
                                    onClick = { update { api.setStaple(ingredient.id, ingredient.id !in staples) } },
                                    label = { Text("Staple") },
                                )
                                FilterChip(
                                    selected = ingredient.id in onHand,
                                    onClick = { update { api.setOnHand(ingredient.id, ingredient.id !in onHand) } },
                                    label = { Text("On hand") },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
