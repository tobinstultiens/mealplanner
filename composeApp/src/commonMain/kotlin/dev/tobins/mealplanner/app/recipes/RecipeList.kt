package dev.tobins.mealplanner.app.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.BackHandler
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Plural
import dev.tobins.mealplanner.app.RefreshNotice
import dev.tobins.mealplanner.app.rememberFrontReturns
import dev.tobins.mealplanner.app.rememberLoad
import dev.tobins.mealplanner.app.ui.AppIcons
import dev.tobins.mealplanner.app.ui.AppSnackbarHost
import dev.tobins.mealplanner.app.ui.BarAction
import dev.tobins.mealplanner.app.ui.EmptyState
import dev.tobins.mealplanner.app.ui.ErrorMessage
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.LoadingState
import dev.tobins.mealplanner.app.ui.MenuAction
import dev.tobins.mealplanner.app.ui.OverflowMenu
import dev.tobins.mealplanner.app.ui.Spacing
import dev.tobins.mealplanner.app.ui.Spot
import dev.tobins.mealplanner.app.ui.WarningBadge
import dev.tobins.mealplanner.app.ui.failureSentence
import dev.tobins.mealplanner.app.ui.load
import dev.tobins.mealplanner.app.ui.rowText
import kotlinx.coroutines.launch

/**
 * The Recipes tab: my recipes, the ones added from the bank and the ones made in the app. A choice above
 * the list shows all of them, those in the rotation or those the planner leaves alone; a row's switch
 * changes that without opening the recipe, and a long press (or "Select recipes" in the menu, for
 * whoever has no long press) selects several, to put in or take out of the rotation or remove in one step.
 * [initialChoice] and [initiallySelected] only say where a new screen starts: pictures and tests start it
 * on another choice, or with a selection.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeListScreen(
    api: ApiClient,
    onOpen: (Long) -> Unit,
    onOpenMapping: () -> Unit,
    onOpenBank: () -> Unit,
    onNewRecipe: () -> Unit,
    initialChoice: RotationChoice = RotationChoice.ALL,
    initiallySelected: Set<Long>? = null,
) {
    val t = LocalStrings.current
    var generating by remember { mutableStateOf(false) }
    val loaded = rememberLoad<List<RecipeSummary>>("recipes")
    var state by loaded
    var reload by remember { mutableIntStateOf(0) }
    val frontReturns = rememberFrontReturns()
    val listState = rememberLazyListState()
    var syncing by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var mealsOnly by rememberSaveable { mutableStateOf(true) }
    // Saved by name: a choice is not something Android can keep as it is.
    var choiceName by rememberSaveable { mutableStateOf(initialChoice.name) }
    val choice = RotationChoice.valueOf(choiceName)
    val selection = rememberSaveable(saver = Selection.Saver) { Selection(initiallySelected) }
    // What a row's switch was just set to, until the server has answered: the row shows it at once and goes back if the change fails.
    var pending by remember { mutableStateOf(emptyMap<Long, Boolean>()) }
    var acting by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val bank = rememberBankOffered(api, reload)

    LaunchedEffect(loaded, reload, frontReturns) { loaded.refresh { api.recipes() } }

    /** Shows [recipes] as my recipes now, after a change that answered them. */
    fun show(recipes: List<RecipeSummary>) {
        state = Load.Ready(recipes)
    }

    fun setRotation(recipe: RecipeSummary, inRotation: Boolean) {
        pending = pending + (recipe.id to inRotation)
        scope.launch {
            val result = load { api.setRecipeRotation(recipe.id, inRotation) }
            if (result is Load.Ready) (state as? Load.Ready)?.let { show(it.value.replacing(result.value.summary)) }
            // The row is the server's again, which on a failure is as it was, before the sentence is shown: that waits until it is gone.
            pending = pending - recipe.id
            if (result is Load.Failed) snackbar.showSnackbar(failureSentence(result, Msg.ROTATION_FAILED))
        }
    }

    /** Runs a change of the selected recipes, which answers my recipes; leaves selecting and says [message] of it. */
    fun change(failed: Msg, call: suspend () -> List<RecipeSummary>, message: (List<RecipeSummary>) -> String) {
        if (acting) return
        acting = true
        scope.launch {
            val result = load(call)
            acting = false
            when (result) {
                is Load.Ready -> {
                    show(result.value)
                    selection.stop()
                    snackbar.showSnackbar(message(result.value))
                }
                is Load.Failed -> snackbar.showSnackbar(failureSentence(result, failed))
                Load.Loading -> Unit
            }
        }
    }

    val mine = (state as? Load.Ready)?.value.orEmpty()
    val chosen = selection.ids.orEmpty()
    // Back leaves selecting instead of whatever Back does on a tab.
    BackHandler(enabled = selection.selecting, onBack = selection::stop)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selection.selecting) t(Msg.SELECT_RECIPES) else t(Msg.TAB_RECIPES)) },
                navigationIcon = { if (selection.selecting) BarAction(AppIcons.Close, t(Msg.STOP_SELECTING), selection::stop) },
                actions = {
                    if (!selection.selecting) {
                        BarAction(AppIcons.Add, t(Msg.NEW_RECIPE), onNewRecipe)
                        BarAction(
                            AppIcons.Sync,
                            if (syncing) t(Msg.SYNCING) else t(Msg.SYNC),
                            busy = syncing,
                            onClick = {
                                syncing = true
                                scope.launch {
                                    val outcome = syncCookingSite(api, t, bank = bank != null)
                                    syncing = false
                                    reload++
                                    val shown = snackbar.showSnackbar(
                                        outcome.message,
                                        actionLabel = if (outcome.newInBank > 0) t(Msg.SHOW) else null,
                                    )
                                    if (shown == SnackbarResult.ActionPerformed) onOpenBank()
                                }
                            },
                        )
                        OverflowMenu(
                            MenuAction(AppIcons.Check, t(Msg.SELECT_RECIPES)) { selection.start() },
                            MenuAction(AppIcons.Generate, t(Msg.GENERATE_RECIPE)) { generating = true },
                            MenuAction(AppIcons.Link, t(Msg.LINK_INGREDIENTS), onOpenMapping),
                        )
                    }
                },
            )
        },
        // The lines have a place of their own: the content ends above them, and snackbars show above them.
        bottomBar = {
            Column {
                if (selection.selecting) {
                    val shown = searched(mine, mealsOnly, query).filter(choice::shows)
                    SelectionBar(chosen.size, onSelectAll = { selection.selectAll(shown.map { it.id }) }, aboveSystemBar = false) {
                        val any = chosen.isNotEmpty() && !acting
                        val picked = { mine.filter { it.id in chosen } }
                        SelectionAction(t(Msg.ADD_TO_ROTATION), any) {
                            change(Msg.ROTATION_FAILED, { api.setRecipesRotation(chosen.toList(), true) }) { t.plural(Plural.IN_ROTATION_NOW, chosen.size) }
                        }
                        SelectionAction(t(Msg.TAKE_OUT_OF_ROTATION), any) {
                            change(Msg.ROTATION_FAILED, { api.setRecipesRotation(chosen.toList(), false) }) { t.plural(Plural.OUT_OF_ROTATION_NOW, chosen.size) }
                        }
                        // Only recipes of the cooking site not changed in the app go back to the bank; the rest are left out and counted.
                        SelectionAction(t(Msg.REMOVE_FROM_MINE), any) {
                            val removal = removalOf(picked())
                            if (removal.ids.isEmpty()) {
                                scope.launch { snackbar.showSnackbar(t(Msg.NOTHING_TO_REMOVE)) }
                            } else {
                                change(Msg.REMOVE_FAILED, { api.setRecipesAdded(removal.ids, added = false) }) {
                                    t.plural(Plural.REMOVED_MANY, removal.ids.size) +
                                        if (removal.kept > 0) ". " + t.plural(Plural.KEPT_MADE_IN_APP, removal.kept) else ""
                                }
                            }
                        }
                    }
                }
                RefreshNotice(loaded) { reload++ }
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        if (generating) {
            GenerateDialog(onDismiss = { generating = false }) { request ->
                val id = api.generateRecipe(request).summary.id
                generating = false
                reload++
                onOpen(id)
            }
        }
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
                val counts = choiceCounts(searched(mine, mealsOnly, query))
                FlowRow(Modifier.padding(horizontal = Spacing.ScreenEdge), horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    RotationChoice.entries.forEach { entry ->
                        FilterChip(
                            selected = choice == entry,
                            onClick = { choiceName = entry.name },
                            label = { Text("${t(entry.label)} (${counts[entry]})") },
                        )
                    }
                    FilterChip(
                        selected = mealsOnly,
                        onClick = { mealsOnly = !mealsOnly },
                        label = { Text(t(Msg.MEALS_ONLY)) },
                    )
                }
                // What "Meals only" leaves out is said, so that a recipe just added (a sauce) does not seem to have vanished.
                val hidden = if (mealsOnly) notMealsHidden(mine, query) else 0
                if (hidden > 0) NotShownLine(hidden) { mealsOnly = false }
                // With none of my recipes the empty state below has the button.
                bank?.takeIf { mine.isNotEmpty() }?.let { BankLink(it.size, onOpenBank) }
            }
        }
        when (val current = state) {
            Load.Loading -> Column(Modifier.padding(padding).fillMaxSize()) { header(); LoadingState() }
            is Load.Failed -> Column(Modifier.padding(padding).fillMaxSize()) {
                header()
                ErrorMessage(t(Msg.RECIPES_LOAD_FAILED), current) { reload++ }
            }
            is Load.Ready -> {
                val shown = searched(current.value, mealsOnly, query).filter(choice::shows)
                if (current.value.isEmpty()) {
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        header()
                        EmptyState(
                            Spot.NoRecipes,
                            t(if (bank != null) Msg.NO_RECIPES_YET_BANK else Msg.NO_RECIPES_YET),
                            actionLabel = if (bank != null) t(Msg.BROWSE_BANK_COUNT, bank.size) else t(Msg.NEW_RECIPE),
                            onAction = if (bank != null) onOpenBank else onNewRecipe,
                            otherActionLabel = if (bank != null) t(Msg.NEW_RECIPE) else null,
                            onOtherAction = onNewRecipe,
                        )
                    }
                } else {
                    LazyColumn(Modifier.padding(padding).fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = Spacing.ScreenEdge)) {
                        item(key = "header") { header() }
                        if (shown.isEmpty()) item(key = "none") { Text(t(Msg.NO_RECIPES_MATCH), Modifier.padding(Spacing.ScreenEdge)) }
                        items(shown, key = { it.id }) { recipe ->
                            val selecting = selection.selecting
                            RecipeRow(
                                recipe,
                                onClick = { if (selecting) selection.toggle(recipe.id) else onOpen(recipe.id) },
                                onLongClick = { if (!selecting) selection.start(recipe.id) },
                                selected = if (selecting) selection.contains(recipe.id) else null,
                                trailing = if (selecting) null else ({
                                    RotationSwitch(recipe, pending[recipe.id] ?: recipe.inRotation) { setRotation(recipe, it) }
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

/** "2 recipes not shown (not meals)" with the way to show them: "Meals only" is on by default. */
@Composable
private fun NotShownLine(count: Int, onShow: () -> Unit) {
    val t = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onShow).padding(start = Spacing.ScreenEdge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t.plural(Plural.NOT_SHOWN, count), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onShow) { Text(t(Msg.SHOW_THEM)) }
    }
}

/** "Browse the bank" with how many recipes wait in it: the way from my recipes to the ones to add. */
@Composable
private fun BankLink(count: Int, onClick: () -> Unit) {
    val t = LocalStrings.current
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = Spacing.ScreenEdge, vertical = Spacing.Related),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(t(Msg.BROWSE_BANK), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            t.plural(Plural.BANK_RECIPES, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The switch at the end of a row: whether the planner may pick the recipe, with its short name under it. */
@Composable
private fun RotationSwitch(recipe: RecipeSummary, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalStrings.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = t(if (checked) Msg.ROTATION_OF else Msg.ROTATION_NOT_OF, recipe.title) },
        )
        Text(t(Msg.FILTER_IN_ROTATION), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A recipe in a list: the title, then nutrition left and cost right, then details and a warning badge.
 * A recipe with a photo shows it as a thumbnail at the end of the row, so the text of every row starts
 * at the same place; one without keeps the plain row, with no box in its place. [trailing] is what sits
 * last, beside the text (the rotation switch); [action] is a button under it, which has the text's whole width
 * (the bank's Add, whose Dutch word would squeeze it); [selected] is a checkbox in front while selecting several, and a
 * long press runs [onLongClick].
 */
@Composable
internal fun RecipeRow(
    recipe: RecipeSummary,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    trailing: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val t = LocalStrings.current
    val text = recipe.rowText(t)
    val picture = recipePicture(recipe.imageUrl)
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClick?.let { t(Msg.SELECT_RECIPES) })
            .padding(horizontal = Spacing.ScreenEdge, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
    ) {
        if (selected != null) Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.align(Alignment.CenterVertically))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            Text(recipe.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (text.nutrition != null || text.cost != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.BetweenCards)) {
                    Text(text.nutrition.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    text.cost?.let { Text(it, style = MaterialTheme.typography.bodyMedium, softWrap = false) }
                }
            }
            text.details?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            text.warning?.let { WarningBadge(it) }
            // In the rotation but never picked: the planner plans meals only.
            if (recipe.added && recipe.inRotation && recipe.kind == RecipeKind.COMPONENT) WarningBadge(t(Msg.NOT_A_MEAL_SKIPPED))
            if (recipe.removedFromSite) {
                Text(t(Msg.REMOVED_FROM_SITE), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.invoke()
        }
        picture?.let { RecipePhoto(it, Modifier.size(72.dp)) }
        trailing?.let { Box(Modifier.align(Alignment.CenterVertically)) { it() } }
    }
}
