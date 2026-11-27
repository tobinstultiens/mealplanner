package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealSlot.DINNER
import dev.tobins.mealplanner.api.MealSlot.LUNCH
import dev.tobins.mealplanner.api.MealSlot.SNACK
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.RotationInput
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.SpareMove
import dev.tobins.mealplanner.api.StandardChoice
import dev.tobins.mealplanner.api.StandardChosen
import dev.tobins.mealplanner.api.StandardInput
import dev.tobins.mealplanner.api.StandardMeals
import dev.tobins.mealplanner.api.StandardSwitch
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.server.ServerConfig
import dev.tobins.mealplanner.server.TestTokens
import dev.tobins.mealplanner.server.UserScope
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.ingredients.StockSource
import dev.tobins.mealplanner.server.module
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
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
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Standard meals (issue 265): recipes of mine I can always make from what is at home, offered in one tap in place of a
 * meal that falls away. Each test is one box of the issue's "Done when", through the API.
 */
class StandardMealsTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Ei|Eggs|9301|Std ei|Std egg||per 100g|140|12|10|3|0|0|0|140",
        "NEVO-Online 2025 9.0|Brood|Bread|9302|Std brood|Std bread||per 100g|250|9|3|1|45|3|6|450",
        "NEVO-Online 2025 9.0|Vet|Fats|9303|Std boter|Std butter||per 100g|740|1|82|50|0|0|0|10",
        "NEVO-Online 2025 9.0|Zuivel|Dairy|9304|Std yoghurt|Std yoghurt||per 100g|60|4|3|2|4|4|0|50",
        "NEVO-Online 2025 9.0|Graan|Cereals|9305|Std rijst|Std rice||per 100g|350|7|1|0|78|0|2|5",
        "NEVO-Online 2025 9.0|Vlees|Meat|9306|Std kip|Std chicken||per 100g|150|20|4|1|0|0|0|50",
    ).joinToString("\r\n")

    /** Monday 16 to Sunday 22 November 2026. */
    private val week = "2026-11-16"
    private lateinit var db: Database

    /** The server's today: the Monday of [week], so that the tests' preps are made on days still to come. */
    private val today: LocalDate = LocalDate.parse(week)

    /** A server of its own, with a cooking site of one soup for the bank. */
    private fun ApplicationTestBuilder.start(): HttpClient {
        val site = createTempDirectory("cooking").toFile()
        File(site, "content/recipes/soup.md").apply { parentFile.mkdirs() }
            .writeText("---\ntitle: Bank soup\n---\n```ingredients\nservings: 2\n400 | g | std chicken\n```")
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = site,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens, today = { today }) }
        return createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
            }
        }
    }

    /**
     * Eggs on bread (one serving: two lines of 60 g egg, 80 g bread, 10 g butter, a gram of pepper that counts for nothing
     * and salt to taste; 442 kcal), set for lunch and snack; a yoghurt bowl set for snack only (180 kcal); rice bowls for
     * lunch and dinner to plan; a chicken salad of four servings that keeps three days, out of the rotation; and the
     * account's id. At 2000 kcal a day, a lunch of eggs on bread is 1.25 servings (552.5 kcal) and a snack 0.75.
     */
    private class Kitchen(
        val egg: Ingredient,
        val bread: Ingredient,
        val butter: Ingredient,
        val pepper: Ingredient,
        val eggs: RecipeSummary,
        val yoghurt: RecipeSummary,
        val rice: RecipeSummary,
        val salad: RecipeSummary,
        val userId: Long,
    )

    private suspend fun HttpClient.kitchen(): Kitchen {
        put("/api/profile") { setBody(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        suspend fun ingredient(name: String, code: Int?, negligible: Boolean = false) =
            post("/api/ingredients") { setBody(IngredientInput(name, code, negligible = negligible)) }.body<Ingredient>()
        suspend fun recipe(title: String, servings: Int, lines: String, vararg slots: MealSlot) =
            post("/api/recipes") { setBody(RecipeInput(title, servings, lines, "Make it.", mealSlots = slots.toList())) }.body<RecipeDetail>().summary
        val egg = ingredient("Std egg", 9301)
        val bread = ingredient("Std bread", 9302)
        val butter = ingredient("Std butter", 9303)
        ingredient("Std yoghurt", 9304)
        ingredient("Std rice", 9305)
        ingredient("Std chicken", 9306)
        val pepper = ingredient("Std pepper", null, negligible = true)
        val eggs = recipe(
            "Eggs on bread", 1, "60 | g | std egg\n60 | g | std egg\n80 | g | std bread\n10 | g | std butter\n1 | g | std pepper\n| | salt to taste",
            LUNCH, SNACK,
        )
        val yoghurt = recipe("Yoghurt bowl", 1, "300 | g | std yoghurt", SNACK)
        val rice = recipe("Rice bowl", 1, "190 | g | std rice", LUNCH, DINNER)
        val salad = recipe("Chicken salad", 4, "1200 | g | std chicken", LUNCH, DINNER)
        put("/api/recipes/${salad.id}/rotation") { setBody(RotationInput(false)) }
        put("/api/recipes/${salad.id}/keeps") { setBody(RecipeKeeps(days = 3)) }
        return Kitchen(egg, bread, butter, pepper, eggs, yoghurt, rice, salad, db.userQueries.selectBySubject("sub-alice").executeAsOne().id)
    }

    private suspend fun HttpClient.mark(recipeId: Long, standard: Boolean = true): HttpResponse =
        put("/api/recipes/$recipeId/standard") { setBody(StandardInput(standard)) }

    private suspend fun HttpClient.offered(weekStart: String = week): StandardMeals = get("/api/plans/$weekStart/standard-meals").body()

    /** [path] under the meal at [date]'s [slot] of [week]. */
    private fun mealPath(date: String, slot: MealSlot, path: String = ""): String =
        "/api/plans/$week/meals/${LocalDate.parse(date).toEpochDay() - LocalDate.parse(week).toEpochDay()}/$slot$path"

    private suspend fun HttpClient.choose(date: String, slot: MealSlot, recipeId: Long, eaten: Boolean = false): HttpResponse =
        put(mealPath(date, slot, "/standard")) { setBody(StandardChoice(recipeId, eaten)) }

    private suspend fun HttpClient.status(date: String, slot: MealSlot, status: MealStatus): HttpResponse =
        put(mealPath(date, slot, "/status")) { setBody(status) }

    private suspend fun HttpClient.plan(): WeekPlan = get("/api/plans/$week").body()

    private fun WeekPlan.at(date: String, slot: MealSlot): PlannedMeal? = days.single { it.date == date }.meals.firstOrNull { it.slot == slot }

    private suspend fun HttpClient.pantry(): Pantry = get("/api/pantry").body()

    private suspend fun HttpClient.spare(date: String): List<SparePortion> = get("/api/preps/spare/$date").body()

    /** The grams of [ingredient] in the account's stock, all entries together. */
    private fun Kitchen.stockOf(ingredient: Ingredient): Double =
        db.pantryQueries.selectStockOfAccount(userId).executeAsList().filter { it.ingredientId == ingredient.id }.sumOf { it.grams ?: 0.0 }

    private fun Kitchen.stockUp(ingredient: Ingredient, grams: Double) =
        StockRepository(db, FoodRepository(db), userId).putOnHandFrom(ingredient.id, LocalDate.parse(week), grams, StockSource.STOCK_UP)

    // --- Marking

    @Test
    fun aMealOfMineIsMarkedAStandardMealWhichMakesItsMissingIngredientsStaplesOnceAndSaysWhich() = testApplication {
        val http = start()
        val k = http.kitchen()
        http.put("/api/pantry/staples/${k.butter.id}")
        assertFalse(k.eggs.standardMeal, "no recipe is one until the account says so")

        val marked = http.mark(k.eggs.id).body<StandardSwitch>()
        assertTrue(marked.recipe.summary.standardMeal)
        assertEquals(listOf("Std egg", "Std bread"), marked.addedStaples, "the egg once though it has two lines; the butter was one; the pepper counts for nothing")
        assertEquals(emptyList(), marked.unlinked, "salt to taste is no ingredient to keep")
        val staples = http.pantry().staples.map { it.name }
        assertEquals(listOf("Std bread", "Std butter", "Std egg"), staples.sorted())
        assertTrue(http.get("/api/recipes").body<List<RecipeSummary>>().single { it.id == k.eggs.id }.standardMeal)

        // Marking it again adds nothing; unmarking leaves the staples.
        assertEquals(emptyList(), http.mark(k.eggs.id).body<StandardSwitch>().addedStaples)
        val unmarked = http.mark(k.eggs.id, standard = false).body<StandardSwitch>()
        assertFalse(unmarked.recipe.summary.standardMeal)
        assertEquals(emptyList(), unmarked.addedStaples)
        assertEquals(staples, http.pantry().staples.map { it.name })

        // A line linked to nothing yet is named, as it could not be made a staple.
        val jam = http.post("/api/recipes") { setBody(RecipeInput("Jam toast", 1, "80 | g | std bread\n20 | g | mystery jam", "Toast.", mealSlots = listOf(SNACK))) }
            .body<RecipeDetail>().summary
        assertEquals(listOf("mystery jam"), http.mark(jam.id).body<StandardSwitch>().unlinked)
    }

    @Test
    fun aBankRecipeABuildingBlockAndARecipeWithoutAmountsAreNoStandardMeal() = testApplication {
        val http = start()
        val k = http.kitchen()
        val soup = http.get("/api/recipes/bank").body<List<RecipeSummary>>().single()
        assertEquals(HttpStatusCode.Conflict, http.mark(soup.id).status, "a bank recipe is added first")

        // Added, marked and put back in the bank, it is no standard meal there, nor once added again.
        http.put("/api/recipes/${soup.id}/added") { setBody(AddedInput(added = true)) }
        assertEquals(HttpStatusCode.OK, http.mark(soup.id).status)
        http.put("/api/recipes/${soup.id}/added") { setBody(AddedInput(added = false)) }
        assertFalse(http.get("/api/recipes/bank").body<List<RecipeSummary>>().single().standardMeal)
        http.put("/api/recipes/${soup.id}/added") { setBody(AddedInput(added = true)) }
        assertFalse(http.get("/api/recipes/${soup.id}").body<RecipeDetail>().summary.standardMeal)

        // Saving the settings of a meal keeps it one, as a released app does; making it a building block ends it.
        http.mark(k.eggs.id)
        http.put("/api/recipes/${k.eggs.id}/settings") { setBody(RecipeSettings(RecipeKind.MEAL, listOf(LUNCH, SNACK))) }
        assertTrue(http.get("/api/recipes/${k.eggs.id}").body<RecipeDetail>().summary.standardMeal)
        http.put("/api/recipes/${k.eggs.id}/settings") { setBody(RecipeSettings(RecipeKind.COMPONENT, listOf(LUNCH, SNACK))) }
        assertFalse(http.get("/api/recipes/${k.eggs.id}").body<RecipeDetail>().summary.standardMeal)
        assertEquals(HttpStatusCode.Conflict, http.mark(k.eggs.id).status)

        val salted = http.post("/api/recipes") { setBody(RecipeInput("Salt", 1, "| | salt to taste", "Salt.")) }.body<RecipeDetail>().summary
        assertEquals(HttpStatusCode.Conflict, http.mark(salted.id).status, "nothing to size a portion by")
        assertEquals(HttpStatusCode.NotFound, http.mark(9999).status)
    }

    // --- Offered where a meal falls away

    @Test
    fun skippingALunchOffersTheStandardMealsSetForLunchAndNotOneSetOnlyForSnack() = testApplication {
        val http = start()
        val k = http.kitchen()
        http.put(mealPath("2026-11-17", LUNCH)) { setBody(MealChoice(k.rice.id)) }
        http.status("2026-11-17", LUNCH, MealStatus.SKIPPED)
        assertEquals(StandardMeals(week, emptyList()), http.offered(), "with none marked, nothing is offered")
        assertEquals(MealStatus.SKIPPED, http.plan().at("2026-11-17", LUNCH)!!.status, "and the skip is what it was")

        http.mark(k.eggs.id)
        http.mark(k.yoghurt.id)
        val offered = http.offered()
        val lunch = offered.meals.filter { it.slot == LUNCH }
        assertEquals(listOf("Eggs on bread"), lunch.map { it.title }, "the yoghurt bowl is set for snack only")
        assertEquals(1.25, lunch.single().portions)
        assertEquals(552.5, lunch.single().kcal, 1e-9)
        assertEquals(listOf("Eggs on bread" to 0.75, "Yoghurt bowl" to 1.0), offered.meals.filter { it.slot == SNACK }.map { it.title to it.portions })
        assertTrue(offered.closed.none { it.day == 1 && it.slot == LUNCH }, "the skipped lunch can take one")
    }

    @Test
    fun oneTapPutsTheStandardMealInTheSlotSizedWithinTheRangeEatenAndCountedByTheDay() = testApplication {
        val http = start()
        val k = http.kitchen()
        http.mark(k.eggs.id)
        http.put(mealPath("2026-11-17", LUNCH)) { setBody(MealChoice(k.rice.id)) }
        http.status("2026-11-17", LUNCH, MealStatus.SKIPPED)
        val before = http.get("/api/days/2026-11-17").body<DayView>()

        val chosen = http.choose("2026-11-17", LUNCH, k.eggs.id, eaten = true).body<StandardChosen>()
        val lunch = chosen.plan.at("2026-11-17", LUNCH)!!
        assertEquals(listOf(k.eggs.id, MealStatus.EATEN, 1.25, true), listOf(lunch.recipeId, lunch.status, lunch.portions, lunch.locked))
        val day = http.get("/api/days/2026-11-17").body<DayView>()
        assertEquals(before.eatenKcal + 552.5, day.eatenKcal, 1e-9)
        assertEquals(before.eatenProteinG + 1.25 * 21.7, day.eatenProteinG, 1e-9)
        assertEquals((before.eatenFibreG ?: 0.0) + 1.25 * 4.8, day.eatenFibreG!!, 1e-9)

        // Without "eaten" it is planned, as the Plan puts it; a profile's smaller range sizes it smaller.
        http.put("/api/profile") { setBody(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000, minPortions = 0.5, maxPortions = 1.0)) }
        val planned = http.choose("2026-11-18", LUNCH, k.eggs.id).body<StandardChosen>().plan.at("2026-11-18", LUNCH)!!
        assertEquals(MealStatus.PLANNED to 1.0, planned.status to planned.portions)

        // Only in the slots it is set for, and only a standard meal.
        assertEquals(HttpStatusCode.Conflict, http.choose("2026-11-18", DINNER, k.eggs.id).status)
        assertEquals(HttpStatusCode.Conflict, http.choose("2026-11-18", SNACK, k.yoghurt.id).status)
        assertEquals(HttpStatusCode.Conflict, http.choose("2026-11-17", LUNCH, k.eggs.id).status, "the eaten lunch stays")
    }

    // --- Shopping and stock

    @Test
    fun theListBuysNothingForAStandardMealsStaplesAndNamesOneThatIsOutOnceAmongThem() = testApplication {
        val http = start()
        val k = http.kitchen()
        http.mark(k.eggs.id)
        // Out of eggs: the pantry no longer has them among the staples, and none on hand.
        http.delete("/api/pantry/staples/${k.egg.id}")
        assertEquals(listOf("Std egg"), http.offered().meals.first { it.slot == LUNCH }.missing, "the chip names it")
        http.put("/api/pantry/on-hand/${k.egg.id}")
        assertEquals(emptyList(), http.offered().meals.first { it.slot == LUNCH }.missing, "on hand, it is at home")
        http.delete("/api/pantry/on-hand/${k.egg.id}")

        // Chosen all the same, twice in the week: the egg is a staple again, and the list names each staple once.
        val chosen = http.choose("2026-11-17", LUNCH, k.eggs.id, eaten = true).body<StandardChosen>()
        assertEquals(listOf("Std egg"), chosen.addedStaples)
        http.choose("2026-11-18", SNACK, k.eggs.id)
        assertTrue(http.pantry().staples.any { it.id == k.egg.id })
        val list = http.get("/api/plans/$week/shopping").body<ShoppingList>()
        assertTrue(list.items.none { it.name in setOf("Std egg", "Std bread", "Std butter") }, "no row: ${list.items.map { it.name }}")
        assertEquals(listOf("Std bread", "Std butter", "Std egg"), list.staples, "each staple once, the egg to restock")
        assertEquals(emptyList(), http.offered().meals.first { it.slot == LUNCH }.missing)
    }

    @Test
    fun aStandardMealUsesUpTheStockWhenEatenAndGivesItBackWhenUnEaten() = testApplication {
        val http = start()
        val k = http.kitchen()
        http.mark(k.eggs.id)
        k.stockUp(k.bread, 800.0)

        http.choose("2026-11-17", LUNCH, k.eggs.id, eaten = true)
        assertEquals(800.0 - 1.25 * 80, k.stockOf(k.bread), 1e-9)
        http.status("2026-11-17", LUNCH, MealStatus.PLANNED)
        assertEquals(800.0, k.stockOf(k.bread), 1e-9)
        http.status("2026-11-17", LUNCH, MealStatus.EATEN)
        assertEquals(700.0, k.stockOf(k.bread), 1e-9)
    }

    // --- Meal prep

    private suspend fun HttpClient.prepSalad(k: Kitchen) = post("/api/plans/$week/preps") {
        setBody(PrepInput(k.salad.id, "2026-11-17", meals = listOf(PrepSlot("2026-11-17", DINNER), PrepSlot("2026-11-18", LUNCH), PrepSlot("2026-11-19", DINNER))))
    }

    @Test
    fun aSkippedPreppedPortionStaysInTheBoxWhenAStandardMealTakesItsSlot() = testApplication {
        val http = start()
        val k = http.kitchen()
        http.mark(k.eggs.id)
        http.prepSalad(k)
        val chicken = http.get("/api/ingredients").body<List<Ingredient>>().single { it.name == "Std chicken" }
        k.stockUp(chicken, 2000.0)
        http.status("2026-11-17", DINNER, MealStatus.EATEN)
        http.status("2026-11-18", LUNCH, MealStatus.SKIPPED)
        val taken = k.stockOf(chicken)
        assertTrue(http.offered().closed.none { it.day == 2 && it.slot == LUNCH }, "the skipped portion's slot can take one")

        val plan = http.choose("2026-11-18", LUNCH, k.eggs.id, eaten = true).body<StandardChosen>().plan
        assertEquals(k.eggs.id to MealStatus.EATEN, plan.at("2026-11-18", LUNCH)!!.let { it.recipeId to it.status })
        val tuesday = plan.at("2026-11-17", DINNER)!!
        assertEquals(4.25, tuesday.prep!!.portions, "the batch is as large as it was")
        assertEquals(MealStatus.SKIPPED, tuesday.prep!!.meals.single { it.date == "2026-11-18" }.status)
        val spare = http.spare("2026-11-18").single()
        assertEquals(PrepSlot("2026-11-18", LUNCH) to 1.25, spare.skipped to spare.portions, "the box card still has it")
        assertEquals(taken, k.stockOf(chicken), 1e-9, "what the batch took stays taken")

        // Eaten on Thursday at lunch: the share leaves the box, the standard meal stays; un-made, all the chicken comes back.
        val moved = http.post("/api/plans/$week/preps/${spare.prepId}/spare") { setBody(SpareMove(spare.skipped, PrepSlot("2026-11-19", LUNCH))) }.body<WeekPlan>()
        assertEquals(k.salad.id to 1.25, moved.at("2026-11-19", LUNCH)!!.let { it.recipeId to it.portions })
        assertEquals(k.eggs.id, moved.at("2026-11-18", LUNCH)!!.recipeId)
        assertEquals(emptyList(), http.spare("2026-11-18"))
        assertEquals(taken, k.stockOf(chicken), 1e-9)
        http.status("2026-11-18", LUNCH, MealStatus.PLANNED)
        http.status("2026-11-17", DINNER, MealStatus.PLANNED)
        assertEquals(2000.0, k.stockOf(chicken), 1e-9, "every share comes back, the one moved from the box too")
    }

    @Test
    fun aStandardMealOverABoxedPortionKeepsItWhenReplacedAndGivesItBackWhenRemoved() = testApplication {
        val http = start()
        val k = http.kitchen()
        http.mark(k.eggs.id)
        http.prepSalad(k)
        val chicken = http.get("/api/ingredients").body<List<Ingredient>>().single { it.name == "Std chicken" }
        k.stockUp(chicken, 2000.0)
        http.status("2026-11-17", DINNER, MealStatus.EATEN)
        http.status("2026-11-18", LUNCH, MealStatus.SKIPPED)
        val taken = k.stockOf(chicken)
        http.choose("2026-11-18", LUNCH, k.eggs.id)

        // Unlocked and replanned, rerolled, chosen again: the share stays in the box under the slot.
        http.delete(mealPath("2026-11-18", LUNCH, "/lock"))
        http.post("/api/plans/$week/generate")
        assertEquals(k.eggs.id, http.plan().at("2026-11-18", LUNCH)!!.recipeId, "replanning keeps it")
        http.put(mealPath("2026-11-18", LUNCH)) { setBody(MealChoice(k.rice.id)) }
        assertEquals(1.25, http.spare("2026-11-18").single().portions)
        assertEquals(HttpStatusCode.Conflict, http.post("/api/plans/$week/preps") {
            setBody(PrepInput(k.salad.id, "2026-11-18", meals = listOf(PrepSlot("2026-11-18", LUNCH), PrepSlot("2026-11-19", LUNCH))))
        }.status, "a slot holds one share of a batch")

        // Removed: the skipped portion is back in its slot, with what the stock gave for it.
        val back = http.delete(mealPath("2026-11-18", LUNCH)).body<WeekPlan>().at("2026-11-18", LUNCH)!!
        assertEquals(listOf(k.salad.id, MealStatus.SKIPPED, 1.25), listOf(back.recipeId, back.status, back.portions))
        assertEquals(PrepSlot("2026-11-18", LUNCH), http.spare("2026-11-18").single().skipped)
        assertEquals(taken, k.stockOf(chicken), 1e-9)
        http.status("2026-11-17", DINNER, MealStatus.PLANNED)
        assertEquals(2000.0, k.stockOf(chicken), 1e-9)
    }

    @Test
    fun theMealThatMakesABatchAndAMealWithAPreppedSideTakeNoStandardMealAndAPortionInTheBoxIsStillCooked() = testApplication {
        val http = start()
        val k = http.kitchen()
        http.mark(k.eggs.id)
        http.put("/api/recipes/${k.eggs.id}/settings") { setBody(RecipeSettings(RecipeKind.MEAL, listOf(LUNCH, DINNER, SNACK))) }
        http.prepSalad(k)
        http.status("2026-11-17", DINNER, MealStatus.SKIPPED)
        val closed = http.offered().closed.single { it.day == 1 && it.slot == DINNER }
        assertTrue(closed.reason.contains("makes the prepped batch"), closed.reason)
        val refused = http.choose("2026-11-17", DINNER, k.eggs.id)
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(k.salad.id, http.plan().at("2026-11-17", DINNER)!!.recipeId, "it stays: ${refused.bodyAsText()}")

        // A batch still to make is cooked with a skipped later portion in the box too.
        http.status("2026-11-18", LUNCH, MealStatus.SKIPPED)
        http.choose("2026-11-18", LUNCH, k.eggs.id)
        val batch = http.plan().at("2026-11-17", DINNER)!!
        assertEquals(4.25, batch.cookPortions)
        // Removed as a whole, its share leaves the box and the standard meal stays.
        val removed = http.delete("/api/plans/$week/preps/${batch.prep!!.id}").body<WeekPlan>()
        assertEquals(listOf(null, k.eggs.id, null), listOf("2026-11-17" to DINNER, "2026-11-18" to LUNCH, "2026-11-19" to DINNER).map { removed.at(it.first, it.second)?.recipeId })
        assertEquals(emptyList(), http.spare("2026-11-18"))

        // A side follows its meal, so a skipped meal with a prepped side next to it keeps its place.
        for (date in listOf("2026-11-20", "2026-11-21")) http.put(mealPath(date, LUNCH)) { setBody(MealChoice(k.rice.id)) }
        http.post("/api/plans/$week/preps") {
            setBody(PrepInput(k.salad.id, "2026-11-20", meals = listOf(PrepSlot("2026-11-20", LUNCH, side = 0.5), PrepSlot("2026-11-21", LUNCH, side = 0.5))))
        }
        http.status("2026-11-21", LUNCH, MealStatus.SKIPPED)
        assertTrue(http.offered().closed.single { it.day == 5 && it.slot == LUNCH }.reason.contains("prepped side"))
        assertEquals(HttpStatusCode.Conflict, http.choose("2026-11-21", LUNCH, k.eggs.id).status)
    }

    // --- The planner

    @Test
    fun generatingAWeekGivesTheSamePlanWithTheSwitchOnAndOff() = testApplication {
        val http = start()
        val k = http.kitchen()
        // Its ingredients are staples already, so that marking changes nothing but the switch.
        for (ingredient in listOf(k.egg, k.bread, k.butter)) http.put("/api/pantry/staples/${ingredient.id}")
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        val scope = UserScope(k.userId, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile(), today = { today })
        fun meals() = scope.plans.get(week).days.flatMap { day -> day.meals.map { listOf(day.date, it.slot, it.recipeId, it.portions) } }

        val plans = listOf(false, true, false).map { standard ->
            scope.recipes.setStandard(k.eggs.id, standard)
            scope.plans.generate(week, seed = 7)
            meals()
        }
        assertTrue(plans.first().any { it[2] == k.eggs.id }, "eggs on bread is in the rotation and planned: ${plans.first()}")
        assertEquals(plans.first(), plans[1])
        assertEquals(plans.first(), plans[2])
    }
}
