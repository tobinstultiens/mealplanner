package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.IngredientSection
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingExtraInput
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.ServerConfig
import dev.tobins.mealplanner.server.TestTokens
import dev.tobins.mealplanner.server.UserScope
import dev.tobins.mealplanner.server.accounts.Identity
import dev.tobins.mealplanner.server.accounts.UserRepository
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.module
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
import dev.tobins.mealplanner.server.prices.offers.OfferSource
import dev.tobins.mealplanner.shopping.ShopSection
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Own items on the shopping list: coffee, toilet paper, anything no recipe needs (issue 93). */
class ShoppingExtrasTest {
    private class FixedOffers(override val store: Store, val offers: List<Offer>) : OfferSource {
        override suspend fun fetch() = offers
    }

    private val week = "2026-11-16"
    private val weeks = listOf("2026-11-09", week, "2026-11-23", "2026-11-30")
    private val oilPicture = "https://www.jumbo.com/INTERSHOP/static/olijfolie.png"

    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vetten|Fats and oils|601|Olijfolie|Olive oil||per 100g|900|0|100|14|0|0|0|0",
    ).joinToString("\r\n")

    private lateinit var db: Database

    /** A server with the seed's Olive oil in the catalogue (NEVO food 601) and on offer at Jumbo this week. */
    private fun ApplicationTestBuilder.start(): HttpClient {
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        val jumbo = FixedOffers(
            Store.JUMBO,
            listOf(Offer(Store.JUMBO, "Jumbo Olijfolie 1 l", "25% korting", packSize = "1 l", validFrom = week, validUntil = "2026-11-22", imageUrl = oilPicture)),
        )
        db = openDatabase(config.databaseUrl).second
        application { module(config, db, listOf(jumbo), priceSource = null, generator = null, verifier = TestTokens) }
        return createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
    }

    private suspend fun HttpClient.list(weekStart: String = week) = get("/api/plans/$weekStart/shopping").body<ShoppingList>()

    private suspend fun HttpClient.add(name: String, weekStart: String = week) =
        post("/api/plans/$weekStart/shopping/extras") { setBody(ShoppingExtraInput(name)) }

    private suspend fun HttpClient.tick(key: String, checked: Boolean = true, weekStart: String = week) =
        put("/api/plans/$weekStart/shopping") { setBody(ShoppingCheck(key, checked)) }

    private suspend fun HttpClient.ingredient(name: String) = get("/api/ingredients").body<List<Ingredient>>().single { it.name == name }

    /** The weeks of [weeks] whose list has [key], and whether it is ticked there. */
    private suspend fun HttpClient.listsWith(key: String): Map<String, Boolean> =
        weeks.mapNotNull { w -> list(w).items.singleOrNull { it.key == key }?.let { w to it.checked } }.toMap()

    private fun ShoppingList.own(name: String): ShoppingItem = items.single { it.key.startsWith("x:") && it.name == name }

    @Test
    fun anOwnItemIsOnEveryListFromItsWeekUntilTickedOff() = testApplication {
        val http = start()
        val added = http.add("  Coffee ")
        assertEquals(HttpStatusCode.Created, added.status)

        // The week has no meals: the item shows all the same, a row like any other without amounts or recipes.
        val coffee = added.body<ShoppingList>().items.single()
        assertTrue(coffee.key.matches(Regex("x:[0-9]+")), coffee.key)
        assertEquals("Coffee", coffee.name)
        assertEquals(ShopSection.OTHER.title, coffee.category)
        assertNull(coffee.grams)
        assertNull(coffee.pieces)
        assertNull(coffee.regularPrice)
        assertEquals(emptyList(), coffee.recipes)
        assertEquals(emptyList(), coffee.offers)
        assertFalse(coffee.checked || coffee.onHand)
        val key = coffee.key
        // Added in this week: on its list and every later one, not on the week before.
        assertEquals(mapOf(week to false, "2026-11-23" to false, "2026-11-30" to false), http.listsWith(key))

        // Ticked off next week, it belongs to next week: ticked there, gone from the lists before and after.
        assertTrue(http.tick(key, weekStart = "2026-11-23").body<ShoppingList>().own("Coffee").checked)
        assertEquals(mapOf("2026-11-23" to true), http.listsWith(key))
        // Its tick is kept with the item, not with the week's ticks.
        val userId = db.userQueries.selectBySubject("sub-t").executeAsOne().id
        assertEquals(emptyList(), db.shoppingQueries.selectChecks(userId, "2026-11-23").executeAsList())

        // Un-ticked, it is back on every list from the week it was added in.
        assertFalse(http.tick(key, checked = false, weekStart = "2026-11-23").body<ShoppingList>().own("Coffee").checked)
        assertEquals(mapOf(week to false, "2026-11-23" to false, "2026-11-30" to false), http.listsWith(key))

        // Deleted, it is gone from every list.
        val id = key.removePrefix("x:")
        assertEquals(emptyList(), http.delete("/api/plans/$week/shopping/extras/$id").body<ShoppingList>().items)
        assertEquals(emptyMap(), http.listsWith(key))
        assertEquals(HttpStatusCode.NotFound, http.delete("/api/plans/$week/shopping/extras/$id").status)
        assertEquals(HttpStatusCode.NotFound, http.tick(key).status)
    }

    @Test
    fun anOwnItemIsTickedOnlyOnAListItIsOn() = testApplication {
        val http = start()
        val key = http.add("Coffee").body<ShoppingList>().own("Coffee").key

        // Not on the list of the week before it was added.
        assertEquals(HttpStatusCode.Conflict, http.tick(key, weekStart = "2026-11-09").status)
        // Ticked twice in one week, it stays ticked there.
        http.tick(key)
        assertEquals(HttpStatusCode.OK, http.tick(key).status)
        assertEquals(mapOf(week to true), http.listsWith(key))
        // Bought this week, it is on no other week's list: ticking it there is refused, and un-ticking
        // it there changes nothing.
        val refused = http.tick(key, weekStart = "2026-11-23")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("Coffee is not on the shopping list" in refused.bodyAsText(), refused.bodyAsText())
        assertEquals(HttpStatusCode.OK, http.tick(key, checked = false, weekStart = "2026-11-23").status)
        assertEquals(mapOf(week to true), http.listsWith(key))
    }

    @Test
    fun aCatalogueNameTakesItsIngredientsSectionAndOffersAndOtherNamesGoLast() = testApplication {
        val http = start()
        http.post("/api/offers/refresh")
        // This week's salad needs olive oil, so the list has the meals' row for it.
        val salad = http.post("/api/recipes") {
            setBody(RecipeInput(title = "Salad", servings = 2, ingredients = "30 | ml | olive oil\n200 | g | lettuce", instructions = "Mix."))
        }.body<RecipeDetail>().summary
        val userId = db.userQueries.selectBySubject("sub-t").executeAsOne().id
        db.planQueries.upsertMeal(userId, week, 0, "DINNER", salad.id, salad.title, 2.0, 500.0, 10.0, 0, 0, "PLANNED")
        val oil = http.ingredient("Olive oil")
        val oilSection = http.get("/api/ingredients/sections").body<List<IngredientSection>>().single { it.ingredientId == oil.id }
        http.put("/api/pantry/on-hand/${oil.id}")

        http.add("Toilet paper")
        val list = http.add("Olive oil").body<ShoppingList>()

        // Olive oil is in the catalogue: the own item has its section, offers, picture and on-hand mark...
        val own = list.own("Olive oil")
        assertEquals(assertNotNull(ShopSection.fromKey(oilSection.section)).title, own.category)
        assertEquals(listOf("Jumbo Olijfolie 1 l"), own.offers.map { it.title })
        assertEquals(oilPicture, own.imageUrl)
        assertTrue(own.onHand)
        assertNull(own.grams)
        assertNull(own.regularPrice)
        // ...and is a row of its own, next to the meals' row, which keeps its amount.
        val meals = list.items.single { it.key == "i${oil.id}" }
        assertEquals(27.6, assertNotNull(meals.grams), 1e-9)
        assertEquals(listOf("Salad"), meals.recipes)
        assertEquals(list.items.indexOf(meals) + 1, list.items.indexOf(own), "${list.items.map { it.key }}")
        // A name the catalogue doesn't know is in section Other, at the end of the walk.
        val paper = list.items.last()
        assertEquals("Toilet paper" to ShopSection.OTHER.title, paper.name to paper.category)
    }

    @Test
    fun anOwnItemNamedByAnAliasSortsNextToTheMealsRowOfItsIngredient() = testApplication {
        val http = start()
        // Mayonnaise and olive oil in one section, both needed by this week's salad.
        val mayonnaise = http.post("/api/ingredients") { setBody(IngredientInput("Mayonnaise")) }.body<Ingredient>()
        val oil = http.ingredient("Olive oil")
        for (id in listOf(mayonnaise.id, oil.id)) http.put("/api/ingredients/$id/section") { setBody(SectionChoice("SAUCES")) }
        val salad = http.post("/api/recipes") {
            setBody(RecipeInput(title = "Salad", servings = 2, ingredients = "30 | ml | olive oil\n30 | g | mayonnaise", instructions = "Mix."))
        }.body<RecipeDetail>().summary
        val userId = db.userQueries.selectBySubject("sub-t").executeAsOne().id
        db.planQueries.upsertMeal(userId, week, 0, "DINNER", salad.id, salad.title, 2.0, 500.0, 10.0, 0, 0, "PLANNED")

        // "Extra virgin olive oil" is one of the seed's aliases of olive oil: by its own name it would come
        // before the mayonnaise; it sorts by its ingredient's name, right after the meals' row.
        val list = http.add("Extra virgin olive oil").body<ShoppingList>()
        assertEquals(listOf("i${mayonnaise.id}", "i${oil.id}", list.own("Extra virgin olive oil").key), list.items.map { it.key })
        assertEquals("Extra virgin olive oil", list.items.last().name, "it keeps the name it was given")
    }

    @Test
    fun anOwnItemOnTheListIsNotAdvisedAsStockUpAsWell() = testApplication {
        val http = start()
        http.post("/api/offers/refresh")
        val oil = http.ingredient("Olive oil")
        http.put("/api/ingredients/${oil.id}") {
            setBody(IngredientInput(oil.name, oil.food?.nevoCode, oil.gramsPerPiece, oil.gramsPerMl, oil.category, pricePerKg = 8.0))
        }
        http.put("/api/pantry/staples/${oil.id}")
        assertEquals(listOf("Olive oil"), http.list().stockUp.map { it.name }, "a staple that keeps, on offer")

        val key = http.add("olive oil").body<ShoppingList>().own("olive oil").key
        assertEquals(emptyList(), http.list().stockUp, "it is on the list already")
        http.delete("/api/plans/$week/shopping/extras/${key.removePrefix("x:")}")
        assertEquals(listOf("Olive oil"), http.list().stockUp.map { it.name })
    }

    @Test
    fun aBlankOrLongNameAndTheHundredAndFirstOpenItemAreRefused() = testApplication {
        val http = start()

        assertEquals(HttpStatusCode.BadRequest, http.add("   ").status)
        assertEquals(HttpStatusCode.BadRequest, http.add("x".repeat(81)).status)
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/plans/$week/shopping/extras") { setBody("{}") }.status)
        assertEquals(HttpStatusCode.BadRequest, http.add("Coffee", weekStart = "next-week").status)
        assertEquals(HttpStatusCode.BadRequest, http.delete("/api/plans/$week/shopping/extras/coffee").status)
        assertEquals(emptyList(), http.list().items, "nothing was stored")
        // 80 characters is the most, counting a character outside the BMP (a roll of paper) as one.
        val paper = "🧻"
        assertEquals(HttpStatusCode.BadRequest, http.add(paper.repeat(81)).status)
        assertEquals(HttpStatusCode.Created, http.add(" " + paper.repeat(80) + " ").status)

        for (i in 2..ShoppingExtraInput.MAX_OPEN) assertEquals(HttpStatusCode.Created, http.add("Item $i").status, "item $i")
        val full = http.add("One too many")
        assertEquals(HttpStatusCode.Conflict, full.status)
        val message = full.bodyAsText()
        assertTrue("100 own items that are not ticked off" in message, message)
        assertEquals(ShoppingExtraInput.MAX_OPEN, http.list().items.size, "the refused one was not kept")

        // One ticked off is not open any more: it makes room for another.
        http.tick(http.list().own("Item 2").key)
        assertEquals(HttpStatusCode.Created, http.add("One more").status)
        assertEquals(HttpStatusCode.Conflict, http.add("One too many").status)
    }

    /**
     * Adds at once never go past the limit: on a file in WAL mode with a connection per thread, as the server runs,
     * and on the in-memory database of most tests, whose one connection every thread shares (issue 248).
     */
    @Test
    fun addsAtTheSameTimeNeverGoPastTheLimit() {
        val dir = createTempDirectory("extras").toFile()
        try {
            for (url in listOf("jdbc:sqlite::memory:", "jdbc:sqlite:${File(dir, "extras.db").path}")) {
                val (driver, db) = openDatabase(url)
                try {
                    assertEquals(mapOf("added" to 2, "full" to 6), addAtOnce(db, File(dir, "pictures")), url)
                } finally {
                    driver.close()
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Fills the list up to two below the limit, then adds eight at once; what became of each, counted. */
    private fun addAtOnce(db: Database, pictures: File): Map<String, Int> {
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        val userId = UserRepository(db).resolve(Identity("sub-t", "t")).id
        val shopping = UserScope(userId, db, foods, offers, PriceRepository(db, null, foods, offers), null, pictures).shopping.ownItems
        repeat(ShoppingExtraInput.MAX_OPEN - 2) { shopping.addExtra(week, ShoppingExtraInput("Item $it")) }

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val outcomes = (1..8).map { i ->
            pool.submit<String> {
                start.await()
                try {
                    shopping.addExtra(week, ShoppingExtraInput("Racing $i"))
                    "added"
                } catch (e: ConflictException) {
                    "full"
                }
            }
        }
        start.countDown()
        val counted = outcomes.map { it.get(30, TimeUnit.SECONDS) }.groupingBy { it }.eachCount()
        pool.shutdown()
        assertEquals(ShoppingExtraInput.MAX_OPEN.toLong(), db.shoppingQueries.countOpenExtras(userId).executeAsOne())
        return counted
    }
}
