package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.UnmappedText
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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogueTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat|10|Rundvlees rauw|Beef raw||per 100g|120|20|4|2|0|0|0|50",
        "NEVO-Online 2025 9.0|Groente|Vegetables|63|Ui rauw|Onions raw||per 100g|40|1|0|0|8|6|2|3",
        "NEVO-Online 2025 9.0|Sauzen|Sauces|5470|Saus soja-|Soy sauce||per 100g|40|5|0|0|5|1|0|5000",
    ).joinToString("\r\n")

    private val gyudon = """
        ---
        title: Gyudon
        ---
        ```ingredients
        servings: 2
        400 | g | beef, thinly sliced
        1 | onion | large
        15 | ml | light soy sauce
        | | togarashi
        ```
    """.trimIndent()

    private val stew = "```ingredients\nservings: 4\n800 | g | Beef,  thinly sliced\n```"

    private fun ApplicationTestBuilder.start(): HttpClient {
        val repo = createTempDirectory("cooking").toFile()
        for ((path, text) in listOf("rice-bowls/gyudon.md" to gyudon, "recipes/stew.md" to stew)) {
            File(repo, "content/$path").apply { parentFile.mkdirs() }.writeText(text)
        }
        val config = ServerConfig(
            port = 0,
            apiToken = "t",
            databaseUrl = "jdbc:sqlite::memory:",
            cookingRepo = repo,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        application { module(config, openDatabase(config.databaseUrl).second) }
        return createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
    }

    private suspend fun HttpClient.gyudon(): RecipeDetail {
        val id = get("/api/recipes").body<List<RecipeSummary>>().first { it.title == "Gyudon" }.id
        return get("/api/recipes/$id").body()
    }

    @Test
    fun unmappedTextsAreListedMostUsedFirstWithSuggestions() = testApplication {
        val http = start()
        http.post("/api/sync/cooking")

        val unmapped = http.get("/api/mappings/unmapped").body<List<UnmappedText>>()
        assertEquals(
            listOf("beef, thinly sliced", "light soy sauce", "onion, large"),
            unmapped.map { it.text },
            "the line without an amount is not listed; beef is used by two recipes",
        )
        val (beef, soy, onion) = unmapped
        assertEquals(2, beef.recipeCount)
        assertEquals(listOf(10), beef.suggestedFoods.map { it.nevoCode })
        assertEquals(listOf(5470), soy.suggestedFoods.map { it.nevoCode }, "falls back from 'light soy sauce' to 'soy sauce'")
        assertTrue(onion.countedInPieces)
        assertFalse(beef.countedInPieces)
    }

    @Test
    fun mappingLinesGivesNutritionPerServing() = testApplication {
        val http = start()
        http.post("/api/sync/cooking")

        val before = http.gyudon().summary.nutrition!!
        assertFalse(before.complete)
        assertEquals(3, before.uncounted.size)

        val beef = http.put("/api/mappings") { setBody(MappingRequest("beef, thinly sliced", nevoCode = 10)) }.body<Ingredient>()
        assertEquals("Beef raw", beef.name)
        http.put("/api/mappings") { setBody(MappingRequest("onion, large", nevoCode = 63, name = "Onion", gramsPerPiece = 150.0)) }
        http.put("/api/mappings") { setBody(MappingRequest("light soy sauce", nevoCode = 5470, gramsPerMl = 1.2)) }

        val detail = http.gyudon()
        val nutrition = detail.summary.nutrition!!
        assertTrue(nutrition.complete)
        assertFalse(nutrition.approximate)
        // (400 g beef * 1.2 + 150 g onion * 0.4 + 18 g soy sauce * 0.4) / 2 servings
        assertEquals((480 + 60 + 7.2) / 2, nutrition.perServing.kcal!!, absoluteTolerance = 1e-9)
        assertEquals((80 + 1.5 + 0.9) / 2, nutrition.perServing.proteinG!!, absoluteTolerance = 1e-9)
        assertEquals(listOf("Beef raw", "Onion", "Soy sauce", null), detail.ingredients.map { it.ingredientName })
        assertEquals(listOf(400.0, 150.0, 18.0, null), detail.ingredients.map { it.grams })

        assertEquals(emptyList(), http.get("/api/mappings/unmapped").body<List<UnmappedText>>(), "the stew's beef line is covered too")

        // Mappings are kept when the cooking repo is synced again.
        http.post("/api/sync/cooking")
        assertTrue(http.gyudon().summary.nutrition!!.complete)

        http.delete("/api/mappings?text=onion, large")
        assertEquals(listOf("onion, large"), http.gyudon().summary.nutrition!!.uncounted)
    }

    @Test
    fun mappingToTheSameFoodReusesTheIngredient() = testApplication {
        val http = start()
        val first = http.put("/api/mappings") { setBody(MappingRequest("beef", nevoCode = 10)) }.body<Ingredient>()
        val second = http.put("/api/mappings") { setBody(MappingRequest("steak", nevoCode = 10, gramsPerPiece = 200.0)) }.body<Ingredient>()

        assertEquals(first.id, second.id)
        assertEquals(200.0, second.gramsPerPiece)
        assertEquals(1, http.get("/api/ingredients").body<List<Ingredient>>().size)
    }

    @Test
    fun ingredientsCanBeCreatedAndEdited() = testApplication {
        val http = start()
        val created = http.post("/api/ingredients") { setBody(IngredientInput("Olive oil", gramsPerMl = 0.92, isStaple = true)) }
        assertEquals(HttpStatusCode.Created, created.status)
        val oil = created.body<Ingredient>()
        assertNull(oil.food)
        assertTrue(oil.isStaple)

        val updated = http.put("/api/ingredients/${oil.id}") { setBody(IngredientInput("Olive oil", nevoCode = 10, pricePerKg = 9.5)) }
            .body<Ingredient>()
        assertEquals(10, updated.food?.nevoCode)
        assertFalse(updated.isStaple)
        assertNull(updated.gramsPerMl)

        assertEquals(HttpStatusCode.BadRequest, http.post("/api/ingredients") { setBody(IngredientInput("olive OIL")) }.status)
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/ingredients") { setBody(IngredientInput(" ")) }.status)
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/ingredients") { setBody(IngredientInput("X", gramsPerPiece = -1.0)) }.status)
        assertEquals(HttpStatusCode.NotFound, http.post("/api/ingredients") { setBody(IngredientInput("Y", nevoCode = 999)) }.status)
        assertEquals(HttpStatusCode.NotFound, http.put("/api/ingredients/999") { setBody(IngredientInput("Z")) }.status)
        assertEquals(HttpStatusCode.NotFound, http.put("/api/mappings") { setBody(MappingRequest("x", ingredientId = 999)) }.status)
        assertEquals(HttpStatusCode.BadRequest, http.put("/api/mappings") { setBody(MappingRequest("x")) }.status)
    }
}
