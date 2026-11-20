package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.SpareMove
import dev.tobins.mealplanner.api.WeekPlan
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The week plan: one card per day with its meals and totals against the targets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    api: ApiClient,
    /** Opens a recipe from a meal: its id and what the meal says about cooking it (a side's, for a side). */
    onOpenRecipe: (Long, MealToCook?) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenRecipes: () -> Unit,
    onOpenBank: () -> Unit,
    /** Opens what was in a meal, to adjust it (issue 226). */
    onAdjust: (MealPlace) -> Unit = {},
    /** The week (its Monday) to show first instead of this week: the one a tapped notification asks for. */
    startWeek: String? = null,
) {
    val t = LocalStrings.current
    val today = rememberToday()
    val now = mondayOf(today)
    val frontReturns = rememberFrontReturns()
    // The week that was this week when the screen last looked; the shown week follows it when it moves on (see followedDate).
    var thisWeek by rememberSaveable { mutableStateOf(now) }
    var weekStart by rememberSaveable { mutableStateOf(startWeek ?: thisWeek) }
    val loaded = rememberLoad<WeekPlan>("plan:$weekStart")
    var state by loaded
    var reload by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // My recipes again whenever the app comes back to the front or a prep is started: what keeps may have been set
    // elsewhere meanwhile, and the Prep dialog offers the recipes that keep (issue 263).
    var prepsStarted by remember { mutableIntStateOf(0) }
    val myRecipes by rememberMyRecipes(api, frontReturns to prepsStarted)
    val pictures = myRecipes?.let(::pictureUrls).orEmpty()
    // Known once my recipes are: with none of them in the rotation the planner has nothing to pick from, which is why slots stay empty.
    val emptyRotation = myRecipes?.let(::rotationIsEmpty) == true
    // The meal a new recipe is being written for.
    var generatingFor by remember { mutableStateOf<PlannedMeal?>(null) }
    // The slot a recipe is being chosen for: a planned meal's or an empty one's.
    var choosingFor by remember { mutableStateOf<Pair<Int, MealSlot>?>(null) }
    // The prep being made (issue 234), the prep being removed with the title of its recipe, and the shares of skipped prepped meals still in the box.
    var prepping by remember { mutableStateOf<PrepChoice?>(null) }
    LaunchedEffect(prepping != null) { if (prepping != null) prepsStarted++ }
    var removingPrep by remember { mutableStateOf<Pair<String, MealPrep>?>(null) }
    var spares by remember { mutableStateOf<List<SparePortion>>(emptyList()) }
    val preppable = myRecipes?.let(::preppable).orEmpty()

    /** Asks for the shares in the box today again; a failure leaves the card as it was, as the plan says what went wrong. */
    suspend fun refreshSpares() {
        (load { api.spareShares(today) } as? Load.Ready)?.let { spares = it.value }
    }

    LaunchedEffect(now) {
        if (now != thisWeek) {
            weekStart = followedDate(thisWeek, weekStart, now)
            thisWeek = now
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(loaded, reload, frontReturns) { loaded.refresh { api.plan(weekStart) } }
    LaunchedEffect(reload, frontReturns, today) { refreshSpares() }
    // The week after the one shown, while the Prep dialog is open: a batch may be eaten into it, as a side next to its meals too.
    var nextWeek by remember(weekStart) { mutableStateOf<WeekPlan?>(null) }
    LaunchedEffect(prepping != null, weekStart) {
        if (prepping != null) (load { api.plan(plusDays(weekStart, 7)) } as? Load.Ready)?.let { nextWeek = it.value }
    }

    /** Shows another week the user stepped to, from its top. */
    fun show(week: String) {
        weekStart = week
        scope.launch { listState.scrollToItem(0) }
    }

    /** Runs a change on the server and shows the plan it returns. */
    fun change(action: suspend () -> WeekPlan) {
        if (busy) return
        busy = true
        scope.launch {
            when (val result = load(action)) {
                is Load.Ready -> {
                    state = result
                    refreshSpares()
                }
                is Load.Failed -> snackbar.showSnackbar(failureSentence(result))
                Load.Loading -> Unit
            }
            busy = false
        }
    }

    generatingFor?.let { meal ->
        GenerateDialog(
            onDismiss = { generatingFor = null },
            fixedSlot = meal.slot,
            title = t(Msg.PLAN_NEW_FOR, t.slot(meal.slot), dayLabel(plusDays(weekStart, meal.day), t.locale)),
        ) { request ->
            state = Load.Ready(api.generateIntoPlan(weekStart, meal.day, meal.slot, request))
            generatingFor = null
        }
    }

    choosingFor?.let { (day, slot) ->
        RecipePicker(
            api, slot,
            title = t(Msg.PLAN_CHOOSE_FOR, t.slot(slot), dayLabel(plusDays(weekStart, day), t.locale)),
            onDismiss = { choosingFor = null },
            onBrowseBank = { choosingFor = null; onOpenBank() },
        ) { recipe ->
            // A refusal (such as "this meal was eaten already") or a server from before the route: the picker shows why and stays open.
            when (val result = load { api.chooseRecipe(weekStart, day, slot, recipe.id) }) {
                is Load.Ready -> {
                    state = Load.Ready(result.value ?: throw AppError(t(Msg.PLAN_CHOOSE_UNSUPPORTED)))
                    choosingFor = null
                }
                is Load.Failed -> throw AppError(failureSentence(result))
                Load.Loading -> Unit
            }
        }
    }

    prepping?.let { start ->
        val hosts = sideHosts((state as? Load.Ready)?.value, nextWeek)
        PrepDialog(start, weekDays = prepDays(weekStart, today), recipes = preppable, hosts = hosts, onDismiss = { prepping = null }) { input ->
            when (val result = load { api.createPrep(weekStart, input) }) {
                is Load.Ready -> {
                    state = result
                    prepping = null
                    refreshSpares()
                    null
                }
                is Load.Failed -> failureSentence(result)
                Load.Loading -> null
            }
        }
    }

    removingPrep?.let { (title, prep) ->
        RemovePrepDialog(title, prep, onDismiss = { removingPrep = null }) {
            removingPrep = null
            change { api.removePrep(weekStart, prep.id) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    PeriodStepper(
                        label = t(Msg.WEEK_OF, dayMonthLabel(weekStart, t.locale)),
                        previous = t(Msg.PREVIOUS_WEEK),
                        next = t(Msg.NEXT_WEEK),
                        onPrevious = { show(plusDays(weekStart, -7)) },
                        onNext = { show(plusDays(weekStart, 7)) },
                    )
                },
            )
        },
        // The line has a place of its own: the content ends above it, and snackbars show above it.
        bottomBar = { RefreshNotice(loaded) { reload++ } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Column(Modifier.padding(padding)) {
                if (current.status == 409) {
                    EmptyState(
                        AppIcons.Person,
                        t(Msg.PLAN_SET_UP_PROFILE),
                        actionLabel = t(Msg.OPEN_PROFILE),
                        onAction = onOpenProfile,
                    )
                } else {
                    ErrorMessage(t(Msg.PLAN_LOAD_FAILED), current) { reload++ }
                }
            }
            is Load.Ready -> {
                val plan = current.value
                val hasMeals = plan.days.any { it.meals.isNotEmpty() }
                // Meals of last week's batch alone do not plan the week: its slots are not missing recipes yet.
                val planned = plan.hasOwnMeals
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(Spacing.ScreenEdge),
                    verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                            Text(
                                plan.targets.let { targets ->
                                    unbrokenParts(
                                        targets.fibreG?.let { t(Msg.DAILY_TARGET_FIBRE, targets.kcal, targets.proteinG, it) }
                                            ?: t(Msg.DAILY_TARGET, targets.kcal, targets.proteinG),
                                    )
                                },
                                style = MaterialTheme.typography.bodyMedium.numeric(),
                            )
                            plan.cost?.let { cost ->
                                val saved = (plan.costWithoutOffers ?: cost) - cost
                                Text(
                                    if (saved >= 0.01) t(Msg.WEEK_COST_SAVED, formatEuro(cost, t.locale), formatEuro(saved, t.locale))
                                    else t(Msg.WEEK_COST, formatEuro(cost, t.locale)),
                                    style = MaterialTheme.typography.bodyMedium.numeric(),
                                )
                            }
                            Button(enabled = !busy, onClick = { change { api.generatePlan(weekStart) } }) {
                                Text(if (planned) t(Msg.REPLAN) else t(Msg.PLAN_WEEK))
                            }
                            // A prep is made today or later: a week that is over has no day for it.
                            val days = prepDays(weekStart, today)
                            TextButton(enabled = !busy && days.isNotEmpty(), onClick = { prepping = newPrep(preppable, days, today) }) {
                                Text(t(Msg.PREP_A_RECIPE))
                            }
                            if (plan.emptySlots.isNotEmpty() && emptyRotation) {
                                // Not for want of recipes in general: none of them is in the rotation.
                                Text(
                                    t(Msg.ROTATION_EMPTY_PLAN),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = onOpenRecipes) { Text(t(Msg.OPEN_RECIPES)) }
                            } else if (planned && plan.emptySlots.isNotEmpty()) {
                                Text(
                                    t(Msg.NO_RECIPES_FOR, plan.emptySlots.joinToString { t.slot(it) }),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    for (spare in sparesShown(spares, weekStart, today)) {
                        item(key = "spare:${spare.prepId}:${spare.skipped.date}:${spare.skipped.slot}") {
                            SpareCard(spare, today, enabled = !busy) { slot ->
                                change { api.eatSpare(weekStart, spare.prepId, SpareMove(spare.skipped, slot)) }
                            }
                        }
                    }
                    if (!hasMeals) {
                        item { EmptyState(Spot.NothingPlanned, t(Msg.NOTHING_PLANNED_WEEK), Modifier.height(240.dp)) }
                    }
                    if (hasMeals) {
                        itemsIndexed(plan.days, key = { _, day -> day.date }) { index, day ->
                            DayCard(
                                day = day,
                                dayIndex = index,
                                today = today,
                                targets = plan.targets,
                                // A slot is offered on every day that has no meal in it.
                                missingSlots = MealSlot.entries.filter { slot -> day.meals.none { it.slot == slot } },
                                enabled = !busy,
                                pictures = pictures,
                                preppable = preppable.mapTo(HashSet()) { it.id },
                                onOpenRecipe = { meal -> onOpenRecipe(meal.recipeId, meal.toCook(weekStart)) },
                                onOpenSide = { meal, side -> onOpenRecipe(side.recipeId, side.toCook(meal.status == MealStatus.SKIPPED)) },
                                onReroll = { meal -> change { api.rerollMeal(weekStart, meal.day, meal.slot) } },
                                onGenerate = { meal -> generatingFor = meal },
                                onChoose = { day, slot -> choosingFor = day to slot },
                                onToggleSkipped = { meal ->
                                    val status = if (meal.status == MealStatus.SKIPPED) MealStatus.PLANNED else MealStatus.SKIPPED
                                    change {
                                        api.setMealStatus(weekStart, meal.day, meal.slot, status)
                                        api.plan(weekStart)
                                    }
                                },
                                onToggleLock = { meal -> change { api.setMealLocked(weekStart, meal.day, meal.slot, !meal.locked) } },
                                onAdjust = { meal -> onAdjust(MealPlace(weekStart, meal.day, meal.slot)) },
                                onPrep = { meal ->
                                    val date = plusDays(weekStart, meal.day)
                                    prepping = PrepChoice(preppable.first { it.id == meal.recipeId }, madeOn = date, firstDay = today).with().toggled(date, meal.slot)
                                },
                                onRemovePrep = { title, prep -> removingPrep = title to prep },
                                onAddSide = { meal ->
                                    val date = plusDays(weekStart, meal.day)
                                    prepping = PrepChoice(preppable.singleOrNull(), madeOn = date, firstDay = today, side = SIDE_PORTIONS.last()).with().toggled(date, meal.slot)
                                },
                                onRemoveSide = { meal -> change { api.removeSide(weekStart, meal.day, meal.slot) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCard(
    day: PlanDay,
    dayIndex: Int,
    /** Today's ISO date: a prep is not made on a day that has passed. */
    today: String,
    targets: DailyTargets,
    missingSlots: List<MealSlot>,
    enabled: Boolean,
    pictures: Map<Long, String>,
    /** The recipes a prep can be made of, by id. */
    preppable: Set<Long>,
    onOpenRecipe: (PlannedMeal) -> Unit,
    onReroll: (PlannedMeal) -> Unit,
    onGenerate: (PlannedMeal) -> Unit,
    onChoose: (Int, MealSlot) -> Unit,
    onToggleSkipped: (PlannedMeal) -> Unit,
    onToggleLock: (PlannedMeal) -> Unit,
    onAdjust: (PlannedMeal) -> Unit,
    onPrep: (PlannedMeal) -> Unit,
    /** Removes a prep after naming its meals: the meal's own or its side's, with the title of its recipe. */
    onRemovePrep: (String, MealPrep) -> Unit,
    onOpenSide: (PlannedMeal, MealSide) -> Unit,
    onAddSide: (PlannedMeal) -> Unit,
    onRemoveSide: (PlannedMeal) -> Unit,
) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            Text(dayLabel(day.date, t.locale), style = MaterialTheme.typography.titleMedium)
            Text(dayTotals(day, targets, t), style = MaterialTheme.typography.bodyMedium.numeric())
            LinearProgressIndicator(
                progress = { (day.kcal / targets.kcal).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            // A side added by hand may take the day over its calories: said, not refused (issue 234).
            day.overWithSides(targets)?.let { (over, sides) ->
                Text(t(Msg.DAY_OVER_WITH_SIDES, over, sides), style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.error)
            }
            if (day.meals.isEmpty()) {
                Text(t(Msg.NOTHING_PLANNED), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (meal in day.meals) {
                // The whole meal opens its recipe; the two icon buttons take their own taps.
                Column(Modifier.fillMaxWidth().clickable { onOpenRecipe(meal) }) {
                    // The title gets the full width left of the icon buttons; the numbers sit below.
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(
                                t.mealLabel(meal),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                meal.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textDecoration = if (meal.status == MealStatus.SKIPPED) TextDecoration.LineThrough else null,
                            )
                        }
                        // A meal with a photo shows it before the buttons; one without has nothing there.
                        recipePicture(pictures[meal.recipeId])?.let { RecipePhoto(it, Modifier.padding(top = 4.dp, end = Spacing.Related).size(48.dp)) }
                        BarAction(
                            if (meal.locked) AppIcons.Lock else AppIcons.LockOpen,
                            if (meal.locked) t(Msg.UNLOCK_SLOT, t.slot(meal.slot)) else t(Msg.LOCK_SLOT, t.slot(meal.slot)),
                            onClick = { onToggleLock(meal) },
                            // A prepped meal stays locked: the batch is made for it.
                            enabled = enabled && meal.prep == null,
                        )
                        Box {
                            var menu by remember { mutableStateOf(false) }
                            BarAction(
                                AppIcons.Swap,
                                t(Msg.SWAP_SLOT, t.slot(meal.slot)),
                                onClick = { menu = true },
                                enabled = enabled,
                            )
                            // A locked meal (one the user chose, or locked) keeps its recipe: only choosing or leaving it out is left.
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(t(Msg.ANOTHER_RECIPE)) }, enabled = !meal.locked, onClick = { menu = false; onReroll(meal) })
                                DropdownMenuItem(text = { Text(t(Msg.CHOOSE_RECIPE)) }, onClick = { menu = false; onChoose(meal.day, meal.slot) })
                                // A skipped meal counts for nothing, so there is nothing in it to adjust.
                                DropdownMenuItem(
                                    text = { Text(t(Msg.ADJUST_WHAT_WAS_IN_IT)) },
                                    enabled = meal.status != MealStatus.SKIPPED,
                                    onClick = { menu = false; onAdjust(meal) },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (meal.status == MealStatus.SKIPPED) t(Msg.EAT_AFTER_ALL) else t(Msg.NOT_EATING_THIS)) },
                                    onClick = { menu = false; onToggleSkipped(meal) },
                                )
                                // Left out for a skipped meal, where it would be greyed with no reason: eat it after all first.
                                if (meal.status != MealStatus.SKIPPED) {
                                    DropdownMenuItem(text = { Text(t(Msg.WRITE_NEW_RECIPE)) }, enabled = !meal.locked, onClick = { menu = false; onGenerate(meal) })
                                }
                                val lunchOrDinner = meal.slot == MealSlot.LUNCH || meal.slot == MealSlot.DINNER
                                val prep = meal.prep
                                if (prep != null) {
                                    DropdownMenuItem(text = { Text(t(Msg.REMOVE_PREP)) }, onClick = { menu = false; onRemovePrep(meal.title, prep) })
                                } else if (meal.recipeId in preppable && lunchOrDinner && day.date >= today) {
                                    DropdownMenuItem(text = { Text(t(Msg.PREP_THIS_RECIPE)) }, onClick = { menu = false; onPrep(meal) })
                                }
                                val side = meal.side
                                if (side != null) {
                                    DropdownMenuItem(text = { Text(t(Msg.REMOVE_SIDE)) }, onClick = { menu = false; onRemoveSide(meal) })
                                    DropdownMenuItem(text = { Text(t(Msg.REMOVE_SIDE_PREP)) }, onClick = { menu = false; onRemovePrep(side.title, side.prep) })
                                } else if (preppable.isNotEmpty() && lunchOrDinner && meal.status == MealStatus.PLANNED && day.date >= today) {
                                    // A side goes next to a meal still to eat, from today on, as a batch is made for the days to come.
                                    DropdownMenuItem(text = { Text(t(Msg.ADD_SIDE)) }, onClick = { menu = false; onAddSide(meal) })
                                }
                            }
                        }
                    }
                    MealNumbers(meal.macrosText(t), meal.cost?.let { "≈ ${formatEuro(it, t.locale)}" }, Modifier.padding(bottom = 4.dp))
                    meal.side?.let { side -> SideRow(meal, side, day.date, onOpen = { onOpenSide(meal, side) }, Modifier.padding(bottom = 4.dp)) }
                }
            }
            if (missingSlots.isNotEmpty()) {
                Box {
                    var menu by remember { mutableStateOf(false) }
                    TextButton(enabled = enabled, onClick = { menu = true }) { Text(t(Msg.PLAN_ADD_MEAL)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for (slot in missingSlots) {
                            DropdownMenuItem(text = { Text(t.slot(slot, capitalised = true)) }, onClick = { menu = false; onChoose(dayIndex, slot) })
                        }
                    }
                }
            }
        }
    }
}

/** A day's kcal and protein against the targets, and its fibre when the server sends fibre and its target. */
private fun dayTotals(day: PlanDay, targets: DailyTargets, t: Strings): String {
    val kcal = day.kcal.roundToInt()
    val protein = day.proteinG.roundToInt()
    val fibre = day.fibreG
    val fibreTarget = targets.fibreG
    return unbrokenParts(
        if (fibre == null || fibreTarget == null) t(Msg.DAY_TOTALS, kcal, targets.kcal, protein, targets.proteinG)
        else t(Msg.DAY_TOTALS_FIBRE, kcal, targets.kcal, protein, targets.proteinG, fibre.roundToInt(), fibreTarget),
    )
}
