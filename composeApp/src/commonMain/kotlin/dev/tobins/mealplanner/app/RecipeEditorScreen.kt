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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
    "${amount?.let { formatAmount(it, FormatLocale.English) }.orEmpty()} | ${unit.orEmpty()} | $item"

/** The recipe editor's fields as typed. The defaults are the empty form of a new recipe. */
private data class RecipeForm(
    val title: String = "",
    val servings: String = "1",
    val ingredients: String = "",
    val instructions: String = "",
    val slots: Set<MealSlot> = setOf(MealSlot.BREAKFAST),
) {
    companion object {
        private fun RecipeForm.fields() = listOf(title, servings, ingredients, instructions, slots.joinToString(",") { it.name })

        private fun fromFields(v: List<String>) = RecipeForm(
            v[0], v[1], v[2], v[3],
            v[4].split(',').filter { it.isNotEmpty() }.map { MealSlot.valueOf(it) }.toSet(),
        )

        val Saver = listSaver<RecipeForm, String>(save = { it.fields() }, restore = ::fromFields)

        /** For the form as loaded, which is not there before the load is done (or ever, for a new recipe). */
        val OptionalSaver = listSaver<RecipeForm?, String>(
            save = { it?.fields().orEmpty() },
            restore = { if (it.isEmpty()) null else fromFields(it) },
        )
    }
}

/**
 * Creates a recipe of the user's own, or edits one ([recipeId] set). Meant for simple
 * foods as much as for dishes: "200 | g | low-fat quark" is a complete breakfast.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeEditorScreen(api: ApiClient, recipeId: Long?, onDone: (Long?) -> Unit, onCancel: () -> Unit) {
    val t = LocalStrings.current
    // Kept over a re-created activity, with the form as it was loaded to tell what is unsaved.
    var form by rememberSaveable(stateSaver = RecipeForm.Saver) { mutableStateOf(RecipeForm()) }
    var baseline by rememberSaveable(stateSaver = RecipeForm.OptionalSaver) { mutableStateOf<RecipeForm?>(null) }
    var loading by remember { mutableStateOf(recipeId != null && baseline == null) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(recipeId) {
        // A restored form is what was typed: the load must not replace it.
        if (recipeId == null || baseline != null) return@LaunchedEffect
        when (val result = load { api.recipe(recipeId) }) {
            is Load.Ready -> with(result.value) {
                form = RecipeForm(
                    title = summary.title,
                    servings = (summary.servings ?: 1).toString(),
                    ingredients = this.ingredients.joinToString("\n") { it.line.asInputLine() },
                    instructions = this.instructions,
                    slots = summary.mealSlots.toSet(),
                )
                baseline = form
            }
            is Load.Failed -> snackbar.showSnackbar(t(Msg.EDITOR_LOAD_FAILED, result.message))
            Load.Loading -> Unit
        }
        loading = false
    }

    // A new recipe is unsaved as soon as anything is typed; one that could not be loaded has nothing to lose.
    val leave = UnsavedChangesGuard(form != (baseline ?: RecipeForm()), onCancel)
    val servingCount = form.servings.trim().toIntOrNull()?.takeIf { it in 1..100 }
    val canSave = !saving && form.title.isNotBlank() && servingCount != null && form.ingredients.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (recipeId == null) t(Msg.NEW_RECIPE) else t(Msg.EDIT_RECIPE)) },
                navigationIcon = { BarAction(AppIcons.Close, t(Msg.CANCEL), leave) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (loading) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
            verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
        ) {
            OutlinedTextField(form.title, { form = form.copy(title = it) }, label = { Text(t(Msg.NAME)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                form.servings, { form = form.copy(servings = it) },
                label = { Text(t(Msg.SERVINGS)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(t(Msg.PLAN_AS), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                MealSlot.entries.forEach { slot ->
                    FilterChip(
                        selected = slot in form.slots,
                        onClick = { form = form.copy(slots = if (slot in form.slots) form.slots - slot else form.slots + slot) },
                        label = { Text(t.slot(slot, capitalised = true)) },
                    )
                }
            }
            OutlinedTextField(
                form.ingredients, { form = form.copy(ingredients = it) },
                label = { Text(t(Msg.INGREDIENTS)) },
                supportingText = { Text(t(Msg.INGREDIENTS_HINT)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                form.instructions, { form = form.copy(instructions = it) },
                label = { Text(t(Msg.STEPS_OPTIONAL)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.BetweenCards)) {
                Button(
                    enabled = canSave,
                    onClick = {
                        saving = true
                        val input = RecipeInput(form.title.trim(), servingCount!!, form.ingredients.trim(), form.instructions.trim(), mealSlots = form.slots.sortedBy { it.ordinal })
                        scope.launch {
                            when (val result = load { if (recipeId == null) api.createRecipe(input) else api.updateRecipe(recipeId, input) }) {
                                is Load.Ready -> onDone(result.value.summary.id)
                                is Load.Failed -> {
                                    saving = false
                                    snackbar.showSnackbar(
                                        if (result.status == 400) t(Msg.CHECK_LINES) else t(Msg.SAVE_FAILED, result.message),
                                    )
                                }
                                Load.Loading -> Unit
                            }
                        }
                    },
                ) { Text(t(Msg.SAVE)) }
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
                                        snackbar.showSnackbar(t(Msg.DELETE_FAILED, result.message))
                                    }
                                    Load.Loading -> Unit
                                }
                            }
                        },
                    ) { Text(t(Msg.DELETE), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
