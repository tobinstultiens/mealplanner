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

private fun kg(value: Double, locale: FormatLocale): String = "${formatAmount((value * 10).roundToInt() / 10.0, locale)} kg"

/** Weight log with its trend, and the calorie-target suggestion that follows from it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(api: ApiClient, onOpenProfile: () -> Unit, onOpenServer: () -> Unit) {
    val t = LocalStrings.current
    val today = rememberToday()
    val loaded = rememberLoad<WeightLog>("weights")
    var state by loaded
    var reload by remember { mutableIntStateOf(0) }
    val frontReturns = rememberFrontReturns()
    val scroll = rememberScrollState()
    var weightText by remember { mutableStateOf("") }
    var dismissed by remember { mutableStateOf<TargetSuggestion?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(loaded, reload, frontReturns) { loaded.refresh { api.weights() } }

    fun change(action: suspend () -> WeightLog) {
        scope.launch {
            when (val result = load(action)) {
                is Load.Ready -> state = result
                is Load.Failed -> snackbar.showSnackbar(failureSentence(result))
                Load.Loading -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(Msg.TAB_PROGRESS)) },
                actions = {
                    BarAction(AppIcons.Person, t(Msg.PROFILE_AND_TARGETS), onOpenProfile)
                    BarAction(AppIcons.Settings, t(Msg.SETTINGS), onOpenServer)
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
                ErrorMessage(t(Msg.WEIGHT_LOAD_FAILED), current) { reload++ }
            }
            is Load.Ready -> {
                val log = current.value
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(Spacing.ScreenEdge),
                    verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.BetweenCards)) {
                        val value = weightText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 30.0..300.0 }
                        OutlinedTextField(
                            weightText, { weightText = it },
                            label = { Text(t(Msg.TODAYS_WEIGHT)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        Button(enabled = value != null, onClick = {
                            change { api.setWeight(today, value!!) }
                            weightText = ""
                        }) { Text(t(Msg.SAVE)) }
                    }

                    if (log.entries.isEmpty()) {
                        Text(
                            t(Msg.WEIGH_HINT),
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
                                        if (result is Load.Ready) t(Msg.TARGET_NOW, suggestion.suggestedKcal) else t(Msg.TARGET_CHANGE_FAILED),
                                    )
                                    reload++
                                }
                            },
                            onDismiss = { dismissed = suggestion },
                        )
                    }

                    if (log.entries.isNotEmpty()) SectionHeader(t(Msg.WEIGHINGS))
                    for (entry in log.entries.asReversed().take(30)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // The day above the reading: side by side the day was squeezed to one word a line at large fonts.
                            Column(Modifier.weight(1f)) {
                                Text(dayLabel(entry.date, t.locale))
                                Text(t(Msg.WEIGHING_ROW, kg(entry.kg, t.locale), kg(entry.trendKg, t.locale)), style = MaterialTheme.typography.bodyMedium.numeric())
                            }
                            TextButton(onClick = { change { api.deleteWeight(entry.date) } }) { Text(t(Msg.REMOVE)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendSummary(log: WeightLog) {
    val t = LocalStrings.current
    val last = log.entries.last()
    val first = log.entries.first()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            Text(t(Msg.TREND_WEIGHT), style = MaterialTheme.typography.labelLarge)
            Text(kg(last.trendKg, t.locale), style = MaterialTheme.typography.headlineMedium)
            val change = last.trendKg - first.trendKg
            if (log.entries.size >= 2) {
                Text(
                    t(if (change <= 0) Msg.DOWN_SINCE else Msg.UP_SINCE, kg(abs(change), t.locale), dayMonthLabel(first.date, t.locale)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                log.actualKgPerWeek?.let {
                    if (it >= 0) t(Msg.LOSING, formatAmount((it * 100).roundToInt() / 100.0, t.locale))
                    else t(Msg.GAINING, formatAmount((-it * 100).roundToInt() / 100.0, t.locale))
                } ?: t(Msg.RATE_LATER),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: TargetSuggestion, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            Text(t(Msg.ADJUST_TARGET), style = MaterialTheme.typography.titleMedium)
            val rate = formatAmount((abs(suggestion.actualKgPerWeek) * 100).roundToInt() / 100.0, t.locale)
            Text(
                t(
                    if (suggestion.actualKgPerWeek >= 0) Msg.SUGGESTION_LOSS else Msg.SUGGESTION_GAIN,
                    formatAmount(suggestion.goalKgPerWeek, t.locale), rate, suggestion.currentKcal, suggestion.suggestedKcal,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                Button(onClick = onAccept) { Text(t(Msg.USE_KCAL, suggestion.suggestedKcal)) }
                TextButton(onClick = onDismiss) { Text(t(Msg.NOT_NOW)) }
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
    val t = LocalStrings.current
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

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        SectionHeader(t(Msg.WEIGHT_TREND_CHART))
        Text(
            selected?.let { entries[it] }?.let { t(Msg.CHART_SELECTED, dayLabel(it.date, t.locale), kg(it.kg, t.locale), kg(it.trendKg, t.locale)) }
                ?: t(Msg.CHART_HINT),
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
                val label = measurer.measure(formatAmount(tick, t.locale), labelStyle)
                drawText(label, topLeft = Offset(left - label.size.width - 6.dp.toPx(), y(tick) - label.size.height / 2))
            }
            val firstLabel = measurer.measure(dayMonthLabel(entries.first().date, t.locale), labelStyle)
            val lastLabel = measurer.measure(dayMonthLabel(entries.last().date, t.locale), labelStyle)
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
