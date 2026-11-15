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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.WeekPlan
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The week plan: one card per day with its meals and totals against the targets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(api: ApiClient, onOpenRecipe: (Long) -> Unit, onOpenProfile: () -> Unit) {
    val t = LocalStrings.current
    var weekStart by rememberSaveable { mutableStateOf(mondayOf(todayIso())) }
    var state by remember { mutableStateOf<Load<WeekPlan>>(Load.Loading) }
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // The meal a new recipe is being written for.
    var generatingFor by remember { mutableStateOf<PlannedMeal?>(null) }

    LaunchedEffect(weekStart) {
        state = Load.Loading
        state = load { api.plan(weekStart) }
    }

    /** Runs a change on the server and shows the plan it returns. */
    fun change(action: suspend () -> WeekPlan) {
        if (busy) return
        busy = true
        scope.launch {
            when (val result = load(action)) {
                is Load.Ready -> state = result
                is Load.Failed -> snackbar.showSnackbar(result.message)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    PeriodStepper(
                        label = t(Msg.WEEK_OF, dayMonthLabel(weekStart, t.locale)),
                        previous = t(Msg.PREVIOUS_WEEK),
                        next = t(Msg.NEXT_WEEK),
                        onPrevious = { weekStart = plusDays(weekStart, -7) },
                        onNext = { weekStart = plusDays(weekStart, 7) },
                    )
                },
            )
        },
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
                    ErrorMessage(t(Msg.PLAN_LOAD_FAILED), current.message) { weekStart = weekStart.also { state = Load.Loading } }
                }
            }
            is Load.Ready -> {
                val plan = current.value
                val hasMeals = plan.days.any { it.meals.isNotEmpty() }
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.ScreenEdge),
                    verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                            Text(
                                t(Msg.DAILY_TARGET, plan.targets.kcal, plan.targets.proteinG),
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
                                Text(if (hasMeals) t(Msg.REPLAN) else t(Msg.PLAN_WEEK))
                            }
                            if (hasMeals && plan.emptySlots.isNotEmpty()) {
                                Text(
                                    t(Msg.NO_RECIPES_FOR, plan.emptySlots.joinToString { t.slot(it) }),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (!hasMeals) {
                        item { EmptyState(Spot.NothingPlanned, t(Msg.NOTHING_PLANNED_WEEK), Modifier.height(240.dp)) }
                    }
                    if (hasMeals) {
                        items(plan.days, key = { it.date }) { day ->
                            DayCard(
                                day = day,
                                targets = plan.targets,
                                enabled = !busy,
                                onOpenRecipe = onOpenRecipe,
                                onReroll = { meal -> change { api.rerollMeal(weekStart, meal.day, meal.slot) } },
                                onGenerate = { meal -> generatingFor = meal },
                                onToggleLock = { meal -> change { api.setMealLocked(weekStart, meal.day, meal.slot, !meal.locked) } },
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
    targets: DailyTargets,
    enabled: Boolean,
    onOpenRecipe: (Long) -> Unit,
    onReroll: (PlannedMeal) -> Unit,
    onGenerate: (PlannedMeal) -> Unit,
    onToggleLock: (PlannedMeal) -> Unit,
) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            Text(dayLabel(day.date, t.locale), style = MaterialTheme.typography.titleMedium)
            Text(
                t(Msg.DAY_TOTALS, day.kcal.roundToInt(), targets.kcal, day.proteinG.roundToInt(), targets.proteinG),
                style = MaterialTheme.typography.bodyMedium.numeric(),
            )
            LinearProgressIndicator(
                progress = { (day.kcal / targets.kcal).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (day.meals.isEmpty()) {
                Text(t(Msg.NOTHING_PLANNED), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (meal in day.meals) {
                // The whole meal opens its recipe; the two icon buttons take their own taps.
                Column(Modifier.fillMaxWidth().clickable { onOpenRecipe(meal.recipeId) }) {
                    // The title gets the full width left of the icon buttons; the numbers sit below.
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(
                                t.mealLabel(meal),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(meal.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        BarAction(
                            if (meal.locked) AppIcons.Lock else AppIcons.LockOpen,
                            if (meal.locked) t(Msg.UNLOCK_SLOT, t.slot(meal.slot)) else t(Msg.LOCK_SLOT, t.slot(meal.slot)),
                            onClick = { onToggleLock(meal) },
                            enabled = enabled,
                        )
                        Box {
                            var menu by remember { mutableStateOf(false) }
                            BarAction(
                                AppIcons.Swap,
                                t(Msg.SWAP_SLOT, t.slot(meal.slot)),
                                onClick = { menu = true },
                                enabled = enabled && !meal.locked,
                            )
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(t(Msg.ANOTHER_RECIPE)) }, onClick = { menu = false; onReroll(meal) })
                                DropdownMenuItem(text = { Text(t(Msg.WRITE_NEW_RECIPE)) }, onClick = { menu = false; onGenerate(meal) })
                            }
                        }
                    }
                    MealNumbers(meal.macrosText(t), meal.cost?.let { "≈ ${formatEuro(it, t.locale)}" }, Modifier.padding(bottom = 4.dp))
                }
            }
        }
    }
}
