package dev.tobins.mealplanner.app.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import dev.tobins.mealplanner.api.CookingRepoSetting
import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.BackHandler
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Plural
import dev.tobins.mealplanner.app.RefreshNotice
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.ingredients.RadioOption
import dev.tobins.mealplanner.app.rememberFrontReturns
import dev.tobins.mealplanner.app.rememberLoad
import dev.tobins.mealplanner.app.ui.AppIcons
import dev.tobins.mealplanner.app.ui.BarAction
import dev.tobins.mealplanner.app.ui.ErrorBanner
import dev.tobins.mealplanner.app.ui.ErrorMessage
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.LoadingState
import dev.tobins.mealplanner.app.ui.MenuAction
import dev.tobins.mealplanner.app.ui.OverflowMenu
import dev.tobins.mealplanner.app.ui.Spacing
import dev.tobins.mealplanner.app.ui.SubScreen
import dev.tobins.mealplanner.app.ui.TwoActionSnackbar
import dev.tobins.mealplanner.app.ui.failureSentence
import dev.tobins.mealplanner.app.ui.instantLabel
import dev.tobins.mealplanner.app.ui.load
import dev.tobins.mealplanner.app.ui.reasonOf
import dev.tobins.mealplanner.app.ui.sentence
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
        is Load.Failed -> SyncOutcome(failureSentence(result, if (imported) Msg.IMPORT_FAILED else Msg.SYNC_FAILED), 0)
        Load.Loading -> SyncOutcome("", 0)
    }

/**
 * Sets where the account's bank comes from, tells [onStatus] the status that answers, and imports from that site at
 * once; null when the account has none to import from.
 */
internal suspend fun saveCookingRepo(api: ApiClient, t: Strings, setting: CookingRepoSetting, onStatus: (CookingSyncStatus) -> Unit): SyncOutcome? {
    val status = api.setCookingRepo(setting)
    onStatus(status)
    return if (status.shared || status.repoUrl != null) syncCookingSite(api, t, bank = true, imported = true) else null
}

/** Where the bank comes from, as the cooking-site dialog offers it. */
internal enum class SiteChoice { SERVER, OWN, NONE }

/** The choice in use. A server from before the server's site was everyone's (issue 208) says nothing of [CookingSyncStatus.shared]: its site is the account's own. */
internal fun CookingSyncStatus.siteChoice(): SiteChoice = when {
    shared -> SiteChoice.SERVER
    repoUrl != null -> SiteChoice.OWN
    else -> SiteChoice.NONE
}

/**
 * What the server is sent for [choice], with the address [url] of a repository of the account's own. "None" is said
 * as such only to a server that offers its site ([serverSite]): to any other, no URL means none already, and one from
 * before issue 208 refuses a field it does not know.
 */
internal fun siteSetting(choice: SiteChoice, url: String, serverSite: Boolean): CookingRepoSetting = when (choice) {
    SiteChoice.OWN -> CookingRepoSetting(url.trim())
    SiteChoice.SERVER -> CookingRepoSetting(null)
    SiteChoice.NONE -> CookingRepoSetting(null, noSite = serverSite)
}

/**
 * Tells that recipes were added, with "Undo" and, when ingredients of them are not linked to a food yet,
 * how many and a way to link them ([onLink]). Runs [onUndo] when "Undo" is tapped. Returns when the
 * snackbar is gone.
 */
internal suspend fun SnackbarHostState.announceAdded(t: Strings, message: String, unlinked: Int, onLink: () -> Unit, onUndo: suspend () -> Unit) {
    val undo = t(Msg.UNDO)
    val result = if (unlinked > 0) {
        showSnackbar(TwoActionSnackbar("$message. " + t.plural(Plural.INGREDIENTS_TO_LINK, unlinked) + ".", undo, t(Msg.LINK_SHORT), onLink))
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
        status.siteChoice() == SiteChoice.NONE && status.syncedAt == null -> t.onWeb(Msg.SYNC_NONE, Msg.SYNC_NONE_BROWSER)
        status.syncedAt == null -> t(Msg.SYNC_NOT_YET)
        status.shared -> t(Msg.SYNC_SERVER_AT, instantLabel(status.syncedAt!!, t))
        else -> t(Msg.SYNC_AT, instantLabel(status.syncedAt!!, t))
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onEdit).padding(horizontal = Spacing.ScreenEdge), verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        status.error?.let { ErrorBanner(t(Msg.LAST_SYNC_FAILED), it) }
    }
}

/**
 * Chooses where the account's bank comes from (Hugo recipes): the server's cooking site, when it offers one, a git
 * repository of the account's own, or none. Starts on the choice in [status], the one in use.
 */
@Composable
internal fun CookingRepoDialog(status: CookingSyncStatus?, onDismiss: () -> Unit, onSave: suspend (CookingRepoSetting) -> Unit) {
    val t = LocalStrings.current
    val serverSite = status?.serverSite == true
    var choice by remember { mutableStateOf(status?.siteChoice() ?: SiteChoice.NONE) }
    var url by remember { mutableStateOf(status?.repoUrl?.takeIf { choice == SiteChoice.OWN }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save() {
        saving = true
        scope.launch {
            runCatching { onSave(siteSetting(choice, url, serverSite)) }.onFailure { error = sentence(reasonOf(it)) }
            saving = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Msg.COOKING_SITE)) },
        text = {
            CookingSiteChoices(
                serverSite = serverSite || choice == SiteChoice.SERVER, serverUrl = status?.serverRepoUrl,
                choice = choice, onChoice = { choice = it; error = null },
                url = url, onUrl = { url = it; error = null },
                error = error,
            )
        },
        confirmButton = {
            TextButton(enabled = !saving && (choice != SiteChoice.OWN || url.isNotBlank()), onClick = ::save) {
                Text(if (saving && choice != SiteChoice.NONE) t(Msg.IMPORTING) else t(Msg.SAVE))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}

/**
 * The cooking-site dialog's body: the server's site (named by its address [serverUrl]) when it offers one ([serverSite]),
 * a repository of the account's own with the field for its address under it, and none.
 */
@Composable
internal fun CookingSiteChoices(
    serverSite: Boolean,
    serverUrl: String?,
    choice: SiteChoice,
    onChoice: (SiteChoice) -> Unit,
    url: String,
    onUrl: (String) -> Unit,
    error: String?,
) {
    val t = LocalStrings.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
        Text(t(Msg.COOKING_SITE_CHOOSE))
        if (serverSite) RadioOption(t(Msg.SITE_OF_SERVER), serverUrl, selected = choice == SiteChoice.SERVER) { onChoice(SiteChoice.SERVER) }
        RadioOption(t(Msg.SITE_OWN), t(Msg.COOKING_SITE_EXPLAIN), selected = choice == SiteChoice.OWN) { onChoice(SiteChoice.OWN) }
        if (choice == SiteChoice.OWN) {
            OutlinedTextField(
                url, onUrl,
                label = { Text(t(Msg.REPO_URL)) },
                placeholder = { Text(t(Msg.REPO_URL_EXAMPLE)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        RadioOption(t(Msg.SITE_NONE), t(Msg.SITE_NONE_EXPLAIN), selected = choice == SiteChoice.NONE) { onChoice(SiteChoice.NONE) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * The recipe bank: the recipes of the cooking site that are not in my recipes yet, to search, filter by
 * section and add from, one at a time with the button on a row or several with a selection. A row opens
 * the recipe read-only. The cooking-site line, with its sync and repository, is here because it is about the bank.
 */
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
                        (load { api.setAdded(ids, added = false) } as? Load.Failed)?.let { snackbar.showSnackbar(failureSentence(it, Msg.REMOVE_FAILED)) }
                        reload++
                    }
                }
                is Load.Failed -> snackbar.showSnackbar(failureSentence(result, Msg.ADDED_FAILED))
                Load.Loading -> Unit
            }
        }
    }

    // Back leaves selecting first, and only then the screen (whose own handler is in App).
    BackHandler(enabled = selection.selecting, onBack = selection::stop)
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
        val bank = (state as? Load.Ready)?.value.orEmpty()
        val sections = bankSections(bank)
        // The header scrolls with the rows, so that a short screen (landscape, a large font) is not taken up by it.
        val header: @Composable () -> Unit = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(t(Msg.SEARCH_RECIPES)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenEdge),
                )
                if (sections.size > 1) {
                    // One row to slide sideways: sixteen sections wrapped would be half the screen.
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.ScreenEdge),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Related),
                    ) {
                        FilterChip(selected = section.isEmpty(), onClick = { section = "" }, label = { Text(t(Msg.BANK_ALL_SECTIONS)) })
                        sections.forEach { name ->
                            FilterChip(selected = section == name, onClick = { section = name }, label = { Text(name) })
                        }
                    }
                }
                syncStatus?.let { SyncLine(it, onEdit = { editingRepo = true }) }
            }
        }
        if (editingRepo) {
            CookingRepoDialog(
                status = syncStatus,
                onDismiss = { editingRepo = false },
                onSave = { setting ->
                    // The dialog stays, saying "Importing…", until this is done: closing it first would cancel it, as its scope goes with it.
                    val outcome = saveCookingRepo(api, t, setting) { syncStatus = it }
                    editingRepo = false
                    // The bank and the line above it show what the import found.
                    reload++
                    outcome?.let { scope.launch { snackbar.showSnackbar(it.message) } }
                },
            )
        }
        when (val current = state) {
            Load.Loading -> Column(Modifier.padding(padding).fillMaxSize()) { header(); LoadingState() }
            is Load.Failed -> Column(Modifier.padding(padding).fillMaxSize()) {
                header()
                ErrorMessage(t(Msg.RECIPES_LOAD_FAILED), current) { reload++ }
            }
            is Load.Ready -> {
                val shown = bankShown(current.value, section.ifEmpty { null }, query)
                LazyColumn(Modifier.padding(padding).fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = Spacing.ScreenEdge)) {
                    item(key = "header") { header() }
                    if (current.value.isEmpty()) {
                        item(key = "empty") { Text(t(Msg.BANK_EMPTY), Modifier.padding(Spacing.ScreenEdge)) }
                    } else if (shown.isEmpty()) {
                        item(key = "none") { Text(t(Msg.NO_RECIPES_MATCH), Modifier.padding(Spacing.ScreenEdge)) }
                    }
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
