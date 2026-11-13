package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.units.Dimension
import dev.tobins.mealplanner.units.UnitTable
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.eclipse.jgit.api.Git
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CookingRepoTest {
    private val units = """
        # Comments and unknown keys are fine.
        weight:
          g:
            factor: 1
            system: metric
            abbr: g
            aliases: [gram, grams]
          kg:
            factor: 1000
        volume:
          ml:
            factor: 1
          snuf:
            factor: 0.3
            aliases: [snufje]
        count:
          clove:
            system: any
            aliases: [teentje]
    """.trimIndent()

    /** A git repository standing in for the cooking repo on GitHub. */
    private class Upstream {
        val dir: File = createTempDirectory("upstream").toFile()
        private val git: Git = Git.init().setDirectory(dir).setInitialBranch("master").call()

        fun commit(files: Map<String, String>, message: String = "Update"): String {
            for ((path, text) in files) File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
            git.add().addFilepattern(".").call()
            return git.commit().setMessage(message).setAuthor("test", "test@example.invalid").setCommitter("test", "test@example.invalid").call().name
        }

        val url: String get() = dir.toURI().toString()
    }

    @Test
    fun unitsYamlBecomesTheUnitTable() {
        val table = parseUnits(units)
        assertEquals(Dimension.VOLUME, table.resolve("snufje")?.dimension)
        assertEquals(0.3, table.resolve("SNUF")?.factor)
        assertEquals(1000.0, table.resolve("kg")?.factor)
        assertEquals("clove", table.resolve("teentje")?.key)
        assertEquals(1.0, table.resolve("clove")?.factor, "count units have no factor")
        assertNull(table.resolve("cup"), "only what the file lists")
    }

    @Test
    fun clonesOnFirstUseThenFollowsTheRemoteBranch() {
        val upstream = Upstream()
        val first = upstream.commit(mapOf("content/a.md" to "first", "data/units.yaml" to units))
        val repo = CookingRepo(File(createTempDirectory("local").toFile(), "cooking"), upstream.url)

        assertEquals(first, repo.update())
        assertEquals("first", File(repo.dir, "content/a.md").readText())
        assertEquals(0.3, repo.units().resolve("snufje")?.factor)

        // Local edits are discarded; new upstream commits arrive.
        File(repo.dir, "content/a.md").writeText("edited here")
        val second = upstream.commit(mapOf("content/a.md" to "second", "content/b.md" to "new"))
        assertEquals(second, repo.update())
        assertEquals("second", File(repo.dir, "content/a.md").readText())
        assertEquals("new", File(repo.dir, "content/b.md").readText())
        assertNotEquals(first, second)
    }

    @Test
    fun withoutAUrlTheDirectoryIsUsedAsItIs() {
        val dir = createTempDirectory("manual").toFile()
        assertNull(CookingRepo(dir, url = null).update())
        assertEquals(UnitTable.Default, CookingRepo(dir, url = null).units(), "no units.yaml: the built-in table")
    }

    @Test
    fun syncFetchesTheRepoAndUsesItsUnits() = testApplication {
        val upstream = Upstream()
        val commit = upstream.commit(
            mapOf(
                "data/units.yaml" to units,
                // "snuf" is unknown to the built-in table, so 2 snuf is 0.6 ml only with the repo's units.
                "content/sauces/dressing.md" to "---\ntitle: Dressing\n---\n```ingredients\nservings: 1\n2 | snuf | salt\n```",
            ),
        )
        val config = ServerConfig(
            port = 0, apiToken = "t", databaseUrl = "jdbc:sqlite::memory:",
            cookingRepo = File(createTempDirectory("server").toFile(), "cooking"), cookingRepoUrl = upstream.url,
        )
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList()) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { bearerAuth("t") }
        }

        val sync = http.post("/api/sync/cooking").body<SyncResult>()
        assertEquals(commit, sync.commit)
        // The server also syncs once at start; whichever ran first imported the recipe.
        assertEquals(1, sync.added + sync.unchanged)
        val id = http.get("/api/recipes").body<List<RecipeSummary>>().single().id
        val line = http.get("/api/recipes/$id").body<RecipeDetail>().ingredients.single().line
        assertEquals(Dimension.VOLUME, line.dimension)
        assertEquals(0.6, line.baseAmount!!, absoluteTolerance = 1e-9)
    }
}
