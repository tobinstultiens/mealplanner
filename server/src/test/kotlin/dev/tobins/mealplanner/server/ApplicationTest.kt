package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.DataHealth
import dev.tobins.mealplanner.Health
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.prices.PriceSource
import dev.tobins.mealplanner.server.prices.StoreProduct
import java.time.Duration
import java.time.Instant
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.FoodDataset
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.api.CookingSyncStatus
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
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
import org.slf4j.LoggerFactory

class ApplicationTest {
    private fun config(cookingRepo: File?, nevoFile: File?) = ServerConfig(
        port = 0,
        databaseUrl = "jdbc:sqlite::memory:",
        cookingRepo = cookingRepo,
        nevoFile = nevoFile,
    )

    /** Starts the app on a fresh in-memory database and returns a client that sends the API token. */
    private fun ApplicationTestBuilder.start(cookingRepo: File? = null, nevoFile: File? = null): HttpClient {
        val config = config(cookingRepo, nevoFile)
        application { module(config, openDatabase(config.databaseUrl).second, verifier = TestTokens) }
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
        assertEquals(Health("ok", BuildInfo.version, BuildInfo.commit), start().get("/health").body<Health>())
    }

    @Test
    fun healthCarriesTheCommitTheBuildWasMadeFrom() = testApplication {
        // The commit is "dev" outside CI; in CI it is GITHUB_SHA, which the build wrote into the jar.
        val expected = System.getenv("GITHUB_SHA") ?: "dev"
        assertEquals(expected, start().get("/health").body<Health>().commit)
    }

    /** Starts the app with offer fetching [fetchOffers] and no stores to fetch from; [seed] fills the database first. */
    private fun ApplicationTestBuilder.startFetching(fetchOffers: Boolean, seed: (Database) -> Unit = {}): HttpClient {
        val config = config(null, null).copy(fetchOffers = fetchOffers)
        val database = openDatabase(config.databaseUrl).second.also(seed)
        application { module(config, database, offerSources = emptyList(), priceSource = null, verifier = TestTokens) }
        return createClient { install(ContentNegotiation) { json() } }
    }

    /** An offer source row as left by a fetch [hoursAgo] hours ago, optionally with a failed fetch since. */
    private fun Database.fetchedHoursAgo(store: Store, hoursAgo: Long, error: String? = null) {
        planQueries.ensureSource(store.name)
        planQueries.recordSuccess(Instant.now().minus(Duration.ofHours(hoursAgo)).toString(), store.name)
        error?.let { planQueries.recordFailure(it, store.name) }
    }

    @Test
    fun dataIsFreshWithoutBeingLoggedIn() = testApplication {
        val http = startFetching(fetchOffers = true) { it.fetchedHoursAgo(Store.AH, hoursAgo = 3) }
        val response = http.get("/health/data")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(DataHealth("ok"), response.body<DataHealth>())
    }

    @Test
    fun dataIsFreshBeforeAnyFetchRecorded() = testApplication {
        assertEquals(HttpStatusCode.OK, startFetching(fetchOffers = true).get("/health/data").status)
    }

    @Test
    fun dataIsStaleWhenAStoreWasLastFetchedThreeDaysAgo() = testApplication {
        val http = startFetching(fetchOffers = true) {
            it.fetchedHoursAgo(Store.AH, hoursAgo = 3)
            it.fetchedHoursAgo(Store.JUMBO, hoursAgo = 72, error = "Jumbo returned no offers; kept the stored ones")
        }
        val response = http.get("/health/data")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.body<DataHealth>()
        assertEquals("stale", body.status)
        assertEquals(1, body.problems.size)
        assertTrue("Jumbo" in body.problems.single() && "Jumbo returned no offers" in body.problems.single(), body.problems.single())
    }

    @Test
    fun dataIsStaleWhenAStoreNeverSucceededAndKeepsFailing() = testApplication {
        val http = startFetching(fetchOffers = true) {
            it.planQueries.ensureSource(Store.LIDL.name)
            it.planQueries.recordFailure("boom", Store.LIDL.name)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, http.get("/health/data").status)
    }

    @Test
    fun dataShowsOnlyTheFirstLineOfAStoresErrorCutShort() = testApplication {
        val http = startFetching(fetchOffers = true) {
            it.fetchedHoursAgo(Store.AH, hoursAgo = 72, error = "Unexpected token " + "x".repeat(500) + "\nat SELECT secret FROM offer")
        }
        val text = http.get("/health/data").body<DataHealth>().problems.single()
        assertFalse("SELECT" in text, text)
        assertTrue(text.length < 400, text)
    }

    @Test
    fun dataIsStaleWhenTheLidlGuardWarns() = testApplication {
        val http = startFetching(fetchOffers = true) {
            it.fetchedHoursAgo(Store.LIDL, hoursAgo = 3)
            it.planQueries.insertOffer(Store.LIDL.name, "Old", null, 1.0, null, null, null, "2020-01-01", "2020-01-07", null, null)
        }
        val response = http.get("/health/data")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue("Lidl offers have ended" in response.body<DataHealth>().problems.single())
    }

    @Test
    fun dataIsFreshWhenOfferFetchingIsOff() = testApplication {
        val http = startFetching(fetchOffers = false) { it.fetchedHoursAgo(Store.AH, hoursAgo = 72) }
        assertEquals(HttpStatusCode.OK, http.get("/health/data").status)
    }

    @Test
    fun pricesLookedUpThreeWeeksAgoAreAProblem() {
        val db = openDatabase("jdbc:sqlite::memory:").second
        val offers = OfferRepository(db, emptyList())
        val lookup = object : PriceSource {
            override val store = Store.AH
            override suspend fun search(term: String) = emptyList<StoreProduct>()
        }
        val prices = PriceRepository(db, lookup, FoodRepository(db), offers)
        val now = Instant.now()
        assertEquals(null, prices.problem(now), "nothing looked up yet")
        db.foodQueries.upsertMeta("pricesFetchedAt", now.minus(Duration.ofDays(13)).toString())
        assertEquals(null, prices.problem(now))
        db.foodQueries.upsertMeta("pricesFetchedAt", now.minus(Duration.ofDays(21)).toString())
        assertTrue(prices.problem(now)!!.contains("regular prices"))
        assertEquals(null, PriceRepository(db, null, FoodRepository(db), offers).problem(now), "no lookup configured")
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
        assertEquals(null, http.get("/api/sync/cooking").body<CookingSyncStatus>().syncedAt)

        val sync = http.post("/api/sync/cooking").body<SyncResult>()
        assertEquals(SyncResult(added = 2, updated = 0, unchanged = 0, removed = 0, problems = emptyMap()), sync)
        val status = http.get("/api/sync/cooking").body<CookingSyncStatus>()
        assertTrue(status.syncedAt != null, "the sync is recorded")
        assertEquals(null, status.error)
        assertEquals(null, status.everyHours, "a local checkout is not synced on a schedule")

        // In the bank, until the account adds them.
        assertEquals(emptyList(), http.get("/api/recipes").body<List<RecipeSummary>>())
        val list = http.get("/api/recipes/bank").body<List<RecipeSummary>>()
        assertEquals(listOf("Gyudon", "Salsa"), list.map { it.title })
        assertTrue(list.none { it.added || it.inRotation })
        val (gyudonSummary, salsa) = list
        assertTrue(gyudonSummary.plannable)
        assertEquals(RecipeKind.MEAL, gyudonSummary.kind)
        assertEquals(listOf("Beef", "Japanese"), gyudonSummary.tags)
        assertFalse(salsa.plannable, "no ingredients block")
        assertEquals(RecipeKind.COMPONENT, salsa.kind)

        val detail = http.get("/api/recipes/${gyudonSummary.id}").body<RecipeDetail>()
        assertEquals(listOf("beef", "large"), detail.ingredients.map { it.line.item })
        assertEquals(400.0, detail.ingredients[0].line.baseAmount)
        assertTrue(detail.instructions.startsWith("Steps:"))
    }

    @Test
    fun resyncUpdatesChangedFilesAndRemovesMissingOnes() = testApplication {
        val repo = cookingRepo("rice-bowls/gyudon.md" to gyudon, "sauces/salsa.md" to "Blend it.")
        val http = start(repo)
        http.post("/api/sync/cooking")
        val idBefore = http.get("/api/recipes/bank").body<List<RecipeSummary>>().first { it.title == "Gyudon" }.id

        assertEquals(2, http.post("/api/sync/cooking").body<SyncResult>().unchanged)

        File(repo, "content/rice-bowls/gyudon.md").writeText(gyudon.replace("400 | g | beef", "500 | g | beef"))
        File(repo, "content/sauces/salsa.md").delete()
        val sync = http.post("/api/sync/cooking").body<SyncResult>()
        assertEquals(SyncResult(added = 0, updated = 1, unchanged = 0, removed = 1, problems = emptyMap()), sync)

        // A bank recipe whose file is gone is gone from the bank.
        val list = http.get("/api/recipes/bank").body<List<RecipeSummary>>()
        assertEquals(listOf("Gyudon"), list.map { it.title })
        assertEquals(idBefore, list.single().id, "a changed file keeps its recipe id")
        val detail = http.get("/api/recipes/$idBefore").body<RecipeDetail>()
        assertEquals(listOf(500.0, 1.0), detail.ingredients.map { it.line.amount })
    }

    @Test
    fun syncWithoutCheckoutIsUnavailableAndUnknownRecipeIs404() = testApplication {
        val http = start(cookingRepo = null)
        assertEquals(HttpStatusCode.ServiceUnavailable, http.post("/api/sync/cooking").status)
        assertEquals(HttpStatusCode.NotFound, http.get("/api/recipes/999").status)
        assertEquals(HttpStatusCode.BadRequest, http.get("/api/recipes/abc").status)
    }

    @Test
    fun badInputIsAnswered400WithItsMessageAndLoggedAsAWarning() = testApplication {
        val http = start(cookingRepo = null)
        val log = LoggerFactory.getLogger("io.ktor.test") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        log.addAppender(appender)
        try {
            val answer = http.get("/api/recipes/abc")
            assertEquals(HttpStatusCode.BadRequest, answer.status)
            assertEquals("""{"error":"Recipe id must be a number"}""", answer.bodyAsText())
            val warning = appender.list.single { it.level == Level.WARN }.formattedMessage
            assertTrue("/api/recipes/abc" in warning && "Recipe id must be a number" in warning, warning)
        } finally {
            log.detachAppender(appender)
        }
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
