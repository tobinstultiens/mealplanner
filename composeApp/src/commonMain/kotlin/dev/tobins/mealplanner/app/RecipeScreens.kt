package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeCost
import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/** Result of loading something from the server. */
internal sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    /**
     * [status] is the HTTP status when the server answered with an error. [technical]: [message] is
     * the text of an exception, such as "unexpected end of stream on http://...", not a sentence for
     * the user. [unreadable]: the server answered, but the app could not read the answer.
     * [lostConnection]: the request could not connect, timed out or was cut off.
     */
    data class Failed(
        val message: String,
        val status: Int? = null,
        val technical: Boolean = false,
        val unreadable: Boolean = false,
        val lostConnection: Boolean = false,
    ) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

internal suspend fun <T> load(block: suspend () -> T): Load<T> = try {
    Load.Ready(block())
} catch (e: CancellationException) {
    throw e
} catch (e: ResponseException) {
    // The server explains errors as {"error": "..."}; fall back to the status line.
    val reason = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(e.response.bodyAsText())
            .let { it as? kotlinx.serialization.json.JsonObject }?.get("error")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }.getOrNull()
    Load.Failed(reason ?: Strings.current(Msg.SERVER_ANSWERED, e.response.status.value, e.response.status.description), e.response.status.value)
} catch (e: Exception) {
    Load.Failed(
        e.message ?: Strings.current(Msg.UNKNOWN_ERROR),
        technical = e.message != null,
        unreadable = e is SerializationException || e is ContentConvertException,
        lostConnection = e is IOException || e is HttpRequestTimeoutException,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeListScreen(api: ApiClient, onOpen: (Long) -> Unit, onOpenMapping: () -> Unit, onNewRecipe: () -> Unit) {
    val t = LocalStrings.current
    var generating by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<Load<List<RecipeSummary>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var syncing by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var mealsOnly by rememberSaveable { mutableStateOf(true) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var syncStatus by remember { mutableStateOf<CookingSyncStatus?>(null) }
    var editingRepo by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        state = Load.Loading
        state = load { api.recipes() }
        syncStatus = (load { api.cookingSyncStatus() } as? Load.Ready)?.value
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(Msg.TAB_RECIPES)) },
                actions = {
                    BarAction(AppIcons.Add, t(Msg.NEW_RECIPE), onNewRecipe)
                    BarAction(
                        AppIcons.Sync,
                        if (syncing) t(Msg.SYNCING) else t(Msg.SYNC),
                        busy = syncing,
                        onClick = {
                            syncing = true
                            scope.launch {
                                val message = when (val result = load { api.syncCookingSite() }) {
                                    is Load.Ready -> with(result.value) { t(Msg.SYNCED, added, updated, removed) }
                                    is Load.Failed -> t(Msg.SYNC_FAILED, result.message)
                                    Load.Loading -> ""
                                }
                                syncing = false
                                reload++
                                snackbar.showSnackbar(message)
                            }
                        },
                    )
                    OverflowMenu(
                        MenuAction(AppIcons.Generate, t(Msg.GENERATE_RECIPE)) { generating = true },
                        MenuAction(AppIcons.Link, t(Msg.LINK_INGREDIENTS), onOpenMapping),
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (generating) {
            GenerateDialog(onDismiss = { generating = false }) { request ->
                val id = api.generateRecipe(request).summary.id
                generating = false
                reload++
                onOpen(id)
            }
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(t(Msg.SEARCH_RECIPES)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenEdge),
            )
            FilterChip(
                selected = mealsOnly,
                onClick = { mealsOnly = !mealsOnly },
                label = { Text(t(Msg.MEALS_ONLY)) },
                modifier = Modifier.padding(horizontal = Spacing.ScreenEdge),
            )
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
                            val message = when (val result = load { api.syncCookingSite() }) {
                                is Load.Ready -> with(result.value) { t(Msg.IMPORTED, added, updated, removed) }
                                is Load.Failed -> t(Msg.IMPORT_FAILED, result.message)
                                Load.Loading -> ""
                            }
                            reload++
                            snackbar.showSnackbar(message)
                        }
                    },
                )
            }
            when (val current = state) {
                Load.Loading -> LoadingState()
                is Load.Failed -> ErrorMessage(t(Msg.RECIPES_LOAD_FAILED), current.message) { reload++ }
                is Load.Ready -> {
                    val shown = current.value.filter { recipe ->
                        (!mealsOnly || recipe.kind == RecipeKind.MEAL) &&
                            (query.isBlank() || recipe.title.contains(query.trim(), ignoreCase = true) ||
                                recipe.tags.any { it.contains(query.trim(), ignoreCase = true) })
                    }
                    if (current.value.isEmpty()) {
                        EmptyState(
                            Spot.NoRecipes,
                            t(Msg.NO_RECIPES_YET),
                            actionLabel = t(Msg.NEW_RECIPE),
                            onAction = onNewRecipe,
                        )
                    } else if (shown.isEmpty()) {
                        EmptyState(AppIcons.Recipes, t(Msg.NO_RECIPES_MATCH))
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.ScreenEdge)) {
                            items(shown, key = { it.id }) { recipe ->
                                RecipeRow(recipe, onClick = { onOpen(recipe.id) })
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * When the account's cooking site was last read, so it is clear that edits there have
 * arrived; tapping it sets the repository to import from.
 */
@Composable
private fun SyncLine(status: CookingSyncStatus, onEdit: () -> Unit) {
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
private fun CookingRepoDialog(current: String?, onDismiss: () -> Unit, onSave: suspend (String?) -> Unit) {
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
 * A recipe in the list: the title, then nutrition left and cost right, then details and a warning badge.
 * A recipe with a photo shows it as a thumbnail at the end of the row, so the text of every row starts
 * at the same place; one without keeps the plain row, with no box in its place.
 */
@Composable
internal fun RecipeRow(recipe: RecipeSummary, onClick: () -> Unit) {
    val text = recipe.rowText(LocalStrings.current)
    val picture = recipePicture(recipe.imageUrl)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.ScreenEdge, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
    ) {
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
        }
        picture?.let { RecipePhoto(it, Modifier.size(72.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeDetailScreen(api: ApiClient, recipeId: Long, nutritionSource: String?, onBack: () -> Unit, onEdit: () -> Unit) {
    val t = LocalStrings.current
    val scope = rememberCoroutineScope()
    var state by remember(recipeId) { mutableStateOf<Load<RecipeDetail>>(Load.Loading) }
    var routines by remember { mutableStateOf<Routines?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(recipeId, reload) {
        state = Load.Loading
        state = load { api.recipe(recipeId) }
        routines = (load { api.routines() } as? Load.Ready)?.value
    }

    SubScreen((state as? Load.Ready)?.value?.summary?.title ?: t(Msg.RECIPE), onBack, actions = {
        if ((state as? Load.Ready)?.value?.summary?.source.let { it == RecipeSource.MANUAL || it == RecipeSource.GENERATED }) {
            BarAction(AppIcons.Edit, t(Msg.EDIT_RECIPE), onEdit)
        }
    }) { padding ->
        when (val current = state) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Box(Modifier.padding(padding)) {
                ErrorMessage(t(Msg.RECIPE_LOAD_FAILED), current.message) { reload++ }
            }
            is Load.Ready -> {
                val recipe = current.value
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
                    verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
                ) {
                    recipePicture(recipe.summary.imageUrl)?.let { RecipePhoto(it, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
                    if (recipe.summary.tags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                            recipe.summary.tags.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                        }
                    }
                    recipe.summary.servings?.let {
                        Text(t.plural(Plural.MAKES_SERVINGS, it), style = MaterialTheme.typography.labelLarge)
                    }
                    recipe.summary.nutrition?.let { NutritionCard(it, nutritionSource) }
                    recipe.summary.cost?.let { CostCard(it) }
                    RatingRow(recipe.summary.rating) { rating ->
                        scope.launch { (load { api.rateRecipe(recipeId, rating) } as? Load.Ready)?.let { state = it } }
                    }
                    PlannerSettings(recipe.summary) { settings ->
                        scope.launch {
                            (load { api.updateRecipeSettings(recipeId, settings) } as? Load.Ready)?.let { state = it }
                        }
                    }
                    routines?.let { current ->
                        RoutineButtons(recipe.summary, current) { updated ->
                            scope.launch { (load { api.saveRoutines(updated) } as? Load.Ready)?.let { routines = it.value } }
                        }
                    }
                    // The reading content can be selected and copied; the controls above stay out of it.
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards)) {
                            if (recipe.ingredients.isEmpty()) {
                                Text(
                                    t(Msg.NO_STRUCTURED_INGREDIENTS),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(t(Msg.INGREDIENTS), style = MaterialTheme.typography.titleMedium)
                                var group: String? = null
                                for (mapped in recipe.ingredients) {
                                    val line = mapped.line
                                    if (line.group != group) {
                                        group = line.group
                                        group?.let { SectionHeader(it) }
                                    }
                                    Column {
                                        Text("•  ${line.displayText(t.locale)}")
                                        mapped.ingredientName?.let { name ->
                                            Text(
                                                "     $name" + when {
                                                    mapped.negligible -> " · " + t(Msg.NOT_COUNTED)
                                                    mapped.grams != null -> " · ${formatGrams(mapped.grams, t.locale)}"
                                                    mapped.line.amount == null -> ""
                                                    else -> " · " + t(Msg.WEIGHT_UNKNOWN)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                            if (recipe.instructions.isNotBlank()) {
                                HorizontalDivider()
                                MarkdownText(recipe.instructions)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Five stars, each a 48 dp target; tapping the current rating clears it. */
@Composable
private fun RatingRow(rating: Int?, onRate: (Int?) -> Unit) {
    val t = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(t(Msg.RATING), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
        for (star in 1..5) {
            val filled = rating != null && star <= rating
            IconButton(onClick = { onRate(if (rating == star) null else star) }) {
                Icon(
                    if (filled) AppIcons.StarFilled else AppIcons.Star,
                    contentDescription = t.plural(Plural.RATE_STARS, star),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Pins the recipe as the breakfast or snack eaten every day, for the slots it is planned as. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineButtons(summary: RecipeSummary, routines: Routines, onChange: (Routines) -> Unit) {
    val t = LocalStrings.current
    val canBreakfast = summary.kind == RecipeKind.MEAL && MealSlot.BREAKFAST in summary.mealSlots
    val canSnack = summary.kind == RecipeKind.MEAL && MealSlot.SNACK in summary.mealSlots
    if (!canBreakfast && !canSnack && routines.breakfast != summary.id && routines.snack != summary.id) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
        if (canBreakfast || routines.breakfast == summary.id) {
            FilterChip(
                selected = routines.breakfast == summary.id,
                onClick = { onChange(routines.copy(breakfast = if (routines.breakfast == summary.id) null else summary.id)) },
                label = { Text(t(Msg.EVERY_DAY_BREAKFAST)) },
            )
        }
        if (canSnack || routines.snack == summary.id) {
            FilterChip(
                selected = routines.snack == summary.id,
                onClick = { onChange(routines.copy(snack = if (routines.snack == summary.id) null else summary.id)) },
                label = { Text(t(Msg.EVERY_DAY_SNACK)) },
            )
        }
    }
}

/** Chips that set which meals the planner may use the recipe for; none selected means never. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlannerSettings(summary: RecipeSummary, onChange: (RecipeSettings) -> Unit) {
    val t = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Text(t(Msg.PLAN_AS), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            val isMeal = summary.kind == RecipeKind.MEAL
            MealSlot.entries.forEach { slot ->
                val selected = isMeal && slot in summary.mealSlots
                FilterChip(
                    selected = selected,
                    onClick = {
                        val slots = if (selected) summary.mealSlots - slot else (if (isMeal) summary.mealSlots else emptyList()) + slot
                        onChange(RecipeSettings(if (slots.isEmpty()) RecipeKind.COMPONENT else RecipeKind.MEAL, slots))
                    },
                    label = { Text(t.slot(slot, capitalised = true)) },
                )
            }
        }
        if (summary.kind == RecipeKind.COMPONENT) {
            Text(
                t(Msg.NOT_PLANNED_AS_MEAL),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NutritionCard(nutrition: RecipeNutrition, source: String?) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            Text(t(Msg.PER_SERVING), style = MaterialTheme.typography.labelLarge)
            Text(nutrition.headline(t), style = MaterialTheme.typography.titleLarge)
            with(nutrition.perServing) {
                Text(
                    t(Msg.NUTRITION_MACROS, formatGrams(fatG, t.locale), formatGrams(carbsG, t.locale), formatGrams(fibreG, t.locale)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!nutrition.complete) {
                Text(
                    t.plural(Plural.INCOMPLETE, nutrition.uncounted.size, nutrition.uncounted.joinToString("; ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (nutrition.unquantified.isNotEmpty()) {
                Text(
                    t(Msg.TOO_LOW, nutrition.unquantified.joinToString("; ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (nutrition.approximate) {
                Text(
                    t(Msg.APPROXIMATE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            source?.let {
                Text(t(Msg.SOURCE, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CostCard(cost: RecipeCost) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            Text(t(Msg.COST_PER_SERVING), style = MaterialTheme.typography.labelLarge)
            Text("≈ ${formatEuro(cost.perServingWithOffers, t.locale)}", style = MaterialTheme.typography.titleLarge.numeric())
            if (cost.perServingWithOffers < cost.perServing - 0.005) {
                Text(
                    t(Msg.COST_SAVED, formatEuro(cost.perServing, t.locale), formatEuro(cost.perServing - cost.perServingWithOffers, t.locale)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (cost.estimated.isNotEmpty()) {
                Text(
                    t(Msg.COST_ESTIMATED, cost.estimated.joinToString("; ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(t(Msg.COST_SOURCE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ErrorMessage(title: String, detail: String, onRetry: () -> Unit) {
    val t = LocalStrings.current
    val openServer = LocalOpenServerSettings.current
    Column(
        Modifier.fillMaxSize().padding(Spacing.Message),
        verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        // A long technical message gets a one-line summary and a "Details" button.
        if (detail.length <= 100 && '\n' !in detail) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ErrorBanner(summary = detail.lineSequence().first().take(100) + "…", details = detail, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            Button(onClick = onRetry) { Text(t(Msg.TRY_AGAIN)) }
            if (openServer != null) TextButton(onClick = openServer) { Text(t(Msg.SERVER_SETTINGS)) }
        }
    }
}

/** Opens the server settings; provided by [App] so any error message can offer it. */
val LocalOpenServerSettings = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }
