package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.AlertDialog
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
import dev.tobins.mealplanner.api.BatchToMake
import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.SpareMove
import dev.tobins.mealplanner.api.StandardChosen
import dev.tobins.mealplanner.api.StandardMeal
import dev.tobins.mealplanner.api.StandardMeals
import dev.tobins.mealplanner.api.WeekPlan
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
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
    /** Whether to open the planning screen of [startWeek] (issue 255), as the "Plan next week" notification asks. */
    startPlanning: Boolean = false,
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
    // The dialogs stay open across a re-created activity, as the screen keeps its place (issue 263).
    var prepping by rememberSaveable(stateSaver = jsonSaver(PrepChoice.serializer().nullable)) { mutableStateOf(null) }
    LaunchedEffect(prepping != null) { if (prepping != null) prepsStarted++ }
    // The meal the user asked to take out of the plan, until they confirm: it may be a standard meal the planner put there.
    var removingStandard by remember { mutableStateOf<PlannedMeal?>(null) }
    var removingPrep by rememberSaveable(stateSaver = jsonSaver(PairSerializer(String.serializer(), MealPrep.serializer()).nullable)) { mutableStateOf(null) }
    // The meal a side is being added to from a batch still to make (issue 269), with those batches and the new prep instead.
    var addingSide by rememberSaveable(stateSaver = jsonSaver(AddingSide.serializer().nullable)) { mutableStateOf(null) }
    var spares by remember { mutableStateOf<List<SparePortion>>(emptyList()) }
    val preppable = myRecipes?.let(::preppable).orEmpty()
    // The week's standard meals (issue 265), for the skip sheet and the picker, and the meal just skipped with those it offers.
    var standard by remember(weekStart) { mutableStateOf<StandardMeals?>(null) }
    var skipped by remember { mutableStateOf<Pair<PlannedMeal, List<StandardMeal>>?>(null) }
    // The week being planned on the planning screen (issue 255), the dinner "Another recipe" suggests others for, and the dinner
    // being moved to another day. They stay open across a re-created activity, as the screen keeps its place.
    var planningWeek by rememberSaveable { mutableStateOf(startWeek.takeIf { startPlanning }) }
    var anotherFor by rememberSaveable(stateSaver = jsonSaver(PlannedMeal.serializer().nullable)) { mutableStateOf(null) }
    var moving by rememberSaveable(stateSaver = jsonSaver(PlannedMeal.serializer().nullable)) { mutableStateOf(null) }

    /** Asks for the shares in the box today again; a failure leaves the card as it was, as the plan says what went wrong. */
    suspend fun refreshSpares() {
        (load { api.spareShares(today) } as? Load.Ready)?.let { spares = it.value }
    }

    /** Asks for the week's standard meals again, as what the plan holds says which slots can take one; a failure keeps those shown. */
    suspend fun refreshStandard(): StandardMeals? {
        (load { api.standardMeals(weekStart) } as? Load.Ready)?.let { standard = it.value }
        return standard
    }

    /** Tells which ingredients choosing a standard meal made staples again, so the shopping list names them to restock. */
    fun sayRestocked(chosen: StandardChosen) {
        if (chosen.addedStaples.isNotEmpty()) scope.launch { snackbar.showSnackbar(t(Msg.STANDARD_RESTOCK, chosen.addedStaples.joinToString(", "))) }
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
    LaunchedEffect(weekStart, reload, frontReturns) { refreshStandard() }
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

    /** Runs a change on the server and shows the plan it returns; [then] follows a change that went through. */
    fun change(then: suspend () -> Unit = {}, action: suspend () -> WeekPlan) {
        if (busy) return
        busy = true
        scope.launch {
            when (val result = load(action)) {
                is Load.Ready -> {
                    state = result
                    refreshSpares()
                    refreshStandard()
                    then()
                }
                is Load.Failed -> snackbar.showSnackbar(failureSentence(result))
                Load.Loading -> Unit
            }
            busy = false
        }
    }

    /** Shows [plan], which a screen opened over this one made, and what follows from it. */
    fun showPlanned(plan: WeekPlan) {
        weekStart = plan.weekStart
        state = Load.Ready(plan)
        scope.launch {
            refreshSpares()
            refreshStandard()
        }
    }

    planningWeek?.let { week ->
        BackHandler(enabled = true) { planningWeek = null; reload++ }
        PlanningScreen(
            api, week,
            onBack = { planningWeek = null; reload++ },
            onPlanned = { plan ->
                planningWeek = null
                showPlanned(plan)
            },
            onOpenRecipes = { planningWeek = null; onOpenRecipes() },
        )
        return
    }
    anotherFor?.let { meal ->
        BackHandler(enabled = true) { anotherFor = null }
        AnotherDinnerScreen(api, weekStart, meal, onBack = { anotherFor = null }) { plan ->
            anotherFor = null
            showPlanned(plan)
        }
        return
    }

    moving?.let { meal ->
        MoveDinnerDialog(meal, (state as? Load.Ready)?.value, today, onDismiss = { moving = null }) { toDay ->
            moving = null
            change { api.moveDinner(weekStart, meal.day, toDay) }
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
        val inSlot = (state as? Load.Ready)?.value?.days?.getOrNull(day)?.meals?.firstOrNull { it.slot == slot }
        // A standard meal goes in by its own route, which keeps a skipped prepped meal's share in the box (issue 265).
        val standardIds = standardChoices(standard, day, slot).mapTo(HashSet()) { it.recipeId }
        RecipePicker(
            api, slot,
            title = t(Msg.PLAN_CHOOSE_FOR, t.slot(slot), dayLabel(plusDays(weekStart, day), t.locale)),
            standard = standardIds,
            note = t(Msg.PICKER_SHRINKS_PREP).takeIf { shrinksPrep(inSlot) },
            onDismiss = { choosingFor = null },
            onBrowseBank = { choosingFor = null; onOpenBank() },
        ) { recipe ->
            // A refusal (such as "this meal was eaten already") or a server from before the route: the picker shows why and stays open.
            val result = if (recipe.id in standardIds) {
                load { api.chooseStandard(weekStart, day, slot, recipe.id, eaten = false).also(::sayRestocked).plan }
            } else {
                load { api.chooseRecipe(weekStart, day, slot, recipe.id) }
            }
            when (result) {
                is Load.Ready -> {
                    state = Load.Ready(result.value ?: throw AppError(t(Msg.PLAN_CHOOSE_UNSUPPORTED)))
                    choosingFor = null
                    refreshSpares()
                    refreshStandard()
                }
                is Load.Failed -> throw AppError(failureSentence(result))
                Load.Loading -> Unit
            }
        }
    }

    skipped?.let { (meal, choices) ->
        SkipSheet(meal.title, choices, enabled = !busy, onDismiss = { skipped = null }) { choice ->
            skipped = null
            change { api.chooseStandard(weekStart, meal.day, meal.slot, choice.recipeId, eaten = false).also(::sayRestocked).plan }
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

    addingSide?.let { adding ->
        AddSideDialog(
            adding.slot, adding.batches,
            onDismiss = { addingSide = null },
            onNewPrep = { addingSide = null; prepping = adding.newPrep },
        ) { batch, portions ->
            when (val result = load { api.addSide(weekStart, batch.prep.id, adding.slot.copy(side = portions)) }) {
                is Load.Ready -> {
                    state = result
                    addingSide = null
                    null
                }
                is Load.Failed -> failureSentence(result)
                Load.Loading -> null
            }
        }
    }

    removingStandard?.let { meal ->
        AlertDialog(
            onDismissRequest = { removingStandard = null },
            title = { Text(t(Msg.REMOVE_STANDARD_TITLE, meal.title)) },
            text = { Text(t(Msg.REMOVE_STANDARD_TEXT)) },
            confirmButton = {
                TextButton(onClick = {
                    removingStandard = null
                    change { api.removeMeal(weekStart, meal.day, meal.slot) }
                }) { Text(t(Msg.REMOVE)) }
            },
            dismissButton = { TextButton(onClick = { removingStandard = null }) { Text(t(Msg.CANCEL)) } },
        )
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
                // Meals of prepped batches alone, made this week or last, do not plan the week: its slots are not missing recipes yet.
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
                            restLine(plan.rest, t)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            // The planning screen (issue 255) in place of planning in one go; with dinners chosen and not on days yet, it goes on.
                            Button(enabled = !busy, onClick = { planningWeek = weekStart }) {
                                Text(if (plan.dinnersChosen > 0) t.plural(Plural.CONTINUE_PLANNING, plan.dinnersChosen) else t(Msg.PLAN_THE_WEEK))
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
                                dayBefore = plan.days.getOrNull(index - 1)?.meals.orEmpty(),
                                dayAfter = plan.days.getOrNull(index + 1)?.meals.orEmpty(),
                                today = today,
                                targets = plan.targets,
                                // A slot is offered on every day that has no meal in it.
                                missingSlots = MealSlot.entries.filter { slot -> day.meals.none { it.slot == slot } },
                                enabled = !busy,
                                pictures = pictures,
                                preppable = preppable.mapTo(HashSet()) { it.id },
                                growable = { meal -> growable(plan, meal, today) },
                                onOpenRecipe = { meal -> onOpenRecipe(meal.recipeId, meal.toCook(weekStart)) },
                                onOpenSide = { meal, side -> onOpenRecipe(side.recipeId, side.toCook(meal.status == MealStatus.SKIPPED)) },
                                // A dinner gets the suggestions of the planning screen for it (issue 255); another meal, the planner's next choice.
                                onReroll = { meal ->
                                    if (meal.slot == MealSlot.DINNER) anotherFor = meal else change { api.rerollMeal(weekStart, meal.day, meal.slot) }
                                },
                                onMove = { meal -> moving = meal },
                                onGenerate = { meal -> generatingFor = meal },
                                onChoose = { day, slot -> choosingFor = day to slot },
                                onToggleSkipped = { meal ->
                                    val status = if (meal.status == MealStatus.SKIPPED) MealStatus.PLANNED else MealStatus.SKIPPED
                                    // Skipped, the standard meals for its slot are offered at once, unless none can take its place.
                                    val offer: suspend () -> Unit = {
                                        val choices = standardChoices(standard, meal.day, meal.slot, skipped = meal.recipeId)
                                        if (status == MealStatus.SKIPPED && choices.isNotEmpty()) skipped = meal to choices
                                    }
                                    change(then = offer) {
                                        api.setMealStatus(weekStart, meal.day, meal.slot, status)
                                        api.plan(weekStart)
                                    }
                                },
                                onToggleLock = { meal -> change { api.setMealLocked(weekStart, meal.day, meal.slot, !meal.locked) } },
                                // A standard meal placed by hand goes again; one placed over a skipped prepped meal brings it back.
                                standardRecipes = standard?.meals?.mapTo(HashSet()) { it.recipeId }.orEmpty(),
                                onRemove = { meal -> removingStandard = meal },
                                onAdjust = { meal -> onAdjust(MealPlace(weekStart, meal.day, meal.slot)) },
                                onPrep = { meal ->
                                    val date = plusDays(weekStart, meal.day)
                                    prepping = PrepChoice(preppable.first { it.id == meal.recipeId }, madeOn = date, firstDay = today).with().toggled(date, meal.slot)
                                },
                                onRemovePrep = { title, prep -> removingPrep = title to prep },
                                onAddSide = { meal ->
                                    val date = plusDays(weekStart, meal.day)
                                    val newPrep = PrepChoice(preppable.singleOrNull(), madeOn = date, firstDay = today, side = SIDE_PORTIONS.last()).with().toggled(date, meal.slot)
                                    // A batch still to make can take the side; without one, the Prep dialog makes a new batch, as before.
                                    val batches = growable(plan, meal, today)
                                    if (batches.isEmpty()) prepping = newPrep else addingSide = AddingSide(PrepSlot(date, meal.slot), batches, newPrep)
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

/** A side being added next to the meal at [slot] from one of [batches] (issue 269), or else from [newPrep], a new batch. */
@Serializable
private data class AddingSide(val slot: PrepSlot, val batches: List<BatchToMake>, val newPrep: PrepChoice)

@Composable
private fun DayCard(
    day: PlanDay,
    dayIndex: Int,
    /** The meals of the day before in this plan, for what its dinner says of a leftover lunch ([whyKept]). */
    dayBefore: List<PlannedMeal>,
    /** The meals of the day after, for whether a dinner's leftover lunch can follow it to another day ([canMove]). */
    dayAfter: List<PlannedMeal>,
    /** Today's ISO date: a prep is not made on a day that has passed. */
    today: String,
    targets: DailyTargets,
    missingSlots: List<MealSlot>,
    enabled: Boolean,
    pictures: Map<Long, String>,
    /** The recipes a prep can be made of, by id. */
    preppable: Set<Long>,
    /** The batches still to make a side next to a meal can be taken from ([growable]). */
    growable: (PlannedMeal) -> List<BatchToMake>,
    onOpenRecipe: (PlannedMeal) -> Unit,
    onReroll: (PlannedMeal) -> Unit,
    /** Moves a dinner to another day (issue 255). */
    onMove: (PlannedMeal) -> Unit,
    onGenerate: (PlannedMeal) -> Unit,
    onChoose: (Int, MealSlot) -> Unit,
    onToggleSkipped: (PlannedMeal) -> Unit,
    onToggleLock: (PlannedMeal) -> Unit,
    /** The recipes that are my standard meals (issue 265): a meal of one can be taken out of the plan ([onRemove]). */
    standardRecipes: Set<Long>,
    onRemove: (PlannedMeal) -> Unit,
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
                val kept = meal.whyKept(day.date, dayBefore)
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
                            // As Today marks it: the Plan is where one sees which meals of the week are done.
                            if (meal.status == MealStatus.EATEN) {
                                Text(t(Msg.STATUS_EATEN_MARK), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
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
                            // Only what the server does for this meal: one that keeps its recipe ([kept]) says why it has no other, rather than offer what is refused.
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                kept?.let { DropdownMenuItem(text = { Text(t(keptMessage(it))) }, enabled = false, onClick = {}) }
                                if (kept == null) {
                                    // A locked meal (one the user chose, or locked) keeps its recipe: only choosing or leaving it out is left. A
                                    // dinner's "Another recipe" opens suggestions to choose from, which a locked one may take too (issue 255).
                                    DropdownMenuItem(
                                        text = { Text(t(Msg.ANOTHER_RECIPE)) },
                                        enabled = !meal.locked || meal.slot == MealSlot.DINNER,
                                        onClick = { menu = false; onReroll(meal) },
                                    )
                                    DropdownMenuItem(text = { Text(t(Msg.CHOOSE_RECIPE)) }, onClick = { menu = false; onChoose(meal.day, meal.slot) })
                                }
                                // Only a dinner the server lets move: not one that keeps its recipe, of a prep, with a side, or whose leftovers can't follow.
                                if (meal.canMove(day.date, dayBefore, dayAfter, today)) {
                                    DropdownMenuItem(text = { Text(t(Msg.MOVE_TO_DAY)) }, onClick = { menu = false; onMove(meal) })
                                }
                                // A skipped meal counts for nothing, so there is nothing in it to adjust.
                                DropdownMenuItem(
                                    text = { Text(t(Msg.ADJUST_WHAT_WAS_IN_IT)) },
                                    enabled = meal.status != MealStatus.SKIPPED,
                                    onClick = { menu = false; onAdjust(meal) },
                                )
                                // A prepped meal whose share is eaten in another meal has nothing left to eat: it stays skipped.
                                if (!meal.shareGone(day.date)) {
                                    DropdownMenuItem(
                                        text = { Text(if (meal.status == MealStatus.SKIPPED) t(Msg.EAT_AFTER_ALL) else t(Msg.NOT_EATING_THIS)) },
                                        onClick = { menu = false; onToggleSkipped(meal) },
                                    )
                                }
                                // Left out for a skipped meal, where it would be greyed with no reason: eat it after all first.
                                if (kept == null && meal.status != MealStatus.SKIPPED) {
                                    DropdownMenuItem(text = { Text(t(Msg.WRITE_NEW_RECIPE)) }, enabled = !meal.locked, onClick = { menu = false; onGenerate(meal) })
                                }
                                // Only where the server lets it go: not a meal that keeps its recipe, of a prep, or with a side.
                                if (kept == null && meal.prep == null && meal.side == null && meal.recipeId in standardRecipes) {
                                    DropdownMenuItem(text = { Text(t(Msg.REMOVE_STANDARD_MEAL)) }, onClick = { menu = false; onRemove(meal) })
                                }
                                val lunchOrDinner = meal.slot == MealSlot.LUNCH || meal.slot == MealSlot.DINNER
                                val prep = meal.prep
                                // Removing a prep is always answered: the dialog says what goes, or that a made batch stays.
                                if (prep != null) {
                                    DropdownMenuItem(text = { Text(t(Msg.REMOVE_PREP)) }, onClick = { menu = false; onRemovePrep(meal.title, prep) })
                                } else if (kept == null && meal.recipeId in preppable && lunchOrDinner && day.date >= today) {
                                    DropdownMenuItem(text = { Text(t(Msg.PREP_THIS_RECIPE)) }, onClick = { menu = false; onPrep(meal) })
                                }
                                val side = meal.side
                                if (side != null) {
                                    // A side of a made batch, or one that makes it, goes with its prep only.
                                    if (!meal.sideStays(day.date)) {
                                        DropdownMenuItem(text = { Text(t(Msg.REMOVE_SIDE)) }, onClick = { menu = false; onRemoveSide(meal) })
                                    }
                                    DropdownMenuItem(text = { Text(t(Msg.REMOVE_SIDE_PREP)) }, onClick = { menu = false; onRemovePrep(side.title, side.prep) })
                                } else if ((preppable.isNotEmpty() || growable(meal).isNotEmpty()) && lunchOrDinner && meal.status == MealStatus.PLANNED && day.date >= today) {
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

/** Why a dinner stays on its day, as the move dialog says it ([WhyStays]). */
private fun whyStaysMessage(why: WhyStays): Msg = when (why) {
    WhyStays.PASSED -> Msg.MOVE_DAY_PASSED
    WhyStays.EATEN -> Msg.MOVE_WHY_EATEN
    WhyStays.BATCH_MADE -> Msg.MOVE_WHY_BATCH_MADE
    WhyStays.MAKES_BATCH -> Msg.MOVE_WHY_MAKES_BATCH
    WhyStays.PREPPED -> Msg.MOVE_WHY_PREPPED
    WhyStays.SIDE -> Msg.MOVE_WHY_SIDE
    WhyStays.LEFTOVERS -> Msg.MOVE_WHY_LEFTOVERS
}

/** What the menu of a meal that keeps its recipe says instead of offering another ([WhyKept]). */
private fun keptMessage(why: WhyKept): Msg = when (why) {
    WhyKept.EATEN -> Msg.KEPT_EATEN
    WhyKept.DINNER_EATEN -> Msg.KEPT_DINNER_EATEN
    WhyKept.BATCH_MADE -> Msg.KEPT_BATCH_MADE
    WhyKept.MAKES_BATCH -> Msg.KEPT_MAKES_BATCH
}

/**
 * Where to move [meal], a dinner of [plan], to (issue 255): the other days of the week, each with the dinner it would swap
 * with, or none. A day before [today] has passed, and a day whose dinner stays where it is ([whyStays]) says why; neither
 * can be chosen.
 */
@Composable
private fun MoveDinnerDialog(meal: PlannedMeal, plan: WeekPlan?, today: String, onDismiss: () -> Unit, onMove: (Int) -> Unit) {
    val t = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Msg.MOVE_TITLE, meal.title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                plan?.days?.forEachIndexed { index, day ->
                    if (index == meal.day) return@forEachIndexed
                    val there = day.meals.firstOrNull { it.slot == MealSlot.DINNER }
                    val why = there?.whyStays(day.date, plan.days.getOrNull(index - 1)?.meals.orEmpty(), plan.days.getOrNull(index + 1)?.meals.orEmpty(), today)
                    val stays = day.date < today || why != null
                    val what = when {
                        day.date < today -> t(Msg.MOVE_DAY_PASSED)
                        there == null -> t(Msg.MOVE_NO_DINNER)
                        why != null -> t(Msg.MOVE_STAYS, there.title, t(whyStaysMessage(why)))
                        else -> t(Msg.MOVE_SWAPS, there.title)
                    }
                    TextButton(enabled = !stays, onClick = { onMove(index) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(dayLabel(day.date, t.locale))
                            Text(what, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}
