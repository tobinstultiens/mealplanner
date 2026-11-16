package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShoppingRowKey
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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A tick remembers what its row said, so the list can tell when the plan changed since (issue 90). */
class ShoppingTicksTest {
    private val week = "2026-11-16"

    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|1634|Kipfilet rauw|Chicken fillet raw||per 100g|200|20|13|5|0|0|0|60",
        "NEVO-Online 2025 9.0|Groente|Vegetables|2346|Sla rauw|Lettuce raw||per 100g|15|1|0|0|2|1|2|5",
        "NEVO-Online 2025 9.0|Graanproducten|Cereal products|5|Rijst wit rauw|Rice white raw||per 100g|350|7|1|0|78|0|1|5",
    ).joinToString("\r\n")

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
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
    }

    /**
     * Plans the week with only two breakfast recipes, so that what a reroll picks is certain: the
     * rice bowl is the pinned routine, every day, at 1.25 portions (25 g of chicken each); the
     * chicken salad is the only other, at one portion (150 g of chicken, 100 g of lettuce). No
     * other meals are planned.
     */
    private suspend fun HttpClient.planned(): HttpClient = apply {
        put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        val bowl = post("/api/recipes") {
            setBody(RecipeInput("Rice bowl", 1, "100 | g | rice\n20 | g | chicken fillet", mealSlots = listOf(MealSlot.BREAKFAST)))
        }.body<RecipeDetail>()
        post("/api/recipes") {
            setBody(RecipeInput("Chicken salad", 1, "50 | g | rice\n150 | g | chicken fillet\n100 | g | lettuce", mealSlots = listOf(MealSlot.BREAKFAST)))
        }
        put("/api/routines") { setBody(Routines(breakfast = bowl.summary.id)) }
        val plan = post("/api/plans/$week/generate").body<WeekPlan>()
        assertEquals(List(7) { "Rice bowl" }, plan.days.flatMap { it.meals }.map { it.title })
    }

    /** Rerolls Monday's breakfast: from the rice bowl to the chicken salad, and back. */
    private suspend fun HttpClient.rerollMonday(): String {
        val plan = post("/api/plans/$week/meals/0/breakfast/reroll").body<WeekPlan>()
        return plan.days[0].meals.single().title
    }

    private suspend fun HttpClient.list() = get("/api/plans/$week/shopping").body<ShoppingList>()

    private suspend fun HttpClient.tick(key: String, checked: Boolean = true) =
        put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, checked)) }

    private suspend fun HttpClient.again(key: String) = put("/api/plans/$week/shopping/again") { setBody(ShoppingRowKey(key)) }

    private suspend fun HttpClient.useUp(key: String) = post("/api/plans/$week/shopping/use-up") { setBody(ShoppingRowKey(key)) }

    private suspend fun HttpClient.ingredient(name: String) = get("/api/ingredients").body<List<Ingredient>>().single { it.name == name }

    private fun ShoppingList.item(name: String): ShoppingItem = items.single { it.name == name }

    private fun userId() = db.userQueries.selectBySubject("sub-t").executeAsOne().id

    @Test
    fun aTickedRowTheMealsNowNeedMoreOfStaysTickedWithWhatWasBought() = testApplication {
        val http = start().planned()
        val chicken = http.list().item("Chicken fillet")
        assertEquals(175.0, assertNotNull(chicken.grams), 1e-9)
        assertNull(chicken.boughtGrams, "not ticked")

        val ticked = http.tick(chicken.key).body<ShoppingList>().item("Chicken fillet")
        assertTrue(ticked.checked)
        assertEquals(175.0, assertNotNull(ticked.boughtGrams), 1e-9)

        // The chicken salad takes Monday: the week needs 6 × 25 + 150 g now, and 175 g were bought.
        assertEquals("Chicken salad", http.rerollMonday())
        val more = http.list().item("Chicken fillet")
        assertTrue(more.checked, "apps that don't know the amount bought must not see it come back")
        assertEquals(300.0, assertNotNull(more.grams), 1e-9)
        assertEquals(175.0, assertNotNull(more.boughtGrams), 1e-9)
        assertNull(more.boughtPieces)

        // Ticking it again keeps what the first tick said; un-ticking and ticking it says what it needs now.
        assertEquals(175.0, assertNotNull(http.tick(chicken.key).body<ShoppingList>().item("Chicken fillet").boughtGrams), 1e-9)
        http.tick(chicken.key, checked = false)
        assertNull(http.list().item("Chicken fillet").boughtGrams)
        assertEquals(300.0, assertNotNull(http.tick(chicken.key).body<ShoppingList>().item("Chicken fillet").boughtGrams), 1e-9)
        assertEquals(emptyList(), http.list().noLongerNeeded)
    }

    @Test
    fun tickingARowAgainRemembersWhatItNeedsNowInOneStep() = testApplication {
        val http = start().planned()
        val chicken = http.list().item("Chicken fillet")
        http.tick(chicken.key)
        assertEquals("Chicken salad", http.rerollMonday())
        assertEquals(175.0, assertNotNull(http.list().item("Chicken fillet").boughtGrams), 1e-9)

        val again = http.again(chicken.key)
        assertEquals(HttpStatusCode.OK, again.status)
        val ticked = again.body<ShoppingList>().item("Chicken fillet")
        assertTrue(ticked.checked)
        assertEquals(300.0, assertNotNull(ticked.boughtGrams), 1e-9)
        assertEquals(300.0, assertNotNull(http.list().item("Chicken fillet").boughtGrams), 1e-9)
    }

    @Test
    fun aRowIsTickedAgainOnlyWhenItIsTickedAndTheMealsStillNeedIt() = testApplication {
        val http = start().planned()
        assertEquals("Chicken salad", http.rerollMonday())
        val lettuce = http.list().item("Lettuce")
        assertEquals(HttpStatusCode.NotFound, http.again(lettuce.key).status, "not ticked")
        assertTrue(http.list().items.none { it.checked }, "and still not ticked")
        // Advice and own items remember no row; a key that is none is refused as for a tick.
        val id = lettuce.key.removePrefix("i")
        for (key in listOf("stock:i$id", "ahead:i$id", "x:1", "dismissed:stock-up", "nonsense")) {
            assertEquals(HttpStatusCode.BadRequest, http.again(key).status, key)
        }

        // Bought, and then no meal needs it: ticking it again would forget what was bought.
        http.tick(lettuce.key)
        assertEquals("Rice bowl", http.rerollMonday())
        assertEquals(HttpStatusCode.Conflict, http.again(lettuce.key).status)
        assertEquals(100.0, assertNotNull(http.list().noLongerNeeded.single().boughtGrams), 1e-9)
    }

    @Test
    fun usingUpABoughtItemStocksWhatWasBoughtFromTheListsWeekAndTakesItOutOfTheGroup() = testApplication {
        val http = start().planned()
        assertEquals("Chicken salad", http.rerollMonday())
        val lettuce = http.list().item("Lettuce")
        http.tick(lettuce.key)
        assertEquals("Rice bowl", http.rerollMonday())
        assertEquals(listOf(lettuce.key), http.list().noLongerNeeded.map { it.key })

        val used = http.useUp(lettuce.key)
        assertEquals(HttpStatusCode.OK, used.status)
        assertEquals(emptyList(), used.body<ShoppingList>().noLongerNeeded)
        assertEquals(emptyList(), http.list().noLongerNeeded)
        val id = http.ingredient("Lettuce").id
        assertEquals(100.0, assertNotNull(http.get("/api/pantry").body<Pantry>().onHandGrams[id]), 1e-9)
        // Stock counted from the list's week, which its meals eat from; the tick is gone.
        val stock = db.pantryQueries.selectStock(userId(), week, listOf(id)).executeAsOne()
        assertEquals(Triple(week, "USE_UP", 100.0), Triple(stock.fromWeek, stock.source, stock.grams))
        assertTrue(db.shoppingQueries.selectChecks(userId(), week).executeAsList().none { it.itemKey == lettuce.key })

        assertEquals(HttpStatusCode.NotFound, http.useUp(lettuce.key).status, "used up already")
    }

    /** The grams of [id] on hand for meals of the week starting [weekStart], of every entry of the stock. */
    private fun stockFor(id: Long, weekStart: String) =
        db.pantryQueries.selectStock(userId(), weekStart, listOf(id)).executeAsList().map { it.fromWeek to it.grams }

    @Test
    fun usingUpIsAnEntryOfItsOwnSoUnTickingAnotherItemLeavesIt() = testApplication {
        val http = start().planned()
        assertEquals("Chicken salad", http.rerollMonday())
        val lettuce = http.list().item("Lettuce")
        http.tick(lettuce.key)
        assertEquals("Rice bowl", http.rerollMonday())
        val id = http.ingredient("Lettuce").id
        val nextWeek = "2026-11-23"
        // Also bought ahead for next week (200 g) and stocked up this week (500 g), each ticked on this week's list.
        for ((key, grams) in listOf("ahead:i$id" to 200.0, "stock:i$id" to 500.0)) {
            http.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, true, grams)) }
        }

        // The 100 g used up count from the list's week, apart from both ticks (issue 116): before, they were
        // added to the one entry, which then counted from this week and was deleted by its week.
        http.useUp(lettuce.key)
        assertEquals(listOf(week to 500.0, week to 100.0), stockFor(id, week))
        assertEquals(800.0, http.get("/api/pantry").body<Pantry>().onHandGrams[id])

        // Un-ticking either takes back what that tick put there, and no more.
        http.tick("stock:i$id", checked = false)
        assertEquals(listOf(week to 100.0), stockFor(id, week), "the used-up grams stay")
        http.tick("ahead:i$id", checked = false)
        assertEquals(listOf(week to 100.0), stockFor(id, nextWeek), "the bought-ahead grams go, though something counts from earlier")
        assertEquals(100.0, http.get("/api/pantry").body<Pantry>().onHandGrams[id])
    }

    @Test
    fun usingUpAnItemBoughtWithoutAWeightPutsItOnHandByHand() = testApplication {
        val http = start().planned()
        // Counted in pieces, with no weight per piece: what was bought has no grams.
        val fruit = http.post("/api/ingredients") { setBody(IngredientInput("Pomegranate")) }.body<Ingredient>()
        val bowl = http.post("/api/recipes") { setBody(RecipeInput("Fruit", 1, "2 | pomegranate | ripe")) }.body<RecipeDetail>().summary
        db.planQueries.upsertMeal(userId(), week, 6, "DINNER", bowl.id, bowl.title, 1.0, 200.0, 2.0, 0, 0, "PLANNED")
        http.tick("i${fruit.id}")
        http.delete("/api/plans/$week/meals/6/dinner")
        val gone = http.list().noLongerNeeded.single()
        assertEquals(2.0 to null, gone.boughtPieces to gone.boughtGrams)

        assertEquals(emptyList(), http.useUp(gone.key).body<ShoppingList>().noLongerNeeded)
        val pantry = http.get("/api/pantry").body<Pantry>()
        assertEquals(listOf("Pomegranate"), pantry.onHand.map { it.name })
        assertNull(pantry.onHandGrams[fruit.id], "how much is not known")
        assertEquals(emptyList(), db.pantryQueries.selectStock(userId(), week, listOf(fruit.id)).executeAsList(), "set by hand, not stock")
    }

    @Test
    fun onlyABoughtIngredientNoMealNeedsIsUsedUp() = testApplication {
        val http = start().planned()
        val chicken = http.list().item("Chicken fillet")
        http.tick(chicken.key)
        assertEquals(HttpStatusCode.Conflict, http.useUp(chicken.key).status, "the meals need it: a row of the list")
        assertEquals(HttpStatusCode.NotFound, http.useUp("i${http.ingredient("Lettuce").id}").status, "not bought")
        assertEquals(HttpStatusCode.BadRequest, http.useUp("t:leek").status, "no ingredient to put on hand")
        assertEquals(emptyList(), http.get("/api/pantry").body<Pantry>().onHand)
        assertTrue(http.list().item("Chicken fillet").checked)
    }

    @Test
    fun aStapleBoughtBeforeItWasOneIsNotUsedUpAsTheGroupDoesNotListIt() = testApplication {
        val http = start().planned()
        assertEquals("Chicken salad", http.rerollMonday())
        val lettuce = http.list().item("Lettuce")
        http.tick(lettuce.key)
        assertEquals("Rice bowl", http.rerollMonday())
        http.put("/api/pantry/staples/${http.ingredient("Lettuce").id}")
        assertEquals(emptyList(), http.list().noLongerNeeded)

        assertEquals(HttpStatusCode.NotFound, http.useUp(lettuce.key).status)
        assertEquals(emptyList(), http.get("/api/pantry").body<Pantry>().onHand)
    }

    @Test
    fun aRouteTheServerDoesNotHaveAnswers404WithoutSayingWhatWasNotFound() = testApplication {
        // How a server from before tick-again and use-up answers them: the app falls back on that, and
        // only on that, as a 404 of the routes themselves says what was not found.
        val http = start()
        val unknown = http.put("/api/plans/$week/shopping/not-a-route") { setBody(ShoppingRowKey("i1")) }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertTrue("error" !in unknown.bodyAsText())
        val known = http.again("i1")
        assertEquals(HttpStatusCode.NotFound, known.status)
        assertTrue("\"error\"" in known.bodyAsText())
    }

    @Test
    fun aBoughtItemNoMealNeedsAnyMoreIsListedApartUntilUnTicked() = testApplication {
        val http = start().planned()
        assertEquals("Chicken salad", http.rerollMonday())
        val lettuce = http.list().item("Lettuce")
        assertEquals(100.0, assertNotNull(lettuce.grams), 1e-9)
        http.tick(lettuce.key)
        http.tick(http.list().item("Chicken fillet").key)

        // Back to the rice bowl: no meal needs lettuce, but it was bought.
        assertEquals("Rice bowl", http.rerollMonday())
        var list = http.list()
        assertTrue(list.items.none { it.key == lettuce.key })
        val gone = list.noLongerNeeded.single()
        assertEquals(lettuce.key to "Lettuce", gone.key to gone.name)
        assertEquals(100.0, assertNotNull(gone.boughtGrams), 1e-9)
        assertTrue(gone.checked)
        assertEquals(false, gone.onHand)
        assertEquals(lettuce.category, gone.category)
        assertEquals(null to null, gone.grams to gone.pieces)
        assertEquals(emptyList(), gone.recipes)
        // The chicken still is needed, less than was bought: a row as it was, not apart.
        assertEquals(300.0, assertNotNull(list.item("Chicken fillet").boughtGrams), 1e-9)

        // It says whether it is on hand already.
        http.put("/api/pantry/on-hand/${http.ingredient("Lettuce").id}")
        assertTrue(http.list().noLongerNeeded.single().onHand)

        // Un-ticked with the usual route, it was not bought after all: gone from the list.
        list = http.tick(lettuce.key, checked = false).body()
        assertEquals(emptyList(), list.noLongerNeeded)
        assertTrue(list.items.none { it.key == lettuce.key })
    }

    @Test
    fun aBoughtItemMadeAStapleAfterwardsIsNotListedAsNoLongerNeeded() = testApplication {
        val http = start().planned()
        assertEquals("Chicken salad", http.rerollMonday())
        http.tick(http.list().item("Lettuce").key)
        assertEquals("Rice bowl", http.rerollMonday())
        assertEquals(listOf("Lettuce"), http.list().noLongerNeeded.map { it.name })

        // Made a staple, it is always at home: nothing to tell about it.
        http.put("/api/pantry/staples/${http.ingredient("Lettuce").id}")
        assertEquals(emptyList(), http.list().noLongerNeeded)
    }

    @Test
    fun aCountedRowRemembersItsPiecesWhenItsMealIsRemoved() = testApplication {
        val http = start().planned()
        // Pomegranates have no nutrition, so the planner can't size a meal of them: it is put in the plan directly.
        val fruit = http.post("/api/ingredients") { setBody(IngredientInput("Pomegranate", gramsPerPiece = 300.0)) }.body<Ingredient>()
        val bowl = http.post("/api/recipes") { setBody(RecipeInput("Fruit", 1, "2 | pomegranate | ripe")) }.body<RecipeDetail>().summary
        db.planQueries.upsertMeal(userId(), week, 6, "DINNER", bowl.id, bowl.title, 1.0, 200.0, 2.0, 0, 0, "PLANNED")
        val pomegranate = http.list().item("Pomegranate")
        assertEquals(Triple("i${fruit.id}", 2.0, 600.0), Triple(pomegranate.key, pomegranate.pieces, pomegranate.grams))
        val ticked = http.tick(pomegranate.key).body<ShoppingList>().item("Pomegranate")
        assertEquals(2.0 to 600.0, ticked.boughtPieces to ticked.boughtGrams)

        // The meal is taken out of the plan: the pomegranates are bought and no meal needs them.
        http.delete("/api/plans/$week/meals/6/dinner")
        val list = http.list()
        assertTrue(list.items.none { it.key == pomegranate.key })
        val gone = list.noLongerNeeded.single()
        assertEquals(Triple(pomegranate.key, "Pomegranate", pomegranate.category), Triple(gone.key, gone.name, gone.category))
        assertEquals(2.0 to 600.0, gone.boughtPieces to gone.boughtGrams)
        assertEquals(false to true, gone.onHand to gone.checked)
    }

    @Test
    fun aTickFromBeforeTicksRememberedIsAPlainTickAndNeverListedApart() = testApplication {
        val http = start().planned()
        assertEquals("Chicken salad", http.rerollMonday())
        val lettuce = http.list().item("Lettuce")
        val chicken = http.list().item("Chicken fillet")
        // Ticks as version 11 of the database left them: the key only.
        for (key in listOf(lettuce.key, chicken.key)) db.shoppingQueries.insertCheck(userId(), week, key, null, null, null)

        var list = http.list()
        for (name in listOf("Lettuce", "Chicken fillet")) {
            assertTrue(list.item(name).checked)
            assertEquals(null to null, list.item(name).boughtGrams to list.item(name).boughtPieces, name)
        }
        // Ticking one again (as an app re-sending it would) keeps it as it was.
        assertNull(http.tick(chicken.key).body<ShoppingList>().item("Chicken fillet").boughtGrams)

        assertEquals("Rice bowl", http.rerollMonday())
        list = http.list()
        assertTrue(list.items.none { it.key == lettuce.key })
        assertEquals(emptyList(), list.noLongerNeeded, "nothing is known of what was bought")
        assertTrue(list.item("Chicken fillet").checked)
    }

    @Test
    fun ticksOfRowsNotOnTheListAreKeptAndRememberNothing() = testApplication {
        val http = start().planned()
        // A key of a row this week's list doesn't have (an app's list from before the plan changed):
        // still ticked, as always, but not listed as bought, as nothing is known of it.
        assertEquals(HttpStatusCode.OK, http.tick("t:leek").status)
        assertEquals(HttpStatusCode.OK, http.tick("i999999").status)
        assertEquals(emptyList(), http.list().noLongerNeeded)
        val stored = db.shoppingQueries.selectChecks(userId(), week).executeAsList()
        assertEquals(setOf("t:leek", "i999999"), stored.map { it.itemKey }.toSet())
        assertTrue(stored.all { it.name == null && it.grams == null && it.pieces == null })

        // Dismissed groups are kept with the ticks and are never a row.
        http.put("/api/plans/$week/shopping/dismissed/stock-up")
        assertEquals(emptyList(), http.list().noLongerNeeded)
    }

    @Test
    fun theAppOfV012ReadsTheListWithWhatWasBoughtAsBefore() = testApplication {
        val http = start().planned()
        assertEquals("Chicken salad", http.rerollMonday())
        for (name in listOf("Lettuce", "Chicken fillet")) http.tick(http.list().item(name).key)
        assertEquals("Rice bowl", http.rerollMonday())
        val list = http.list()
        assertTrue(list.noLongerNeeded.isNotEmpty() && list.items.any { it.boughtGrams != null })

        // v0.1.2 reads with ignoreUnknownKeys (its ApiClient): the bought item is not on its list,
        // and what it has is ticked as before.
        val v012 = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        val old = v012.decodeFromString<V012ShoppingList>(http.get("/api/plans/$week/shopping").bodyAsText())
        assertEquals(list.items.map { Triple(it.key, it.name, it.checked) }, old.items.map { Triple(it.key, it.name, it.checked) })
        assertTrue(old.items.none { it.name == "Lettuce" })
    }

    /** `ShoppingList` and `ShoppingItem` as v0.1.2 has them (offers left out: the store test reads those). */
    @Serializable
    private data class V012ShoppingList(val weekStart: String, val items: List<V012ShoppingItem>, val staples: List<String>, val toTaste: List<String> = emptyList())

    @Serializable
    private data class V012ShoppingItem(
        val key: String,
        val name: String,
        val grams: Double?,
        val pieces: Double?,
        val category: String,
        val recipes: List<String>,
        val onHand: Boolean,
        val checked: Boolean,
        val imageUrl: String? = null,
    )
}
