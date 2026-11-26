package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.MealFood
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.WeekPlan
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/*
 * Foods in a slot of the plan (issue 358): a slot holds a recipe, foods, or a recipe with foods next to it, each food in the
 * unit it was picked in ("2 bar"). README, "Foods in a slot".
 */

/** A slot of the plan a food is picked for, with [food] when it is one planned there already, to change or take out. */
@Serializable
data class FoodPlace(val day: Int, val slot: MealSlot, val food: MealFood? = null)

/**
 * What a meal is called where one line names it (a dialog, the skip sheet): its recipe's title, or for foods alone their names
 * in the app's language, one or two as they are and more as the first and how many follow ("Banana + 2 more"). The
 * server's title of foods alone is in English, for apps that do not know foods.
 */
fun PlannedMeal.shownTitle(t: Strings): String = when {
    !foodsOnly || foods.isEmpty() -> title
    foods.size <= 2 -> foods.joinToString(", ") { it.name }
    else -> t(Msg.FOODS_AND_MORE, foods.first().name, foods.size - 1)
}

/** Whether a food can go in [meal]'s slot, as the server lets it: not in a skipped meal, which counts for nothing, nor a prepped one. */
fun takesFoods(meal: PlannedMeal?): Boolean = meal == null || (meal.status != MealStatus.SKIPPED && meal.prep == null)

/** `2 bar`, `1 piece`, `150 g`: an [amount] of a food in the [unit] it was picked in, in the app's language. */
fun foodAmount(amount: Double, unit: String, t: Strings): String = if (unit == PIECE) {
    t.plural(Plural.PIECES_AMOUNT, if (amount == 1.0) 1 else 2, formatAmount(amount, t.locale))
} else {
    "${formatAmount(amount, t.locale)} ${unitLabel(unit, t)}"
}

/**
 * `2 bar · 360 kcal`, `1 piece · 108 kcal`, `150 g · 525 kcal`: a food of a slot in the unit it was picked in, and what it
 * counts; one that counts as nothing says so.
 */
fun MealFood.amountText(t: Strings): String {
    val amount = foodAmount(this.amount, unit, t)
    return unbrokenParts(if (counted) t(Msg.FOOD_AMOUNT, amount, kcal.roundToLong()) else t(Msg.FOOD_UNCOUNTED, amount))
}

/** The foods of a slot under its meal, a row each: the name and [amountText]; with [onOpen], a tap opens one. */
@Composable
fun FoodRows(foods: List<MealFood>, enabled: Boolean = true, onOpen: ((MealFood) -> Unit)? = null) {
    val t = LocalStrings.current
    Column(Modifier.fillMaxWidth()) {
        for (food in foods) {
            val row = Modifier.fillMaxWidth().let { if (onOpen != null) it.clickable(enabled = enabled) { onOpen(food) } else it }
            Column(row.padding(vertical = 4.dp)) {
                Text(food.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    food.amountText(t),
                    style = MaterialTheme.typography.bodySmall.numeric(),
                    color = if (food.counted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * A food for [place]'s slot on day [FoodPlace.day] of the week starting [weekStart] (issue 358), picked on the [FoodScreen]
 * under the slot's name and the day. For a food planned already, its amount and unit to change, and taking it out. [onDone]
 * gets the week as the server answers the change.
 */
@Composable
fun SlotFoodScreen(api: ApiClient, weekStart: String, place: FoodPlace, onBack: () -> Unit, onDone: (WeekPlan) -> Unit) {
    val t = LocalStrings.current
    val food = place.food
    // The slot alone in the bar, which has one line, and the day as the first line under it: both fit at the largest font.
    FoodScreen(
        api, t.slot(place.slot, capitalised = true), dayLabel(plusDays(weekStart, place.day), t.locale),
        food?.let { ChosenFood(it.ingredientId, it.name, it.amount, it.unit) }, onBack,
        save = { sent -> if (food == null) api.addFood(weekStart, place.day, place.slot, sent) else api.changeFood(weekStart, place.day, place.slot, food.id, sent) },
        remove = { api.removeFood(weekStart, place.day, place.slot, checkNotNull(food).id) },
        onDone = onDone,
    )
}
