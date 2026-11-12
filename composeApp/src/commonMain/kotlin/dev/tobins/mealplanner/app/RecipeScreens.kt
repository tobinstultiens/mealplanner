package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
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
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Result of loading something from the server. */
private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

private suspend fun <T> load(block: suspend () -> T): Load<T> = try {
    Load.Ready(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Load.Failed(e.message ?: "Unknown error")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeListScreen(api: ApiClient, onOpen: (Long) -> Unit) {
    var state by remember { mutableStateOf<Load<List<RecipeSummary>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var syncing by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var mealsOnly by rememberSaveable { mutableStateOf(true) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        state = Load.Loading
        state = load { api.recipes() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recipes") },
                actions = {
                    TextButton(
                        enabled = !syncing,
                        onClick = {
                            syncing = true
                            scope.launch {
                                val message = when (val result = load { api.syncCookingSite() }) {
                                    is Load.Ready -> with(result.value) {
                                        "Synced: $added added, $updated updated, $removed removed"
                                    }
                                    is Load.Failed -> "Sync failed: ${result.message}"
                                    Load.Loading -> ""
                                }
                                syncing = false
                                reload++
                                snackbar.showSnackbar(message)
                            }
                        },
                    ) { Text(if (syncing) "Syncing…" else "Sync") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search title or tag") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            FilterChip(
                selected = mealsOnly,
                onClick = { mealsOnly = !mealsOnly },
                label = { Text("Meals only") },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            when (val current = state) {
                Load.Loading -> CenteredBox { CircularProgressIndicator() }
                is Load.Failed -> ErrorMessage("Could not load recipes", current.message) { reload++ }
                is Load.Ready -> {
                    val shown = current.value.filter { recipe ->
                        (!mealsOnly || recipe.kind == RecipeKind.MEAL) &&
                            (query.isBlank() || recipe.title.contains(query.trim(), ignoreCase = true) ||
                                recipe.tags.any { it.contains(query.trim(), ignoreCase = true) })
                    }
                    if (shown.isEmpty()) {
                        CenteredBox {
                            Text(
                                if (current.value.isEmpty()) "No recipes yet. Tap Sync to import from the cooking site."
                                else "No recipes match.",
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(shown, key = { it.id }) { recipe ->
                                RecipeRow(recipe, onClick = { onOpen(recipe.id) })
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeRow(recipe: RecipeSummary, onClick: () -> Unit) {
    val details = buildList {
        if (recipe.tags.isNotEmpty()) add(recipe.tags.joinToString(" · "))
        recipe.servings?.let { add(if (it == 1) "1 serving" else "$it servings") }
        if (!recipe.plannable) add("no amounts yet")
    }
    ListItem(
        headlineContent = { Text(recipe.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(details.joinToString("  ·  "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeDetailScreen(api: ApiClient, recipeId: Long, onBack: () -> Unit) {
    var state by remember(recipeId) { mutableStateOf<Load<RecipeDetail>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(recipeId, reload) {
        state = Load.Loading
        state = load { api.recipe(recipeId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (state as? Load.Ready)?.value?.summary?.title ?: "Recipe",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        when (val current = state) {
            Load.Loading -> CenteredBox(Modifier.padding(padding)) { CircularProgressIndicator() }
            is Load.Failed -> Box(Modifier.padding(padding)) {
                ErrorMessage("Could not load recipe", current.message) { reload++ }
            }
            is Load.Ready -> {
                val recipe = current.value
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (recipe.summary.tags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            recipe.summary.tags.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                        }
                    }
                    recipe.summary.servings?.let {
                        Text(if (it == 1) "Makes 1 serving" else "Makes $it servings", style = MaterialTheme.typography.labelLarge)
                    }
                    if (recipe.ingredients.isEmpty()) {
                        Text(
                            "This recipe has no structured ingredient list on the cooking site yet, so it cannot be planned.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("Ingredients", style = MaterialTheme.typography.titleMedium)
                        var group: String? = null
                        for (line in recipe.ingredients) {
                            if (line.group != group) {
                                group = line.group
                                group?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                            }
                            Text("•  ${line.displayText()}")
                        }
                    }
                    if (recipe.instructions.isNotBlank()) {
                        HorizontalDivider()
                        Text(recipe.instructions)
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ErrorMessage(title: String, detail: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row { Button(onClick = onRetry) { Text("Try again") } }
    }
}
