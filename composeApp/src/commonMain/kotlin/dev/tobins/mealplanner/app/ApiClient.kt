package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.Health
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.DinnerMove
import dev.tobins.mealplanner.api.DinnerSuggestions
import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodDataset
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.AddOn
import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.IngredientSection
import dev.tobins.mealplanner.api.MappedLine
import dev.tobins.mealplanner.api.ManyAddedInput
import dev.tobins.mealplanner.api.ManyRotationInput
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.MealAdjustment
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.OfferExclusionRequest
import dev.tobins.mealplanner.api.OfferExclusions
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.PricesState
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.WeekPlanning
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.RatingInput
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.SpareMove
import dev.tobins.mealplanner.api.StandardChoice
import dev.tobins.mealplanner.api.StandardChosen
import dev.tobins.mealplanner.api.StandardInput
import dev.tobins.mealplanner.api.StandardMeals
import dev.tobins.mealplanner.api.StandardSwitch
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.RotationInput
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.api.KeepsChoice
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingExtraInput
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShoppingRowKey
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.ToppingInput
import dev.tobins.mealplanner.api.ToppingsInput
import dev.tobins.mealplanner.api.UnmappedText
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeightLog
import dev.tobins.mealplanner.api.CookingRepoSetting
import dev.tobins.mealplanner.api.LoginRefusal
import dev.tobins.mealplanner.api.Me
import dev.tobins.mealplanner.api.AccountRequest
import dev.tobins.mealplanner.api.RegistrationReceipt
import dev.tobins.mealplanner.api.RegistrationRequest
import dev.tobins.mealplanner.api.RegistrationStatus
import dev.tobins.mealplanner.api.NotificationFacts
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A 403 the server answered to the login itself, not to what was asked: the access token it was sent with, and why. */
private class LoginRefused(val token: String?, val reason: String, val detail: String?)

/**
 * The [LoginRefused] of [cause]; null for any other failure. The reason is in the body, as
 * `{"error": "...", "reason": "not_in_group"}`, with an optional `detail` ([LoginRefusal.WAITING_FOR_APPROVAL]).
 */
private suspend fun loginRefusal(cause: Throwable): LoginRefused? {
    if (cause !is ClientRequestException || cause.response.status != HttpStatusCode.Forbidden) return null
    val failed = failedFrom(cause)
    val reason = failed.reason?.takeIf { it == LoginRefusal.NOT_IN_GROUP || it == LoginRefusal.NO_GROUPS } ?: return null
    return LoginRefused(cause.response.call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer "), reason, failed.detail)
}

/** Where the server is. */
data class ServerSettings(val url: String)

/**
 * Ktor's default JSON settings, but tolerant of what this app version does not know, so the server
 * can grow a response without breaking installed apps: a field it does not know is skipped
 * (`ignoreUnknownKeys`), and an enum value it does not know becomes the field's default
 * (`coerceInputValues`). A field of an enum type without a default still fails on an unknown
 * value, and so does an enum inside a list or a map, which coercion does not reach: enum fields
 * added from now on need a default (or are nullable, `= null`), and a new value of an enum that
 * is already in use waits for a release (README, "API compatibility").
 */
private val lenientJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    isLenient = true
    allowSpecialFloatingPointValues = true
    allowStructuredMapKeys = true
    prettyPrint = false
    useArrayPolymorphism = false
}

/**
 * Talks to the meal planner server as the logged-in account. Calls throw on network errors (an
 * IOException on every platform) and non-2xx responses. An expired access token is renewed with
 * [refresh], which is told the access token the server refused, and the call retried. A login the
 * server refuses as such ([LoginRefusal], a 403) is told to [loginRefused], with the access token it
 * was refused for, the reason and its detail, if any; the call fails all the same.
 */
class ApiClient(
    settings: ServerSettings,
    session: Session? = null,
    engine: HttpClientEngine? = null,
    loginRefused: suspend (refused: String?, reason: String, detail: String?) -> Unit = { _, _, _ -> },
    refresh: suspend (refused: String?) -> Session? = { null },
) {
    private val setUp: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = true
        HttpResponseValidator {
            handleResponseExceptionWithRequest { cause, _ ->
                // A request that cannot reach the server fails as an IOException on every platform, the
                // lost connection the screens catch; the login's renewal fails through here too (connectionFailure).
                connectionFailure(cause)?.let { throw it }
                // Told with the token the request was sent with, which a renewal meanwhile may have replaced.
                loginRefusal(cause)?.let { loginRefused(it.token, it.reason, it.detail) }
            }
        }
        install(ContentNegotiation) { json(lenientJson) }
        install(HttpTimeout)
        if (session != null) {
            install(Auth) {
                bearer {
                    loadTokens { BearerTokens(session.accessToken, session.refreshToken) }
                    refreshTokens { refresh(oldTokens?.accessToken)?.let { BearerTokens(it.accessToken, it.refreshToken) } }
                    sendWithoutRequest { true }
                }
            }
        }
        defaultRequest { url(settings.url.trimEnd('/') + "/") }
    }

    private val http = if (engine == null) HttpClient(setUp) else HttpClient(engine, setUp)

    suspend fun me(): Me = http.get("api/me").body()

    /** Whether the server takes requests for an account (issue 314); asked without a login. A server from before it answers 404. */
    suspend fun registrationStatus(): RegistrationStatus = http.get("api/registration").body()

    /**
     * Asks for an account (issue 314), without a login. A refusal is thrown as any failed call is; [registrationOutcome]
     * says what it means.
     */
    suspend fun register(request: RegistrationRequest): RegistrationReceipt = http.post("api/registration") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    /** The requests for an account that wait, oldest first: the owner's account only ([Me.approvesRequests]). */
    suspend fun accountRequests(): List<AccountRequest> = http.get("api/registrations").body()

    /** Approves [username]'s request, answering the requests still waiting. */
    suspend fun approveRequest(username: String): List<AccountRequest> = http.post("api/registrations/${username.encodeURLPathPart()}/approve").body()

    /** Rejects [username]'s request, which deletes the account made for it; answers the requests still waiting. */
    suspend fun rejectRequest(username: String): List<AccountRequest> = http.post("api/registrations/${username.encodeURLPathPart()}/reject").body()

    /** What the account's data says is due today, for the notifications (Android's background check). */
    suspend fun notifications(): NotificationFacts = http.get("api/notifications").body()

    /** Where the account's bank comes from: its own repository, the server's site, or none. */
    suspend fun setCookingRepo(setting: CookingRepoSetting): CookingSyncStatus = http.put("api/sync/cooking/repo") {
        contentType(ContentType.Application.Json)
        setBody(setting)
    }.body()

    suspend fun health(): Health = http.get("health").body()

    /** My recipes: those added from the bank and those made in the app; every recipe the server has before the bank. */
    suspend fun recipes(): List<RecipeSummary> = http.get("api/recipes").body()

    /**
     * The cooking-site recipes not added yet, or null from a server from before the bank: it reads
     * `bank` as a recipe id and answers 400 (or 404), where the bank's own answer is always a list.
     * The caller then offers no bank.
     */
    suspend fun recipeBank(): List<RecipeSummary>? = try {
        http.get("api/recipes/bank").body()
    } catch (e: ClientRequestException) {
        if (e.response.status != HttpStatusCode.BadRequest && e.response.status != HttpStatusCode.NotFound) throw e
        null
    }

    /** Adds a bank recipe to my recipes, in the rotation unless [inRotation] is false; or puts it back with [added] false. */
    suspend fun setRecipeAdded(id: Long, added: Boolean, inRotation: Boolean = true): RecipeDetail =
        http.put("api/recipes/$id/added") {
            contentType(ContentType.Application.Json)
            setBody(AddedInput(added, inRotation))
        }.body()

    /** Puts one of my recipes in the rotation the planner picks from, or takes it out. */
    suspend fun setRecipeRotation(id: Long, inRotation: Boolean): RecipeDetail = http.put("api/recipes/$id/rotation") {
        contentType(ContentType.Application.Json)
        setBody(RotationInput(inRotation))
    }.body()

    /** [setRecipeAdded] for many recipes, all or none; answers my recipes. */
    suspend fun setRecipesAdded(ids: List<Long>, added: Boolean, inRotation: Boolean = true): List<RecipeSummary> =
        http.put("api/recipes/added") {
            contentType(ContentType.Application.Json)
            setBody(ManyAddedInput(ids, added, inRotation))
        }.body()

    /** [setRecipeRotation] for many recipes, all or none; answers my recipes. */
    suspend fun setRecipesRotation(ids: List<Long>, inRotation: Boolean): List<RecipeSummary> =
        http.put("api/recipes/rotation") {
            contentType(ContentType.Application.Json)
            setBody(ManyRotationInput(ids, inRotation))
        }.body()

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

    /** Adds an ingredient to the account's catalogue, such as one for a NEVO food picked in the recipe editor. */
    suspend fun createIngredient(input: IngredientInput): Ingredient = http.post("api/ingredients") {
        contentType(ContentType.Application.Json)
        setBody(input)
    }.body()

    suspend fun setStaple(ingredientId: Long, staple: Boolean): Pantry =
        (if (staple) http.put("api/pantry/staples/$ingredientId") else http.delete("api/pantry/staples/$ingredientId")).body()

    suspend fun setOnHand(ingredientId: Long, onHand: Boolean): Pantry =
        (if (onHand) http.put("api/pantry/on-hand/$ingredientId") else http.delete("api/pantry/on-hand/$ingredientId")).body()

    suspend fun pantry(): Pantry = http.get("api/pantry").body()

    suspend fun offers(date: String): OffersState = http.get("api/offers") { parameter("date", date) }.body()

    suspend fun refreshOffers(): OffersState = http.post("api/offers/refresh").body()

    /** The offers the account said are not one of its ingredients ("Not this ingredient", issue 316), the latest first. */
    suspend fun offerExclusions(): OfferExclusions = http.get("api/offers/exclusions").body()

    /** Says that offers like [request]'s are not its ingredient, or with [excluded] false takes that back; answers the account's list. */
    suspend fun setOfferExcluded(request: OfferExclusionRequest, excluded: Boolean): OfferExclusions =
        if (excluded) {
            http.put("api/offers/exclusions") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
        } else {
            http.delete("api/offers/exclusions") {
                parameter("store", request.store.name)
                parameter("title", request.title)
                parameter("ingredientId", request.ingredientId)
            }.body()
        }

    suspend fun prices(): PricesState = http.get("api/prices").body()

    /** Starts a lookup on the server; it runs for a few minutes, so poll [prices]. */
    suspend fun refreshPrices(): PricesState = http.post("api/prices/refresh").body()

    suspend fun shoppingList(weekStart: String): ShoppingList = http.get("api/plans/$weekStart/shopping").body()

    /** Ticks an item off or back on; [grams] is what the list advised buying of a buy-ahead or stock-up item, [shown] what the phone showed of a row when it made the tick that is sent late. */
    suspend fun setShoppingChecked(weekStart: String, key: String, checked: Boolean, grams: Double? = null, shown: ShoppingCheck.Shown? = null): ShoppingList =
        http.put("api/plans/$weekStart/shopping") {
            contentType(ContentType.Application.Json)
            setBody(ShoppingCheck(key, checked, grams, shown))
        }.body()

    /** Ticks the ticked row [key] again for what it needs now, in one step; null from a server from before that. */
    suspend fun tickShoppingAgain(weekStart: String, key: String): ShoppingList? = unlessUnknown {
        http.put("api/plans/$weekStart/shopping/again") {
            contentType(ContentType.Application.Json)
            setBody(ShoppingRowKey(key))
        }.body()
    }

    /** Puts what was bought of [key], which no meal needs any more, on hand; null from a server from before that. */
    suspend fun useUpShopping(weekStart: String, key: String): ShoppingList? = unlessUnknown {
        http.post("api/plans/$weekStart/shopping/use-up") {
            contentType(ContentType.Application.Json)
            setBody(ShoppingRowKey(key))
        }.body()
    }

    /**
     * What [request] answers, or null when the server has no such route: a server from before it
     * answers 404 without an error, as Ktor does for any path it does not know, where the route's own
     * 404 says what was not found. The caller then does what it did before the route.
     */
    private suspend fun <T : Any> unlessUnknown(request: suspend () -> T): T? = try {
        request()
    } catch (e: ClientRequestException) {
        if (e.response.status != HttpStatusCode.NotFound || saysWhy(e.response.bodyAsText())) throw e
        null
    }

    /** Whether [body] is the server's own explanation of an error, `{"error": "..."}`. */
    private fun saysWhy(body: String): Boolean =
        runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.containsKey("error") == true }.getOrDefault(false)

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

    /**
     * [lines] as a recipe saved with them would have them, each with the ingredient it means for the account, without
     * saving anything (issue 342): how the editor looks pasted lines up.
     */
    suspend fun readLines(lines: List<RecipeLineInput>): List<MappedLine> = http.post("api/recipes/lines") {
        contentType(ContentType.Application.Json)
        setBody(lines)
    }.body()

    suspend fun deleteRecipe(id: Long) {
        http.delete("api/recipes/$id")
    }

    suspend fun updateRecipeSettings(id: Long, settings: RecipeSettings): RecipeDetail = http.put("api/recipes/$id/settings") {
        contentType(ContentType.Application.Json)
        setBody(settings)
    }.body()

    /** Replaces the recipe's optional toppings with [toppings], in their order; answers the recipe. */
    suspend fun setRecipeToppings(id: Long, toppings: List<ToppingInput>): RecipeDetail = http.put("api/recipes/$id/toppings") {
        contentType(ContentType.Application.Json)
        setBody(ToppingsInput(toppings))
    }.body()

    /** What a meal of recipe [id] can have added with one tap; none from a server from before quick add-ons. */
    suspend fun addOns(id: Long): List<AddOn> = unlessUnknown { http.get("api/recipes/$id/add-ons").body<List<AddOn>>() }.orEmpty()

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

    /**
     * Puts the recipe [recipeId] in a meal's slot, locked, and returns the week's plan; null from a
     * server from before the route, which the caller says to the user.
     */
    suspend fun chooseRecipe(weekStart: String, day: Int, slot: MealSlot, recipeId: Long): WeekPlan? = unlessUnknown {
        http.put("api/plans/$weekStart/meals/$day/${slot.name}") {
            contentType(ContentType.Application.Json)
            setBody(MealChoice(recipeId))
        }.body()
    }

    /** The meal in [day]'s [slot] with what was in it: its recipe's ingredients for its portions and this meal's changes. */
    suspend fun mealLines(weekStart: String, day: Int, slot: MealSlot): MealLines =
        http.get("api/plans/$weekStart/meals/$day/${slot.name}/lines").body()

    /** Says what was in a meal: [changes] replace those it had, none puts it back to its recipe; answers the meal as it is now. */
    suspend fun adjustMeal(weekStart: String, day: Int, slot: MealSlot, changes: List<MealLineChange>): MealLines =
        http.put("api/plans/$weekStart/meals/$day/${slot.name}/lines") {
            contentType(ContentType.Application.Json)
            setBody(MealAdjustment(changes))
        }.body()

    suspend fun setMealLocked(weekStart: String, day: Int, slot: MealSlot, locked: Boolean): WeekPlan {
        val path = "api/plans/$weekStart/meals/$day/${slot.name}/lock"
        return if (locked) http.put(path).body() else http.delete(path).body()
    }

    /** Sets how many days recipe [id] keeps once made, in a box and vacuum sealed (issue 234); null for a way it is not prepped. */
    suspend fun setRecipeKeeps(id: Long, keeps: RecipeKeeps): RecipeDetail = http.put("api/recipes/$id/keeps") {
        contentType(ContentType.Application.Json)
        setBody(keeps)
    }.body()

    /** Makes a prep ([PrepInput]) and answers the week starting [weekStart], the one shown. */
    suspend fun createPrep(weekStart: String, input: PrepInput): WeekPlan = http.post("api/plans/$weekStart/preps") {
        contentType(ContentType.Application.Json)
        setBody(input)
    }.body()

    /** Takes prep [id] out of the plan with all its meals, in every week; answers the week starting [weekStart]. */
    suspend fun removePrep(weekStart: String, id: Long): WeekPlan = http.delete("api/plans/$weekStart/preps/$id").body()

    /**
     * Adds a side of [PrepSlot.side] servings of prep [id], still to make, next to the meal at [side]'s slot, which makes the
     * batch larger (issue 269); answers the week starting [weekStart].
     */
    suspend fun addSide(weekStart: String, id: Long, side: PrepSlot): WeekPlan = http.post("api/plans/$weekStart/preps/$id/sides") {
        contentType(ContentType.Application.Json)
        setBody(side)
    }.body()

    /** Takes the side next to the meal in [day]'s [slot] out of its batch (issue 234, step 2); the meal keeps its main. */
    suspend fun removeSide(weekStart: String, day: Int, slot: MealSlot): WeekPlan = http.delete("api/plans/$weekStart/meals/$day/${slot.name}/side").body()

    /** The shares of skipped prepped meals still in the box on [date]; none from a server from before preps. */
    suspend fun spareShares(date: String): List<SparePortion> = unlessUnknown { http.get("api/preps/spare/$date").body<List<SparePortion>>() }.orEmpty()

    /** Eats the share of the skipped meal [SpareMove.from] of prep [id] in [SpareMove.to]; answers the week starting [weekStart]. */
    suspend fun eatSpare(weekStart: String, id: Long, move: SpareMove): WeekPlan = http.post("api/plans/$weekStart/preps/$id/spare") {
        contentType(ContentType.Application.Json)
        setBody(move)
    }.body()

    /** Makes one of my meals a standard meal (issue 265), or no longer one; marking answers what it made staples. */
    suspend fun setStandardMeal(id: Long, standard: Boolean): StandardSwitch = http.put("api/recipes/$id/standard") {
        contentType(ContentType.Application.Json)
        setBody(StandardInput(standard))
    }.body()

    /** The standard meals for each slot of the week starting [weekStart], and the slots none can take; null from a server before them. */
    suspend fun standardMeals(weekStart: String): StandardMeals? = unlessUnknown { http.get("api/plans/$weekStart/standard-meals").body<StandardMeals>() }

    /**
     * Takes the meal in [day]'s [slot] of the week starting [weekStart] out of the plan; a standard meal placed over a skipped
     * prepped meal gives the slot back to it (issue 265). The server refuses a meal that keeps its recipe (409).
     */
    suspend fun removeMeal(weekStart: String, day: Int, slot: MealSlot): WeekPlan = http.delete("api/plans/$weekStart/meals/$day/${slot.name}").body()

    /** Puts standard meal [recipeId] in [day]'s [slot] of the week starting [weekStart], eaten at once when [eaten]. */
    suspend fun chooseStandard(weekStart: String, day: Int, slot: MealSlot, recipeId: Long, eaten: Boolean): StandardChosen =
        http.put("api/plans/$weekStart/meals/$day/${slot.name}/standard") {
            contentType(ContentType.Application.Json)
            setBody(StandardChoice(recipeId, eaten))
        }.body()

    /** The planning screen of the week starting [weekStart] (issue 255); null from a server before it, which plans in one go. */
    suspend fun planning(weekStart: String): WeekPlanning? = unlessUnknown { http.get("api/plans/$weekStart/planning").body<WeekPlanning>() }

    /**
     * The next [count] suggestions for the week's dinners, leaving out the recipes [seen] already ("Show others"); with a
     * [query], every meal of mine whose title has each of its words ("Something else"). Null from a server before the
     * planning screen, which has no such route.
     */
    suspend fun dinnerSuggestions(weekStart: String, seen: Collection<Long>, count: Int, query: String? = null): DinnerSuggestions? = unlessUnknown {
        http.get("api/plans/$weekStart/planning/suggestions") {
            parameter("count", count)
            if (seen.isNotEmpty()) parameter("seen", seen.joinToString(","))
            query?.let { parameter("q", it) }
        }.body<DinnerSuggestions>()
    }

    /** Chooses recipe [recipeId] for the week's dinners, or with [chosen] false takes it out again. */
    suspend fun chooseDinner(weekStart: String, recipeId: Long, chosen: Boolean): WeekPlanning =
        if (chosen) http.put("api/plans/$weekStart/planning/picks/$recipeId").body() else http.delete("api/plans/$weekStart/planning/picks/$recipeId").body()

    /** Hides recipe [recipeId] from the week's suggestions ("Not this week"), or with [hidden] false shows it again. */
    suspend fun hideForWeek(weekStart: String, recipeId: Long, hidden: Boolean): WeekPlanning =
        if (hidden) http.put("api/plans/$weekStart/planning/not-this-week/$recipeId").body() else http.delete("api/plans/$weekStart/planning/not-this-week/$recipeId").body()

    /** Plans the week with the dinners chosen for it ("Done", or "Fill the rest"), and returns the plan. */
    suspend fun planningDone(weekStart: String): WeekPlan = http.post("api/plans/$weekStart/planning/done").body()

    /** Moves the dinner of [day] to [toDay], swapping it with the dinner there; the server refuses one that stays (409). */
    suspend fun moveDinner(weekStart: String, day: Int, toDay: Int): WeekPlan = http.post("api/plans/$weekStart/meals/$day/${MealSlot.DINNER.name}/move") {
        contentType(ContentType.Application.Json)
        setBody(DinnerMove(toDay))
    }.body()

    fun close() = http.close()
}
