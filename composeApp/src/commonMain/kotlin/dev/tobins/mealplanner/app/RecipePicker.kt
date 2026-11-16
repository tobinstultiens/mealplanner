package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The recipes to choose a meal from: meals that can be planned and match [query] in their title or
 * a tag, those marked for [slot] first (the planner would pick them for it), each group by title.
 */
fun pickableRecipes(recipes: List<RecipeSummary>, slot: MealSlot, query: String): List<RecipeSummary> {
    val needle = query.trim()
    return recipes
        .filter { it.kind == RecipeKind.MEAL && it.plannable }
        .filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) || it.tags.any { tag -> tag.contains(needle, ignoreCase = true) } }
        .sortedWith(compareBy({ slot !in it.mealSlots }, { it.title.lowercase() }))
}

/**
 * Lets the user pick a recipe for one meal. [onPick] puts it in the plan and throws on failure; the
 * dialog stays open and shows the error then.
 */
@Composable
fun RecipePicker(api: ApiClient, slot: MealSlot, title: String, onDismiss: () -> Unit, onPick: suspend (RecipeSummary) -> Unit) {
    val t = LocalStrings.current
    var state by remember { mutableStateOf<Load<List<RecipeSummary>>>(Load.Loading) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        state = Load.Loading
        state = load { api.recipes() }
    }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when (val current = state) {
                Load.Loading -> LoadingState()
                is Load.Failed -> ErrorMessage(t(Msg.RECIPES_LOAD_FAILED), current) { reload++ }
                is Load.Ready -> Column {
                    RecipePickerBody(current.value, slot) { recipe ->
                        scope.launch {
                            try {
                                onPick(recipe)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: t(Msg.PLAN_CHOOSE_FAILED)
                            }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}

/** The search field and the rows of [pickableRecipes]; split from the dialog so a screenshot can show it. */
@Composable
fun RecipePickerBody(recipes: List<RecipeSummary>, slot: MealSlot, onPick: (RecipeSummary) -> Unit) {
    val t = LocalStrings.current
    var query by remember { mutableStateOf("") }
    val shown = pickableRecipes(recipes, slot, query)
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(t(Msg.SEARCH_RECIPES)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (shown.isEmpty()) {
            Text(t(Msg.NO_RECIPES_MATCH), Modifier.padding(top = 16.dp))
        } else {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(shown, key = { it.id }) { recipe ->
                    RecipeRow(recipe, onClick = { onPick(recipe) })
                    HorizontalDivider()
                }
            }
        }
    }
}
