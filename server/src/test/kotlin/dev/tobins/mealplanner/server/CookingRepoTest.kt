package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.CookingSyncStatus
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import java.util.concurrent.atomic.AtomicInteger
import org.eclipse.jgit.api.errors.GitAPIException
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The temporary directories of the test running, deleted after it. */
private val temporary = ConcurrentLinkedQueue<File>()

private fun tempDir(prefix: String): File = createTempDirectory(prefix).toFile().also(temporary::add)

class CookingRepoTest {
    @AfterTest
    fun deleteTemporaryDirectories() {
        while (true) (temporary.poll() ?: break).deleteRecursively()
    }

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
        val dir: File = tempDir("upstream")
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
        val repo = CookingRepo(File(tempDir("local"), "cooking"), upstream.url)

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
            val repo = CookingRepo(File(tempDir("local"), "cooking"), "http://127.0.0.1:${silent.address.port}/cooking.git", timeoutSeconds = 1)
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
        val dir = tempDir("manual")
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
            cookingRepo = File(tempDir("server"), "cooking"), cookingRepoUrl = upstream.url,
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
        // Every sync here follows a push and must fetch it: no interval between fetches.
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:",
            cookingRepo = File(tempDir("server"), "cooking"), cookingRepoUrl = upstream.url,
            cookingFetchInterval = Duration.ZERO,
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

    /** The server's site in a clone that counts its fetches; with a [gate], each fetch waits until it opens. */
    private class CountingRepo(dir: File, url: String, private val gate: CountDownLatch? = null) : CookingRepo(dir, url) {
        val fetches = AtomicInteger()

        /** Opens when the first fetch has begun. */
        val fetching = CountDownLatch(1)

        override fun update(): String? {
            fetches.incrementAndGet()
            fetching.countDown()
            gate?.await(30, TimeUnit.SECONDS)
            return super.update()
        }
    }

    /** The time as the server sees it, which moves only when a test moves it. */
    private class TestClock {
        @Volatile
        var now: Instant = Instant.parse("2026-11-17T12:00:00Z")

        fun advance(by: Duration) {
            now += by
        }
    }

    /**
     * A server's accounts and their cooking sites, without HTTP: [accounts] logged in, the first of them the owner;
     * in the database at [databaseUrl]. Closing it closes the database and deletes the accounts' files.
     */
    private class Accounts(site: CookingRepo?, shared: Boolean = true, accounts: Int = 2, databaseUrl: String = "jdbc:sqlite::memory:") : AutoCloseable {
        val clock = TestClock()
        private val opened = openDatabase(databaseUrl)
        val writes = RefusingWrites(opened.first)
        private val db = writes.database
        private val foods = FoodRepository(db)
        private val offers = OfferRepository(db, emptyList())
        private val prices = PriceRepository(db, null, foods, offers)
        private val images = tempDir("images")
        private val data = tempDir("data")
        private val users = UserRepository(db)
        val ids = (1..accounts).map { users.resolve(Identity("sub-$it", "user$it")).id }
        val cooking = CookingSync(users, data, site, shared, ::scope, CookingSync.FETCH_INTERVAL) { clock.now }

        override fun close() {
            opened.first.close()
            images.deleteRecursively()
            data.deleteRecursively()
        }

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
        val site = CountingRepo(File(tempDir("server"), "cooking"), upstream.url)
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
        accounts.clock.advance(Duration.ofHours(CookingSync.EVERY_HOURS.toLong()))
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
        val accounts = Accounts(CookingRepo(File(tempDir("server"), "cooking"), server.url))
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
        assertNull(status.fetchedAt, "the server's fetches are not its site's")
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
        val site = CountingRepo(File(tempDir("server"), "cooking"), upstream.url)
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

    /**
     * Any account may ask for a fetch of the server's site, which the others wait for (issue 215): within the
     * interval a second account's sync imports the clone as it is, which only [CookingSyncStatus.fetchedAt] tells.
     * The schedule is no exception. After the interval a sync fetches again.
     */
    @Test
    fun syncsOfTwoAccountsWithinTheIntervalFetchOnce() = runBlocking {
        val upstream = Upstream()
        val first = upstream.commit(mapOf("content/recipes/stew.md" to page("Stew")))
        val site = CountingRepo(File(tempDir("server"), "cooking"), upstream.url)
        val accounts = Accounts(site)
        val (owner, other) = accounts.ids
        val clock = accounts.clock

        assertEquals(first, accounts.cooking.sync(owner)?.commit)
        val fetched = clock.now
        val second = upstream.commit(mapOf("content/recipes/stew.md" to page("Stew", 500)))
        clock.advance(CookingSync.FETCH_INTERVAL.minusSeconds(1))
        val result = assertNotNull(accounts.cooking.sync(other))
        assertEquals(1, site.fetches.get(), "one fetch for both accounts")
        assertEquals(listOf(first, "1"), listOf(result.commit, "${result.added}"), "the clone as it was, imported")
        assertEquals(400.0, accounts.grams(other, "Stew"))
        val status = accounts.cooking.status(other)
        assertNull(status.error, "not fetching is no failure")
        assertEquals(listOf(clock.now.toString(), fetched.toString()), listOf(status.syncedAt, status.fetchedAt))
        accounts.cooking.syncScheduled()
        assertEquals(1, site.fetches.get(), "nor does the schedule fetch within the interval")

        clock.advance(Duration.ofSeconds(1))
        assertEquals(second, accounts.cooking.sync(other)?.commit)
        assertEquals(2, site.fetches.get(), "after the interval a sync fetches")
        assertEquals(500.0, accounts.grams(other, "Stew"))
        assertEquals(clock.now.toString(), accounts.cooking.status(owner).fetchedAt, "one site for every account on it")
        assertNotNull(accounts.cooking.sync(owner))
        assertEquals(2, site.fetches.get())
        assertEquals(500.0, accounts.grams(owner, "Stew"), "what the other account's fetch brought")

        clock.advance(Duration.ofHours(-1))
        assertNotNull(accounts.cooking.sync(owner))
        assertEquals(3, site.fetches.get(), "a clock set back does not hold fetching off for the hour")
    }

    /**
     * While one account's fetch runs, the others' syncs and first visits wait for it, as the clone's files change, and
     * then import what it brought: none of them fetches again, so none waits for more than the fetch already running.
     */
    @Test
    fun syncsDuringAFetchWaitForItAndFetchNoMore() = runBlocking {
        val upstream = Upstream().apply { commit(mapOf("content/recipes/stew.md" to page("Stew"))) }
        val gate = CountDownLatch(1)
        val site = CountingRepo(File(tempDir("server"), "cooking"), upstream.url, gate)
        val accounts = Accounts(site, accounts = 3)
        val (owner, second, third) = accounts.ids

        val running = launch(Dispatchers.IO) { accounts.cooking.sync(owner) }
        assertTrue(site.fetching.await(30, TimeUnit.SECONDS))
        val waiting = listOf(
            launch(Dispatchers.IO) { accounts.cooking.sync(second) },
            launch(Dispatchers.IO) { accounts.cooking.sync(third) },
            launch(Dispatchers.IO) { accounts.cooking.importFirst(third) },
            launch(Dispatchers.IO) { accounts.cooking.sync(owner) },
        )
        // Nothing says when they reach the clone's lock; a moment lets them get there while the fetch still runs.
        delay(300)
        assertTrue(waiting.none { it.isCompleted }, "the clone's files are changing: they wait")
        gate.countDown()
        (waiting + running).joinAll()
        assertEquals(1, site.fetches.get())
        for (id in accounts.ids) assertEquals(listOf("Stew"), accounts.bank(id), "account $id")
    }

    /**
     * After a fetch every account on the server's site imports it at once, into a database file as the server's
     * (issue 246): the imports write one after another, none fails, and every account ends with every recipe.
     */
    @Test
    fun accountsImportingAtOnceIntoADatabaseFileEachGetEveryRecipe() = runBlocking {
        val titles = (1..20).map { "Dish $it" }
        val upstream = Upstream().apply { commit(titles.associate { "content/recipes/${it.replace(' ', '-')}.md" to page(it) }) }
        val gate = CountDownLatch(1)
        val server = tempDir("server")
        val site = CountingRepo(File(server, "cooking"), upstream.url, gate)
        val database = tempDir("database")
        try {
            Accounts(site, accounts = 6, databaseUrl = "jdbc:sqlite:${File(database, "mealplanner.db").path}").use { accounts ->
                val syncs = accounts.ids.map { id -> launch(Dispatchers.IO) { accounts.cooking.sync(id) } }
                assertTrue(site.fetching.await(30, TimeUnit.SECONDS))
                gate.countDown()
                syncs.joinAll()
                assertEquals(1, site.fetches.get())
                for (id in accounts.ids) {
                    assertEquals(titles.sorted(), accounts.bank(id), "account $id")
                    assertNull(accounts.cooking.status(id).error, "account $id")
                }
            }
        } finally {
            for (dir in listOf(upstream.dir, server, database)) dir.deleteRecursively()
        }
    }

    /**
     * A remote that fails is tried once per interval, not by every request: within it the clone serves as it is,
     * without an error for whoever did not fetch, and without a clone a sync fails at once instead of trying again.
     */
    @Test
    fun aFailedFetchIsNotTriedAgainWithinTheInterval() = runBlocking {
        val upstream = Upstream().apply { commit(mapOf("content/recipes/stew.md" to page("Stew"))) }
        val site = CountingRepo(File(tempDir("server"), "cooking"), upstream.url)
        val accounts = Accounts(site)
        val (owner, other) = accounts.ids
        accounts.cooking.syncScheduled()
        upstream.dir.deleteRecursively()

        accounts.clock.advance(CookingSync.FETCH_INTERVAL)
        assertFailsWith<GitAPIException> { accounts.cooking.sync(owner) }
        assertNotNull(accounts.cooking.status(owner).error)
        assertNotNull(accounts.cooking.sync(other), "the clone, as it is")
        assertEquals(2, site.fetches.get())
        assertNull(accounts.cooking.status(other).error)
        assertEquals(listOf("Stew"), accounts.bank(other))

        // No clone: nothing to read until the next try.
        val gone = File(tempDir("gone"), "cooking.git").toURI().toString()
        val unreachable = CountingRepo(File(tempDir("server"), "cooking"), gone)
        val fresh = Accounts(unreachable)
        val (first, next) = fresh.ids
        assertFailsWith<GitAPIException> { fresh.cooking.sync(first) }
        assertFailsWith<IllegalStateException> { fresh.cooking.sync(next) }
        fresh.cooking.importFirst(next)
        assertEquals(1, unreachable.fetches.get(), "tried once")
        assertNotNull(fresh.cooking.status(next).error)
        fresh.clock.advance(CookingSync.FETCH_INTERVAL)
        assertFailsWith<GitAPIException> { fresh.cooking.sync(next) }
        assertEquals(2, unreachable.fetches.get(), "tried again after the interval")
    }

    /**
     * Issue 285: when the server's site cannot be fetched, the error is kept for each account that reads it; an
     * account whose error cannot be written (the database was busy) is logged, and the next one still gets its error.
     */
    @Test
    fun anErrorThatCannotBeKeptForOneAccountDoesNotSkipTheNext() = runBlocking {
        val gone = File(tempDir("gone"), "cooking.git").toURI().toString()
        val accounts = Accounts(CountingRepo(File(tempDir("server"), "cooking"), gone))
        val (first, next) = accounts.ids
        var refused = false
        accounts.writes.refuse = { sql -> "userMeta" in sql && !refused.also { refused = true } }

        accounts.cooking.syncScheduled()

        assertTrue(refused, "the first account's write was refused")
        assertNull(accounts.cooking.status(first).error)
        assertNotNull(accounts.cooking.status(next).error)
        accounts.close()
    }

    /** What the app reads of a sync within the interval: what was imported, and no error (issue 215). */
    @Test
    fun aSyncThatDidNotFetchAnswersWhatItImported() = testApplication {
        val upstream = Upstream().apply { commit(mapOf("content/recipes/stew.md" to page("Stew"))) }
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:",
            cookingRepo = File(tempDir("server"), "cooking"), cookingRepoUrl = upstream.url,
            dataDir = tempDir("data"),
        )
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        fun client(token: String) = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { bearerAuth(token) }
        }
        val (alice, bob) = listOf(client("alice"), client("bob"))
        assertEquals(HttpStatusCode.OK, alice.post("/api/sync/cooking").status)
        upstream.commit(mapOf("content/recipes/curry.md" to page("Curry")))

        val answer = bob.post("/api/sync/cooking")
        assertEquals(HttpStatusCode.OK, answer.status)
        val result = answer.body<SyncResult>()
        // The server's own round at start may have imported Bob's bank already.
        assertEquals(listOf(1, 0), listOf(result.added + result.unchanged, result.removed), "the stew, as the clone has it")
        val status = bob.get("/api/sync/cooking").body<CookingSyncStatus>()
        assertNull(status.error)
        assertNotNull(status.syncedAt)
        assertNotNull(status.fetchedAt)
        assertEquals(listOf("Stew"), bob.get("/api/recipes/bank").body<List<RecipeSummary>>().map { it.title })
    }

    @Test
    fun theIntervalBetweenFetchesIsConfigured() {
        val key = "COOKING_REPO_FETCH_INTERVAL_MINUTES"
        assertEquals(Duration.ofMinutes(5), ServerConfig.fromEnv(emptyMap()).cookingFetchInterval)
        assertEquals(Duration.ZERO, ServerConfig.fromEnv(mapOf(key to "0")).cookingFetchInterval)
        assertEquals(Duration.ofMinutes(15), ServerConfig.fromEnv(mapOf(key to " 15 ")).cookingFetchInterval)
        for (wrong in listOf("-1", "soon", "1.5")) {
            assertFailsWith<IllegalArgumentException>(wrong) { ServerConfig.fromEnv(mapOf(key to wrong)) }
        }
    }

    /** `COOKING_REPO_SHARED=0`: only the owner reads the server's site. */
    @Test
    fun unsharedOnlyTheOwnerReadsTheServersSite() = runBlocking {
        val upstream = Upstream().apply { commit(mapOf("content/recipes/stew.md" to page("Stew"))) }
        val accounts = Accounts(CookingRepo(File(tempDir("server"), "cooking"), upstream.url), shared = false)
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
        val path = tempDir("checkout")
        val data = tempDir("data")
        assertEquals(path, CookingSync.serverSite(path, "https://example.org/c.git", data)!!.dir, "the owner's clone stays where it was")
        assertEquals(File(data, "cooking"), CookingSync.serverSite(null, "https://example.org/c.git", data)!!.dir)
        assertNull(CookingSync.serverSite(path, null, data)!!.url, "a checkout managed by hand")
        assertNull(CookingSync.serverSite(null, null, data))
    }
}
