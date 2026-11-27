package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepMeal
import dev.tobins.mealplanner.api.PrepPastLastDay
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.MealRef
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * A meal of a prepped batch as stored: the meal in its [week], and its [share] of the batch, which is the
 * meal's portions until it is skipped and its share is eaten in another meal (then 0). A [side] is the side
 * next to [meal] (issue 234, step 2): [meal] is the meal whose main it goes with, whose status it shares, and
 * [share] its own portions. A [boxed] one is a skipped meal whose slot a standard meal took (issue 265): [meal]
 * is the skipped meal as the batch has it, of the batch's recipe, with no numbers of its own, and [share] is in
 * the box.
 */
class BatchMeal(val week: LocalDate, val meal: PlannedMeal, val share: Double, val side: Boolean = false, val boxed: Boolean = false) {
    val date: LocalDate get() = week.plusDays(meal.day.toLong())

    val ref: MealRef get() = MealRef(week, meal.day, meal.slot, side, boxed)

    fun isAt(date: LocalDate, slot: MealSlot): Boolean = this.date == date && meal.slot == slot

    /** Whether this is [meal]'s own share in [week] ([side] false) or that of the side next to it ([side] true). */
    fun isOf(week: LocalDate, meal: PlannedMeal, side: Boolean): Boolean =
        this.week == week && this.meal.day == meal.day && this.meal.slot == meal.slot && this.side == side && !boxed
}

/**
 * A prepped batch (issue 234, the tables `prep` and `prepPortion`): one recipe cooked once, on [madeOn], and
 * eaten in [meals] until [lastDay], in eating order and in whichever plan weeks they are. The batch is the
 * sum of the meals' shares: a skipped meal's share stays in it, as it is in the box. Its meals are the slots'
 * meals or sides next to them (step 2), or both; [title] is its recipe's.
 */
class Batch(
    val id: Long,
    val recipeId: Long,
    val title: String,
    val madeOn: LocalDate,
    val lastDay: LocalDate,
    val vacuum: Boolean,
    val meals: List<BatchMeal>,
) {
    /**
     * The meal that makes the batch: its first, on [madeOn], a slot's meal or a side next to one. It stays while the
     * batch does ([KeptMeals]' rules).
     */
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

    /** Whether [meal] of [week] is the meal that makes the batch: its own share ([side] false) or its side's. */
    fun isCook(week: LocalDate, meal: PlannedMeal, side: Boolean = false): Boolean = cook?.isOf(week, meal, side) == true

    /**
     * The batch's meal, side or share in the box at [date]'s [slot]: a batch is never two of them in one slot (the meal of
     * a slot and the side next to it, or a share in the box under a slot and that slot's meal or side).
     */
    fun mealAt(date: LocalDate, slot: MealSlot): BatchMeal? = meals.firstOrNull { it.isAt(date, slot) }

    /** The side of the batch next to [meal] of [week]; null when its side is of another batch or it has none. */
    fun sideOf(week: LocalDate, meal: PlannedMeal): BatchMeal? = meals.firstOrNull { it.isOf(week, meal, side = true) }

    /** The servings cooked when [meal] of [week] is made, for its own share or its [side]'s: the batch where it is made, else 0. */
    fun cookPortions(week: LocalDate, meal: PlannedMeal, side: Boolean): Double =
        if (isCook(week, meal, side) && status != MealStatus.SKIPPED) portions else 0.0

    fun toApi() = MealPrep(
        id = id,
        madeOn = madeOn.toString(),
        lastDay = lastDay.toString(),
        vacuum = vacuum,
        meals = meals.map { PrepMeal(it.date.toString(), it.meal.slot, it.share, it.meal.status, side = it.side) },
    )

    /**
     * What may still be in a box once the batch's last day is over (issue 264): the shares of skipped meals not eaten
     * elsewhere (a moved share is 0 where it was skipped, and its meal's where it went), and the meals still planned,
     * which may have been eaten without being marked. Null when nothing may be: every share eaten, or the batch never
     * made, as nothing of a batch that was never cooked is in a box. Meals and sides are counted together and the sides
     * also apart, as one batch may be both (issue 269).
     */
    fun pastLastDay(): PrepPastLastDay? {
        if (!made) return null
        val inBox = meals.filter { it.share > 0.0 }
        val left = inBox.filter { it.meal.status == MealStatus.SKIPPED }
        val unmarked = inBox.filter { it.meal.status == MealStatus.PLANNED }
        if (left.isEmpty() && unmarked.isEmpty()) return null
        return PrepPastLastDay(
            prepId = id,
            recipeId = recipeId,
            title = title,
            madeOn = madeOn.toString(),
            lastDay = lastDay.toString(),
            side = meals.all { it.side },
            left = left.size,
            unmarked = unmarked.size,
            firstUnmarked = unmarked.firstOrNull()?.date?.toString(),
            leftSides = left.count { it.side },
            unmarkedSides = unmarked.count { it.side },
            firstLeft = left.firstOrNull()?.date?.toString(),
        )
    }

    /** The meals after the first, as a sentence names them: `Tuesday lunch, Wednesday dinner (a side)`. */
    fun laterMealsText(): String = meals.drop(1).joinToString(", ") { slotName(it.date, it.meal.slot) + if (it.side) " (a side)" else "" }

    companion object {
        /** Reads the batches [ids] of [userId] with every meal of each, in every week; a batch of another account is not there. */
        fun load(db: Database, userId: Long, ids: Collection<Long>): Map<Long, Batch> {
            if (ids.isEmpty()) return emptyMap()
            val meals = db.planQueries.selectPrepMeals(userId, ids.distinct()).executeAsList().groupBy { it.prepId }
            return ids.distinct().mapNotNull { id -> db.planQueries.selectPrep(userId, id).executeAsOneOrNull() }.associate { row ->
                val rows = meals[row.id].orEmpty()
                // The recipe's title as stored, else that of a meal of it, which the plan keeps when the recipe goes.
                val title = row.recipeTitle ?: rows.firstOrNull { it.role == MealRef.MEAL }?.title.orEmpty()
                val ofBatch = rows.map { m ->
                    val meal = PlannedMeal(
                        day = m.day.toInt(), slot = MealSlot.valueOf(m.slot), recipeId = m.recipeId, title = m.title, portions = m.portions,
                        kcal = m.kcal, proteinG = m.proteinG, locked = m.locked != 0L, status = MealStatus.valueOf(m.status),
                    )
                    val week = LocalDate.parse(m.weekStart)
                    when (m.role) {
                        MealRef.SIDE -> BatchMeal(week, meal, m.share ?: m.portions, side = true)
                        // The slot's meal is the standard meal that took its place: the skipped meal is the batch's own.
                        MealRef.BOX -> {
                            val skipped = PlannedMeal(meal.day, meal.slot, row.recipeId, title, m.share ?: 0.0, 0.0, 0.0, locked = true, status = MealStatus.SKIPPED)
                            BatchMeal(week, skipped, m.share ?: 0.0, boxed = true)
                        }
                        else -> BatchMeal(week, meal.copy(locked = true), m.share ?: m.portions)
                    }
                }.sortedWith(compareBy({ it.date }, { it.meal.slot.ordinal }))
                row.id to Batch(row.id, row.recipeId, title, LocalDate.parse(row.madeOn), LocalDate.parse(row.lastDay), row.vacuum != 0L, ofBatch)
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
