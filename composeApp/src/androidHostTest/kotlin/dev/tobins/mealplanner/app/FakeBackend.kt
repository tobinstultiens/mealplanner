package dev.tobins.mealplanner.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tobins.mealplanner.api.BatchToMake
import dev.tobins.mealplanner.api.CheaperPack
import dev.tobins.mealplanner.api.PastOffer
import dev.tobins.mealplanner.api.PriceRaise
import dev.tobins.mealplanner.api.PriceVerdict
import dev.tobins.mealplanner.api.VerdictKind
import dev.tobins.mealplanner.api.VerdictMeasure
import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.WeekPlanning
import dev.tobins.mealplanner.api.SuggestionOffer
import dev.tobins.mealplanner.api.SuggestionGround
import dev.tobins.mealplanner.api.PlanningTotals
import dev.tobins.mealplanner.api.PackRestUse
import dev.tobins.mealplanner.api.HiddenRecipe
import dev.tobins.mealplanner.api.DinnerSuggestions
import dev.tobins.mealplanner.api.DinnerSuggestion
import dev.tobins.mealplanner.api.DinnerPick
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.FoodDataset
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientPrice
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.MappedLine
import dev.tobins.mealplanner.api.MealAdjustment
import dev.tobins.mealplanner.api.MealLine
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.Me
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferSourceStatus
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepMeal
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.PricesState
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.RecipeCost
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.ClosedSlot
import dev.tobins.mealplanner.api.StandardChosen
import dev.tobins.mealplanner.api.StandardInput
import dev.tobins.mealplanner.api.StandardMeal
import dev.tobins.mealplanner.api.StandardMeals
import dev.tobins.mealplanner.api.StandardSwitch
import dev.tobins.mealplanner.api.AddOn
import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.LaterShortfall
import dev.tobins.mealplanner.api.RecipeTopping
import dev.tobins.mealplanner.api.ToppingsInput
import dev.tobins.mealplanner.api.ManyAddedInput
import dev.tobins.mealplanner.api.ManyRotationInput
import dev.tobins.mealplanner.api.RotationInput
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.api.TargetSuggestion
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeekRest
import dev.tobins.mealplanner.api.WeightEntry
import dev.tobins.mealplanner.api.WeightLog
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.shopping.ShopSection
import dev.tobins.mealplanner.units.Dimension
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.encodeToJsonElement
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * A server on localhost that answers the calls of the screens with fixed sample data, so
 * the pictures of the screenshot tests never change on their own. The week is the one of
 * [TODAY]; [emptyWeek] leaves it without meals, and [emptyOffers], [emptyPrices] and [emptyRecipes]
 * leave those lists empty, for the empty states. The offers and prices are shared data that a
 * running server always has, so only a fake can show those states.
 */
class FakeBackend(
    private val emptyWeek: Boolean = false,
    private val emptyOffers: Boolean = false,
    private val emptyPrices: Boolean = false,
    private val emptyRecipes: Boolean = false,
    /** A server from before the recipe bank: no bank route, and recipes without the flags of mine and the rotation. */
    private val olderServer: Boolean = false,
    /** The week with a prep of the chicken thighs (issue 234), whose Wednesday lunch was skipped and is still in the box. */
    private val prepped: Boolean = false,
    /** The week with half a serving of the chickpea salad prepped as a side next to three meals (issue 234, step 2). */
    private val sides: Boolean = false,
    /** The week of [sides] with the salad's Thursday lunch eaten as the meal itself instead of as a side (issue 269). */
    private val mixed: Boolean = false,
    /** The week holding only Monday's lunch of the chicken thighs prepped the Sunday before ([tailPrep]), not planned yet (issue 263). */
    private val tailOnly: Boolean = false,
    /** The week says what fresh ingredients it leaves over (issue 244): five rests, so the line says "and 2 more". */
    private val weekRest: Boolean = false,
    /** The prep of [prepped] kept vacuum sealed rather than in a box, which its labels say (issue 263). */
    private val vacuum: Boolean = false,
    /** The week lists a batch of the chickpea salad still to make, made today, that can take a side next to a later meal (issue 263). */
    private val growing: Boolean = false,
    /** Friday's dinner (the lentil soup, whose leftovers are Saturday's lunch) is eaten, so that lunch keeps its recipe too (issue 191). */
    private val dinnerEaten: Boolean = false,
    /**
     * The tuna pasta salad and the shakshuka are standard meals (issue 265), offered for lunch and dinner, the shakshuka with
     * no eggs at home; today's (Wednesday's) lunch is skipped; the eaten breakfast takes none. Without it the server is one
     * from before standard meals.
     */
    private val standardMeals: Boolean = false,
    /**
     * The planning screen (issue 255): the lentil soup and the shakshuka chosen for the week's dinners and the tuna salad
     * hidden, with [suggestions] to choose from. Without it the server is one from before the planning screen.
     */
    private val planning: Boolean = false,
    /** With [planning], an account with no dinner to suggest, as one whose rotation has none that makes a dinner. */
    nothingToSuggest: Boolean = false,
    /**
     * The offers' verdicts once weeks of price history are recorded (issue 118): one of each kind the screens show.
     * Without it every judged offer is "too new to judge", as in the first eight weeks.
     */
    private val laterVerdicts: Boolean = false,
) : AutoCloseable {
    /** Every call that changed something, as `PUT /api/recipes/7/rotation {"inRotation":false}`, for a test to read. */
    val changes: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Every call the server got, as `GET /api/recipes/bank`, for a test to tell that something was asked (or not). */
    val requests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /**
     * The calls of [requests] once their answer is sent: what the server sends from then on is no longer in it, so a test
     * can change the data after an answer and know that only a later call can tell. The app may not have read it yet.
     */
    val answered: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /**
     * The free lunch or dinner the server proposes for the share in the box ([spare]): none, as every slot of its room holds a
     * meal in the sample week, unless a test sets one.
     */
    @Volatile
    var spareProposed: PrepSlot? = null

    /** While set, a change of how long a recipe keeps waits for it to open before it is answered, as a slow server would. */
    @Volatile
    var keepsAnswer: java.util.concurrent.CountDownLatch? = null

    /** Sets how long recipe [id] keeps as another device would, without the app asking. */
    fun keepElsewhere(id: Long, keeps: RecipeKeeps) {
        rows.replaceAll { if (it.id == id) it.copy(keepsDays = keeps.days, keepsVacuumDays = keeps.vacuumDays) else it }
    }

    /** A sentence that makes every change of recipes answer 409, as the server does for a recipe planned on Friday. */
    @Volatile
    var refusing: String? = null

    /**
     * While set, the week's standard meals are answered once it opens, as over a slow connection, so that a test can show
     * a screen before they come in. They wait on a thread of their own: every other call is answered meanwhile.
     */
    @Volatile
    var standardMealsAnswer: java.util.concurrent.CountDownLatch? = null

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val held = standardMealsAnswer
            if (held != null && exchange.requestURI.path.endsWith("/standard-meals")) {
                thread { held.await(10, java.util.concurrent.TimeUnit.SECONDS); exchange.respond() }
            } else {
                exchange.respond()
            }
        }
        start()
    }
    private val json = Json { encodeDefaults = true }

    val settings = ServerSettings("http://127.0.0.1:${server.address.port}")

    override fun close() = server.stop(0)

    private fun HttpExchange.respond() {
        // A session the server no longer accepts, as after its access token ran out.
        if (requestHeaders.getFirst("Authorization") == "Bearer $REFUSED_TOKEN") {
            sendResponseHeaders(401, -1)
            close()
            return
        }
        // A login the server refuses on every request, as one with MEALPLANNER_OIDC_GROUP answers an account outside it.
        if (requestHeaders.getFirst("Authorization") == "Bearer $NOT_IN_GROUP_TOKEN") {
            val bytes = """{"error":"This account may not use the meal planner yet. Ask the person who runs this server.","reason":"not_in_group"}""".toByteArray()
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(403, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
            return
        }
        val sent = requestBody.readBytes().decodeToString()
        requests += "$requestMethod ${requestURI.path}"
        var status = 200
        val body = try {
            route(requestMethod, requestURI.path, sent, queryOf(requestURI.rawQuery))
        } catch (e: Refusal) {
            status = e.status
            """{"error":${json.encodeToString(e.message)}}"""
        } catch (e: Exception) {
            null
        }
        if (body == null) {
            sendResponseHeaders(404, -1)
        } else {
            val bytes = body.toByteArray()
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        close()
        answered += "$requestMethod ${requestURI.path}"
    }

    /** What the server answers with an error and its sentence, as `{"error": "..."}`. */
    private class Refusal(val status: Int, message: String) : Exception(message)

    /** The parameters of a query string, `count=6&seen=1%2C2`, decoded. */
    private fun queryOf(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { '=' in it }
        .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun route(method: String, path: String, sent: String, query: Map<String, String> = emptyMap()): String? {
        val parts = path.trim('/').split('/')
        if (parts.getOrNull(1) == "plans" && (parts.getOrNull(3) == "planning" || parts.lastOrNull() == "move")) return planningRoute(method, path, sent, query)
        if (parts.getOrNull(1) == "recipes" && method != "GET") return changeRecipes(method, parts, sent)
        return when {
            path == "/api/me" -> json.encodeToString(Me("anna", "Anna"))
            path == "/api/foods/dataset" -> json.encodeToString(FoodDataset(2328, "NEVO online version 2025/9.0, RIVM, Bilthoven"))
            path == "/api/recipes" -> recipesJson(if (emptyRecipes) emptyList() else mine())
            // A server from before the bank reads `bank` as a recipe id.
            path == "/api/recipes/bank" -> if (olderServer) throw Refusal(400, "Recipe id must be a number") else recipesJson(bank())
            parts.size == 3 && parts[1] == "recipes" -> detailJson(parts[2].toLong())
            parts.size == 4 && parts[1] == "recipes" && parts[3] == "add-ons" -> json.encodeToString(addOns(parts[2].toLong()))
            path == "/api/profile" -> profileJson(method, sent)
            method == "PUT" && path.startsWith("/api/plans/") && path.endsWith("/status") -> { changes += "PUT $path $sent"; "{}" }
            path == "/api/routines" -> json.encodeToString(Routines(breakfast = 1))
            // A sync that found three new recipes (in the bank on a server that has one) and changed one.
            method == "PUT" && path == "/api/sync/cooking/repo" -> json.encodeToString(
                CookingSyncStatus(
                    "2026-11-18T10:00:00Z", "d4e5f6a", null, 12, repoUrl = "https://example.com/mine.git",
                    serverSite = true, serverRepoUrl = "https://example.com/cooking.git",
                ),
            )
            method == "POST" && path == "/api/sync/cooking" -> json.encodeToString(SyncResult(added = 3, updated = 1, unchanged = 6, removed = 0, problems = emptyMap()))
            // The account reads the server's cooking site.
            path == "/api/sync/cooking" -> json.encodeToString(
                CookingSyncStatus(
                    "2026-11-17T20:00:00Z", "a1b2c3d", null, 6, repoUrl = "https://example.com/cooking.git",
                    shared = true, serverSite = true, serverRepoUrl = "https://example.com/cooking.git",
                ),
            )
            parts.size == 3 && parts[1] == "days" -> json.encodeToString(day(parts[2]))
            parts.size == 4 && parts[1] == "preps" && parts[2] == "spare" -> json.encodeToString(if (prepped) listOf(spare.copy(proposed = spareProposed)) else emptyList())
            // A share in the box eaten in another meal: the week as it was, as the test reads what was sent.
            method == "POST" && parts.size == 6 && parts[3] == "preps" && parts[5] == "spare" -> { changes += "POST $path $sent"; json.encodeToString(plan(parts[2])) }
            parts.size == 3 && parts[1] == "plans" -> json.encodeToString(plan(parts[2]))
            // What was in a meal; every meal of the week is Wednesday's dinner here.
            parts.size == 7 && parts[1] == "plans" && parts[6] == "lines" -> json.encodeToString(mealLines(method, sent))
            parts.size == 4 && parts[1] == "plans" && parts[3] == "standard-meals" -> if (standardMeals) json.encodeToString(standardOffer(parts[2])) else null
            // A standard meal in a slot: the week as it was, as the test reads what was sent; the eggs are staples again.
            method == "PUT" && parts.size == 7 && parts[1] == "plans" && parts[6] == "standard" -> {
                changes += "PUT $path $sent"
                json.encodeToString(StandardChosen(plan(parts[2]), addedStaples = listOf("Eggs")))
            }
            // A meal taken out of the plan: the week as it was, as the test reads what was sent.
            method == "DELETE" && parts.size == 6 && parts[1] == "plans" && parts[3] == "meals" -> { changes += "DELETE $path"; json.encodeToString(plan(parts[2])) }
            // Choosing a recipe for a meal: the plan, which already has such a choice; the test reads what was sent.
            parts.size == 6 && parts[1] == "plans" && parts[3] == "meals" -> {
                if (method == "PUT") changes += "PUT $path $sent"
                json.encodeToString(plan(parts[2]))
            }
            // Planning the week in one go, as a server from before the planning screen does it (issue 255): the week as it was.
            method == "POST" && parts.size == 4 && parts[1] == "plans" && parts[3] == "generate" -> {
                changes += "POST $path"
                json.encodeToString(plan(parts[2]))
            }
            parts.size == 4 && parts[1] == "plans" && parts[3] == "shopping" -> json.encodeToString(judged(shopping(parts[2])))
            path == "/api/offers" -> json.encodeToString(if (emptyOffers) OffersState(emptyList(), emptyList()) else offers)
            path == "/api/prices" -> json.encodeToString(if (emptyPrices) PricesState(emptyList(), emptyList(), null) else prices)
            (method == "PUT" || method == "DELETE") && path.startsWith("/api/weights/") -> changeWeights(method, path.substringAfterLast('/'), sent)
            path == "/api/weights" -> json.encodeToString(weights)
            path == "/api/ingredients" -> json.encodeToString(ingredients)
            path == "/api/pantry" -> json.encodeToString(pantry)
            // Never chosen: every store, as for an account that never opened the per-store list.
            path == "/api/shops" -> json.encodeToString(ShopsSetting())
            path == "/api/shopping-advice" -> json.encodeToString(ShoppingAdvice())
            else -> null
        }
    }

    private val targets = DailyTargets(2100, 130, 30)

    /** The profile the server holds; null for an account that has not saved one. A test may set it before the screen loads. */
    @Volatile
    var profile: Profile? = null

    /** `GET /api/profile`, and `PUT` which stores what was sent (the targets stay the fixed ones). */
    private fun profileJson(method: String, sent: String): String {
        if (method == "PUT") {
            profile = json.decodeFromString<Profile>(sent)
            changes += "PUT /api/profile $sent"
        }
        return json.encodeToString(ProfileState(profile, targets.takeIf { profile != null }, targets.takeIf { profile != null }, 2400.takeIf { profile != null }))
    }

    private fun ingredient(id: Long, name: String, staple: Boolean = false) = Ingredient(id, name, null, null, null, null, null, isStaple = staple)

    private val ingredients = listOf(
        ingredient(1, "Chicken thigh fillet"), ingredient(2, "Feta"), ingredient(3, "Greek yoghurt"),
        ingredient(4, "Minced beef"), ingredient(5, "Olive oil", staple = true), ingredient(6, "Rolled oats"),
        ingredient(7, "Salt", staple = true), ingredient(8, "Spinach"),
    )

    /**
     * Feta on hand by hand, how much not known; what is left of the chicken bought ahead last week and
     * of the yoghurt and oats stocked up; the minced beef bought ahead for next week, untouched. The
     * yoghurt was stocked up in two weeks.
     */
    private val pantry = Pantry(
        staples = ingredients.filter { it.isStaple },
        onHand = ingredients.filter { it.id in setOf(1L, 2L, 3L, 4L, 6L) },
        onHandGrams = mapOf(1L to 300.0, 3L to 650.0, 4L to 1000.0, 6L to 200.0),
        onHandFrom = mapOf(1L to "2026-11-09", 3L to "2026-11-16", 4L to "2026-11-23", 6L to "2026-11-16"),
        onHandWeeks = mapOf(1L to listOf("2026-11-09"), 3L to listOf("2026-11-16", "2026-11-23"), 4L to listOf("2026-11-23"), 6L to listOf("2026-11-16")),
    )

    private fun meal(day: Int, slot: MealSlot, id: Long, title: String, kcal: Double, protein: Double, fibre: Double, cost: Double = 2.4) =
        PlannedMeal(day, slot, id, title, 1.0, kcal, protein, cost = cost, fibreG = fibre)

    /** Three meals a day; a title is long on purpose, to show how rows cope. */
    private fun planDay(index: Int): PlanDay {
        val meals = listOf(
            meal(index, MealSlot.BREAKFAST, 1, "Overnight oats with blueberries", 460.0, 24.0, 8.2, cost = 1.1)
                .let { if (index == 2) it.copy(status = MealStatus.EATEN) else it },
            meal(index, MealSlot.LUNCH, 2L + index % 2, if (index % 2 == 0) "Chickpea salad with feta and roasted peppers" else "Tuna pasta salad", 640.0, 42.0, if (index % 2 == 0) 11.4 else 4.1),
            meal(index, MealSlot.DINNER, 4, "Slow-cooked chicken thighs with coconut, lemon and spinach", 810.0, 55.0, 5.3, cost = 3.9),
        ).map { meal ->
            when {
                // Wednesday's dinner got more chicken and an egg, and no spinach; Thursday's dinner is left out;
                // Friday's was chosen by the user, with Saturday's lunch its leftovers.
                index == 2 && meal.slot == MealSlot.DINNER -> meal.copy(kcal = 935.0, proteinG = 72.0, fibreG = 3.1, adjusted = true)
                index == 3 && meal.slot == MealSlot.DINNER -> meal.copy(status = MealStatus.SKIPPED)
                index == 4 && meal.slot == MealSlot.DINNER ->
                    meal.copy(recipeId = 5, title = "Lentil soup", locked = true, fibreG = 12.6, status = if (dinnerEaten) MealStatus.EATEN else MealStatus.PLANNED)
                index == 5 && meal.slot == MealSlot.LUNCH -> meal.copy(recipeId = 5, title = "Lentil soup", leftover = true, fibreG = 12.6)
                standardMeals && index == 2 && meal.slot == MealSlot.LUNCH -> meal.copy(status = MealStatus.SKIPPED)
                else -> meal
            }
        }.map { meal -> if (prepped) prepped(meal) else meal }.map { meal -> if (sides || mixed) withSide(meal) else meal }.map { meal ->
            // What the server says to cook: Friday's lentil soup also feeds Saturday's lunch, which cooks nothing; the prep's Monday dinner cooks the batch.
            meal.copy(cookPortions = when {
                meal.prep != null -> if (meal.leftover) 0.0 else meal.prep!!.portions
                meal.status == MealStatus.SKIPPED || meal.leftover -> 0.0
                index == 4 && meal.slot == MealSlot.DINNER -> 2.0
                else -> meal.portions
            })
        }
        // As the server counts them: each meal with its side.
        val counted = meals.filter { it.status != MealStatus.SKIPPED }
        return PlanDay(plusDays(WEEK_START, index), meals, counted.sumOf { it.totalKcal }, counted.sumOf { it.totalProteinG }, counted.sumOf { it.totalFibreG!! })
    }

    /**
     * The chickpea salad prepped as a side of half a serving: made today (Wednesday) with the dinner, which goes over the day's
     * calories with it, and eaten next to Thursday's lunch and Friday's lentil soup; of a [mixed] batch, Thursday's lunch is
     * a serving of the salad itself.
     */
    private val sidePrep = MealPrep(
        id = 2, madeOn = TODAY, lastDay = plusDays(TODAY, 3), vacuum = false,
        meals = listOf(2 to MealSlot.DINNER, 3 to MealSlot.LUNCH, 4 to MealSlot.DINNER).map { (day, slot) ->
            val asMeal = mixed && day == 3
            PrepMeal(plusDays(WEEK_START, day), slot, if (asMeal) 1.0 else 0.5, MealStatus.PLANNED, side = !asMeal)
        },
    )

    /** [meal] as the week of [sides] has it: with half a serving of the salad next to it, the salad itself for a [mixed] batch, or as it was. */
    private fun withSide(meal: PlannedMeal): PlannedMeal {
        val date = plusDays(WEEK_START, meal.day)
        val share = sidePrep.meals.firstOrNull { it.date == date && it.slot == meal.slot } ?: return meal
        if (!share.side) {
            return meal(meal.day, meal.slot, 2, "Chickpea salad with feta and roasted peppers", 640.0, 42.0, 11.4)
                .copy(leftover = true, locked = true, prep = sidePrep)
        }
        val prepDay = date == sidePrep.madeOn
        val side = MealSide(
            2, "Chickpea salad with feta and roasted peppers", 0.5, 320.0, 21.0, 5.7, cost = 1.2, cookPortions = if (prepDay) sidePrep.portions else 0.0, prep = sidePrep,
        )
        return meal.copy(side = side)
    }

    /**
     * The chicken thighs prepped: made on Monday's dinner and eaten on Tuesday's and Wednesday's lunches and Thursday's
     * dinner, in a box for three days. Wednesday's lunch was skipped, so its share is in the box ([spare]).
     */
    private val prep = MealPrep(
        id = 1, madeOn = WEEK_START, lastDay = plusDays(WEEK_START, 3), vacuum = vacuum,
        meals = listOf(0 to MealSlot.DINNER, 1 to MealSlot.LUNCH, 2 to MealSlot.LUNCH, 3 to MealSlot.DINNER).map { (day, slot) ->
            PrepMeal(plusDays(WEEK_START, day), slot, 1.0, if (day == 2) MealStatus.SKIPPED else MealStatus.PLANNED)
        },
    )

    private val spare = SparePortion(
        prepId = 1, recipeId = 4, title = "Slow-cooked chicken thighs with coconut, lemon and spinach", portions = 1.0,
        skipped = PrepSlot(plusDays(WEEK_START, 2), MealSlot.LUNCH), lastDay = prep.lastDay,
        room = listOf(PrepSlot(plusDays(WEEK_START, 2), MealSlot.DINNER), PrepSlot(plusDays(WEEK_START, 3), MealSlot.LUNCH)),
    )

    /** [meal] as the week of [prepped] has it: one of the prep's, or as it was. */
    private fun prepped(meal: PlannedMeal): PlannedMeal {
        val ofPrep = prep.meals.firstOrNull { it.date == plusDays(WEEK_START, meal.day) && it.slot == meal.slot } ?: return meal
        val chicken = meal(meal.day, meal.slot, 4, "Slow-cooked chicken thighs with coconut, lemon and spinach", 810.0, 55.0, 5.3, cost = 3.9)
        return chicken.copy(status = ofPrep.status, leftover = meal.day > 0, locked = true, prep = prep)
    }

    /** The chicken thighs prepped on the Sunday before the week, eaten then and at Monday's lunch: the week's only meal ([tailOnly]). */
    private val tailPrep = MealPrep(
        id = 3, madeOn = plusDays(WEEK_START, -1), lastDay = plusDays(WEEK_START, 1), vacuum = false,
        meals = listOf(PrepMeal(plusDays(WEEK_START, -1), MealSlot.DINNER, 1.5, MealStatus.EATEN), PrepMeal(WEEK_START, MealSlot.LUNCH, 1.25, MealStatus.PLANNED)),
    )

    /** The week of [tailOnly]: Monday's lunch from last week's batch, nothing else; as the server says, no breakfast, dinner or snack in it. */
    private fun tailWeek(weekStart: String) = WeekPlan(
        weekStart = weekStart,
        targets = targets,
        days = (0..6).map { day ->
            val meals = if (day == 0) {
                listOf(
                    meal(0, MealSlot.LUNCH, 4, "Slow-cooked chicken thighs with coconut, lemon and spinach", 1012.5, 68.75, 6.6, cost = 4.9)
                        .copy(portions = 1.25, leftover = true, locked = true, prep = tailPrep, cookPortions = 0.0),
                )
            } else {
                emptyList()
            }
            PlanDay(plusDays(weekStart, day), meals, meals.sumOf { it.kcal }, meals.sumOf { it.proteinG }, meals.sumOf { it.fibreG ?: 0.0 })
        },
        emptySlots = listOf(MealSlot.BREAKFAST, MealSlot.DINNER, MealSlot.SNACK),
    )

    /** The batch of [growing]: today's dinner makes it and it keeps three days, with no side of it in the week yet. */
    private val growingPrep = MealPrep(
        id = 2, madeOn = TODAY, lastDay = plusDays(TODAY, 3), vacuum = false,
        meals = listOf(PrepMeal(TODAY, MealSlot.DINNER, 1.0, MealStatus.PLANNED)),
    )

    private fun plan(weekStart: String) = if (tailOnly) tailWeek(weekStart) else WeekPlan(
        weekStart = weekStart,
        targets = targets,
        days = (0..6).map { if (emptyWeek) PlanDay(plusDays(weekStart, it), emptyList(), 0.0, 0.0, 0.0) else planDay(it) },
        emptySlots = if (emptyWeek) MealSlot.entries else emptyList(),
        batchesToMake = if (growing) listOf(BatchToMake(2, "Chickpea salad with feta and roasted peppers", growingPrep)) else emptyList(),
        dinnersChosen = if (planning) picks.size else 0,
        cost = if (emptyWeek) null else 61.40,
        costWithoutOffers = if (emptyWeek) null else 68.95,
        rest = if (emptyWeek || !weekRest) emptyList() else listOf(
            WeekRest("Crème fraîche", 100.0, 1.10), WeekRest("Koriander", 12.0, 0.60), WeekRest("Bieslook", 8.0, 0.40),
            WeekRest("Gember", 25.0, 0.20), WeekRest("Peterselie", 10.0, 0.10),
        ),
    )

    /** The standard meals of [standardMeals] for each of their slots, sized as the server would; the eaten breakfast takes none. */
    private fun standardOffer(weekStart: String) = StandardMeals(
        weekStart,
        meals = listOf(MealSlot.LUNCH, MealSlot.DINNER).flatMap { slot ->
            listOf(
                StandardMeal(3, "Tuna pasta salad", slot, 1.0, 610.0, 38.0, 4.0),
                StandardMeal(6, "Shakshuka", slot, 1.25, 700.0, 36.0, 6.0, missing = listOf("Eggs")),
            )
        },
        // With [prepped], Monday's dinner makes the batch, which takes no standard meal, skipped or not.
        closed = listOfNotNull(
            ClosedSlot(2, MealSlot.BREAKFAST, "This meal was eaten already"),
            ClosedSlot(0, MealSlot.DINNER, "This meal makes the prepped batch, which stays in the plan with it").takeIf { prepped },
        ),
    )

    private fun day(date: String): DayView {
        val planned = plan(mondayOf(date)).days.first { it.date == date }
        val extras = listOf(LogEntry(1, date, "Apple and a handful of almonds", 210.0, 5.0))
        return DayView(
            date = date,
            weekStart = mondayOf(date),
            dayIndex = daysBetween(mondayOf(date), date),
            targets = targets,
            meals = planned.meals,
            extras = extras,
            eatenKcal = 460.0 + 210.0,
            eatenProteinG = 29.0,
            expectedKcal = planned.kcal + 210.0,
            expectedProteinG = planned.proteinG + 5.0,
            // The breakfast was eaten; the extra has no fibre.
            eatenFibreG = 8.2,
            expectedFibreG = planned.fibreG,
        )
    }

    /** What the meal's changes are now; a test reads them, and every `PUT .../lines` replaces them. */
    @Volatile
    var lineChanges: List<MealLineChange> = listOf(MealLineChange(1, 250.0), MealLineChange(8, 0.0), MealLineChange(9, 60.0))

    /**
     * The chicken dinner with what was in it: the recipe's lines for one serving, one of them linked to no
     * ingredient, with [lineChanges] applied. An egg is not in the catalogue the screens list, so that the
     * pantry's picture stays as it is; its line counts it in pieces.
     */
    private fun mealLines(method: String, sent: String): MealLines {
        if (method == "PUT") {
            lineChanges = json.decodeFromString<MealAdjustment>(sent).lines
            changes += "PUT lines $sent"
        }
        val changed = lineChanges.associate { it.ingredientId to it.grams }
        val recipe = listOf(MealLine(1, "Chicken thigh fillet", 200.0, 200.0), MealLine(8, "Spinach", 75.0, 75.0), MealLine(5, "Olive oil", 10.0, 10.0))
            .map { line -> changed[line.ingredientId]?.let { line.copy(grams = it, changed = true) } ?: line } +
            MealLine(null, "Coconut milk", 100.0)
        val added = lineChanges.filter { change -> recipe.none { it.ingredientId == change.ingredientId } }.map { change ->
            val ingredient = names.getValue(change.ingredientId)
            MealLine(ingredient.id, ingredient.name, change.grams, fromRecipe = false, changed = true, gramsPerPiece = ingredient.gramsPerPiece)
        }
        val adjusted = lineChanges.isNotEmpty()
        // More chicken than the recipe's takes what was bought for Saturday's dinner of the same recipe.
        val shortfalls = changed[1]?.takeIf { it > 200.0 }?.let {
            listOf(LaterShortfall(5, MealSlot.DINNER, "Slow-cooked chicken thighs with coconut, lemon and spinach", 1, "Chicken thigh fillet", it - 200.0))
        }.orEmpty()
        return MealLines(
            WEEK_START, 2, MealSlot.DINNER, 4, "Slow-cooked chicken thighs with coconut, lemon and spinach", 1.0, recipe + added,
            kcal = if (adjusted) 935.0 else 810.0, proteinG = if (adjusted) 72.0 else 55.0, fibreG = if (adjusted) 3.1 else 5.3,
            complete = false, uncounted = listOf("Coconut milk"), adjusted = adjusted, shortfalls = shortfalls,
        )
    }

    /** The catalogue's ingredients by id, with an egg the screens' catalogue does not list (so the pantry's picture stays as it is). */
    private val names = (ingredients + Ingredient(9, "Egg", null, 60.0, null, null, null, isStaple = false)).associateBy { it.id }

    /** Each recipe's toppings; the chicken dinner's are feta and an egg. A test reads them, and `PUT .../toppings` replaces them. */
    @Volatile
    var toppings: Map<Long, List<RecipeTopping>> = mapOf(4L to listOf(RecipeTopping(2, "Feta", 30.0), RecipeTopping(9, "Egg", 60.0, 60.0)))

    /** A recipe's quick add-ons: its toppings, then Greek yoghurt, added to three of its meals before. */
    private fun addOns(recipeId: Long): List<AddOn> =
        toppings[recipeId].orEmpty().map { AddOn(it.ingredientId, it.name, it.grams, it.gramsPerPiece, topping = true) } +
            AddOn(3, "Greek yoghurt", 50.0, times = 3)

    private fun shopping(weekStart: String): ShoppingList {
        if (emptyWeek) return ShoppingList(weekStart, emptyList(), emptyList())
        // The week's only meal is of last week's batch, which last week's list bought.
        if (tailOnly) return ShoppingList(weekStart, emptyList(), emptyList(), preppedBoughtIn = plusDays(WEEK_START, -7))
        // In walking order, as the server sends it: by shop section, then by name. The regular price is the
        // amount needed at the price per kg of `prices`; the pepper, counted in pieces only, has none.
        fun item(key: String, name: String, grams: Double?, pieces: Double?, section: ShopSection, recipe: String?, checked: Boolean = false, offers: List<Offer> = emptyList(), imageUrl: String? = null, regularPrice: Double? = null, knownGrams: Double? = null, otherAmounts: List<String> = emptyList(), boughtGrams: Double? = null, onHand: Boolean = false, onHandGrams: Double? = null, toComeGrams: Double? = null) =
            ShoppingItem(key, name, grams, pieces, section.title, listOfNotNull(recipe), onHand = onHand, checked = checked, knownGrams = knownGrams, otherAmounts = otherAmounts, offers = offers, imageUrl = imageUrl, regularPrice = regularPrice, boughtGrams = boughtGrams, onHandGrams = onHandGrams, toComeGrams = toComeGrams)
        // Offers that end this week, for the advice: with their share already worked out, as the server sends them.
        fun deal(store: Store, title: String, deal: String, price: Double, paidShare: Double) =
            Offer(store, title, deal, price, null, null, null, "2026-11-16", "2026-11-22", paidShare = paidShare)
        val chicken = "Slow-cooked chicken thighs with coconut, lemon and spinach"
        val chickpea = "Chickpea salad with feta and roasted peppers"
        return ShoppingList(
            weekStart = weekStart,
            items = listOf(
                item("pepper", "Red bell pepper", null, 4.0, ShopSection.FRUIT_VEG, chickpea, offers = listOf(offerList[0].copy(paidShare = 0.75)), imageUrl = offerList[0].imageUrl),
                // Weighed in one recipe and counted in bags in another: the weight is known only in part.
                // Two whole plants are counted in a third, next to the bag.
                item("spinach", "Spinach", null, 3.0, ShopSection.FRUIT_VEG, chicken, knownGrams = 600.0, otherAmounts = listOf("1 bag"))
                    .copy(ownPieces = 2.0),
                item(
                    // Bought for the 800 g a reroll needed; the meals need 1.4 kg now: 600 g more. 300 g
                    // bought ahead last week is in the freezer: counted in when the row said 800 g, so the
                    // ticked row doesn't name it again.
                    "chicken", "Chicken thigh fillet", 1400.0, null, ShopSection.MEAT, chicken, checked = true,
                    offers = listOf(offerList[1].copy(paidShare = 0.75)), imageUrl = offerList[1].imageUrl, regularPrice = 13.97, boughtGrams = 800.0,
                    onHand = true, onHandGrams = 300.0, toComeGrams = 1400.0,
                ),
                // On hand by hand, so how much is not known.
                item(
                    "feta", "Feta", 400.0, null, ShopSection.DAIRY, chickpea, offers = listOf(offerList[2].copy(paidShare = 0.5)), imageUrl = offerList[2].imageUrl,
                    regularPrice = 4.60, onHand = true, toComeGrams = 400.0,
                ),
                // Lidl's second week starts on Thursday, two days after the fixed "today": a badge with its day, nothing in the saving.
                item(
                    "yoghurt", "Greek yoghurt", 500.0, null, ShopSection.DAIRY, chickpea,
                    offers = listOf(Offer(Store.LIDL, "Griekse yoghurt 500 g", "voor 1,19", 1.19, 1.79, "500 g", null, "2026-11-19", "2026-11-25", paidShare = 0.66)), regularPrice = 1.79,
                    // Stocked up on earlier: more than the meals need, so nothing to buy: in a group of its own per store.
                    onHand = true, onHandGrams = 650.0, toComeGrams = 500.0,
                ),
                // What is left of a stock-up bag covers part of it. Wednesday's breakfast was eaten from it
                // (50 g): the six still to come need 300 g, the amount shown, so 100 g is still to buy.
                // The eaten breakfast is no longer named as its source: only the meals still to cook are.
                item(
                    "oats", "Rolled oats", 350.0, null, ShopSection.BREAKFAST, "Overnight oats with blueberries",
                    offers = listOf(offerList[3].copy(paidShare = 0.79, ingredientName = "Rolled oats")), regularPrice = 0.76, onHand = true, onHandGrams = 200.0, toComeGrams = 300.0,
                ).let { it.copy(recipes = listOf("Porridge with banana") + it.recipes, toComeRecipes = listOf("Overnight oats with blueberries")) },
                // Own items, typed by hand: one the server filed under a section, one it could not place.
                item("x:1", "Coffee", null, null, ShopSection.DRINKS, null),
                item("x:2", "Birthday candles", null, null, ShopSection.OTHER, null),
            ),
            // Bought, then the meal that needed it was swapped out.
            noLongerNeeded = listOf(item("i31", "Broccoli", null, null, ShopSection.FRUIT_VEG, null, checked = true, boughtGrams = 500.0)),
            staples = listOf("Olive oil", "Salt"),
            toTaste = listOf("Black pepper", "Fresh parsley"),
            // Two meals of next week, provisional: one item for both, one for one; the second is ticked.
            buyAhead = listOf(
                item(
                    "ahead:i21", "Minced beef", 1000.0, null, ShopSection.MEAT, "Beef chilli with beans",
                    offers = listOf(deal(Store.JUMBO, "Rundergehakt 500 g", "2 voor 7,00", 3.50, 0.6)), regularPrice = 11.98,
                ).let { it.copy(recipes = it.recipes + "Lasagne") },
                item(
                    "ahead:i22", "Frozen spinach", 450.0, null, ShopSection.FROZEN, "Lasagne", checked = true,
                    offers = listOf(deal(Store.AH, "AH Spinazie diepvries 450 g", "25% korting", 1.12, 0.75)), regularPrice = 1.49,
                ),
            ),
            buyAheadProvisional = true,
            stockUp = listOf(
                item("stock:i23", "Rice", 1000.0, null, ShopSection.WORLD, null, offers = listOf(deal(Store.LIDL, "Basmati rijst 1 kg", "1+1 gratis", 2.29, 0.5)), regularPrice = 2.29),
                item("stock:i24", "Chopped tomatoes", 400.0, null, ShopSection.TINS, null, offers = listOf(deal(Store.AH, "AH Tomatenblokjes 400 g", "2e halve prijs", 0.62, 0.75)), regularPrice = 0.69),
            ),
        )
    }

    private fun summary(
        id: Long,
        title: String,
        section: String,
        kcal: Double,
        protein: Double,
        rating: Int?,
        tags: List<String> = emptyList(),
        photo: Boolean = false,
        inRotation: Boolean = true,
        added: Boolean = true,
        removedFromSite: Boolean = false,
        /** Ingredients left out of the numbers: the recipe is incomplete until they are linked. */
        uncounted: List<String> = emptyList(),
        source: RecipeSource = RecipeSource.COOKING_SITE,
        kind: RecipeKind = RecipeKind.MEAL,
        /** Days it keeps once made, in a box and vacuum sealed (issue 234). */
        keeps: Pair<Int?, Int?> = null to null,
    ) = RecipeSummary(
        id = id,
        title = title,
        section = section,
        tags = tags,
        servings = 2,
        source = source,
        kind = kind,
        plannable = true,
        nutrition = RecipeNutrition(
            Nutrition(kcal, protein, 22.0, 6.0, 48.0, 7.0, 9.0, 620.0),
            complete = uncounted.isEmpty(), approximate = false, uncounted = uncounted,
        ),
        mealSlots = if (kind == RecipeKind.MEAL) listOf(MealSlot.LUNCH, MealSlot.DINNER) else emptyList(),
        rating = rating,
        cost = RecipeCost(2.40, 2.10),
        imageUrl = if (photo) "/api/recipes/$id/image?v=0a1b2c" else null,
        added = added,
        inRotation = inRotation,
        removedFromSite = removedFromSite,
        keepsDays = keeps.first,
        keepsVacuumDays = keeps.second,
    )

    /**
     * Every recipe the account has: the first seven are mine, the rest wait in the bank. Of mine, the tuna salad
     * and the shakshuka are out of the rotation (the shakshuka's file has left the cooking site), the lentil soup
     * is in it with a ingredient not linked yet, and the peanut sauce is a sauce, not a meal.
     */
    private val rows = mutableListOf(
        // Two recipes have a photo, like the real ones where a few do; the others show how the screens look without.
        summary(1, "Overnight oats with blueberries", "Breakfast", 460.0, 24.0, 4, listOf("quick", "vegetarian"), photo = true),
        summary(2, "Chickpea salad with feta and roasted peppers", "Lunch", 640.0, 42.0, 5, listOf("vegetarian")),
        summary(3, "Tuna pasta salad", "Lunch", 610.0, 38.0, null, inRotation = false),
        summary(4, "Slow-cooked chicken thighs with coconut, lemon and spinach", "Dinner", 810.0, 55.0, 5, listOf("meal prep", "gluten free"), photo = true, keeps = 3 to 6),
        summary(5, "Lentil soup", "Dinner", 520.0, 31.0, 3, uncounted = listOf("red lentils")),
        summary(6, "Shakshuka", "Dinner", 560.0, 29.0, 4, listOf("vegetarian", "eggs"), inRotation = false, removedFromSite = true),
        summary(7, "Peanut sauce", "", 180.0, 6.0, null, source = RecipeSource.MANUAL, kind = RecipeKind.COMPONENT),
        summary(8, "Miso aubergine with sesame rice", "Dinner", 590.0, 18.0, null, listOf("vegan"), added = false, inRotation = false),
        summary(9, "Greek yoghurt pancakes", "Breakfast", 430.0, 28.0, null, listOf("quick", "vegetarian"), photo = true, added = false, inRotation = false),
        summary(10, "Harissa chickpea stew", "Dinner", 540.0, 22.0, null, listOf("vegan", "spicy"), added = false, inRotation = false, uncounted = listOf("harissa", "preserved lemon")),
        summary(11, "Soba noodle salad", "Lunch", 480.0, 20.0, null, listOf("vegan"), added = false, inRotation = false),
        summary(12, "Banana oat cookies", "Snack", 210.0, 5.0, null, listOf("vegan"), added = false, inRotation = false),
    )

    private fun mine() = rows.filter { it.added }

    private fun bank() = rows.filter { !it.added }

    /** The recipes that are planned in the sample week: the server refuses to put them back. */
    private val planned = setOf(2L, 4L, 5L)

    /** The recipe pinned as the breakfast routine (see `/api/routines`): it can neither leave the rotation nor my recipes. */
    private val routine = 1L

    private fun row(id: Long) = rows.first { it.id == id }

    private val flags = setOf("added", "inRotation", "removedFromSite")

    /** [this] as an older server sends recipes: without the fields of mine, the rotation and the site. */
    private fun JsonElement.withoutFlags(): JsonElement = when (this) {
        is JsonArray -> JsonArray(map { it.withoutFlags() })
        // A recipe's detail holds its summary; a summary is the object with the flags.
        is JsonObject -> when {
            "summary" in this -> JsonObject(mapValues { (key, value) -> if (key == "summary") value.withoutFlags() else value })
            else -> JsonObject(filterKeys { it !in flags })
        }
        else -> this
    }

    private fun recipesJson(list: List<RecipeSummary>): String =
        json.encodeToJsonElement(list).let { if (olderServer) it.withoutFlags() else it }.toString()

    private fun detailJson(id: Long): String =
        json.encodeToJsonElement(recipeDetail(id)).let { if (olderServer) it.withoutFlags() else it }.toString()

    /**
     * The changes of recipes the app can make, answering as the server does, refusals included: a recipe
     * that is planned or the routine cannot be put back (409), nor can one made in the app, which is
     * deleted instead. [refusing] makes every change fail with its sentence. Many at once is all or nothing.
     */
    private fun changeRecipes(method: String, parts: List<String>, sent: String): String? {
        changes += "$method /${parts.joinToString("/")} $sent"
        if (method == "DELETE" && parts.size == 3) {
            rows.removeAll { it.id == parts[2].toLong() }
            return ""
        }
        // A recipe made or edited in the editor: the answer is always the sample recipe, which the screen only passes on.
        if (method == "POST" || (method == "PUT" && parts.size == 3 && parts[2].toLongOrNull() != null)) return detailJson(parts.getOrNull(2)?.toLong() ?: 4)
        if (olderServer || method != "PUT") return null
        refusing?.let { throw Refusal(409, it) }
        val many = parts.size == 3
        return when (parts.last()) {
            "added" -> if (many) {
                val input = json.decodeFromString<ManyAddedInput>(sent)
                setAdded(input.ids, input.added, input.inRotation)
                recipesJson(mine())
            } else {
                val input = json.decodeFromString<AddedInput>(sent)
                val id = parts[2].toLong()
                setAdded(listOf(id), input.added, input.inRotation)
                detailJson(id)
            }
            "toppings" -> {
                val id = parts[2].toLong()
                toppings = toppings + (id to json.decodeFromString<ToppingsInput>(sent).toppings.map { input ->
                    val ingredient = names.getValue(input.ingredientId)
                    RecipeTopping(ingredient.id, ingredient.name, input.grams, ingredient.gramsPerPiece)
                })
                detailJson(id)
            }
            "keeps" -> {
                keepsAnswer?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                val id = parts[2].toLong()
                keepElsewhere(id, json.decodeFromString<RecipeKeeps>(sent))
                detailJson(id)
            }
            // Marking a standard meal makes its ingredients staples; one line is linked to nothing.
            "standard" -> {
                val id = parts[2].toLong()
                val standard = json.decodeFromString<StandardInput>(sent).standard
                rows.replaceAll { if (it.id == id) it.copy(standardMeal = standard) else it }
                val switch = if (standard) StandardSwitch(recipeDetail(id), listOf("Chicken thigh fillet", "Spinach"), listOf("juiced")) else StandardSwitch(recipeDetail(id))
                json.encodeToString(switch)
            }
            "rotation" -> if (many) {
                val input = json.decodeFromString<ManyRotationInput>(sent)
                setRotation(input.ids, input.inRotation)
                recipesJson(mine())
            } else {
                val id = parts[2].toLong()
                setRotation(listOf(id), json.decodeFromString<RotationInput>(sent).inRotation)
                detailJson(id)
            }
            else -> null
        }
    }

    private fun setAdded(ids: List<Long>, added: Boolean, inRotation: Boolean) {
        val targets = ids.map(::row)
        if (!added) {
            targets.forEach { recipe ->
                when {
                    recipe.source != RecipeSource.COOKING_SITE ->
                        throw Refusal(409, "“${recipe.title}” was made in the app, so it has no bank to go back to: delete it instead")
                    recipe.id == routine -> throw Refusal(409, "“${recipe.title}” is the breakfast routine, so it cannot go back to the bank")
                    recipe.id in planned ->
                        throw Refusal(409, "“${recipe.title}” is planned on Fri 20 Nov, so it cannot go back to the bank yet")
                }
            }
        }
        rows.replaceAll { if (it.id !in ids) it else if (added) it.copy(added = true, inRotation = inRotation) else it.copy(added = false, inRotation = false) }
    }

    private fun setRotation(ids: List<Long>, inRotation: Boolean) {
        val targets = ids.map(::row)
        if (!inRotation) {
            targets.firstOrNull { it.id == routine }?.let { throw Refusal(409, "“${it.title}” is the breakfast routine, so it cannot leave the rotation") }
        }
        rows.replaceAll { if (it.id in ids) it.copy(inRotation = inRotation) else it }
    }

    private fun recipeDetail(id: Long) = RecipeDetail(
        summary = row(id),
        ingredients = listOf(
            line(700.0, "g", Dimension.WEIGHT, "chicken thigh fillet"),
            line(400.0, "ml", Dimension.VOLUME, "coconut milk"),
            line(300.0, "g", Dimension.WEIGHT, "spinach"),
            line(1.0, "lemon", Dimension.COUNT, "juiced"),
            line(null, null, null, "salt and pepper to taste"),
        ),
        toppings = toppings[id].orEmpty(),
        instructions = "Season the chicken and brown it in a heavy pan.\n\n" +
            "1. Add the coconut milk and the lemon juice.\n2. Cover and cook gently for 40 minutes.\n3. Stir in the spinach until it wilts.\n\n" +
            "Serve with rice or flatbread.",
        problems = emptyList(),
    )

    private fun line(amount: Double?, unit: String?, dimension: Dimension?, item: String) = MappedLine(
        IngredientLine(amount, unit, dimension, if (dimension == Dimension.COUNT) null else amount, item, null),
        ingredientId = 1,
        ingredientName = item.substringBefore(" to taste"),
        grams = amount.takeIf { dimension != Dimension.COUNT },
    )

    private val offerList = listOf(
        Offer(Store.AH, "Rode paprika 500 g", "25% korting", 2.24, 2.99, "500 g", "Groente", "2026-11-16", "2026-11-22", ingredientName = "Red bell pepper", imageUrl = "https://static.ah.nl/dam/product/paprika.webp"),
        Offer(Store.JUMBO, "Kipdijfilet 600 g", "2 voor 9,00", 4.50, 5.99, "600 g", "Vlees", "2026-11-16", "2026-11-22", ingredientName = "Chicken thigh fillet", imageUrl = "https://www.jumbo.com/INTERSHOP/static/kip.png"),
        Offer(Store.LIDL, "Griekse feta 200 g", "1+1 gratis", 1.79, null, "200 g", "Zuivel", "2026-11-16", "2026-11-22", ingredientName = "Feta", imageUrl = "https://www.lidl.nl/assets/feta.png"),
        Offer(Store.AH, "Bio havermout met een heel lange productnaam voor de test", "voor 1,49", 1.49, 1.89, "500 g", null, "2026-11-16", "2026-11-22"),
    )

    /**
     * What the server's price history says of an offer valid today ([Offer.verdict]), by its title. Early on, two days
     * into the history, every one is too new to judge. Later the pepper is a real deal; the chicken usually cheaper, its
     * kilo pack cheapest per kg before; the feta better than usual next to a Lidl price; the oats always on offer; the
     * minced beef no real discount after a raise; the rice cheaper another way; the rest the usual deal. The oats are no
     * ingredient on the Offers screen, so not judged there.
     */
    private fun verdictOf(offer: Offer): PriceVerdict? {
        if (offer.validFrom > TODAY || offer.validUntil < TODAY) return null
        if (!laterVerdicts) return PriceVerdict(VerdictKind.NOT_ENOUGH_HISTORY, daysRead = 2, daysNeeded = 56)
        fun past(from: String, until: String, deal: String?, value: Double, price: Double? = null, endedEarly: Boolean = false) =
            PastOffer(from, until, deal, price, value = value, endedEarly = endedEarly)
        val known = PriceVerdict(daysRead = 70, daysNeeded = 56)
        return when (offer.title) {
            "Rode paprika 500 g" -> known.copy(
                verdict = VerdictKind.REAL_DEAL, measure = VerdictMeasure.PER_KG, value = 4.48, typical = 4.99,
                earlier = listOf(
                    past("2026-11-02", "2026-11-08", "voor 2,49", 4.98),
                    past("2026-10-12", "2026-10-18", "2 voor 5,00", 5.00),
                    past("2026-09-21", "2026-09-27", "25% korting", 4.98),
                ),
            )
            "Kipdijfilet 600 g" -> known.copy(
                verdict = VerdictKind.WORSE_THAN_USUAL, measure = VerdictMeasure.PER_KG, value = 7.50, typical = 6.65,
                lastAsCheap = past("2026-11-02", "2026-11-08", "1+1 gratis", 4.99),
                cheaperPack = CheaperPack(PastOffer("2026-10-19", "2026-10-25", "voor 5,99", 5.99, "1 kg", 5.99), VerdictMeasure.PER_KG, now = false),
                earlier = listOf(
                    past("2026-11-02", "2026-11-08", "1+1 gratis", 4.99),
                    past("2026-10-19", "2026-10-25", "2 voor 8,00", 6.65, endedEarly = true),
                    past("2026-09-28", "2026-10-04", "25% korting", 7.49),
                ),
            )
            "Griekse feta 200 g" -> known.copy(
                verdict = VerdictKind.USUAL_DEAL, measure = VerdictMeasure.PER_KG, value = 4.48, typical = 5.20, betterThanUsual = true,
                lastAsCheap = past("2026-10-19", "2026-10-25", "1+1 gratis", 4.20),
                alongside = listOf(PastOffer("2026-11-16", "2026-11-22", null, 2.29, "400 g")),
            )
            "Bio havermout met een heel lange productnaam voor de test" -> known.copy(verdict = VerdictKind.ALWAYS_ON_OFFER).takeIf { offer.ingredientName != null }
            "Rundergehakt 500 g" -> known.copy(verdict = VerdictKind.NOT_A_DEAL, measure = VerdictMeasure.PAID_SHARE, value = 0.98, raise = PriceRaise(3.29, 3.69))
            "Basmati rijst 1 kg" -> known.copy(
                verdict = VerdictKind.CHEAPER_ANOTHER_WAY, measure = VerdictMeasure.PER_KG, value = 1.15,
                cheaperNow = PastOffer("2026-11-16", "2026-11-22", "2 voor 1,80", value = 0.90),
            )
            else -> known.copy(verdict = VerdictKind.USUAL_DEAL, measure = VerdictMeasure.PAID_SHARE, value = 0.75, typical = 0.75)
        }
    }

    private fun Offer.judged() = copy(verdict = verdictOf(this))

    /** [list] as the server sends it: its offers with their verdicts, wherever they are on it. */
    private fun judged(list: ShoppingList): ShoppingList {
        fun List<ShoppingItem>.judged() = map { item -> item.copy(offers = item.offers.map { it.judged() }) }
        return list.copy(items = list.items.judged(), buyAhead = list.buyAhead.judged(), stockUp = list.stockUp.judged(), noLongerNeeded = list.noLongerNeeded.judged())
    }

    private val offers = OffersState(
        offers = offerList.map { it.judged() },
        sources = listOf(
            OfferSourceStatus(Store.AH, "2026-11-17T06:00:00Z", 2, null),
            OfferSourceStatus(Store.JUMBO, "2026-11-17T06:00:00Z", 1, null),
            OfferSourceStatus(
                Store.LIDL, "2026-11-17T06:00:00Z", 1, null,
                warning = "No Lidl offer runs into next week (the last ends 2026-11-22); the offers page may not show next week's offers.",
            ),
        ),
    )

    private val prices = PricesState(
        prices = listOf(
            IngredientPrice(1, "Chicken thigh fillet", Store.AH, "AH Kipdijfilet", "600 g", 5.99, 9.98),
            IngredientPrice(2, "Spinach", Store.AH, "AH Verse spinazie", "300 g", 1.99, 6.63),
            IngredientPrice(3, "Feta", Store.AH, "Set by hand", null, null, 11.50),
            IngredientPrice(4, "Rolled oats", Store.AH, "AH Havermout", "500 g", 1.09, 2.18),
        ),
        unpriced = listOf("Harissa", "Smoked paprika"),
        fetchedAt = "2026-11-17T06:00:00Z",
    )

    /** The weight log the server holds; a test may set it before the screen loads. */
    @Volatile
    var weights = WeightLog(
        entries = (0..9).map { WeightEntry(plusDays("2026-11-09", it), 82.4 - it * 0.08 + (it % 3) * 0.15, 82.4 - it * 0.09) },
        actualKgPerWeek = 0.2,
        suggestion = TargetSuggestion(2100, 1950, 0.2, 0.5),
    )

    /**
     * `PUT` and `DELETE /api/weights/{date}`: the log with that day set or removed (a trend of its own is
     * not worked out: the reading stands for it), and the profile's weight following the newest weigh-in,
     * as on the server. Returns the log.
     */
    private fun changeWeights(method: String, date: String, sent: String): String {
        changes += "$method /api/weights/$date $sent"
        val others = weights.entries.filter { it.date != date }
        val entries = if (method == "PUT") (others + sent.trim().toDouble().let { WeightEntry(date, it, it) }).sortedBy { it.date } else others
        weights = weights.copy(entries = entries)
        entries.lastOrNull()?.let { newest -> profile = profile?.copy(weightKg = newest.kg) }
        return json.encodeToString(weights)
    }


    // --- The planning screen (issue 255), with [planning]

    /** The recipes chosen for the week's dinners, in the order they were, and those hidden for it. */
    val picks: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf(5L, 6L))
    private val hidden: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf(3L))

    /** While set, Done answers 409 with this sentence, as the server does when a chosen dinner can no longer be planned. */
    @Volatile
    var doneRefusal: String? = null

    /** False for an account with no dinner to suggest ([nothingToSuggest]); a test may switch it. */
    @Volatile
    var suggesting = !nothingToSuggest

    private fun suggestion(id: Long, title: String, vararg grounds: SuggestionGround, kcal: Double = 640.0, protein: Double = 42.0, price: Double = 2.35, saving: Double = 0.0) =
        DinnerSuggestion(id, title, grounds.toList(), 1.25, kcal * 1.25, protein * 1.25, 7.5, price, saving)

    /** What the server suggests, in the mixed order it sends them: on offer, what is there, favourites and recipes not tried yet, in turn. */
    private val suggestions = listOf(
        suggestion(21, "Gochujang chicken thighs with rice and quick-pickled cucumber", SuggestionGround.ON_OFFER, saving = 0.85)
            .copy(offers = listOf(SuggestionOffer("Chicken thigh fillet", Store.AH, "AH Kippendijfilet", "25% korting", 0.85))),
        suggestion(22, "Spinach and feta pie", SuggestionGround.USES_WHAT_IS_THERE).copy(onHand = listOf("Feta")),
        suggestion(2, "Chickpea salad with feta and roasted peppers", SuggestionGround.FAVOURITE).copy(rating = 5, weeksSinceEaten = 4),
        suggestion(24, "Miso salmon traybake", SuggestionGround.NOT_TRIED_YET, price = 4.10),
        suggestion(25, "Beef chilli", SuggestionGround.ON_OFFER, saving = 0.60)
            .copy(offers = listOf(SuggestionOffer("Minced beef", Store.JUMBO, "Jumbo Rundergehakt 500 g", "2 voor 7,50", 0.60))),
        suggestion(26, "Pasta with spinach and lemon", SuggestionGround.USES_WHAT_IS_THERE).copy(packRests = listOf(PackRestUse("Spinach", "Lentil soup"))),
        suggestion(27, "Thai green curry", SuggestionGround.FAVOURITE).copy(rating = 4),
        suggestion(28, "Fish tacos", SuggestionGround.NOT_TRIED_YET),
        suggestion(29, "Pork tenderloin with apples", SuggestionGround.ON_OFFER, SuggestionGround.NOT_TRIED_YET, saving = 1.10)
            .copy(offers = listOf(SuggestionOffer("Pork tenderloin", Store.LIDL, "Varkenshaas", null, 1.10))),
        suggestion(30, "Mushroom risotto", SuggestionGround.FAVOURITE).copy(weeksSinceEaten = 6),
    )

    private fun titleOf(id: Long) = suggestions.firstOrNull { it.recipeId == id }?.title ?: row(id).title

    /**
     * The week's planning: the open days (Monday and Tuesday have passed, Thursday's dinner is skipped and Friday's locked,
     * so Wednesday, Saturday and Sunday are left), the choices and the strip.
     */
    private fun planningOf(weekStart: String): WeekPlanning {
        val chosen = picks.toList().map { id -> DinnerPick(id, titleOf(id), 1.25, 812.5, 51.0, 9.0, 3.40, 3.90) }
        return WeekPlanning(
            weekStart, targets, openDays = listOf(2, 5, 6), picks = chosen,
            notThisWeek = hidden.toList().map { HiddenRecipe(it, titleOf(it)) },
            totals = PlanningTotals(
                meals = 3 + 2 * chosen.size, cost = 7.80 + chosen.sumOf { 2 * it.cost!! }, costWithoutOffers = 8.40 + chosen.sumOf { 2 * it.costWithoutOffers!! },
                kcal = 2080.0 + chosen.sumOf { 2 * it.kcal }, proteinG = 121.0 + chosen.sumOf { 2 * it.proteinG }, fibreG = 26.0 + chosen.sumOf { 2 * it.fibreG!! },
                targetKcal = 1890.0 + 1428.0 * chosen.size, targetProteinG = 117.0 + 88.4 * chosen.size, targetFibreG = 27.0 + 20.4 * chosen.size,
            ),
        )
    }

    /** The next suggestions, as the server pages them; a search finds every meal of mine whose title has each word. */
    private fun suggestionsOf(query: Map<String, String>): DinnerSuggestions {
        val count = query["count"]?.toInt() ?: 6
        val seen = query["seen"].orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
        val words = query["q"]?.lowercase()?.split(' ')?.filter { it.isNotEmpty() }
        val pool = if (words == null) {
            suggestions.filter { suggesting && it.recipeId !in picks && it.recipeId !in hidden }
        } else {
            val meals = mine().filter { it.kind == RecipeKind.MEAL && suggestions.none { s -> s.recipeId == it.id } }
                .map { DinnerSuggestion(it.id, it.title, emptyList(), 1.25, 760.0, 45.0, 6.0, 2.40, 0.30) }
            (suggestions + meals).filter { s -> words.all { it in s.title.lowercase() } }
        }.filter { it.recipeId !in seen }
        return DinnerSuggestions(pool.take(count), pool.size > count)
    }

    private fun planningRoute(method: String, path: String, sent: String, query: Map<String, String>): String? {
        if (!planning) return null
        val parts = path.trim('/').split('/')
        val week = parts[2]
        if (parts.last() == "move") {
            changes += "POST $path $sent"
            return json.encodeToString(plan(week))
        }
        return when {
            method == "GET" && parts.size == 4 -> json.encodeToString(planningOf(week))
            method == "GET" && parts.size == 5 && parts[4] == "suggestions" -> json.encodeToString(suggestionsOf(query))
            method == "POST" && parts.size == 5 && parts[4] == "done" -> {
                doneRefusal?.let { throw Refusal(409, it) }
                changes += "POST $path"
                picks.clear()
                json.encodeToString(plan(week))
            }
            parts.size == 6 && (parts[4] == "picks" || parts[4] == "not-this-week") -> {
                changes += "$method $path"
                val id = parts[5].toLong()
                val (list, other) = if (parts[4] == "picks") picks to hidden else hidden to picks
                list.remove(id)
                if (method == "PUT") {
                    list += id
                    other.remove(id)
                }
                json.encodeToString(planningOf(week))
            }
            else -> null
        }
    }

    companion object {
        /** The day all pictures are taken on: a Wednesday. */
        const val TODAY = "2026-11-18"
        val WEEK_START = mondayOf(TODAY)

        /** An access token every call answers 401 to. */
        const val REFUSED_TOKEN = "refused-token"

        /** An access token of an account outside the server's group: every call answers 403 `not_in_group` to it. */
        const val NOT_IN_GROUP_TOKEN = "not-in-group-token"
    }
}
