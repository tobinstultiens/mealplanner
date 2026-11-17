package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.AddedInput
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
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import com.sun.net.httpserver.HttpServer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

        fun delete(vararg paths: String): String {
            for (path in paths) git.rm().addFilepattern(path).call()
            return git.commit().setMessage("Remove").setAuthor("test", "test@example.invalid").setCommitter("test", "test@example.invalid").call().name
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
    fun aRemoteThatNeverAnswersFailsTheUpdateInsteadOfHoldingItForever() {
        // Takes the request and then says nothing, like a stalled git server.
        val release = CountDownLatch(1)
        val silent = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { release.await() }
            start()
        }
        try {
            val repo = CookingRepo(File(createTempDirectory("local").toFile(), "cooking"), "http://127.0.0.1:${silent.address.port}/cooking.git", timeoutSeconds = 1)
            val started = System.nanoTime()
            assertFailsWith<GitAPIException> { repo.update() }
            assertTrue((System.nanoTime() - started) / 1_000_000_000 < 30, "gave up after the timeout")
        } finally {
            release.countDown()
            silent.stop(0)
        }
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
            port = 0, databaseUrl = "jdbc:sqlite::memory:",
            cookingRepo = File(createTempDirectory("server").toFile(), "cooking"), cookingRepoUrl = upstream.url,
        )
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), verifier = TestTokens) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { bearerAuth("t") }
        }

        val sync = http.post("/api/sync/cooking").body<SyncResult>()
        assertEquals(commit, sync.commit)
        // The server also syncs once at start; whichever ran first imported the recipe.
        assertEquals(1, sync.added + sync.unchanged)
        val id = http.get("/api/recipes/bank").body<List<RecipeSummary>>().single().id
        val line = http.get("/api/recipes/$id").body<RecipeDetail>().ingredients.single().line
        assertEquals(Dimension.VOLUME, line.dimension)
        assertEquals(0.6, line.baseAmount!!, absoluteTolerance = 1e-9)
    }

    /**
     * The recipe bank across syncs (issue 205). A new file's recipe is in the bank, not in my recipes. Whether
     * a recipe is added and in the rotation is the account's: a changed file, and a file that leaves the site
     * and comes back, keep both. An added recipe whose file leaves stays one of mine, marked; a bank one is
     * hidden until its file is back.
     */
    @Test
    fun aSyncKeepsWhatTheAccountAddedAndPutInItsRotation() = testApplication {
        val upstream = Upstream()
        fun page(title: String, grams: Int = 400) = "---\ntitle: $title\n---\n```ingredients\nservings: 2\n$grams | g | beef\n```"
        val files = mapOf("stew" to "Stew", "curry" to "Curry", "soup" to "Soup").entries.associate { (name, title) -> "content/recipes/$name.md" to page(title) }
        upstream.commit(files)
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:",
            cookingRepo = File(createTempDirectory("server").toFile(), "cooking"), cookingRepoUrl = upstream.url,
        )
        val db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), verifier = TestTokens) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
        // Counts are not asserted: the server's own sync at start may take a change before this one does.
        suspend fun sync() = assertEquals(HttpStatusCode.OK, http.post("/api/sync/cooking").status)
        suspend fun mine() = http.get("/api/recipes").body<List<RecipeSummary>>().associateBy { it.title }
        suspend fun bank() = http.get("/api/recipes/bank").body<List<RecipeSummary>>().map { it.title }

        sync()
        assertEquals(emptyMap(), mine(), "new on the site: in the bank only")
        val ids = http.get("/api/recipes/bank").body<List<RecipeSummary>>().associate { it.title to it.id }
        assertEquals(listOf("Curry", "Soup", "Stew"), ids.keys.sorted())

        http.put("/api/recipes/${ids.getValue("Stew")}/added") { setBody(AddedInput(added = true)) }
        http.put("/api/recipes/${ids.getValue("Curry")}/added") { setBody(AddedInput(added = true, inRotation = false)) }
        val account = UserRepository(db).resolve(Identity("sub-t", "t", "T")).id
        fun addedAt(title: String) = db.recipeQueries.selectById(account, ids.getValue(title)).executeAsOne().addedAt
        val addedWhen = listOf("Stew", "Curry").associateWith(::addedAt)
        assertTrue(addedWhen.values.all { it != null })
        suspend fun assertKept(why: String, curryOnSite: Boolean = true) = mine().let { mine ->
            assertEquals(listOf("Curry", "Stew"), mine.keys.sorted(), why)
            assertEquals(listOf(true, true), listOf(mine.getValue("Stew").inRotation, mine.getValue("Stew").added), why)
            assertEquals(listOf(false, true), listOf(mine.getValue("Curry").inRotation, mine.getValue("Curry").added), why)
            assertEquals(!curryOnSite, mine.getValue("Curry").removedFromSite, why)
            assertEquals(addedWhen, listOf("Stew", "Curry").associateWith(::addedAt), "$why: added when they were added")
        }

        // Changed files.
        upstream.commit(mapOf("content/recipes/stew.md" to page("Stew", 500), "content/recipes/curry.md" to page("Curry", 600)))
        sync()
        assertKept("after a change")
        assertEquals(listOf("Soup"), bank())
        assertEquals(500.0, http.get("/api/recipes/${ids.getValue("Stew")}").body<RecipeDetail>().ingredients.single().line.amount)

        // The curry and the soup leave the site: the curry stays one of mine, the soup is gone from the bank.
        upstream.delete("content/recipes/curry.md", "content/recipes/soup.md")
        sync()
        assertKept("after its file left the site", curryOnSite = false)
        assertEquals(emptyList(), bank())

        // Back on the site, both are where they were.
        upstream.commit(files)
        sync()
        assertKept("after its file came back")
        assertEquals(listOf("Soup"), bank())
    }
}
