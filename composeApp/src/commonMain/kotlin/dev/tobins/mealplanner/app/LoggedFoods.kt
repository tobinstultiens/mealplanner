package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.LogFoodInput
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/*
 * Food eaten outside the plan, picked from the catalogue in an amount (issue 362) or typed. The server counts a food when it
 * is logged or its amount changed, and the entry keeps those numbers. README, "Food eaten outside the plan".
 */

/** The log's food screen on [date], with [entry] when it opens a logged food to change, or none for a food to add. */
@Serializable
data class LogFoodPlace(val date: String, val entry: LogEntry? = null)

/** True when [this] is a food that can still be recounted: its food is in the catalogue. Else it is typed over, if at all. */
val LogEntry.recountable: Boolean get() = food?.ingredientId != null

/** What an entry is called in the app's language: a food by its name as it was logged, a typed entry by what was typed. */
val LogEntry.shownName: String get() = food?.name ?: description

/**
 * The line under an entry's name: a food's amount in its unit and what it counted ("1 bar · 171 kcal · 14 g protein"), or
 * that it counted nothing; a typed entry's numbers.
 */
fun LogEntry.numbersText(t: Strings): String {
    val numbers = t(Msg.KCAL_PROTEIN, kcal.roundToInt(), proteinG.roundToInt())
    val food = food ?: return numbers
    val amount = foodAmount(food.amount, food.unit, t)
    return unbrokenParts(if (food.counted) "$amount · $numbers" else t(Msg.FOOD_UNCOUNTED, amount))
}

/** How [this] is typed over when it cannot be recounted: a food's amount and name in the app's language, or what was typed. */
fun LogEntry.typedText(t: Strings): String = food?.let { "${foodAmount(it.amount, it.unit, t)} ${it.name}" } ?: description

/**
 * A food eaten on [LogFoodPlace.date] outside the plan (issue 362), picked on the [FoodScreen] under "Also eaten" and the
 * day; for a logged food, under "Change food": what it counted, its amount and unit to change, which the server
 * recounts, and removing the entry. [onDone] gets the day as the server answers the change.
 */
@Composable
fun LogFoodScreen(api: ApiClient, place: LogFoodPlace, onBack: () -> Unit, onDone: (DayView) -> Unit) {
    val t = LocalStrings.current
    val entry = place.entry
    val food = entry?.food
    FoodScreen(
        api, t(if (entry == null) Msg.ALSO_EATEN else Msg.LOG_CHANGE_FOOD_TITLE), dayLabel(place.date, t.locale),
        food?.ingredientId?.let { ChosenFood(it, food.name, food.amount, food.unit, entry.kcal, entry.proteinG) }, onBack,
        save = { sent ->
            if (entry == null) api.logPickedFood(LogFoodInput(place.date, sent.ingredientId, sent.amount, sent.unit)) else api.changeLoggedFood(entry.id, sent)
        },
        remove = {
            api.deleteLog(checkNotNull(entry).id)
            api.day(place.date)
        },
        onDone = onDone,
    )
}
