package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.DinnerPick
import dev.tobins.mealplanner.api.DinnerSuggestion
import dev.tobins.mealplanner.api.DinnerSuggestions
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeekPlanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Suggestions on one screen, as the owner asked for about six (issue 255). */
private const val SUGGESTIONS_SHOWN = 6

/** How long the search waits after a key before it asks the server, so that typing a word is one request. */
private const val SEARCH_PAUSE_MS = 300L

/**
 * Planning the week starting [weekStart] from suggestions (issue 255): the dinners chosen so far and how the week stands
 * with them (the strip), about six suggestions at a time with why each is there, "Show others", "Something else" (a search
 * of every meal of mine), "Not this week", and "Done" or "Fill the rest", which plans the week and hands its plan to
 * [onPlanned]. Every choice is the server's as it is made, so leaving the screen loses nothing. Nothing is refused or
 * warned about for its calories: the strip only shows. A server from before the screen plans the week in one go.
 */
@Composable
fun PlanningScreen(
    api: ApiClient,
    weekStart: String,
    onBack: () -> Unit,
    onPlanned: (WeekPlan) -> Unit,
    /** Opens the Recipes tab, where recipes are added and put in the rotation: offered when there is nothing to suggest. */
    onOpenRecipes: (() -> Unit)? = null,
) {
    val t = LocalStrings.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var planning by remember(weekStart) { mutableStateOf<Load<WeekPlanning?>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // The suggestions shown on earlier screens ("Show others"), and the search typed, if any ("Something else").
    var seen by rememberSaveable(weekStart) { mutableStateOf(listOf<Long>()) }
    var query by rememberSaveable(weekStart) { mutableStateOf<String?>(null) }
    // Asked for again after every change: a chosen or hidden recipe goes, and the rest are scored again.
    var rescored by remember { mutableIntStateOf(0) }
    var page by remember(weekStart) { mutableStateOf<Load<DinnerSuggestions>>(Load.Loading) }
    val myRecipes by rememberMyRecipes(api)
    val pictures = myRecipes?.let(::pictureUrls).orEmpty()

    LaunchedEffect(weekStart, reload) { planning = load { api.planning(weekStart) } }
    LaunchedEffect(weekStart, seen, query, rescored, reload) {
        if (query != null) delay(SEARCH_PAUSE_MS)
        page = load { api.dinnerSuggestions(weekStart, seen, SUGGESTIONS_SHOWN, query) }
    }

    /** Runs a change of the week's choices and shows the planning it returns; a refusal is said in the snackbar. */
    fun change(action: suspend () -> WeekPlanning) {
        if (busy) return
        busy = true
        scope.launch {
            when (val result = load(action)) {
                is Load.Ready -> {
                    planning = result
                    rescored++
                }
                is Load.Failed -> snackbar.showSnackbar(failureSentence(result))
                Load.Loading -> Unit
            }
            busy = false
        }
    }

    /** Plans the week ([plan]: Done, or Generate on a server before the screen) and leaves the screen with its plan. */
    fun finish(plan: suspend () -> WeekPlan) {
        if (busy) return
        busy = true
        scope.launch {
            when (val result = load(plan)) {
                is Load.Ready -> onPlanned(result.value)
                is Load.Failed -> snackbar.showSnackbar(failureSentence(result))
                Load.Loading -> Unit
            }
            busy = false
        }
    }

    val ready = (planning as? Load.Ready)?.value
    SubScreen(
        // Short enough for the top bar at the largest font; the week's date is the screen's first line.
        title = t(Msg.PLAN_THE_WEEK),
        onBack = onBack,
        snackbar = snackbar,
        notice = {
            if (ready != null) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        enabled = !busy,
                        onClick = { finish { api.planningDone(weekStart) } },
                        modifier = Modifier.fillMaxWidth().padding(Spacing.ScreenEdge),
                    ) { Text(if (ready.complete) t(Msg.PLANNING_DONE) else t(Msg.PLANNING_FILL_REST)) }
                }
            }
        },
    ) { padding ->
        when (val current = planning) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Column(Modifier.padding(padding)) { ErrorMessage(t(Msg.PLANNING_LOAD_FAILED), current) { reload++ } }
            is Load.Ready -> {
                val week = current.value
                if (week == null) {
                    EmptyState(
                        Spot.NothingPlanned, t(Msg.PLANNING_OLD_SERVER), Modifier.padding(padding),
                        actionLabel = t(Msg.PLAN_THE_WEEK), onAction = { finish { api.generatePlan(weekStart) } },
                    )
                } else {
                    LazyColumn(
                        Modifier.padding(padding).fillMaxSize(),
                        contentPadding = PaddingValues(Spacing.ScreenEdge),
                        verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
                    ) {
                        item(key = "toChoose") {
                            Column {
                                Text(t(Msg.WEEK_OF, dayMonthLabel(weekStart, t.locale)), style = MaterialTheme.typography.titleMedium)
                                Text(week.toChooseText(t), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        item(key = "strip") { PlanningStrip(week) }
                        if (week.picks.isNotEmpty()) {
                            item(key = "chosen") { SectionHeader(t(Msg.PLANNING_CHOSEN)) }
                            items(week.picks, key = { "pick:${it.recipeId}" }) { pick ->
                                ChosenDinner(pick, enabled = !busy) { change { api.chooseDinner(weekStart, pick.recipeId, chosen = false) } }
                            }
                        }
                        suggestionItems(
                            page, query,
                            onQuery = { query = it },
                            canStartAgain = seen.isNotEmpty(),
                            onShowOthers = { suggestions -> seen = seen + suggestions.map { it.recipeId } },
                            onStartAgain = { seen = emptyList() },
                            onRetry = { rescored++ },
                            onOpenRecipes = onOpenRecipes,
                        ) { suggestion ->
                            // A search finds the dinners chosen too: marked so, as they are added already.
                            SuggestionCard(
                                suggestion, pictures[suggestion.recipeId], t(Msg.ADD), enabled = !busy,
                                chosen = week.picks.any { it.recipeId == suggestion.recipeId },
                                onAction = { change { api.chooseDinner(weekStart, suggestion.recipeId, chosen = true) } },
                                onNotThisWeek = { change { api.hideForWeek(weekStart, suggestion.recipeId, hidden = true) } },
                            )
                        }
                        if (week.notThisWeek.isNotEmpty()) {
                            item(key = "hidden") { HiddenRecipes(week, enabled = !busy) { id -> change { api.hideForWeek(weekStart, id, hidden = false) } } }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Another recipe" for one dinner (issue 255): the suggestions for the week's dinners, leaving out the dinner's own
 * recipe, with "Show others", "Something else" and "Not this week" as on the planning screen; choosing one puts it in
 * the meal, locked, as a recipe chosen by hand. "Let the planner choose" is the planner's own next choice, as before, for a
 * meal that is not locked. [onChanged] gets the week's plan once the meal changed.
 */
@Composable
fun AnotherDinnerScreen(api: ApiClient, weekStart: String, meal: PlannedMeal, onBack: () -> Unit, onChanged: (WeekPlan) -> Unit) {
    val t = LocalStrings.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var seen by rememberSaveable(meal.day) { mutableStateOf(listOf<Long>()) }
    var query by rememberSaveable(meal.day) { mutableStateOf<String?>(null) }
    var rescored by remember { mutableIntStateOf(0) }
    var page by remember(meal.day) { mutableStateOf<Load<DinnerSuggestions>>(Load.Loading) }
    val myRecipes by rememberMyRecipes(api)
    val pictures = myRecipes?.let(::pictureUrls).orEmpty()

    LaunchedEffect(seen, query, rescored) {
        if (query != null) delay(SEARCH_PAUSE_MS)
        // The meal's own recipe is no other dinner; a search finds every meal, that one too.
        val shown = if (query == null) seen + meal.recipeId else seen
        page = load { api.dinnerSuggestions(weekStart, shown, SUGGESTIONS_SHOWN, query) }
    }

    /** Changes the meal on the server and leaves with the plan; a refusal (an eaten meal, an old server) is said in the snackbar. */
    fun change(action: suspend () -> WeekPlan?) {
        if (busy) return
        busy = true
        scope.launch {
            when (val result = load(action)) {
                is Load.Ready -> result.value?.let(onChanged) ?: snackbar.showSnackbar(t(Msg.PLAN_CHOOSE_UNSUPPORTED))
                is Load.Failed -> snackbar.showSnackbar(failureSentence(result))
                Load.Loading -> Unit
            }
            busy = false
        }
    }

    SubScreen(title = t(Msg.ANOTHER_DINNER_TITLE, dayLabel(plusDays(weekStart, meal.day), t.locale)), onBack = onBack, snackbar = snackbar) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(Spacing.ScreenEdge),
            verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
        ) {
            if (!meal.locked) {
                item(key = "planner") {
                    TextButton(enabled = !busy, onClick = { change { api.rerollMeal(weekStart, meal.day, meal.slot) } }) { Text(t(Msg.PLANNERS_CHOICE)) }
                }
            }
            suggestionItems(
                page, query,
                onQuery = { query = it },
                canStartAgain = seen.isNotEmpty(),
                onShowOthers = { suggestions -> seen = seen + suggestions.map { it.recipeId } },
                onStartAgain = { seen = emptyList() },
                onRetry = { rescored++ },
            ) { suggestion ->
                SuggestionCard(
                    suggestion, pictures[suggestion.recipeId], t(Msg.PLANNING_CHOOSE), enabled = !busy,
                    onAction = { change { api.chooseRecipe(weekStart, meal.day, meal.slot, suggestion.recipeId) } },
                    onNotThisWeek = {
                        scope.launch {
                            when (val hidden = load { api.hideForWeek(weekStart, suggestion.recipeId, hidden = true) }) {
                                is Load.Ready -> rescored++
                                is Load.Failed -> snackbar.showSnackbar(failureSentence(hidden))
                                Load.Loading -> Unit
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * The suggestions part of a screen: its header with "Something else" (or the search field and the way back), the cards
 * of [page], and under them "Show others" while there are more, or that there are no more and "Start again" from the
 * first. With none shown yet ([canStartAgain] false), that there is nothing to suggest and why, with [onOpenRecipes]
 * where recipes are added. [query] is the search typed, null without one.
 */
internal fun LazyListScope.suggestionItems(
    page: Load<DinnerSuggestions>,
    query: String?,
    onQuery: (String?) -> Unit,
    canStartAgain: Boolean,
    onShowOthers: (List<DinnerSuggestion>) -> Unit,
    onStartAgain: () -> Unit,
    onRetry: () -> Unit,
    onOpenRecipes: (() -> Unit)? = null,
    card: @Composable (DinnerSuggestion) -> Unit,
) {
    item(key = "suggestionsHeader") {
        val t = LocalStrings.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(t(Msg.PLANNING_SUGGESTIONS), Modifier.weight(1f))
            if (query == null) TextButton(onClick = { onQuery("") }) { Text(t(Msg.PLANNING_SOMETHING_ELSE)) }
            else TextButton(onClick = { onQuery(null) }) { Text(t(Msg.PLANNING_BACK_TO_SUGGESTIONS)) }
        }
    }
    if (query != null) {
        item(key = "search") {
            OutlinedTextField(query, onValueChange = onQuery, label = { Text(LocalStrings.current(Msg.PLANNING_SEARCH)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
    when (page) {
        Load.Loading -> item(key = "suggestionsLoading") { LoadingState(Modifier.height(160.dp)) }
        is Load.Failed -> item(key = "suggestionsFailed") { ErrorMessage(LocalStrings.current(Msg.PLANNING_LOAD_FAILED), page, onRetry) }
        is Load.Ready -> {
            val suggestions = page.value.suggestions
            items(suggestions, key = { "suggestion:${it.recipeId}" }) { card(it) }
            item(key = "suggestionsFooter") {
                val t = LocalStrings.current
                when {
                    page.value.more -> TextButton(onClick = { onShowOthers(suggestions) }) { Text(t(Msg.PLANNING_SHOW_OTHERS)) }
                    query != null -> if (suggestions.isEmpty()) Text(t(Msg.PLANNING_SEARCH_NONE), style = MaterialTheme.typography.bodyMedium)
                    suggestions.isEmpty() && !canStartAgain -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                        Text(t(Msg.PLANNING_NONE), style = MaterialTheme.typography.bodyMedium)
                        onOpenRecipes?.let { TextButton(onClick = it) { Text(t(Msg.OPEN_RECIPES)) } }
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                        Text(t(Msg.PLANNING_NO_MORE), style = MaterialTheme.typography.bodyMedium)
                        if (canStartAgain) TextButton(onClick = onStartAgain) { Text(t(Msg.PLANNING_START_AGAIN)) }
                    }
                }
            }
        }
    }
}

/** The strip: what the meals that stay and the dinners chosen come to, against what those meals should give. Only shown. */
@Composable
private fun PlanningStrip(week: WeekPlanning) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            Text(t(Msg.PLANNING_STRIP_TITLE), style = MaterialTheme.typography.titleSmall)
            val lines = week.totals.lines(t)
            if (lines.isEmpty()) {
                Text(t(Msg.PLANNING_STRIP_EMPTY), style = MaterialTheme.typography.bodyMedium)
            } else {
                for (line in lines) Text(line, style = MaterialTheme.typography.bodyMedium.numeric())
                Text(t(Msg.PLANNING_STRIP_NOTE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A dinner chosen for the week, with its numbers and the button that takes it out; one that can't be planned says so. */
@Composable
private fun ChosenDinner(pick: DinnerPick, enabled: Boolean, onTakeOut: () -> Unit) {
    val t = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(pick.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (pick.plannable) {
                MealNumbers(
                    "${unbrokenParts(servingsText(pick.portions, t))} · ${macrosText(pick.kcal, pick.proteinG, pick.fibreG, t)}",
                    pick.cost?.let { "≈ ${formatEuro(it, t.locale)}" },
                )
            } else {
                Text(t(Msg.PLANNING_NOT_PLANNABLE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        BarAction(AppIcons.Close, t(Msg.PLANNING_TAKE_OUT, pick.title), onClick = onTakeOut, enabled = enabled)
    }
}

/**
 * A suggested recipe: its title (and photo), why it is there, what it gives at the planner's portion and its price with
 * the saving. Tapping it, or [actionLabel], is [onAction]; "Not this week" hides it for the week. One [chosen] already,
 * as a search finds it, says so in their place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SuggestionCard(
    suggestion: DinnerSuggestion,
    picture: String?,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
    onNotThisWeek: () -> Unit,
    chosen: Boolean = false,
) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth().clickable(enabled = enabled && !chosen, onClick = onAction)) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(suggestion.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                recipePicture(picture)?.let { RecipePhoto(it, Modifier.padding(start = Spacing.Related).size(48.dp)) }
            }
            for (reason in suggestion.reasons(t)) {
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MealNumbers(suggestion.numbersText(t), null)
            suggestion.priceText(t)?.let { Text(it, style = MaterialTheme.typography.bodySmall.numeric()) }
            if (chosen) {
                Text(t(Msg.PLANNING_ALREADY_CHOSEN), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    Button(enabled = enabled, onClick = onAction) { Text(actionLabel) }
                    TextButton(enabled = enabled, onClick = onNotThisWeek) { Text(t(Msg.PLANNING_NOT_THIS_WEEK)) }
                }
            }
        }
    }
}

/** The recipes hidden for the week, each with the button that shows it again. */
@Composable
private fun HiddenRecipes(week: WeekPlanning, enabled: Boolean, onShowAgain: (Long) -> Unit) {
    val t = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
        SectionHeader(t(Msg.PLANNING_NOT_THIS_WEEK))
        for (hidden in week.notThisWeek) {
            // One row to a screen reader: the recipe, then its button.
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Text(hidden.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(enabled = enabled, onClick = { onShowAgain(hidden.recipeId) }) { Text(t(Msg.PLANNING_SHOW_AGAIN)) }
            }
        }
    }
}
