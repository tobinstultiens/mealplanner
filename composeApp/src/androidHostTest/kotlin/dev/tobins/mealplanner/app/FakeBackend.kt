package dev.tobins.mealplanner.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tobins.mealplanner.api.AccountRequest
import dev.tobins.mealplanner.api.LoginRefusal
import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.FoodDataset
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.LabelNutrition
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.MealFood
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.Me
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.OfferExclusion
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.PricesState
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.StandardChosen
import dev.tobins.mealplanner.api.RecipeTopping
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.api.TargetSuggestion
import dev.tobins.mealplanner.api.WeightEntry
import dev.tobins.mealplanner.api.WeightLog
import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.app.accounts.profileJson
import dev.tobins.mealplanner.app.accounts.registrationRoute
import dev.tobins.mealplanner.app.ingredients.changeIngredient
import dev.tobins.mealplanner.app.ingredients.createIngredient
import dev.tobins.mealplanner.app.ingredients.foodsMatching
import dev.tobins.mealplanner.app.ingredients.ingredients
import dev.tobins.mealplanner.app.ingredients.nevo
import dev.tobins.mealplanner.app.ingredients.pantry
import dev.tobins.mealplanner.app.ingredients.productLookup
import dev.tobins.mealplanner.app.ingredients.productSearch
import dev.tobins.mealplanner.app.plan.day
import dev.tobins.mealplanner.app.plan.foodOf
import dev.tobins.mealplanner.app.plan.foodsRoute
import dev.tobins.mealplanner.app.plan.mealLines
import dev.tobins.mealplanner.app.plan.plan
import dev.tobins.mealplanner.app.plan.planningRoute
import dev.tobins.mealplanner.app.plan.spare
import dev.tobins.mealplanner.app.plan.standardOffer
import dev.tobins.mealplanner.app.prices.changeExclusions
import dev.tobins.mealplanner.app.prices.judged
import dev.tobins.mealplanner.app.prices.offerList
import dev.tobins.mealplanner.app.prices.offers
import dev.tobins.mealplanner.app.prices.prices
import dev.tobins.mealplanner.app.recipes.PIECE
import dev.tobins.mealplanner.app.recipes.addOns
import dev.tobins.mealplanner.app.recipes.bank
import dev.tobins.mealplanner.app.recipes.changeRecipes
import dev.tobins.mealplanner.app.recipes.detailJson
import dev.tobins.mealplanner.app.recipes.mine
import dev.tobins.mealplanner.app.recipes.recipesJson
import dev.tobins.mealplanner.app.recipes.summary
import dev.tobins.mealplanner.app.shopping.shopping
import dev.tobins.mealplanner.app.tracking.changeWeights
import dev.tobins.mealplanner.app.tracking.logRoute
import dev.tobins.mealplanner.app.ui.mondayOf
import dev.tobins.mealplanner.app.ui.plusDays
import dev.tobins.mealplanner.nutrition.Salt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    internal val emptyWeek: Boolean = false,
    internal val emptyOffers: Boolean = false,
    internal val emptyPrices: Boolean = false,
    internal val emptyRecipes: Boolean = false,
    /** A server from before the recipe bank: no bank route, and recipes without the flags of mine and the rotation. */
    internal val olderServer: Boolean = false,
    /** The week with a prep of the chicken thighs (issue 234), whose Wednesday lunch was skipped and is still in the box. */
    internal val prepped: Boolean = false,
    /** The week with half a serving of the chickpea salad prepped as a side next to three meals (issue 234, step 2). */
    internal val sides: Boolean = false,
    /** The week of [sides] with the salad's Thursday lunch eaten as the meal itself instead of as a side (issue 269). */
    internal val mixed: Boolean = false,
    /** The week holding only Monday's lunch of the chicken thighs prepped the Sunday before ([tailPrep]), not planned yet (issue 263). */
    internal val tailOnly: Boolean = false,
    /** The slots the week names as empty although it has meals, as one planned partly by hand: the server names every slot without a meal (issue 374). */
    internal val emptySlots: List<MealSlot> = emptyList(),
    /** The week says what fresh ingredients it leaves over (issue 244): five rests, so the line says "and 2 more". */
    internal val weekRest: Boolean = false,
    /** The prep of [prepped] kept vacuum sealed rather than in a box, which its labels say (issue 263). */
    internal val vacuum: Boolean = false,
    /** The week lists a batch of the chickpea salad still to make, made today, that can take a side next to a later meal (issue 263). */
    internal val growing: Boolean = false,
    /** Friday's dinner (the lentil soup, whose leftovers are Saturday's lunch) is eaten, so that lunch keeps its recipe too (issue 191). */
    internal val dinnerEaten: Boolean = false,
    /**
     * The tuna pasta salad and the shakshuka are standard meals (issue 265), offered for lunch and dinner, the shakshuka with
     * no eggs at home; today's (Wednesday's) lunch is skipped; the eaten breakfast takes none. Without it the server is one
     * from before standard meals.
     */
    internal val standardMeals: Boolean = false,
    /**
     * The planning screen (issue 255): the lentil soup and the shakshuka chosen for the week's dinners and the tuna salad
     * hidden, with [suggestions] to choose from. Without it the server is one from before the planning screen.
     */
    internal val planning: Boolean = false,
    /** With [planning], an account with no dinner to suggest, as one whose rotation has none that makes a dinner. */
    nothingToSuggest: Boolean = false,
    /**
     * The offers' verdicts once weeks of price history are recorded (issue 118): one of each kind the screens show.
     * Without it every judged offer is "too new to judge", as in the first eight weeks.
     */
    internal val laterVerdicts: Boolean = false,
    /**
     * Writing recipes (issue 342): a catalogue whose ingredients have NEVO foods, foods to search that the account does not
     * have yet, links of Dutch texts, and the chicken thighs as a recipe taken from the cooking site with groups. Recipes
     * saved and lines looked up are read as the server reads them, with the shared code it uses.
     */
    internal val writing: Boolean = false,
    /**
     * Foods in slots (issue 358): Monday's snack is two foods alone (two bars of a protein bar, in its catalogue then, and an
     * egg) and its breakfast has Greek yoghurt next to the oats; today's (Wednesday's) snack is yoghurt and a bar. Foods
     * added, changed and taken out are kept in any week.
     */
    internal val slotFoods: Boolean = false,
    /**
     * Foods logged as eaten outside the plan (issue 362): every day's log has, after the typed apple, a bar of the protein bar
     * (in the catalogue then), counted when its label said less, and a muesli bar no longer in it. Entries logged, changed
     * and removed are kept, on any day.
     */
    internal val loggedFoods: Boolean = false,
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

    /**
     * Asking for an account (issue 314): null is a server from before registration, which has no such routes; else whether
     * it takes requests now. A request is answered as taken, or with [registrationRefusal] (the status and the reason);
     * [registered] has each request's body as sent.
     */
    @Volatile
    var registrationOpen: Boolean? = null

    @Volatile
    var registrationRefusal: Pair<Int, String>? = null

    val registered: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Whether the account is the owner's, which decides on [accountRequests] (issue 314); every other is refused them. */
    @Volatile
    var owner = false

    /** The requests for an account that wait for the owner, oldest first; a decision takes one out. */
    val accountRequests: MutableList<AccountRequest> = java.util.Collections.synchronizedList(mutableListOf())

    /** A sentence that makes every change of recipes answer 409, as the server does for a recipe planned on Friday. */
    @Volatile
    var refusing: String? = null

    /**
     * While set, the week's standard meals are answered once it opens, as over a slow connection, so that a test can show
     * a screen before they come in. They wait on a thread of their own: every other call is answered meanwhile.
     */
    @Volatile
    var standardMealsAnswer: java.util.concurrent.CountDownLatch? = null

    /**
     * While set, the call that puts a standard meal in a slot is answered once it opens, so that a
     * test can act while the tap is still being sent.
     */
    @Volatile
    var standardChoiceAnswer: java.util.concurrent.CountDownLatch? = null

    /** While set, a recipe's quick add-ons are answered once it opens, as over a slow connection; the other calls are answered meanwhile. */
    @Volatile
    var addOnsAnswer: java.util.concurrent.CountDownLatch? = null

    /** Opens when a quick add-ons request is held by [addOnsAnswer], so that a test knows it was asked for. */
    val addOnsHeld = java.util.concurrent.CountDownLatch(1)

    internal val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val held = standardMealsAnswer
            val choice = standardChoiceAnswer
            val addOns = addOnsAnswer
            if (held != null && exchange.requestURI.path.endsWith("/standard-meals")) {
                thread { held.await(10, java.util.concurrent.TimeUnit.SECONDS); exchange.respond() }
            } else if (choice != null && exchange.requestMethod == "PUT" && exchange.requestURI.path.endsWith("/standard")) {
                thread { choice.await(10, java.util.concurrent.TimeUnit.SECONDS); exchange.respond() }
            } else if (addOns != null && exchange.requestURI.path.endsWith("/add-ons")) {
                addOnsHeld.countDown()
                thread { addOns.await(10, java.util.concurrent.TimeUnit.SECONDS); exchange.respond() }
            } else {
                exchange.respond()
            }
        }
        start()
    }

    internal val json = Json { encodeDefaults = true }

    val settings = ServerSettings("http://127.0.0.1:${server.address.port}")

    override fun close() = server.stop(0)

    private fun HttpExchange.respond() {
        // A session the server no longer accepts, as after its access token ran out.
        if (requestHeaders.getFirst("Authorization") == "Bearer $REFUSED_TOKEN") {
            sendResponseHeaders(401, -1)
            close()
            return
        }
        // A login the server refuses on every request, as one with MEALPLANNER_OIDC_GROUP answers an account outside it, and
        // one whose request for an account waits (issue 314).
        val outside = when (requestHeaders.getFirst("Authorization")) {
            "Bearer $NOT_IN_GROUP_TOKEN" -> """{"error":"This account may not use the meal planner yet. Ask the person who runs this server.","reason":"not_in_group"}"""
            "Bearer $WAITING_TOKEN" -> """{"error":"Your request is waiting for approval.","reason":"not_in_group","detail":"${LoginRefusal.WAITING_FOR_APPROVAL}"}"""
            else -> null
        }
        if (outside != null) {
            val bytes = outside.toByteArray()
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
            val reason = e.reason?.let { ""","reason":${json.encodeToString(it)}""" } ?: ""
            """{"error":${json.encodeToString(e.message)}$reason}"""
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

    /** What the server answers with an error and its sentence, as `{"error": "..."}`, with the [reason] when there is one. */
    internal class Refusal(val status: Int, message: String, val reason: String? = null) : Exception(message)

    /** The parameters of a query string, `count=6&seen=1%2C2`, decoded. */
    internal fun queryOf(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { '=' in it }
        .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    internal fun route(method: String, path: String, sent: String, query: Map<String, String> = emptyMap()): String? {
        val parts = path.trim('/').split('/')
        if (parts.getOrNull(1) == "plans" && (parts.getOrNull(3) == "planning" || parts.lastOrNull() == "move")) return planningRoute(method, path, sent, query)
        if (parts.getOrNull(1) == "recipes" && method != "GET") return changeRecipes(method, parts, sent)
        if (parts.getOrNull(1) == "registration" || parts.getOrNull(1) == "registrations") return registrationRoute(method, parts, sent)
        if (parts.getOrNull(1) == "plans" && parts.getOrNull(6) == "foods") return foodsRoute(method, path, parts, sent)
        if (parts.getOrNull(1) == "log") return logRoute(method, path, parts, sent)
        return when {
            path == "/api/me" -> json.encodeToString(Me("anna", "Anna", approvesRequests = owner))
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
            path == "/api/offers/exclusions" -> changeExclusions(method, sent, query)
            path == "/api/offers" -> json.encodeToString(if (emptyOffers) OffersState(emptyList(), emptyList()) else offers)
            path == "/api/prices" -> json.encodeToString(if (emptyPrices) PricesState(emptyList(), emptyList(), null) else prices)
            (method == "PUT" || method == "DELETE") && path.startsWith("/api/weights/") -> changeWeights(method, path.substringAfterLast('/'), sent)
            path == "/api/weights" -> json.encodeToString(weights)
            method == "POST" && path == "/api/ingredients" -> json.encodeToString(createIngredient(sent))
            method == "PUT" && parts.size == 3 && parts[1] == "ingredients" -> json.encodeToString(changeIngredient(parts[2].toLong(), sent))
            path == "/api/ingredients" -> json.encodeToString(if (writing) catalogue else ingredients)
            // "Link ingredients" with nothing left to link and no section to show: its products are what is tested.
            path == "/api/mappings/unmapped" -> "[]"
            path == "/api/ingredients/sections" -> "[]"
            path == "/api/foods" -> json.encodeToString(foodsMatching(query.getValue("q")))
            path == "/api/products/search" -> json.encodeToString(productSearch(query.getValue("q")))
            parts.getOrNull(1) == "products" && parts.size == 4 -> json.encodeToString(productLookup(parts[2], parts[3]))
            path == "/api/pantry" -> json.encodeToString(pantry)
            // Never chosen: every store, as for an account that never opened the per-store list.
            path == "/api/shops" -> json.encodeToString(ShopsSetting())
            path == "/api/shopping-advice" -> json.encodeToString(ShoppingAdvice())
            else -> null
        }
    }

    internal val targets = DailyTargets(2100, 130, 30)

    /** The profile the server holds; null for an account that has not saved one. A test may set it before the screen loads. */
    @Volatile
    var profile: Profile? = null

    /**
     * With [writing], the catalogue a recipe is written from (issue 342): each ingredient with its NEVO food, a weight per
     * piece where one is counted in pieces, the oil's density; salt left out of the nutrition. Ingredients made from a
     * food picked in the editor are added.
     */
    internal val catalogue: MutableList<Ingredient> = java.util.Collections.synchronizedList(
        mutableListOf(
            Ingredient(1, "Chicken thigh fillet", nevo(1, "Chicken thigh fillet raw", "Kipdijfilet rauw", 180.0, 18.0), 110.0, null, null, null, false),
            Ingredient(3, "Greek yoghurt", nevo(3, "Yoghurt Greek", "Yoghurt Grieks", 120.0, 4.5), null, null, null, null, false),
            Ingredient(5, "Olive oil", nevo(5, "Olive oil", "Olijfolie", 884.0, 0.0), null, 0.91, null, null, isStaple = true),
            Ingredient(7, "Salt", null, null, null, null, null, isStaple = true, negligible = true),
            Ingredient(8, "Spinach", nevo(8, "Spinach raw", "Spinazie rauw", 23.0, 2.9), null, null, null, null, false),
            Ingredient(10, "Coconut milk", nevo(10, "Coconut milk", "Kokosmelk", 197.0, 1.8, FoodBasis.PER_100_ML), null, null, null, null, false),
            Ingredient(11, "Lemon", nevo(11, "Lemon", "Citroen", 29.0, 1.1), 100.0, null, null, null, false),
            Ingredient(12, "Egg", nevo(12, "Egg chicken raw", "Kippenei rauw", 143.0, 12.6), 60.0, null, null, null, false),
        ),
    )

    /** The protein bar of [slotFoods]: the values of its label and a portion "bar" of 45 g. */
    internal val proteinBar = Ingredient(
        40, "Protein bar chocolate", null, null, null, null, null, false,
        label = LabelNutrition(FoodBasis.PER_100_G, Nutrition(380.0, 31.0, 14.0, 6.5, 32.0, 3.2, 6.0, Salt.sodiumMg(0.48))),
        portions = listOf(Portion("bar", grams = 45.0)),
    )

    init {
        if (slotFoods || loggedFoods) catalogue += proteinBar
    }

    /**
     * Every day's log (issue 362), by date, once a day is asked for: the typed apple, with [loggedFoods] a bar of the protein bar
     * and a muesli bar whose food is gone; and what was logged, changed and removed since. A fixture's ids are its day's own.
     */
    internal val log: MutableMap<String, List<LogEntry>> = java.util.concurrent.ConcurrentHashMap()

    /** The id the next entry logged gets: above every fixture's. */
    internal val nextLogId = java.util.concurrent.atomic.AtomicLong(1_000_000)

    /** The foods of each slot (issue 358), by week, day and slot: those of [slotFoods] and any added since. */
    internal val plannedFoods: MutableMap<Triple<String, Int, MealSlot>, List<MealFood>> = java.util.concurrent.ConcurrentHashMap<Triple<String, Int, MealSlot>, List<MealFood>>().also {
        if (!slotFoods) return@also
        it[Triple(WEEK_START, 0, MealSlot.SNACK)] = listOf(foodOf(1, MealFoodInput(proteinBar.id, 2.0, "bar")), foodOf(2, MealFoodInput(12, 1.0, PIECE)))
        it[Triple(WEEK_START, 0, MealSlot.BREAKFAST)] = listOf(foodOf(3, MealFoodInput(3, 150.0, "g")))
        it[Triple(WEEK_START, 2, MealSlot.SNACK)] = listOf(foodOf(4, MealFoodInput(3, 200.0, "g")), foodOf(5, MealFoodInput(proteinBar.id, 1.0, "bar")))
    }

    /** NEVO's foods as `/api/foods` searches them: those of the catalogue, and four the account does not have. */
    internal val foods = catalogue.mapNotNull { it.food } + listOf(
        nevo(500, "Rice white raw", "Rijst wit rauw", 350.0, 7.0),
        nevo(501, "Lentils red raw", "Linzen rood rauw", 336.0, 24.0),
        nevo(502, "Coriander fresh", "Koriander vers", 23.0, 2.1),
        nevo(503, "Chickpeas canned", "Kikkererwten uit blik", 120.0, 7.0),
    )

    /** While false, the product lookup (issue 354) answers as the server does when AH does not answer. */
    @Volatile
    var productSourceAnswers: Boolean = true

    /** What the meal's changes are now; a test reads them, and every `PUT .../lines` replaces them. */
    @Volatile
    var lineChanges: List<MealLineChange> = listOf(MealLineChange(1, 250.0), MealLineChange(8, 0.0), MealLineChange(9, 60.0))

    /** Each recipe's toppings; the chicken dinner's are feta and an egg. A test reads them, and `PUT .../toppings` replaces them. */
    @Volatile
    var toppings: Map<Long, List<RecipeTopping>> = mapOf(4L to listOf(RecipeTopping(2, "Feta", 30.0), RecipeTopping(9, "Egg", 60.0, 60.0)))

    /**
     * Every recipe the account has: the first seven are mine, the rest wait in the bank. Of mine, the tuna salad
     * and the shakshuka are out of the rotation (the shakshuka's file has left the cooking site), the lentil soup
     * is in it with a ingredient not linked yet, and the peanut sauce is a sauce, not a meal.
     */
    internal val rows = mutableListOf(
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

    /** The recipes saved by the editor while [writing], by id. */
    internal val saved = java.util.concurrent.ConcurrentHashMap<Long, RecipeDetail>()

    /**
     * The account's "Not this ingredient" list (issue 316), the newest first: at first the two [madeBefore], for the list in
     * Settings. Like the server it answers each change with the list; unlike it, the offers and the shopping list stay as they
     * are, so what a screen shows of a change is the app's own doing.
     */
    val exclusions: MutableList<OfferExclusion> = java.util.Collections.synchronizedList(madeBefore.toMutableList())

    /** The names of the ingredients an exclusion can be of: those of the offers and of the two made before. */
    internal val excludable by lazy {
        (offerList.mapNotNull { offer -> offer.ingredientId?.let { it to offer.ingredientName.orEmpty() } } + madeBefore.map { it.ingredientId to it.ingredientName }).toMap()
    }

    /** The weight log the server holds; a test may set it before the screen loads. */
    @Volatile
    var weights = WeightLog(
        entries = (0..9).map { WeightEntry(plusDays("2026-11-09", it), 82.4 - it * 0.08 + (it % 3) * 0.15, 82.4 - it * 0.09) },
        actualKgPerWeek = 0.2,
        suggestion = TargetSuggestion(2100, 1950, 0.2, 0.5),
    )

    /** The recipes chosen for the week's dinners, in the order they were, and those hidden for it. */
    val picks: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf(5L, 6L))

    internal val hidden: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf(3L))

    /** While set, Done answers 409 with this sentence, as the server does when a chosen dinner can no longer be planned. */
    @Volatile
    var doneRefusal: String? = null

    /** False for an account with no dinner to suggest ([nothingToSuggest]); a test may switch it. */
    @Volatile
    var suggesting = !nothingToSuggest

    companion object {
        /** The day all pictures are taken on: a Wednesday. */
        const val TODAY = "2026-11-18"
        val WEEK_START = mondayOf(TODAY)

        /** Made-up barcodes of the product lookup ([productLookup]): AH has it without a table. */
        const val AH_WITHOUT_TABLE = "8720000000001"

        /** Open Food Facts has it, with values per portion only. */
        const val OFF_WITHOUT_VALUES = "4000000000006"

        /** Open Food Facts has its values, without saying per 100 g or per 100 ml. */
        const val OFF_WITHOUT_BASIS = "5000000000001"

        /** An access token every call answers 401 to. */
        const val REFUSED_TOKEN = "refused-token"

        /** An access token of an account outside the server's group: every call answers 403 `not_in_group` to it. */
        const val NOT_IN_GROUP_TOKEN = "not-in-group-token"

        /** An access token of a person whose request for an account waits (issue 314): refused as [NOT_IN_GROUP_TOKEN], saying so. */
        const val WAITING_TOKEN = "waiting-token"

        /** Two "Not this ingredient" made on another device before the screens open (issue 316), the newest first. */
        private val madeBefore = listOf(
            OfferExclusion(Store.JUMBO, "Hela curry", "hela curry", 10, "Curry powder", "2026-11-17T18:02:11Z"),
            OfferExclusion(Store.AH, "AH Excellent hertenbiefstuk", "ah excellent hertenbiefstuk", 11, "Beef steak", "2026-11-16T09:40:00Z"),
        )
    }
}
