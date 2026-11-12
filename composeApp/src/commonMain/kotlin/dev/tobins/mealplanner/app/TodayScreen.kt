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
import androidx.compose.material3.CircularProgressIndicator
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
                title = { Text(if (date == today) "Today" else dayLabel(date)) },
                actions = {
                    TextButton(onClick = { date = plusDays(date, -1) }) { Text("‹") }
                    if (date != today) TextButton(onClick = { date = today }) { Text("Today") }
                    TextButton(onClick = { date = plusDays(date, 1) }) { Text("›") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            Load.Loading -> CenteredBox(Modifier.padding(padding)) { CircularProgressIndicator() }
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage("Could not load the day", current.message) { reload++ }
            }
            is Load.Ready -> {
                val day = current.value
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TotalsCard(day)
                    if (day.meals.isEmpty()) {
                        Text("Nothing is planned for this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onOpenPlan) { Text("Open the plan") }
                    }
                    for (meal in day.meals) {
                        MealCard(
                            meal = meal,
                            onOpen = { onOpenRecipe(meal.recipeId) },
                            onStatus = { status -> change { api.setMealStatus(day.weekStart, day.dayIndex, meal.slot, status) } },
                        )
                    }
                    if (day.extras.isNotEmpty()) Text("Also eaten", style = MaterialTheme.typography.titleSmall)
                    for (extra in day.extras) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(extra.description, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${extra.kcal.roundToInt()} kcal · ${extra.proteinG.roundToInt()} g protein",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { change { api.deleteLog(extra.id) } }) { Text("Remove") }
                        }
                    }
                    OutlinedButton(onClick = { adding = true }) { Text("Add something else you ate") }
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val targets = day.targets
            if (targets == null) {
                Text("Eaten: ${day.eatenKcal.roundToInt()} kcal · ${day.eatenProteinG.roundToInt()} g protein", style = MaterialTheme.typography.titleMedium)
                Text("Set up your profile under Progress to see targets.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Eaten so far", style = MaterialTheme.typography.labelLarge)
                Text("${day.eatenKcal.roundToInt()} of ${targets.kcal} kcal", style = MaterialTheme.typography.titleLarge)
                LinearProgressIndicator(
                    progress = { (day.eatenKcal / targets.kcal).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${day.eatenProteinG.roundToInt()} of ${targets.proteinG} g protein", style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(
                    progress = { (day.eatenProteinG / targets.proteinG).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val difference = (day.expectedKcal - targets.kcal).roundToInt()
                Text(
                    "Following the rest of the plan ends the day at ${day.expectedKcal.roundToInt()} kcal" +
                        when {
                            difference > 0 -> ", $difference over the target."
                            difference < 0 -> ", ${-difference} under the target."
                            else -> ", exactly on target."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MealCard(meal: PlannedMeal, onOpen: () -> Unit, onStatus: (MealStatus) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
                Text(
                    meal.slot.name.lowercase().replaceFirstChar { it.uppercase() } + if (meal.leftover) " · leftovers" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    meal.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (meal.status == MealStatus.SKIPPED) TextDecoration.LineThrough else null,
                )
                Text(
                    "${formatAmount(meal.portions)} ${if (meal.portions == 1.0) "serving" else "servings"} · " +
                        "${meal.kcal.roundToInt()} kcal · ${meal.proteinG.roundToInt()} g protein",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (meal.status) {
                    MealStatus.PLANNED -> {
                        Button(onClick = { onStatus(MealStatus.EATEN) }) { Text("Eaten") }
                        OutlinedButton(onClick = { onStatus(MealStatus.SKIPPED) }) { Text("Skipped") }
                    }
                    MealStatus.EATEN -> {
                        Text("✓ Eaten", color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { onStatus(MealStatus.PLANNED) }) { Text("Undo") }
                    }
                    MealStatus.SKIPPED -> {
                        Text("Skipped", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onStatus(MealStatus.PLANNED) }) { Text("Undo") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtraDialog(onDismiss: () -> Unit, onConfirm: (String, Double, Double) -> Unit) {
    var description by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    val kcalValue = kcal.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    val proteinValue = if (protein.isBlank()) 0.0 else protein.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Something else you ate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(description, { description = it }, label = { Text("What") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    kcal, { kcal = it },
                    label = { Text("Calories (a rough number is fine)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    protein, { protein = it },
                    label = { Text("Protein in grams (optional)") },
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
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
