package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.LabelNutrition
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.LogFoodInput
import dev.tobins.mealplanner.api.LogTextInput
import dev.tobins.mealplanner.api.LoggedFood
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingList
import app.cash.sqldelight.db.SqlDriver
import dev.tobins.mealplanner.server.db.Database
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Food eaten outside the plan picked from the catalogue (issue 362): counted from the food's values when it is logged or
 * its amount changed, in the day's totals with the plan's, and kept as it was whatever happens to the food afterwards;
 * typed entries as before.
 */
class FoodLogTest {
    /** Per 100 g: banana 90 kcal, 1 g protein, 2 g fibre; rice 350, 7, 2. */
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Fruit|Fruit|9101|Log banaan|Log banana||per 100g|90|1|0|0|20|15|2|1",
        "NEVO-Online 2025 9.0|Graan|Cereals|9102|Log rijst|Log rice||per 100g|350|7|1|0|78|0|2|5",
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

    /** The protein bar's label: 400 kcal, 30 g protein and 5 g fibre in 100 g, and a bar of 45 g. */
    private val barInput = IngredientInput(
        "Protein bar",
        label = LabelNutrition(FoodBasis.PER_100_G, Nutrition(400.0, 30.0, null, null, null, null, 5.0, null)),
        portions = listOf(Portion("bar", grams = 45.0)),
    )

    /** A profile, a banana of 120 g a piece, rice and the protein bar. */
    private class Kitchen(val banana: Ingredient, val rice: Ingredient, val bar: Ingredient, val userId: Long)

    private suspend fun HttpClient.kitchen(token: String): Kitchen {
        put("/api/profile") { setBody(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        suspend fun ingredient(input: IngredientInput) = post("/api/ingredients") { setBody(input) }.body<Ingredient>()
        val banana = ingredient(IngredientInput("Banana", 9101, gramsPerPiece = 120.0))
        val rice = ingredient(IngredientInput("Rice", 9102))
        val bar = ingredient(barInput)
        return Kitchen(banana, rice, bar, db.userQueries.selectBySubject("sub-$token").executeAsOne().id)
    }

    private suspend fun HttpClient.logFood(ingredient: Ingredient, amount: Double, unit: String, date: String = week): HttpResponse =
        post("/api/log/foods") { setBody(LogFoodInput(date, ingredient.id, amount, unit)) }

    private suspend fun HttpClient.day(date: String = week): DayView = get("/api/days/$date").body()

    private fun ok(response: HttpResponse, status: HttpStatusCode = HttpStatusCode.OK): HttpResponse =
        response.also { assertEquals(status, it.status, "${it.status}") }

    private suspend fun answer(response: HttpResponse, status: HttpStatusCode): String {
        assertEquals(status, response.status, response.bodyAsText())
        return response.bodyAsText()
    }

    private fun rounded(value: Double?) = value?.let { Math.round(it * 100) / 100.0 }

    /** kcal, protein and fibre of [entry], rounded. */
    private fun numbers(entry: LogEntry) = listOf(entry.kcal, entry.proteinG, entry.fibreG).map(::rounded)

    @Test
    fun aProductLoggedInItsPortionCountsItsValuesInTheDayAndFollowsItsAmount() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        // A banana planned as Monday's snack, not eaten yet: 108 kcal, 1.2 g protein, 2.4 g fibre.
        ok(http.post("/api/plans/$week/meals/0/SNACK/foods") { setBody(MealFoodInput(k.banana.id, 1.0, "piece")) })
        val stock = StockRepository(db, FoodRepository(db), k.userId)
        stock.putOnHandFrom(k.bar.id, LocalDate.parse(week), 90.0, StockSource.STOCK_UP)

        // One bar of 45 g: 180 kcal, 13.5 g protein, 2.25 g fibre, eaten; and in the day's forecast with the planned banana.
        val logged = ok(http.logFood(k.bar, 1.0, "bar"), HttpStatusCode.Created).body<DayView>()
        val entry = logged.extras.single()
        assertEquals("1 bar Protein bar", entry.description, "the line an app that does not know foods shows")
        assertEquals(LoggedFood(k.bar.id, "Protein bar", 1.0, "bar"), entry.food)
        assertEquals(listOf(180.0, 13.5, 2.25), numbers(entry))
        assertEquals(listOf(180.0, 13.5, 2.25), listOf(logged.eatenKcal, logged.eatenProteinG, logged.eatenFibreG).map(::rounded))
        assertEquals(listOf(288.0, 14.7, 4.65), listOf(logged.expectedKcal, logged.expectedProteinG, logged.expectedFibreG).map(::rounded))
        ok(http.put("/api/plans/$week/meals/0/SNACK/status") { setBody(MealStatus.EATEN) })
        assertEquals(listOf(288.0, 14.7, 4.65), http.day().let { listOf(it.eatenKcal, it.eatenProteinG, it.eatenFibreG) }.map(::rounded))
        // It was eaten outside the plan: not on the list, and the bars at home are all still there.
        val list = http.get("/api/plans/$week/shopping").body<ShoppingList>().items.map { it.name }
        assertTrue("Banana" in list && "Protein bar" !in list, "$list")
        assertEquals(90.0, db.pantryQueries.selectStockOfAccount(k.userId).executeAsList().single { it.ingredientId == k.bar.id }.grams)
        // A bar is what it starts in the next time it is picked, as in a slot or a recipe.
        assertEquals("bar", http.get("/api/ingredients").body<List<Ingredient>>().single { it.id == k.bar.id }.lastUnit)

        // Two bars instead: recounted, and the totals follow; then 30 g, in another of its units.
        val two = ok(http.put("/api/log/${entry.id}/food") { setBody(MealFoodInput(k.bar.id, 2.0, "bar")) }).body<DayView>()
        assertEquals(listOf(360.0, 27.0, 4.5), numbers(two.extras.single()))
        assertEquals("2 bar Protein bar", two.extras.single().description)
        assertEquals(listOf(468.0, 28.2, 6.9), listOf(two.eatenKcal, two.eatenProteinG, two.eatenFibreG).map(::rounded))
        val grams = ok(http.put("/api/log/${entry.id}/food") { setBody(MealFoodInput(k.bar.id, 30.0, "g")) }).body<DayView>().extras.single()
        assertEquals(listOf(120.0, 9.0, 1.5), numbers(grams))
        assertEquals("30 g Protein bar" to LoggedFood(k.bar.id, "Protein bar", 30.0, "g"), grams.description to grams.food)
        assertEquals(entry.id to week, grams.id to grams.date, "the same entry, on its day")

        // Removed: the day is the banana's alone.
        ok(http.delete("/api/log/${entry.id}"), HttpStatusCode.NoContent)
        assertEquals(listOf(108.0, 108.0), http.day().let { listOf(it.eatenKcal, it.expectedKcal) }.map(::rounded))
    }

    @Test
    fun aLoggedFoodKeepsItsNumbersWhenItsValuesChangeAndWhenItIsGoneAndTypedEntriesWorkAsBefore() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        val yesterday = "2026-11-15"
        val entry = ok(http.logFood(k.bar, 1.0, "bar", date = yesterday), HttpStatusCode.Created).body<DayView>().extras.single()
        val typed = ok(http.post("/api/log") { setBody(LogEntry(0, yesterday, "Apple", 80.0, 0.5)) }, HttpStatusCode.Created).body<DayView>().extras.last()
        assertEquals(LogEntry(typed.id, yesterday, "Apple", 80.0, 0.5), typed, "a typed entry has no food and no fibre")
        val before = http.day(yesterday)
        assertEquals(listOf(260.0, 14.0, 2.25), listOf(before.eatenKcal, before.eatenProteinG, before.eatenFibreG).map(::rounded))

        // The label read again with other values: the day that is over keeps what was logged.
        ok(http.put("/api/ingredients/${k.bar.id}") { setBody(barInput.copy(label = LabelNutrition(FoodBasis.PER_100_G, Nutrition(200.0, 20.0, null, null, null, null, 2.0, null)))) })
        assertEquals(before, http.day(yesterday))
        // Its amount changed now counts the values of now: one bar of 45 g is 90 kcal.
        assertEquals(listOf(90.0, 9.0, 0.9), numbers(ok(http.put("/api/log/${entry.id}/food") { setBody(MealFoodInput(k.bar.id, 1.0, "bar")) }).body<DayView>().extras.first()))
        val recounted = http.day(yesterday)

        // The product gone from the catalogue: the entry reads and counts as it did, and says its food is gone.
        driver.execute(null, "DELETE FROM ingredient WHERE id = ${k.bar.id}", 0)
        val gone = http.day(yesterday)
        assertEquals(recounted.extras.map { it.copy(food = it.food?.copy(ingredientId = null)) }, gone.extras)
        assertEquals("1 bar Protein bar", gone.extras.first().description)
        assertNull(gone.extras.first().food?.ingredientId)
        assertEquals(listOf(recounted.eatenKcal, recounted.eatenProteinG, recounted.eatenFibreG), listOf(gone.eatenKcal, gone.eatenProteinG, gone.eatenFibreG))
        // It cannot be recounted, and is typed over instead: a typed entry from then on.
        val refused = answer(http.put("/api/log/${entry.id}/food") { setBody(MealFoodInput(k.rice.id, 50.0, "g")) }, HttpStatusCode.Conflict)
        assertTrue("Protein bar is no longer among your foods" in refused, refused)
        val retyped = ok(http.put("/api/log/${entry.id}") { setBody(LogTextInput(" Protein bar ", 200.0, 20.0)) }).body<DayView>()
        assertEquals(LogEntry(entry.id, yesterday, "Protein bar", 200.0, 20.0), retyped.extras.first())
        assertEquals(listOf(280.0, 20.5, 0.0), listOf(retyped.eatenKcal, retyped.eatenProteinG, retyped.eatenFibreG).map(::rounded))

        // A typed entry is typed over and removed as before, and has no food to recount.
        assertEquals(HttpStatusCode.Conflict, http.put("/api/log/${typed.id}/food") { setBody(MealFoodInput(k.rice.id, 50.0, "g")) }.status)
        assertEquals(LogEntry(typed.id, yesterday, "Two apples", 160.0, 1.0), ok(http.put("/api/log/${typed.id}") { setBody(LogTextInput("Two apples", 160.0, 1.0)) }).body<DayView>().extras.last())
        ok(http.delete("/api/log/${typed.id}"), HttpStatusCode.NoContent)
        assertEquals(listOf(entry.id), http.day(yesterday).extras.map { it.id })
    }

    @Test
    fun aFoodIsLoggedOnlyInAnAmountItCanBeInAndATypedEntryOnlyAsText() = testApplication {
        start()
        val http = clientFor("alice")
        val k = http.kitchen("alice")
        assertEquals(HttpStatusCode.BadRequest, http.logFood(k.rice, 1.0, "piece").status, "rice has no weight per piece")
        assertEquals(HttpStatusCode.BadRequest, http.logFood(k.rice, 0.0, "g").status)
        assertEquals(HttpStatusCode.BadRequest, http.logFood(k.rice, 6000.0, "g").status)
        assertEquals(HttpStatusCode.BadRequest, http.logFood(k.rice, 50.0, "g", date = "16-11-2026").status)
        assertEquals(HttpStatusCode.NotFound, http.post("/api/log/foods") { setBody(LogFoodInput(week, 999_999, 1.0, "g")) }.status)
        // The food's fields are the server's to fill in: a typed entry cannot claim one.
        val claimed = LogEntry(0, week, "Rice", 10.0, 1.0, fibreG = 1.0, food = LoggedFood(k.rice.id, "Rice", 50.0, "g"))
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/log") { setBody(claimed) }.status)
        assertTrue(http.day().extras.isEmpty(), "nothing was logged")

        // A portion's name in any case, kept as the portion spells it; an entry that is not there is not changed.
        val entry = ok(http.logFood(k.bar, 1.0, "BAR"), HttpStatusCode.Created).body<DayView>().extras.single()
        assertEquals("bar", entry.food?.unit)
        assertEquals(HttpStatusCode.BadRequest, http.put("/api/log/${entry.id}/food") { setBody(MealFoodInput(k.bar.id, 1.0, "slice")) }.status)
        assertEquals(HttpStatusCode.BadRequest, http.put("/api/log/${entry.id}") { setBody(LogTextInput(" ", 10.0, 1.0)) }.status)
        assertEquals(HttpStatusCode.NotFound, http.put("/api/log/999999/food") { setBody(MealFoodInput(k.bar.id, 1.0, "bar")) }.status)
        assertEquals(HttpStatusCode.NotFound, http.put("/api/log/999999") { setBody(LogTextInput("Apple", 80.0, 0.5)) }.status)
        assertEquals(listOf(entry), http.day().extras)
    }

    @Test
    fun aLoggedFoodIsWordedInEnglishForAppsThatDoNotKnowFoods() {
        assertEquals("2 bar Protein bar", TrackingRepository.describe("Protein bar", 2.0, "bar"))
        assertEquals("1 piece Banana", TrackingRepository.describe("Banana", 1.0, "piece"))
        assertEquals("1.5 pieces Banana", TrackingRepository.describe("Banana", 1.5, "piece"))
        assertEquals("0.33 g Salt", TrackingRepository.describe("Salt", 1.0 / 3, "g"))
        assertEquals("150 ml Milk", TrackingRepository.describe("Milk", 150.0, "ml"))
    }
}
