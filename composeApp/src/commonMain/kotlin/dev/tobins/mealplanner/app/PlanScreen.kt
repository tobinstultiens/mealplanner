package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
            title = "New ${meal.slot.name.lowercase()} for ${dayLabel(plusDays(weekStart, meal.day))}",
        ) { request ->
            state = Load.Ready(api.generateIntoPlan(weekStart, meal.day, meal.slot, request))
            generatingFor = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Week of ${dayLabel(weekStart).substringAfter(' ')}") },
                actions = {
                    TextButton(onClick = { weekStart = plusDays(weekStart, -7) }) { Text("‹ Prev") }
                    TextButton(onClick = { weekStart = plusDays(weekStart, 7) }) { Text("Next ›") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            Load.Loading -> CenteredBox(Modifier.padding(padding)) { CircularProgressIndicator() }
            is Load.Failed -> Column(Modifier.padding(padding)) {
                if (current.status == 409) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Set up your profile first", style = MaterialTheme.typography.titleMedium)
                        Text("The planner needs your daily calorie and protein targets.")
                        Button(onClick = onOpenProfile) { Text("Open profile") }
                    }
                } else {
                    ErrorMessage("Could not load the plan", current.message) { weekStart = weekStart.also { state = Load.Loading } }
                }
            }
            is Load.Ready -> {
                val plan = current.value
                val hasMeals = plan.days.any { it.meals.isNotEmpty() }
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Daily target: ${plan.targets.kcal} kcal · ${plan.targets.proteinG} g protein",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            plan.cost?.let { cost ->
                                val saved = (plan.costWithoutOffers ?: cost) - cost
                                Text(
                                    "≈ ${formatEuro(cost)} for the week's meals" +
                                        if (saved >= 0.01) ", ${formatEuro(saved)} saved by offers" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Button(enabled = !busy, onClick = { change { api.generatePlan(weekStart) } }) {
                                Text(if (hasMeals) "Replan unlocked meals" else "Plan this week")
                            }
                            if (hasMeals && plan.emptySlots.isNotEmpty()) {
                                Text(
                                    "No recipes yet for: ${plan.emptySlots.joinToString { it.name.lowercase() }}. " +
                                        "Days stay below the target until those exist.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(dayLabel(day.date), style = MaterialTheme.typography.titleMedium)
            Text(
                "${day.kcal.roundToInt()} of ${targets.kcal} kcal · ${day.proteinG.roundToInt()} of ${targets.proteinG} g protein",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { (day.kcal / targets.kcal).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (day.meals.isEmpty()) {
                Text("Nothing planned", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (meal in day.meals) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onOpenRecipe(meal.recipeId) }) {
                        Text(
                            meal.slot.name.lowercase().replaceFirstChar { it.uppercase() } + if (meal.leftover) " · leftovers" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(meal.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${formatAmount(meal.portions)} ${if (meal.portions == 1.0) "serving" else "servings"} · " +
                                "${meal.kcal.roundToInt()} kcal · ${meal.proteinG.roundToInt()} g protein" +
                                (meal.cost?.let { " · ≈ ${formatEuro(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(enabled = enabled, onClick = { onToggleLock(meal) }) { Text(if (meal.locked) "Unlock" else "Lock") }
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        TextButton(enabled = enabled && !meal.locked, onClick = { menu = true }) { Text("Swap") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Another recipe") }, onClick = { menu = false; onReroll(meal) })
                            DropdownMenuItem(text = { Text("Write a new recipe…") }, onClick = { menu = false; onGenerate(meal) })
                        }
                    }
                }
            }
        }
    }
}
