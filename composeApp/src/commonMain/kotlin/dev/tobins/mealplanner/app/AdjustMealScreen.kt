package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MealLine
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What was in one planned meal (issue 226): its recipe's ingredients for its portions, each of which can
 * be changed or left out, and ingredients of the catalogue added to it; the meal then counts that, on
 * Today and in the Plan. Every edit is saved at once and can be undone from its snackbar; "Reset to
 * recipe" takes every change away. The recipe itself is never changed.
 */
@Composable
fun AdjustMealScreen(api: ApiClient, place: MealPlace, onBack: () -> Unit) {
    val t = LocalStrings.current
    val loaded = rememberLoad<MealLines>("lines:${place.weekStart}:${place.day}:${place.slot}")
    var state by loaded
    var reload by remember { mutableIntStateOf(0) }
    val frontReturns = rememberFrontReturns()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val saving = remember { Mutex() }
    // The line whose amount is being changed, and the ingredient being added once one is picked.
    var editing by remember { mutableStateOf<MealLine?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf<Ingredient?>(null) }

    LaunchedEffect(loaded, reload, frontReturns) { loaded.refresh { api.mealLines(place.weekStart, place.day, place.slot) } }

    /**
     * Saves the changes [edit] makes of the meal as it is when its turn comes, and shows the meal as it is
     * then. Saves go one at a time, each from the answer to the one before, so none overwrites another.
     * [done] is said with an Undo that takes back this edit only: what it touched goes back as it was,
     * and an edit made since stays.
     */
    fun save(done: String? = null, edit: (MealLines) -> List<MealLineChange>) {
        scope.launch {
            var previous = emptyMap<Long, MealLineChange?>()
            val result = saving.withLock {
                val meal = (state as? Load.Ready)?.value ?: return@launch
                val changes = edit(meal)
                previous = meal.before(touched(meal.changes(), changes))
                busy = true
                load { api.adjustMeal(place.weekStart, place.day, place.slot, changes) }.also {
                    if (it is Load.Ready) state = it
                    busy = false
                }
            }
            when (result) {
                is Load.Ready -> if (done != null && snackbar.showSnackbar(done, t(Msg.UNDO), duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    save { it.undoing(previous) }
                }
                is Load.Failed -> snackbar.showSnackbar(failureSentence(result))
                Load.Loading -> Unit
            }
        }
    }

    SubScreen(t(Msg.ADJUST_TITLE), onBack, snackbar, notice = { RefreshNotice(loaded, aboveSystemBar = true) { reload++ } }) { padding ->
        when (val current = state) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Column(Modifier.padding(padding)) { ErrorMessage(t(Msg.ADJUST_LOAD_FAILED), current) { reload++ } }
            is Load.Ready -> {
                val meal = current.value
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.ScreenEdge),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Related),
                ) {
                    item { MealHeader(meal, enabled = !busy, onReset = { save(t(Msg.ADJUST_RESET_DONE)) { emptyList() } }) }
                    item { SectionHeader(t(Msg.ADJUST_IN_MEAL)) }
                    items(meal.lines, key = { "${it.ingredientId}:${it.name}" }) { line ->
                        LineRow(
                            line,
                            enabled = !busy,
                            onEdit = { editing = line },
                            onLeaveOut = {
                                val done = if (line.fromRecipe) Msg.ADJUST_LEFT_OUT_DONE else Msg.ADJUST_REMOVED_DONE
                                save(t(done, line.name)) { it.leavingOut(line) }
                            },
                            onPutBack = { save { it.asInRecipe(line) } },
                        )
                    }
                    item {
                        Button(enabled = !busy, onClick = { picking = true }, modifier = Modifier.padding(top = Spacing.Related)) { Text(t(Msg.ADJUST_ADD)) }
                    }
                }
                editing?.let { line ->
                    AmountDialog(line.name, line.gramsPerPiece, line.grams, onDismiss = { editing = null }) { grams ->
                        editing = null
                        save { it.withAmount(line.ingredientId!!, grams) }
                    }
                }
                if (picking) {
                    IngredientPicker(api, inMeal = meal.lines.mapNotNullTo(HashSet()) { it.ingredientId }, onDismiss = { picking = false }) { ingredient ->
                        picking = false
                        adding = ingredient
                    }
                }
                adding?.let { ingredient ->
                    // Prefilled with one piece when the catalogue knows its weight: "1 egg, 60 g".
                    AmountDialog(ingredient.name, ingredient.gramsPerPiece, ingredient.gramsPerPiece, onDismiss = { adding = null }) { grams ->
                        adding = null
                        save { it.withAmount(ingredient.id, grams) }
                    }
                }
            }
        }
    }
}

/** The meal's title and day, what it counts now, what is not counted, and the way back to its recipe. */
@Composable
private fun MealHeader(meal: MealLines, enabled: Boolean, onReset: () -> Unit) {
    val t = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${t.slot(meal.slot, capitalised = true)} · ${dayLabel(plusDays(meal.weekStart, meal.day), t.locale)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(meal.title, style = MaterialTheme.typography.titleMedium)
        Text(
            "${unbrokenParts(servingsText(meal.portions, t))} · ${macrosText(meal.kcal, meal.proteinG, meal.fibreG, t)}",
            style = MaterialTheme.typography.bodyMedium.numeric(),
        )
        if (!meal.complete) {
            Text(t(Msg.ADJUST_INCOMPLETE, meal.uncounted.joinToString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(t(Msg.ADJUST_EXPLAIN), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (meal.adjusted) OutlinedButton(enabled = enabled, onClick = onReset) { Text(t(Msg.ADJUST_RESET)) }
    }
}

/** One ingredient: tapped, its amount changes; the cross leaves it out; a line left out can be put back. */
@Composable
private fun LineRow(line: MealLine, enabled: Boolean, onEdit: () -> Unit, onLeaveOut: () -> Unit, onPutBack: () -> Unit) {
    val t = LocalStrings.current
    val changeable = line.ingredientId != null
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled && changeable && !line.leftOut, onClick = onEdit).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Related),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                line.name,
                textDecoration = if (line.leftOut) TextDecoration.LineThrough else null,
                color = if (changeable && !line.leftOut) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(line.note(t), style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            !changeable -> Unit
            line.leftOut -> TextButton(enabled = enabled, onClick = onPutBack) { Text(t(Msg.ADJUST_PUT_BACK)) }
            else -> BarAction(AppIcons.Close, t(Msg.ADJUST_LEAVE_OUT, line.name), onClick = onLeaveOut, enabled = enabled)
        }
    }
}

/**
 * Asks how much of [name] was eaten, starting from [grams]: in pieces when the catalogue knows the
 * weight of one ([gramsPerPiece]; halves allowed), else in grams. Left as it was, it answers [grams]
 * itself, so a piece count rounded for the field does not count as a change.
 */
@Composable
internal fun AmountDialog(name: String, gramsPerPiece: Double?, grams: Double?, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    val t = LocalStrings.current
    val startInPieces = gramsPerPiece != null
    val startText = remember { amountFieldText(grams, startInPieces, gramsPerPiece) }
    var inPieces by remember { mutableStateOf(startInPieces) }
    var text by remember { mutableStateOf(startText) }
    val entered = gramsEntered(text, inPieces, gramsPerPiece)

    /** Shows the same amount in the other unit. */
    fun switchTo(pieces: Boolean) {
        if (pieces == inPieces) return
        text = entered?.let { amountFieldText(it, pieces, gramsPerPiece) } ?: text
        inPieces = pieces
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Msg.AMOUNT_OF, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                if (gramsPerPiece != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                        FilterChip(selected = inPieces, onClick = { switchTo(true) }, label = { Text(t(Msg.AMOUNT_PIECES)) })
                        FilterChip(selected = !inPieces, onClick = { switchTo(false) }, label = { Text(t(Msg.AMOUNT_GRAMS)) })
                    }
                }
                OutlinedTextField(
                    text, { text = it },
                    label = { Text(if (inPieces) t(Msg.AMOUNT_PIECES) else t(Msg.AMOUNT_GRAMS)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (inPieces && entered != null) {
                    Text("= ${formatGrams(entered, t.locale)}", style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = entered != null, onClick = {
                val unchanged = text == startText && inPieces == startInPieces && grams != null
                onConfirm(if (unchanged) grams!! else entered!!)
            }) { Text(t(Msg.SAVE)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}

/** Searches the account's ingredients for one to add; a failure to load them is said in place of the list. */
@Composable
private fun IngredientPicker(api: ApiClient, inMeal: Set<Long>, onDismiss: () -> Unit, onPick: (Ingredient) -> Unit) {
    val t = LocalStrings.current
    var catalogue by remember { mutableStateOf<Load<List<Ingredient>>>(Load.Loading) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { catalogue = load { api.ingredients() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Msg.ADJUST_ADD)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                OutlinedTextField(query, { query = it }, label = { Text(t(Msg.SEARCH_INGREDIENTS)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                when (val all = catalogue) {
                    Load.Loading -> Text(t(Msg.LOADING))
                    is Load.Failed -> Text(failureSentence(all), color = MaterialTheme.colorScheme.error)
                    is Load.Ready -> {
                        val shown = ingredientsMatching(all.value, query, inMeal)
                        if (shown.isEmpty()) Text(t(Msg.ADJUST_NO_MATCH), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(shown, key = { it.id }) { ingredient ->
                                Text(
                                    ingredient.name,
                                    Modifier.fillMaxWidth().clickable { onPick(ingredient) }.padding(vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}
