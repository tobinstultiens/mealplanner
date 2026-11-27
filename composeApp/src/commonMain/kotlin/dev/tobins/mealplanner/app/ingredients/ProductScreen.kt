package dev.tobins.mealplanner.app.ingredients

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.ProductHit
import dev.tobins.mealplanner.api.ProductLookup
import dev.tobins.mealplanner.api.ProductSearch
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.KeyboardMakesRoom
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.createIngredient
import dev.tobins.mealplanner.app.plan.jsonSaver
import dev.tobins.mealplanner.app.recipes.Found
import dev.tobins.mealplanner.app.recipes.per100
import dev.tobins.mealplanner.app.ui.AppIcons
import dev.tobins.mealplanner.app.ui.BarAction
import dev.tobins.mealplanner.app.ui.CommonMsg
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.Spacing
import dev.tobins.mealplanner.app.ui.UnsavedChangesGuard
import dev.tobins.mealplanner.app.ui.failureSentence
import dev.tobins.mealplanner.app.ui.inlineMarkdown
import dev.tobins.mealplanner.app.ui.load
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable

/**
 * A product of the account's own (issue 351), new when [ingredient] is null: its name, the values of its label per 100 g
 * or per 100 ml as the label gives them (salt as salt, energy in kcal; a value left empty is unknown), and its named
 * portions, each a unit of it in the recipe editor. Numbers are typed with a decimal comma or point. [onDone] gets the
 * product as saved; leaving with changes asks first ([UnsavedChangesGuard]).
 *
 * Whatever keeps Save off is said in red at the field that causes it. The keyboard's Next goes from the salt on into the
 * portions, from a portion's name to its size and on to the next portion; the last one's is Done. Saving without a
 * portion the product had asks first, as recipes with a line in it stop counting that line.
 *
 * At its top a product is looked up (issue 354): a name is searched at AH and one of what it finds picked, a barcode is
 * looked up at once (at Open Food Facts when AH cannot answer it). That fills the form, with its source shown and its pack
 * as a portion, and says what the source did not give or could not answer; nothing is saved until Save. A new product
 * starts with [name], in its name and in the lookup's field. A name that another ingredient of [catalogue] has is said at
 * the name, as the server would refuse it.
 *
 * Where there is a [scanner] (the Android app on a phone with a camera) the lookup has a scan button: the barcode the camera
 * reads is looked up as if it had been typed. A refused camera permission is said in a line under it, and typing stays possible.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProductScreen(
    api: ApiClient,
    ingredient: Ingredient?,
    onDone: (Ingredient) -> Unit,
    onCancel: () -> Unit,
    name: String = "",
    catalogue: List<Ingredient> = emptyList(),
    scanner: BarcodeScanner? = rememberBarcodeScanner(),
) {
    val t = LocalStrings.current
    val start = remember(ingredient) { ingredient?.toProductDraft(t.locale) ?: ProductDraft(name = name) }
    // Kept over a re-created activity, as what was typed.
    var draft by rememberSaveable(stateSaver = jsonSaver(ProductDraft.serializer())) { mutableStateOf(start) }
    var saving by remember { mutableStateOf(false) }
    var askingRemoval by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val leave = UnsavedChangesGuard(draft != start, onCancel)
    val nameFocus = remember { HashMap<Long, FocusRequester>() }
    val sizeFocus = remember { HashMap<Long, FocusRequester>() }
    var query by rememberSaveable { mutableStateOf(name) }
    var lookup by remember { mutableStateOf<LookupState>(LookupState.Idle) }
    // Why the last scan gave no barcode, said under the lookup until the next lookup or scan.
    var scanProblem by remember { mutableStateOf<ScanResult?>(null) }
    // What the last lookup that filled the form said, kept with the values it filled in.
    var notes by rememberSaveable(stateSaver = jsonSaver(ProductLookup.serializer().nullable)) { mutableStateOf<ProductLookup?>(null) }
    KeyboardMakesRoom()

    /** Fills the form with what [answer] found, its pack named as a portion in the form's language. */
    fun fill(answer: ProductLookup) {
        answer.product?.let { found -> draft = draft.filledWith(found, t(if (found.pack?.single == true) IngredientsMsg.PORTION_ITEM else IngredientsMsg.PORTION_PACK), t.locale) }
        notes = answer
        lookup = LookupState.Idle
    }

    /** Runs [ask] while the lookup says it is busy, and does [then] with its answer; a failure to reach the server is said. */
    fun <T> looking(ask: suspend () -> T, then: (T) -> Unit) {
        if (lookup == LookupState.Busy) return
        lookup = LookupState.Busy
        notes = null
        scope.launch {
            when (val result = load { ask() }) {
                is Load.Ready -> then(result.value)
                is Load.Failed -> lookup = LookupState.Failed(result)
                Load.Loading -> Unit
            }
        }
    }

    /** A barcode is looked up at once; a name is searched, to pick one of what is found. */
    fun lookUp() {
        val text = query.trim()
        scanProblem = null
        if (text.length < 2) {
            notes = null
            lookup = LookupState.TooShort
            return
        }
        val barcode = barcodeIn(text)
        if (barcode != null) looking({ api.productByBarcode(barcode) }, ::fill)
        else looking({ api.searchProducts(text) }) { lookup = LookupState.Found(text, it) }
    }

    /** The camera reads a barcode and it is looked up as a typed one is: put in the field, then looked up. */
    fun scanned(result: ScanResult) {
        if (result is ScanResult.Read) {
            query = result.digits
            lookUp()
        } else {
            scanProblem = result
        }
    }
    DisposableEffect(scanner) {
        scanner?.onResult = ::scanned
        onDispose { scanner?.onResult = null }
    }

    // The refusal is no longer true once the camera has been allowed (in the settings, then back to the form).
    val cameraAllowed = scanner?.cameraAllowed == true
    LaunchedEffect(cameraAllowed) { if (cameraAllowed && scanProblem == ScanResult.Refused) scanProblem = null }

    val nameTaken = draft.nameTaken(catalogue)

    fun save() {
        if (!draft.canSave || nameTaken || saving) return
        saving = true
        val input = draft.input()
        scope.launch {
            when (val result = load { ingredient?.let { api.updateIngredient(it.id, input) } ?: api.createIngredient(input) }) {
                is Load.Ready -> onDone(result.value)
                is Load.Failed -> {
                    saving = false
                    snackbar.showSnackbar(failureSentence(result, CommonMsg.SAVE_FAILED))
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
                title = { Text(if (ingredient == null) t(IngredientsMsg.NEW_PRODUCT) else t(IngredientsMsg.EDIT_PRODUCT)) },
                navigationIcon = { BarAction(AppIcons.Close, t(CommonMsg.CANCEL), leave) },
                actions = { TextButton(onClick = ::saveAsked, enabled = draft.canSave && !nameTaken && !saving) { Text(t(CommonMsg.SAVE)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val enabled = !saving
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
            verticalArrangement = Arrangement.spacedBy(Spacing.Related),
        ) {
            LookupPanel(
                query,
                { text ->
                    // What the last lookup said is of another text now.
                    query = text
                    if (lookup != LookupState.Busy) lookup = LookupState.Idle
                    notes = null
                    scanProblem = null
                },
                lookup, notes, enabled,
                onLookUp = ::lookUp,
                onScan = scanner?.let { active -> { scanProblem = null; active.scan() } },
                onOpenSettings = scanner?.let { active -> active::openSettings },
                scanProblem = scanProblem,
                onPick = { hit -> looking({ api.ahProduct(hit) }, ::fill) },
            )
            OutlinedTextField(
                draft.name, { draft = draft.copy(name = it) },
                label = { Text(t(CommonMsg.NAME)) },
                isError = draft.name.isBlank() || nameTaken,
                supportingText = when {
                    draft.name.isBlank() -> ({ Text(t(IngredientsMsg.PRODUCT_NAME_NEEDED)) })
                    nameTaken -> ({ Text(t(IngredientsMsg.PRODUCT_NAME_TAKEN)) })
                    else -> null
                },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            draft.source?.let { SourceLine(it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(t(IngredientsMsg.PRODUCT_VALUES_PER), style = MaterialTheme.typography.labelLarge)
                // Neither is chosen while a lookup left it open.
                val chosen = !draft.basisNeeded
                FilterChip(selected = chosen && !draft.perMl, onClick = { draft = draft.choosingBasis(ml = false) }, label = { Text(per100(FoodBasis.PER_100_G)) }, enabled = enabled)
                FilterChip(selected = chosen && draft.perMl, onClick = { draft = draft.choosingBasis(ml = true) }, label = { Text(per100(FoodBasis.PER_100_ML)) }, enabled = enabled)
            }
            if (draft.basisNeeded) {
                Text(t(IngredientsMsg.PRODUCT_BASIS_NEEDED), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(t(IngredientsMsg.PRODUCT_VALUES_HINT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            Text(t(IngredientsMsg.PORTIONS), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.Related))
            Text(t(IngredientsMsg.PORTIONS_HINT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            TextButton(onClick = { draft = draft.addingPortion() }, enabled = enabled) { Text(t(IngredientsMsg.ADD_PORTION)) }
        }
    }

    if (askingRemoval) {
        val names = draft.removedPortions.joinToString { "“${it.name}”" }
        AlertDialog(
            onDismissRequest = { askingRemoval = false },
            title = { Text(t(IngredientsMsg.REMOVE_PORTIONS_TITLE, names)) },
            text = { Text(t(IngredientsMsg.REMOVE_PORTIONS_TEXT, names)) },
            confirmButton = {
                TextButton(onClick = { askingRemoval = false; save() }) { Text(t(IngredientsMsg.REMOVE_AND_SAVE)) }
            },
            dismissButton = {
                TextButton(onClick = { askingRemoval = false; draft = draft.keepingRemovedPortions(t.locale) }) { Text(t(IngredientsMsg.KEEP_PORTIONS)) }
            },
        )
    }
}

/**
 * Where the lookup is: nothing asked, a text too short to ask, waiting for the server, what a search found for
 * [Found.text], or the server not reached.
 */
private sealed interface LookupState {
    data object Idle : LookupState

    data object TooShort : LookupState

    data object Busy : LookupState

    data class Found(val text: String, val search: ProductSearch) : LookupState

    data class Failed(val failed: Load.Failed) : LookupState
}

/**
 * The lookup at the top of the form: a field for a name or a barcode and its button; under it, while it waits a line
 * saying so, what a search found to pick (each with its brand and pack), or what the last lookup said ([lookupNotes]),
 * in the error colour when it left values to type. With an [onScan] a button next to the lookup's reads the barcode with
 * the camera; why a scan gave none is [scanProblem], with after a refusal the way to the app's settings ([onOpenSettings]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookupPanel(
    query: String,
    onQuery: (String) -> Unit,
    state: LookupState,
    notes: ProductLookup?,
    enabled: Boolean,
    onLookUp: () -> Unit,
    onScan: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    scanProblem: ScanResult?,
    onPick: (ProductHit) -> Unit,
) {
    val t = LocalStrings.current
    val busy = state == LookupState.Busy
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    Text(t(IngredientsMsg.LOOKUP_TITLE), style = MaterialTheme.typography.titleMedium)
    Text(t(IngredientsMsg.LOOKUP_HINT), style = MaterialTheme.typography.bodySmall, color = quiet)
    OutlinedTextField(
        query, onQuery,
        label = { Text(t(IngredientsMsg.LOOKUP_FIELD)) },
        enabled = enabled && !busy,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onLookUp() }),
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related), itemVerticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onLookUp, enabled = enabled && !busy) { Text(t(IngredientsMsg.LOOKUP)) }
        onScan?.let { TextButton(onClick = it, enabled = enabled && !busy) { Text(t(IngredientsMsg.SCAN)) } }
    }
    when (scanProblem) {
        ScanResult.Refused -> {
            Text(t(IngredientsMsg.SCAN_REFUSED), style = MaterialTheme.typography.bodySmall, color = error)
            onOpenSettings?.let { TextButton(onClick = it) { Text(t(IngredientsMsg.SCAN_SETTINGS)) } }
        }
        ScanResult.Unavailable -> Text(t(IngredientsMsg.SCAN_UNAVAILABLE), style = MaterialTheme.typography.bodySmall, color = error)
        is ScanResult.Read, null -> Unit
    }
    when (state) {
        LookupState.Idle -> notes?.let { answer ->
            val color = if (answer.product?.basis == null || answer.problems.isNotEmpty()) error else quiet
            lookupNotes(answer, t).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
        }
        LookupState.TooShort -> Text(t(IngredientsMsg.LOOKUP_TOO_SHORT), style = MaterialTheme.typography.bodySmall, color = error)
        LookupState.Busy -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Related), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(t(IngredientsMsg.LOOKUP_BUSY), style = MaterialTheme.typography.bodyMedium, color = quiet)
        }
        is LookupState.Found -> {
            state.search.problems.forEach { Text(problemText(it, t), style = MaterialTheme.typography.bodySmall, color = error) }
            if (state.search.problems.isNotEmpty()) Text(t(IngredientsMsg.LOOKUP_TYPE_VALUES), style = MaterialTheme.typography.bodySmall, color = error)
            if (state.search.products.isEmpty() && state.search.problems.isEmpty()) {
                Text(t(IngredientsMsg.LOOKUP_NOTHING, state.text), style = MaterialTheme.typography.bodyMedium, color = quiet)
            }
            state.search.products.forEach { hit -> HitRow(hit, enabled) { onPick(hit) } }
        }
        is LookupState.Failed -> Text(failureSentence(state.failed, IngredientsMsg.LOOKUP_FAILED), style = MaterialTheme.typography.bodySmall, color = error)
    }
    HorizontalDivider(Modifier.padding(vertical = Spacing.Related))
}

/** A product a search found: its name, and under it its brand and pack; a tap fills the form with it. */
@Composable
private fun HitRow(hit: ProductHit, enabled: Boolean, onPick: () -> Unit) {
    val t = LocalStrings.current
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onPick).padding(vertical = 8.dp)) {
        Text(hit.name, style = MaterialTheme.typography.bodyLarge)
        hit.detail(t).takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Where the values came from ([sourceLine]), and for Open Food Facts' the credit and link its licence (ODbL) asks
 * wherever its values are shown.
 */
@Composable
private fun SourceLine(source: String) {
    val t = LocalStrings.current
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Text(sourceLine(source, t), style = MaterialTheme.typography.bodySmall, color = quiet)
    if (fromOpenFoodFacts(source)) {
        val link = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
        Text(
            inlineMarkdown(t(IngredientsMsg.OFF_LICENCE, "[openfoodfacts.org]($OPEN_FOOD_FACTS_URL)"), link),
            style = MaterialTheme.typography.bodySmall,
            color = quiet,
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
        supportingText = problem?.let { { Text(numberProblemText(it, t(IngredientsMsg.PRODUCT_NEEDS_ENERGY), t)) } },
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
    NumberProblem.NOT_A_NUMBER -> t(IngredientsMsg.PRODUCT_NOT_A_NUMBER)
    NumberProblem.NEGATIVE -> t(IngredientsMsg.PRODUCT_NEGATIVE)
    NumberProblem.ZERO -> t(IngredientsMsg.PORTION_SIZE_ZERO)
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
            label = { Text(t(IngredientsMsg.PORTION_NAME)) },
            isError = problem != null,
            supportingText = problem?.let {
                {
                    Text(
                        when (it) {
                            PortionNameProblem.NEEDED -> t(IngredientsMsg.PORTION_NAME_NEEDED)
                            PortionNameProblem.TAKEN -> t(IngredientsMsg.PORTION_NAME_TAKEN)
                            PortionNameProblem.IS_UNIT -> t(IngredientsMsg.PORTION_NAME_IS_UNIT, row.name.trim())
                            PortionNameProblem.BAD_CHARACTER -> t(IngredientsMsg.PORTION_NAME_BAD)
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
            BarAction(AppIcons.Close, t(IngredientsMsg.REMOVE_PORTION, row.name), onRemove, enabled = enabled)
        }
    }
    Row(Modifier.padding(start = 24.dp, bottom = Spacing.Related), verticalAlignment = Alignment.Top) {
        val sizeProblem = row.sizeProblem
        OutlinedTextField(
            row.size, { onChange(row.copy(size = it)) },
            label = { Text(t(IngredientsMsg.PORTION_SIZE)) },
            isError = sizeProblem != null,
            supportingText = sizeProblem?.let { { Text(numberProblemText(it, t(IngredientsMsg.PORTION_SIZE_NEEDED, unit), t)) } },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = next.imeAction),
            keyboardActions = next.actions,
            modifier = Modifier.weight(1f).focusRequester(sizeFocus),
        )
        TextButton(
            onClick = { onChange(row.copy(ml = !row.ml)) },
            enabled = enabled,
            modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = t(IngredientsMsg.PORTION_UNIT, unit) },
        ) { Text(unit) }
    }
}
