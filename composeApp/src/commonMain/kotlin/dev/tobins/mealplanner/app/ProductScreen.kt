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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
 *
 * Whatever keeps Save off is said in red at the field that causes it. The keyboard's Next goes from the salt on into the
 * portions, from a portion's name to its size and on to the next portion; the last one's is Done. Saving without a
 * portion the product had asks first, as recipes with a line in it stop counting that line.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProductScreen(api: ApiClient, ingredient: Ingredient?, onDone: (Ingredient) -> Unit, onCancel: () -> Unit) {
    val t = LocalStrings.current
    val start = remember(ingredient) { ingredient?.toProductDraft(t.locale) ?: ProductDraft() }
    // Kept over a re-created activity, as what was typed.
    var draft by rememberSaveable(stateSaver = jsonSaver(ProductDraft.serializer())) { mutableStateOf(start) }
    var saving by remember { mutableStateOf(false) }
    var askingRemoval by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val leave = UnsavedChangesGuard(draft != start, onCancel)
    val nameFocus = remember { HashMap<Long, FocusRequester>() }
    val sizeFocus = remember { HashMap<Long, FocusRequester>() }
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

    /** Saves, after asking when portions the product had are gone. */
    fun saveAsked() = if (draft.removedPortions.isEmpty()) save() else askingRemoval = true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ingredient == null) t(Msg.NEW_PRODUCT) else t(Msg.EDIT_PRODUCT)) },
                navigationIcon = { BarAction(AppIcons.Close, t(Msg.CANCEL), leave) },
                actions = { TextButton(onClick = ::saveAsked, enabled = draft.canSave && !saving) { Text(t(Msg.SAVE)) } },
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
                isError = draft.name.isBlank(),
                supportingText = if (draft.name.isBlank()) ({ Text(t(Msg.PRODUCT_NAME_NEEDED)) }) else null,
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
            val firstPortion = draft.portions.firstOrNull()?.key
            LabelValue.entries.forEach { value ->
                // From the last value on into the portions, which the focus does not find by itself: it went back up.
                val next = when {
                    value != LabelValue.entries.last() -> Next.Below
                    firstPortion != null -> Next.To(nameFocus.getOrPut(firstPortion) { FocusRequester() })
                    else -> Next.Done
                }
                ValueField(draft, value, enabled, next) { draft = draft.withValue(value, it) }
            }

            Text(t(Msg.PORTIONS), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.Related))
            Text(t(Msg.PORTIONS_HINT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            draft.portions.forEachIndexed { index, row ->
                key(row.key) {
                    val following = draft.portions.getOrNull(index + 1)?.key
                    PortionField(
                        row, draft.nameProblem(row), enabled,
                        nameFocus = nameFocus.getOrPut(row.key) { FocusRequester() },
                        sizeFocus = sizeFocus.getOrPut(row.key) { FocusRequester() },
                        nextFocus = following?.let { nameFocus.getOrPut(it) { FocusRequester() } },
                        onChange = { changed -> draft = draft.changingPortion(row.key) { changed } },
                        onRemove = { draft = draft.removingPortion(row.key) },
                    )
                }
            }
            TextButton(onClick = { draft = draft.addingPortion() }, enabled = enabled) { Text(t(Msg.ADD_PORTION)) }
        }
    }

    if (askingRemoval) {
        val names = draft.removedPortions.joinToString { "“${it.name}”" }
        AlertDialog(
            onDismissRequest = { askingRemoval = false },
            title = { Text(t(Msg.REMOVE_PORTIONS_TITLE, names)) },
            text = { Text(t(Msg.REMOVE_PORTIONS_TEXT, names)) },
            confirmButton = {
                TextButton(onClick = { askingRemoval = false; save() }) { Text(t(Msg.REMOVE_AND_SAVE)) }
            },
            dismissButton = {
                TextButton(onClick = { askingRemoval = false; draft = draft.keepingRemovedPortions(t.locale) }) { Text(t(Msg.KEEP_PORTIONS)) }
            },
        )
    }
}

/** Where the keyboard's action key goes from a field: to the field below, to another one, or Done, as none follows. */
private sealed interface Next {
    data object Below : Next

    data class To(val target: FocusRequester) : Next

    data object Done : Next

    val imeAction: ImeAction get() = if (this == Done) ImeAction.Done else ImeAction.Next

    val actions: KeyboardActions get() = if (this is To) KeyboardActions(onNext = { target.requestFocus() }) else KeyboardActions.Default
}

/**
 * One value of the label, with its unit after it; "of which" values set in under the one they are part of, as on a label.
 * What is wrong with it is said under it in red, the energy too while it is needed. [next] is where the keyboard's Next goes.
 */
@Composable
private fun ValueField(draft: ProductDraft, value: LabelValue, enabled: Boolean, next: Next, onChange: (String) -> Unit) {
    val t = LocalStrings.current
    val problem = draft.valueProblem(value)
    val partOf = value == LabelValue.SATURATED_FAT || value == LabelValue.SUGARS
    OutlinedTextField(
        draft.value(value), onChange,
        label = { Text(t(value.msg)) },
        suffix = { Text(value.unit) },
        isError = problem != null,
        supportingText = problem?.let { { Text(numberProblemText(it, t(Msg.PRODUCT_NEEDS_ENERGY), t)) } },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = next.imeAction),
        keyboardActions = next.actions,
        modifier = Modifier.fillMaxWidth().padding(start = if (partOf) 24.dp else 0.dp),
    )
}

/** What is wrong with a number, as the line under its field says it; [needed] when none is given and one is needed. */
private fun numberProblemText(problem: NumberProblem, needed: String, t: Strings): String = when (problem) {
    NumberProblem.NEEDED -> needed
    NumberProblem.NOT_A_NUMBER -> t(Msg.PRODUCT_NOT_A_NUMBER)
    NumberProblem.NEGATIVE -> t(Msg.PRODUCT_NEGATIVE)
    NumberProblem.ZERO -> t(Msg.PORTION_SIZE_ZERO)
}

/**
 * One portion: its name with the way to remove it, and under it its size with the unit of the size (a tap changes grams to
 * millilitres and back). Two lines, so that a long name and a large font both fit. What is wrong with either is said under
 * it. Next goes from the name to the size and on to [nextFocus], the next portion's name; from the last portion it is Done.
 */
@Composable
private fun PortionField(
    row: PortionRow,
    problem: PortionNameProblem?,
    enabled: Boolean,
    nameFocus: FocusRequester,
    sizeFocus: FocusRequester,
    nextFocus: FocusRequester?,
    onChange: (PortionRow) -> Unit,
    onRemove: () -> Unit,
) {
    val t = LocalStrings.current
    val unit = if (row.ml) "ml" else "g"
    val next = nextFocus?.let { Next.To(it) } ?: Next.Done
    Row(verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            row.name, { onChange(row.copy(name = it)) },
            label = { Text(t(Msg.PORTION_NAME)) },
            isError = problem != null,
            supportingText = problem?.let {
                {
                    Text(
                        when (it) {
                            PortionNameProblem.NEEDED -> t(Msg.PORTION_NAME_NEEDED)
                            PortionNameProblem.TAKEN -> t(Msg.PORTION_NAME_TAKEN)
                            PortionNameProblem.IS_UNIT -> t(Msg.PORTION_NAME_IS_UNIT, row.name.trim())
                            PortionNameProblem.BAD_CHARACTER -> t(Msg.PORTION_NAME_BAD)
                        },
                    )
                }
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = Next.To(sizeFocus).actions,
            modifier = Modifier.weight(1f).focusRequester(nameFocus),
        )
        Column(Modifier.padding(top = 4.dp)) {
            BarAction(AppIcons.Close, t(Msg.REMOVE_PORTION, row.name), onRemove, enabled = enabled)
        }
    }
    Row(Modifier.padding(start = 24.dp, bottom = Spacing.Related), verticalAlignment = Alignment.Top) {
        val sizeProblem = row.sizeProblem
        OutlinedTextField(
            row.size, { onChange(row.copy(size = it)) },
            label = { Text(t(Msg.PORTION_SIZE)) },
            isError = sizeProblem != null,
            supportingText = sizeProblem?.let { { Text(numberProblemText(it, t(Msg.PORTION_SIZE_NEEDED, unit), t)) } },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = next.imeAction),
            keyboardActions = next.actions,
            modifier = Modifier.weight(1f).focusRequester(sizeFocus),
        )
        TextButton(
            onClick = { onChange(row.copy(ml = !row.ml)) },
            enabled = enabled,
            modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = t(Msg.PORTION_UNIT, unit) },
        ) { Text(unit) }
    }
}
