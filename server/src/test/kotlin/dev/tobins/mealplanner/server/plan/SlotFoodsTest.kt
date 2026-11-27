package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.DinnerSuggestions
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.LabelNutrition
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PlannedSlot
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.UnmappedText
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeekPlanning
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.tobins.mealplanner.server.ServerConfig
import dev.tobins.mealplanner.server.TestTokens
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.ingredients.StockSource
import dev.tobins.mealplanner.server.module
import dev.tobins.mealplanner.server.openDatabase
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Foods planned in a slot (issue 358): several per slot, each in the unit it was picked in, alone or next to a recipe; what
 * they count in the day, on the shopping list and in the stock; and that a slot of foods is no recipe to the person, and
 * leaves nothing behind once it goes.
 */
class SlotFoodsTest {
    /** Per 100 g: banana 90 kcal, 1 g protein, 2 g fibre; rice 350, 7, 2; beef 150, 20, 0. */
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Fruit|Fruit|9101|Slot banaan|Slot banana||per 100g|90|1|0|0|20|15|2|1",
        "NEVO-Online 2025 9.0|Graan|Cereals|9102|Slot rijst|Slot rice||per 100g|350|7|1|0|78|0|2|5",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|9103|Slot rund|Slot beef||per 100g|150|20|4|2|0|0|0|50",
    ).joinToString("\r\n")

    private val week = "2026-11-16"
    private lateinit var db: Database
    private lateinit var driver: SqlDriver

    private fun ApplicationTestBuilder.start() {
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        val opened = openDatabase(config.databaseUrl)
        driver = opened.first
        db = opened.second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens, today = { LocalDate.parse(week) }) }
    }

    private fun ApplicationTestBuilder.clientFor(token: String): HttpClient = createClient {
        install(ContentNegotiation) { json() }
        defaultRequest {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
        }
    }

    /**
     * An account's catalogue: a banana of 120 g a piece, rice and beef by NEVO, and a protein bar with its label's values
     * (400 kcal, 30 g protein, 5 g fibre in 100 g) and a portion "bar" of 45 g; and a stew of 2 servings of 300 g rice and
     * 400 g beef, and a beef bowl of one serving, dinners in the rotation.
     */
    private class Kitchen(val banana: Ingredient, val rice: Ingredient, val beef: Ingredient, val bar: Ingredient, val stew: RecipeSummary, val bowl: RecipeSummary, val userId: Long)

    private suspend fun HttpClient.kitchen(token: String): Kitchen {
        put("/api/profile") { setBody(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        suspend fun ingredient(input: IngredientInput) = post("/api/ingredients") { setBody(input) }.body<Ingredient>()
        val banana = ingredient(IngredientInput("Banana", 9101, gramsPerPiece = 120.0))
        val rice = ingredient(IngredientInput("Rice", 9102))
        val beef = ingredient(IngredientInput("Beef", 9103))
        val label = LabelNutrition(FoodBasis.PER_100_G, Nutrition(400.0, 30.0, null, null, null, null, 5.0, null))
        val bar = ingredient(IngredientInput("Protein bar", label = label, portions = listOf(Portion("bar", grams = 45.0))))
        val stew = post("/api/recipes") { setBody(RecipeInput("Stew", 2, "300 | g | rice\n400 | g | beef", "Stew.", mealSlots = listOf(MealSlot.DINNER))) }
            .body<RecipeDetail>().summary
        val bowl = post("/api/recipes") { setBody(RecipeInput("Beef bowl", 1, "120 | g | rice\n180 | g | beef", "Bowl.", mealSlots = listOf(MealSlot.DINNER))) }
            .body<RecipeDetail>().summary
        val userId = db.userQueries.selectBySubject("sub-$token").executeAsOne().id
        return Kitchen(banana, rice, beef, bar, stew, bowl, userId)
    }

    private fun foodsPath(day: Int, slot: MealSlot) = "/api/plans/$week/meals/$day/$slot/foods"

    private suspend fun HttpClient.addFood(day: Int, slot: MealSlot, ingredient: Ingredient, amount: Double, unit: String): HttpResponse =
        post(foodsPath(day, slot)) { setBody(MealFoodInput(ingredient.id, amount, unit)) }

    private suspend fun HttpClient.plan(): WeekPlan = get("/api/plans/$week").body()

    private suspend fun HttpClient.meal(day: Int, slot: MealSlot): PlannedMeal = plan().days[day].meals.single { it.slot == slot }

    private suspend fun HttpClient.shopping(): Map<String, Double?> = rows().mapValues { it.value.grams }

    private suspend fun HttpClient.rows(): Map<String, ShoppingItem> = get("/api/plans/$week/shopping").body<ShoppingList>().items.associateBy { it.name }

    private suspend fun HttpClient.ok(response: HttpResponse): HttpResponse = response.also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }

    private fun Kitchen.stockUp(ingredient: Ingredient, grams: Double) =
        StockRepository(db, FoodRepository(db), userId).putOnHandFrom(ingredient.id, LocalDate.parse(week), grams, StockSource.STOCK_UP)

    private fun Kitchen.stock(): Map<String, Double?> {
        val names = listOf(banana, rice, beef, bar).associate { it.id to it.name }
        return db.pantryQueries.selectStockOfAccount(userId).executeAsList().associate { names.getValue(it.ingredientId) to it.grams }
    }

    /** The account's recipes and their lines, counted as rows: what a slot of foods leaves behind when it goes. */
    private fun Kitchen.rows(): Pair<Long, Long> =
        count("SELECT count(*) FROM recipe WHERE userId = $userId") to
            count("SELECT count(*) FROM recipeIngredient WHERE recipeId IN (SELECT id FROM recipe WHERE userId = $userId)")

    private fun count(sql: String): Long =
        driver.executeQuery(null, sql, { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else 0L) }, 0).value

    @Test
    fun aProductInItsPortionAndASecondFoodAreASnackThatCountsEverywhere() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")

        // Two bars of 45 g (360 kcal, 27 g protein, 4.5 g fibre) and a banana of 120 g (108 kcal, 1.2 g protein, 2.4 g fibre).
        http.ok(http.addFood(0, MealSlot.SNACK, k.bar, 2.0, "bar"))
        val plan = http.ok(http.addFood(0, MealSlot.SNACK, k.banana, 1.0, "piece")).body<WeekPlan>()
        val snack = plan.days[0].meals.single()
        assertTrue(snack.foodsOnly)
        assertEquals("Protein bar, Banana", snack.title)
        assertEquals(listOf("Protein bar 2.0 bar 90.0", "Banana 1.0 piece 120.0"), snack.foods.map { "${it.name} ${it.amount} ${it.unit} ${it.grams}" })
        assertEquals(listOf(468.0, 28.2, 6.9), listOf(snack.kcal, snack.proteinG, snack.fibreG!!).map(::rounded))
        assertEquals(listOf(468.0, 28.2), listOf(plan.days[0].kcal, plan.days[0].proteinG).map(::rounded))
        val today = http.get("/api/days/$week").body<DayView>()
        assertEquals(468.0, rounded(today.expectedKcal))
        // On the week's list by their weight.
        assertEquals(90.0 to 120.0, http.shopping().let { it["Protein bar"] to it["Banana"] })
        // Each row says the slot its food is planned in, not a recipe, and not the other food of the slot.
        val snackSlot = PlannedSlot(week, MealSlot.SNACK)
        assertEquals(listOf(emptyList<String>() to listOf(snackSlot), emptyList<String>() to listOf(snackSlot)), http.rows().let { rows ->
            listOf("Protein bar", "Banana").map { rows.getValue(it).recipes to rows.getValue(it).foodSlots }
        })
        assertEquals(listOf(snackSlot), http.rows().getValue("Banana").toComeFoodSlots)
        // Each starts in the unit it was picked in, the next time it is picked.
        assertEquals(listOf("bar", "piece"), http.get("/api/ingredients").body<List<Ingredient>>().let { all -> listOf(k.bar, k.banana).map { i -> all.single { it.id == i.id }.lastUnit } })

        // One bar instead of two: the totals follow, and the list.
        val bar = snack.foods.first()
        http.ok(http.put("${foodsPath(0, MealSlot.SNACK)}/${bar.id}") { setBody(MealFoodInput(k.bar.id, 1.0, "bar")) })
        val fewer = http.meal(0, MealSlot.SNACK)
        assertEquals(listOf(288.0, 14.7), listOf(fewer.kcal, fewer.proteinG).map(::rounded))
        assertEquals(288.0, rounded(http.plan().days[0].kcal))
        assertEquals(45.0, http.shopping()["Protein bar"])

        // The banana taken out: the bar stays, alone, and the banana is off the list.
        val banana = fewer.foods.last()
        http.ok(http.delete("${foodsPath(0, MealSlot.SNACK)}/${banana.id}"))
        val left = http.meal(0, MealSlot.SNACK)
        assertEquals(listOf("Protein bar 1.0 bar"), left.foods.map { "${it.name} ${it.amount} ${it.unit}" })
        assertEquals("Protein bar" to 180.0, left.title to rounded(left.kcal))
        assertNull(http.shopping()["Banana"])
    }

    @Test
    fun aDinnerOfFoodsIsEatenFromTheStockAndAFoodNextToARecipeCountsWithIt() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        k.stockUp(k.rice, 1000.0)
        k.stockUp(k.beef, 1000.0)
        k.stockUp(k.banana, 1000.0)

        // A dinner of 150 g rice, 200 g beef and a banana, with no recipe: marked eaten, the stock goes down by each.
        http.ok(http.addFood(0, MealSlot.DINNER, k.rice, 150.0, "g"))
        http.ok(http.addFood(0, MealSlot.DINNER, k.beef, 200.0, "g"))
        http.ok(http.addFood(0, MealSlot.DINNER, k.banana, 1.0, "piece"))
        assertEquals("Rice + 2 more", http.meal(0, MealSlot.DINNER).title)
        http.ok(http.put("/api/plans/$week/meals/0/DINNER/status") { setBody(MealStatus.EATEN) })
        assertEquals(mapOf("Rice" to 850.0, "Beef" to 800.0, "Banana" to 880.0), k.stock())
        // Eaten, a food changed moves the stock by the difference; un-eaten, all of it comes back.
        val beef = http.meal(0, MealSlot.DINNER).foods.single { it.ingredientId == k.beef.id }
        http.ok(http.put("${foodsPath(0, MealSlot.DINNER)}/${beef.id}") { setBody(MealFoodInput(k.beef.id, 250.0, "g")) })
        assertEquals(750.0, k.stock()["Beef"])
        http.ok(http.put("/api/plans/$week/meals/0/DINNER/status") { setBody(MealStatus.PLANNED) })
        assertEquals(mapOf("Rice" to 1000.0, "Beef" to 1000.0, "Banana" to 1000.0), k.stock())

        // A banana next to a planned stew: one serving (150 g rice 525 kcal, 200 g beef 300 kcal) and the banana's 108.
        http.ok(http.put("/api/plans/$week/meals/1/DINNER") { setBody(MealChoice(k.stew.id)) })
        val stew = http.meal(1, MealSlot.DINNER)
        http.ok(http.addFood(1, MealSlot.DINNER, k.banana, 1.0, "piece"))
        val withBanana = http.meal(1, MealSlot.DINNER)
        assertFalse(withBanana.foodsOnly)
        assertEquals(stew.recipeId to "Stew", withBanana.recipeId to withBanana.title)
        assertEquals(listOf("Banana"), withBanana.foods.map { it.name })
        assertEquals(rounded(stew.kcal + 108.0), rounded(withBanana.kcal))
        assertEquals(rounded(withBanana.kcal + http.meal(0, MealSlot.DINNER).kcal), rounded(http.plan().days.sumOf { it.kcal }))
        // On the list with the dinner of foods' banana, and eaten from the stock with the stew.
        val portions = stew.portions
        assertEquals(240.0, http.shopping()["Banana"])
        // The banana next to the stew is named by its slot, the stew's rice by the stew.
        val rows = http.rows()
        assertEquals(emptyList<String>() to listOf(PlannedSlot(week, MealSlot.DINNER), PlannedSlot("2026-11-17", MealSlot.DINNER)), rows.getValue("Banana").let { it.recipes to it.foodSlots })
        assertEquals(listOf("Stew"), rows.getValue("Rice").recipes)
        http.ok(http.put("/api/plans/$week/meals/1/DINNER/status") { setBody(MealStatus.EATEN) })
        val stock = k.stock()
        assertEquals(880.0, stock["Banana"])
        assertEquals(rounded(1000.0 - 150.0 * portions), rounded(stock.getValue("Rice")!!))
        // Taken out after it was eaten: the stock gets the banana back, the stew keeps what it took.
        http.ok(http.delete("${foodsPath(1, MealSlot.DINNER)}/${withBanana.foods.single().id}"))
        assertEquals(1000.0, k.stock()["Banana"])
        assertEquals(rounded(1000.0 - 150.0 * portions), rounded(k.stock().getValue("Rice")!!))
        assertEquals(stew.kcal, http.meal(1, MealSlot.DINNER).kcal)
    }

    @Test
    fun theTotalPlanningReportsCountsAFoodNextToAKeptRecipeAsThePlanDoes() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")

        // A kept stew with a banana next to it: the stew's serving (825 kcal) and the banana's 108.
        http.ok(http.put("/api/plans/$week/meals/1/DINNER") { setBody(MealChoice(k.stew.id)) })
        val stew = http.meal(1, MealSlot.DINNER)
        http.ok(http.addFood(1, MealSlot.DINNER, k.banana, 1.0, "piece"))
        val kept = http.meal(1, MealSlot.DINNER)
        assertEquals(rounded(stew.kcal + 108.0), rounded(kept.kcal))

        // The planning screen's strip is the Plan's day, the banana in it, in kcal, protein and fibre.
        val totals = http.get("/api/plans/$week/planning").body<WeekPlanning>().totals
        val day = http.plan().days[1]
        assertEquals(listOf(rounded(kept.kcal), rounded(kept.proteinG), rounded(kept.fibreG!!)), listOf(totals.kcal, totals.proteinG, totals.fibreG).map(::rounded))
        assertEquals(listOf(rounded(day.kcal), rounded(day.proteinG), rounded(day.fibreG!!)), listOf(totals.kcal, totals.proteinG, totals.fibreG).map(::rounded))

        // Planning the rest of the week keeps the slot with its banana, and the Plan counts it in the day.
        val planned = http.ok(http.post("/api/plans/$week/generate")).body<WeekPlan>()
        val after = planned.days[1]
        assertEquals(rounded(kept.kcal), rounded(after.meals.single { it.slot == MealSlot.DINNER }.kcal))
        assertEquals(rounded(after.meals.sumOf { it.kcal }), rounded(after.kcal))
    }

    @Test
    fun aSlotOfFoodsIsNoRecipeAndLeavesNothingBehind() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        val before = k.rows()

        http.ok(http.addFood(2, MealSlot.DINNER, k.banana, 2.0, "piece"))
        val foods = http.meal(2, MealSlot.DINNER)
        assertEquals(before.first + 1 to before.second + 1, k.rows(), "one recipe of one line holds the foods")
        assertTrue(http.get("/api/recipes").body<List<RecipeSummary>>().none { it.id == foods.recipeId }, "not in my recipes")
        assertTrue(http.get("/api/recipes/bank").body<List<RecipeSummary>>().none { it.id == foods.recipeId }, "not in the bank")
        assertTrue(http.get("/api/mappings/unmapped").body<List<UnmappedText>>().none { "banana" in it.text.lowercase() })
        // Not to choose, add, prep or plan by hand: as a recipe that is not there.
        assertEquals(HttpStatusCode.NotFound, http.put("/api/recipes/${foods.recipeId}/added") { setBody(AddedInput(true)) }.status)
        assertEquals(HttpStatusCode.NotFound, http.put("/api/plans/$week/meals/3/DINNER") { setBody(MealChoice(foods.recipeId)) }.status)
        assertEquals(HttpStatusCode.NotFound, http.put("/api/plans/$week/planning/picks/${foods.recipeId}").status)
        // The planner keeps the slot as it is and never plans its recipe elsewhere, nor suggests it, nor finds it by name.
        val planned = http.ok(http.post("/api/plans/$week/generate")).body<WeekPlan>()
        assertEquals(foods.foods, planned.days[2].meals.single { it.slot == MealSlot.DINNER }.foods)
        assertEquals(1, planned.days.flatMap { it.meals }.count { it.recipeId == foods.recipeId })
        for (query in listOf("", "&q=Banana")) {
            val suggestions = http.get("/api/plans/$week/planning/suggestions?count=20$query").body<DinnerSuggestions>().suggestions
            assertTrue(suggestions.none { it.recipeId == foods.recipeId }, "$suggestions")
        }
        // Eaten, it is no recipe eaten before.
        http.ok(http.put("/api/plans/$week/meals/2/DINNER/status") { setBody(MealStatus.EATEN) })
        assertTrue(db.planQueries.selectLastEaten(k.userId, "2026-11-23").executeAsList().none { it.recipeId == foods.recipeId })
        http.ok(http.put("/api/plans/$week/meals/2/DINNER/status") { setBody(MealStatus.PLANNED) })
        // Moved to another day with its foods.
        http.ok(http.post("/api/plans/$week/meals/2/DINNER/move") { setBody("""{"toDay": 4}""") })
        assertEquals(foods.foods, http.meal(4, MealSlot.DINNER).foods)
        assertEquals(before.first + 1 to before.second + 1, k.rows())

        // Removed: nothing of it is left.
        http.ok(http.delete("/api/plans/$week/meals/4/DINNER"))
        assertEquals(before, k.rows(), "removed")

        // A recipe chosen for a slot of foods alone goes in with the foods next to it; another recipe keeps them too.
        http.ok(http.delete("/api/plans/$week/meals/5/DINNER"))
        http.ok(http.addFood(5, MealSlot.DINNER, k.banana, 1.0, "piece"))
        val banana = http.meal(5, MealSlot.DINNER).also { assertTrue(it.foodsOnly) }.foods
        http.ok(http.put("/api/plans/$week/meals/5/DINNER") { setBody(MealChoice(k.stew.id)) })
        val chosen = http.meal(5, MealSlot.DINNER)
        assertEquals(Triple(k.stew.id, "Stew", false), Triple(chosen.recipeId, chosen.title, chosen.foodsOnly))
        assertEquals(banana, chosen.foods)
        assertEquals(before.first + 1 to before.second + 1, k.rows(), "the foods stay")
        http.ok(http.post("/api/plans/$week/meals/5/DINNER/reroll"))
        val rerolled = http.meal(5, MealSlot.DINNER)
        assertEquals(k.bowl.id to banana, rerolled.recipeId to rerolled.foods)
        // Planning the week keeps the slot with its foods as it is.
        http.ok(http.post("/api/plans/$week/generate"))
        assertEquals(k.bowl.id to banana, http.meal(5, MealSlot.DINNER).let { it.recipeId to it.foods })
        // A prepped meal does not take the place of foods, which leave a slot only when taken out.
        http.ok(http.put("/api/recipes/${k.stew.id}/keeps") { setBody(RecipeKeeps(days = 3)) })
        val prep = http.post("/api/plans/$week/preps") { setBody(PrepInput(k.stew.id, "2026-11-21", meals = listOf(PrepSlot("2026-11-21", MealSlot.DINNER), PrepSlot("2026-11-22", MealSlot.DINNER)))) }
        assertEquals(HttpStatusCode.Conflict, prep.status, prep.bodyAsText())
        // The slot removed: its recipe stays a recipe, its foods go.
        http.ok(http.delete("/api/plans/$week/meals/5/DINNER"))
        assertEquals(before, k.rows(), "removed with a recipe")

        // Emptied of its last food, alone or next to a recipe: nothing of it is left.
        http.ok(http.addFood(6, MealSlot.SNACK, k.bar, 1.0, "bar"))
        http.ok(http.delete("${foodsPath(6, MealSlot.SNACK)}/${http.meal(6, MealSlot.SNACK).foods.single().id}"))
        assertTrue(http.plan().days[6].meals.none { it.slot == MealSlot.SNACK }, "the slot is empty again")
        assertEquals(before, k.rows(), "its last food taken out")
        http.ok(http.put("/api/plans/$week/meals/5/LUNCH") { setBody(MealChoice(k.stew.id)) })
        http.ok(http.addFood(5, MealSlot.LUNCH, k.banana, 1.0, "piece"))
        http.ok(http.delete("${foodsPath(5, MealSlot.LUNCH)}/${http.meal(5, MealSlot.LUNCH).foods.single().id}"))
        assertEquals("Stew" to before, http.meal(5, MealSlot.LUNCH).title to k.rows(), "the last food next to a recipe")
    }

    @Test
    fun aFoodIsRefusedWhereItWouldNotCount() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        assertEquals(HttpStatusCode.BadRequest, http.addFood(0, MealSlot.SNACK, k.rice, 1.0, "piece").status, "rice has no weight per piece")
        assertEquals(HttpStatusCode.BadRequest, http.addFood(0, MealSlot.SNACK, k.rice, 0.0, "g").status)
        assertEquals(HttpStatusCode.BadRequest, http.addFood(0, MealSlot.SNACK, k.rice, 6000.0, "g").status)
        assertEquals(HttpStatusCode.NotFound, http.post(foodsPath(0, MealSlot.SNACK)) { setBody(MealFoodInput(999_999, 1.0, "g")) }.status)
        assertTrue(http.plan().days[0].meals.isEmpty(), "nothing was planned")

        // A portion's name in any case; a skipped meal takes no food, and a slot of foods has no recipe to adjust or unlock.
        http.ok(http.addFood(0, MealSlot.SNACK, k.bar, 1.0, "Bar"))
        assertEquals("bar", http.meal(0, MealSlot.SNACK).foods.single().unit)
        assertEquals(HttpStatusCode.Conflict, http.put("/api/plans/$week/meals/0/SNACK/lines") { setBody("""{"lines":[]}""") }.status)
        assertEquals(HttpStatusCode.Conflict, http.delete("/api/plans/$week/meals/0/SNACK/lock").status)
        http.ok(http.put("/api/plans/$week/meals/0/SNACK/status") { setBody(MealStatus.SKIPPED) })
        assertEquals(HttpStatusCode.Conflict, http.addFood(0, MealSlot.SNACK, k.banana, 1.0, "piece").status)
    }

    private fun rounded(value: Double) = Math.round(value * 10) / 10.0
}
