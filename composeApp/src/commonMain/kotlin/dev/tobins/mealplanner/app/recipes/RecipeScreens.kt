package dev.tobins.mealplanner.app.recipes

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeCost
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.BackHandler
import dev.tobins.mealplanner.app.KeepScreenOn
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.RefreshNotice
import dev.tobins.mealplanner.app.plan.StandardMealRow
import dev.tobins.mealplanner.app.prices.line
import dev.tobins.mealplanner.app.rememberFrontReturns
import dev.tobins.mealplanner.app.rememberScreenMemory
import dev.tobins.mealplanner.app.ui.AppIcons
import dev.tobins.mealplanner.app.ui.BarAction
import dev.tobins.mealplanner.app.ui.CommonMsg
import dev.tobins.mealplanner.app.ui.CommonPlural
import dev.tobins.mealplanner.app.ui.ErrorMessage
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.LoadingState
import dev.tobins.mealplanner.app.ui.MarkdownText
import dev.tobins.mealplanner.app.ui.MenuAction
import dev.tobins.mealplanner.app.ui.OverflowMenu
import dev.tobins.mealplanner.app.ui.SectionHeader
import dev.tobins.mealplanner.app.ui.Spacing
import dev.tobins.mealplanner.app.ui.SubScreen
import dev.tobins.mealplanner.app.ui.displayText
import dev.tobins.mealplanner.app.ui.formatEuro
import dev.tobins.mealplanner.app.ui.formatGrams
import dev.tobins.mealplanner.app.ui.headline
import dev.tobins.mealplanner.app.ui.numeric
import dev.tobins.mealplanner.app.ui.scaled
import dev.tobins.mealplanner.app.ui.sentence
import dev.tobins.mealplanner.app.ui.servingsText
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/**
 * What the planned meal a recipe was opened from says about cooking it: [portions] of it are eaten,
 * [cookPortions] are to be cooked now (those of tomorrow's leftover lunch included, 0 for that
 * lunch itself). Opened from the recipe list there is none, and the recipe shows its own servings.
 * [place] says where the meal is in the plan and [planned] that it is still to be eaten, which is
 * when cook mode offers to mark it eaten; without a place there is nothing to mark. [prepMadeOn] is the ISO date a
 * prepped batch the meal is of is made (issue 234): its cooking is for the prep's later meals, not tomorrow's lunch.
 */
data class MealToCook(
    val recipeId: Long,
    val portions: Double,
    val cookPortions: Double,
    val skipped: Boolean,
    val place: MealPlace? = null,
    val planned: Boolean = false,
    val prepMadeOn: String? = null,
) {
    /** A later meal of a prepped batch, not skipped: nothing is cooked for it, its portion is in a box already (issue 263). */
    val fromBatch: Boolean get() = prepMadeOn != null && cookPortions <= 0.0 && !skipped

    /**
     * What the recipe's amounts are scaled by when opened from this meal, for a recipe of [servings]: to what is cooked
     * now, or for a later meal of a batch ([fromBatch]) to its own portion, which is what its box holds, so that nothing
     * reads as something to buy or cook (issue 263). Null for the amounts as written: when asked ([asWritten]), or for a
     * meal with nothing to cook of its own (skipped, or yesterday's leftovers).
     */
    fun amountsFactor(servings: Int, asWritten: Boolean): Double? = when {
        asWritten -> null
        cookPortions > 0.0 -> cookPortions / servings
        fromBatch -> portions / servings
        else -> null
    }

    companion object {
        /** Keeps the meal across a re-created screen; no meal is an empty list. */
        val Saver = listSaver<MealToCook?, Any>(
            save = { meal ->
                meal?.let {
                    listOf(
                        it.recipeId, it.portions, it.cookPortions, it.skipped, it.planned, it.place?.weekStart ?: "", it.place?.day ?: 0,
                        it.place?.slot?.name ?: "", it.prepMadeOn ?: "",
                    )
                }.orEmpty()
            },
            restore = { v ->
                if (v.isEmpty()) null else MealToCook(
                    v[0] as Long, v[1] as Double, v[2] as Double, v[3] as Boolean,
                    place = (v[5] as String).takeIf { it.isNotEmpty() }?.let { MealPlace(it, v[6] as Int, MealSlot.valueOf(v[7] as String)) },
                    planned = v[4] as Boolean,
                    prepMadeOn = (v.getOrNull(8) as? String)?.takeIf { it.isNotEmpty() },
                )
            },
        )
    }
}

/** Where a planned meal is: the plan's week (its first day), the day in it and the slot, which is what the status route takes. */
data class MealPlace(val weekStart: String, val day: Int, val slot: MealSlot) {
    companion object {
        /** Keeps the place across a re-created screen; no place is an empty list. */
        val Saver = listSaver<MealPlace?, Any>(
            save = { place -> place?.let { listOf(it.weekStart, it.day, it.slot.name) }.orEmpty() },
            restore = { v -> if (v.isEmpty()) null else MealPlace(v[0] as String, v[1] as Int, MealSlot.valueOf(v[2] as String)) },
        )
    }
}

/** What [this] meal of the week starting [weekStart] passes on to its recipe; null from a server that does not say how much to cook, so the recipe shows as written. */
fun PlannedMeal.toCook(weekStart: String): MealToCook? = cookPortions?.let {
    MealToCook(recipeId, portions, it, status == MealStatus.SKIPPED, MealPlace(weekStart, day, slot), planned = status == MealStatus.PLANNED, prepMadeOn = prep?.madeOn)
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
    val frontReturns = rememberFrontReturns()
    val scroll = rememberScrollState()
    val snackbar = remember { SnackbarHostState() }
    // What the page loads, sends and shows while that runs or fails is the state's; this draws it. The state reads the latest
    // language and the screen's way to the mapping when it needs them.
    val latestStrings by rememberUpdatedState(t)
    val latestOpenMapping by rememberUpdatedState(onOpenMapping)
    val memory = rememberScreenMemory()
    val server = remember(api) { api.recipeServer() }
    val voice = remember(snackbar) { SnackbarVoice(snackbar, { latestStrings }, { latestOpenMapping() }) }
    val page = remember(server, memory, recipeId) { RecipeState(server, scope, memory, recipeId, { latestStrings }, voice) }
    val state = page.recipe
    val routines = page.routines
    val acting = page.acting
    // A recipe of the bank is read-only until it is added: whether the planner may pick it is asked for with the adding.
    var pickOnAdd by rememberSaveable(recipeId) { mutableStateOf(true) }
    var confirmingDelete by remember { mutableStateOf(false) }
    // Cook mode and its ticks live as long as the recipe is open (App forgets them with the screen).
    var cookMode by rememberSaveable(recipeId) { mutableStateOf(initiallyCooking) }
    var progress by rememberSaveable(recipeId, stateSaver = CookProgress.Saver) { mutableStateOf(CookProgress()) }
    var eaten by rememberSaveable(recipeId) { mutableStateOf(false) }
    BackHandler(enabled = cookMode) { cookMode = false }
    LaunchedEffect(page.loaded, page.reloads, frontReturns) { page.refresh() }

    val opened = (state as? Load.Ready)?.value?.summary
    if (confirmingDelete && opened != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(t(RecipesMsg.DELETE_RECIPE_TITLE, opened.title)) },
            // A copy of a cooking-site recipe goes for good too, but the site's file comes back to the bank.
            text = { Text(t(if (opened.source == RecipeSource.COOKING_SITE) RecipesMsg.DELETE_TAKEN_RECIPE_TEXT else RecipesMsg.DELETE_RECIPE_TEXT)) },
            confirmButton = { TextButton(onClick = { confirmingDelete = false; page.delete(onBack) }) { Text(t(RecipesMsg.DELETE), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(t(CommonMsg.CANCEL)) } },
        )
    }

    KeepScreenOn(cookMode)
    // Back from cook mode goes to the recipe, back from the recipe leaves it.
    SubScreen(opened?.title ?: t(RecipesMsg.RECIPE), if (cookMode) ({ cookMode = false }) else onBack, snackbar = snackbar, notice = { RefreshNotice(page.loaded, aboveSystemBar = true) { page.reload() } }, actions = {
        // Every one of my recipes is edited in the app (issue 342), also one taken from the cooking site; the bank's is the site's.
        if (opened != null && opened.added) {
            BarAction(AppIcons.Edit, t(RecipesMsg.EDIT_RECIPE), onEdit)
            // What leaves a recipe: one of the cooking site not changed in the app goes back to the bank, any other is deleted.
            if (opened.hasBank()) {
                OverflowMenu(MenuAction(AppIcons.Close, t(RecipesMsg.REMOVE_FROM_MINE)) { page.putBack(opened.title, opened.inRotation) { pickOnAdd = it } })
            } else {
                OverflowMenu(MenuAction(AppIcons.Close, t(RecipesMsg.DELETE)) { confirmingDelete = true })
            }
        }
    }) { padding ->
        when (val current = state) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Box(Modifier.padding(padding)) {
                ErrorMessage(t(RecipesMsg.RECIPE_LOAD_FAILED), current) { page.reload() }
            }
            is Load.Ready -> {
                val recipe = current.value
                // Opened from a planned meal: the amounts are for what is cooked now, unless asked as written.
                var asWritten by rememberSaveable(recipeId) { mutableStateOf(false) }
                val cooking = meal?.takeIf { it.recipeId == recipeId }
                val servings = recipe.summary.servings?.takeIf { it > 0 }
                val factor = servings?.let { cooking?.amountsFactor(it, asWritten) }
                // A later meal of a batch shows what is in its box, under its own heading.
                val ingredientsTitle = cooking?.takeIf { it.fromBatch && factor != null }?.let { t(RecipesMsg.IN_THIS_MEAL, servingsText(it.portions, t)) }
                // The amounts can be shown as written whenever they are scaled for this meal.
                val scalable = cooking != null && (cooking.cookPortions > 0 || cooking.fromBatch)
                if (cookMode) {
                    val place = cooking?.takeIf { it.planned && !eaten }?.place
                    CookMode(
                        recipe, factor, progress, { progress = it },
                        onMarkEaten = place?.let { { page.markEaten(it) { eaten = true } } },
                        modifier = Modifier.padding(padding),
                        ingredientsTitle = ingredientsTitle,
                        top = {
                            // What to cook, as on the recipe, and the amounts can be changed here without going back to it.
                            if (cooking != null && servings != null) {
                                Text(cooking.sentence(t), style = MaterialTheme.typography.titleMedium)
                                if (scalable) AsWrittenRow(servings, asWritten) { asWritten = it }
                            }
                        },
                    )
                    return@SubScreen
                }
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(Spacing.ScreenEdge),
                    verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
                ) {
                    if (recipe.ingredients.isNotEmpty() || recipe.instructions.isNotBlank()) {
                        Button(onClick = { cookMode = true }, Modifier.fillMaxWidth()) { Text(t(RecipesMsg.COOK)) }
                    }
                    recipePicture(recipe.summary.imageUrl)?.let { RecipePhoto(it, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
                    if (recipe.summary.tags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                            recipe.summary.tags.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                        }
                    }
                    // Not for a later meal of a batch, which cooks nothing: its box holds its own portion.
                    recipe.summary.servings?.takeIf { ingredientsTitle == null }?.let {
                        Text(t.plural(RecipesPlural.MAKES_SERVINGS, it), style = MaterialTheme.typography.labelLarge)
                    }
                    if (recipe.summary.removedFromSite) {
                        Text(t(RecipesMsg.REMOVED_FROM_SITE), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!recipe.summary.added) {
                        AddFromBank(pickOnAdd, acting, onPick = { pickOnAdd = it }, onAdd = { page.add(pickOnAdd) })
                    }
                    recipe.summary.nutrition?.let { NutritionCard(it, nutritionSource) }
                    recipe.summary.cost?.let { CostCard(it) }
                    // Rating, the rotation, "Plan as" and the routines are about one of my recipes: none until it is added.
                    if (recipe.summary.added) {
                        RatingRow(recipe.summary.rating) { rating -> page.rate(rating) }
                        RotationRow(recipe.summary.inRotation, enabled = !acting) { inRotation -> page.setInRotation(inRotation) }
                        // A building block is no standard meal (issue 265). Busy while saving, as for the rotation.
                        if (recipe.summary.kind == RecipeKind.MEAL) {
                            StandardMealRow(recipe.summary.standardMeal, page.standardMarked, enabled = !acting) { standard -> page.setStandard(standard) }
                        }
                        PlannerSettings(recipe.summary) { settings -> page.saveSettings(settings) }
                        // Only a meal can be prepped, not a building block. Busy while saving, so a second choice is made on
                        // the answer to the first and does not send the other number as the screen last drew it.
                        if (recipe.summary.kind == RecipeKind.MEAL) {
                            KeepsSettings(RecipeKeeps(recipe.summary.keepsDays, recipe.summary.keepsVacuumDays), enabled = !acting) { keeps -> page.setKeeps(keeps) }
                        }
                        ToppingsSettings(api, recipe, save = page::saveToppings, onSaved = page::toppingsSaved)
                        routines?.let { current ->
                            RoutineButtons(recipe.summary, current) { updated -> page.saveRoutines(updated) }
                        }
                    }
                    if (cooking != null && servings != null) {
                        Text(cooking.sentence(t), style = MaterialTheme.typography.titleMedium)
                        if (scalable) {
                            AsWrittenRow(servings, asWritten) { asWritten = it }
                        }
                    }
                    // The reading content can be selected and copied; the controls above stay out of it.
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards)) {
                            if (recipe.ingredients.isEmpty()) {
                                Text(
                                    t(RecipesMsg.NO_STRUCTURED_INGREDIENTS),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(ingredientsTitle ?: t(RecipesMsg.INGREDIENTS), style = MaterialTheme.typography.titleMedium)
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
                                                    mapped.negligible -> " · " + t(RecipesMsg.NOT_COUNTED)
                                                    mapped.grams != null -> " · ${formatGrams(mapped.grams?.times(factor ?: 1.0), t.locale)}"
                                                    mapped.line.amount == null -> ""
                                                    else -> " · " + t(RecipesMsg.WEIGHT_UNKNOWN)
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

/** The recipe page's [RecipeVoice]: snackbars on [snackbar], in the language [strings] gives, with [openMapping] for the link to unlinked ingredients. */
private class SnackbarVoice(
    private val snackbar: SnackbarHostState,
    private val strings: () -> Strings,
    private val openMapping: () -> Unit,
) : RecipeVoice {
    override suspend fun say(sentence: String) {
        snackbar.showSnackbar(sentence)
    }

    override suspend fun announceAdded(message: String, unlinked: Int, onUndo: suspend () -> Unit) =
        snackbar.announceAdded(strings(), message, unlinked, openMapping, onUndo)

    override suspend fun offerUndo(message: String): Boolean =
        snackbar.showSnackbar(message, actionLabel = strings()(CommonMsg.UNDO), duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed
}

/** The switch that shows the amounts as the recipe is written ([servings] of it) instead of scaled to what is cooked now. */
@Composable
private fun AsWrittenRow(servings: Int, asWritten: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(t(RecipesMsg.AS_WRITTEN, t.plural(CommonPlural.SERVINGS, servings)), Modifier.weight(1f))
        Switch(checked = asWritten, onCheckedChange = onChange)
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
        Button(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(t(RecipesMsg.ADD_TO_MY_RECIPES)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t(RecipesMsg.LET_PLANNER_PICK), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = pick, onCheckedChange = onPick)
        }
    }
}

/**
 * A setting's [label] with its switch at the end of the line: the whole line toggles it, as a tap on the words is meant
 * for the switch too, and reads as one switch to TalkBack.
 */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** The switch that puts one of my recipes in the rotation the planner picks from, with a line on what that means. */
@Composable
private fun RotationRow(inRotation: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        SwitchRow(t(RecipesMsg.IN_MY_ROTATION), inRotation, enabled, onChange)
        Text(
            t(if (inRotation) RecipesMsg.ROTATION_ON_HINT else RecipesMsg.ROTATION_OFF_HINT),
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
        Text(t(RecipesMsg.RATING), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
        for (star in 1..5) {
            val filled = rating != null && star <= rating
            IconButton(onClick = { onRate(if (rating == star) null else star) }) {
                Icon(
                    if (filled) AppIcons.StarFilled else AppIcons.Star,
                    contentDescription = t.plural(RecipesPlural.RATE_STARS, star),
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
                label = { Text(t(RecipesMsg.EVERY_DAY_BREAKFAST)) },
            )
        }
        if (canSnack || routines.snack == summary.id) {
            FilterChip(
                selected = routines.snack == summary.id,
                onClick = { onChange(routines.copy(snack = if (routines.snack == summary.id) null else summary.id)) },
                label = { Text(t(RecipesMsg.EVERY_DAY_SNACK)) },
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
        Text(t(RecipesMsg.PLAN_AS), style = MaterialTheme.typography.labelLarge)
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
                t(RecipesMsg.NOT_PLANNED_AS_MEAL),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * How many days the recipe keeps once made, in a box and vacuum sealed (issue 234): what a prep of it may span. Each a
 * button that opens the choices, "not prepped" and 1 to [RecipeKeeps.MAX_DAYS] days; a server from before preps has
 * neither, and then both read "not prepped".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeepsSettings(keeps: RecipeKeeps, enabled: Boolean, onChange: (RecipeKeeps) -> Unit) {
    val t = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Text(t(RecipesMsg.KEEPS_ONCE_MADE), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            KeepsChoice(RecipesMsg.KEEPS_IN_A_BOX, keeps.days, enabled) { onChange(keeps.copy(days = it)) }
            KeepsChoice(RecipesMsg.KEEPS_VACUUM, keeps.vacuumDays, enabled) { onChange(keeps.copy(vacuumDays = it)) }
        }
    }
}

/** One of [KeepsSettings]' two numbers: a button saying it ([label]), with a menu of the days to choose from. */
@Composable
private fun KeepsChoice(label: Msg, days: Int?, enabled: Boolean, onChoose: (Int?) -> Unit) {
    val t = LocalStrings.current
    fun text(days: Int?) = days?.let { t.plural(CommonPlural.DAYS, it) } ?: t(CommonMsg.KEEPS_NOT)
    Box {
        var open by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { open = true }, enabled = enabled) { Text(t(label, text(days))) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf(null) + (1..RecipeKeeps.MAX_DAYS)).forEach { choice ->
                DropdownMenuItem(text = { Text(text(choice)) }, onClick = { open = false; if (choice != days) onChoose(choice) })
            }
        }
    }
}

@Composable
private fun NutritionCard(nutrition: RecipeNutrition, source: String?) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            Text(t(RecipesMsg.PER_SERVING), style = MaterialTheme.typography.labelLarge)
            Text(nutrition.headline(t), style = MaterialTheme.typography.titleLarge)
            with(nutrition.perServing) {
                Text(
                    t(RecipesMsg.NUTRITION_MACROS, formatGrams(fatG, t.locale), formatGrams(carbsG, t.locale), formatGrams(fibreG, t.locale)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!nutrition.complete) {
                Text(
                    t.plural(RecipesPlural.INCOMPLETE, nutrition.uncounted.size, nutrition.uncounted.joinToString("; ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (nutrition.unquantified.isNotEmpty()) {
                Text(
                    t(RecipesMsg.TOO_LOW, nutrition.unquantified.joinToString("; ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (nutrition.approximate) {
                Text(
                    t(RecipesMsg.APPROXIMATE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            source?.let {
                Text(t(RecipesMsg.SOURCE, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CostCard(cost: RecipeCost) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            Text(t(RecipesMsg.COST_PER_SERVING), style = MaterialTheme.typography.labelLarge)
            Text("≈ ${formatEuro(cost.perServingWithOffers, t.locale)}", style = MaterialTheme.typography.titleLarge.numeric())
            if (cost.perServingWithOffers < cost.perServing - 0.005) {
                Text(
                    t(RecipesMsg.COST_SAVED, formatEuro(cost.perServing, t.locale), formatEuro(cost.perServing - cost.perServingWithOffers, t.locale)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (cost.estimated.isNotEmpty()) {
                Text(
                    t(RecipesMsg.COST_ESTIMATED, cost.estimated.joinToString("; ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(t(RecipesMsg.COST_SOURCE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
