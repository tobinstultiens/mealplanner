package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.recipes.IngredientLine
import kotlinx.coroutines.launch

/** An ingredient line back in the `amount | unit | item` form the editor works with. */
private fun IngredientLine.asInputLine(): String =
    "${amount?.let(::formatAmount).orEmpty()} | ${unit.orEmpty()} | $item"

/**
 * Creates a recipe of the user's own, or edits one ([recipeId] set). Meant for simple
 * foods as much as for dishes: "200 | g | low-fat quark" is a complete breakfast.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeEditorScreen(api: ApiClient, recipeId: Long?, onDone: (Long?) -> Unit, onCancel: () -> Unit) {
    var loading by remember { mutableStateOf(recipeId != null) }
    var title by remember { mutableStateOf("") }
    var servings by remember { mutableStateOf("1") }
    var ingredients by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var slots by remember { mutableStateOf(setOf(MealSlot.BREAKFAST)) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(recipeId) {
        if (recipeId == null) return@LaunchedEffect
        when (val result = load { api.recipe(recipeId) }) {
            is Load.Ready -> with(result.value) {
                title = summary.title
                servings = (summary.servings ?: 1).toString()
                ingredients = this.ingredients.joinToString("\n") { it.line.asInputLine() }
                instructions = this.instructions
                slots = summary.mealSlots.toSet()
            }
            is Load.Failed -> snackbar.showSnackbar("Could not load the recipe: ${result.message}")
            Load.Loading -> Unit
        }
        loading = false
    }

    val servingCount = servings.trim().toIntOrNull()?.takeIf { it in 1..100 }
    val canSave = !saving && title.isNotBlank() && servingCount != null && ingredients.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (recipeId == null) "New recipe" else "Edit recipe") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (loading) {
            CenteredBox(Modifier.padding(padding)) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                servings, { servings = it },
                label = { Text("Servings") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Plan as", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MealSlot.entries.forEach { slot ->
                    FilterChip(
                        selected = slot in slots,
                        onClick = { slots = if (slot in slots) slots - slot else slots + slot },
                        label = { Text(slot.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            OutlinedTextField(
                ingredients, { ingredients = it },
                label = { Text("Ingredients") },
                supportingText = { Text("One per line: amount | unit | item, for example\n200 | g | low-fat quark\n1 | banana |") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                instructions, { instructions = it },
                label = { Text("Steps (optional)") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = canSave,
                    onClick = {
                        saving = true
                        val input = RecipeInput(title.trim(), servingCount!!, ingredients.trim(), instructions.trim(), mealSlots = slots.sortedBy { it.ordinal })
                        scope.launch {
                            when (val result = load { if (recipeId == null) api.createRecipe(input) else api.updateRecipe(recipeId, input) }) {
                                is Load.Ready -> onDone(result.value.summary.id)
                                is Load.Failed -> {
                                    saving = false
                                    snackbar.showSnackbar(
                                        if (result.status == 400) "Check the ingredient lines: each needs the form amount | unit | item"
                                        else "Could not save: ${result.message}",
                                    )
                                }
                                Load.Loading -> Unit
                            }
                        }
                    },
                ) { Text("Save") }
                if (recipeId != null) {
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            saving = true
                            scope.launch {
                                when (val result = load { api.deleteRecipe(recipeId) }) {
                                    is Load.Ready -> onDone(null)
                                    is Load.Failed -> {
                                        saving = false
                                        snackbar.showSnackbar("Could not delete: ${result.message}")
                                    }
                                    Load.Loading -> Unit
                                }
                            }
                        },
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
