package dev.tobins.mealplanner.app.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.LogTextInput
import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.StandardMeal
import dev.tobins.mealplanner.api.StandardMeals
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.AppMsg
import dev.tobins.mealplanner.app.BackHandler
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.RefreshNotice
import dev.tobins.mealplanner.app.day
import dev.tobins.mealplanner.app.deleteLog
import dev.tobins.mealplanner.app.followedDate
import dev.tobins.mealplanner.app.recipes.MealPlace
import dev.tobins.mealplanner.app.recipes.MealToCook
import dev.tobins.mealplanner.app.recipes.RecipePhoto
import dev.tobins.mealplanner.app.recipes.recipePicture
import dev.tobins.mealplanner.app.recipes.rememberRecipePictureUrls
import dev.tobins.mealplanner.app.recipes.toCook
import dev.tobins.mealplanner.app.rememberFrontReturns
import dev.tobins.mealplanner.app.rememberLoad
import dev.tobins.mealplanner.app.rememberToday
import dev.tobins.mealplanner.app.setMealStatus
import dev.tobins.mealplanner.app.tracking.LogFoodPlace
import dev.tobins.mealplanner.app.tracking.LogFoodScreen
import dev.tobins.mealplanner.app.tracking.numbersText
import dev.tobins.mealplanner.app.tracking.recountable
import dev.tobins.mealplanner.app.tracking.shownName
import dev.tobins.mealplanner.app.tracking.typedText
import dev.tobins.mealplanner.app.ui.AppIcons
import dev.tobins.mealplanner.app.ui.BarAction
import dev.tobins.mealplanner.app.ui.CommonMsg
import dev.tobins.mealplanner.app.ui.ErrorMessage
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.LoadingState
import dev.tobins.mealplanner.app.ui.MealNumbers
import dev.tobins.mealplanner.app.ui.PeriodStepper
import dev.tobins.mealplanner.app.ui.SectionHeader
import dev.tobins.mealplanner.app.ui.Spacing
import dev.tobins.mealplanner.app.ui.dayLabel
import dev.tobins.mealplanner.app.ui.failureOf
import dev.tobins.mealplanner.app.ui.formatAmount
import dev.tobins.mealplanner.app.ui.formatEuro
import dev.tobins.mealplanner.app.ui.load
import dev.tobins.mealplanner.app.ui.macrosText
import dev.tobins.mealplanner.app.ui.mondayOf
import dev.tobins.mealplanner.app.ui.numeric
import dev.tobins.mealplanner.app.ui.plusDays
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable
import kotlin.math.roundToInt

/** One day: what is planned, what has been eaten, and how that compares with the targets. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(
    api: ApiClient,
    /** Opens a recipe from a meal: its id and what the meal says about cooking it (a side's, for a side). */
    onOpenRecipe: (Long, MealToCook?) -> Unit,
    onOpenPlan: () -> Unit,
    /** Opens what was in a meal, to adjust it (issue 226). */
    onAdjust: (MealPlace) -> Unit = {},
) {
    val t = LocalStrings.current
    val now = rememberToday()
    val frontReturns = rememberFrontReturns()
    // The day that was today when this screen last looked; the date follows it when it moves on (see followedDate).
    var today by rememberSaveable { mutableStateOf(now) }
    var date by rememberSaveable { mutableStateOf(today) }
    val loaded = rememberLoad<DayView>("day:$date")
    var state by loaded
    val scroll = rememberScrollState()
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    // An entry being typed over: a typed one, or a food that is no longer in the catalogue (issue 362).
    var retyping by remember { mutableStateOf<LogEntry?>(null) }
    // The food screen of the log, open over the day: kept over a re-created activity, as the Plan keeps its food screen.
    var foodAt by rememberSaveable(stateSaver = jsonSaver(LogFoodPlace.serializer().nullable)) { mutableStateOf(null) }
    val snackbar = remember { SnackbarHostState() }
    val pictures by rememberRecipePictureUrls(api)
    val scope = rememberCoroutineScope()
    // The week's standard meals (issue 265), offered under a skipped meal; none from a server before them.
    val week = mondayOf(date)
    var standard by remember(week) { mutableStateOf<StandardMeals?>(null) }
    // Set while a chosen standard meal is being sent: a second tap would be a second request, which the server refuses (409).
    var placing by remember { mutableStateOf(false) }

    LaunchedEffect(now) {
        if (now != today) {
            date = followedDate(today, date, now)
            today = now
            scroll.scrollTo(0)
        }
    }
    LaunchedEffect(loaded, reload, frontReturns) { loaded.refresh { api.day(date) } }
    LaunchedEffect(week, reload, frontReturns) { (load { api.standardMeals(week) } as? Load.Ready)?.let { standard = it.value } }

    /** Shows another day the user stepped to, from its top. */
    fun show(other: String) {
        date = other
        scope.launch { scroll.scrollTo(0) }
    }

    /** Runs a change and then reloads the day, reporting a failure. */
    fun change(action: suspend () -> Unit) {
        scope.launch {
            failureOf(action)?.let { snackbar.showSnackbar(it) }
            reload++
        }
    }

    foodAt?.let { place ->
        BackHandler(enabled = true) { foodAt = null }
        LogFoodScreen(api, place, onBack = { foodAt = null }) { answered ->
            foodAt = null
            state = Load.Ready(answered)
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    PeriodStepper(
                        label = if (date == today) t(AppMsg.TAB_TODAY) else dayLabel(date, t.locale),
                        previous = t(PlanMsg.PREVIOUS_DAY),
                        next = t(PlanMsg.NEXT_DAY),
                        onPrevious = { show(plusDays(date, -1)) },
                        onNext = { show(plusDays(date, 1)) },
                    )
                },
                actions = {
                    if (date != today) BarAction(AppIcons.Today, t(PlanMsg.BACK_TO_TODAY), onClick = { show(today) })
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
                ErrorMessage(t(PlanMsg.DAY_LOAD_FAILED), current) { reload++ }
            }
            is Load.Ready -> {
                val day = current.value
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(Spacing.ScreenEdge),
                    verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
                ) {
                    TotalsCard(day)
                    if (day.meals.isEmpty()) {
                        Text(t(PlanMsg.NOTHING_PLANNED_DAY), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onOpenPlan) { Text(t(PlanMsg.OPEN_PLAN)) }
                    }
                    for (meal in day.meals) {
                        MealCard(
                            meal = meal,
                            pictureUrl = pictures[meal.recipeId],
                            date = day.date,
                            onOpen = { onOpenRecipe(meal.recipeId, meal.toCook(day.weekStart)) },
                            onOpenSide = { side -> onOpenRecipe(side.recipeId, side.toCook(meal.status == MealStatus.SKIPPED)) },
                            onStatus = { status -> change { api.setMealStatus(day.weekStart, day.dayIndex, meal.slot, status) } },
                            onAdjust = { onAdjust(MealPlace(day.weekStart, day.dayIndex, meal.slot)) },
                            // Only a skipped meal's place is offered: a chip there takes it at once.
                            standardChoices = if (meal.status == MealStatus.SKIPPED) standardChoices(standard, day.dayIndex, meal.slot, skipped = meal.recipeId) else emptyList(),
                            placing = placing,
                            onPickStandard = { choice ->
                                if (!placing) change {
                                    placing = true
                                    try {
                                        // Today's and a past day's meal was eaten: that is why it is picked here.
                                        val chosen = api.chooseStandard(day.weekStart, day.dayIndex, meal.slot, choice.recipeId, eaten = day.date <= today)
                                        if (chosen.addedStaples.isNotEmpty()) scope.launch { snackbar.showSnackbar(t(PlanMsg.STANDARD_RESTOCK, chosen.addedStaples.joinToString(", "))) }
                                    } finally {
                                        placing = false
                                    }
                                }
                            },
                        )
                    }
                    // Always there, so that the buttons under it read as what they add: something eaten besides the plan.
                    SectionHeader(t(CommonMsg.ALSO_EATEN))
                    for (extra in day.extras) {
                        // A food still in the catalogue opens to change its amount; any other entry is typed over.
                        LogRow(
                            extra,
                            onOpen = { if (extra.recountable) foodAt = LogFoodPlace(day.date, extra) else retyping = extra },
                            onRemove = { change { api.deleteLog(extra.id) } },
                        )
                    }
                    // A food of the catalogue is the main way, typing the numbers the lesser one, as Eaten and Skipped are.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related), verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                        Button(onClick = { foodAt = LogFoodPlace(day.date) }) { Text(t(PlanMsg.LOG_ADD_FOOD)) }
                        OutlinedButton(onClick = { adding = true }) { Text(t(PlanMsg.ADD_EXTRA)) }
                    }
                }
            }
        }
    }

    if (adding) {
        ExtraDialog(
            onDismiss = { adding = false },
            onConfirm = { description, kcal, protein ->
                adding = false
                change { api.logFood(LogEntry(date = date, description = description, kcal = kcal, proteinG = protein)) }
            },
        )
    }
    retyping?.let { entry ->
        ExtraDialog(
            onDismiss = { retyping = null },
            onConfirm = { description, kcal, protein ->
                retyping = null
                change { api.retypeLog(entry.id, LogTextInput(description, kcal, protein)) }
            },
            entry = entry,
        )
    }
}

/** An entry of the log: its name and [numbersText], a tap on them opening it ([onOpen]), and Remove. */
@Composable
private fun LogRow(entry: LogEntry, onOpen: () -> Unit, onRemove: () -> Unit) {
    val t = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 4.dp)) {
            Text(entry.shownName, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                entry.numbersText(t),
                style = MaterialTheme.typography.bodySmall.numeric(),
                color = if (entry.food?.counted == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove) { Text(t(CommonMsg.REMOVE)) }
    }
}

@Composable
private fun TotalsCard(day: DayView) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val targets = day.targets
            if (targets == null) {
                Text(t(PlanMsg.EATEN_TOTAL, day.eatenKcal.roundToInt(), day.eatenProteinG.roundToInt()), style = MaterialTheme.typography.titleMedium.numeric())
                Text(t(PlanMsg.SET_UP_PROFILE_HINT), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(t(PlanMsg.EATEN_SO_FAR), style = MaterialTheme.typography.labelLarge)
                Text(t(PlanMsg.KCAL_OF, day.eatenKcal.roundToInt(), targets.kcal), style = MaterialTheme.typography.titleLarge.numeric())
                LinearProgressIndicator(
                    progress = { (day.eatenKcal / targets.kcal).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(t(PlanMsg.PROTEIN_OF, day.eatenProteinG.roundToInt(), targets.proteinG), style = MaterialTheme.typography.bodyLarge.numeric())
                LinearProgressIndicator(
                    progress = { (day.eatenProteinG / targets.proteinG).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                // A server before fibre counted sends neither the target nor the amount: no fibre then.
                val fibreTarget = targets.fibreG
                val eatenFibre = day.eatenFibreG
                if (fibreTarget != null && eatenFibre != null) {
                    Text(t(PlanMsg.FIBRE_OF, eatenFibre.roundToInt(), fibreTarget), style = MaterialTheme.typography.bodyLarge.numeric())
                    LinearProgressIndicator(
                        progress = { (eatenFibre / fibreTarget).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val difference = (day.expectedKcal - targets.kcal).roundToInt()
                Text(
                    when {
                        difference > 0 -> t(PlanMsg.FORECAST_OVER, day.expectedKcal.roundToInt(), difference)
                        difference < 0 -> t(PlanMsg.FORECAST_UNDER, day.expectedKcal.roundToInt(), -difference)
                        else -> t(PlanMsg.FORECAST_ON, day.expectedKcal.roundToInt())
                    },
                    style = MaterialTheme.typography.bodySmall.numeric(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val expectedFibre = day.expectedFibreG
                if (fibreTarget != null && expectedFibre != null) {
                    Text(
                        t(PlanMsg.FORECAST_FIBRE, expectedFibre.roundToInt(), fibreTarget),
                        style = MaterialTheme.typography.bodySmall.numeric(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealCard(
    meal: PlannedMeal,
    pictureUrl: String?,
    /** The meal's ISO date, which says whether its side's batch is made today. */
    date: String,
    onOpen: () -> Unit,
    onOpenSide: (MealSide) -> Unit,
    onStatus: (MealStatus) -> Unit,
    onAdjust: () -> Unit,
    /** The standard meals that can take the skipped meal's place (issue 265); empty for any other meal. */
    standardChoices: List<StandardMeal> = emptyList(),
    /** True while a standard meal is being put in a slot: the chips wait, as a second tap would be refused. */
    placing: Boolean = false,
    onPickStandard: (StandardMeal) -> Unit = {},
) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // A meal with a photo shows it at the end of the meal's lines; one without keeps them full width.
            // Foods alone (issue 358) have no recipe to open: each is listed by its name instead of their one-line title.
            Row(Modifier.fillMaxWidth().clickable(enabled = !meal.foodsOnly, onClick = onOpen), horizontalArrangement = Arrangement.spacedBy(Spacing.BetweenCards)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t.mealLabel(meal),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (meal.foodsOnly) {
                        FoodRows(meal.foods)
                    } else {
                        Text(
                            meal.title,
                            style = MaterialTheme.typography.titleMedium,
                            textDecoration = if (meal.status == MealStatus.SKIPPED) TextDecoration.LineThrough else null,
                        )
                    }
                    MealNumbers(meal.macrosText(t), meal.cost?.let { "≈ ${formatEuro(it, t.locale)}" })
                    if (!meal.foodsOnly && meal.foods.isNotEmpty()) FoodRows(meal.foods)
                }
                recipePicture(pictureUrl)?.let { RecipePhoto(it, Modifier.size(64.dp)) }
            }
            // A side is eaten or skipped with its meal: the buttons below mark both.
            meal.side?.let { side -> SideRow(meal, side, date, onOpen = { onOpenSide(side) }) }
            // Wraps on a narrow screen with a large font, where the three buttons of a planned meal do not fit.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related), itemVerticalAlignment = Alignment.CenterVertically) {
                when (meal.status) {
                    MealStatus.PLANNED -> {
                        Button(onClick = { onStatus(MealStatus.EATEN) }) { Text(t(PlanMsg.STATUS_EATEN)) }
                        OutlinedButton(onClick = { onStatus(MealStatus.SKIPPED) }) { Text(t(PlanMsg.STATUS_SKIPPED)) }
                        // Toppings are often decided while cooking: a meal still to eat can be adjusted too; foods alone are changed on the Plan.
                        if (!meal.foodsOnly) TextButton(onClick = onAdjust) { Text(t(PlanMsg.ADJUST)) }
                    }
                    MealStatus.EATEN -> {
                        Text(t(PlanMsg.STATUS_EATEN_MARK), color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { onStatus(MealStatus.PLANNED) }) { Text(t(CommonMsg.UNDO)) }
                        if (!meal.foodsOnly) TextButton(onClick = onAdjust) { Text(t(PlanMsg.ADJUST)) }
                    }
                    MealStatus.SKIPPED -> {
                        Text(t(PlanMsg.STATUS_SKIPPED), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onStatus(MealStatus.PLANNED) }) { Text(t(CommonMsg.UNDO)) }
                    }
                }
            }
            if (standardChoices.isNotEmpty()) {
                Text(t(PlanMsg.STANDARD_INSTEAD), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StandardChips(standardChoices, enabled = !placing, onPick = onPickStandard)
            }
        }
    }
}

/**
 * Something eaten typed with its numbers: a new entry, or [entry] typed over, which starts as it reads. A food of [entry]
 * that is gone from the catalogue cannot be recounted, and the dialog says so (issue 362).
 */
@Composable
private fun ExtraDialog(onDismiss: () -> Unit, onConfirm: (String, Double, Double) -> Unit, entry: LogEntry? = null) {
    val t = LocalStrings.current
    var description by remember { mutableStateOf(entry?.typedText(t).orEmpty()) }
    var kcal by remember { mutableStateOf(entry?.let { formatAmount(it.kcal, t.locale) }.orEmpty()) }
    var protein by remember { mutableStateOf(entry?.let { formatAmount(it.proteinG, t.locale) }.orEmpty()) }
    val kcalValue = kcal.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    val proteinValue = if (protein.isBlank()) 0.0 else protein.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(if (entry == null) PlanMsg.EXTRA_TITLE else PlanMsg.LOG_CHANGE_TITLE)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                entry?.food?.let { gone -> Text(t(PlanMsg.LOG_FOOD_GONE, gone.name), style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(description, { description = it }, label = { Text(t(PlanMsg.EXTRA_WHAT)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    kcal, { kcal = it },
                    label = { Text(t(PlanMsg.EXTRA_CALORIES)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    protein, { protein = it },
                    label = { Text(t(PlanMsg.EXTRA_PROTEIN)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = description.isNotBlank() && kcalValue != null && proteinValue != null,
                onClick = { onConfirm(description.trim(), kcalValue!!, proteinValue!!) },
            ) { Text(t(if (entry == null) CommonMsg.ADD else CommonMsg.SAVE)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(CommonMsg.CANCEL)) } },
    )
}
