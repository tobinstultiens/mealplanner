package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealAdjustment
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.WeekPlan
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
import kotlin.test.assertTrue

/**
 * A meal adjusted to what was eaten (issue 226): lines added, changed, removed and reset; the numbers it
 * counts everywhere; the recipe and other meals untouched; and the stock on eating, un-eating and
 * adjusting a meal that was eaten, also a leftover lunch and its dinner.
 */
class MealAdjustmentTest {
    /** Per 100 g: rice 350 kcal, 7 g protein, 2 g fibre; beef 150, 20, 0; egg 140, 12, 0; edamame 120, 11, 5. */
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Graan|Cereals|9001|Bowl rijst|Bowl rice||per 100g|350|7|1|0|78|0|2|5",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|9002|Bowl rund|Bowl beef||per 100g|150|20|4|2|0|0|0|50",
        "NEVO-Online 2025 9.0|Ei|Eggs|9003|Bowl ei|Bowl egg||per 100g|140|12|10|3|0|0|0|140",
        "NEVO-Online 2025 9.0|Peulvruchten|Legumes|9004|Bowl edamame|Bowl edamame||per 100g|120|11|5|1|9|2|5|5",
    ).joinToString("\r\n")

    private val week = "2026-11-16"
    private lateinit var db: Database

    private fun ApplicationTestBuilder.start() {
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
    }

    private fun ApplicationTestBuilder.clientFor(token: String): HttpClient = createClient {
        install(ContentNegotiation) { json() }
        defaultRequest {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
        }
    }

    /** An account's catalogue and its rice bowl: 2 servings of 200 g rice, 300 g beef and 100 g edamame. */
    private class Kitchen(val rice: Ingredient, val beef: Ingredient, val edamame: Ingredient, val egg: Ingredient, val bowl: RecipeSummary, val userId: Long)

    /** A serving of the bowl: 635 kcal, 42.5 g protein and 4.5 g fibre, which is what a meal of it stores as planned. */
    private val bowlKcal = 635.0
    private val bowlProtein = 42.5
    private val bowlFibre = 4.5

    private suspend fun HttpClient.kitchen(token: String): Kitchen {
        put("/api/profile") { setBody(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        suspend fun ingredient(name: String, code: Int, gramsPerPiece: Double? = null) =
            post("/api/ingredients") { setBody(IngredientInput(name, code, gramsPerPiece)) }.body<Ingredient>()
        val rice = ingredient("Bowl rice", 9001)
        val beef = ingredient("Bowl beef", 9002)
        val edamame = ingredient("Bowl edamame", 9004)
        val egg = ingredient("Bowl egg", 9003, gramsPerPiece = 60.0)
        val bowl = post("/api/recipes") {
            setBody(RecipeInput("Rice bowl", 2, "200 | g | bowl rice\n300 | g | bowl beef\n100 | g | bowl edamame\n| | salt to taste", "Bowl."))
        }.body<RecipeDetail>().summary
        val userId = db.userQueries.selectBySubject("sub-$token").executeAsOne().id
        return Kitchen(rice, beef, edamame, egg, bowl, userId)
    }

    /** Plans [portions] of the bowl in [day]'s [slot], stored with the numbers the planner would give it. */
    private fun Kitchen.plan(day: Int, slot: MealSlot, portions: Double = 1.0, leftover: Boolean = false) =
        db.planQueries.upsertMeal(userId, week, day.toLong(), slot.name, bowl.id, bowl.title, portions, bowlKcal * portions, bowlProtein * portions, if (leftover) 1 else 0, 0, "PLANNED")

    private fun Kitchen.stockUp(ingredient: Ingredient, grams: Double) =
        StockRepository(db, FoodRepository(db), userId).putOnHandFrom(ingredient.id, LocalDate.parse(week), grams, StockSource.STOCK_UP)

    /** What is left in the stock, by ingredient name, of every entry. */
    private fun Kitchen.stock(): Map<String, Double?> {
        val names = listOf(rice, beef, edamame, egg).associate { it.id to it.name }
        return db.pantryQueries.selectStockOfAccount(userId).executeAsList().associate { names.getValue(it.ingredientId) to it.grams }
    }

    private suspend fun HttpClient.adjust(day: Int, slot: MealSlot, vararg changes: Pair<Ingredient, Double>): HttpResponse =
        put("/api/plans/$week/meals/$day/$slot/lines") { setBody(MealAdjustment(changes.map { (ingredient, grams) -> MealLineChange(ingredient.id, grams) })) }

    private suspend fun HttpClient.lines(day: Int, slot: MealSlot): MealLines = get("/api/plans/$week/meals/$day/$slot/lines").body()

    private suspend fun HttpClient.meal(day: Int, slot: MealSlot): PlannedMeal =
        get("/api/plans/$week").body<WeekPlan>().days[day].meals.single { it.slot == slot }

    private suspend fun HttpClient.mark(day: Int, slot: MealSlot, status: MealStatus) =
        assertEquals(HttpStatusCode.OK, put("/api/plans/$week/meals/$day/$slot/status") { setBody(status) }.status)

    @Test
    fun aMealCountsWhatWasInItEverywhereAndTheRecipeStaysAsItIs() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        k.plan(0, MealSlot.DINNER)
        k.plan(1, MealSlot.DINNER)
        val recipeBefore = http.get("/api/recipes/${k.bowl.id}").body<RecipeDetail>()

        // As planned: the recipe's lines for one serving, seasoning without an amount left out.
        val planned = http.lines(0, MealSlot.DINNER)
        assertEquals(listOf("Bowl rice" to 100.0, "Bowl beef" to 150.0, "Bowl edamame" to 50.0), planned.lines.map { it.name to it.grams })
        assertTrue(planned.lines.all { it.fromRecipe && !it.changed } && !planned.adjusted)
        assertEquals(listOf(bowlKcal, bowlProtein, bowlFibre), listOf(planned.kcal, planned.proteinG, planned.fibreG))

        // An egg added, the edamame left out and more rice: 150 g rice (525 kcal, 10.5 g protein, 3 g fibre),
        // 150 g beef (225, 30, 0) and an egg of 60 g (84, 7.2, 0).
        val adjusted = http.adjust(0, MealSlot.DINNER, k.egg to 60.0, k.edamame to 0.0, k.rice to 150.0).body<MealLines>()
        assertTrue(adjusted.adjusted && adjusted.complete)
        assertEquals(
            listOf(Triple("Bowl rice", 150.0, 100.0), Triple("Bowl beef", 150.0, 150.0), Triple("Bowl edamame", 0.0, 50.0), Triple("Bowl egg", 60.0, null)),
            adjusted.lines.map { Triple(it.name, it.grams, it.recipeGrams) },
        )
        assertEquals(listOf(true, false, true, true), adjusted.lines.map { it.changed })
        assertFalse(adjusted.lines.last().fromRecipe)
        assertEquals(60.0, adjusted.lines.last().gramsPerPiece, "the egg is counted in pieces")
        assertEquals(834.0, adjusted.kcal, 1e-6)
        assertEquals(47.7, adjusted.proteinG, 1e-6)
        assertEquals(3.0, adjusted.fibreG, 1e-6)
        assertEquals(adjusted, http.lines(0, MealSlot.DINNER))

        // The plan's meal and day, and the day view, count the adjusted numbers.
        val meal = http.meal(0, MealSlot.DINNER)
        assertTrue(meal.adjusted)
        assertEquals(listOf(834.0, 47.7, 3.0), listOf(meal.kcal, meal.proteinG, meal.fibreG!!).map { Math.round(it * 1000) / 1000.0 })
        val day = http.get("/api/plans/$week").body<WeekPlan>().days[0]
        assertEquals(meal.kcal, day.kcal, 1e-9)
        http.mark(0, MealSlot.DINNER, MealStatus.EATEN)
        val today = http.get("/api/days/2026-11-16").body<DayView>()
        assertTrue(today.meals.single().adjusted)
        assertEquals(listOf(meal.kcal, meal.proteinG, 3.0), listOf(today.eatenKcal, today.eatenProteinG, today.eatenFibreG!!).map { Math.round(it * 1000) / 1000.0 })

        // The recipe and the same recipe's meal on another day are as they were.
        assertEquals(recipeBefore, http.get("/api/recipes/${k.bowl.id}").body<RecipeDetail>())
        val other = http.meal(1, MealSlot.DINNER)
        assertFalse(other.adjusted)
        assertEquals(listOf(bowlKcal, bowlProtein), listOf(other.kcal, other.proteinG))

        // Reset to the recipe: the numbers stored when it was planned again.
        val reset = http.adjust(0, MealSlot.DINNER).body<MealLines>()
        assertFalse(reset.adjusted)
        assertEquals(planned.lines, reset.lines)
        val back = http.meal(0, MealSlot.DINNER)
        assertEquals(listOf(false, bowlKcal, bowlProtein), listOf(back.adjusted, back.kcal, back.proteinG))
    }

    @Test
    fun anAmountAsTheRecipeHasItIsNoChange() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        k.plan(0, MealSlot.LUNCH, portions = 1.5)
        // 1.5 servings have 150 g of rice; an egg of 0 g is nothing added.
        val same = http.adjust(0, MealSlot.LUNCH, k.rice to 150.0, k.egg to 0.0).body<MealLines>()
        assertFalse(same.adjusted)
        assertFalse(http.meal(0, MealSlot.LUNCH).adjusted)
        assertEquals(bowlKcal * 1.5, http.meal(0, MealSlot.LUNCH).kcal, 1e-9)
    }

    @Test
    fun anIngredientWithoutNumbersIsListedAndTheTotalsSayTheyAreIncomplete() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        k.plan(0, MealSlot.DINNER)
        val sauce = http.post("/api/ingredients") { setBody(IngredientInput("House chilli sauce")) }.body<Ingredient>()
        val lines = http.adjust(0, MealSlot.DINNER, sauce to 20.0).body<MealLines>()
        assertEquals("House chilli sauce" to 20.0, lines.lines.last().let { it.name to it.grams })
        assertFalse(lines.complete)
        assertEquals(listOf("House chilli sauce"), lines.uncounted)
        assertEquals(bowlKcal, lines.kcal, 1e-9, "it counts as nothing")
        assertTrue(http.meal(0, MealSlot.DINNER).adjusted)
    }

    @Test
    fun whatCannotBeAdjustedIsRefusedAndChangesNothing() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        k.plan(0, MealSlot.DINNER)
        k.plan(1, MealSlot.DINNER)
        http.adjust(0, MealSlot.DINNER, k.egg to 60.0)
        val before = http.lines(0, MealSlot.DINNER)

        http.mark(1, MealSlot.DINNER, MealStatus.SKIPPED)
        val skipped = http.adjust(1, MealSlot.DINNER, k.egg to 60.0)
        assertEquals(HttpStatusCode.Conflict, skipped.status)
        assertTrue("skipped" in skipped.bodyAsText(), skipped.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, http.adjust(2, MealSlot.DINNER, k.egg to 60.0).status, "an empty slot")
        assertEquals(HttpStatusCode.NotFound, http.get("/api/plans/$week/meals/2/DINNER/lines").status)

        val unknown = http.put("/api/plans/$week/meals/0/DINNER/lines") { setBody(MealAdjustment(listOf(MealLineChange(99_999, 10.0)))) }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        for ((case, body) in listOf(
            "a negative amount" to MealAdjustment(listOf(MealLineChange(k.egg.id, -1.0))),
            "more than 5 kg" to MealAdjustment(listOf(MealLineChange(k.egg.id, 5001.0))),
            "an ingredient twice" to MealAdjustment(listOf(MealLineChange(k.egg.id, 60.0), MealLineChange(k.egg.id, 120.0))),
        )) {
            assertEquals(HttpStatusCode.BadRequest, http.put("/api/plans/$week/meals/0/DINNER/lines") { setBody(body) }.status, case)
        }
        assertEquals(before, http.lines(0, MealSlot.DINNER))
    }

    @Test
    fun replacingOrRemovingAMealDropsWhatWasChangedInIt() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        val other = http.post("/api/recipes") { setBody(RecipeInput("Beef plate", 1, "200 | g | bowl beef", "Plate.")) }.body<RecipeDetail>().summary
        k.plan(0, MealSlot.DINNER)
        http.adjust(0, MealSlot.DINNER, k.egg to 60.0)

        // Another recipe chosen for the slot: the egg does not move to it.
        val chosen = http.put("/api/plans/$week/meals/0/DINNER") { setBody(MealChoice(other.id)) }.body<WeekPlan>().days[0].meals.single()
        assertEquals(other.id to false, chosen.recipeId to chosen.adjusted)
        assertTrue(http.lines(0, MealSlot.DINNER).lines.none { it.ingredientId == k.egg.id })

        // Removed and the bowl chosen again: it starts as its recipe.
        http.adjust(0, MealSlot.DINNER, k.egg to 60.0)
        assertEquals(HttpStatusCode.OK, http.delete("/api/plans/$week/meals/0/DINNER").status)
        http.put("/api/plans/$week/meals/0/DINNER") { setBody(MealChoice(k.bowl.id)) }
        assertFalse(http.meal(0, MealSlot.DINNER).adjusted)
        assertEquals(emptyList(), db.planQueries.selectWeekLines(k.userId, week).executeAsList(), "no lines are left behind")
    }

    @Test
    fun eatingAnAdjustedMealTakesWhatWasInItAndUnEatingGivesItAllBack() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        k.plan(0, MealSlot.DINNER)
        k.stockUp(k.rice, 1000.0)
        k.stockUp(k.edamame, 500.0)
        k.stockUp(k.egg, 600.0)
        val before = k.stock()

        // Less rice, no edamame, an egg; planned still, so the stock waits for it to be eaten.
        http.adjust(0, MealSlot.DINNER, k.rice to 80.0, k.edamame to 0.0, k.egg to 60.0)
        assertEquals(before, k.stock())
        http.mark(0, MealSlot.DINNER, MealStatus.EATEN)
        assertEquals(mapOf("Bowl rice" to 920.0, "Bowl edamame" to 500.0, "Bowl egg" to 540.0), k.stock())

        http.mark(0, MealSlot.DINNER, MealStatus.PLANNED)
        assertEquals(before, k.stock(), "exactly as before it was eaten")
    }

    @Test
    fun adjustingAnEatenMealMovesTheStockByTheDifference() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        k.plan(0, MealSlot.DINNER)
        k.stockUp(k.rice, 1000.0)
        k.stockUp(k.egg, 600.0)
        val before = k.stock()
        http.mark(0, MealSlot.DINNER, MealStatus.EATEN)
        assertEquals(mapOf("Bowl rice" to 900.0, "Bowl egg" to 600.0), k.stock())

        // Two eggs and less rice, said after eating.
        http.adjust(0, MealSlot.DINNER, k.egg to 120.0, k.rice to 70.0)
        assertEquals(mapOf("Bowl rice" to 930.0, "Bowl egg" to 480.0), k.stock())
        // One egg after all; then the recipe again.
        http.adjust(0, MealSlot.DINNER, k.egg to 60.0, k.rice to 70.0)
        assertEquals(mapOf("Bowl rice" to 930.0, "Bowl egg" to 540.0), k.stock())
        http.adjust(0, MealSlot.DINNER)
        assertEquals(mapOf("Bowl rice" to 900.0, "Bowl egg" to 600.0), k.stock())

        // Adjusted again and un-eaten: everything back as before it was eaten.
        http.adjust(0, MealSlot.DINNER, k.egg to 180.0)
        http.mark(0, MealSlot.DINNER, MealStatus.SKIPPED)
        assertEquals(before, k.stock())
    }

    @Test
    fun aLeftoverLunchIsAdjustedOnItsOwnAndTheStockEndsAsItBegan() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        k.plan(0, MealSlot.DINNER)
        k.plan(1, MealSlot.LUNCH, leftover = true)
        k.stockUp(k.rice, 1000.0)
        k.stockUp(k.edamame, 1000.0)
        k.stockUp(k.egg, 600.0)
        val before = k.stock()

        // The lunch gets an egg and no edamame; its dinner keeps its lines.
        http.adjust(1, MealSlot.LUNCH, k.egg to 60.0, k.edamame to 0.0)
        assertFalse(http.lines(0, MealSlot.DINNER).adjusted)
        assertTrue(http.meal(1, MealSlot.LUNCH).adjusted)
        assertFalse(http.meal(0, MealSlot.DINNER).adjusted)

        // The dinner is cooked with its leftovers, as adjusted: their share is taken with it.
        http.mark(0, MealSlot.DINNER, MealStatus.EATEN)
        assertEquals(mapOf("Bowl rice" to 800.0, "Bowl edamame" to 950.0, "Bowl egg" to 540.0), k.stock())

        // A second egg on the lunch, its share taken with the dinner: the stock moves by that egg.
        http.adjust(1, MealSlot.LUNCH, k.egg to 120.0, k.edamame to 0.0)
        assertEquals(mapOf("Bowl rice" to 800.0, "Bowl edamame" to 950.0, "Bowl egg" to 480.0), k.stock())
        http.mark(1, MealSlot.LUNCH, MealStatus.EATEN)
        assertEquals(mapOf("Bowl rice" to 800.0, "Bowl edamame" to 950.0, "Bowl egg" to 480.0), k.stock(), "eaten, it takes nothing more")

        // Un-eating the dinner gives back its own share; un-eating the lunch then gives back the rest.
        http.mark(0, MealSlot.DINNER, MealStatus.PLANNED)
        assertEquals(mapOf("Bowl rice" to 900.0, "Bowl edamame" to 1000.0, "Bowl egg" to 480.0), k.stock())
        http.mark(1, MealSlot.LUNCH, MealStatus.PLANNED)
        assertEquals(before, k.stock())
    }
}
