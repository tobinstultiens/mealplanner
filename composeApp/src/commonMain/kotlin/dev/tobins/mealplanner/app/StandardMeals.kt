package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.StandardMeal
import dev.tobins.mealplanner.api.StandardMeals
import dev.tobins.mealplanner.api.StandardSwitch
import kotlin.math.roundToInt

/**
 * The standard meals (issue 265) to offer in [day]'s [slot] of the week [offer] is of: those set to be planned as [slot],
 * at the portion it gets, but not the recipe of the meal just [skipped] there, which is what was not wanted. None where the
 * server says no standard meal can take the slot's meal ([StandardMeals.closed]), and none from a server before standard
 * meals ([offer] null).
 */
fun standardChoices(offer: StandardMeals?, day: Int, slot: MealSlot, skipped: Long? = null): List<StandardMeal> =
    if (offer == null || offer.closed.any { it.day == day && it.slot == slot }) emptyList()
    else offer.meals.filter { it.slot == slot && it.recipeId != skipped }

/** What a chip of [this] standard meal says first: `Eggs on bread · 553 kcal`, the calories at the slot's portion. */
fun StandardMeal.chipText(strings: Strings): String = strings(Msg.STANDARD_CHIP, title, kcal.roundToInt())

/**
 * What a chip of [this] says under its title when the pantry has ingredients of it not at home: `no eggs at home`, `no
 * eggs, feta or tuna at home`; null when it has all.
 */
fun StandardMeal.missingText(strings: Strings): String? {
    val names = missing.map { it.replaceFirstChar(Char::lowercase) }.ifEmpty { return null }
    val listed = if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " " + strings(Msg.STANDARD_OR) + " " + names.last()
    return strings(Msg.STANDARD_MISSING, listed)
}

/**
 * Whether choosing another recipe for [meal] takes it out of its prepped batch, which is then made smaller: a later meal
 * of a batch still to make (none of its meals eaten), not skipped. A skipped one's share stays in the box when a standard
 * meal takes its place; the meal that makes a batch can't be replaced at all.
 */
fun shrinksPrep(meal: PlannedMeal?): Boolean {
    val prep = meal?.prep ?: return false
    return meal.leftover && meal.status == MealStatus.PLANNED && prep.meals.none { it.status == MealStatus.EATEN }
}

/** What the recipe's switch says after marking it ([switch]): the ingredients it made staples, and those it could not. */
fun standardReport(switch: StandardSwitch, strings: Strings): List<String> = listOfNotNull(
    if (switch.addedStaples.isEmpty()) strings(Msg.STANDARD_NONE_ADDED) else strings(Msg.STANDARD_ADDED_STAPLES, switch.addedStaples.joinToString(", ")),
    switch.unlinked.takeIf { it.isNotEmpty() }?.let { strings(Msg.STANDARD_UNLINKED, it.joinToString(", ")) },
)

/** One chip per standard meal of [choices]: its title and calories, and on a second line an ingredient that is out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StandardChips(choices: List<StandardMeal>, enabled: Boolean, onPick: (StandardMeal) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalStrings.current
    // Spaced both ways: chips of two lines, or at a large font, wrap onto rows that would otherwise touch.
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.Related), verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
        for (choice in choices) {
            AssistChip(
                enabled = enabled,
                onClick = { onPick(choice) },
                label = {
                    // A chip grows with its lines: an ingredient that is out is said, and the meal can be chosen all the same.
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(choice.chipText(t), style = MaterialTheme.typography.labelLarge.numeric())
                        choice.missingText(t)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                    }
                },
            )
        }
    }
}

/**
 * The skip sheet of the Plan: a meal was just skipped ([title]), and the standard meals for its slot ([choices]) are
 * offered at once, one tap each; leaving it empty is the skip as it was.
 */
@Composable
fun SkipSheet(title: String, choices: List<StandardMeal>, enabled: Boolean, onDismiss: () -> Unit, onPick: (StandardMeal) -> Unit) {
    val t = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Msg.SKIP_SHEET_TITLE, title)) },
        text = { SkipSheetBody(choices, enabled, onPick) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t(Msg.SKIP_SHEET_LEAVE)) } },
    )
}

/** The skip sheet's question and chips; split from the dialog so a screenshot can show it. */
@Composable
fun SkipSheetBody(choices: List<StandardMeal>, enabled: Boolean, onPick: (StandardMeal) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
        Text(LocalStrings.current(Msg.SKIP_SHEET_TEXT))
        StandardChips(choices, enabled, onPick)
    }
}

/**
 * The switch that makes one of my meals a standard meal, with a line on what that means; after marking it here, what
 * that did to the staples ([report], [standardReport]), which the screen keeps while it is open.
 */
@Composable
fun StandardMealRow(standard: Boolean, report: StandardSwitch?, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        SwitchRow(t(Msg.STANDARD_MEAL), standard, enabled, onChange)
        val hint = listOf(t(if (standard) Msg.STANDARD_ON_HINT else Msg.STANDARD_OFF_HINT)) + report?.let { standardReport(it, t) }.orEmpty()
        for (line in hint) {
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
