package dev.tobins.mealplanner.app.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.BatchToMake
import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.ingredients.RadioOption
import dev.tobins.mealplanner.app.recipes.MealToCook
import dev.tobins.mealplanner.app.ui.CommonMsg
import dev.tobins.mealplanner.app.ui.MealNumbers
import dev.tobins.mealplanner.app.ui.Spacing
import dev.tobins.mealplanner.app.ui.dayLabel
import dev.tobins.mealplanner.app.ui.formatEuro
import dev.tobins.mealplanner.app.ui.macrosText
import dev.tobins.mealplanner.app.ui.numeric
import dev.tobins.mealplanner.app.ui.plusDays
import dev.tobins.mealplanner.app.ui.servingsText
import dev.tobins.mealplanner.app.ui.unbrokenParts
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Sides (issue 234, step 2): a quarter or half a serving of a prepped batch next to a lunch's or dinner's main, added
 * by hand, from a new prep or one still to make (issue 269). The main keeps its own portion; the side is eaten or skipped
 * with it, and the day counts both.
 */

/** The portions a side may be, in servings: what the Prep dialog offers and the server takes. */
val SIDE_PORTIONS = listOf(0.25, 0.5)

/** Whether the meal at [date]'s [slot] is where [this] side's batch is made: its first meal. */
fun MealSide.isPrepDay(date: String, slot: MealSlot): Boolean = prep.meals.firstOrNull()?.let { it.date == date && it.slot == slot } == true

/**
 * What [this] side next to a meal ([skipped] or not) passes on to its recipe: its portions, and the batch to cook where it is
 * made. No place in the plan: cook mode marks a meal eaten, and a side is eaten with its meal.
 */
fun MealSide.toCook(skipped: Boolean): MealToCook = MealToCook(recipeId, portions, cookPortions, skipped, prepMadeOn = prep.madeOn)

/**
 * The lunches and dinners of [weeks] a side can go next to: those with a meal still to eat that has no side yet, as the
 * server takes them, each with the id of the recipe of its main, which a side may not be of (no plate holds one recipe
 * twice; [PrepChoice.canTake] looks at it). A week not loaded (null) offers none.
 */
fun sideHosts(vararg weeks: WeekPlan?): Map<PrepSlot, Long> = weeks.filterNotNull().flatMap { week ->
    week.days.flatMap { day ->
        day.meals.filter { it.slot in setOf(MealSlot.LUNCH, MealSlot.DINNER) && it.status == MealStatus.PLANNED && it.side == null }
            .map { PrepSlot(day.date, it.slot) to it.recipeId }
    }
}.toMap()

/**
 * The batches a side next to [meal] of [week] can be added to, which makes them larger (issue 269), as the server takes
 * them: still to make (no meal of them eaten), with their first share before [meal] and their last day not before it,
 * neither [meal]'s own nor of its recipe, so that no plate holds one recipe twice. None when [meal] can't take a side: not
 * a lunch or dinner still to eat from [today] on, or with one already. Every batch of the account whose days reach into
 * [week] ([WeekPlan.batchesToMake], issue 263), also one made the week before with no share in it; from a server before
 * that, only those with a meal or side in [week].
 */
fun growable(week: WeekPlan, meal: PlannedMeal, today: String): List<BatchToMake> {
    val date = plusDays(week.weekStart, meal.day)
    val canTakeOne = meal.slot in setOf(MealSlot.LUNCH, MealSlot.DINNER) && meal.status == MealStatus.PLANNED && meal.side == null && date >= today
    if (!canTakeOne) return emptyList()
    val inWeek = week.days.flatMap { it.meals }.flatMap { other ->
        listOfNotNull(other.prep?.let { BatchToMake(other.recipeId, other.title, it) }, other.side?.let { BatchToMake(it.recipeId, it.title, it.prep) })
    }
    val batches = (week.batchesToMake + inWeek).distinctBy { it.prep.id }
    return batches.filter { batch ->
        val first = batch.prep.meals.firstOrNull()
        first != null && !batch.prep.made && date <= batch.prep.lastDay &&
            (first.date < date || (first.date == date && first.slot.ordinal < meal.slot.ordinal)) &&
            meal.prep?.id != batch.prep.id && meal.recipeId != batch.recipeId
    }
}

/**
 * "Add a prepped side…" when [batches] can take one (issue 269): a side of one of them next to the meal at [slot], of a
 * quarter or half a serving, which makes that batch larger; or [onNewPrep], the Prep dialog for a new one. [onAdd] adds
 * it, and says why when the server refuses.
 */
@Composable
fun AddSideDialog(
    slot: PrepSlot,
    batches: List<BatchToMake>,
    onDismiss: () -> Unit,
    onNewPrep: () -> Unit,
    onAdd: suspend (BatchToMake, Double) -> String?,
) {
    val t = LocalStrings.current
    val scope = rememberCoroutineScope()
    // Kept across a re-created activity, as the dialog is (issue 263).
    var batch by rememberSaveable(stateSaver = jsonSaver(BatchToMake.serializer())) { mutableStateOf(batches.first()) }
    var portions by rememberSaveable { mutableStateOf(SIDE_PORTIONS.last()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(PlanMsg.ADD_SIDE_TITLE, slotLabel(slot, t))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AddSideChoices(batches, batch, portions, onBatch = { batch = it; error = null }, onPortions = { portions = it; error = null }, onNewPrep = onNewPrep)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        error = onAdd(batch, portions)
                        saving = false
                    }
                },
            ) { Text(t(CommonMsg.ADD)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(CommonMsg.CANCEL)) } },
    )
}

/**
 * The body of [AddSideDialog]: the batches to take the side from, each with the day it is made and its last day, the side's
 * servings, and a new prep instead. A dialog is a window of its own, so the screenshot tests take this.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddSideChoices(
    batches: List<BatchToMake>,
    batch: BatchToMake,
    portions: Double,
    onBatch: (BatchToMake) -> Unit,
    onPortions: (Double) -> Unit,
    onNewPrep: () -> Unit,
) {
    val t = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
        Text(t(PlanMsg.ADD_SIDE_HINT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(t(PlanMsg.ADD_SIDE_FROM), style = MaterialTheme.typography.labelLarge)
        for (each in batches) {
            val detail = t(PlanMsg.ADD_SIDE_BATCH, dayLabel(each.prep.madeOn, t.locale), dayLabel(each.prep.lastDay, t.locale))
            RadioOption(each.title, detail, selected = each == batch) { onBatch(each) }
        }
        Text(t(PlanMsg.ADD_SIDE_SIZE), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            for (size in SIDE_PORTIONS) {
                FilterChip(selected = size == portions, onClick = { onPortions(size) }, label = { Text(servingsText(size, t)) })
            }
        }
        TextButton(onClick = onNewPrep) { Text(t(PlanMsg.ADD_SIDE_NEW_PREP)) }
    }
}

/**
 * What the day card says (a note, not a refusal) when [this] day has sides and goes over [targets]: the kcal it goes over by
 * and the kcal its sides add, rounded; null otherwise. Both, as the day may have been over without its sides already.
 */
fun PlanDay.overWithSides(targets: DailyTargets): Pair<Int, Int>? {
    val sides = meals.filter { it.status != MealStatus.SKIPPED }.mapNotNull { it.side }
    val over = (kcal - targets.kcal).roundToInt()
    return if (sides.isEmpty() || over <= 0) null else over to sides.sumOf { it.kcal }.roundToInt()
}

/** What [this] side gives, as a meal's numbers: `0.5 servings · 225 kcal · 30 g protein`. */
fun MealSide.macrosText(strings: Strings): String =
    "${unbrokenParts(servingsText(portions, strings))} · ${macrosText(kcal, proteinG, fibreG, strings)}"

/** What [this] meal comes to with its side: `With the side: 945 kcal · 44 g protein · 9 g fibre`. */
fun PlannedMeal.withSideText(strings: Strings): String = strings(PlanMsg.MEAL_WITH_SIDE, macrosText(totalKcal, totalProteinG, totalFibreG, strings))

/**
 * The side next to [meal], on [date]: labelled as prepped, with its title and numbers, then what the meal comes to with it.
 * A tap opens its recipe ([onOpen]); struck through when the meal is skipped, as the side is skipped with it.
 */
@Composable
fun SideRow(meal: PlannedMeal, side: MealSide, date: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalStrings.current
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp)) {
            Text(t.sideLabel(side, date, meal.slot), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                side.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (meal.status == MealStatus.SKIPPED) TextDecoration.LineThrough else null,
            )
            MealNumbers(side.macrosText(t), side.cost?.let { "≈ ${formatEuro(it, t.locale)}" })
        }
        Text(meal.withSideText(t), style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
