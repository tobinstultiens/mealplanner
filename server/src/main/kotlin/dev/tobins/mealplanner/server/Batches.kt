package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepMeal
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.server.db.Database
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * A meal of a prepped batch as stored: the meal in its [week], and its [share] of the batch, which is the
 * meal's portions until it is skipped and its share is eaten in another meal (then 0).
 */
class BatchMeal(val week: LocalDate, val meal: PlannedMeal, val share: Double) {
    val date: LocalDate get() = week.plusDays(meal.day.toLong())

    val ref: MealRef get() = MealRef(week, meal.day, meal.slot)

    fun isAt(date: LocalDate, slot: MealSlot): Boolean = this.date == date && meal.slot == slot
}

/**
 * A prepped batch (issue 234, the tables `prep` and `prepPortion`): one recipe cooked once, on [madeOn], and
 * eaten in [meals] until [lastDay], in eating order and in whichever plan weeks they are. The batch is the
 * sum of the meals' shares: a skipped meal's share stays in it, as it is in the box.
 */
class Batch(
    val id: Long,
    val recipeId: Long,
    val madeOn: LocalDate,
    val lastDay: LocalDate,
    val vacuum: Boolean,
    val meals: List<BatchMeal>,
) {
    /** The meal that makes the batch: its first, on [madeOn]. It stays while the batch does ([PlanRepository]'s rules). */
    val cook: BatchMeal? get() = meals.firstOrNull()?.takeIf { it.date == madeOn }

    /** Made, and so bought and taken from the stock: one of its meals was eaten. */
    val made: Boolean get() = meals.any { it.meal.status == MealStatus.EATEN }

    /** The servings cooked on [madeOn]. */
    val portions: Double get() = meals.sumOf { it.share }

    /** The week whose shopping list buys the batch and whose stock it is cooked from: that of [madeOn]. */
    val week: LocalDate get() = mondayOf(madeOn)

    /**
     * What becomes of the batch as one meal, for the shopping list and the stock: eaten once made, skipped
     * when every meal of it is, so it is not cooked at all, and else still to cook.
     */
    val status: MealStatus
        get() = when {
            made -> MealStatus.EATEN
            meals.all { it.meal.status == MealStatus.SKIPPED } -> MealStatus.SKIPPED
            else -> MealStatus.PLANNED
        }

    fun isCook(week: LocalDate, meal: PlannedMeal): Boolean = cook?.let { it.week == week && it.meal.day == meal.day && it.meal.slot == meal.slot } == true

    fun mealAt(date: LocalDate, slot: MealSlot): BatchMeal? = meals.firstOrNull { it.isAt(date, slot) }

    fun toApi() = MealPrep(
        id = id,
        madeOn = madeOn.toString(),
        lastDay = lastDay.toString(),
        vacuum = vacuum,
        meals = meals.map { PrepMeal(it.date.toString(), it.meal.slot, it.share, it.meal.status) },
    )

    /** The meals after the first, as a sentence names them: `Tuesday lunch, Wednesday dinner`. */
    fun laterMealsText(): String = meals.drop(1).joinToString(", ") { slotName(it.date, it.meal.slot) }

    companion object {
        /** Reads the batches [ids] of [userId] with every meal of each, in every week; a batch of another account is not there. */
        fun load(db: Database, userId: Long, ids: Collection<Long>): Map<Long, Batch> {
            if (ids.isEmpty()) return emptyMap()
            val meals = db.planQueries.selectPrepMeals(userId, ids.distinct()).executeAsList().groupBy { it.prepId }
            return ids.distinct().mapNotNull { id -> db.planQueries.selectPrep(userId, id).executeAsOneOrNull() }.associate { row ->
                val ofBatch = meals[row.id].orEmpty().map { m ->
                    val meal = PlannedMeal(
                        day = m.day.toInt(), slot = MealSlot.valueOf(m.slot), recipeId = m.recipeId, title = m.title, portions = m.portions,
                        kcal = m.kcal, proteinG = m.proteinG, locked = true, status = MealStatus.valueOf(m.status),
                    )
                    BatchMeal(LocalDate.parse(m.weekStart), meal, m.share ?: m.portions)
                }.sortedWith(compareBy({ it.date }, { it.meal.slot.ordinal }))
                row.id to Batch(row.id, row.recipeId, LocalDate.parse(row.madeOn), LocalDate.parse(row.lastDay), row.vacuum != 0L, ofBatch)
            }
        }

        /** `Friday 20 November`: a day as a refusal names it. */
        fun dayName(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH))

        /** `Tuesday lunch`: a meal as a refusal names it. */
        fun slotName(date: LocalDate, slot: MealSlot): String =
            date.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)) + " " + slot.name.lowercase()

        fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        /** [slot] on [date] as the plan stores it: the plan week and the day in it. */
        fun placeOf(slot: PrepSlot): Triple<LocalDate, Int, MealSlot> {
            val date = parseDate(slot.date)
            val week = mondayOf(date)
            return Triple(week, (date.toEpochDay() - week.toEpochDay()).toInt(), slot.slot)
        }

        fun parseDate(text: String): LocalDate = try {
            LocalDate.parse(text)
        } catch (e: java.time.format.DateTimeParseException) {
            throw IllegalArgumentException("dates must be ISO dates such as 2026-11-16")
        }
    }
}
