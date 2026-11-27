package dev.tobins.mealplanner.app.plan

import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SpareMove
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.StandardChosen
import dev.tobins.mealplanner.api.StandardMeals
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.recipes.recipes
import dev.tobins.mealplanner.app.setMealStatus

/**
 * What the plan screen asks of the server, so that [PlanState] is tested with a server that answers what a test says.
 * Like the [ApiClient] calls they stand for, they throw when the call fails; [PlanState] turns that into what the screen says.
 */
internal interface PlanServer {
    suspend fun plan(weekStart: String): WeekPlan

    suspend fun spareShares(date: String): List<SparePortion>

    /** The week's standard meals; null from a server from before them. */
    suspend fun standardMeals(weekStart: String): StandardMeals?

    suspend fun myRecipes(): List<RecipeSummary>

    suspend fun rerollMeal(weekStart: String, day: Int, slot: MealSlot): WeekPlan

    suspend fun moveDinner(weekStart: String, day: Int, toDay: Int): WeekPlan

    suspend fun setMealLocked(weekStart: String, day: Int, slot: MealSlot, locked: Boolean): WeekPlan

    /** Marks the meal; the week as it is after that is asked for separately ([plan]). */
    suspend fun setMealStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus)

    suspend fun removeMeal(weekStart: String, day: Int, slot: MealSlot): WeekPlan

    suspend fun removeSide(weekStart: String, day: Int, slot: MealSlot): WeekPlan

    suspend fun removePrep(weekStart: String, id: Long): WeekPlan

    suspend fun createPrep(weekStart: String, input: PrepInput): WeekPlan

    suspend fun addSide(weekStart: String, id: Long, side: PrepSlot): WeekPlan

    suspend fun eatSpare(weekStart: String, id: Long, move: SpareMove): WeekPlan

    suspend fun chooseStandard(weekStart: String, day: Int, slot: MealSlot, recipeId: Long, eaten: Boolean): StandardChosen

    /** Null from a server from before the route. */
    suspend fun chooseRecipe(weekStart: String, day: Int, slot: MealSlot, recipeId: Long): WeekPlan?

    suspend fun generateIntoPlan(weekStart: String, day: Int, slot: MealSlot, request: GenerateRequest): WeekPlan
}

/** The plan screen's server: the calls of `PlanCalls.kt` on this client. */
internal fun ApiClient.planServer(): PlanServer {
    val api = this
    return object : PlanServer {
        override suspend fun plan(weekStart: String) = api.plan(weekStart)
        override suspend fun spareShares(date: String) = api.spareShares(date)
        override suspend fun standardMeals(weekStart: String) = api.standardMeals(weekStart)
        override suspend fun myRecipes() = api.recipes()
        override suspend fun rerollMeal(weekStart: String, day: Int, slot: MealSlot) = api.rerollMeal(weekStart, day, slot)
        override suspend fun moveDinner(weekStart: String, day: Int, toDay: Int) = api.moveDinner(weekStart, day, toDay)
        override suspend fun setMealLocked(weekStart: String, day: Int, slot: MealSlot, locked: Boolean) = api.setMealLocked(weekStart, day, slot, locked)
        override suspend fun setMealStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus) = api.setMealStatus(weekStart, day, slot, status)
        override suspend fun removeMeal(weekStart: String, day: Int, slot: MealSlot) = api.removeMeal(weekStart, day, slot)
        override suspend fun removeSide(weekStart: String, day: Int, slot: MealSlot) = api.removeSide(weekStart, day, slot)
        override suspend fun removePrep(weekStart: String, id: Long) = api.removePrep(weekStart, id)
        override suspend fun createPrep(weekStart: String, input: PrepInput) = api.createPrep(weekStart, input)
        override suspend fun addSide(weekStart: String, id: Long, side: PrepSlot) = api.addSide(weekStart, id, side)
        override suspend fun eatSpare(weekStart: String, id: Long, move: SpareMove) = api.eatSpare(weekStart, id, move)
        override suspend fun chooseStandard(weekStart: String, day: Int, slot: MealSlot, recipeId: Long, eaten: Boolean) =
            api.chooseStandard(weekStart, day, slot, recipeId, eaten)
        override suspend fun chooseRecipe(weekStart: String, day: Int, slot: MealSlot, recipeId: Long) = api.chooseRecipe(weekStart, day, slot, recipeId)
        override suspend fun generateIntoPlan(weekStart: String, day: Int, slot: MealSlot, request: GenerateRequest) =
            api.generateIntoPlan(weekStart, day, slot, request)
    }
}
