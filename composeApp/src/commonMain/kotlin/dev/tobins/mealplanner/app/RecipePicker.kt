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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSummary
import kotlinx.coroutines.launch

/**
 * The recipes to choose a meal from, in the groups the picker shows: [standard] first (issue 265), then [rotation], then
 * [others]; each recipe in one of them.
 */
data class Pickable(val rotation: List<RecipeSummary>, val others: List<RecipeSummary>, val standard: List<RecipeSummary> = emptyList()) {
    /** Every group in the order shown. */
    val all: List<RecipeSummary> get() = standard + rotation + others

    /** Whether more than one group has recipes, so that each is shown under its heading. */
    val headings: Boolean get() = listOf(standard, rotation, others).count { it.isNotEmpty() } > 1
}

/**
 * The recipes to choose a meal from: my meals that can be planned and match [query] in their title or
 * a tag. My standard meals that can take the slot ([standard], their ids) come first, then those in the rotation, and the
 * rest of mine after them (by hand any of mine can be planned, whether or not the planner would pick it); in each group
 * those marked for [slot] first (the planner would pick them for it), then by title. A recipe only in the bank is not
 * offered: it has to be added first.
 */
fun pickableRecipes(recipes: List<RecipeSummary>, slot: MealSlot, query: String, standard: Set<Long> = emptySet()): Pickable {
    val needle = query.trim()
    val matching = recipes
        .filter { it.added && it.kind == RecipeKind.MEAL && it.plannable }
        .filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) || it.tags.any { tag -> tag.contains(needle, ignoreCase = true) } }
        .sortedWith(compareBy({ slot !in it.mealSlots }, { it.title.lowercase() }))
    val (standards, planned) = matching.partition { it.id in standard }
    val (inRotation, rest) = planned.partition { it.inRotation }
    return Pickable(inRotation, rest, standards)
}

/**
 * Lets the user pick a recipe for one meal. [onPick] puts it in the plan and throws on failure; the
 * dialog stays open and shows the error then. A server with a bank also offers [onBrowseBank], for the
 * recipe that is not in my recipes yet. [standard] are the ids of my standard meals that can take the slot, listed
 * first; [note] says what choosing here does beyond the meal itself.
 */
@Composable
fun RecipePicker(
    api: ApiClient,
    slot: MealSlot,
    title: String,
    standard: Set<Long> = emptySet(),
    note: String? = null,
    onDismiss: () -> Unit,
    onBrowseBank: () -> Unit,
    onPick: suspend (RecipeSummary) -> Unit,
) {
    val t = LocalStrings.current
    var state by remember { mutableStateOf<Load<List<RecipeSummary>>>(Load.Loading) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        state = Load.Loading
        state = load { api.recipes() }
    }
    val bank = rememberBankOffered(api)
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
                    RecipePickerBody(current.value, slot, standard, note, onBrowseBank = onBrowseBank.takeIf { bank != null }) { recipe ->
                        scope.launch {
                            error = failureOf { onPick(recipe) }
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

/**
 * [note] above the search field, the field and the rows of [pickableRecipes] with my [standard] meals first, under a
 * heading each when more than one group has any, and a last row "Browse the bank…" under the list when [onBrowseBank] is
 * given; split from the dialog so a screenshot can show it.
 */
@Composable
fun RecipePickerBody(
    recipes: List<RecipeSummary>,
    slot: MealSlot,
    standard: Set<Long> = emptySet(),
    note: String? = null,
    onBrowseBank: (() -> Unit)?,
    onPick: (RecipeSummary) -> Unit,
) {
    val t = LocalStrings.current
    var query by remember { mutableStateOf("") }
    val searchField = remember { FocusRequester() }
    // In the browser typing goes to the search at once; on a phone the keyboard would hide the list, so it waits for a tap.
    if (runsInBrowser()) LaunchedEffect(Unit) { searchField.requestFocus() }
    val shown = pickableRecipes(recipes, slot, query, standard)
    val headings = shown.headings
    Column {
        note?.let { Text(it, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(t(Msg.SEARCH_RECIPES)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(searchField),
        )
        if (shown.all.isEmpty()) {
            Text(t(Msg.NO_RECIPES_MATCH), Modifier.padding(top = 16.dp))
        }
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            if (headings && shown.standard.isNotEmpty()) item(key = "heading:standard") { SectionHeader(t(Msg.PICKER_STANDARD), Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            items(shown.standard, key = { "standard:${it.id}" }) { recipe ->
                RecipeRow(recipe, onClick = { onPick(recipe) })
                HorizontalDivider()
            }
            if (headings && shown.rotation.isNotEmpty()) item(key = "heading:rotation") { SectionHeader(t(Msg.PICKER_IN_ROTATION), Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            items(shown.rotation, key = { it.id }) { recipe ->
                RecipeRow(recipe, onClick = { onPick(recipe) })
                HorizontalDivider()
            }
            if (headings && shown.others.isNotEmpty()) item(key = "heading:others") { SectionHeader(t(Msg.PICKER_OTHERS), Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            items(shown.others, key = { it.id }) { recipe ->
                RecipeRow(recipe, onClick = { onPick(recipe) })
                HorizontalDivider()
            }
        }
        // Under the list, not in it: the way to the bank is there however many recipes are above it.
        onBrowseBank?.let { TextButton(onClick = it) { Text(t(Msg.BROWSE_BANK_MORE)) } }
    }
}
