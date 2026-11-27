package dev.tobins.mealplanner.app.recipes

import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.CookingRepoSetting
import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.ManyAddedInput
import dev.tobins.mealplanner.api.ManyRotationInput
import dev.tobins.mealplanner.api.MappedLine
import dev.tobins.mealplanner.api.RatingInput
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.RotationInput
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.StandardInput
import dev.tobins.mealplanner.api.StandardSwitch
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.api.ToppingInput
import dev.tobins.mealplanner.api.ToppingsInput
import dev.tobins.mealplanner.app.ApiClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/*
 * The recipes' server calls: my recipes and the bank, their editing, rotation, toppings and ratings, generation and the cooking site.
 * They are extensions of [ApiClient], which keeps the address, the login, the errors and the retries for all of them.
 */

/** Where the account's bank comes from: its own repository, the server's site, or none. */
suspend fun ApiClient.setCookingRepo(setting: CookingRepoSetting): CookingSyncStatus = http.put("api/sync/cooking/repo") {
    contentType(ContentType.Application.Json)
    setBody(setting)
}.body()

/** My recipes: those added from the bank and those made in the app; every recipe the server has before the bank. */
suspend fun ApiClient.recipes(): List<RecipeSummary> = http.get("api/recipes").body()

/**
 * The cooking-site recipes not added yet, or null from a server from before the bank: it reads
 * `bank` as a recipe id and answers 400 (or 404), where the bank's own answer is always a list.
 * The caller then offers no bank.
 */
suspend fun ApiClient.recipeBank(): List<RecipeSummary>? = try {
    http.get("api/recipes/bank").body()
} catch (e: ClientRequestException) {
    if (e.response.status != HttpStatusCode.BadRequest && e.response.status != HttpStatusCode.NotFound) throw e
    null
}

/** Adds a bank recipe to my recipes, in the rotation unless [inRotation] is false; or puts it back with [added] false. */
suspend fun ApiClient.setRecipeAdded(id: Long, added: Boolean, inRotation: Boolean = true): RecipeDetail =
    http.put("api/recipes/$id/added") {
        contentType(ContentType.Application.Json)
        setBody(AddedInput(added, inRotation))
    }.body()

/** Puts one of my recipes in the rotation the planner picks from, or takes it out. */
suspend fun ApiClient.setRecipeRotation(id: Long, inRotation: Boolean): RecipeDetail = http.put("api/recipes/$id/rotation") {
    contentType(ContentType.Application.Json)
    setBody(RotationInput(inRotation))
}.body()

/** [setRecipeAdded] for many recipes, all or none; answers my recipes. */
suspend fun ApiClient.setRecipesAdded(ids: List<Long>, added: Boolean, inRotation: Boolean = true): List<RecipeSummary> =
    http.put("api/recipes/added") {
        contentType(ContentType.Application.Json)
        setBody(ManyAddedInput(ids, added, inRotation))
    }.body()

/** [setRecipeRotation] for many recipes, all or none; answers my recipes. */
suspend fun ApiClient.setRecipesRotation(ids: List<Long>, inRotation: Boolean): List<RecipeSummary> =
    http.put("api/recipes/rotation") {
        contentType(ContentType.Application.Json)
        setBody(ManyRotationInput(ids, inRotation))
    }.body()

suspend fun ApiClient.recipe(id: Long): RecipeDetail = http.get("api/recipes/$id").body()

suspend fun ApiClient.syncCookingSite(): SyncResult = http.post("api/sync/cooking").body()

suspend fun ApiClient.cookingSyncStatus(): CookingSyncStatus = http.get("api/sync/cooking").body()

/** Writes a recipe with Claude on the server; this can take a minute or two. */
suspend fun ApiClient.generateRecipe(request: GenerateRequest): RecipeDetail = http.post("api/recipes/generate") {
    timeout {
        requestTimeoutMillis = 240_000
        socketTimeoutMillis = 240_000
    }
    contentType(ContentType.Application.Json)
    setBody(request)
}.body()

suspend fun ApiClient.createRecipe(input: RecipeInput): RecipeDetail = http.post("api/recipes") {
    contentType(ContentType.Application.Json)
    setBody(input)
}.body()

suspend fun ApiClient.updateRecipe(id: Long, input: RecipeInput): RecipeDetail = http.put("api/recipes/$id") {
    contentType(ContentType.Application.Json)
    setBody(input)
}.body()

/**
 * [lines] as a recipe saved with them would have them, each with the ingredient it means for the account, without
 * saving anything (issue 342): how the editor looks pasted lines up.
 */
suspend fun ApiClient.readLines(lines: List<RecipeLineInput>): List<MappedLine> = http.post("api/recipes/lines") {
    contentType(ContentType.Application.Json)
    setBody(lines)
}.body()

suspend fun ApiClient.deleteRecipe(id: Long) {
    http.delete("api/recipes/$id")
}

suspend fun ApiClient.updateRecipeSettings(id: Long, settings: RecipeSettings): RecipeDetail = http.put("api/recipes/$id/settings") {
    contentType(ContentType.Application.Json)
    setBody(settings)
}.body()

/** Replaces the recipe's optional toppings with [toppings], in their order; answers the recipe. */
suspend fun ApiClient.setRecipeToppings(id: Long, toppings: List<ToppingInput>): RecipeDetail = http.put("api/recipes/$id/toppings") {
    contentType(ContentType.Application.Json)
    setBody(ToppingsInput(toppings))
}.body()

suspend fun ApiClient.rateRecipe(id: Long, rating: Int?): RecipeDetail = http.put("api/recipes/$id/rating") {
    contentType(ContentType.Application.Json)
    setBody(RatingInput(rating))
}.body()

suspend fun ApiClient.routines(): Routines = http.get("api/routines").body()

suspend fun ApiClient.saveRoutines(routines: Routines): Routines = http.put("api/routines") {
    contentType(ContentType.Application.Json)
    setBody(routines)
}.body()

/** Sets how many days recipe [id] keeps once made, in a box and vacuum sealed (issue 234); null for a way it is not prepped. */
suspend fun ApiClient.setRecipeKeeps(id: Long, keeps: RecipeKeeps): RecipeDetail = http.put("api/recipes/$id/keeps") {
    contentType(ContentType.Application.Json)
    setBody(keeps)
}.body()

/** Makes one of my meals a standard meal (issue 265), or no longer one; marking answers what it made staples. */
suspend fun ApiClient.setStandardMeal(id: Long, standard: Boolean): StandardSwitch = http.put("api/recipes/$id/standard") {
    contentType(ContentType.Application.Json)
    setBody(StandardInput(standard))
}.body()
