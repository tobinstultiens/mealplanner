package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.Prep
import dev.tobins.mealplanner.server.db.SelectBoxPortion
import dev.tobins.mealplanner.server.db.SelectPicks
import dev.tobins.mealplanner.server.db.SelectSidePortion
import dev.tobins.mealplanner.server.ingredients.MealRef
import dev.tobins.mealplanner.server.writing
import java.time.LocalDate

/**
 * What the plan's rules read and write in the database for one account (the tables of `Plan.sq`): rows in, values
 * out, and no rule of what may change. It is built for the account [userId], so a caller never names one, and every
 * query it runs is of that account only. The rules ([WeekMeals], [MealChanges], [Eating], ...) call it and no query.
 */
class PlanStore(private val db: Database, private val userId: Long) {
    private val q = db.planQueries

    /**
     * Runs [body] in a transaction that holds the write lock ([writing]); inside another one on this thread it joins
     * that one, so a rule that calls another rule's change still makes one transaction of the two.
     */
    fun <T> writing(body: () -> T): T = db.writing(body)

    /**
     * The meals stored for the week starting [start], by day, as their rows have them: without their batches or sides,
     * which [prepIdsIn] and [sideIdsIn] give. A slot of foods alone is a row whose foods are its recipe.
     */
    fun mealsIn(start: LocalDate): List<PlannedMeal> = q.selectWeek(userId, start.toString()).executeAsList().map {
        PlannedMeal(
            day = it.day.toInt(),
            slot = MealSlot.valueOf(it.slot),
            recipeId = it.recipeId,
            title = it.title,
            portions = it.portions,
            kcal = it.kcal,
            proteinG = it.proteinG,
            leftover = it.leftover != 0L,
            locked = it.locked != 0L,
            status = MealStatus.valueOf(it.status),
            foodsOnly = it.foodsId != null && it.foodsId == it.recipeId,
            foodsId = it.foodsId,
        )
    }

    /** The batch each meal of the week starting [start] is eaten from, by day and slot. */
    fun prepIdsIn(start: LocalDate): Map<Pair<Int, MealSlot>, Long> =
        q.selectWeekPrepIds(userId, start.toString()).executeAsList().associate { (it.day.toInt() to MealSlot.valueOf(it.slot)) to it.prepId }

    /** The batch the side next to each meal of the week starting [start] is taken from, by day and slot. */
    fun sideIdsIn(start: LocalDate): Map<Pair<Int, MealSlot>, Long> =
        q.selectWeekSides(userId, start.toString()).executeAsList().associate { (it.day.toInt() to MealSlot.valueOf(it.slot)) to it.prepId }

    /** The batch of each share in the box under a slot of the week starting [start], by day and slot. */
    fun boxesIn(start: LocalDate): Map<Pair<Int, MealSlot>, Long> =
        q.selectWeekBoxes(userId, start.toString()).executeAsList().associate { (it.day.toInt() to MealSlot.valueOf(it.slot)) to it.prepId }

    /** The share in the box under [day]'s [slot] of the week starting [start]; null when it holds none. */
    fun box(start: LocalDate, day: Int, slot: MealSlot): SelectBoxPortion? =
        q.selectBoxPortion(userId, start.toString(), day.toLong(), slot.name).executeAsOneOrNull()

    /** The side next to the meal in [day]'s [slot] of the week starting [start]; null when it has none. */
    fun side(start: LocalDate, day: Int, slot: MealSlot): SelectSidePortion? =
        q.selectSidePortion(userId, start.toString(), day.toLong(), slot.name).executeAsOneOrNull()

    /** The foods of [day]'s [slot] of the week starting [start]; null without foods, or without a meal there. */
    fun foodsIdIn(start: LocalDate, day: Int, slot: MealSlot): Long? =
        q.selectMealFoods(userId, start.toString(), day.toLong(), slot.name).executeAsOneOrNull()?.foodsId

    /** Whether the stock gave something for the meal in [day]'s [slot] of the week starting [start], kept under it. */
    fun holdsStock(start: LocalDate, day: Int, slot: MealSlot): Boolean =
        db.pantryQueries.selectStockUses(userId, start.toString(), day.toLong(), slot.name, MealRef.MEAL).executeAsList().isNotEmpty()

    /** The batches [ids] with every meal of each, in every week ([Batch.load]); one of another account is not there. */
    fun batches(ids: Collection<Long>): Map<Long, Batch> = Batch.load(db, userId, ids)

    /** The batches still keeping on [start] or later, in the order they are made. */
    fun prepsKeeping(start: LocalDate): List<Prep> = q.selectPrepsKeeping(userId, start.toString()).executeAsList()

    /** The dinners chosen and the recipes hidden for the week starting [start] on the planning screen, in the order they were made. */
    fun picks(start: LocalDate): List<SelectPicks> = q.selectPicks(userId, start.toString()).executeAsList()

    /** How many dinners are chosen for the week starting [start] on the planning screen. */
    fun countPicks(start: LocalDate): Int = q.countPicks(userId, start.toString()).executeAsOne().toInt()

    /**
     * Writes [meal] in its slot of the week starting [start], in place of the row there, without foods. The rows that
     * belong to the slot's old meal (its share of a batch, its side, a share in the box, what the stock gave it, what was
     * in it) go with that row.
     */
    fun upsertMeal(start: LocalDate, meal: PlannedMeal) {
        q.upsertMeal(
            userId, start.toString(), meal.day.toLong(), meal.slot.name, meal.recipeId, meal.title, meal.portions,
            meal.kcal, meal.proteinG, if (meal.leftover) 1 else 0, if (meal.locked) 1 else 0, meal.status.name,
        )
    }

    /** Takes the meal out of [day]'s [slot] of the week starting [start], with every row that belongs to it. */
    fun deleteMeal(start: LocalDate, day: Int, slot: MealSlot) = q.deleteMeal(userId, start.toString(), day.toLong(), slot.name)

    fun setFoods(start: LocalDate, day: Int, slot: MealSlot, foodsId: Long?) = q.setMealFoods(foodsId, userId, start.toString(), day.toLong(), slot.name)

    /** The title and numbers of a slot of foods alone; nothing for a slot with a recipe. */
    fun setFoodsMeal(start: LocalDate, day: Int, slot: MealSlot, title: String, kcal: Double, proteinG: Double) =
        q.setFoodsMeal(title, kcal, proteinG, userId, start.toString(), day.toLong(), slot.name)

    fun setLocked(start: LocalDate, day: Int, slot: MealSlot, locked: Boolean) =
        q.setLocked(if (locked) 1 else 0, userId, start.toString(), day.toLong(), slot.name)

    fun setStatus(start: LocalDate, day: Int, slot: MealSlot, status: MealStatus) =
        q.setStatus(status.name, userId, start.toString(), day.toLong(), slot.name)

    /** What the meal in [day]'s [slot] ate of ingredient [ingredientId] that differs from its recipe. */
    fun insertMealLine(start: LocalDate, day: Int, slot: MealSlot, ingredientId: Long, grams: Double) =
        q.insertMealLine(userId, start.toString(), day.toLong(), slot.name, ingredientId, grams)

    /** The meal in [day]'s [slot] is eaten from batch [prepId], its own portions. */
    fun insertPrepPortion(start: LocalDate, day: Int, slot: MealSlot, prepId: Long) =
        q.insertPrepPortion(userId, start.toString(), day.toLong(), slot.name, prepId)

    fun insertSidePortion(start: LocalDate, day: Int, slot: MealSlot, prepId: Long, portions: Double?) =
        q.insertSidePortion(userId, start.toString(), day.toLong(), slot.name, prepId, portions)

    fun insertBoxPortion(start: LocalDate, day: Int, slot: MealSlot, prepId: Long, portions: Double?) =
        q.insertBoxPortion(userId, start.toString(), day.toLong(), slot.name, prepId, portions)

    /** Takes the meal in [day]'s [slot] out of its batch: the row of its own share. */
    fun deleteMealPortion(start: LocalDate, day: Int, slot: MealSlot) = q.deleteMealPortion(userId, start.toString(), day.toLong(), slot.name)

    /** Moves what the stock gave and kept under [from] to [to], as [to]'s own. */
    fun moveStockUses(from: MealRef, to: MealRef) = q.moveStockUses(
        to.week.toString(), to.day.toLong(), to.slot.name, to.role, userId, from.week.toString(), from.day.toLong(), from.slot.name, from.role,
    )
}
