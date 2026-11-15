package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One day: what is planned, what has been eaten, and how that compares with the targets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(api: ApiClient, onOpenRecipe: (Long) -> Unit, onOpenPlan: () -> Unit) {
    val t = LocalStrings.current
    val today = remember { todayIso() }
    var date by rememberSaveable { mutableStateOf(today) }
    var state by remember { mutableStateOf<Load<DayView>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(date, reload) {
        if (state !is Load.Ready) state = Load.Loading
        state = load { api.day(date) }
    }

    /** Runs a change and then reloads the day, reporting a failure. */
    fun change(action: suspend () -> Unit) {
        scope.launch {
            (load(action) as? Load.Failed)?.let { snackbar.showSnackbar(it.message) }
            reload++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    PeriodStepper(
                        label = if (date == today) t(Msg.TAB_TODAY) else dayLabel(date, t.locale),
                        previous = t(Msg.PREVIOUS_DAY),
                        next = t(Msg.NEXT_DAY),
                        onPrevious = { date = plusDays(date, -1) },
                        onNext = { date = plusDays(date, 1) },
                    )
                },
                actions = {
                    if (date != today) BarAction(AppIcons.Today, t(Msg.BACK_TO_TODAY), onClick = { date = today })
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage(t(Msg.DAY_LOAD_FAILED), current.message) { reload++ }
            }
            is Load.Ready -> {
                val day = current.value
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
                    verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
                ) {
                    TotalsCard(day)
                    if (day.meals.isEmpty()) {
                        Text(t(Msg.NOTHING_PLANNED_DAY), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onOpenPlan) { Text(t(Msg.OPEN_PLAN)) }
                    }
                    for (meal in day.meals) {
                        MealCard(
                            meal = meal,
                            onOpen = { onOpenRecipe(meal.recipeId) },
                            onStatus = { status -> change { api.setMealStatus(day.weekStart, day.dayIndex, meal.slot, status) } },
                        )
                    }
                    if (day.extras.isNotEmpty()) SectionHeader(t(Msg.ALSO_EATEN))
                    for (extra in day.extras) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(extra.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    t(Msg.KCAL_PROTEIN, extra.kcal.roundToInt(), extra.proteinG.roundToInt()),
                                    style = MaterialTheme.typography.bodySmall.numeric(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { change { api.deleteLog(extra.id) } }) { Text(t(Msg.REMOVE)) }
                        }
                    }
                    OutlinedButton(onClick = { adding = true }) { Text(t(Msg.ADD_EXTRA)) }
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
}

@Composable
private fun TotalsCard(day: DayView) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val targets = day.targets
            if (targets == null) {
                Text(t(Msg.EATEN_TOTAL, day.eatenKcal.roundToInt(), day.eatenProteinG.roundToInt()), style = MaterialTheme.typography.titleMedium.numeric())
                Text(t(Msg.SET_UP_PROFILE_HINT), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(t(Msg.EATEN_SO_FAR), style = MaterialTheme.typography.labelLarge)
                Text(t(Msg.KCAL_OF, day.eatenKcal.roundToInt(), targets.kcal), style = MaterialTheme.typography.titleLarge.numeric())
                LinearProgressIndicator(
                    progress = { (day.eatenKcal / targets.kcal).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(t(Msg.PROTEIN_OF, day.eatenProteinG.roundToInt(), targets.proteinG), style = MaterialTheme.typography.bodyLarge.numeric())
                LinearProgressIndicator(
                    progress = { (day.eatenProteinG / targets.proteinG).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val difference = (day.expectedKcal - targets.kcal).roundToInt()
                Text(
                    when {
                        difference > 0 -> t(Msg.FORECAST_OVER, day.expectedKcal.roundToInt(), difference)
                        difference < 0 -> t(Msg.FORECAST_UNDER, day.expectedKcal.roundToInt(), -difference)
                        else -> t(Msg.FORECAST_ON, day.expectedKcal.roundToInt())
                    },
                    style = MaterialTheme.typography.bodySmall.numeric(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MealCard(meal: PlannedMeal, onOpen: () -> Unit, onStatus: (MealStatus) -> Unit) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
                Text(
                    t.mealLabel(meal),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    meal.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (meal.status == MealStatus.SKIPPED) TextDecoration.LineThrough else null,
                )
                MealNumbers(meal.macrosText(t), meal.cost?.let { "≈ ${formatEuro(it, t.locale)}" })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Related), verticalAlignment = Alignment.CenterVertically) {
                when (meal.status) {
                    MealStatus.PLANNED -> {
                        Button(onClick = { onStatus(MealStatus.EATEN) }) { Text(t(Msg.STATUS_EATEN)) }
                        OutlinedButton(onClick = { onStatus(MealStatus.SKIPPED) }) { Text(t(Msg.STATUS_SKIPPED)) }
                    }
                    MealStatus.EATEN -> {
                        Text(t(Msg.STATUS_EATEN_MARK), color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { onStatus(MealStatus.PLANNED) }) { Text(t(Msg.UNDO)) }
                    }
                    MealStatus.SKIPPED -> {
                        Text(t(Msg.STATUS_SKIPPED), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onStatus(MealStatus.PLANNED) }) { Text(t(Msg.UNDO)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtraDialog(onDismiss: () -> Unit, onConfirm: (String, Double, Double) -> Unit) {
    val t = LocalStrings.current
    var description by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    val kcalValue = kcal.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    val proteinValue = if (protein.isBlank()) 0.0 else protein.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Msg.EXTRA_TITLE)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                OutlinedTextField(description, { description = it }, label = { Text(t(Msg.EXTRA_WHAT)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    kcal, { kcal = it },
                    label = { Text(t(Msg.EXTRA_CALORIES)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    protein, { protein = it },
                    label = { Text(t(Msg.EXTRA_PROTEIN)) },
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
            ) { Text(t(Msg.ADD)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}
