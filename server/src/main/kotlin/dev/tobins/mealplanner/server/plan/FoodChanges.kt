package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.NotFoundException
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.mealAt
import java.time.LocalDate

/**
 * Putting foods in a meal's slot, changing them and taking them out (issue 358): an ingredient in an amount of its unit,
 * next to a recipe or as all the slot holds. The foods themselves are [SlotFoods]'; a meal whose share of the stock was
 * taken takes them from the stock too ([Eating.retakingStock]).
 */
class FoodChanges(
    private val store: PlanStore,
    private val weekMeals: WeekMeals,
    private val changes: MealChanges,
    private val eating: Eating,
    private val view: WeekView,
    private val foods: SlotFoods,
) {
    /**
     * Adds a food to [day]'s [slot] (issue 358): [input]'s ingredient in its amount and unit ([SlotFoods.lineOf]). An empty
     * slot becomes a slot of foods alone: one portion, locked as a meal chosen by hand, titled by its foods for the apps
     * that do not know them. A slot with a meal gets it next to what is there, a recipe or foods. When the meal's share of
     * the stock was taken already, the stock takes the food too ([Eating.retakingStock]). Refused where foods would not count
     * ([requireRoomForFoods]); another account's ingredient is not there (404).
     */
    fun addFood(weekStart: String, day: Int, slot: MealSlot, input: MealFoodInput): WeekPlan {
        val start = parseWeekStart(weekStart)
        val line = foods.lineOf(input)
        store.writing {
            val week = weekMeals.of(start)
            val meal = week.firstOrNull { it.day == day && it.slot == slot }
            if (meal == null) {
                val id = foods.create(line)
                val (kcal, protein) = foods.numbersOf(id)
                weekMeals.save(start, PlannedMeal(day, slot, id, foods.titleOf(id), 1.0, kcal, protein, locked = true, foodsOnly = true, foodsId = id))
                return@writing
            }
            requireRoomForFoods(meal)
            eating.retakingStock(start, meal, week) {
                val id = meal.foodsId
                if (id == null) store.setFoods(start, day, slot, foods.create(line)) else foods.add(id, line)
            }
            storeFoodsMeal(start, meal)
        }
        return view.of(start)
    }

    /**
     * Replaces food [foodId] of [day]'s [slot] with [input] (issue 358): another amount or unit, as [addFood] checks it; the
     * stock follows a meal whose share was taken ([Eating.retakingStock]). A food that is not in the slot is not there (404).
     */
    fun changeFood(weekStart: String, day: Int, slot: MealSlot, foodId: Long, input: MealFoodInput): WeekPlan {
        val start = parseWeekStart(weekStart)
        val line = foods.lineOf(input)
        store.writing {
            val week = weekMeals.of(start)
            val meal = mealAt(week, day, slot)
            val id = meal.foodsId ?: throw NotFoundException("No food with id $foodId in this meal")
            requireRoomForFoods(meal)
            eating.retakingStock(start, meal, week) { foods.change(id, foodId, line) }
            storeFoodsMeal(start, meal)
        }
        return view.of(start)
    }

    /**
     * Takes food [foodId] out of [day]'s [slot] (issue 358), and gives the stock back what it took for it. The last food of a
     * slot of foods alone takes the meal with it, as [MealChanges.remove] does, so not of one that was eaten (409: un-eat it first);
     * next to a recipe, the recipe stays alone. Nothing of the foods is left behind ([SlotFoods.dropUnused]).
     */
    fun removeFood(weekStart: String, day: Int, slot: MealSlot, foodId: Long): WeekPlan {
        val start = parseWeekStart(weekStart)
        store.writing {
            val week = weekMeals.of(start)
            val meal = mealAt(week, day, slot)
            val id = meal.foodsId ?: throw NotFoundException("No food with id $foodId in this meal")
            if (meal.foodsOnly && foods.idsOf(id).size == 1) {
                if (foods.idsOf(id).single() != foodId) throw NotFoundException("No food with id $foodId in this meal")
                changes.removeIn(start, day, slot)
                return@writing
            }
            eating.retakingStock(start, meal, week) {
                if (foods.remove(id, foodId) == 0) store.setFoods(start, day, slot, null)
            }
            storeFoodsMeal(start, meal)
            foods.dropUnused()
        }
        return view.of(start)
    }

    /**
     * Refuses foods for [meal] where they would not count as planned: a skipped meal counts for nothing (mark it planned or
     * eaten first), and a meal of a prepped batch is bought and taken from the stock as the batch, which knows no foods.
     */
    private fun requireRoomForFoods(meal: PlannedMeal) {
        if (meal.status == MealStatus.SKIPPED) throw ConflictException("This meal was skipped: mark it planned or eaten to change its foods")
        if (meal.prep != null) throw ConflictException("This meal is of a prepped batch: add the food to another meal")
    }

    /** Stores the title and numbers of [meal]'s foods when they are all it is, for the apps that do not know foods. */
    private fun storeFoodsMeal(start: LocalDate, meal: PlannedMeal) {
        val id = meal.foodsId?.takeIf { meal.foodsOnly } ?: return
        val (kcal, protein) = foods.numbersOf(id)
        store.setFoodsMeal(start, meal.day, meal.slot, foods.titleOf(id), kcal, protein)
    }
}
