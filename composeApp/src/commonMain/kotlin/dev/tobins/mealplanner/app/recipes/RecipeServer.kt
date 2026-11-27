package dev.tobins.mealplanner.app.recipes

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.StandardSwitch
import dev.tobins.mealplanner.api.ToppingInput
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.setMealStatus

/**
 * What the recipe page asks of the server, so that [RecipeState] is tested with a server that answers what a test says.
 * Like the [ApiClient] calls they stand for, they throw when the call fails; [RecipeState] turns that into what the page shows.
 */
internal interface RecipeServer {
    suspend fun recipe(id: Long): RecipeDetail

    suspend fun routines(): Routines

    /** Takes the recipe into my recipes or puts it back in the bank; [inRotation] says whether the planner may pick it once taken. */
    suspend fun setRecipeAdded(id: Long, added: Boolean, inRotation: Boolean): RecipeDetail

    /** Marks the planned meal the recipe was opened from. */
    suspend fun setMealStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus)

    suspend fun deleteRecipe(id: Long)

    suspend fun rateRecipe(id: Long, rating: Int?): RecipeDetail

    suspend fun setRecipeRotation(id: Long, inRotation: Boolean): RecipeDetail

    suspend fun setStandardMeal(id: Long, standard: Boolean): StandardSwitch

    suspend fun updateRecipeSettings(id: Long, settings: RecipeSettings): RecipeDetail

    suspend fun setRecipeKeeps(id: Long, keeps: RecipeKeeps): RecipeDetail

    suspend fun setRecipeToppings(id: Long, toppings: List<ToppingInput>): RecipeDetail

    suspend fun saveRoutines(routines: Routines): Routines
}

/** The recipe page's server: the calls of `RecipesCalls.kt` on this client. */
internal fun ApiClient.recipeServer(): RecipeServer {
    val api = this
    return object : RecipeServer {
        override suspend fun recipe(id: Long) = api.recipe(id)
        override suspend fun routines() = api.routines()
        override suspend fun setRecipeAdded(id: Long, added: Boolean, inRotation: Boolean) = api.setRecipeAdded(id, added, inRotation)
        override suspend fun setMealStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus) = api.setMealStatus(weekStart, day, slot, status)
        override suspend fun deleteRecipe(id: Long) = api.deleteRecipe(id)
        override suspend fun rateRecipe(id: Long, rating: Int?) = api.rateRecipe(id, rating)
        override suspend fun setRecipeRotation(id: Long, inRotation: Boolean) = api.setRecipeRotation(id, inRotation)
        override suspend fun setStandardMeal(id: Long, standard: Boolean) = api.setStandardMeal(id, standard)
        override suspend fun updateRecipeSettings(id: Long, settings: RecipeSettings) = api.updateRecipeSettings(id, settings)
        override suspend fun setRecipeKeeps(id: Long, keeps: RecipeKeeps) = api.setRecipeKeeps(id, keeps)
        override suspend fun setRecipeToppings(id: Long, toppings: List<ToppingInput>) = api.setRecipeToppings(id, toppings)
        override suspend fun saveRoutines(routines: Routines) = api.saveRoutines(routines)
    }
}
