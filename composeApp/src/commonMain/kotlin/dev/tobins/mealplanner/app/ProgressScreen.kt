package dev.tobins.mealplanner.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.TargetSuggestion
import dev.tobins.mealplanner.api.WeightEntry
import dev.tobins.mealplanner.api.WeightLog
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private fun kg(value: Double): String = "${formatAmount((value * 10).roundToInt() / 10.0)} kg"

/** Weight log with its trend, and the calorie-target suggestion that follows from it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(api: ApiClient, onOpenProfile: () -> Unit) {
    val today = remember { todayIso() }
    var state by remember { mutableStateOf<Load<WeightLog>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var weightText by remember { mutableStateOf("") }
    var dismissed by remember { mutableStateOf<TargetSuggestion?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        if (state !is Load.Ready) state = Load.Loading
        state = load { api.weights() }
    }

    fun change(action: suspend () -> WeightLog) {
        scope.launch {
            when (val result = load(action)) {
                is Load.Ready -> state = result
                is Load.Failed -> snackbar.showSnackbar(result.message)
                Load.Loading -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Progress") },
                actions = { TextButton(onClick = onOpenProfile) { Text("Profile and targets") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            Load.Loading -> CenteredBox(Modifier.padding(padding)) { CircularProgressIndicator() }
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage("Could not load your weight log", current.message) { reload++ }
            }
            is Load.Ready -> {
                val log = current.value
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val value = weightText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 30.0..300.0 }
                        OutlinedTextField(
                            weightText, { weightText = it },
                            label = { Text("Today's weight (kg)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        Button(enabled = value != null, onClick = {
                            change { api.setWeight(today, value!!) }
                            weightText = ""
                        }) { Text("Save") }
                    }

                    if (log.entries.isEmpty()) {
                        Text(
                            "Weigh yourself at the same time each day, for example after getting up. " +
                                "After two weeks the trend shows whether the calorie target fits.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TrendSummary(log)
                        if (log.entries.size >= 2) WeightChart(log.entries)
                    }

                    log.suggestion?.takeIf { it != dismissed }?.let { suggestion ->
                        SuggestionCard(
                            suggestion = suggestion,
                            onAccept = {
                                scope.launch {
                                    val result = load {
                                        val profile = api.profile().profile ?: error("No profile")
                                        api.saveProfile(profile.copy(kcalOverride = suggestion.suggestedKcal))
                                    }
                                    snackbar.showSnackbar(
                                        if (result is Load.Ready) "Calorie target is now ${suggestion.suggestedKcal} kcal" else "Could not change the target",
                                    )
                                    reload++
                                }
                            },
                            onDismiss = { dismissed = suggestion },
                        )
                    }

                    if (log.entries.isNotEmpty()) Text("Weighings", style = MaterialTheme.typography.titleSmall)
                    for (entry in log.entries.asReversed().take(30)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dayLabel(entry.date), Modifier.weight(1f))
                            Text("${kg(entry.kg)}  ·  trend ${kg(entry.trendKg)}", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { change { api.deleteWeight(entry.date) } }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendSummary(log: WeightLog) {
    val last = log.entries.last()
    val first = log.entries.first()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Trend weight", style = MaterialTheme.typography.labelLarge)
            Text(kg(last.trendKg), style = MaterialTheme.typography.headlineMedium)
            val change = last.trendKg - first.trendKg
            if (log.entries.size >= 2) {
                Text(
                    "${if (change <= 0) "Down" else "Up"} ${kg(abs(change))} since ${dayLabel(first.date).substringAfter(' ')}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                log.actualKgPerWeek?.let {
                    if (it >= 0) "Losing ${formatAmount((it * 100).roundToInt() / 100.0)} kg a week over the last two weeks"
                    else "Gaining ${formatAmount((-it * 100).roundToInt() / 100.0)} kg a week over the last two weeks"
                } ?: "A weekly rate appears after two weeks of regular weighings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: TargetSuggestion, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Adjust your calorie target?", style = MaterialTheme.typography.titleMedium)
            val rate = formatAmount((abs(suggestion.actualKgPerWeek) * 100).roundToInt() / 100.0)
            Text(
                "Your goal is ${formatAmount(suggestion.goalKgPerWeek)} kg a week, and the trend shows " +
                    "${if (suggestion.actualKgPerWeek >= 0) "a loss" else "a gain"} of $rate kg a week. " +
                    "Changing the target from ${suggestion.currentKcal} to ${suggestion.suggestedKcal} kcal should close the gap.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text("Use ${suggestion.suggestedKcal} kcal") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}

/**
 * Weight over time: each weighing as a faint dot, the smoothed trend as the line.
 * One series, so the title names it and no legend is needed. Touch or drag across
 * the chart to read a single day.
 */
@Composable
private fun WeightChart(entries: List<WeightEntry>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    var selected by remember(entries) { mutableStateOf<Int?>(null) }

    // Whole-kilogram bounds with room around the data, so the axis labels are round numbers.
    val low = floor(entries.minOf { minOf(it.kg, it.trendKg) } - 0.3)
    val high = ceil(entries.maxOf { maxOf(it.kg, it.trendKg) } + 0.3)
    val ticks = listOf(low, (low + high) / 2, high)
    // Position along the time axis: days since the first weighing, so gaps show as gaps.
    val days = remember(entries) { entries.map { daysBetween(entries.first().date, it.date) } }
    val span = days.last().coerceAtLeast(1)

    /** The weighing closest in time to a horizontal position given as a fraction of the plot width. */
    fun nearest(fraction: Float): Int {
        val day = fraction.coerceIn(0f, 1f) * span
        return days.indices.minBy { abs(days[it] - day) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Weight trend (kg)", style = MaterialTheme.typography.titleSmall)
        Text(
            selected?.let { entries[it] }?.let { "${dayLabel(it.date)}: weighed ${kg(it.kg)}, trend ${kg(it.trendKg)}" }
                ?: "Line: trend. Dots: daily weighings. Touch the chart to read a day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier.fillMaxWidth().height(200.dp)
                .pointerInput(entries) {
                    val left = 44.dp.toPx()
                    detectTapGestures { selected = nearest((it.x - left) / (size.width - left - 8.dp.toPx())) }
                }
                .pointerInput(entries) {
                    val left = 44.dp.toPx()
                    detectDragGestures(onDragEnd = { selected = null }) { change, _ ->
                        selected = nearest((change.position.x - left) / (size.width - left - 8.dp.toPx()))
                    }
                },
        ) {
            val left = 44.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 20.dp.toPx()
            fun x(index: Int) = left + (right - left) * days[index] / span
            fun y(value: Double) = (bottom - (bottom - top) * ((value - low) / (high - low))).toFloat()

            // Recessive grid with value labels in text colour.
            for (tick in ticks) {
                drawLine(gridColor, Offset(left, y(tick)), Offset(right, y(tick)), strokeWidth = 1.dp.toPx())
                val label = measurer.measure(formatAmount(tick), labelStyle)
                drawText(label, topLeft = Offset(left - label.size.width - 6.dp.toPx(), y(tick) - label.size.height / 2))
            }
            val firstLabel = measurer.measure(dayLabel(entries.first().date).substringAfter(' '), labelStyle)
            val lastLabel = measurer.measure(dayLabel(entries.last().date).substringAfter(' '), labelStyle)
            drawText(firstLabel, topLeft = Offset(left, bottom + 4.dp.toPx()))
            drawText(lastLabel, topLeft = Offset(right - lastLabel.size.width, bottom + 4.dp.toPx()))

            entries.forEachIndexed { index, entry -> drawCircle(dotColor, radius = 3.dp.toPx(), center = Offset(x(index), y(entry.kg))) }

            val path = Path()
            entries.forEachIndexed { index, entry ->
                if (index == 0) path.moveTo(x(index), y(entry.trendKg)) else path.lineTo(x(index), y(entry.trendKg))
            }
            drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            selected?.let { index ->
                drawLine(gridColor, Offset(x(index), top), Offset(x(index), bottom), strokeWidth = 1.dp.toPx())
                drawCircle(lineColor, radius = 5.dp.toPx(), center = Offset(x(index), y(entries[index].trendKg)))
            }
        }
    }
}
