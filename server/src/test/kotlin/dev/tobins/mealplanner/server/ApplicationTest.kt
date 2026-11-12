package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.Health
import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.FoodDataset
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SyncResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationTest {
    private fun config(cookingRepo: File?, nevoFile: File?) = ServerConfig(
        port = 0,
        apiToken = "test-token",
        databaseUrl = "jdbc:sqlite::memory:",
        cookingRepo = cookingRepo,
        nevoFile = nevoFile,
    )

    /** Starts the app on a fresh in-memory database and returns a client that sends the API token. */
    private fun ApplicationTestBuilder.start(cookingRepo: File? = null, nevoFile: File? = null): HttpClient {
        val config = config(cookingRepo, nevoFile)
        application { module(config, openDatabase(config.databaseUrl).second) }
        return createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { bearerAuth("test-token") }
        }
    }

    private fun cookingRepo(vararg files: Pair<String, String>): File {
        val dir = createTempDirectory("cooking").toFile()
        for ((path, text) in files) File(dir, "content/$path").apply { parentFile.mkdirs() }.writeText(text)
        return dir
    }

    private val gyudon = """
        ---
        title: Gyudon
        tags: [Beef, Japanese]
        ---
        ```ingredients
        servings: 2
        400 | g | beef
        1 | onion | large
        ```
        Steps:
        - Simmer.
    """.trimIndent()

    @Test
    fun healthIsPublic() = testApplication {
        start()
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun healthReportsVersion() = testApplication {
        assertEquals(Health("ok", APP_VERSION), start().get("/health").body<Health>())
    }

    @Test
    fun apiRejectsMissingOrWrongToken() = testApplication {
        start()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/recipes").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/recipes") { bearerAuth("nope") }.status)
    }

    @Test
    fun syncImportsRecipesAndServesThem() = testApplication {
        val repo = cookingRepo(
            "rice-bowls/gyudon.md" to gyudon,
            "rice-bowls/_index.md" to "{{<toc-tree>}}",
            "sauces/salsa.md" to "---\ntitle: Salsa\n---\nBlend it.",
        )
        val http = start(repo)

        val sync = http.post("/api/sync/cooking").body<SyncResult>()
        assertEquals(SyncResult(added = 2, updated = 0, unchanged = 0, removed = 0, problems = emptyMap()), sync)

        val list = http.get("/api/recipes").body<List<RecipeSummary>>()
        assertEquals(listOf("Gyudon", "Salsa"), list.map { it.title })
        val (gyudonSummary, salsa) = list
        assertTrue(gyudonSummary.plannable)
        assertEquals(RecipeKind.MEAL, gyudonSummary.kind)
        assertEquals(listOf("Beef", "Japanese"), gyudonSummary.tags)
        assertFalse(salsa.plannable, "no ingredients block")
        assertEquals(RecipeKind.COMPONENT, salsa.kind)

        val detail = http.get("/api/recipes/${gyudonSummary.id}").body<RecipeDetail>()
        assertEquals(listOf("beef", "large"), detail.ingredients.map { it.item })
        assertEquals(400.0, detail.ingredients[0].baseAmount)
        assertTrue(detail.instructions.startsWith("Steps:"))
    }

    @Test
    fun resyncUpdatesChangedFilesAndRemovesMissingOnes() = testApplication {
        val repo = cookingRepo("rice-bowls/gyudon.md" to gyudon, "sauces/salsa.md" to "Blend it.")
        val http = start(repo)
        http.post("/api/sync/cooking")
        val idBefore = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.title == "Gyudon" }.id

        assertEquals(2, http.post("/api/sync/cooking").body<SyncResult>().unchanged)

        File(repo, "content/rice-bowls/gyudon.md").writeText(gyudon.replace("400 | g | beef", "500 | g | beef"))
        File(repo, "content/sauces/salsa.md").delete()
        val sync = http.post("/api/sync/cooking").body<SyncResult>()
        assertEquals(SyncResult(added = 0, updated = 1, unchanged = 0, removed = 1, problems = emptyMap()), sync)

        val list = http.get("/api/recipes").body<List<RecipeSummary>>()
        assertEquals(listOf("Gyudon"), list.map { it.title })
        assertEquals(idBefore, list.single().id, "a changed file keeps its recipe id")
        val detail = http.get("/api/recipes/$idBefore").body<RecipeDetail>()
        assertEquals(listOf(500.0, 1.0), detail.ingredients.map { it.amount })
    }

    @Test
    fun syncWithoutCheckoutIsUnavailableAndUnknownRecipeIs404() = testApplication {
        val http = start(cookingRepo = null)
        assertEquals(HttpStatusCode.ServiceUnavailable, http.post("/api/sync/cooking").status)
        assertEquals(HttpStatusCode.NotFound, http.get("/api/recipes/999").status)
        assertEquals(HttpStatusCode.BadRequest, http.get("/api/recipes/abc").status)
    }

    private val nevoSample = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees en gevogelte|Meat and poultry|1634|Kipfilet rauw|Chicken fillet raw||per 100g|109|23,3|1,8|0,5|0|0|0|52",
        "NEVO-Online 2025 9.0|Vlees en gevogelte|Meat and poultry|2341|Kipfilet gepaneerd rauw|Chicken fillet breaded raw||per 100g|131|21,5|2,3|0,6|5,6|0,4|0,6|",
        "NEVO-Online 2025 9.0|Melk en melkproducten|Milk and milk products|279|Melk halfvolle|Milk semi-skimmed||per 100ml|47|3,5|1,5|1|4,7|4,7|0|43",
        "NEVO-Online 2025 9.0|Melk en melkproducten|Milk and milk products|305|Kwark magere|Quark low fat|Magere kwark|per 100g|51|8,4|0|0|3,8|3,8|0|40",
    ).joinToString("\r\n")

    private fun nevoFile(): File = kotlin.io.path.createTempFile("nevo", ".csv").toFile().apply { writeText(nevoSample) }

    @Test
    fun nevoIsImportedOnFirstStartAndSearchable() = testApplication {
        val http = start(nevoFile = nevoFile())

        val dataset = http.get("/api/foods/dataset").body<FoodDataset>()
        assertEquals(FoodDataset(4, "NEVO-online version 2025/9.0, RIVM, Bilthoven"), dataset)

        val chicken = http.get("/api/foods?q=chicken fillet").body<List<Food>>()
        assertEquals(listOf(1634, 2341), chicken.map { it.nevoCode }, "shorter name ranks first")
        assertEquals(23.3, chicken[0].per100.proteinG)
        assertEquals(109.0, chicken[0].per100.kcal)
        assertEquals(null, chicken[1].per100.sodiumMg, "an empty cell is unknown, not zero")

        assertEquals(FoodBasis.PER_100_G, chicken[0].basis)
        assertEquals(FoodBasis.PER_100_ML, http.get("/api/foods?q=milk").body<List<Food>>().single().basis)

        assertEquals(listOf(305), http.get("/api/foods?q=KWARK").body<List<Food>>().map { it.nevoCode })
        assertEquals(listOf(305), http.get("/api/foods?q=magere kwark").body<List<Food>>().map { it.nevoCode })
        assertEquals(emptyList(), http.get("/api/foods?q=tofu").body<List<Food>>())
        assertEquals(HttpStatusCode.BadRequest, http.get("/api/foods").status)
    }

    @Test
    fun foodImportWithoutFileIsUnavailable() = testApplication {
        val http = start()
        assertEquals(FoodDataset(0, null), http.get("/api/foods/dataset").body<FoodDataset>())
        assertEquals(HttpStatusCode.ServiceUnavailable, http.post("/api/foods/import").status)
    }
}
