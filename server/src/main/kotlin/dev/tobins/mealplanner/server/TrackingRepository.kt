package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.LogFoodInput
import dev.tobins.mealplanner.api.LogTextInput
import dev.tobins.mealplanner.api.LoggedFood
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.WeightEntry
import dev.tobins.mealplanner.api.WeightLog
import dev.tobins.mealplanner.nutrition.PIECE_UNIT
import dev.tobins.mealplanner.nutrition.portionKey
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.FoodLog
import dev.tobins.mealplanner.tracking.Weighing
import dev.tobins.mealplanner.tracking.WeightTrend
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * What was actually eaten and weighed, next to what was planned. Food eaten outside the plan is the log (README, "Food
 * eaten outside the plan"): typed, or a food of the catalogue in an amount, counted once and kept so. It is not on the
 * shopping list and takes nothing from the stock: it was not planned.
 */
class TrackingRepository(
    private val db: Database,
    private val userId: Long,
    private val plans: PlanRepository,
    private val profiles: ProfileRepository,
    private val ingredients: IngredientRepository,
) {
    private val q = db.trackingQueries

    fun day(date: String): DayView {
        val day = parseDate(date)
        val (weekStart, index, meals) = plans.mealsOn(day)
        val extras = q.selectLogForDate(userId, day.toString()).executeAsList().map { it.toEntry() }
        val eaten = meals.filter { it.status == MealStatus.EATEN }
        val expected = meals.filter { it.status != MealStatus.SKIPPED }
        return DayView(
            date = day.toString(),
            weekStart = weekStart.toString(),
            dayIndex = index,
            targets = profiles.state().targets,
            meals = meals,
            extras = extras,
            // A meal counts with its side (issue 234), which is eaten or skipped with it.
            eatenKcal = eaten.sumOf { it.totalKcal } + extras.sumOf { it.kcal },
            eatenProteinG = eaten.sumOf { it.totalProteinG } + extras.sumOf { it.proteinG },
            expectedKcal = expected.sumOf { it.totalKcal } + extras.sumOf { it.kcal },
            expectedProteinG = expected.sumOf { it.totalProteinG } + extras.sumOf { it.proteinG },
            // A logged food counts its fibre as a planned meal does; a typed extra has none (issue 223 leaves it at none).
            eatenFibreG = eaten.sumOf { (it.fibreG ?: 0.0) + (it.side?.fibreG ?: 0.0) } + extras.sumOf { it.fibreG ?: 0.0 },
            expectedFibreG = expected.sumOf { (it.fibreG ?: 0.0) + (it.side?.fibreG ?: 0.0) } + extras.sumOf { it.fibreG ?: 0.0 },
        )
    }

    /** A typed entry, as the installed app sends it: a description and rough numbers. The food's fields are the server's. */
    fun log(entry: LogEntry): DayView {
        val day = parseDate(entry.date)
        require(entry.food == null && entry.fibreG == null) { "a food is logged with POST /api/log/foods, not typed" }
        val text = typed(LogTextInput(entry.description, entry.kcal, entry.proteinG))
        db.writing { q.insertLog(userId, day.toString(), text.description, text.kcal, text.proteinG) }
        return day(day.toString())
    }

    /**
     * Logs the account's food [LogFoodInput.ingredientId] (another's is not there, 404) in an amount of one of its units
     * ([foodLine]), with what it counts from its values now (issue 362).
     */
    fun logFood(input: LogFoodInput): DayView {
        val day = parseDate(input.date)
        db.writing {
            val food = counted(MealFoodInput(input.ingredientId, input.amount, input.unit))
            q.insertLoggedFood(
                userId, day.toString(), food.description, food.count.kcal, food.count.proteinG, food.count.fibreG,
                food.ingredient.id, food.ingredient.name, food.line.amount, food.line.unit, if (food.count.counted) 1 else 0, Instant.now().toString(),
            )
        }
        return day(day.toString())
    }

    /**
     * Entry [id] as [input]'s food in its amount, recounted from that food's values now. The same food, amount and unit
     * change nothing, so that a day keeps its numbers when a food's values changed since it was logged: saved again as it
     * was, by any client, the entry is not recounted. An entry not of the account is not there (404); a typed one, or one
     * whose food is gone, has nothing to recount from (409): it is typed over instead.
     */
    fun changeFood(id: Long, input: MealFoodInput): DayView {
        val date = db.writing {
            val entry = q.selectLog(userId, id).executeAsOneOrNull() ?: throw NotFoundException("No log entry with id $id")
            if (entry.ingredientId == null) {
                val gone = entry.foodName?.let { "$it is no longer among your foods: type its numbers over or remove it" }
                throw ConflictException(gone ?: "A typed entry has no food to recount: type its numbers over")
            }
            if (entry.isAsPicked(input)) return@writing entry.date
            val food = counted(input)
            q.updateLoggedFood(
                food.description, food.count.kcal, food.count.proteinG, food.count.fibreG, food.ingredient.id, food.ingredient.name,
                food.line.amount, food.line.unit, if (food.count.counted) 1 else 0, Instant.now().toString(), userId, id,
            )
            entry.date
        }
        return day(date)
    }

    /** Entry [id] typed over with [input]: a typed entry from then on, on its own date. Not the account's: 404. */
    fun retype(id: Long, input: LogTextInput): DayView {
        val text = typed(input)
        val date = db.writing {
            val entry = q.selectLog(userId, id).executeAsOneOrNull() ?: throw NotFoundException("No log entry with id $id")
            q.updateLogText(text.description, text.kcal, text.proteinG, userId, id)
            entry.date
        }
        return day(date)
    }

    fun deleteLog(id: Long) = db.writing { q.deleteLog(userId, id) }

    /** [input] with its description trimmed, once it is a description and plausible numbers; else a 400. */
    private fun typed(input: LogTextInput): LogTextInput {
        require(input.description.isNotBlank()) { "description must not be blank" }
        require(input.kcal in 0.0..10000.0 && input.proteinG in 0.0..1000.0) { "kcal and proteinG must be plausible, non-negative numbers" }
        return input.copy(description = input.description.trim())
    }

    /** A food as the log keeps it: the ingredient, its line as picked, what it counts now, and its line in English. */
    private class CountedFood(val ingredient: Ingredient, val line: IngredientLine, val count: FoodCount) {
        val description = describe(ingredient.name, line.amount ?: 0.0, line.unit.orEmpty())
    }

    /** [input] counted from the values of the account's ingredient it names (404 for another's); a 400 for an amount it cannot be in. */
    private fun counted(input: MealFoodInput): CountedFood {
        val ingredient = ingredients.find(input.ingredientId) ?: throw NotFoundException("No ingredient with id ${input.ingredientId}")
        val line = foodLine(ingredient, input)
        return CountedFood(ingredient, line, countOf(line, ingredient))
    }

    /** True when [input] is this entry's food in the amount and unit it has (a portion's name in any case). */
    private fun FoodLog.isAsPicked(input: MealFoodInput): Boolean {
        val unit = unit ?: return false
        return ingredientId == input.ingredientId && amount == input.amount && portionKey(unit) == portionKey(input.unit)
    }

    private fun FoodLog.toEntry() = LogEntry(
        id = id,
        date = date,
        description = description,
        kcal = kcal,
        proteinG = proteinG,
        fibreG = fibreG,
        food = foodName?.let { name -> LoggedFood(ingredientId, name, amount ?: 0.0, unit.orEmpty(), counted != 0L) },
    )

    fun weights(): WeightLog {
        val entries = WeightTrend.withTrend(
            q.selectWeights(userId).executeAsList().map { WeightEntry(it.date, it.kg) },
            dayOf = { LocalDate.parse(it).toEpochDay() },
        )
        val loss = WeightTrend.lossPerWeek(entries.map { Weighing(LocalDate.parse(it.date).toEpochDay(), it.kg) })
        val profile = profiles.profile()
        val targets = profiles.state().targets
        return WeightLog(
            entries = entries,
            actualKgPerWeek = loss,
            suggestion = if (loss != null && profile != null && targets != null) WeightTrend.suggest(profile, targets, loss) else null,
        )
    }

    /**
     * Records a weighing (a second one for the same day replaces the first). The newest weighing is
     * the profile's weight, which the targets follow: a weighing for an earlier day leaves the profile alone.
     */
    fun setWeight(date: String, kg: Double): WeightLog {
        val day = parseDate(date)
        require(kg in 30.0..300.0) { "kg must be between 30 and 300" }
        db.writing {
            q.upsertWeight(userId, day.toString(), kg)
            if (isNewest(day)) followNewestWeighing()
        }
        return weights()
    }

    /**
     * Removes a weighing. Removing the newest one takes its effect back: the profile's weight becomes
     * that of the newest weighing left. Removing an older one changes nothing. When it was the only
     * one the profile keeps its weight: what it was before the first weighing is not stored.
     */
    fun deleteWeight(date: String): WeightLog {
        val day = parseDate(date)
        db.writing {
            val wasNewest = isNewest(day)
            q.deleteWeight(userId, day.toString())
            if (wasNewest) followNewestWeighing()
        }
        return weights()
    }

    /** The day of the newest weighing; null when there is none. */
    fun lastWeighed(): LocalDate? = q.selectWeights(userId).executeAsList().lastOrNull()?.let { LocalDate.parse(it.date) }

    /** Whether there is a weighing on [day] and none after it. */
    private fun isNewest(day: LocalDate): Boolean =
        q.selectWeights(userId).executeAsList().lastOrNull()?.date == day.toString()

    /** Makes the newest weighing the profile's weight, when there is one and a profile. */
    private fun followNewestWeighing() {
        val newest = q.selectWeights(userId).executeAsList().lastOrNull() ?: return
        profiles.profile()?.takeIf { it.weightKg != newest.kg }?.let { profiles.save(it.copy(weightKg = newest.kg)) }
    }

    private fun parseDate(date: String): LocalDate = try {
        LocalDate.parse(date)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("date must be an ISO date such as 2026-11-16")
    }

    companion object {
        /**
         * A logged food's line in English, its [LogEntry.description] for an app that does not know foods and shows that
         * alone: the amount in its unit, then the food ("2 bar Protein bar", "1 piece Banana", "150 g Rice"). The app that
         * knows foods words it itself, in its language.
         */
        fun describe(name: String, amount: Double, unit: String): String {
            val shown = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            val unitWord = if (unit == PIECE_UNIT && amount != 1.0) "pieces" else unit
            return "$shown $unitWord $name"
        }
    }
}
