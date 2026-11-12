package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.Health
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
    private fun config(cookingRepo: File? = null) =
        ServerConfig(port = 0, apiToken = "test-token", databaseUrl = "jdbc:sqlite::memory:", cookingRepo = cookingRepo)

    /** Starts the app on a fresh in-memory database and returns a client that sends the API token. */
    private fun ApplicationTestBuilder.start(cookingRepo: File? = null): HttpClient {
        val config = config(cookingRepo)
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
}
