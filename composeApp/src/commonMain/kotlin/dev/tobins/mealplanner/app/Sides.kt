package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.WeekPlan
import kotlin.math.roundToInt

/*
 * Sides (issue 234, step 2): a quarter or half a serving of a prepped batch next to a lunch's or dinner's main, added
 * by hand. The main keeps its own portion; the side is eaten or skipped with it, and the day counts both.
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
 * server takes them. A week not loaded (null) offers none.
 */
fun sideHosts(vararg weeks: WeekPlan?): Set<PrepSlot> = weeks.filterNotNull().flatMap { week ->
    week.days.flatMap { day ->
        day.meals.filter { it.slot in setOf(MealSlot.LUNCH, MealSlot.DINNER) && it.status == MealStatus.PLANNED && it.side == null }
            .map { PrepSlot(day.date, it.slot) }
    }
}.toSet()

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
fun PlannedMeal.withSideText(strings: Strings): String = strings(Msg.MEAL_WITH_SIDE, macrosText(totalKcal, totalProteinG, totalFibreG, strings))

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
