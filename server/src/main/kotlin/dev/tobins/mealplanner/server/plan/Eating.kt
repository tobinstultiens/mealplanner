package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.ingredients.MealRef
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.dinnerOf
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.leftoversOf
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.mealAt
import dev.tobins.mealplanner.server.recipes.RecipeCandidates
import dev.tobins.mealplanner.server.shopping.gramsUsed
import java.time.LocalDate
import kotlin.math.abs

/**
 * Cooking and eating a meal: marking it eaten, skipped or planned again ([setStatus]), saying what was in it
 * ([adjust]), and what either takes from the stock or gives back to it. A prepped batch is made, and taken from the
 * stock, with the first of its meals eaten.
 */
class Eating(
    private val store: PlanStore,
    private val weekMeals: WeekMeals,
    private val view: WeekView,
    private val shortfalls: WeekShortfalls,
    private val adjustments: MealAdjustments,
    private val foods: SlotFoods,
    private val recipes: RecipeCandidates,
    private val stock: StockRepository,
) {
    /**
     * Marks a meal eaten, skipped or planned again. Eating it is eating from what was bought ahead or
     * stocked up for it ([eatFromStock]); marking it skipped or planned again puts that back
     * ([giveBackToStock]), and marking it eaten when it already is takes nothing more. Its side is eaten,
     * skipped or planned with it, which may make its batch or un-make it ([settle]).
     */
    fun setStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus): WeekPlan {
        val start = parseWeekStart(weekStart)
        markStatus(start, day, slot, status)
        return view.of(start)
    }

    /** [setStatus] without answering the week, for a caller that writes more in the same transaction. */
    internal fun markStatus(start: LocalDate, day: Int, slot: MealSlot, status: MealStatus) {
        store.writing {
            val week = weekMeals.of(start)
            val meal = week.firstOrNull { it.day == day && it.slot == slot }
            val batch = meal?.let { weekMeals.batchesOf(listOf(it)).values.singleOrNull() }
            val sideBatch = meal?.side?.let { store.batches(listOf(it.prep.id)).values.singleOrNull() }
            if (batch != null) {
                setBatchStatus(batch, batch.mealAt(start.plusDays(day.toLong()), slot)!!, status)
            } else {
                store.setStatus(start, day, slot, status)
                if (meal != null && (meal.status == MealStatus.EATEN) != (status == MealStatus.EATEN)) {
                    if (status == MealStatus.EATEN) eatFromStock(start, meal, week) else giveBackToStock(start, meal, week)
                }
            }
            sideBatch?.let(::settle)
        }
    }

    /**
     * Marks [meal] of [batch] eaten, skipped or planned again. A meal whose share went to another meal
     * ([BatchMeal.share] 0) can only be skipped: there is nothing left to eat.
     */
    private fun setBatchStatus(batch: Batch, meal: BatchMeal, status: MealStatus) {
        if (meal.share == 0.0 && status != MealStatus.SKIPPED) {
            throw ConflictException("Its share of the prepped batch is eaten in another meal already")
        }
        store.setStatus(meal.week, meal.meal.day, meal.meal.slot, status)
        settle(batch)
    }

    /**
     * After one of [before]'s meals was marked: the batch is made once, so the first of its meals eaten, whichever,
     * takes every meal's and side's share from the stock, each kept under its own meal and from the stock of the week
     * the batch is made in ([cookBatch]); un-eating the last eaten one gives all of it back.
     */
    private fun settle(before: Batch) {
        val after = store.batches(listOf(before.id)).getValue(before.id)
        when {
            !before.made && after.made -> cookBatch(after)
            before.made && !after.made -> after.meals.forEach { stock.giveBack(it.ref) }
        }
    }

    /**
     * Takes each meal's share of [batch] from the stock under the meal, from what was at home in the week it is made: a
     * meal's as adjusted, a side's as its recipe has it, as a side can't be adjusted.
     */
    private fun cookBatch(batch: Batch) {
        val recipe = recipes.linesWithIngredients(listOf(batch.recipeId))[batch.recipeId] ?: return
        for (meal in batch.meals.filter { it.share > 0.0 }) {
            // What was in a share in the box is the recipe's: its slot's own lines are the standard meal's.
            val grams = if (meal.side || meal.boxed) gramsUsed(recipe, meal.share) else adjustments.gramsEaten(meal.week, meal.meal.copy(portions = meal.share), recipe)
            stock.useUp(meal.ref, grams, cookedIn = batch.week)
        }
    }

    /**
     * Takes from the stock what eating [meal] used ([StockRepository.useUp]), each share kept
     * under the meal whose portions it is for. A dinner is cooked with its leftovers, so it takes the
     * next day's leftover lunch's share too, unless that lunch was eaten already and took its own. A
     * leftover lunch takes its own share only when its dinner was not eaten: then it was cooked for
     * the lunch. Whichever is eaten first, the two take the same in the end.
     */
    private fun eatFromStock(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>) {
        if (dinnerOf(meal, week)?.status != MealStatus.EATEN) take(start, meal)
        leftoversOf(meal, week)?.takeIf { it.status != MealStatus.EATEN }?.let { take(start, it) }
    }

    /**
     * The inverse of [eatFromStock]: puts back what was taken for [meal]'s portions, unless it is a
     * leftover lunch whose dinner is still eaten (cooked, so used whether eaten or not), and for its
     * leftover lunch's, unless that lunch is eaten.
     */
    private fun giveBackToStock(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>) {
        if (dinnerOf(meal, week)?.status != MealStatus.EATEN) stock.giveBack(MealRef(start, meal.day, meal.slot))
        leftoversOf(meal, week)?.takeIf { it.status != MealStatus.EATEN }?.let { stock.giveBack(MealRef(start, it.day, it.slot)) }
    }

    /** Takes what eating [meal] uses from the stock ([eatenGrams]), kept under [meal]. */
    private fun take(start: LocalDate, meal: PlannedMeal) {
        val grams = eatenGrams(start, meal) ?: return
        stock.useUp(MealRef(start, meal.day, meal.slot), grams)
    }

    /**
     * Grams of each ingredient that eating [meal] uses: its portions of its recipe as it was adjusted
     * ([MealAdjustments.gramsEaten]) and the foods next to it ([SlotFoods.gramsNextTo]); null when its recipe is gone and it
     * has no foods, which takes nothing.
     */
    private fun eatenGrams(start: LocalDate, meal: PlannedMeal): Map<Long, Double>? {
        val recipe = recipes.linesWithIngredients(listOf(meal.recipeId))[meal.recipeId]
        val next = foods.gramsNextTo(meal)
        if (recipe == null && next.isEmpty()) return null
        val own = recipe?.let { adjustments.gramsEaten(start, meal, it) }.orEmpty()
        return (own.keys + next.keys).associateWith { (own[it] ?: 0.0) + (next[it] ?: 0.0) }
    }

    /**
     * The meal in [day]'s [slot] with what was in it ([MealAdjustments.lines]) and the later meals it leaves
     * short ([WeekShortfalls.warnings]); a [dev.tobins.mealplanner.server.NotFoundException] for an empty slot.
     */
    fun lines(weekStart: String, day: Int, slot: MealSlot): MealLines {
        val start = parseWeekStart(weekStart)
        val week = weekMeals.of(start)
        val meal = mealAt(week, day, slot)
        return adjustments.lines(start, meal).copy(shortfalls = shortfalls.warnings(start, meal, week))
    }

    /**
     * Says what was in a meal (issue 226): [changes] replace those it had ([MealAdjustments.replace]); none
     * puts it back to its recipe. Any meal that is not skipped can be adjusted, eaten or still to eat, as
     * toppings are often decided while cooking; a skipped one counts for nothing, so that is a
     * [ConflictException]. When the meal's share of the stock was taken already ([WeekMeals.shareTaken]), the stock
     * moves by the difference ([StockRepository.retake]), and un-eating it later gives back what it holds
     * then. A leftover lunch is adjusted on its own: its dinner keeps its lines. A slot of foods alone (issue 358) has
     * no recipe to adjust, a [ConflictException]: its foods are changed themselves ([FoodChanges.changeFood]).
     */
    fun adjust(weekStart: String, day: Int, slot: MealSlot, changes: List<MealLineChange>): MealLines {
        val start = parseWeekStart(weekStart)
        return store.writing {
            val week = weekMeals.of(start)
            val meal = mealAt(week, day, slot)
            if (meal.status == MealStatus.SKIPPED) throw ConflictException("This meal was skipped: mark it eaten or planned to say what was in it")
            if (meal.foodsOnly) throw ConflictException("This meal is foods, not a recipe: change the foods themselves")
            retakingStock(start, meal, week) { adjustments.replace(start, meal, changes) }
            adjustments.lines(start, meal).copy(shortfalls = shortfalls.warnings(start, meal, week))
        }
    }

    /**
     * Runs [change] of what [meal] (of [week], starting [start]) eats. When its share of the stock was taken already
     * ([WeekMeals.shareTaken]), the stock moves by the difference ([StockRepository.retake]), and un-eating it later gives back what
     * it holds then. The meal is read again after [change], which may have given it foods.
     */
    internal fun retakingStock(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>, change: () -> Unit) {
        val before = if (weekMeals.shareTaken(start, meal, week)) eatenGrams(start, meal) else null
        change()
        if (before == null) return
        val after = weekMeals.of(start).firstOrNull { it.day == meal.day && it.slot == meal.slot }?.let { eatenGrams(start, it) }.orEmpty()
        val moved = (before.keys + after.keys).filter { abs((before[it] ?: 0.0) - (after[it] ?: 0.0)) > 1e-9 }
        stock.retake(MealRef(start, meal.day, meal.slot), moved.associateWith { after[it] ?: 0.0 })
    }
}
