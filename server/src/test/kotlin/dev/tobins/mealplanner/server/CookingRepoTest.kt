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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import java.util.concurrent.atomic.AtomicInteger
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
import kotlin.test.assertNotNull
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

    /** The server's site in a clone that counts its fetches. */
    private class CountingRepo(dir: File, url: String) : CookingRepo(dir, url) {
        val fetches = AtomicInteger()

        override fun update(): String? {
            fetches.incrementAndGet()
            return super.update()
        }
    }

    /** A server's accounts and their cooking sites, without HTTP: [accounts] logged in, the first of them the owner. */
    private class Accounts(site: CookingRepo?, shared: Boolean = true, accounts: Int = 2) {
        private val db = openDatabase("jdbc:sqlite::memory:").second
        private val foods = FoodRepository(db)
        private val offers = OfferRepository(db, emptyList())
        private val prices = PriceRepository(db, null, foods, offers)
        private val images = createTempDirectory("images").toFile()
        private val users = UserRepository(db)
        val ids = (1..accounts).map { users.resolve(Identity("sub-$it", "user$it")).id }
        val cooking = CookingSync(users, createTempDirectory("data").toFile(), site, shared, ::scope)

        fun scope(id: Long) = UserScope(id, db, foods, offers, prices, null, images)
        fun bank(id: Long) = scope(id).recipes.bank().map { it.title }.sorted()
        fun mine(id: Long) = scope(id).recipes.list().associate { it.title to it.removedFromSite }
        fun add(id: Long, title: String) = scope(id).recipes.let { it.setAdded(it.bank().single { r -> r.title == title }.id, AddedInput(added = true)) }
        fun grams(id: Long, title: String) = scope(id).recipes.let { r ->
            (r.list() + r.bank()).single { it.title == title }.let { r.find(it.id)!!.ingredients.single().line.amount }
        }
    }

    private fun page(title: String, grams: Int = 400) = "---\ntitle: $title\n---\n```ingredients\nservings: 2\n$grams | g | beef\n```"

    /**
     * Two accounts on the server's site (issue 208): one fetch a round, and each account's own rows. A changed file
     * reaches both; a deleted one leaves both banks, and stays where it was added.
     */
    @Test
    fun accountsOnTheServersSiteShareOneFetchAndKeepTheirOwnRows() = runBlocking {
        val upstream = Upstream()
        upstream.commit(mapOf("content/recipes/stew.md" to page("Stew"), "content/recipes/curry.md" to page("Curry")))
        val site = CountingRepo(File(createTempDirectory("server").toFile(), "cooking"), upstream.url)
        val accounts = Accounts(site)
        val (owner, other) = accounts.ids

        accounts.cooking.syncScheduled()
        assertEquals(1, site.fetches.get())
        for (id in accounts.ids) assertEquals(listOf("Curry", "Stew"), accounts.bank(id), "account $id")
        accounts.add(other, "Curry")
        assertEquals(mapOf("Curry" to false), accounts.mine(other))
        assertEquals(emptyMap(), accounts.mine(owner), "added in one account only")

        upstream.commit(mapOf("content/recipes/stew.md" to page("Stew", 500)))
        upstream.delete("content/recipes/curry.md")
        accounts.cooking.syncScheduled()
        assertEquals(2, site.fetches.get(), "one fetch for both accounts")
        for (id in accounts.ids) {
            assertEquals(listOf("Stew"), accounts.bank(id), "account $id")
            assertEquals(500.0, accounts.grams(id, "Stew"), "account $id")
            assertTrue(accounts.cooking.status(id).shared)
        }
        assertEquals(mapOf("Curry" to true), accounts.mine(other), "kept where it was added, as no longer on the site")
        assertEquals(emptyMap(), accounts.mine(owner))
    }

    /** An account that sets its own repository leaves the server's bank, and keeps what it added from it. */
    @Test
    fun anAccountThatSetsItsOwnRepositoryKeepsWhatItAddedFromTheServersSite() = runBlocking {
        val server = Upstream().apply { commit(mapOf("content/recipes/stew.md" to page("Stew"), "content/recipes/curry.md" to page("Curry"))) }
        val accounts = Accounts(CookingRepo(File(createTempDirectory("server").toFile(), "cooking"), server.url))
        val (owner, other) = accounts.ids
        accounts.cooking.syncScheduled()
        accounts.add(other, "Stew")

        // An address of the account's own is never a file on the server, so this one is not fetched here.
        val own = "https://example.invalid/mine/cooking.git"
        assertFailsWith<IllegalArgumentException> { accounts.cooking.setRepo(other, server.url) }
        accounts.cooking.setRepo(other, own)
        assertEquals(emptyList(), accounts.bank(other), "the server's bank-only recipes left with its site")
        assertEquals(mapOf("Stew" to true), accounts.mine(other), "kept, as no longer on its site")
        val status = accounts.cooking.status(other)
        assertEquals(listOf(own, "false", "null"), listOf(status.repoUrl, "${status.shared}", "${status.syncedAt}"))
        accounts.cooking.importFirst(other)
        assertEquals(emptyList(), accounts.bank(other), "a first visit imports the server's site only for who reads it")

        assertNotNull(accounts.cooking.sync(owner))
        assertEquals(listOf("Curry", "Stew"), accounts.bank(owner), "the owner's bank is the server's still")
        assertEquals(emptyMap(), accounts.mine(owner))

        // Back to the server's site: the curry is in the bank again, and the stew on the site, still added.
        accounts.cooking.setRepo(other, null)
        accounts.cooking.importFirst(other)
        assertEquals(listOf("Curry"), accounts.bank(other))
        assertEquals(mapOf("Stew" to false), accounts.mine(other))
    }

    /** A never synced account's first visits to the bank, two at once, import once: one clone, no second fetch. */
    @Test
    fun firstVisitsAtOnceImportOnce() = runBlocking {
        val upstream = Upstream().apply { commit(mapOf("content/recipes/stew.md" to page("Stew"))) }
        val site = CountingRepo(File(createTempDirectory("server").toFile(), "cooking"), upstream.url)
        val accounts = Accounts(site)
        val (owner, other) = accounts.ids

        (1..4).map { launch(Dispatchers.IO) { accounts.cooking.importFirst(other) } }.joinAll()
        assertEquals(1, site.fetches.get(), "cloned once, as there was no clone")
        assertEquals(listOf("Stew"), accounts.bank(other))
        assertNotNull(accounts.cooking.status(other).syncedAt)
        accounts.cooking.importFirst(owner)
        assertEquals(1, site.fetches.get(), "the clone is there: no fetch")
        assertEquals(listOf("Stew"), accounts.bank(owner))
    }

    /** `COOKING_REPO_SHARED=0`: only the owner reads the server's site. */
    @Test
    fun unsharedOnlyTheOwnerReadsTheServersSite() = runBlocking {
        val upstream = Upstream().apply { commit(mapOf("content/recipes/stew.md" to page("Stew"))) }
        val accounts = Accounts(CookingRepo(File(createTempDirectory("server").toFile(), "cooking"), upstream.url), shared = false)
        val (owner, other) = accounts.ids
        accounts.cooking.syncScheduled()
        accounts.cooking.importFirst(other)
        assertEquals(listOf("Stew"), accounts.bank(owner))
        assertEquals(emptyList(), accounts.bank(other))
        assertNull(accounts.cooking.sync(other))
        assertEquals(upstream.url, accounts.cooking.status(owner).repoUrl)
        assertEquals(listOf(false, false), accounts.cooking.status(other).let { listOf(it.shared, it.serverSite) })
    }

    @Test
    fun theServersSiteIsTheConfiguredCheckoutAsBefore() {
        val path = createTempDirectory("checkout").toFile()
        val data = createTempDirectory("data").toFile()
        assertEquals(path, CookingSync.serverSite(path, "https://example.org/c.git", data)!!.dir, "the owner's clone stays where it was")
        assertEquals(File(data, "cooking"), CookingSync.serverSite(null, "https://example.org/c.git", data)!!.dir)
        assertNull(CookingSync.serverSite(path, null, data)!!.url, "a checkout managed by hand")
        assertNull(CookingSync.serverSite(null, null, data))
    }
}
