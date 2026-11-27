package dev.tobins.mealplanner.server

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.RotationInput
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingExtraInput
import dev.tobins.mealplanner.api.ShoppingRowKey
import dev.tobins.mealplanner.server.accounts.Identity
import dev.tobins.mealplanner.server.accounts.TokenVerifier
import dev.tobins.mealplanner.server.accounts.UserRepository
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
import dev.tobins.mealplanner.server.shopping.ShoppingKey
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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

/**
 * Every change that reads before it writes takes the write lock first ([writing]), so that another
 * connection's write in between (a second server process; before issue 248 also the offer refresh and the
 * price lookup) makes it wait instead of failing with "database is locked" (issue 169).
 *
 * The server runs on a database whose driver lets another connection write and commit right after the
 * first read inside a transaction, which is where a deferred transaction would lose: its first write then
 * fails with SQLITE_BUSY_SNAPSHOT. Taken first, the lock holds that write off until the change is done.
 *
 * Writers of one process take turns as well (issue 246), on a file as on the in-memory database of most tests,
 * whose one connection every thread shares: none begins its transaction inside another's. Every write of the
 * server does, and one that waits too long for its turn gives up with a clear error (issue 248).
 */
class WriteLockTest {
    private val dir = createTempDirectory("write-lock").toFile()
    private val url = "jdbc:sqlite:${File(dir, "plans.db").absolutePath}"
    private val week = "2026-11-16"

    @AfterTest
    fun cleanUp() {
        if (::driver.isInitialized) driver.close()
        if (otherDatabase.isInitialized()) otherDatabase.value.first.close()
        dir.deleteRecursively()
    }

    /** Another connection's write, to run once inside [within] ("StockRepository.useUp"), right after a read there. */
    private class Meddling(val within: String, val write: () -> Unit) {
        fun isIn(stack: Array<StackTraceElement>): Boolean {
            val (type, function) = within.split('.')
            return stack.any { it.className.substringAfterLast('.') == type && (it.methodName == function || it.methodName.startsWith("$function$")) }
        }
    }

    /**
     * Runs a [Meddling] write right after the first read inside a transaction within its function: as a write
     * of the offer refresh between a change's read and its write. Reads outside a transaction (a login of a
     * known account, the list a request answers with) are left alone, as they can't fail this way.
     */
    private class MeddlingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        val meddling = AtomicReference<Meddling?>(null)

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            val result = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
            val armed = meddling.get()
            if (armed != null && delegate.currentTransaction() != null && armed.isIn(Throwable().stackTrace) && meddling.compareAndSet(armed, null)) {
                armed.write()
            }
            return result
        }
    }

    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|10|Rundvlees rauw|Beef raw||per 100g|150|20|4|2|0|0|0|50",
    ).joinToString("\r\n")

    /** The account's names as the identity provider gives them; changing them makes the next login write. */
    private var displayName = "T"

    private val names = object : TokenVerifier {
        override suspend fun verify(token: String) = Identity("sub-$token", token, displayName)
        override suspend fun clientConfig() = TestTokens.clientConfig()
    }

    private lateinit var driver: MeddlingDriver

    /** Another connection, as a second server process has; [lastWrite] reads what it wrote last. */
    private val otherDatabase = lazy { openDatabase(url).also { it.second.planQueries.ensureSource("AH") } }
    private val other by lazy { otherDatabase.value.second }

    private fun lastWrite() = other.planQueries.selectSources().executeAsList().single().error

    private fun ApplicationTestBuilder.start(): HttpClient {
        val repo = File(dir, "cooking")
        fun write(path: String, text: String) = File(repo, "content/$path").apply { parentFile.mkdirs() }.writeText(text)
        for (i in 0..4) write("recipes/dinner$i.md", "---\ntitle: Dinner $i\n---\n```ingredients\nservings: 4\n${1200 + i * 80} | g | beef\n```")
        val config = ServerConfig(
            port = 0, databaseUrl = url, cookingRepo = repo, dataDir = File(dir, "data"),
            nevoFile = createTempFile(dir.toPath(), "nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        driver = MeddlingDriver(openDatabase(url).first)
        val db = Database(driver)
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = names) }
        return createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
    }

    /**
     * Sends [request] while another connection writes right after a read in a transaction of [within], and
     * checks that the request went through, and the other write after it. Without the lock taken first,
     * the request's write fails (a 500), as the other one committed in between.
     */
    private suspend fun whileAnotherWrites(change: String, within: String, request: suspend () -> HttpResponse) {
        var writer: Thread? = null
        driver.meddling.set(Meddling(within) {
            // Time to commit, as it would when nothing holds the lock; then the request goes on without it.
            writer = thread { other.planQueries.recordFailure("written during $change", "AH") }.apply { join(300) }
        })
        val response = request()
        assertTrue(response.status.isSuccess(), "$change: ${response.status} ${response.bodyAsText()}")
        assertNotNull(writer, "$change read nothing in a transaction of $within, so this case tests nothing").join()
        assertEquals("written during $change", lastWrite(), "the other write waited, and then went through")
    }

    @Test
    fun aChangeThatReadsFirstWaitsForAnotherConnectionThatWritesMeanwhile() = testApplication {
        val http = start()
        http.post("/api/sync/cooking")
        http.addWholeBank()
        http.put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        val beef = http.get("/api/ingredients").body<List<Ingredient>>().first { it.name == "Beef raw" }.id
        val dinner = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.title == "Dinner 1" }

        // The plan (PlanRepository), and a stock change joined from inside it (StockRepository.useUp, giveBack).
        whileAnotherWrites("a replan", "PlanRepository.generate") { http.post("/api/plans/$week/generate") }
        whileAnotherWrites("eating a meal", "PlanRepository.setStatus") { http.put("/api/plans/$week/meals/1/dinner/status") { setBody(MealStatus.EATEN) } }
        whileAnotherWrites("un-eating it", "PlanRepository.setStatus") { http.put("/api/plans/$week/meals/1/dinner/status") { setBody(MealStatus.PLANNED) } }

        // The stock on its own (StockRepository) and from the shopping list (ShoppingListRepository).
        val stockUp = ShoppingKey.Stock(beef).toString()
        whileAnotherWrites("a stock-up tick", "StockRepository.putOnHandFrom") { http.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(stockUp, true, grams = 500.0)) } }
        whileAnotherWrites("taking it back", "StockRepository.takeBackOnHand") { http.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(stockUp, false)) } }
        whileAnotherWrites("on hand by hand", "StockRepository.setOnHand") { http.put("/api/pantry/on-hand/$beef") }
        whileAnotherWrites("off hand by hand", "StockRepository.setOnHand") { http.delete("/api/pantry/on-hand/$beef") }

        // A bought item no meal needs any more, used up (ShoppingListRepository.useUp, StockRepository.addToStock).
        val later = "2026-11-23"
        http.put("/api/plans/$later/meals/0/dinner") { setBody(MealChoice(dinner.id)) }
        http.put("/api/plans/$later/shopping") { setBody(ShoppingCheck(ShoppingKey.Ingredient(beef).toString(), true)) }
        http.delete("/api/plans/$later/meals/1/lunch")
        http.delete("/api/plans/$later/meals/0/dinner")
        whileAnotherWrites("using up what was bought", "ShoppingListRepository.useUp") { http.post("/api/plans/$later/shopping/use-up") { setBody(ShoppingRowKey("i$beef")) } }

        // The catalogue (IngredientRepository.linking) and the recipes (RecipeRepository).
        whileAnotherWrites("a new ingredient", "IngredientRepository.linking") { http.post("/api/ingredients") { setBody(IngredientInput("Pepper")) } }
        val own = http.post("/api/recipes") { setBody(RecipeInput("Beef bowl", 1, "300 | g | beef")) }.body<RecipeDetail>().summary
        whileAnotherWrites("editing a recipe", "RecipeRepository.update") { http.put("/api/recipes/${own.id}") { setBody(RecipeInput("Beef bowl", 2, "300 | g | beef")) } }
        whileAnotherWrites("taking it out of the rotation", "RecipeRepository.changeRotation") { http.put("/api/recipes/${own.id}/rotation") { setBody(RotationInput(false)) } }
        whileAnotherWrites("adding it again, in the rotation", "RecipeRepository.changeAdded") { http.put("/api/recipes/${own.id}/added") { setBody(AddedInput(added = true)) } }
        whileAnotherWrites("pinning it as a routine", "RecipeRepository.saveRoutines") { http.put("/api/routines") { setBody(Routines(snack = own.id)) } }
        File(dir, "cooking/content/recipes/dinner0.md").appendText("\nA new note.")
        whileAnotherWrites("a sync of the cooking site", "RecipeRepository.syncCookingSite") { http.post("/api/sync/cooking") }

        // Logging in with new names (UserRepository.resolve).
        displayName = "Tee"
        whileAnotherWrites("a login under new names", "UserRepository.resolveForWriting") { http.get("/api/ping") }
    }

    @Test
    fun aKnownAccountDoesNotWaitForTheWriteLockToLogIn() = testApplication {
        val http = start()
        assertEquals(HttpStatusCode.OK, http.get("/api/ping").status)
        val held = CountDownLatch(1)
        val done = CountDownLatch(1)
        val holder = thread { other.writing { held.countDown(); done.await() } }
        try {
            held.await()
            val started = System.nanoTime()
            assertEquals(HttpStatusCode.OK, http.get("/api/ping").status)
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue(millis < 2_000, "every request would wait for every writer: took $millis ms")
        } finally {
            done.countDown()
            holder.join()
        }
    }

    /**
     * A database and an account in it, for [check]; on the in-memory database of most tests and on a file, as the
     * server's, where writers wait up to [writeWait] for their turn. Closed after.
     */
    private fun onEither(writeWait: Duration = WRITE_WAIT, check: (Database, UserRepository, Long) -> Unit) {
        for (at in listOf("jdbc:sqlite::memory:", url)) {
            val (driver, db) = openDatabase(at, writeWait)
            try {
                val users = UserRepository(db)
                check(db, users, users.resolve(Identity("sub-turns", "turns")).id)
            } catch (e: Throwable) {
                throw AssertionError("On $at: ${e.message}", e)
            } finally {
                driver.close()
            }
        }
    }

    /** Runs [body] on a thread of its own, [name]d, whatever the machine's cores; [CompletableFuture.get] passes its failure on. */
    private fun elsewhere(name: String = "elsewhere", body: () -> Unit): CompletableFuture<Unit> = CompletableFuture<Unit>().also { done ->
        thread(name = name) {
            try {
                done.complete(body())
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
    }

    /**
     * A second writer waits for the first to commit before its own transaction begins (issue 246). Two
     * accounts' cooking-site imports at once began a second transaction on the in-memory database's one
     * connection ("cannot start a transaction within a transaction"), or the second ran inside the first's.
     */
    @Test
    fun aSecondWriterWaitsUntilTheFirstIsDone() = onEither { db, users, account ->
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = elsewhere { db.writing { users.setMeta(account, "turn", "first"); inside.countDown(); release.await(30, SECONDS) } }
        assertTrue(inside.await(30, SECONDS))
        val entered = CountDownLatch(1)
        val second = elsewhere { db.writing { entered.countDown(); users.setMeta(account, "turn", "second") } }
        assertFalse(entered.await(300, MILLISECONDS), "the second writer waits while the first is in its transaction")
        release.countDown()
        first.get(30, SECONDS)
        second.get(30, SECONDS)
        assertEquals("second", users.meta(account, "turn"))
    }

    /** A writer that fails takes back what it wrote, and nothing of the writer before it, which committed (issue 246). */
    @Test
    fun aWriterThatFailsTakesBackOnlyItsOwn() = onEither { db, users, account ->
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = elsewhere { db.writing { users.setMeta(account, "turn", "first"); inside.countDown(); release.await(30, SECONDS) } }
        assertTrue(inside.await(30, SECONDS))
        val second = elsewhere { db.writing { users.setMeta(account, "turn", "second"); error("the second writer fails") } }
        // Time for the second writer to get as far as it can while the first holds its turn.
        Thread.sleep(300)
        release.countDown()
        first.get(30, SECONDS)
        val failure = assertFailsWith<ExecutionException> { second.get(30, SECONDS) }
        assertEquals("the second writer fails", failure.cause?.message)
        assertEquals("first", users.meta(account, "turn"))
    }

    /**
     * A writer called inside another on the same thread, as eating a meal changes the stock, joins it rather than
     * waiting for itself: the two commit or are taken back together, and afterwards another thread writes as usual.
     */
    @Test
    fun aWriterInsideAWriterJoinsIt() = onEither { db, users, account ->
        elsewhere {
            db.writing { users.setMeta(account, "outer", "1"); db.writing { users.setMeta(account, "inner", "1") } }
            assertFailsWith<IllegalStateException> {
                db.writing {
                    users.setMeta(account, "outer", "2")
                    db.writing { users.setMeta(account, "inner", "2"); error("the inner writer fails") }
                }
            }
        }.get(30, SECONDS)
        assertEquals(listOf("1", "1"), listOf(users.meta(account, "outer"), users.meta(account, "inner")))
        elsewhere { db.writing { users.setMeta(account, "outer", "3") } }.get(30, SECONDS)
        assertEquals("3", users.meta(account, "outer"))
    }

    /**
     * A writer gives up when another holds the turn for the database's whole wait, saying which thread holds it and,
     * in the error's log, where that one is (issue 248): a writer that hangs no longer holds up every other one for good.
     * The one holding it is not disturbed, and once it is done the next writer goes as usual.
     */
    @Test
    fun aWriterThatWaitsTooLongGivesUpAndSaysWhoHoldsTheTurn() = onEither(writeWait = Duration.ofMillis(300)) { db, users, account ->
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = elsewhere("slow-writer") { db.writing { users.setMeta(account, "turn", "first"); inside.countDown(); release.await(30, SECONDS) } }
        try {
            assertTrue(inside.await(30, SECONDS))
            val started = System.nanoTime()
            val busy = assertFailsWith<DatabaseBusyException> { db.writing { users.setMeta(account, "turn", "second") } }
            val waited = (System.nanoTime() - started) / 1_000_000
            assertTrue(waited >= 300, "gave up after $waited ms, before the wait was over")
            assertTrue("thread slow-writer" in busy.message!!, busy.message)
            val where = busy.suppressed.single().stackTrace
            assertTrue(where.any { it.className == CountDownLatch::class.java.name }, "the holder's stack, waiting on its latch: ${where.toList()}")
        } finally {
            release.countDown()
        }
        first.get(30, SECONDS)
        assertEquals("first", users.meta(account, "turn"), "the writer that held the turn kept its change")
        db.writing { users.setMeta(account, "turn", "third") }
        assertEquals("third", users.meta(account, "turn"))
    }

    /**
     * A request that waits too long for its turn is answered 503 with a message for the app, rather than hanging for
     * as long as the writer before it does; the same request goes through once the turn is free.
     */
    @Test
    fun aRequestThatWaitsTooLongToWriteIsAnsweredBusy() = testApplication {
        val (opened, db) = openDatabase(url, writeWait = Duration.ofMillis(300))
        val config = ServerConfig(port = 0, databaseUrl = url, cookingRepo = null, dataDir = File(dir, "data"))
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = names) }
        try {
            val http = createClient {
                install(ContentNegotiation) { json() }
                defaultRequest {
                    bearerAuth("t")
                    contentType(ContentType.Application.Json)
                }
            }
            val profile = Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT)
            // Logged in first: a new account is written too.
            assertEquals(HttpStatusCode.OK, http.get("/api/ping").status)
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = elsewhere { db.writing { held.countDown(); release.await(30, SECONDS) } }
            try {
                assertTrue(held.await(30, SECONDS))
                val busy = http.put("/api/profile") { setBody(profile) }
                assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
                assertTrue("try again" in busy.bodyAsText(), busy.bodyAsText())
                assertFalse("reason" in busy.bodyAsText(), "a busy write is not \"generation is off\": ${busy.bodyAsText()}")
            } finally {
                release.countDown()
            }
            holder.get(30, SECONDS)
            assertEquals(HttpStatusCode.OK, http.put("/api/profile") { setBody(profile) }.status)
        } finally {
            opened.close()
        }
    }

    /** The warnings of the writer turn's log while [block] runs. */
    private fun turnWarnings(block: () -> Unit): List<String> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val log = LoggerFactory.getLogger("dev.tobins.mealplanner.server.WriterTurn") as Logger
        log.addAppender(appender)
        try {
            block()
        } finally {
            log.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    /**
     * A writer that holds the turn for more than a third of the wait is logged as it lets go, with where it wrote: three
     * such in a row would make the next writer give up. A quick one, and one inside another, are not.
     */
    @Test
    fun aWriterThatHoldsTheTurnLongIsLoggedWithWhereItWrote() = onEither(writeWait = Duration.ofMillis(900)) { db, users, account ->
        val warnings = turnWarnings {
            db.writing { users.setMeta(account, "quick", "1") }
            db.writing { users.setMeta(account, "slow", "1"); Thread.sleep(400) }
        }
        val warning = warnings.single()
        assertTrue("WriteLockTest.kt" in warning && "give up after 900 ms" in warning, warning)
    }

    /**
     * What wrote outside [writing] before issue 248, from several accounts at once with the NEVO import running: making
     * a recipe (an insert, then `last_insert_rowid()`), an own item of the list counted against its limit, ticking it,
     * an account's settings. On the in-memory database such writes began a transaction inside another's ("cannot
     * start a transaction within a transaction"), or took another's new row for their own. Each account ends with
     * exactly what it wrote, on either database.
     */
    @Test
    fun writersOfSeveralAccountsAtOnceEachKeepTheirOwn() = onEither { db, users, _ ->
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        val prices = PriceRepository(db, null, foods, offers)
        val pictures = File(dir, "pictures")
        val nevoFile = File(dir, "nevo.csv").apply { writeText(nevo) }
        fun scope(account: Long) = UserScope(account, db, foods, offers, prices, null, pictures)
        val accounts = (1..6).map { users.resolve(Identity("sub-$it", "user$it")).id }
        val writers = accounts.map { account ->
            elsewhere("account-$account") {
                val user = scope(account)
                repeat(8) { n ->
                    val recipe = user.recipes.create(RecipeInput("Dish $account-$n", 2, "${100 + n} | g | beef"))
                    assertEquals("Dish $account-$n" to 100.0 + n, recipe.summary.title to recipe.ingredients.single().line.amount)
                    val item = user.shopping.addExtra(week, ShoppingExtraInput("Item $n")).items.single { it.key.startsWith("x:") && it.name == "Item $n" }
                    user.shopping.setChecked(week, ShoppingCheck(item.key, true))
                    users.setMeta(account, "last", "$n")
                }
            }
        } + elsewhere("nevo-import") { repeat(4) { assertEquals(1, foods.importNevo(nevoFile)) } }
        writers.forEach { it.get(60, SECONDS) }
        for (account in accounts) {
            val user = scope(account)
            assertEquals((0..7).map { "Dish $account-$it" }, user.recipes.list().map { it.title }.sorted(), "account $account")
            assertEquals((0..7).map { "Item $it" }, user.shopping.get(week).items.filter { it.key.startsWith("x:") && it.checked }.map { it.name }.sorted())
            assertEquals("7", users.meta(account, "last"))
        }
    }
}
