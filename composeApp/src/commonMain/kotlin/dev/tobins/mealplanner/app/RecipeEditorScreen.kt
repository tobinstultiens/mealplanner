package dev.tobins.mealplanner.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.nutrition.Values
import dev.tobins.mealplanner.nutrition.measured
import dev.tobins.mealplanner.nutrition.values
import dev.tobins.mealplanner.units.Dimension
import dev.tobins.mealplanner.recipes.PlainLines
import dev.tobins.mealplanner.recipes.oneLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable
import kotlin.math.roundToLong

/**
 * Writes a recipe as rows of picked ingredients (issue 342), or edits one of mine ([recipeId] set), also one taken from
 * the cooking site. An ingredient is searched among the account's own and the NEVO foods it does not have yet (picking
 * one of those adds it to the catalogue), and its row gets an amount and a unit that ingredient has; no syntax is typed.
 * The bar under the title says what a serving has, as the saved recipe will ([RecipeDraft.nutrition]). Pasted lines
 * become rows ([PlainLines]), looked up as a save looks them up (`POST /api/recipes/lines`); a row that means nothing is
 * tapped to choose its ingredient, or left as text, which it says is not counted. Tags, groups, steps, slots and how
 * long it keeps are edited here too, and what is not changed is saved as it was ([DraftRow.input]). Under what the search
 * finds, "New product" opens the product's form with the text typed (issue 354); saved, the product is a row.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeEditorScreen(api: ApiClient, recipeId: Long?, onDone: (Long?) -> Unit, onCancel: () -> Unit) {
    val t = LocalStrings.current
    // Kept over a re-created activity, with the recipe as it was loaded (or last saved) to tell what is unsaved.
    var draft by rememberSaveable(stateSaver = jsonSaver(RecipeDraft.serializer())) { mutableStateOf(RecipeDraft()) }
    var baseline by rememberSaveable(stateSaver = jsonSaver(RecipeDraft.serializer().nullable)) {
        mutableStateOf(if (recipeId == null) RecipeDraft() else null)
    }
    // A new recipe once saved: saved again (after "keeps" failed), it is changed, not made twice.
    var savedId by rememberSaveable { mutableStateOf(recipeId) }
    var catalogue by remember { mutableStateOf<Load<List<Ingredient>>>(Load.Loading) }
    var recipeFailed by remember { mutableStateOf<Load.Failed?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    // Adding an ingredient for a food, or looking pasted lines up: one at a time.
    var busy by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var openRow by rememberSaveable { mutableStateOf<Long?>(null) }
    var pasting by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var newGroup by rememberSaveable { mutableStateOf(false) }
    // The name of a new product being made from the search, whose form is open.
    var newProduct by rememberSaveable { mutableStateOf<String?>(null) }
    val rowFocus = remember { HashMap<Long, FocusRequester>() }
    var focusRow by remember { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    /** Opens a dialog with [open]: the field being typed in lets go first, so its keyboard does not stay up behind the dialog. */
    fun opening(open: () -> Unit) {
        focusManager.clearFocus()
        open()
    }

    LaunchedEffect(reload) {
        recipeFailed = null
        catalogue = load { api.ingredients() }
        // A restored draft is what was typed: the load must not replace it.
        if (recipeId == null || baseline != null) return@LaunchedEffect
        when (val result = load { api.recipe(recipeId) }) {
            is Load.Ready -> {
                draft = result.value.toDraft()
                baseline = draft
            }
            is Load.Failed -> recipeFailed = result
            Load.Loading -> Unit
        }
    }

    val ingredients = (catalogue as? Load.Ready)?.value
    val byId = remember(ingredients) { ingredients.orEmpty().associateBy { it.id } }
    val ready = ingredients != null && baseline != null
    // One that could not be loaded has nothing to lose.
    val leave = UnsavedChangesGuard(baseline != null && draft != baseline, onCancel)
    // The keyboard makes room below the column instead of sliding the window, title and totals, up under the status bar.
    KeyboardMakesRoom()

    /** The ingredient [found] is: one of the account's, or one made now for a NEVO food it did not have (null when that failed). */
    suspend fun ingredientOf(found: Found): Ingredient? = when (found) {
        is Found.Mine -> found.ingredient
        is Found.NewFood -> {
            val all = (catalogue as? Load.Ready)?.value.orEmpty()
            when (val made = load { api.createIngredient(IngredientInput(nameFor(found.food, all), nevoCode = found.food.nevoCode)) }) {
                is Load.Ready -> made.value.also { catalogue = Load.Ready(all + it) }
                is Load.Failed -> null.also { snackbar.showSnackbar(failureSentence(made, Msg.EDITOR_ADD_FAILED)) }
                Load.Loading -> null
            }
        }
    }

    fun add(found: Found) {
        if (busy) return
        busy = true
        scope.launch {
            ingredientOf(found)?.let { ingredient ->
                val key = draft.nextKey
                draft = draft.adding(ingredient)
                query = ""
                focusRow = key
            }
            busy = false
        }
    }

    fun paste(text: String) {
        val lines = PlainLines.read(text)
        if (lines.isEmpty() || busy) return
        busy = true
        scope.launch {
            // Without an answer every row is one to choose the ingredient of, which each says.
            val meanings = when (val read = load { api.readLines(lines) }) {
                is Load.Ready -> read.value.map { it.ingredientId }
                is Load.Failed -> emptyList<Long?>().also { snackbar.showSnackbar(failureSentence(read, Msg.EDITOR_LOOKUP_FAILED)) }
                Load.Loading -> emptyList()
            }
            draft = draft.pasting(lines, meanings)
            busy = false
        }
    }

    fun save() {
        if (!draft.canSave || saving) return
        saving = true
        val sent = draft
        scope.launch {
            val id = savedId
            val input = sent.input()
            when (val result = load { if (id == null) api.createRecipe(input) else api.updateRecipe(id, input) }) {
                is Load.Ready -> {
                    val saved = result.value.summary
                    savedId = saved.id
                    // How long it keeps has a route of its own, which released apps saving the recipe leave alone.
                    val keptBefore = RecipeKeeps(saved.keepsDays, saved.keepsVacuumDays)
                    if (sent.meal && sent.keeps != keptBefore && load { api.setRecipeKeeps(saved.id, sent.keeps) } is Load.Failed) {
                        // The recipe is saved; only how long it keeps is not, which the screen still shows.
                        baseline = sent.copy(keeps = keptBefore)
                        saving = false
                        snackbar.showSnackbar(t(Msg.KEEPS_FAILED))
                        return@launch
                    }
                    onDone(saved.id)
                }
                is Load.Failed -> {
                    saving = false
                    snackbar.showSnackbar(failureSentence(result, Msg.SAVE_FAILED))
                }
                Load.Loading -> Unit
            }
        }
    }

    // A new product from the search (issue 354), in place of the editor, whose draft waits as it is: saved, the product is
    // a row of it; cancelled, the editor is as it was.
    newProduct?.let { name ->
        ProductScreen(
            api, null,
            onDone = { made ->
                catalogue = Load.Ready((catalogue as? Load.Ready)?.value.orEmpty() + made)
                val key = draft.nextKey
                draft = draft.adding(made)
                query = ""
                newProduct = null
                focusRow = key
            },
            onCancel = { newProduct = null },
            name = name,
            catalogue = ingredients.orEmpty(),
        )
        return
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(if (recipeId == null) t(Msg.NEW_RECIPE) else t(Msg.EDIT_RECIPE)) },
                    navigationIcon = { BarAction(AppIcons.Close, t(Msg.CANCEL), leave) },
                    actions = {
                        TextButton(onClick = ::save, enabled = ready && draft.canSave && !saving && !busy) { Text(t(Msg.SAVE)) }
                    },
                )
                if (ready) Totals(draft.nutrition(byId), draft.servingCount != null)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val failed = recipeFailed ?: catalogue as? Load.Failed
        if (failed != null) {
            Box(Modifier.padding(padding)) {
                ErrorMessage(t(if (recipeFailed != null) Msg.RECIPE_LOAD_FAILED else Msg.INGREDIENTS_LOAD_FAILED), failed) { reload++ }
            }
            return@Scaffold
        }
        if (!ready) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        val enabled = !saving
        Column(
            // The column ends above the keyboard (its height less the navigation bar the padding has already), so what it
            // brings into view is not behind the keyboard.
            Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
            verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
        ) {
            OutlinedTextField(
                draft.title, { draft = draft.copy(title = it) },
                label = { Text(t(Msg.NAME)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                draft.servings, { draft = draft.copy(servings = it) },
                label = { Text(t(Msg.SERVINGS)) },
                isError = draft.servingCount == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(t(Msg.PLAN_AS), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                MealSlot.entries.forEach { slot ->
                    FilterChip(
                        selected = slot in draft.slots,
                        onClick = { draft = draft.copy(slots = if (slot in draft.slots) draft.slots - slot else draft.slots + slot) },
                        label = { Text(t.slot(slot, capitalised = true)) },
                    )
                }
            }

            Text(t(Msg.INGREDIENTS), style = MaterialTheme.typography.titleMedium)
            if (draft.unread.isNotEmpty()) {
                UnreadLines(draft.unread, enabled = !busy) {
                    paste(draft.unread.joinToString("\n"))
                    draft = draft.copy(unread = emptyList())
                }
            }
            if (draft.rows.isEmpty()) {
                Text(t(Msg.EDITOR_NO_ROWS), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            draft.rows.forEachIndexed { index, row ->
                key(row.key) {
                    // A heading where the group changes, as the recipe shows them; none for rows outside a group.
                    if (row.group != null && row.group != draft.rows.getOrNull(index - 1)?.group) {
                        GroupHeading(row.group, enabled) { opening { renaming = row.group } }
                    }
                    DraftRowItem(
                        row, row.ingredientId?.let(byId::get), rowFocus.getOrPut(row.key) { FocusRequester() }, enabled,
                        onChange = { changed -> draft = draft.changing(row.key) { changed } },
                        onOpen = { opening { openRow = row.key } },
                        onRemove = { draft = draft.removing(row.key) },
                    )
                    // A row just added takes the focus, so its amount is typed at once (and Next moves on from it). Asked
                    // from the row, a frame after it is laid out: after the product form's detour the whole editor is new,
                    // and asked any earlier the window gave the focus to the first field in view instead (issue 354).
                    if (focusRow == row.key) {
                        LaunchedEffect(row.key) {
                            withFrameNanos { }
                            rowFocus[row.key]?.requestFocus()
                            focusRow = null
                        }
                    }
                }
            }
            draft.addingTo?.takeIf { it != draft.rows.lastOrNull()?.group }?.let { next ->
                GroupHeading(next, enabled) { opening { renaming = next } }
                Text(t(Msg.EDITOR_NEXT_GROUP), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val (found, searchFailed) = rememberFound(api, ingredients.orEmpty(), query)
            IngredientSearch(
                query, { query = it }, found, searchFailed, enabled = enabled && !busy,
                onNewProduct = { name -> opening { newProduct = name } },
                onPick = ::add,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                TextButton(onClick = { opening { pasting = true } }, enabled = enabled && !busy) { Text(t(Msg.EDITOR_PASTE)) }
                TextButton(onClick = { opening { newGroup = true } }, enabled = enabled) { Text(t(Msg.EDITOR_NEW_GROUP)) }
            }

            // The recipe's Markdown as it is; untouched, it is saved as it was, whatever more than steps it holds.
            OutlinedTextField(
                draft.instructions, { draft = draft.copy(instructions = it) },
                label = { Text(t(Msg.STEPS_OPTIONAL)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            TagsEditor(draft.tags, enabled) { draft = draft.copy(tags = it) }
            // Only a meal is prepped, so only a meal keeps.
            if (draft.meal) KeepsSettings(draft.keeps, enabled) { draft = draft.copy(keeps = it) }
        }
    }

    openRow?.let { key -> draft.rows.firstOrNull { it.key == key } }?.let { row ->
        RowDialog(
            row, row.ingredientId?.let(byId::get), api, ingredients.orEmpty(),
            pick = ::ingredientOf,
            onDismiss = { openRow = null },
            onDone = { changed ->
                draft = draft.changing(row.key) { changed }
                openRow = null
            },
        )
    }
    if (pasting) {
        PasteDialog(onDismiss = { pasting = false }) {
            pasting = false
            paste(it)
        }
    }
    renaming?.let { from ->
        GroupDialog(t(Msg.EDITOR_RENAME_GROUP, from), from, onDismiss = { renaming = null }) {
            draft = draft.renaming(from, it)
            renaming = null
        }
    }
    if (newGroup) {
        GroupDialog(t(Msg.EDITOR_NEW_GROUP), "", onDismiss = { newGroup = false }) {
            draft = draft.copy(addingTo = it?.oneLine()?.ifEmpty { null })
            newGroup = false
        }
    }
}

/** What a serving has, as the saved recipe will have it: under the title, so it moves as the rows change. */
@Composable
private fun Totals(nutrition: RecipeNutrition?, servingsKnown: Boolean) {
    val t = LocalStrings.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Spacing.ScreenEdge, vertical = Spacing.Related)) {
            when {
                !servingsKnown -> Text(t(Msg.EDITOR_NO_SERVINGS), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                nutrition == null -> Text(t(Msg.EDITOR_NO_TOTALS), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text(t(Msg.EDITOR_PER_SERVING, nutrition.headline(t)), style = MaterialTheme.typography.titleMedium.numeric())
                    if (!nutrition.complete) {
                        Text(
                            t.plural(Plural.INCOMPLETE, nutrition.uncounted.size, nutrition.uncounted.joinToString("; ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One row: what it is and how it counts (a tap chooses its ingredient, its text and group), its amount and unit. Also the
 * food being picked for a slot of the plan (issue 358), as a food is picked as a recipe's row is.
 */
@Composable
internal fun DraftRowItem(
    row: DraftRow,
    ingredient: Ingredient?,
    focus: FocusRequester,
    enabled: Boolean,
    onChange: (DraftRow) -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val t = LocalStrings.current
    val label = row.label(ingredient).ifBlank { ingredient?.name.orEmpty() }
    val count = row.count(ingredient)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = enabled, onClick = onOpen).padding(vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    rowNote(row, ingredient, count, t),
                    style = MaterialTheme.typography.bodySmall.numeric(),
                    color = if (row.countsAsWritten(ingredient, count)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            BarAction(AppIcons.Close, t(Msg.EDITOR_REMOVE_ROW, label), onRemove, enabled = enabled)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Related), verticalAlignment = Alignment.CenterVertically) {
            AmountField(row, focus, enabled, Modifier.weight(1f)) { onChange(row.copy(amount = it)) }
            UnitChoice(row.unit, unitChoices(ingredient, row.unit), enabled) { onChange(row.inUnit(it, ingredient)) }
        }
    }
}

/**
 * Whether [this] row counts as it should: counted, an ingredient that is left out on purpose, or seasoning without an
 * amount ("salt to taste"). Any other is said in the error colour, as it makes the totals too low.
 */
private fun DraftRow.countsAsWritten(ingredient: Ingredient?, count: RowCount): Boolean = when (count) {
    RowCount.COUNTED, RowCount.NEGLIGIBLE -> true
    RowCount.NO_AMOUNT -> NutritionCalculator.isSeasoning(label(ingredient), ingredient)
    else -> false
}

/**
 * What a row says under its name: the ingredient with its weight and kcal, or why it is not counted. A volume of an
 * ingredient whose density is not known (a drink counted per 100 ml, a portion in ml) says its millilitres: its weight
 * would be a guess.
 */
internal fun rowNote(row: DraftRow, ingredient: Ingredient?, count: RowCount, t: Strings): String {
    val name = ingredient?.name.orEmpty()
    return when (count) {
        RowCount.NO_INGREDIENT -> t.onWeb(Msg.ROW_NO_INGREDIENT, Msg.ROW_NO_INGREDIENT_BROWSER)
        RowCount.NEGLIGIBLE -> t(Msg.ROW_NOT_COUNTED, name, t(Msg.NOT_COUNTED))
        RowCount.NO_AMOUNT -> t(Msg.ROW_NOT_COUNTED, name, t(Msg.ROW_NO_AMOUNT))
        RowCount.NO_NUTRITION -> t(Msg.ROW_NOT_COUNTED, name, t(Msg.ROW_NO_NUTRITION))
        RowCount.PORTION_GONE -> t(Msg.ROW_NOT_COUNTED, name, t(Msg.ROW_PORTION_GONE, row.unit.orEmpty()))
        RowCount.WEIGHT_UNKNOWN -> t(Msg.ROW_NOT_COUNTED, name, t(Msg.ROW_WEIGHT_UNKNOWN))
        RowCount.COUNTED -> {
            val line = row.line()!!
            val volume = measured(line, ingredient!!)?.takeIf { it.dimension == Dimension.VOLUME && ingredient.gramsPerMl == null }?.baseAmount
            val quantity = volume?.let { formatMillilitres(it, t.locale) } ?: formatGrams(NutritionCalculator.quantityOf(line, ingredient)!!.grams, t.locale)
            val kcal = NutritionCalculator.ofLines(listOf(row.input() to ingredient), servings = 1)?.perServing?.kcal ?: 0.0
            t(Msg.ROW_COUNTED, name, quantity, kcal.roundToLong())
        }
    }
}

/**
 * A row's amount. Its whole text is selected when it gets the focus, so that the number a picked row starts with is
 * replaced by the one typed; Next moves on to the next row's amount, and from the last to the search.
 */
@Composable
private fun AmountField(row: DraftRow, focus: FocusRequester, enabled: Boolean, modifier: Modifier, onAmount: (String) -> Unit) {
    val t = LocalStrings.current
    var field by remember(row.key) { mutableStateOf(TextFieldValue(row.amount)) }
    var focused by remember { mutableStateOf(false) }
    // Changed elsewhere (another unit converts it): the new amount, with the cursor at its end.
    val shown = if (field.text == row.amount) field else TextFieldValue(row.amount, TextRange(row.amount.length))
    OutlinedTextField(
        value = shown,
        onValueChange = {
            field = it
            if (it.text != row.amount) onAmount(it.text)
        },
        label = { Text(t(Msg.EDITOR_AMOUNT)) },
        isError = !row.amountValid,
        supportingText = if (row.amountValid) null else ({ Text(t(Msg.EDITOR_AMOUNT_UNREADABLE)) }),
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        // The whole field, label and border included, not only the line the cursor is on.
        modifier = modifier.keptInView(focused).focusRequester(focus).onFocusChanged { state ->
            focused = state.isFocused
            if (state.isFocused) field = shown.copy(selection = TextRange(0, shown.text.length))
        },
    )
}

/**
 * Brings what this marks wholly into view of the scrolling screen while [wanted]: when it becomes so, when [content]
 * changes, and each time the on-screen keyboard's height changes (as it opens, the screen's column ends higher up,
 * [KeyboardMakesRoom]), so that it ends above the keyboard rather than behind it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.keptInView(wanted: Boolean, content: Any? = null): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(wanted, content, keyboard) { if (wanted) requester.bringIntoView() }
    return bringIntoViewRequester(requester)
}

/** The row's unit, a button that opens the units the ingredient has ([unitChoices]). */
@Composable
private fun UnitChoice(unit: String?, choices: List<String>, enabled: Boolean, onChoose: (String) -> Unit) {
    val t = LocalStrings.current
    Box {
        var open by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { open = true }, enabled = enabled) { Text(unit?.let { unitLabel(it, t) } ?: t(Msg.EDITOR_UNIT)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(unitLabel(choice, t)) }, onClick = { open = false; if (choice != unit) onChoose(choice) })
            }
        }
    }
}

/** A unit as the app's language writes it: the table's keys that differ in Dutch (`el` for `tbsp`), any other as it is. */
internal fun unitLabel(unit: String, t: Strings): String = when (unit) {
    PIECE -> t(Msg.UNIT_PIECE)
    "tbsp" -> t(Msg.UNIT_TBSP)
    "tsp" -> t(Msg.UNIT_TSP)
    "clove" -> t(Msg.UNIT_CLOVE)
    "cup" -> t(Msg.UNIT_CUP)
    else -> unit
}

/** A group's heading, with the pencil that renames it. */
@Composable
private fun GroupHeading(name: String, enabled: Boolean, onRename: () -> Unit) {
    val t = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(name, Modifier.weight(1f))
        BarAction(AppIcons.Edit, t(Msg.EDITOR_RENAME_GROUP, name), onRename, enabled = enabled)
    }
}

/** The lines of a recipe of the cooking site that the site could not read, which would be lost, with the way to make them rows. */
@Composable
private fun UnreadLines(lines: List<String>, enabled: Boolean, onAdd: () -> Unit) {
    val t = LocalStrings.current
    Column {
        Text(t(Msg.EDITOR_UNREAD, lines.joinToString(" / ") { "“$it”" }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onAdd, enabled = enabled) { Text(t(Msg.EDITOR_ADD_ROWS)) }
    }
}

/**
 * What a search for [query] finds ([found]), with the NEVO foods asked of the server after a pause in typing; and the
 * failure of that request, if it failed. The account's own ingredients are found at once.
 */
@Composable
internal fun rememberFound(api: ApiClient, catalogue: List<Ingredient>, query: String): Pair<List<Found>, Load.Failed?> {
    var foods by remember { mutableStateOf<List<Food>>(emptyList()) }
    var failed by remember { mutableStateOf<Load.Failed?>(null) }
    LaunchedEffect(query) {
        val text = query.trim()
        if (text.length < 2) {
            foods = emptyList()
            failed = null
            return@LaunchedEffect
        }
        delay(300) // a pause in typing, not every key
        when (val result = load { api.searchFoods(text) }) {
            is Load.Ready -> {
                foods = result.value
                failed = null
            }
            is Load.Failed -> failed = result
            Load.Loading -> Unit
        }
    }
    return found(catalogue, foods, query) to failed
}

/**
 * The search field for an ingredient to add, and under it what it found ([found]), each picked with a tap. With
 * [onNewProduct], the last row makes a new product named as typed (issue 354): what was meant may be found by nothing.
 */
@Composable
internal fun IngredientSearch(
    query: String,
    onQuery: (String) -> Unit,
    found: List<Found>,
    failed: Load.Failed?,
    enabled: Boolean,
    onNewProduct: ((String) -> Unit)? = null,
    onPick: (Found) -> Unit,
) {
    val t = LocalStrings.current
    var typing by remember { mutableStateOf(false) }
    // What it found shows below the field, where the keyboard would cover it on a phone: brought into view as it comes.
    Column(Modifier.keptInView(typing && found.isNotEmpty(), found)) {
        OutlinedTextField(
            query, onQuery,
            label = { Text(t(Msg.EDITOR_ADD_INGREDIENT)) },
            supportingText = { Text(t(Msg.EDITOR_SEARCH_HINT)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused },
        )
        failed?.let { Text(failureSentence(it, Msg.EDITOR_SEARCH_FAILED), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (query.trim().length >= 2 && found.isEmpty() && failed == null) {
            Text(t(Msg.EDITOR_NOTHING_FOUND), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        found.forEach { FoundRow(it, enabled) { onPick(it) } }
        val name = query.trim()
        if (onNewProduct != null && name.length >= 2) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) { onNewProduct(name) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Related),
            ) {
                Text(t(Msg.NEW_PRODUCT_NAMED, name), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** One thing a search found: its name, and what 100 g or 100 ml of it has; a NEVO food says it is new to the account. */
@Composable
private fun FoundRow(found: Found, enabled: Boolean, onPick: () -> Unit) {
    val t = LocalStrings.current
    val (name, counted) = when (found) {
        is Found.Mine -> found.ingredient.name to found.ingredient.values
        is Found.NewFood -> found.food.let { (if (t.language == Language.DUTCH) it.nameNl else it.nameEn) to Values(it.basis, it.per100) }
    }
    // Open Food Facts' values are credited wherever they are shown, as its licence asks.
    val credit = "Open Food Facts".takeIf { found is Found.Mine && fromOpenFoodFacts(found.ingredient.label?.source) }
    val values = listOfNotNull(counted?.let { per100Text(it, t) } ?: t(Msg.FOUND_NO_NUTRITION), credit).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onPick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Related),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (found is Found.NewFood) "${t(Msg.FOUND_NEW)} · $values" else values,
                style = MaterialTheme.typography.bodySmall.numeric(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/** `165 kcal · 31 g protein per 100 g`, or per 100 ml for what is counted so: the values of a label or of a NEVO food. */
private fun per100Text(values: Values, t: Strings): String =
    t(Msg.FOUND_PER_100, (values.per100.kcal ?: 0.0).roundToLong(), (values.per100.proteinG ?: 0.0).roundToLong(), per100(values.basis))

/** `100 g`, or `100 ml` for what is counted per volume. */
internal fun per100(basis: FoodBasis): String = if (basis == FoodBasis.PER_100_ML) "100 ml" else "100 g"

/**
 * A row opened: its ingredient (searched and picked as below the rows; the search starts with the row's text when it has
 * none), the text the recipe shows and the group it is in. [pick] makes a picked NEVO food an ingredient of the account,
 * only when the row is saved: a dialog cancelled leaves the account's ingredients as they were.
 */
@Composable
private fun RowDialog(
    row: DraftRow,
    ingredient: Ingredient?,
    api: ApiClient,
    catalogue: List<Ingredient>,
    pick: suspend (Found) -> Ingredient?,
    onDismiss: () -> Unit,
    onDone: (DraftRow) -> Unit,
) {
    val t = LocalStrings.current
    var chosen by remember { mutableStateOf(ingredient) }
    // A NEVO food picked and not yet an ingredient: it becomes one when the row is saved.
    var chosenFood by remember { mutableStateOf<Found.NewFood?>(null) }
    val chosenName = chosenFood?.let { nameFor(it.food, catalogue) } ?: chosen?.name
    var text by rememberSaveable { mutableStateOf(row.text) }
    var group by rememberSaveable { mutableStateOf(row.group.orEmpty()) }
    var query by rememberSaveable { mutableStateOf(if (ingredient == null) row.label() else "") }
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val (found, failed) = rememberFound(api, catalogue, query)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Msg.EDITOR_CHOOSE_INGREDIENT)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                Text(chosenName?.let { t(Msg.EDITOR_CHOSEN, it) } ?: t(Msg.EDITOR_NONE_CHOSEN), style = MaterialTheme.typography.bodyMedium)
                IngredientSearch(query, { query = it }, found, failed, enabled = !picking) { picked ->
                    // A row named after its ingredient is named after the new one; a text of the recipe's own stays.
                    val name = when (picked) {
                        is Found.Mine -> picked.ingredient.name
                        is Found.NewFood -> nameFor(picked.food, catalogue)
                    }
                    if (text.isBlank() || text == chosenName) text = name
                    chosen = (picked as? Found.Mine)?.ingredient
                    chosenFood = picked as? Found.NewFood
                    query = ""
                }
                OutlinedTextField(text, { text = it }, label = { Text(t(Msg.EDITOR_ROW_TEXT)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    group, { group = it },
                    label = { Text(t(Msg.EDITOR_GROUP)) },
                    supportingText = { Text(t(Msg.EDITOR_GROUP_HINT)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !picking, onClick = {
                fun done(ingredientId: Long?) = onDone(row.copy(ingredientId = ingredientId, text = text, group = group.oneLine().ifEmpty { null }))
                val food = chosenFood
                if (food == null) {
                    done(chosen?.id)
                } else {
                    picking = true
                    scope.launch {
                        // Not made (the editor says so) leaves the dialog open to try again.
                        pick(food)?.let { done(it.id) }
                        picking = false
                    }
                }
            }) { Text(t(Msg.SAVE)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}

/**
 * Asks for ingredient lines to paste, one per line. In the browser the field has the focus, so a paste goes there at
 * once; on a phone the paste is a long press in the field, which opens the keyboard there and then.
 */
@Composable
private fun PasteDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val t = LocalStrings.current
    var text by rememberSaveable { mutableStateOf("") }
    val field = remember { FocusRequester() }
    if (runsInBrowser()) LaunchedEffect(Unit) { field.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Msg.EDITOR_PASTE_TITLE)) },
        text = {
            OutlinedTextField(
                text, { text = it },
                label = { Text(t(Msg.EDITOR_PASTE_LINES)) },
                supportingText = { Text(t(Msg.EDITOR_PASTE_HINT)) },
                minLines = 5,
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onAdd(text) }) { Text(t(Msg.EDITOR_ADD_ROWS)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}

/** Names a group, under [title], starting from [initial]; an empty name is no group. */
@Composable
private fun GroupDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    val t = LocalStrings.current
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { name = it },
                label = { Text(t(Msg.EDITOR_GROUP)) },
                supportingText = { Text(t(Msg.EDITOR_GROUP_HINT)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(name.oneLine().ifEmpty { null }) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.oneLine().ifEmpty { null }) }) { Text(t(Msg.SAVE)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}

/** The recipe's tags: a tap on one takes it off, and the field adds one with Enter or the button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsEditor(tags: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    val t = LocalStrings.current
    var text by rememberSaveable { mutableStateOf("") }
    fun add() {
        val tag = text.oneLine()
        if (tag.isNotEmpty() && tag !in tags) onChange(tags + tag)
        text = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Text(t(Msg.EDITOR_TAGS), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { onChange(tags - tag) },
                    enabled = enabled,
                    label = { Text(tag) },
                    trailingIcon = { Icon(AppIcons.Close, t(Msg.EDITOR_REMOVE_TAG, tag), Modifier.size(18.dp)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it },
                label = { Text(t(Msg.EDITOR_ADD_TAG)) },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = ::add, enabled = enabled && text.isNotBlank()) { Icon(AppIcons.Add, t(Msg.EDITOR_ADD_TAG)) }
        }
    }
}
