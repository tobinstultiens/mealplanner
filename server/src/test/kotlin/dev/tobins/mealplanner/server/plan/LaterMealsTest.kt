package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.LaterShortfall
import dev.tobins.mealplanner.api.MealAdjustment
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.server.ServerConfig
import dev.tobins.mealplanner.server.TestTokens
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.ingredients.StockSource
import dev.tobins.mealplanner.server.module
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.shopping.leftToBuyGrams
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The later-meal warning and the shopping row (issue 226): an adjustment buys nothing itself, but where it took
 * what was at home for a meal still to cook (the stock, or what was ticked as bought), the Adjust screen says
 * which meal is how much short and the list asks for that again; with enough left, neither. Also when the
 * adjusted meal was eaten already.
 */
class LaterMealsTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Graan|Cereals|9101|Later rijst|Later rice||per 100g|350|7|1|0|78|0|2|5",
        "NEVO-Online 2025 9.0|Groente|Vegetables|9102|Later spinazie|Later spinach||per 100g|25|3|0|0|1|0|2|50",
    ).joinToString("\r\n")

    private val week = "2026-11-16"
    private lateinit var db: Database

    private fun ApplicationTestBuilder.start(): HttpClient {
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        return createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
            }
        }
    }

    /** A rice bowl (100 g rice a serving) and a spinach curry (200 g spinach a serving), and the account's id. */
    private class Kitchen(val rice: Ingredient, val spinach: Ingredient, val bowl: RecipeSummary, val curry: RecipeSummary, val userId: Long)

    private suspend fun HttpClient.kitchen(): Kitchen {
        put("/api/profile") { setBody(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        suspend fun ingredient(name: String, code: Int) = post("/api/ingredients") { setBody(IngredientInput(name, code)) }.body<Ingredient>()
        suspend fun recipe(title: String, lines: String) = post("/api/recipes") { setBody(RecipeInput(title, 1, lines, "Cook.")) }.body<RecipeDetail>().summary
        val rice = ingredient("Later rice", 9101)
        val spinach = ingredient("Later spinach", 9102)
        val bowl = recipe("Rice bowl", "100 | g | later rice")
        val curry = recipe("Spinach curry", "200 | g | later spinach")
        return Kitchen(rice, spinach, bowl, curry, db.userQueries.selectBySubject("sub-alice").executeAsOne().id)
    }

    private fun Kitchen.plan(day: Int, recipe: RecipeSummary) =
        db.planQueries.upsertMeal(userId, week, day.toLong(), MealSlot.DINNER.name, recipe.id, recipe.title, 1.0, 600.0, 30.0, 0, 0, "PLANNED")

    /** Monday a rice bowl, Thursday the spinach curry. */
    private fun Kitchen.planWeek() {
        plan(0, bowl)
        plan(3, curry)
    }

    private fun Kitchen.stockUp(ingredient: Ingredient, grams: Double) =
        StockRepository(db, FoodRepository(db), userId).putOnHandFrom(ingredient.id, LocalDate.parse(week), grams, StockSource.STOCK_UP)

    private suspend fun HttpClient.adjust(day: Int, vararg changes: Pair<Ingredient, Double>): MealLines {
        val response = put("/api/plans/$week/meals/$day/DINNER/lines") { setBody(MealAdjustment(changes.map { (ingredient, grams) -> MealLineChange(ingredient.id, grams) })) }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private suspend fun HttpClient.lines(day: Int): MealLines = get("/api/plans/$week/meals/$day/DINNER/lines").body()

    private suspend fun HttpClient.list(): ShoppingList = get("/api/plans/$week/shopping").body()

    private suspend fun HttpClient.row(name: String): ShoppingItem = list().items.single { it.name == name }

    private suspend fun HttpClient.tick(ingredient: Ingredient) =
        assertEquals(HttpStatusCode.OK, put("/api/plans/$week/shopping") { setBody(ShoppingCheck("i${ingredient.id}", true)) }.status)

    private suspend fun HttpClient.mark(day: Int, status: MealStatus) =
        assertEquals(HttpStatusCode.OK, put("/api/plans/$week/meals/$day/DINNER/status") { setBody(status) }.status)

    private fun Kitchen.curryShort(grams: Double) = listOf(LaterShortfall(3, MealSlot.DINNER, "Spinach curry", spinach.id, "Later spinach", grams))

    /**
     * What [row] asks for, as the app works it out (`moreNeeded`, without its rounding step of 25 g and 5 %): a
     * ticked row the need beyond what was bought, but no more than the meals to come need beyond the stock; a row
     * not ticked what the meals to come need beyond the stock.
     */
    private fun asksFor(row: ShoppingItem): Double {
        val beyondStock = row.leftToBuyGrams() ?: Double.MAX_VALUE
        return if (row.checked) minOf(row.grams!! - row.boughtGrams!!, beyondStock).coerceAtLeast(0.0) else beyondStock
    }

    /** The spinach row asks for nothing yet: what is at home covers the week, as each case starts. */
    private suspend fun HttpClient.covered() = assertEquals(0.0, asksFor(row("Later spinach")), 1e-9)

    /** The warning names [grams] in all, and the spinach row asks for exactly that: one number for both. */
    private suspend fun HttpClient.agree(lines: MealLines, grams: Double) {
        assertEquals(grams, lines.shortfalls.sumOf { it.grams }, 1e-9, "the card")
        assertEquals(grams, asksFor(row("Later spinach")), 1e-9, "the row")
    }

    @Test
    fun withStockOnlyTheCardAndTheRowAgree() = testApplication {
        val http = start()
        val k = http.kitchen()
        k.planWeek()
        k.stockUp(k.spinach, 250.0)
        http.covered()
        http.agree(http.adjust(0, k.spinach to 100.0), 50.0)
    }

    @Test
    fun withATickOnlyTheCardAndTheRowAgree() = testApplication {
        val http = start()
        val k = http.kitchen()
        k.planWeek()
        http.tick(k.spinach)
        http.covered()
        http.agree(http.adjust(0, k.spinach to 100.0), 100.0)
    }

    @Test
    fun withStockAndATickTheStockIsCountedOnce() = testApplication {
        // 100 g in stock: the row said "buy 100 g", and ticking it remembers the week's 200 g, the stock in it.
        // At home are 200 g, not 300 g: the bowl's 100 g leaves the curry 100 g short.
        val http = start()
        val k = http.kitchen()
        k.planWeek()
        k.stockUp(k.spinach, 100.0)
        assertEquals(100.0, asksFor(http.row("Later spinach")), 1e-9)
        http.tick(k.spinach)
        http.covered()
        http.agree(http.adjust(0, k.spinach to 100.0), 100.0)
    }

    @Test
    fun withStockAndATickAnAdditionWithinTheSurplusAsksForNothing() = testApplication {
        // 300 g in stock for a 200 g curry, and the row ticked all the same: 100 g to spare.
        val http = start()
        val k = http.kitchen()
        k.planWeek()
        k.stockUp(k.spinach, 300.0)
        http.tick(k.spinach)
        http.covered()
        val before = http.row("Later spinach")
        http.agree(http.adjust(0, k.spinach to 60.0), 0.0)
        assertEquals(before, http.row("Later spinach"), "the row as it was")
        http.agree(http.adjust(0, k.spinach to 150.0), 50.0)
    }

    @Test
    fun withStockAndATickAnAdditionAfterEatingIsAskedForOnce() = testApplication {
        val http = start()
        val k = http.kitchen()
        k.planWeek()
        k.stockUp(k.spinach, 100.0)
        http.tick(k.spinach)
        http.mark(0, MealStatus.EATEN)
        http.covered()
        // Said after eating: the bowl takes the 100 g of stock; the 100 g bought is the curry's half.
        http.agree(http.adjust(0, k.spinach to 100.0), 100.0)
        assertEquals(emptyList(), db.pantryQueries.selectStockOfAccount(k.userId).executeAsList(), "the stock went to the bowl")
    }

    @Test
    fun aStapleHasNoRowSoNothingIsSaidOfIt() = testApplication {
        val http = start()
        val k = http.kitchen()
        k.planWeek()
        k.stockUp(k.spinach, 200.0)
        assertEquals(HttpStatusCode.OK, http.put("/api/pantry/staples/${k.spinach.id}").status)
        assertEquals(emptyList(), http.adjust(0, k.spinach to 100.0).shortfalls)
        assertTrue(http.list().items.none { it.name == "Later spinach" })
    }

    @Test
    fun spinachBoughtForALaterMealAndAddedToABowlIsAskedForAgain() = testApplication {
        val http = start()
        val k = http.kitchen()
        k.planWeek()
        http.tick(k.spinach)
        assertEquals(200.0, http.row("Later spinach").boughtGrams)

        // The bowl gets 100 g of the 200 g bought for Thursday's curry: the curry is 100 g short.
        assertEquals(k.curryShort(100.0), http.adjust(0, k.spinach to 100.0).shortfalls)
        assertEquals(k.curryShort(100.0), http.lines(0).shortfalls)
        val asked = http.row("Later spinach")
        assertTrue(asked.checked)
        assertEquals(listOf(300.0, 200.0, 300.0), listOf(asked.grams, asked.boughtGrams, asked.toComeGrams), "the row needs 100 g more than was bought")
        assertEquals(listOf("Spinach curry", "Rice bowl"), asked.recipes)
        assertEquals(100.0, http.row("Later rice").grams, "no other row changes")
        assertEquals(emptyList(), http.lines(3).shortfalls, "the curry itself took nothing from another meal")

        // Eaten, the bowl is done; the curry is still short, and the row still asks for it.
        http.mark(0, MealStatus.EATEN)
        assertEquals(k.curryShort(100.0), http.lines(0).shortfalls)
        val eaten = http.row("Later spinach")
        assertEquals(listOf(300.0, 200.0, 200.0), listOf(eaten.grams, eaten.boughtGrams, eaten.toComeGrams))

        // Put back to its recipe, the bowl leaves the curry its spinach.
        assertEquals(emptyList(), http.adjust(0).shortfalls)
        assertEquals(200.0, http.row("Later spinach").grams)
    }

    @Test
    fun anAdjustmentAfterEatingThatTakesTheStockOfALaterMealIsAskedForAgain() = testApplication {
        val http = start()
        val k = http.kitchen()
        k.planWeek()
        k.stockUp(k.spinach, 250.0)
        // The bowl was eaten as its recipe; spinach is added to it afterwards, from the stock as it is now.
        http.mark(0, MealStatus.EATEN)
        assertEquals(k.curryShort(50.0), http.adjust(0, k.spinach to 100.0).shortfalls)
        val row = http.row("Later spinach")
        assertTrue(!row.checked && row.onHand)
        assertEquals(listOf(150.0, 200.0), listOf(row.onHandGrams, row.toComeGrams))
        assertEquals(50.0, row.leftToBuyGrams(), "what the curry is short is to buy")

        // Un-eaten, the bowl gives the spinach back but will take it again: still 50 g short.
        http.mark(0, MealStatus.PLANNED)
        assertEquals(k.curryShort(50.0), http.lines(0).shortfalls)
        val planned = http.row("Later spinach")
        assertEquals(listOf(250.0, 300.0), listOf(planned.onHandGrams, planned.toComeGrams))
        assertEquals(50.0, planned.leftToBuyGrams())
    }

    @Test
    fun theMealsLeftShortAddUpToWhatTheRowAsksForAgain() = testApplication {
        // The emulator's case in small: three bowls of 56.7 g spinach bought for, and Monday's set to 150 g. Tuesday
        // is 36.6 g short and Wednesday 56.7 g: 93.3 g, which the app rounds once on both the card and the row.
        val http = start()
        val k = http.kitchen()
        val bowl = http.post("/api/recipes") { setBody(RecipeInput("Spinach bowl", 1, "56.7 | g | later spinach", "Cook.")) }.body<RecipeDetail>().summary
        for (day in 0..2) k.plan(day, bowl)
        http.tick(k.spinach)
        val short = http.adjust(0, k.spinach to 150.0).shortfalls
        assertEquals(listOf(1 to 36.6, 2 to 56.7), short.map { it.day to Math.round(it.grams * 10) / 10.0 })
        val row = http.row("Later spinach")
        assertEquals(row.grams!! - row.boughtGrams!!, short.sumOf { it.grams }, 1e-9)
    }

    @Test
    fun withEnoughLeftThereIsNoWarningAndNoRowChanges() = testApplication {
        val http = start()
        val k = http.kitchen()
        k.planWeek()
        k.stockUp(k.spinach, 300.0)
        val before = http.list()
        assertEquals(emptyList(), http.adjust(0, k.spinach to 100.0).shortfalls)
        assertEquals(before.items, http.list().items, "an adjustment buys nothing itself")
    }

    @Test
    fun withNothingKnownAtHomeTheAdditionCameFromTheFridge() = testApplication {
        val http = start()
        val k = http.kitchen()
        k.planWeek()
        val before = http.list()
        assertEquals(emptyList(), http.adjust(0, k.spinach to 100.0).shortfalls)
        assertEquals(before.items, http.list().items)

        // On hand by hand, in an amount nobody knows: nothing to say either.
        assertEquals(HttpStatusCode.OK, http.put("/api/pantry/on-hand/${k.spinach.id}").status)
        assertEquals(emptyList(), http.lines(0).shortfalls)
    }

    @Test
    fun aLaterMealAdjustedToLessIsNotShort() = testApplication {
        val http = start()
        val k = http.kitchen()
        k.planWeek()
        http.tick(k.spinach)
        http.adjust(3, k.spinach to 100.0)
        assertEquals(emptyList(), http.adjust(0, k.spinach to 100.0).shortfalls)
        assertEquals(200.0, http.row("Later spinach").grams)
    }

    @Test
    fun aMealCookedAfterTheOneThatNeedsItTakesNothingFromIt() = testApplication {
        val http = start()
        val k = http.kitchen()
        k.planWeek()
        k.plan(4, k.bowl)
        http.tick(k.spinach)
        // Friday's bowl: Thursday's curry has had its spinach by then.
        assertEquals(emptyList(), http.adjust(4, k.spinach to 100.0).shortfalls)
        assertEquals(200.0, assertNotNull(http.row("Later spinach").grams))
    }
}
