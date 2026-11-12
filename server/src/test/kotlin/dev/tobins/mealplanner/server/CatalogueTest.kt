package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SyncResult
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
    // Codes 63 and 5470 are also in the default catalogue (Onion, Soy sauce); 10, 20 and 21 are not.
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat|10|Rundvlees rauw|Beef raw||per 100g|120|20|4|2|0|0|0|50",
        "NEVO-Online 2025 9.0|Vlees|Meat|20|Kip m vel rauw|Chicken w skin raw||per 100g|200|18|14|4|0|0|0|70",
        "NEVO-Online 2025 9.0|Vlees|Meat|21|Kipfilet rauw|Chicken fillet raw||per 100g|110|23|2|1|0|0|0|50",
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

    private val stew = "```ingredients\nservings: 4\n800 | g | Beef (chuck),  cubed\n2 | onions | \n2 | bay leaf |\n1 | tsp | Finely chopped ONION\n```"

    private val chicken = """
        ---
        title: Chicken
        ---
        ```ingredients
        servings: 1
        2 | chicken thigh | (boneless)
        300 | g | chicken thigh, diced
        1 | can | mystery beans
        ```
    """.trimIndent()

    private fun ApplicationTestBuilder.start(): HttpClient {
        val repo = createTempDirectory("cooking").toFile()
        for ((path, text) in listOf("rice-bowls/gyudon.md" to gyudon, "recipes/stew.md" to stew, "recipes/chicken.md" to chicken)) {
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

    private suspend fun HttpClient.recipe(title: String): RecipeDetail {
        val id = get("/api/recipes").body<List<RecipeSummary>>().first { it.title == title }.id
        return get("/api/recipes/$id").body()
    }

    @Test
    fun exactMatchesAreLinkedWithoutAnyManualStep() = testApplication {
        val http = start()
        val sync = http.post("/api/sync/cooking").body<SyncResult>()
        assertEquals(1, sync.autoLinked, "beef is exactly the NEVO food 'Beef raw'; onion and soy sauce come from the default catalogue")

        val gyudon = http.recipe("Gyudon")
        val nutrition = gyudon.summary.nutrition!!
        assertTrue(nutrition.complete)
        assertFalse(nutrition.approximate)
        assertEquals(listOf("Beef raw", "Onion", "Soy sauce", null), gyudon.ingredients.map { it.ingredientName })
        // 400 g beef, one 110 g onion, 15 ml soy sauce at 1.15 g/ml
        assertEquals(listOf(400.0, 110.0, 17.25, null), gyudon.ingredients.map { it.grams })
        assertEquals((480 + 44 + 6.9) / 2, nutrition.perServing.kcal!!, absoluteTolerance = 1e-9)

        // Variants of the same ingredient resolve too: other notes, capitals, brackets, plural.
        val stew = http.recipe("Stew")
        assertEquals(listOf("Beef raw", "Onion", "Bay leaf", "Onion"), stew.ingredients.map { it.ingredientName })
        assertEquals(listOf(false, false, true, false), stew.ingredients.map { it.negligible })
        assertTrue(stew.summary.nutrition!!.complete, "the bay leaf is negligible, not missing")

        assertEquals(0, http.post("/api/sync/cooking").body<SyncResult>().autoLinked, "nothing new to link")
    }

    @Test
    fun onlyUnclearTextsAreLeftForTheUser() = testApplication {
        val http = start()
        http.post("/api/sync/cooking")

        val unmapped = http.get("/api/mappings/unmapped").body<List<UnmappedText>>()
        assertEquals(listOf("chicken thigh", "mystery beans"), unmapped.map { it.text })
        val (thigh, beans) = unmapped
        assertEquals(listOf(20, 21), thigh.suggestedFoods.map { it.nevoCode }, "two chicken foods: ambiguous, so suggested, not linked")
        assertTrue(thigh.countedInPieces)
        assertNull(thigh.linkedIngredient)
        assertEquals(emptyList(), beans.suggestedFoods)
        assertTrue(beans.countedInPieces, "a can is a container, so the ingredient is the item")
    }

    @Test
    fun oneManualLinkCoversAllVariantsAndThenAsksForTheMissingWeight() = testApplication {
        val http = start()
        http.post("/api/sync/cooking")

        val thigh = http.put("/api/mappings") { setBody(MappingRequest("chicken thigh", nevoCode = 20, name = "Chicken thigh")) }
            .body<Ingredient>()
        var recipe = http.recipe("Chicken")
        assertEquals(listOf("Chicken thigh", "Chicken thigh", null), recipe.ingredients.map { it.ingredientName })
        assertEquals(listOf(null, 300.0, null), recipe.ingredients.map { it.grams }, "the line counted in pieces has no weight yet")

        // Linked, but the weight per piece is still needed.
        val pending = http.get("/api/mappings/unmapped").body<List<UnmappedText>>().first { it.text == "chicken thigh" }
        assertEquals(thigh.id, pending.linkedIngredient?.id)

        http.put("/api/mappings") { setBody(MappingRequest("chicken thigh", ingredientId = thigh.id, gramsPerPiece = 120.0)) }
        recipe = http.recipe("Chicken")
        assertEquals(listOf(240.0, 300.0, null), recipe.ingredients.map { it.grams })
        assertEquals(listOf("mystery beans"), http.get("/api/mappings/unmapped").body<List<UnmappedText>>().map { it.text })

        // Links are kept when the cooking repo is synced again.
        http.post("/api/sync/cooking")
        assertEquals(240.0, http.recipe("Chicken").ingredients[0].grams)

        // Removing the link is not enough to unlink here: the ingredient is itself named "Chicken thigh".
        http.delete("/api/mappings?text=chicken thigh")
        assertEquals("Chicken thigh", http.recipe("Chicken").ingredients[0].ingredientName)

        // A link whose ingredient has another name does go away.
        http.put("/api/mappings") { setBody(MappingRequest("mystery beans", ingredientId = thigh.id)) }
        assertEquals(emptyList(), http.get("/api/mappings/unmapped").body<List<UnmappedText>>())
        http.delete("/api/mappings?text=mystery beans")
        assertEquals(listOf("mystery beans"), http.get("/api/mappings/unmapped").body<List<UnmappedText>>().map { it.text })
    }

    @Test
    fun anExactLinkForAFullTextWinsOverTheGeneralOne() = testApplication {
        val http = start()
        http.post("/api/sync/cooking")
        http.put("/api/mappings") { setBody(MappingRequest("beef, thinly sliced", nevoCode = 21, name = "Not beef")) }

        assertEquals("Not beef", http.recipe("Gyudon").ingredients[0].ingredientName)
        assertEquals("Beef raw", http.recipe("Stew").ingredients[0].ingredientName)
    }

    @Test
    fun mappingToTheSameFoodReusesTheIngredient() = testApplication {
        val http = start()
        val first = http.put("/api/mappings") { setBody(MappingRequest("beef", nevoCode = 10)) }.body<Ingredient>()
        val second = http.put("/api/mappings") { setBody(MappingRequest("steak", nevoCode = 10, gramsPerPiece = 200.0)) }.body<Ingredient>()

        assertEquals(first.id, second.id)
        assertEquals(200.0, second.gramsPerPiece)
    }

    @Test
    fun theDefaultCatalogueIsAddedOnceAndOnlyForFoodsThatExist() = testApplication {
        val http = start()
        val catalogue = http.get("/api/ingredients").body<List<Ingredient>>()
        assertEquals(
            listOf("Onion", "Shallot", "Soy sauce"),
            catalogue.filter { it.food != null }.map { it.name },
            "the other default ingredients point at foods this NEVO sample lacks",
        )
        val bayLeaf = catalogue.first { it.name == "Bay leaf" }
        assertTrue(bayLeaf.negligible)
        assertNull(bayLeaf.food)
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

    @Test
    fun profileGivesTargetsAndRejectsImplausibleValues() = testApplication {
        val http = start()
        assertEquals(ProfileState(null, null, null, null), http.get("/api/profile").body<ProfileState>())

        val profile = Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, goalKgPerWeek = 0.5)
        val saved = http.put("/api/profile") { setBody(profile) }.body<ProfileState>()
        assertEquals(DailyTargets(2040, 160), saved.targets)
        assertEquals(2585, saved.maintenanceKcal)

        val overridden = http.put("/api/profile") { setBody(profile.copy(kcalOverride = 1900)) }.body<ProfileState>()
        assertEquals(DailyTargets(1900, 160), overridden.targets)
        assertEquals(DailyTargets(2040, 160), overridden.suggested)
        assertEquals(overridden, http.get("/api/profile").body<ProfileState>())

        assertEquals(HttpStatusCode.BadRequest, http.put("/api/profile") { setBody(profile.copy(weightKg = 5.0)) }.status)
    }

    @Test
    fun pantryTracksStaplesAndOnHandItems() = testApplication {
        val http = start()
        val catalogue = http.get("/api/ingredients").body<List<Ingredient>>()
        val onion = catalogue.first { it.name == "Onion" }
        val soy = catalogue.first { it.name == "Soy sauce" }
        assertEquals(Pantry(emptyList(), emptyList()), http.get("/api/pantry").body<Pantry>())

        http.put("/api/pantry/staples/${soy.id}")
        http.put("/api/pantry/on-hand/${onion.id}")
        val pantry = http.put("/api/pantry/on-hand/${onion.id}").body<Pantry>()
        assertEquals(listOf("Soy sauce"), pantry.staples.map { it.name })
        assertEquals(listOf("Onion"), pantry.onHand.map { it.name }, "adding twice keeps one entry")

        http.delete("/api/pantry/staples/${soy.id}")
        assertEquals(Pantry(emptyList(), emptyList()), http.delete("/api/pantry/on-hand/${onion.id}").body<Pantry>())
        assertEquals(HttpStatusCode.NotFound, http.put("/api/pantry/on-hand/99999").status)
    }
}
