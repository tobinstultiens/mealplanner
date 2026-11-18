package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeCost
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

/**
 * What the planned meal a recipe was opened from says about cooking it: [portions] of it are eaten,
 * [cookPortions] are to be cooked now (those of tomorrow's leftover lunch included, 0 for that
 * lunch itself). Opened from the recipe list there is none, and the recipe shows its own servings.
 * [place] says where the meal is in the plan and [planned] that it is still to be eaten, which is
 * when cook mode offers to mark it eaten; without a place there is nothing to mark.
 */
data class MealToCook(
    val recipeId: Long,
    val portions: Double,
    val cookPortions: Double,
    val skipped: Boolean,
    val place: MealPlace? = null,
    val planned: Boolean = false,
) {
    companion object {
        /** Keeps the meal across a re-created screen; no meal is an empty list. */
        val Saver = listSaver<MealToCook?, Any>(
            save = { meal ->
                meal?.let {
                    listOf(it.recipeId, it.portions, it.cookPortions, it.skipped, it.planned, it.place?.weekStart ?: "", it.place?.day ?: 0, it.place?.slot?.name ?: "")
                }.orEmpty()
            },
            restore = { v ->
                if (v.isEmpty()) null else MealToCook(
                    v[0] as Long, v[1] as Double, v[2] as Double, v[3] as Boolean,
                    place = (v[5] as String).takeIf { it.isNotEmpty() }?.let { MealPlace(it, v[6] as Int, MealSlot.valueOf(v[7] as String)) },
                    planned = v[4] as Boolean,
                )
            },
        )
    }
}

/** Where a planned meal is: the plan's week (its first day), the day in it and the slot, which is what the status route takes. */
data class MealPlace(val weekStart: String, val day: Int, val slot: MealSlot)

/** What [this] meal of the week starting [weekStart] passes on to its recipe; null from a server that does not say how much to cook, so the recipe shows as written. */
fun PlannedMeal.toCook(weekStart: String): MealToCook? = cookPortions?.let {
    MealToCook(recipeId, portions, it, status == MealStatus.SKIPPED, MealPlace(weekStart, day, slot), planned = status == MealStatus.PLANNED)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeDetailScreen(
    api: ApiClient,
    recipeId: Long,
    nutritionSource: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    meal: MealToCook? = null,
    onOpenMapping: () -> Unit = {},
    initiallyCooking: Boolean = false,
) {
    val t = LocalStrings.current
    val scope = rememberCoroutineScope()
    val loaded = rememberLoad<RecipeDetail>("recipe:$recipeId")
    var state by loaded
    var routines by remember { mutableStateOf<Routines?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val frontReturns = rememberFrontReturns()
    val scroll = rememberScrollState()
    val snackbar = remember { SnackbarHostState() }
    // A recipe of the bank is read-only until it is added: whether the planner may pick it is asked for with the adding.
    var pickOnAdd by rememberSaveable(recipeId) { mutableStateOf(true) }
    var acting by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    // Cook mode and its ticks live as long as the recipe is open (App forgets them with the screen).
    var cookMode by rememberSaveable(recipeId) { mutableStateOf(initiallyCooking) }
    var progress by rememberSaveable(recipeId, stateSaver = CookProgress.Saver) { mutableStateOf(CookProgress()) }
    var eaten by rememberSaveable(recipeId) { mutableStateOf(false) }
    BackHandler(enabled = cookMode) { cookMode = false }
    LaunchedEffect(loaded, reload, frontReturns) {
        loaded.refresh { api.recipe(recipeId) }
        (load { api.routines() } as? Load.Ready)?.value?.let { routines = it }
    }

    /** Shows the recipe the server answered after a change, or tells why the change failed. */
    suspend fun settle(result: Load<RecipeDetail>, failed: Msg) {
        when (result) {
            is Load.Ready -> state = result
            is Load.Failed -> snackbar.showSnackbar(t(failed, result.message))
            Load.Loading -> Unit
        }
    }

    fun add() {
        if (acting) return
        acting = true
        scope.launch {
            val result = load { api.setRecipeAdded(recipeId, added = true, inRotation = pickOnAdd) }
            acting = false
            settle(result, Msg.ADDED_FAILED)
            if (result is Load.Ready) {
                val summary = result.value.summary
                snackbar.announceAdded(t, t(Msg.ADDED_ONE, summary.title), unlinkedIngredients(listOf(summary)), onOpenMapping) {
                    settle(load { api.setRecipeAdded(recipeId, added = false) }, Msg.REMOVE_FAILED)
                }
            }
        }
    }

    /** Puts a recipe from the cooking site back in the bank; a recipe that is planned says so (409) and stays. */
    fun putBack(title: String, wasInRotation: Boolean) {
        if (acting) return
        acting = true
        scope.launch {
            val result = load { api.setRecipeAdded(recipeId, added = false) }
            acting = false
            settle(result, Msg.REMOVE_FAILED)
            if (result is Load.Ready) {
                // The bank view's switch shows what it was (the server's answer is the default), so that adding it again keeps it.
                pickOnAdd = wasInRotation
                val undo = snackbar.showSnackbar(t(Msg.REMOVED_ONE, title), actionLabel = t(Msg.UNDO), duration = SnackbarDuration.Long)
                if (undo == SnackbarResult.ActionPerformed) settle(load { api.setRecipeAdded(recipeId, added = true, inRotation = wasInRotation) }, Msg.ADDED_FAILED)
            }
        }
    }

    /** Sets the planned meal this recipe was opened from to eaten, by the route Today and the Plan use; they load it again when they come back. */
    fun markEaten(place: MealPlace) {
        if (acting) return
        acting = true
        scope.launch {
            val result = load { api.setMealStatus(place.weekStart, place.day, place.slot, MealStatus.EATEN) }
            acting = false
            when (result) {
                is Load.Ready -> eaten = true
                is Load.Failed -> snackbar.showSnackbar(t(Msg.COOK_MARK_EATEN_FAILED, result.message))
                Load.Loading -> Unit
            }
        }
    }

    fun delete() {
        acting = true
        scope.launch {
            val result = load { api.deleteRecipe(recipeId) }
            acting = false
            when (result) {
                is Load.Ready -> onBack()
                is Load.Failed -> snackbar.showSnackbar(t(Msg.DELETE_FAILED, result.message))
                Load.Loading -> Unit
            }
        }
    }

    val opened = (state as? Load.Ready)?.value?.summary
    if (confirmingDelete && opened != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(t(Msg.DELETE_RECIPE_TITLE, opened.title)) },
            text = { Text(t(Msg.DELETE_RECIPE_TEXT)) },
            confirmButton = { TextButton(onClick = { confirmingDelete = false; delete() }) { Text(t(Msg.DELETE), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(t(Msg.CANCEL)) } },
        )
    }

    KeepScreenOn(cookMode)
    // Back from cook mode goes to the recipe, back from the recipe leaves it.
    SubScreen(opened?.title ?: t(Msg.RECIPE), if (cookMode) ({ cookMode = false }) else onBack, snackbar = snackbar, notice = { RefreshNotice(loaded, aboveSystemBar = true) { reload++ } }, actions = {
        val madeHere = opened?.source.let { it == RecipeSource.MANUAL || it == RecipeSource.GENERATED }
        if (madeHere) BarAction(AppIcons.Edit, t(Msg.EDIT_RECIPE), onEdit)
        // What leaves a recipe: from the cooking site it goes back to the bank, one made in the app is deleted.
        if (opened != null && opened.added) {
            if (madeHere) {
                OverflowMenu(MenuAction(AppIcons.Close, t(Msg.DELETE)) { confirmingDelete = true })
            } else {
                OverflowMenu(MenuAction(AppIcons.Close, t(Msg.REMOVE_FROM_MINE)) { putBack(opened.title, opened.inRotation) })
            }
        }
    }) { padding ->
        when (val current = state) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Box(Modifier.padding(padding)) {
                ErrorMessage(t(Msg.RECIPE_LOAD_FAILED), current) { reload++ }
            }
            is Load.Ready -> {
                val recipe = current.value
                // Opened from a planned meal: the amounts are for what is cooked now, unless asked as written.
                var asWritten by rememberSaveable(recipeId) { mutableStateOf(false) }
                val cooking = meal?.takeIf { it.recipeId == recipeId }
                val servings = recipe.summary.servings?.takeIf { it > 0 }
                val factor = if (cooking != null && servings != null && cooking.cookPortions > 0 && !asWritten) cooking.cookPortions / servings else null
                if (cookMode) {
                    val place = cooking?.takeIf { it.planned && !eaten }?.place
                    CookMode(
                        recipe, factor, progress, { progress = it },
                        onMarkEaten = place?.let { { markEaten(it) } },
                        modifier = Modifier.padding(padding),
                    )
                    return@SubScreen
                }
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(Spacing.ScreenEdge),
                    verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
                ) {
                    if (recipe.ingredients.isNotEmpty() || recipe.instructions.isNotBlank()) {
                        Button(onClick = { cookMode = true }, Modifier.fillMaxWidth()) { Text(t(Msg.COOK)) }
                    }
                    recipePicture(recipe.summary.imageUrl)?.let { RecipePhoto(it, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
                    if (recipe.summary.tags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                            recipe.summary.tags.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                        }
                    }
                    recipe.summary.servings?.let {
                        Text(t.plural(Plural.MAKES_SERVINGS, it), style = MaterialTheme.typography.labelLarge)
                    }
                    if (recipe.summary.removedFromSite) {
                        Text(t(Msg.REMOVED_FROM_SITE), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!recipe.summary.added) {
                        AddFromBank(pickOnAdd, acting, onPick = { pickOnAdd = it }, onAdd = ::add)
                    }
                    recipe.summary.nutrition?.let { NutritionCard(it, nutritionSource) }
                    recipe.summary.cost?.let { CostCard(it) }
                    // Rating, the rotation, "Plan as" and the routines are about one of my recipes: none until it is added.
                    if (recipe.summary.added) {
                        RatingRow(recipe.summary.rating) { rating ->
                            scope.launch { (load { api.rateRecipe(recipeId, rating) } as? Load.Ready)?.let { state = it } }
                        }
                        RotationRow(recipe.summary.inRotation, enabled = !acting) { inRotation ->
                            acting = true
                            scope.launch {
                                val result = load { api.setRecipeRotation(recipeId, inRotation) }
                                acting = false
                                settle(result, Msg.ROTATION_FAILED)
                            }
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
                    }
                    if (cooking != null && servings != null) {
                        Text(cooking.sentence(t), style = MaterialTheme.typography.titleMedium)
                        if (cooking.cookPortions > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t(Msg.AS_WRITTEN, t.plural(Plural.SERVINGS, servings)), Modifier.weight(1f))
                                Switch(checked = asWritten, onCheckedChange = { asWritten = it })
                            }
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
                                    val line = factor?.let { mapped.line.scaled(it) } ?: mapped.line
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
                                                    mapped.grams != null -> " · ${formatGrams(mapped.grams?.times(factor ?: 1.0), t.locale)}"
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

/**
 * What a recipe of the bank offers instead of rating and planning: adding it to my recipes, and under
 * the button the choice whether the planner may pick it ([pick], on by default).
 */
@Composable
private fun AddFromBank(pick: Boolean, busy: Boolean, onPick: (Boolean) -> Unit, onAdd: () -> Unit) {
    val t = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
        Button(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(t(Msg.ADD_TO_MY_RECIPES)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t(Msg.LET_PLANNER_PICK), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = pick, onCheckedChange = onPick)
        }
    }
}

/** The switch that puts one of my recipes in the rotation the planner picks from, with a line on what that means. */
@Composable
private fun RotationRow(inRotation: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t(Msg.IN_MY_ROTATION), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Switch(checked = inRotation, onCheckedChange = onChange, enabled = enabled)
        }
        Text(
            t(if (inRotation) Msg.ROTATION_ON_HINT else Msg.ROTATION_OFF_HINT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
