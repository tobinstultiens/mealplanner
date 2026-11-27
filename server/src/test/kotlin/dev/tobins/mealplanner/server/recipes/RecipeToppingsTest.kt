package dev.tobins.mealplanner.server.recipes

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.AddOn
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealAdjustment
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.RecipeTopping
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ToppingInput
import dev.tobins.mealplanner.api.ToppingsInput
import dev.tobins.mealplanner.recipes.HugoRecipeParser
import dev.tobins.mealplanner.server.ServerConfig
import dev.tobins.mealplanner.server.TestTokens
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.module
import dev.tobins.mealplanner.server.openDatabase
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Optional toppings of a recipe and the quick add-ons of its meals (issue 226): set in the recipe's
 * settings, kept through a sync, counted nowhere until a meal has one added; the add-ons offer the
 * toppings first, then what the recipe's meals had added before, most used first with its last amount.
 */
class RecipeToppingsTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Graan|Cereals|9201|Top rijst|Top rice||per 100g|350|7|1|0|78|0|2|5",
        "NEVO-Online 2025 9.0|Ei|Eggs|9202|Top ei|Top egg||per 100g|140|12|10|3|0|0|0|140",
        "NEVO-Online 2025 9.0|Fruit|Fruit|9203|Top avocado|Top avocado||per 100g|160|2|15|2|2|0|7|5",
        "NEVO-Online 2025 9.0|Kaas|Cheese|9204|Top feta|Top feta||per 100g|260|14|21|14|0|0|0|1000",
        "NEVO-Online 2025 9.0|Noten|Nuts|9205|Top sesam|Top sesame||per 100g|570|18|50|7|10|0|8|10",
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

    private class Kitchen(val rice: Ingredient, val egg: Ingredient, val avocado: Ingredient, val feta: Ingredient, val sesame: Ingredient, val bowl: RecipeSummary, val userId: Long)

    private suspend fun HttpClient.kitchen(): Kitchen {
        put("/api/profile") { setBody(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        suspend fun ingredient(name: String, code: Int, gramsPerPiece: Double? = null) =
            post("/api/ingredients") { setBody(IngredientInput(name, code, gramsPerPiece)) }.body<Ingredient>()
        val rice = ingredient("Top rice", 9201)
        val egg = ingredient("Top egg", 9202, gramsPerPiece = 60.0)
        val avocado = ingredient("Top avocado", 9203, gramsPerPiece = 140.0)
        val feta = ingredient("Top feta", 9204)
        val sesame = ingredient("Top sesame", 9205)
        val bowl = post("/api/recipes") { setBody(RecipeInput("Rice bowl", 2, "200 | g | top rice", "Bowl.")) }.body<RecipeDetail>().summary
        return Kitchen(rice, egg, avocado, feta, sesame, bowl, db.userQueries.selectBySubject("sub-alice").executeAsOne().id)
    }

    private suspend fun HttpClient.setToppings(recipeId: Long, vararg toppings: Pair<Ingredient, Double>): HttpResponse =
        put("/api/recipes/$recipeId/toppings") { setBody(ToppingsInput(toppings.map { (ingredient, grams) -> ToppingInput(ingredient.id, grams) })) }

    private suspend fun HttpClient.addOns(recipeId: Long): List<AddOn> = get("/api/recipes/$recipeId/add-ons").body()

    private fun Kitchen.plan(weekStart: String, day: Int, slot: MealSlot = MealSlot.DINNER) =
        db.planQueries.upsertMeal(userId, weekStart, day.toLong(), slot.name, bowl.id, bowl.title, 1.0, 700.0, 14.0, 0, 0, "PLANNED")

    private suspend fun HttpClient.adjust(weekStart: String, day: Int, vararg changes: Pair<Ingredient, Double>, slot: MealSlot = MealSlot.DINNER): MealLines {
        val response = put("/api/plans/$weekStart/meals/$day/$slot/lines") { setBody(MealAdjustment(changes.map { (ingredient, grams) -> MealLineChange(ingredient.id, grams) })) }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    @Test
    fun toppingsAreSetInTheirOrderAndCountForNothingUntilAMealHasOne() = testApplication {
        val http = start()
        val k = http.kitchen()
        k.plan(week, 0)
        val before = http.get("/api/recipes/${k.bowl.id}").body<RecipeDetail>()
        val listBefore = http.get("/api/plans/$week/shopping").body<ShoppingList>()

        val set = http.setToppings(k.bowl.id, k.egg to 60.0, k.avocado to 70.0)
        assertEquals(HttpStatusCode.OK, set.status)
        val expected = listOf(RecipeTopping(k.egg.id, "Top egg", 60.0, 60.0), RecipeTopping(k.avocado.id, "Top avocado", 70.0, 140.0))
        assertEquals(expected, set.body<RecipeDetail>().toppings)
        val after = http.get("/api/recipes/${k.bowl.id}").body<RecipeDetail>()
        assertEquals(expected, after.toppings)
        assertEquals(before.summary, after.summary, "nutrition and cost are the recipe's own")
        assertEquals(before.ingredients, after.ingredients)
        assertEquals(listBefore, http.get("/api/plans/$week/shopping").body<ShoppingList>(), "nothing to buy for a topping")
        assertEquals(700.0, http.adjust(week, 0).kcal, "the meal counts its recipe")

        // Replaced as a whole, in the new order; none takes them all away.
        assertEquals(listOf("Top avocado"), http.setToppings(k.bowl.id, k.avocado to 70.0).body<RecipeDetail>().toppings.map { it.name })
        assertEquals(emptyList(), http.setToppings(k.bowl.id).body<RecipeDetail>().toppings)
    }

    @Test
    fun whatCannotBeAToppingIsRefusedAndChangesNothing() = testApplication {
        val http = start()
        val k = http.kitchen()
        http.setToppings(k.bowl.id, k.egg to 60.0)
        for ((toppings, status) in listOf(
            arrayOf(k.egg to 0.0) to HttpStatusCode.BadRequest,
            arrayOf(k.egg to 5001.0) to HttpStatusCode.BadRequest,
            arrayOf(k.egg to 60.0, k.egg to 30.0) to HttpStatusCode.BadRequest,
            arrayOf(k.egg to 60.0, k.egg.copy(id = 999_999) to 30.0) to HttpStatusCode.NotFound,
        )) {
            assertEquals(status, http.setToppings(k.bowl.id, *toppings).status, toppings.joinToString())
        }
        assertEquals(HttpStatusCode.NotFound, http.setToppings(999_999, k.egg to 60.0).status)
        assertEquals(HttpStatusCode.NotFound, http.get("/api/recipes/999999/add-ons").status)
        assertEquals(listOf("Top egg"), http.get("/api/recipes/${k.bowl.id}").body<RecipeDetail>().toppings.map { it.name })
    }

    @Test
    fun aSyncOfTheCookingSiteNeverChangesTheToppings() = testApplication {
        val http = start()
        val k = http.kitchen()
        val recipes = RecipeRepository(db, IngredientRepository(db, FoodRepository(db), k.userId), k.userId)
        fun page(hash: String, rice: Int) = SitePage(HugoRecipeParser().parse("recipes/bowl.md", "---\ntitle: Site bowl\n---\n```ingredients\nservings: 2\n$rice | g | top rice\n```"), hash)
        recipes.syncCookingSite(listOf(page("h1", 200)))
        val site = recipes.bank().single()
        assertEquals(HttpStatusCode.OK, http.setToppings(site.id, k.egg to 60.0).status, "a bank recipe has toppings too")

        recipes.syncCookingSite(listOf(page("h2", 250)))
        assertEquals(listOf("Top egg"), http.get("/api/recipes/${site.id}").body<RecipeDetail>().toppings.map { it.name }, "changed on the site")
        recipes.syncCookingSite(emptyList())
        recipes.syncCookingSite(listOf(page("h2", 250)))
        assertEquals(listOf("Top egg"), http.get("/api/recipes/${site.id}").body<RecipeDetail>().toppings.map { it.name }, "gone from the site and back")
    }

    @Test
    fun addOnsAreTheToppingsThenWhatWasAddedBeforeMostUsedFirstWithItsLastAmount() = testApplication {
        val http = start()
        val k = http.kitchen()
        val earlier = "2026-11-09"
        http.setToppings(k.bowl.id, k.avocado to 70.0)
        // Feta added twice (30 g, then 40 g), sesame once, the egg once and later; more rice is no add-on.
        k.plan(earlier, 0)
        k.plan(earlier, 2)
        k.plan(week, 0)
        k.plan(week, 1, MealSlot.LUNCH)
        http.adjust(earlier, 0, k.feta to 30.0, k.sesame to 5.0, k.rice to 150.0)
        http.adjust(earlier, 2, k.feta to 40.0, k.avocado to 35.0)
        http.adjust(week, 0, k.egg to 120.0)
        http.adjust(week, 1, k.egg to 0.0, slot = MealSlot.LUNCH)

        assertEquals(
            listOf(
                AddOn(k.avocado.id, "Top avocado", 70.0, 140.0, topping = true, times = 1),
                AddOn(k.feta.id, "Top feta", 40.0, times = 2),
                AddOn(k.egg.id, "Top egg", 120.0, 60.0, times = 1),
                AddOn(k.sesame.id, "Top sesame", 5.0, times = 1),
            ),
            http.addOns(k.bowl.id),
        )

        // One tap: the add-on goes in as it comes, and the meal counts it.
        val feta = http.addOns(k.bowl.id)[1]
        k.plan(week, 3)
        val lines = http.adjust(week, 3, k.feta to feta.grams)
        assertEquals(40.0, lines.lines.single { it.name == "Top feta" }.grams)
        assertEquals(3, http.addOns(k.bowl.id)[1].times)

        // A topping the recipe has too is offered all the same: the app adds it on top of what the meal has.
        http.setToppings(k.bowl.id, k.rice to 50.0)
        assertEquals(AddOn(k.rice.id, "Top rice", 50.0, topping = true), http.addOns(k.bowl.id).first())
    }
}
