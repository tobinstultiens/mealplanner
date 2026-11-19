package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealAdjustment
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealSlot.DINNER
import dev.tobins.mealplanner.api.MealSlot.LUNCH
import dev.tobins.mealplanner.api.MealStatus
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
import dev.tobins.mealplanner.api.WeekPlan
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Prepped meals (issue 234): a recipe made once and eaten over several days, by hand. Each test is one box of the
 * issue's "Done when" for step 1, through the API.
 */
class PrepTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat|9201|Prep kip|Prep chicken||per 100g|150|20|4|1|0|0|0|50",
        "NEVO-Online 2025 9.0|Graan|Cereals|9202|Prep rijst|Prep rice||per 100g|350|7|1|0|78|0|2|5",
        "NEVO-Online 2025 9.0|Kaas|Cheese|9203|Prep kaas|Prep cheese||per 100g|400|25|30|20|0|0|0|600",
    ).joinToString("\r\n")

    /** Monday 16 to Sunday 22 November 2026, and the week after. */
    private val week = "2026-11-16"
    private val nextWeek = "2026-11-23"
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

    /**
     * A chicken salad of four servings of 300 g chicken (450 kcal), which keeps 3 days in a box and 5 vacuum sealed and
     * which the planner does not pick on its own; three rice bowls of one serving for the planner; the cheese to adjust a
     * meal with; and the account's id. At 2000 kcal a day a lunch of the salad is 1.25 servings and a dinner 1.5.
     */
    private class Kitchen(val chicken: Ingredient, val cheese: Ingredient, val salad: RecipeSummary, val userId: Long)

    private suspend fun HttpClient.kitchen(keeps: RecipeKeeps? = RecipeKeeps(days = 3, vacuumDays = 5)): Kitchen {
        put("/api/profile") { setBody(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        suspend fun ingredient(name: String, code: Int) = post("/api/ingredients") { setBody(IngredientInput(name, code)) }.body<Ingredient>()
        suspend fun recipe(title: String, servings: Int, lines: String) =
            post("/api/recipes") { setBody(RecipeInput(title, servings, lines, "Cook.", mealSlots = listOf(LUNCH, DINNER))) }.body<RecipeDetail>().summary
        val chicken = ingredient("Prep chicken", 9201)
        ingredient("Prep rice", 9202)
        val cheese = ingredient("Prep cheese", 9203)
        val salad = recipe("Chicken salad", 4, "1200 | g | prep chicken")
        put("/api/recipes/${salad.id}/rotation") { setBody(RotationInput(false)) }
        for (i in 1..3) recipe("Rice bowl $i", 1, "${180 + i * 10} | g | prep rice")
        if (keeps != null) assertEquals(HttpStatusCode.OK, put("/api/recipes/${salad.id}/keeps") { setBody(keeps) }.status)
        return Kitchen(chicken, cheese, salad, db.userQueries.selectBySubject("sub-alice").executeAsOne().id)
    }

    private suspend fun HttpClient.prep(
        recipeId: Long,
        madeOn: String,
        vararg meals: Pair<String, MealSlot>,
        vacuum: Boolean = false,
        weekStart: String = week,
    ): HttpResponse = post("/api/plans/$weekStart/preps") { setBody(PrepInput(recipeId, madeOn, vacuum, meals.map { PrepSlot(it.first, it.second) })) }

    private suspend fun HttpClient.plan(weekStart: String = week): WeekPlan = get("/api/plans/$weekStart").body()

    private fun WeekPlan.at(date: String, slot: MealSlot): PlannedMeal? = days.single { it.date == date }.meals.firstOrNull { it.slot == slot }

    private suspend fun HttpClient.mark(date: String, slot: MealSlot, status: MealStatus): HttpResponse {
        val day = LocalDate.parse(date)
        val monday = Batch.mondayOf(day)
        return put("/api/plans/$monday/meals/${day.toEpochDay() - monday.toEpochDay()}/$slot/status") { setBody(status) }
    }

    private suspend fun HttpClient.spare(date: String): List<SparePortion> = get("/api/preps/spare/$date").body()

    /** The grams of [ingredient] in the account's stock, all entries together. */
    private fun Kitchen.stockOf(ingredient: Ingredient): Double =
        db.pantryQueries.selectStockOfAccount(userId).executeAsList().filter { it.ingredientId == ingredient.id }.sumOf { it.grams ?: 0.0 }

    private fun Kitchen.stockUp(ingredient: Ingredient, grams: Double, fromWeek: String = week) =
        StockRepository(db, FoodRepository(db), userId).putOnHandFrom(ingredient.id, LocalDate.parse(fromWeek), grams, StockSource.STOCK_UP)

    private suspend fun HttpClient.chickenRow(weekStart: String = week) =
        get("/api/plans/$weekStart/shopping").body<ShoppingList>().items.firstOrNull { it.name == "Prep chicken" }

    // --- A recipe's "keeps"

    @Test
    fun aRecipeKeepsForTwoNumbersOfDaysThatTheOldSettingsLeaveAlone() = testApplication {
        val http = start()
        val k = http.kitchen(keeps = null)
        assertNull(k.salad.keepsDays, "not for prepping until the account says so")

        val set = http.put("/api/recipes/${k.salad.id}/keeps") { setBody(RecipeKeeps(days = 3, vacuumDays = 6)) }.body<RecipeDetail>().summary
        assertEquals(3 to 6, set.keepsDays to set.keepsVacuumDays)
        // A released app saves the kind and slots only: the keeps stay.
        http.put("/api/recipes/${k.salad.id}/settings") { setBody(RecipeSettings(RecipeKind.MEAL, listOf(LUNCH))) }
        val listed = http.get("/api/recipes").body<List<RecipeSummary>>().single { it.id == k.salad.id }
        assertEquals(3 to 6, listed.keepsDays to listed.keepsVacuumDays)

        val vacuumOnly = http.put("/api/recipes/${k.salad.id}/keeps") { setBody(RecipeKeeps(days = null, vacuumDays = 6)) }.body<RecipeDetail>().summary
        assertEquals(null to 6, vacuumOnly.keepsDays to vacuumOnly.keepsVacuumDays)
        for (days in listOf(0, 8)) {
            assertEquals(HttpStatusCode.BadRequest, http.put("/api/recipes/${k.salad.id}/keeps") { setBody(RecipeKeeps(days = days)) }.status, "$days days")
        }
        assertEquals(HttpStatusCode.NotFound, http.put("/api/recipes/999/keeps") { setBody(RecipeKeeps(days = 2)) }.status)
    }

    // --- Prep on the Plan

    @Test
    fun aPrepIsMadeOnItsFirstMealsDayAndEatenNoLaterThanItKeeps() = testApplication {
        val http = start()
        val k = http.kitchen()
        val id = k.salad.id
        fun refused(response: HttpResponse, status: HttpStatusCode) = assertEquals(status, response.status)

        refused(http.prep(id, "2026-11-17", "2026-11-17" to DINNER, "2026-11-21" to LUNCH), HttpStatusCode.Conflict)
        assertTrue(http.prep(id, "2026-11-17", "2026-11-17" to DINNER, "2026-11-21" to LUNCH).bodyAsText().contains("keeps until Friday 20 November"))
        refused(http.prep(id, "2026-11-17", "2026-11-18" to LUNCH, "2026-11-19" to LUNCH), HttpStatusCode.BadRequest)
        refused(http.prep(id, "2026-11-17", "2026-11-17" to MealSlot.BREAKFAST, "2026-11-18" to LUNCH), HttpStatusCode.BadRequest)
        refused(http.prep(id, "2026-11-17", "2026-11-17" to DINNER), HttpStatusCode.BadRequest)
        refused(http.prep(id, "2026-11-17", "2026-11-17" to DINNER, "2026-11-17" to DINNER), HttpStatusCode.BadRequest)
        assertTrue(http.plan().days.all { it.meals.isEmpty() }, "nothing refused was planned")

        // Vacuum sealed it keeps five days: made Tuesday, eaten up to Sunday.
        val plan = http.prep(id, "2026-11-17", "2026-11-17" to DINNER, "2026-11-18" to LUNCH, "2026-11-19" to DINNER, "2026-11-22" to LUNCH, vacuum = true)
            .body<WeekPlan>()
        val made = assertNotNull(plan.at("2026-11-17", DINNER))
        val prep = assertNotNull(made.prep)
        assertEquals("2026-11-17" to "2026-11-22", prep.madeOn to prep.lastDay)
        assertTrue(prep.vacuum)
        assertEquals(listOf("2026-11-17", "2026-11-18", "2026-11-19", "2026-11-22"), prep.meals.map { it.date })

        // Not vacuum sealed, it keeps only in a box.
        http.put("/api/recipes/$id/keeps") { setBody(RecipeKeeps(days = 2)) }
        val notSealed = http.prep(id, "2026-11-20", "2026-11-20" to DINNER, "2026-11-21" to LUNCH, vacuum = true)
        refused(notSealed, HttpStatusCode.Conflict)
        assertTrue(notSealed.bodyAsText().contains("vacuum sealed"), notSealed.bodyAsText())
    }

    @Test
    fun theDayItIsMadeCooksTheBatchAndTheLaterDaysAreLockedLeftoversThatCookNothing() = testApplication {
        val http = start()
        val k = http.kitchen()
        val plan = http.prep(k.salad.id, "2026-11-17", "2026-11-17" to DINNER, "2026-11-18" to LUNCH, "2026-11-19" to DINNER, "2026-11-20" to LUNCH)
            .body<WeekPlan>()
        val meals = listOf("2026-11-17" to DINNER, "2026-11-18" to LUNCH, "2026-11-19" to DINNER, "2026-11-20" to LUNCH).map { plan.at(it.first, it.second)!! }

        // Each meal's portion is the planner's for its slot, within the profile's 0.75 to 1.5; the batch is their sum.
        assertEquals(listOf(1.5, 1.25, 1.5, 1.25), meals.map { it.portions })
        assertEquals(5.5, meals.first().prep!!.portions)
        assertEquals(listOf(5.5, 0.0, 0.0, 0.0), meals.map { it.cookPortions })
        assertEquals(listOf(false, true, true, true), meals.map { it.leftover })
        assertTrue(meals.all { it.locked && it.recipeId == k.salad.id })
        // The recipe opened from Tuesday's dinner shows the batch; the day after is "prepped Tuesday".
        assertEquals("2026-11-17", meals[1].prep!!.madeOn)

        // Today and the day's totals count each meal's own portion.
        val wednesday = http.get("/api/days/2026-11-18").body<DayView>()
        assertEquals(1.25 * 450, wednesday.expectedKcal, 1e-9)
        assertEquals(1.25 * 450, plan.days.single { it.date == "2026-11-18" }.kcal, 1e-9)
        http.mark("2026-11-18", LUNCH, MealStatus.EATEN)
        assertEquals(1.25 * 450, http.get("/api/days/2026-11-18").body<DayView>().eatenKcal, 1e-9)
    }

    @Test
    fun generatingAndReplanningPlanAroundAPrepAndCountItOnce() = testApplication {
        val http = start()
        val k = http.kitchen()
        val prepped = listOf("2026-11-17" to DINNER, "2026-11-18" to DINNER, "2026-11-19" to LUNCH)
        val before = http.prep(k.salad.id, "2026-11-17", *prepped.toTypedArray()).body<WeekPlan>()

        val plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
        assertEquals(prepped.map { before.at(it.first, it.second) }, prepped.map { plan.at(it.first, it.second) }, "the prepped meals stay as they were")
        // A prepped dinner leaves no leftover lunch, though the salad's four servings would: Wednesday has a lunch of its own.
        assertNotEquals(k.salad.id, plan.at("2026-11-18", LUNCH)?.recipeId)
        assertEquals(3, plan.days.flatMap { it.meals }.count { it.recipeId == k.salad.id })
        assertTrue(plan.days.all { day -> day.meals.any { it.slot == LUNCH } && day.meals.any { it.slot == DINNER } }, "every other lunch and dinner planned")

        // Neither rerolled, unlocked nor removed: the meal that makes it names the later meals it would leave without a batch.
        assertEquals(HttpStatusCode.Conflict, http.post("/api/plans/$week/meals/1/DINNER/reroll").status)
        assertEquals(HttpStatusCode.Conflict, http.delete("/api/plans/$week/meals/2/DINNER/lock").status)
        val removal = http.delete("/api/plans/$week/meals/1/DINNER")
        assertEquals(HttpStatusCode.Conflict, removal.status)
        assertTrue(removal.bodyAsText().contains("Wednesday dinner, Thursday lunch"), removal.bodyAsText())

        // A later meal of a batch not made yet can be replaced: the batch gets smaller.
        val rerolled = http.post("/api/plans/$week/meals/3/LUNCH/reroll").body<WeekPlan>()
        assertNull(rerolled.at("2026-11-19", LUNCH)!!.prep)
        assertEquals(1.5 + 1.5, rerolled.at("2026-11-17", DINNER)!!.cookPortions)
    }

    // --- Bought once, taken from the stock once

    @Test
    fun theBatchIsBoughtOnceInTheWeekItIsMadeAlsoForMealsOfTheWeekAfter() = testApplication {
        val http = start()
        val k = http.kitchen()
        // Made on Sunday's dinner, eaten Monday at lunch and Tuesday at dinner of the next week.
        val plan = http.prep(k.salad.id, "2026-11-22", "2026-11-22" to DINNER, "2026-11-23" to LUNCH, "2026-11-24" to DINNER).body<WeekPlan>()
        assertEquals(4.25, plan.at("2026-11-22", DINNER)!!.cookPortions)

        assertEquals(4.25 * 300, http.chickenRow()!!.grams!!, 1e-9)
        assertNull(http.chickenRow(nextWeek), "bought with the week it is made in")

        // The next week has the batch's meals before it is planned, and they stay when it is.
        val next = http.plan(nextWeek)
        assertEquals("2026-11-22", next.at("2026-11-23", LUNCH)!!.prep!!.madeOn)
        assertEquals(0.0, next.at("2026-11-24", DINNER)!!.cookPortions)
        val planned = http.post("/api/plans/$nextWeek/generate").body<WeekPlan>()
        assertEquals(next.at("2026-11-23", LUNCH), planned.at("2026-11-23", LUNCH))
        assertEquals(next.at("2026-11-24", DINNER), planned.at("2026-11-24", DINNER))
        assertNull(http.chickenRow(nextWeek), "planned, the next week still buys no chicken for the batch")
    }

    @Test
    fun theStockGoesDownOnceForTheBatchWhicheverMealIsEatenFirstAndComesBackWhenNoneIsEaten() = testApplication {
        val http = start()
        val k = http.kitchen()
        http.prep(k.salad.id, "2026-11-22", "2026-11-22" to DINNER, "2026-11-23" to LUNCH, "2026-11-24" to DINNER)
        // 1000 g at home this week, and 1000 g bought ahead for the next: the batch is cooked from this week's.
        k.stockUp(k.chicken, 1000.0)
        k.stockUp(k.chicken, 1000.0, fromWeek = nextWeek)

        assertEquals(HttpStatusCode.OK, http.mark("2026-11-23", LUNCH, MealStatus.EATEN).status)
        assertEquals(1000.0, k.stockOf(k.chicken), 1e-9, "the whole batch was made from this week's 1000 g; next week's stays")
        http.mark("2026-11-22", DINNER, MealStatus.EATEN)
        http.mark("2026-11-24", DINNER, MealStatus.EATEN)
        assertEquals(1000.0, k.stockOf(k.chicken), 1e-9, "made once")

        http.mark("2026-11-23", LUNCH, MealStatus.PLANNED)
        http.mark("2026-11-22", DINNER, MealStatus.SKIPPED)
        assertEquals(1000.0, k.stockOf(k.chicken), 1e-9, "one meal still eaten: the batch was made")
        assertNull(http.chickenRow()!!.toComeGrams?.takeIf { it > 0.0 }, "nothing left to buy for a batch that was made")
        http.mark("2026-11-24", DINNER, MealStatus.PLANNED)
        assertEquals(2000.0, k.stockOf(k.chicken), 1e-9, "none eaten: all of it back")
    }

    @Test
    fun aMealOfAMadeBatchAdjustedAfterwardsMovesTheStockByTheDifference() = testApplication {
        val http = start()
        val k = http.kitchen()
        http.prep(k.salad.id, "2026-11-17", "2026-11-17" to DINNER, "2026-11-18" to LUNCH)
        k.stockUp(k.chicken, 1000.0)
        k.stockUp(k.cheese, 100.0)
        http.mark("2026-11-17", DINNER, MealStatus.EATEN)
        assertEquals(1000.0 - 2.75 * 300, k.stockOf(k.chicken), 1e-9)

        // Wednesday's portion gets 30 g of cheese when it is eaten.
        val adjusted = http.put("/api/plans/$week/meals/2/LUNCH/lines") { setBody(MealAdjustment(listOf(MealLineChange(k.cheese.id, 30.0)))) }
        assertEquals(HttpStatusCode.OK, adjusted.status)
        assertEquals(70.0, k.stockOf(k.cheese), 1e-9)
        assertEquals(1000.0 - 2.75 * 300, k.stockOf(k.chicken), 1e-9, "the chicken stays as it was taken")
        http.mark("2026-11-17", DINNER, MealStatus.PLANNED)
        assertEquals(1000.0 to 100.0, k.stockOf(k.chicken) to k.stockOf(k.cheese))
    }

    // --- A skipped meal's share

    @Test
    fun aSkippedMealsShareIsOfferedForLaterMealsWithRoomWhileItKeepsAndNeverAfter() = testApplication {
        val http = start()
        val k = http.kitchen()
        // Made Tuesday, keeps until Friday; Wednesday's lunch is skipped. Friday's lunch was chosen by hand.
        http.prep(k.salad.id, "2026-11-17", "2026-11-17" to DINNER, "2026-11-18" to LUNCH, "2026-11-19" to DINNER)
        val bowl = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.title.startsWith("Rice bowl") }
        http.put("/api/plans/$week/meals/4/LUNCH") { setBody(MealChoice(bowl.id)) }
        k.stockUp(k.chicken, 2000.0)
        http.mark("2026-11-17", DINNER, MealStatus.EATEN)
        http.mark("2026-11-18", LUNCH, MealStatus.SKIPPED)
        val taken = k.stockOf(k.chicken)

        val spare = http.spare("2026-11-18").single()
        assertEquals(PrepSlot("2026-11-18", LUNCH) to 1.25, spare.skipped to spare.portions)
        assertEquals("2026-11-20", spare.lastDay)
        assertEquals(
            listOf(PrepSlot("2026-11-18", DINNER), PrepSlot("2026-11-19", LUNCH), PrepSlot("2026-11-20", DINNER)),
            spare.room, "later lunches and dinners up to Friday, not the batch's own nor the one chosen by hand",
        )
        assertEquals(listOf(PrepSlot("2026-11-20", DINNER)), http.spare("2026-11-20").single().room, "the last day: eat it today or throw it away")
        assertEquals(emptyList(), http.spare("2026-11-21"), "past its last day it is not offered")

        assertEquals(HttpStatusCode.Conflict, http.post("/api/plans/$week/preps/${spare.prepId}/spare") { setBody(SpareMove(spare.skipped, PrepSlot("2026-11-21", LUNCH))) }.status)
        assertEquals(HttpStatusCode.Conflict, http.post("/api/plans/$week/preps/${spare.prepId}/spare") { setBody(SpareMove(spare.skipped, PrepSlot("2026-11-20", LUNCH))) }.status)
        val plan = http.post("/api/plans/$week/preps/${spare.prepId}/spare") { setBody(SpareMove(spare.skipped, PrepSlot("2026-11-20", DINNER))) }.body<WeekPlan>()

        val friday = plan.at("2026-11-20", DINNER)!!
        assertEquals(Triple(k.salad.id, 1.25, true), Triple(friday.recipeId, friday.portions, friday.leftover))
        val prep = friday.prep!!
        assertEquals(listOf(1.5, 0.0, 1.5, 1.25), prep.meals.map { it.portions }, "Wednesday's share is Friday's now")
        assertEquals(4.25, prep.portions, "the batch is as large as it was")
        assertEquals(MealStatus.SKIPPED, plan.at("2026-11-18", LUNCH)!!.status)
        assertEquals(emptyList(), http.spare("2026-11-18"))
        assertEquals(HttpStatusCode.Conflict, http.mark("2026-11-18", LUNCH, MealStatus.EATEN).status, "its share is eaten on Friday")
        assertEquals(taken, k.stockOf(k.chicken), 1e-9, "the stock does not move")

        http.mark("2026-11-17", DINNER, MealStatus.PLANNED)
        assertEquals(2000.0, k.stockOf(k.chicken), 1e-9, "un-made, every share comes back, Friday's too")
    }

    // --- Removing

    @Test
    fun removingAPrepRemovesItsMealsInEveryWeekUnlessItWasMade() = testApplication {
        val http = start()
        val k = http.kitchen()
        val plan = http.prep(k.salad.id, "2026-11-22", "2026-11-22" to DINNER, "2026-11-23" to LUNCH, "2026-11-24" to DINNER).body<WeekPlan>()
        val id = plan.at("2026-11-22", DINNER)!!.prep!!.id

        // A later meal goes on its own, and the batch is made smaller.
        http.delete("/api/plans/$nextWeek/meals/1/DINNER")
        assertEquals(1.5 + 1.25, http.plan().at("2026-11-22", DINNER)!!.cookPortions)

        http.mark("2026-11-23", LUNCH, MealStatus.EATEN)
        assertEquals(HttpStatusCode.Conflict, http.delete("/api/plans/$week/preps/$id").status)
        assertEquals(HttpStatusCode.Conflict, http.delete("/api/plans/$nextWeek/meals/0/LUNCH").status)
        http.mark("2026-11-23", LUNCH, MealStatus.PLANNED)

        val removed = http.delete("/api/plans/$week/preps/$id").body<WeekPlan>()
        assertNull(removed.at("2026-11-22", DINNER))
        assertTrue(http.plan(nextWeek).days.all { it.meals.isEmpty() })
        assertEquals(HttpStatusCode.NotFound, http.delete("/api/plans/$week/preps/$id").status)
    }
}
