package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.Health
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodDataset
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientSection
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.PricesState
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.RatingInput
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.api.KeepsChoice
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingExtraInput
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.UnmappedText
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeightLog
import dev.tobins.mealplanner.api.CookingRepoSetting
import dev.tobins.mealplanner.api.Me
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Where the server is. */
data class ServerSettings(val url: String)

/**
 * Ktor's default JSON settings, but tolerant of fields this app version does not know, so the server
 * can add fields to a response without breaking installed apps. Unknown enum values still fail.
 */
private val lenientJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    allowSpecialFloatingPointValues = true
    allowStructuredMapKeys = true
    prettyPrint = false
    useArrayPolymorphism = false
}

/**
 * Talks to the meal planner server as the logged-in account. Calls throw on network errors
 * and non-2xx responses. An expired access token is renewed with [refresh] and the call retried.
 */
class ApiClient(
    settings: ServerSettings,
    session: Session? = null,
    engine: HttpClientEngine? = null,
    refresh: suspend () -> Session? = { null },
) {
    private val setUp: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = true
        install(ContentNegotiation) { json(lenientJson) }
        install(HttpTimeout)
        if (session != null) {
            install(Auth) {
                bearer {
                    loadTokens { BearerTokens(session.accessToken, session.refreshToken) }
                    refreshTokens { refresh()?.let { BearerTokens(it.accessToken, it.refreshToken) } }
                    sendWithoutRequest { true }
                }
            }
        }
        defaultRequest { url(settings.url.trimEnd('/') + "/") }
    }

    private val http = if (engine == null) HttpClient(setUp) else HttpClient(engine, setUp)

    suspend fun me(): Me = http.get("api/me").body()

    suspend fun setCookingRepo(url: String?): CookingSyncStatus = http.put("api/sync/cooking/repo") {
        contentType(ContentType.Application.Json)
        setBody(CookingRepoSetting(url))
    }.body()

    suspend fun health(): Health = http.get("health").body()

    suspend fun recipes(): List<RecipeSummary> = http.get("api/recipes").body()

    suspend fun recipe(id: Long): RecipeDetail = http.get("api/recipes/$id").body()

    suspend fun syncCookingSite(): SyncResult = http.post("api/sync/cooking").body()

    suspend fun cookingSyncStatus(): CookingSyncStatus = http.get("api/sync/cooking").body()

    suspend fun day(date: String): DayView = http.get("api/days/$date").body()

    suspend fun setMealStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus) {
        http.put("api/plans/$weekStart/meals/$day/${slot.name}/status") {
            contentType(ContentType.Application.Json)
            setBody(status)
        }
    }

    suspend fun logFood(entry: LogEntry): DayView = http.post("api/log") {
        contentType(ContentType.Application.Json)
        setBody(entry)
    }.body()

    suspend fun deleteLog(id: Long) {
        http.delete("api/log/$id")
    }

    suspend fun weights(): WeightLog = http.get("api/weights").body()

    suspend fun setWeight(date: String, kg: Double): WeightLog = http.put("api/weights/$date") {
        contentType(ContentType.Application.Json)
        setBody(kg)
    }.body()

    suspend fun deleteWeight(date: String): WeightLog = http.delete("api/weights/$date").body()

    suspend fun ingredients(): List<Ingredient> = http.get("api/ingredients").body()

    suspend fun setStaple(ingredientId: Long, staple: Boolean): Pantry =
        (if (staple) http.put("api/pantry/staples/$ingredientId") else http.delete("api/pantry/staples/$ingredientId")).body()

    suspend fun setOnHand(ingredientId: Long, onHand: Boolean): Pantry =
        (if (onHand) http.put("api/pantry/on-hand/$ingredientId") else http.delete("api/pantry/on-hand/$ingredientId")).body()

    suspend fun pantry(): Pantry = http.get("api/pantry").body()

    suspend fun offers(date: String): OffersState = http.get("api/offers") { parameter("date", date) }.body()

    suspend fun refreshOffers(): OffersState = http.post("api/offers/refresh").body()

    suspend fun prices(): PricesState = http.get("api/prices").body()

    /** Starts a lookup on the server; it runs for a few minutes, so poll [prices]. */
    suspend fun refreshPrices(): PricesState = http.post("api/prices/refresh").body()

    suspend fun shoppingList(weekStart: String): ShoppingList = http.get("api/plans/$weekStart/shopping").body()

    /** Ticks an item off or back on; [grams] is what the list advised buying of a buy-ahead or stock-up item. */
    suspend fun setShoppingChecked(weekStart: String, key: String, checked: Boolean, grams: Double? = null): ShoppingList =
        http.put("api/plans/$weekStart/shopping") {
            contentType(ContentType.Application.Json)
            setBody(ShoppingCheck(key, checked, grams))
        }.body()

    /** Hides the buy-ahead or stock-up advice of one week's list, or with [dismissed] false shows it again. */
    suspend fun setShoppingDismissed(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean): ShoppingList {
        val path = "api/plans/$weekStart/shopping/dismissed/${group.path}"
        return if (dismissed) http.put(path).body() else http.delete(path).body()
    }

    /** Puts an own item such as "Coffee" on the list from [weekStart] on; the answer is that week's list. */
    suspend fun addShoppingExtra(weekStart: String, name: String): ShoppingList =
        http.post("api/plans/$weekStart/shopping/extras") {
            contentType(ContentType.Application.Json)
            setBody(ShoppingExtraInput(name))
        }.body()

    /** Takes the own item [id] (the number in its key `x:<id>`) off the list for good; the answer is [weekStart]'s list. */
    suspend fun removeShoppingExtra(weekStart: String, id: Long): ShoppingList =
        http.delete("api/plans/$weekStart/shopping/extras/$id").body()

    /** Whether the account's shopping list advises buying ahead and stocking up. */
    suspend fun shoppingAdvice(): ShoppingAdvice = http.get("api/shopping-advice").body()

    suspend fun saveShoppingAdvice(advice: ShoppingAdvice): ShoppingAdvice = http.put("api/shopping-advice") {
        contentType(ContentType.Application.Json)
        setBody(advice)
    }.body()

    /** Writes a recipe with Claude on the server; this can take a minute or two. */
    suspend fun generateRecipe(request: GenerateRequest): RecipeDetail = http.post("api/recipes/generate") {
        timeout {
            requestTimeoutMillis = 240_000
            socketTimeoutMillis = 240_000
        }
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    /** Writes a recipe for one meal of the plan and puts it there; can take a minute or two. */
    suspend fun generateIntoPlan(weekStart: String, day: Int, slot: MealSlot, request: GenerateRequest): WeekPlan =
        http.post("api/plans/$weekStart/meals/$day/${slot.name}/generate") {
            timeout {
                requestTimeoutMillis = 240_000
                socketTimeoutMillis = 240_000
            }
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun createRecipe(input: RecipeInput): RecipeDetail = http.post("api/recipes") {
        contentType(ContentType.Application.Json)
        setBody(input)
    }.body()

    suspend fun updateRecipe(id: Long, input: RecipeInput): RecipeDetail = http.put("api/recipes/$id") {
        contentType(ContentType.Application.Json)
        setBody(input)
    }.body()

    suspend fun deleteRecipe(id: Long) {
        http.delete("api/recipes/$id")
    }

    suspend fun updateRecipeSettings(id: Long, settings: RecipeSettings): RecipeDetail = http.put("api/recipes/$id/settings") {
        contentType(ContentType.Application.Json)
        setBody(settings)
    }.body()

    suspend fun rateRecipe(id: Long, rating: Int?): RecipeDetail = http.put("api/recipes/$id/rating") {
        contentType(ContentType.Application.Json)
        setBody(RatingInput(rating))
    }.body()

    suspend fun routines(): Routines = http.get("api/routines").body()

    suspend fun saveRoutines(routines: Routines): Routines = http.put("api/routines") {
        contentType(ContentType.Application.Json)
        setBody(routines)
    }.body()

    suspend fun unmapped(): List<UnmappedText> = http.get("api/mappings/unmapped").body()

    suspend fun map(request: MappingRequest): Ingredient = http.put("api/mappings") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    suspend fun ingredientSections(): List<IngredientSection> = http.get("api/ingredients/sections").body()

    /** Sets an ingredient's shop section by hand; null goes back to the automatic one. */
    suspend fun setSection(ingredientId: Long, section: String?): IngredientSection = http.put("api/ingredients/$ingredientId/section") {
        contentType(ContentType.Application.Json)
        setBody(SectionChoice(section))
    }.body()

    /** Sets by hand whether an ingredient keeps until next week; null goes back to its section's answer. */
    suspend fun setKeeps(ingredientId: Long, keeps: Boolean?): IngredientSection = http.put("api/ingredients/$ingredientId/keeps") {
        contentType(ContentType.Application.Json)
        setBody(KeepsChoice(keeps))
    }.body()

    suspend fun searchFoods(query: String): List<Food> = http.get("api/foods") { parameter("q", query) }.body()

    suspend fun foodDataset(): FoodDataset = http.get("api/foods/dataset").body()

    suspend fun profile(): ProfileState = http.get("api/profile").body()

    suspend fun saveProfile(profile: Profile): ProfileState = http.put("api/profile") {
        contentType(ContentType.Application.Json)
        setBody(profile)
    }.body()

    suspend fun plan(weekStart: String): WeekPlan = http.get("api/plans/$weekStart").body()

    /** The stores the account shops at; [ShopsSetting.stores] is null when it never chose. */
    suspend fun shops(): ShopsSetting = http.get("api/shops").body()

    suspend fun saveShops(setting: ShopsSetting): ShopsSetting = http.put("api/shops") {
        contentType(ContentType.Application.Json)
        setBody(setting)
    }.body()

    suspend fun generatePlan(weekStart: String): WeekPlan = http.post("api/plans/$weekStart/generate").body()

    suspend fun rerollMeal(weekStart: String, day: Int, slot: MealSlot): WeekPlan =
        http.post("api/plans/$weekStart/meals/$day/${slot.name}/reroll").body()

    suspend fun setMealLocked(weekStart: String, day: Int, slot: MealSlot, locked: Boolean): WeekPlan {
        val path = "api/plans/$weekStart/meals/$day/${slot.name}/lock"
        return if (locked) http.put(path).body() else http.delete(path).body()
    }

    fun close() = http.close()
}
