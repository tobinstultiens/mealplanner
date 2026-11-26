package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import kotlinx.coroutines.launch

/**
 * A product of the account's own (issue 351), new when [ingredient] is null: its name, the values of its label per 100 g
 * or per 100 ml as the label gives them (salt as salt, energy in kcal; a value left empty is unknown), and its named
 * portions, each a unit of it in the recipe editor. Numbers are typed with a decimal comma or point. [onDone] gets the
 * product as saved; leaving with changes asks first ([UnsavedChangesGuard]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProductScreen(api: ApiClient, ingredient: Ingredient?, onDone: (Ingredient) -> Unit, onCancel: () -> Unit) {
    val t = LocalStrings.current
    val start = remember(ingredient) { ingredient?.toProductDraft(t.locale) ?: ProductDraft() }
    // Kept over a re-created activity, as what was typed.
    var draft by rememberSaveable(stateSaver = jsonSaver(ProductDraft.serializer())) { mutableStateOf(start) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val leave = UnsavedChangesGuard(draft != start, onCancel)
    KeyboardMakesRoom()

    fun save() {
        if (!draft.canSave || saving) return
        saving = true
        val input = draft.input()
        scope.launch {
            when (val result = load { ingredient?.let { api.updateIngredient(it.id, input) } ?: api.createIngredient(input) }) {
                is Load.Ready -> onDone(result.value)
                is Load.Failed -> {
                    saving = false
                    snackbar.showSnackbar(failureSentence(result, Msg.SAVE_FAILED))
                }
                Load.Loading -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ingredient == null) t(Msg.NEW_PRODUCT) else t(Msg.EDIT_PRODUCT)) },
                navigationIcon = { BarAction(AppIcons.Close, t(Msg.CANCEL), leave) },
                actions = { TextButton(onClick = ::save, enabled = draft.canSave && !saving) { Text(t(Msg.SAVE)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val enabled = !saving
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
            verticalArrangement = Arrangement.spacedBy(Spacing.Related),
        ) {
            OutlinedTextField(
                draft.name, { draft = draft.copy(name = it) },
                label = { Text(t(Msg.NAME)) },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            ingredient?.label?.source?.let {
                Text(t(Msg.PRODUCT_VALUES_FROM, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(t(Msg.PRODUCT_VALUES_PER), style = MaterialTheme.typography.labelLarge)
                FilterChip(selected = !draft.perMl, onClick = { draft = draft.copy(perMl = false) }, label = { Text(per100(FoodBasis.PER_100_G)) }, enabled = enabled)
                FilterChip(selected = draft.perMl, onClick = { draft = draft.copy(perMl = true) }, label = { Text(per100(FoodBasis.PER_100_ML)) }, enabled = enabled)
            }
            Text(t(Msg.PRODUCT_VALUES_HINT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LabelValue.entries.forEach { value ->
                ValueField(draft, value, enabled) { draft = draft.withValue(value, it) }
            }

            Text(t(Msg.PORTIONS), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.Related))
            Text(t(Msg.PORTIONS_HINT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            draft.portions.forEach { row ->
                key(row.key) {
                    PortionField(
                        row, draft.nameProblem(row), enabled,
                        onChange = { changed -> draft = draft.changingPortion(row.key) { changed } },
                        onRemove = { draft = draft.removingPortion(row.key) },
                    )
                }
            }
            TextButton(onClick = { draft = draft.addingPortion() }, enabled = enabled) { Text(t(Msg.ADD_PORTION)) }
        }
    }
}

/**
 * One value of the label, with its unit after it; "of which" values set in under the one they are part of, as on a label.
 * The energy says it is needed while it is.
 */
@Composable
private fun ValueField(draft: ProductDraft, value: LabelValue, enabled: Boolean, onChange: (String) -> Unit) {
    val t = LocalStrings.current
    val valid = draft.valueValid(value)
    val needed = value == LabelValue.ENERGY && draft.needsEnergy && valid
    val partOf = value == LabelValue.SATURATED_FAT || value == LabelValue.SUGARS
    OutlinedTextField(
        draft.value(value), onChange,
        label = { Text(t(value.msg)) },
        suffix = { Text(value.unit) },
        isError = !valid,
        supportingText = when {
            !valid -> ({ Text(t(Msg.PRODUCT_NOT_A_NUMBER)) })
            needed -> ({ Text(t(Msg.PRODUCT_NEEDS_ENERGY)) })
            else -> null
        },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().padding(start = if (partOf) 24.dp else 0.dp),
    )
}

/**
 * One portion: its name with the way to remove it, and under it its size with the unit of the size (a tap changes grams to
 * millilitres and back). Two lines, so that a long name and a large font both fit.
 */
@Composable
private fun PortionField(row: PortionRow, problem: PortionNameProblem?, enabled: Boolean, onChange: (PortionRow) -> Unit, onRemove: () -> Unit) {
    val t = LocalStrings.current
    val unit = if (row.ml) "ml" else "g"
    Row(verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            row.name, { onChange(row.copy(name = it)) },
            label = { Text(t(Msg.PORTION_NAME)) },
            isError = problem != null,
            supportingText = problem?.let {
                {
                    Text(
                        when (it) {
                            PortionNameProblem.TAKEN -> t(Msg.PORTION_NAME_TAKEN)
                            PortionNameProblem.IS_UNIT -> t(Msg.PORTION_NAME_IS_UNIT)
                            PortionNameProblem.BAD_CHARACTER -> t(Msg.PORTION_NAME_BAD)
                        },
                    )
                }
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f),
        )
        Column(Modifier.padding(top = 4.dp)) {
            BarAction(AppIcons.Close, t(Msg.REMOVE_PORTION, row.name), onRemove, enabled = enabled)
        }
    }
    Row(Modifier.padding(start = 24.dp, bottom = Spacing.Related), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            row.size, { onChange(row.copy(size = it)) },
            label = { Text(t(Msg.PORTION_SIZE)) },
            isError = row.size.isNotBlank() && row.sizeValue == null,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onChange(row.copy(ml = !row.ml)) },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = t(Msg.PORTION_UNIT, unit) },
        ) { Text(unit) }
    }
}
