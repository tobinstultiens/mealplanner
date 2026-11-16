package dev.tobins.mealplanner.server

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingRowKey
import dev.tobins.mealplanner.server.db.Database
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every change that reads before it writes takes the write lock first ([writing]), so that another
 * connection's write in between (the offer refresh, the price lookup, a second server process) makes it
 * wait instead of failing with "database is locked" (issue 169).
 *
 * The server runs on a database whose driver lets another connection write and commit right after the
 * first read inside a transaction, which is where a deferred transaction would lose: its first write then
 * fails with SQLITE_BUSY_SNAPSHOT. Taken first, the lock holds that write off until the change is done.
 */
class WriteLockTest {
    private val dir = createTempDirectory("write-lock").toFile()
    private val url = "jdbc:sqlite:${File(dir, "plans.db").absolutePath}"
    private val week = "2026-11-16"

    @AfterTest
    fun cleanUp() {
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

    /** The account's names as the identity provider gives them; changing them makes the next login write. */
    private var displayName = "T"

    private val names = object : TokenVerifier {
        override suspend fun verify(token: String) = Identity("sub-$token", token, displayName)
        override suspend fun clientConfig() = TestTokens.clientConfig()
    }

    private lateinit var driver: MeddlingDriver

    /** Another connection, as the offer refresh and the price lookup have; [lastWrite] reads what it wrote last. */
    private val other by lazy { openDatabase(url).second.also { it.planQueries.ensureSource("AH") } }

    private fun lastWrite() = other.planQueries.selectSources().executeAsList().single().error

    private fun ApplicationTestBuilder.start(): HttpClient {
        val repo = File(dir, "cooking")
        fun write(path: String, text: String) = File(repo, "content/$path").apply { parentFile.mkdirs() }.writeText(text)
        for (i in 0..4) write("recipes/dinner$i.md", "---\ntitle: Dinner $i\n---\n```ingredients\nservings: 4\n${1200 + i * 80} | g | beef\n```")
        val nevo = listOf(
            "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
            "NEVO-Online 2025 9.0|Vlees|Meat and poultry|10|Rundvlees rauw|Beef raw||per 100g|150|20|4|2|0|0|0|50",
        ).joinToString("\r\n")
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
}
