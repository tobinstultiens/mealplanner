package dev.tobins.mealplanner.server.prices

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.RefusingWrites
import dev.tobins.mealplanner.server.ServerConfig
import dev.tobins.mealplanner.server.TestTokens
import dev.tobins.mealplanner.server.addWholeBank
import dev.tobins.mealplanner.server.module
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.server.prices.offers.OfferSource
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.contentType
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeInput
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.assertNull

class OffersApiTest {
    private class FakeSource(override val store: Store, var offers: List<Offer>? = null) : OfferSource {
        var calls = 0

        override suspend fun fetch(): List<Offer> {
            calls++
            return offers ?: error("${store.label} is down")
        }
    }

    private fun offer(store: Store, title: String, from: String = "2026-11-09", until: String = "2026-11-15") =
        Offer(store, title, "25% korting", validFrom = from, validUntil = until)

    private fun wide(store: Store, title: String, ended: Boolean = false) = Offer(
        store, title, "25% korting",
        validFrom = LocalDate.now().minusDays(if (ended) 40 else 3).toString(),
        validUntil = LocalDate.now().plusDays(if (ended) -30 else 30).toString(),
    )

    private fun many(store: Store, prefix: String, n: Int, ended: Boolean = false) = (1..n).map { wide(store, "$prefix $it", ended) }

    /** Refreshes AH with [first], then with [second] (Jumbo gets a second offer meanwhile); returns what the API shows after. */
    private fun refreshTwice(first: List<Offer>, second: List<Offer>): OffersState {
        lateinit var state: OffersState
        testApplication {
            val ah = FakeSource(Store.AH, first)
            val jumbo = FakeSource(Store.JUMBO, listOf(wide(Store.JUMBO, "Zalm")))
            val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
            application { module(config, openDatabase(config.databaseUrl).second, listOf(ah, jumbo), verifier = TestTokens) }
            val http = createClient {
                install(ContentNegotiation) { json() }
                defaultRequest { bearerAuth("t") }
            }
            http.post("/api/offers/refresh")
            ah.offers = second
            jumbo.offers = listOf(wide(Store.JUMBO, "Zalm"), wide(Store.JUMBO, "Tonijn"))
            http.post("/api/offers/refresh")
            state = http.get("/api/offers").body<OffersState>()
        }
        return state
    }

    @Test
    fun anEmptyResultKeepsTheStoredOffersSetsTheErrorAndLeavesTheOtherStoresUpdating() {
        val state = refreshTwice(many(Store.AH, "Offer", 40), emptyList())
        val ah = state.sources.single { it.store == Store.AH }
        assertEquals(40, state.offers.count { it.store == Store.AH })
        assertEquals(40, ah.offerCount)
        assertContains(ah.error.orEmpty(), "returned no offers; kept the stored ones")
        assertEquals(2, state.offers.count { it.store == Store.JUMBO }, "the other store still updates")
        assertNull(state.sources.single { it.store == Store.JUMBO }.error)
    }

    @Test
    fun aCollapsedResultKeepsTheStoredOffersAndNamesBothNumbers() {
        val state = refreshTwice(many(Store.AH, "Offer", 40), many(Store.AH, "New", 5))
        assertEquals(many(Store.AH, "Offer", 40).map { it.title }.sorted(), state.offers.filter { it.store == Store.AH }.map { it.title }.sorted())
        val error = state.sources.single { it.store == Store.AH }.error.orEmpty()
        assertContains(error, "5 offers")
        assertContains(error, "40")
    }

    @Test
    fun aQuietWeekOfMoreThanAThirdIsStored() {
        val state = refreshTwice(many(Store.AH, "Offer", 40), many(Store.AH, "New", 14))
        assertEquals(14, state.offers.count { it.store == Store.AH }, "14 of 40 is a quiet week, not a broken page")
        assertNull(state.sources.single { it.store == Store.AH }.error)
    }

    @Test
    fun aSmallResultReplacesStoredOffersThatHaveAllEnded() {
        val state = refreshTwice(many(Store.AH, "Old", 40, ended = true), many(Store.AH, "New", 5))
        assertEquals(many(Store.AH, "New", 5).map { it.title }.sorted(), state.offers.filter { it.store == Store.AH }.map { it.title }.sorted())
        assertNull(state.sources.single { it.store == Store.AH }.error)
    }

    @Test
    fun aRefusedRefreshEndsNoPeriodInThePriceHistory() = runBlocking {
        val db = openDatabase("jdbc:sqlite::memory:").second
        val source = FakeSource(Store.AH, many(Store.AH, "Offer", 40))
        val offers = OfferRepository(db, listOf(source))
        offers.refresh(now = Instant.now())
        val before = db.priceHistoryQueries.selectOfferHistory().executeAsList()
        val recordedBefore = db.priceHistoryQueries.selectOfferRecordings("AH", "", "~").executeAsList()
        assertEquals(1, recordedBefore.size, "the first refresh is recorded")
        source.offers = many(Store.AH, "New", 5)
        offers.refresh(now = Instant.now().plusSeconds(3600))
        assertEquals(before, db.priceHistoryQueries.selectOfferHistory().executeAsList())
        // "Store not read" is no "not on offer": the refused refresh adds no recording either.
        assertEquals(recordedBefore, db.priceHistoryQueries.selectOfferRecordings("AH", "", "~").executeAsList())
    }

    @Test
    fun refreshStoresOffersPerStoreAndKeepsOldOnesWhenAStoreFails() = testApplication {
        val ah = FakeSource(Store.AH, listOf(offer(Store.AH, "Kipfilet"), offer(Store.AH, "Next week", "2026-11-16", "2026-11-22")))
        val jumbo = FakeSource(Store.JUMBO, listOf(offer(Store.JUMBO, "Zalm")))
        val lidl = FakeSource(Store.LIDL)
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        application { module(config, openDatabase(config.databaseUrl).second, listOf(ah, jumbo, lidl), verifier = TestTokens) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { bearerAuth("t") }
        }

        var state = http.get("/api/offers?date=2026-11-12").body<OffersState>()
        assertEquals(emptyList(), state.offers)
        assertEquals(listOf(0, 0, 0), state.sources.map { it.offerCount })

        http.post("/api/offers/refresh")
        state = http.get("/api/offers?date=2026-11-12").body()
        assertEquals(listOf("Kipfilet", "Zalm"), state.offers.map { it.title }, "only offers valid on the date")
        val (ahStatus, jumboStatus, lidlStatus) = state.sources
        assertEquals(2, ahStatus.offerCount)
        assertNotNull(jumboStatus.fetchedAt)
        assertNull(lidlStatus.fetchedAt)
        assertEquals("Lidl is down", lidlStatus.error)

        // A later failure keeps the last good offers.
        jumbo.offers = null
        state = http.post("/api/offers/refresh").body()
        assertEquals(1, state.sources[1].offerCount)
        assertEquals("Jumbo is down", state.sources[1].error)
        assertEquals(listOf("Next week"), http.get("/api/offers?date=2026-11-20").body<OffersState>().offers.map { it.title })
        assertEquals(HttpStatusCode.BadRequest, http.get("/api/offers?date=soon").status)
    }

    /**
     * Issue 285: a store whose save gave up and whose failure could not be recorded either (the database stayed
     * busy) is logged, and the stores after it are still refreshed. It shows as stale, as its last success ages.
     */
    @Test
    fun aFailureThatCannotBeRecordedDoesNotSkipTheStoresAfterIt() = runBlocking {
        val opened = openDatabase("jdbc:sqlite::memory:")
        val writes = RefusingWrites(opened.first)
        val db = writes.database
        val lastNight = Instant.parse("2026-11-16T10:00:00Z")
        db.planQueries.ensureSource(Store.AH.name)
        db.planQueries.recordSuccess(lastNight.toString(), Store.AH.name)
        // AH finds the database busy from its fetch until Jumbo's begins.
        val ah = object : OfferSource {
            override val store = Store.AH
            override suspend fun fetch(): List<Offer> {
                writes.refuse = { "offer" in it.lowercase() }
                return listOf(offer(Store.AH, "Kipfilet"))
            }
        }
        val jumbo = object : OfferSource {
            override val store = Store.JUMBO
            override suspend fun fetch(): List<Offer> {
                writes.refuse = { false }
                return listOf(offer(Store.JUMBO, "Rundergehakt", "2026-11-16", "2026-11-22"))
            }
        }
        val repository = OfferRepository(db, listOf(ah, jumbo))
        val now = Instant.parse("2026-11-19T10:00:00Z")

        val state = repository.refresh(now = now)

        assertEquals(listOf("Rundergehakt"), state.offers.map { it.title }, "Jumbo was refreshed")
        assertEquals(now.toString(), state.sources.single { it.store == Store.JUMBO }.fetchedAt)
        assertEquals(0, state.sources.single { it.store == Store.AH }.offerCount, "AH kept what it had")
        val problems = repository.problems(now)
        assertEquals(1, problems.size, problems.toString())
        assertContains(problems.single(), Store.AH.label)
        assertContains(problems.single(), lastNight.toString())
        opened.first.close()
    }

    @Test
    fun warnsAboutLidlOffersThatStoppedBeingRenewed() = runBlocking {
        val ah = FakeSource(Store.AH, listOf(offer(Store.AH, "Kipfilet", "2026-11-16", "2026-11-22")))
        val lidl = FakeSource(Store.LIDL, listOf(offer(Store.LIDL, "Rundergehakt", "2026-11-16", "2026-11-22")))
        val repository = OfferRepository(openDatabase("jdbc:sqlite::memory:").second, listOf(ah, lidl))
        repository.refresh(now = Instant.parse("2026-11-18T10:00:00Z"))

        assertEquals(emptyList(), repository.warnings(LocalDate.parse("2026-11-18")), "Wednesday: current")
        assertEquals(1, repository.warnings(LocalDate.parse("2026-11-20")).size, "Friday, nothing runs into next week")
        assertEquals(1, repository.warnings(LocalDate.parse("2026-11-23")).size, "everything has ended")
        assertEquals(1, repository.state(LocalDate.parse("2026-11-23")).sources.single { it.store == Store.LIDL }.offerCount, "the offers are kept")
    }

    @Test
    fun theLidlWarningReachesTheAppsAndOlderClientsStillDecodeTheResponse() = testApplication {
        val lidl = FakeSource(Store.LIDL, listOf(offer(Store.LIDL, "Rundergehakt", "2026-11-16", "2026-11-22")))
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        application { module(config, openDatabase(config.databaseUrl).second, listOf(lidl), verifier = TestTokens) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { bearerAuth("t") }
        }
        http.post("/api/offers/refresh")

        val fresh = http.get("/api/offers?date=2026-11-18").bodyAsText()
        val stale = http.get("/api/offers?date=2026-11-23").bodyAsText()
        assertNull(Json.decodeFromString<OffersState>(fresh).sources.single { it.store == Store.LIDL }.warning)
        assertContains(Json.decodeFromString<OffersState>(stale).sources.single { it.store == Store.LIDL }.warning!!, "have ended")

        // The lenient client (ApiClient) skips fields it does not know: a type without `warning` reads both.
        val lenient = Json { ignoreUnknownKeys = true }
        assertEquals(3, lenient.decodeFromString<OldOffersState>(stale).sources.size)
        assertEquals(3, lenient.decodeFromString<OldOffersState>(fresh).sources.size)
    }

    /** The offers state as the apps before the warning knew it. */
    @Serializable
    private data class OldOffersState(val offers: List<Offer>, val sources: List<OldSource>)

    @Serializable
    private data class OldSource(val store: Store, val fetchedAt: String?, val offerCount: Int, val error: String?)

    @Test
    fun scheduledRefreshSkipsStoresFetchedRecently() = runBlocking {
        val ah = FakeSource(Store.AH, listOf(offer(Store.AH, "Kipfilet")))
        val lidl = FakeSource(Store.LIDL)
        val repository = OfferRepository(openDatabase("jdbc:sqlite::memory:").second, listOf(ah, lidl))
        val now = Instant.parse("2026-11-12T10:00:00Z")

        repository.refresh(olderThan = Duration.ofHours(24), now = now)
        repository.refresh(olderThan = Duration.ofHours(24), now = now.plusSeconds(3600))
        assertEquals(1, ah.calls, "fetched an hour ago, so not again")
        assertEquals(2, lidl.calls, "never fetched successfully, so tried every time")

        repository.refresh(olderThan = Duration.ofHours(24), now = now.plus(Duration.ofHours(25)))
        assertEquals(2, ah.calls)
    }

    /** The owner's cooking site holds one chili of minced beef, and NEVO knows minced beef. */
    private fun chiliConfig(): ServerConfig {
        val nevo = listOf(
            "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
            "NEVO-Online 2025 9.0|Vlees|Meat|1405|Rundergehakt rauw|Minced beef raw||per 100g|200|20|13|5|0|0|0|60",
        ).joinToString("\r\n")
        val repo = kotlin.io.path.createTempDirectory("cooking").toFile()
        java.io.File(repo, "content/recipes/chili.md").apply { parentFile.mkdirs() }
            .writeText("---\ntitle: Chili\n---\n```ingredients\nservings: 4\n1200 | g | ground beef\n```")
        return ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = repo,
            nevoFile = kotlin.io.path.createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
    }

    private fun ApplicationTestBuilder.clientFor(token: String) = createClient {
        install(ContentNegotiation) { json() }
        defaultRequest {
            bearerAuth(token)
            contentType(io.ktor.http.ContentType.Application.Json)
        }
    }

    @Test
    fun shoppingListShowsOffersForItsIngredients() = testApplication {
        val config = chiliConfig()
        val ah = FakeSource(Store.AH, listOf(
            Offer(Store.AH, "AH Gemengd gehakt 500 gram", "VOOR 3.49", price = 3.49, category = "Vlees", validFrom = "2026-11-09", validUntil = "2026-11-15"),
            Offer(Store.AH, "Kwekkeboom gehaktballen", "2 voor 5.99", category = "Diepvries", validFrom = "2026-11-09", validUntil = "2026-11-15"),
        ))
        val lidl = FakeSource(Store.LIDL, listOf(Offer(Store.LIDL, "Rundergehakt", null, price = 4.29, validFrom = "2026-11-17", validUntil = "2026-11-22")))
        application { module(config, openDatabase(config.databaseUrl).second, listOf(ah, lidl), verifier = TestTokens) }
        val http = clientFor("t")
        http.post("/api/sync/cooking")
        http.addWholeBank()
        http.post("/api/offers/refresh")
        http.put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        http.post("/api/plans/2026-11-09/generate")

        val mince = http.get("/api/plans/2026-11-09/shopping").body<ShoppingList>().items.single { it.name == "Minced beef" }
        assertEquals(listOf("AH Gemengd gehakt 500 gram"), mince.offers.map { it.title }, "frozen meatballs are not mince; Lidl's offer is next week")

        http.post("/api/plans/2026-11-16/generate")
        val nextWeek = http.get("/api/plans/2026-11-16/shopping").body<ShoppingList>().items.single { it.name == "Minced beef" }
        assertEquals(listOf(Store.LIDL), nextWeek.offers.map { it.store }, "the AH offer ends on the 15th")
    }

    @Test
    fun shoppingListItemsShowTheBestOffersPictureElseTheProducts() = testApplication {
        val config = chiliConfig()
        val db = openDatabase(config.databaseUrl).second
        val ahPicture = "https://static.ah.nl/dam/product/AHI_4354523130303533393736?revLabel=1&rendition=200x200_JPG&fileType=binary"
        val jumboPicture = "https://www.jumbo.com/INTERSHOP/static/WFS/Jumbo-Grocery-Site/-/Jumbo-Grocery/nl_NL/Images_Aanbiedingen/2026/Weekaanbiedingen/Week%2046/Weekaanbiedingen-Week-46_56.png"
        val productPicture = "https://static.ah.nl/dam/product/AHI_4354523130303237393132?revLabel=1&rendition=200x200_JPG&fileType=binary"
        val ah = FakeSource(Store.AH, listOf(
            Offer(Store.AH, "AH Gemengd gehakt 500 gram", "VOOR 3.49", price = 3.49, validFrom = "2026-11-09", validUntil = "2026-11-15", imageUrl = ahPicture),
        ))
        // No price stated, but 1+1 gratis saves half while the AH offer is dearer than the regular price: this one is the best.
        val jumbo = FakeSource(Store.JUMBO, listOf(
            Offer(Store.JUMBO, "Jumbo Rundergehakt", "1+1 gratis", validFrom = "2026-11-09", validUntil = "2026-11-15", imageUrl = jumboPicture),
        ))
        // A picture somewhere else than Lidl's image host is not stored.
        val lidl = FakeSource(Store.LIDL, listOf(
            Offer(Store.LIDL, "Rundergehakt", null, price = 4.29, validFrom = "2026-11-17", validUntil = "2026-11-22", imageUrl = "https://tracker.example/rundergehakt.png"),
        ))
        application { module(config, db, listOf(ah, jumbo, lidl), verifier = TestTokens) }
        val owner = clientFor("t")
        val bob = clientFor("bob")
        owner.post("/api/sync/cooking")
        owner.addWholeBank()
        owner.post("/api/offers/refresh")
        // The regular price's product, as the weekly lookup stores it for every account.
        db.priceQueries.upsertPrice("Minced beef", "AH", "AH Rundergehakt", "300 g", 3.0, 10.0, "2026-11-15T00:00:00Z", "Vlees", null, productPicture)
        val profile = Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)
        owner.put("/api/profile") { setBody(profile) }
        bob.put("/api/profile") { setBody(profile) }
        // Bob's chili uses the minced beef of his own catalogue.
        bob.post("/api/recipes") { setBody(RecipeInput("Bob's chili", 4, "1200 | g | minced beef", mealSlots = listOf(MealSlot.LUNCH, MealSlot.DINNER))) }
        for (week in listOf("2026-11-09", "2026-11-16")) {
            owner.post("/api/plans/$week/generate")
            bob.post("/api/plans/$week/generate")
        }
        suspend fun HttpClient.mince(week: String): ShoppingItem {
            val items = get("/api/plans/$week/shopping").body<ShoppingList>().items
            return assertNotNull(items.singleOrNull { it.name == "Minced beef" }, "the list holds ${items.map { it.name }}")
        }

        val thisWeek = owner.mince("2026-11-09")
        assertEquals(listOf(Store.AH, Store.JUMBO), thisWeek.offers.map { it.store })
        assertEquals(jumboPicture, thisWeek.imageUrl, "the offer that saves most is the one shown")
        val nextWeek = owner.mince("2026-11-16")
        assertNull(nextWeek.offers.single().imageUrl)
        assertEquals(productPicture, nextWeek.imageUrl, "the offer has no picture, so the product's")

        // The owner prices minced beef by hand: the product is no longer the one that counts.
        val beef = owner.get("/api/ingredients").body<List<Ingredient>>().single { it.name == "Minced beef" }
        owner.put("/api/ingredients/${beef.id}") {
            setBody(IngredientInput(beef.name, beef.food?.nevoCode, beef.gramsPerPiece, beef.gramsPerMl, beef.category, pricePerKg = 9.0, beef.isStaple, beef.negligible))
        }
        assertNull(owner.mince("2026-11-16").imageUrl)
        assertEquals(jumboPicture, owner.mince("2026-11-09").imageUrl, "an offer's picture still shows")
        assertEquals(productPicture, bob.mince("2026-11-16").imageUrl, "another account's hand price changes nothing for Bob")
    }
}
