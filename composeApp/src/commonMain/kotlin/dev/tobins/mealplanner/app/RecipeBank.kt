package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.RecipeSummary
import kotlinx.coroutines.launch

/**
 * The bank as a screen that only offers it (the Recipes tab, the recipe picker) knows it: the recipes
 * of the cooking site not added yet, kept in [ScreenMemory] under `bank` so that the bank screen starts
 * from them. Null until they are known, and for good on a server from before the bank, which then
 * gets no "Browse the bank". A failed fetch keeps what was there. [refetchOn] fetches again when it changes.
 */
@Composable
internal fun rememberBankOffered(api: ApiClient, refetchOn: Any? = null): List<RecipeSummary>? {
    val loaded = rememberLoad<List<RecipeSummary>>("bank")
    LaunchedEffect(loaded, refetchOn) {
        (load { api.recipeBank() } as? Load.Ready)?.value?.let { loaded.value = Load.Ready(it) }
    }
    return (loaded.value as? Load.Ready)?.value
}

/**
 * What a sync of the cooking site came to: the [message] for the snackbar, and how many recipes are new
 * in the bank, which is when the snackbar offers to open it. [bank] is false on a server from before the
 * bank, where a synced recipe is added at once and the message says "added".
 */
internal class SyncOutcome(val message: String, val newInBank: Int)

internal suspend fun syncCookingSite(api: ApiClient, t: Strings, bank: Boolean, imported: Boolean = false): SyncOutcome =
    when (val result = load { api.syncCookingSite() }) {
        is Load.Ready -> with(result.value) {
            when {
                !bank -> SyncOutcome(t(if (imported) Msg.IMPORTED else Msg.SYNCED, added, updated, removed), 0)
                added > 0 -> SyncOutcome(t.plural(Plural.NEW_IN_BANK, added), added)
                else -> SyncOutcome(t(if (imported) Msg.IMPORTED_BANK else Msg.SYNCED_BANK, added, updated, removed), 0)
            }
        }
        is Load.Failed -> SyncOutcome(t(if (imported) Msg.IMPORT_FAILED else Msg.SYNC_FAILED, result.message), 0)
        Load.Loading -> SyncOutcome("", 0)
    }

/**
 * Tells that recipes were added, with "Undo" and, when ingredients of them are not linked to a food yet,
 * how many and a way to link them ([onLink]). Runs [onUndo] when "Undo" is tapped. Returns when the
 * snackbar is gone.
 */
internal suspend fun SnackbarHostState.announceAdded(t: Strings, message: String, unlinked: Int, onLink: () -> Unit, onUndo: suspend () -> Unit) {
    val undo = t(Msg.UNDO)
    val result = if (unlinked > 0) {
        showSnackbar(TwoActionSnackbar(message + " " + t.plural(Plural.INGREDIENTS_TO_LINK, unlinked) + ".", undo, t(Msg.LINK_SHORT), onLink))
    } else {
        showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Long)
    }
    if (result == SnackbarResult.ActionPerformed) onUndo()
}

/** Adds the recipes [ids] to my recipes, or puts them back, in one call; answers what the recipes are now. */
internal suspend fun ApiClient.setAdded(ids: List<Long>, added: Boolean): List<RecipeSummary> =
    if (ids.size == 1) listOf(setRecipeAdded(ids.single(), added).summary) else setRecipesAdded(ids, added)

/**
 * The cooking-site line with the time of the last sync and the repository, with the sync's failure when
 * there was one. It is about the bank, which is what a sync fills; tapping it sets the repository.
 */
@Composable
internal fun SyncLine(status: CookingSyncStatus, onEdit: () -> Unit) {
    val t = LocalStrings.current
    val text = when {
        status.repoUrl == null && status.syncedAt == null -> t(Msg.SYNC_NONE)
        status.syncedAt == null -> t(Msg.SYNC_NOT_YET)
        else -> t(Msg.SYNC_AT, instantLabel(status.syncedAt!!, t))
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onEdit).padding(horizontal = Spacing.ScreenEdge), verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        status.error?.let { ErrorBanner(t(Msg.LAST_SYNC_FAILED), it) }
    }
}

/** Sets the git repository of the account's cooking site (Hugo recipes), or clears it. */
@Composable
internal fun CookingRepoDialog(current: String?, onDismiss: () -> Unit, onSave: suspend (String?) -> Unit) {
    val t = LocalStrings.current
    var url by remember { mutableStateOf(current.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save(value: String?) {
        saving = true
        scope.launch {
            runCatching { onSave(value) }.onFailure { error = (load { throw it } as? Load.Failed)?.message ?: it.message }
            saving = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Msg.COOKING_SITE)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                Text(t(Msg.COOKING_SITE_EXPLAIN))
                OutlinedTextField(
                    url, { url = it; error = null },
                    label = { Text(t(Msg.REPO_URL)) },
                    placeholder = { Text(t(Msg.REPO_URL_EXAMPLE)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && url.isNotBlank(), onClick = { save(url.trim()) }) { Text(if (saving) t(Msg.IMPORTING) else t(Msg.SAVE)) }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(enabled = !saving, onClick = { save(null) }) { Text(t(Msg.REMOVE)) }
                TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) }
            }
        },
    )
}

/**
 * The recipe bank: the recipes of the cooking site that are not in my recipes yet, to search, filter by
 * section and add from, one at a time with the button on a row or several with a selection. A row opens
 * the recipe read-only. The cooking-site line, with its sync and repository, is here because it is about the bank.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeBankScreen(api: ApiClient, onBack: () -> Unit, onOpen: (Long) -> Unit, onOpenMapping: () -> Unit) {
    val t = LocalStrings.current
    val loaded = rememberLoad<List<RecipeSummary>>("bank")
    var state by loaded
    var reload by remember { mutableIntStateOf(0) }
    val frontReturns = rememberFrontReturns()
    val listState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    // The section being looked at; empty for all of them.
    var section by rememberSaveable { mutableStateOf("") }
    val selection = rememberSaveable(saver = Selection.Saver) { Selection() }
    var acting by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf<CookingSyncStatus?>(null) }
    var editingRepo by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(loaded, reload, frontReturns) {
        // A server from before the bank has none: an empty one, which only a typed address can open.
        loaded.refresh { api.recipeBank() ?: emptyList() }
        // A failed fetch keeps the line that was shown.
        (load { api.cookingSyncStatus() } as? Load.Ready)?.value?.let { syncStatus = it }
    }

    /** Adds [recipes] (one or several) to my recipes and tells so, with "Undo". */
    fun add(recipes: List<RecipeSummary>) {
        if (acting || recipes.isEmpty()) return
        acting = true
        val ids = recipes.map { it.id }
        scope.launch {
            val result = load { api.setAdded(ids, added = true) }
            acting = false
            when (result) {
                is Load.Ready -> {
                    (state as? Load.Ready)?.let { state = Load.Ready(it.value.filter { recipe -> recipe.id !in ids }) }
                    selection.stop()
                    val message = if (recipes.size == 1) t(Msg.ADDED_ONE, recipes.single().title) else t.plural(Plural.ADDED_MANY, recipes.size)
                    snackbar.announceAdded(t, message, unlinkedIngredients(result.value.filter { it.id in ids }), onOpenMapping) {
                        (load { api.setAdded(ids, added = false) } as? Load.Failed)?.let { snackbar.showSnackbar(t(Msg.REMOVE_FAILED, it.message)) }
                        reload++
                    }
                }
                is Load.Failed -> snackbar.showSnackbar(t(Msg.ADDED_FAILED, result.message))
                Load.Loading -> Unit
            }
        }
    }

    SubScreen(
        title = if (selection.selecting) t(Msg.SELECT_RECIPES) else t(Msg.BANK_TITLE),
        onBack = { if (selection.selecting) selection.stop() else onBack() },
        snackbar = snackbar,
        notice = {
            Column {
                val shown = ((state as? Load.Ready)?.value).orEmpty().let { bankShown(it, section.ifEmpty { null }, query) }
                if (selection.selecting) {
                    SelectionBar(
                        count = selection.ids.orEmpty().size,
                        onSelectAll = { selection.selectAll(shown.map { it.id }) },
                        aboveSystemBar = false,
                    ) {
                        SelectionAction(t(Msg.ADD_TO_MY_RECIPES), enabled = !acting && selection.ids.orEmpty().isNotEmpty()) {
                            add(((state as? Load.Ready)?.value).orEmpty().filter { selection.contains(it.id) })
                        }
                    }
                }
                RefreshNotice(loaded, aboveSystemBar = true) { reload++ }
            }
        },
        actions = {
            BarAction(
                AppIcons.Sync,
                if (syncing) t(Msg.SYNCING) else t(Msg.SYNC),
                busy = syncing,
                onClick = {
                    syncing = true
                    scope.launch {
                        val outcome = syncCookingSite(api, t, bank = true)
                        syncing = false
                        reload++
                        snackbar.showSnackbar(outcome.message)
                    }
                },
            )
            OverflowMenu(MenuAction(AppIcons.Check, t(Msg.SELECT_RECIPES)) { selection.start() })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(t(Msg.SEARCH_RECIPES)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenEdge),
            )
            val bank = (state as? Load.Ready)?.value.orEmpty()
            val sections = bankSections(bank)
            if (sections.size > 1) {
                FlowRow(Modifier.padding(horizontal = Spacing.ScreenEdge), horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    FilterChip(selected = section.isEmpty(), onClick = { section = "" }, label = { Text(t(Msg.BANK_ALL_SECTIONS)) })
                    sections.forEach { name ->
                        FilterChip(selected = section == name, onClick = { section = name }, label = { Text(name) })
                    }
                }
            }
            syncStatus?.let { SyncLine(it, onEdit = { editingRepo = true }) }
            if (editingRepo) {
                CookingRepoDialog(
                    current = syncStatus?.repoUrl,
                    onDismiss = { editingRepo = false },
                    onSave = { url ->
                        syncStatus = api.setCookingRepo(url)
                        editingRepo = false
                        // A new repository is imported right away.
                        if (url != null) {
                            val outcome = syncCookingSite(api, t, bank = true, imported = true)
                            reload++
                            snackbar.showSnackbar(outcome.message)
                        }
                    },
                )
            }
            when (val current = state) {
                Load.Loading -> LoadingState()
                is Load.Failed -> ErrorMessage(t(Msg.RECIPES_LOAD_FAILED), current) { reload++ }
                is Load.Ready -> {
                    val shown = bankShown(current.value, section.ifEmpty { null }, query)
                    if (current.value.isEmpty()) {
                        EmptyState(AppIcons.Recipes, t(Msg.BANK_EMPTY))
                    } else if (shown.isEmpty()) {
                        EmptyState(AppIcons.Recipes, t(Msg.NO_RECIPES_MATCH))
                    } else {
                        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = Spacing.ScreenEdge)) {
                            items(shown, key = { it.id }) { recipe ->
                                val selecting = selection.selecting
                                RecipeRow(
                                    recipe,
                                    onClick = { if (selecting) selection.toggle(recipe.id) else onOpen(recipe.id) },
                                    onLongClick = { if (!selecting) selection.start(recipe.id) },
                                    selected = if (selecting) selection.contains(recipe.id) else null,
                                    action = if (selecting) null else ({
                                        FilledTonalButton(
                                            onClick = { add(listOf(recipe)) },
                                            enabled = !acting,
                                            modifier = Modifier.semantics { contentDescription = t(Msg.ADD_RECIPE, recipe.title) },
                                        ) { Text(t(Msg.ADD)) }
                                    }),
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
