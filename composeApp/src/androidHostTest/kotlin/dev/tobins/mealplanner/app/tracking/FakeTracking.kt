package dev.tobins.mealplanner.app.tracking

import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.LogFoodInput
import dev.tobins.mealplanner.api.LogTextInput
import dev.tobins.mealplanner.api.LoggedFood
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.WeightEntry
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.FakeBackend.Refusal
import dev.tobins.mealplanner.app.plan.day
import dev.tobins.mealplanner.app.plan.foodOf
import dev.tobins.mealplanner.app.ui.FormatLocale
import dev.tobins.mealplanner.app.ui.formatAmount
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/* The tracking's part of [FakeBackend]: the foods logged as eaten and the weights. */

internal fun FakeBackend.logOf(date: String): List<LogEntry> = log.getOrPut(date) {
    val first = java.time.LocalDate.parse(date).toEpochDay() * 10
    val apple = LogEntry(first + 1, date, "Apple and a handful of almonds", 210.0, 5.0)
    if (!loggedFoods) return@getOrPut listOf(apple)
    listOf(
        apple,
        // Logged when its label said less than now (171 kcal and 14 g protein a bar): it keeps what it counted then.
        loggedOf(first + 2, date, MealFoodInput(proteinBar.id, 1.0, "bar")).copy(kcal = 160.0, proteinG = 13.0, fibreG = 2.5),
        LogEntry(first + 3, date, "1 bar Muesli bar", 120.0, 2.5, fibreG = 1.5, food = LoggedFood(null, "Muesli bar", 1.0, "bar")),
    )
}

/** [input] logged on [date] as entry [id]: counted as [foodOf] counts a slot's food, with a line in English as the server's. */
internal fun FakeBackend.loggedOf(id: Long, date: String, input: MealFoodInput): LogEntry {
    val food = foodOf(id, input)
    return LogEntry(
        id, date, "${formatAmount(food.amount, FormatLocale.English)} ${food.unit} ${food.name}", food.kcal, food.proteinG, food.fibreG,
        LoggedFood(food.ingredientId, food.name, food.amount, food.unit),
    )
}

/**
 * `POST /api/log` and `/api/log/foods`, `PUT /api/log/{id}` and `/api/log/{id}/food`, `DELETE /api/log/{id}`: the log
 * changed, and the day it is on, as the server answers (a removal answers nothing).
 */
internal fun FakeBackend.logRoute(method: String, path: String, parts: List<String>, sent: String): String {
    changes += "$method $path $sent".trim()
    if (method == "POST") {
        val entry = if (parts.getOrNull(2) == "foods") {
            val input = json.decodeFromString<LogFoodInput>(sent)
            loggedOf(nextLogId.getAndIncrement(), input.date, MealFoodInput(input.ingredientId, input.amount, input.unit))
        } else {
            json.decodeFromString<LogEntry>(sent).copy(id = nextLogId.getAndIncrement())
        }
        log[entry.date] = logOf(entry.date) + entry
        return json.encodeToString(day(entry.date))
    }
    val id = parts[2].toLong()
    val date = log.entries.firstOrNull { (_, entries) -> entries.any { it.id == id } }?.key ?: throw Refusal(404, "No log entry with id $id")
    log[date] = when {
        method == "DELETE" -> logOf(date).filterNot { it.id == id }
        parts.getOrNull(3) == "food" -> logOf(date).map { if (it.id == id) loggedOf(id, date, json.decodeFromString<MealFoodInput>(sent)) else it }
        else -> {
            val text = json.decodeFromString<LogTextInput>(sent)
            logOf(date).map { if (it.id == id) LogEntry(id, date, text.description, text.kcal, text.proteinG) else it }
        }
    }
    return if (method == "DELETE") "" else json.encodeToString(day(date))
}

/**
 * `PUT` and `DELETE /api/weights/{date}`: the log with that day set or removed (a trend of its own is
 * not worked out: the reading stands for it), and the profile's weight following the newest weigh-in,
 * as on the server. Returns the log.
 */
internal fun FakeBackend.changeWeights(method: String, date: String, sent: String): String {
    changes += "$method /api/weights/$date $sent"
    val others = weights.entries.filter { it.date != date }
    val entries = if (method == "PUT") (others + sent.trim().toDouble().let { WeightEntry(date, it, it) }).sortedBy { it.date } else others
    weights = weights.copy(entries = entries)
    entries.lastOrNull()?.let { newest -> profile = profile?.copy(weightKg = newest.kg) }
    return json.encodeToString(weights)
}

// --- The planning screen (issue 255), with [planning]
