package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.AuthConfig
import dev.tobins.mealplanner.api.CookingRepoSetting
import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.IngredientSection
import dev.tobins.mealplanner.api.KeepsChoice
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.Me
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.RatingInput
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeightLog
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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every account sees only its own data; offers and prices are the only shared things. */
class AccountsTest {
    private fun ApplicationTestBuilder.clientFor(token: String): HttpClient = createClient {
        install(ContentNegotiation) { json() }
        defaultRequest {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
        }
    }

    private fun ApplicationTestBuilder.start() {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
    }

    private val recipe = RecipeInput(title = "Alice's stew", servings = 2, ingredients = "400 | g | beef\n1 | onion | large", instructions = "Stew.")

    @Test
    fun accountsCannotSeeOrChangeEachOthersData() = testApplication {
        start()
        val alice = clientFor("alice")
        val bob = clientFor("bob")

        // Alice fills her account.
        val stew = alice.post("/api/recipes") { setBody(recipe) }.body<RecipeDetail>().summary
        val onion = alice.post("/api/ingredients") { setBody(IngredientInput("Shallot")) }.body<Ingredient>()
        alice.put("/api/pantry/on-hand/${onion.id}")
        alice.put("/api/profile") { setBody(Profile(weightKg = 70.0, heightCm = 170.0, ageYears = 30, sex = Sex.FEMALE, activity = ActivityLevel.LIGHT)) }
        alice.put("/api/weights/2026-11-10") { setBody(70.0) }
        val day = alice.post("/api/log") { setBody(LogEntry(0, "2026-11-10", "Apple", 80.0, 0.5)) }.body<DayView>()
        val logId = day.extras.single().id

        // Bob sees none of it.
        assertEquals(emptyList(), bob.get("/api/recipes").body<List<RecipeSummary>>())
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/recipes/${stew.id}").status)
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/recipes/${stew.id}/rating") { setBody(RatingInput(1)) }.status)
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/recipes/${stew.id}") { setBody(recipe.copy(title = "Bob's now")) }.status)
        assertEquals(HttpStatusCode.NotFound, bob.delete("/api/recipes/${stew.id}").status)
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/ingredients/${onion.id}") { setBody(IngredientInput("Mine")) }.status)
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/pantry/staples/${onion.id}").status)
        assertTrue(bob.get("/api/ingredients").body<List<Ingredient>>().none { it.name == "Shallot" })
        assertEquals(emptyList(), bob.get("/api/pantry").body<Pantry>().onHand)
        assertNull(bob.get("/api/profile").body<ProfileState>().profile)
        assertEquals(emptyList(), bob.get("/api/weights").body<WeightLog>().entries)
        assertEquals(emptyList(), bob.get("/api/days/2026-11-10").body<DayView>().extras)
        bob.delete("/api/log/$logId")
        assertEquals(HttpStatusCode.Conflict, bob.post("/api/plans/2026-11-09/generate").status, "no profile of his own yet")

        // ...and Alice's data is untouched.
        assertEquals(listOf("Alice's stew"), alice.get("/api/recipes").body<List<RecipeSummary>>().map { it.title })
        assertNull(alice.get("/api/recipes/${stew.id}").body<RecipeDetail>().summary.rating)
        assertEquals(1, alice.get("/api/days/2026-11-10").body<DayView>().extras.size)
        assertEquals(listOf("Shallot"), alice.get("/api/pantry").body<Pantry>().onHand.map { it.name })

        // Shop sections set by hand are per account: Bob can neither see nor change Alice's, and
        // his ingredient of the same name keeps its automatic section.
        val placed = alice.put("/api/ingredients/${onion.id}/section") { setBody(SectionChoice("FRUIT_VEG")) }.body<IngredientSection>()
        assertTrue(placed.byHand)
        assertTrue(bob.get("/api/ingredients/sections").body<List<IngredientSection>>().none { it.ingredientId == onion.id })
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/ingredients/${onion.id}/section") { setBody(SectionChoice("FROZEN")) }.status)
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/ingredients/${onion.id}/section") { setBody(SectionChoice(null)) }.status)
        val bobsShallot = bob.post("/api/ingredients") { setBody(IngredientInput("Shallot")) }.body<Ingredient>()
        val bobsSection = bob.get("/api/ingredients/sections").body<List<IngredientSection>>().single { it.ingredientId == bobsShallot.id }
        assertEquals("OTHER", bobsSection.section)
        assertFalse(bobsSection.byHand)
        val alicesSection = alice.get("/api/ingredients/sections").body<List<IngredientSection>>().single { it.ingredientId == onion.id }
        assertEquals("FRUIT_VEG", alicesSection.section)
        assertEquals(HttpStatusCode.BadRequest, alice.put("/api/ingredients/${onion.id}/section") { setBody(SectionChoice("AISLE_42")) }.status)

        // Plans are per account too (without NEVO data here, Alice's plan stays empty, but it is hers).
        assertEquals(HttpStatusCode.OK, alice.post("/api/plans/2026-11-09/generate").status)
        bob.put("/api/profile") { setBody(Profile(weightKg = 80.0, heightCm = 180.0, ageYears = 30, sex = Sex.MALE, activity = ActivityLevel.LIGHT)) }
        assertTrue(bob.get("/api/plans/2026-11-09").body<WeekPlan>().days.all { it.meals.isEmpty() })
    }

    @Test
    fun eachAccountChoosesItsOwnStores() = testApplication {
        start()
        val alice = clientFor("alice")
        val bob = clientFor("bob")

        assertEquals(ShopsSetting(listOf(Store.AH)), alice.put("/api/shops") { setBody(ShopsSetting(listOf(Store.AH))) }.body<ShopsSetting>())
        // Bob neither sees Alice's choice nor changes it with his own.
        assertNull(bob.get("/api/shops").body<ShopsSetting>().stores, "never chosen: every store")
        bob.put("/api/shops") { setBody(ShopsSetting(listOf(Store.LIDL, Store.JUMBO))) }
        assertEquals(listOf(Store.AH), alice.get("/api/shops").body<ShopsSetting>().stores)
        bob.put("/api/shops") { setBody(ShopsSetting(null)) }
        assertEquals(listOf(Store.AH), alice.get("/api/shops").body<ShopsSetting>().stores)
        assertNull(bob.get("/api/shops").body<ShopsSetting>().stores)
    }

    @Test
    fun buyingAheadAndStockingUpStayWithTheirAccount() = testApplication {
        start()
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        val week = "2026-11-16"
        val chicken = alice.post("/api/ingredients") { setBody(IngredientInput("Chicken thigh")) }.body<Ingredient>()

        // Bob can't tick Alice's ingredient onto anyone's pantry, nor say whether it keeps.
        for (key in listOf("ahead:i${chicken.id}", "stock:i${chicken.id}")) {
            assertEquals(HttpStatusCode.NotFound, bob.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, true)) }.status)
            assertEquals(HttpStatusCode.NotFound, bob.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, false)) }.status)
        }
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/ingredients/${chicken.id}/keeps") { setBody(KeepsChoice(false)) }.status)
        assertEquals(emptyList(), alice.get("/api/pantry").body<Pantry>().onHand)
        assertFalse(alice.get("/api/ingredients/sections").body<List<IngredientSection>>().single { it.ingredientId == chicken.id }.keepsByHand)

        // Alice's tick puts it in her pantry only; her correction is hers.
        alice.put("/api/plans/$week/shopping") { setBody(ShoppingCheck("ahead:i${chicken.id}", true)) }
        assertEquals(listOf("Chicken thigh"), alice.get("/api/pantry").body<Pantry>().onHand.map { it.name })
        assertEquals(emptyList(), bob.get("/api/pantry").body<Pantry>().onHand)
        alice.put("/api/ingredients/${chicken.id}/keeps") { setBody(KeepsChoice(true)) }
        assertTrue(bob.get("/api/ingredients/sections").body<List<IngredientSection>>().none { it.ingredientId == chicken.id })

        // Bob can't make her tick one set by hand: un-ticking still takes it back.
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/pantry/on-hand/${chicken.id}").status)
        alice.put("/api/plans/$week/shopping") { setBody(ShoppingCheck("ahead:i${chicken.id}", false)) }
        assertEquals(emptyList(), alice.get("/api/pantry").body<Pantry>().onHand)

        // The switch and a dismissed group are per account.
        alice.put("/api/shopping-advice") { setBody(ShoppingAdvice(buyAhead = false, stockUp = false)) }
        assertEquals(ShoppingAdvice(), bob.get("/api/shopping-advice").body<ShoppingAdvice>())
        bob.put("/api/plans/$week/shopping/dismissed/buy-ahead")
        assertFalse(alice.get("/api/plans/$week/shopping").body<ShoppingList>().buyAheadDismissed)
        assertTrue(bob.get("/api/plans/$week/shopping").body<ShoppingList>().buyAheadDismissed)
        assertEquals(ShoppingAdvice(buyAhead = false, stockUp = false), alice.get("/api/shopping-advice").body<ShoppingAdvice>())
    }

    @Test
    fun shoppingListTicksStayWithTheirAccount() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        val db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        val week = "2026-11-16"
        // Without NEVO data the planner plans nothing, so each account's stew is put in its plan directly.
        for ((client, subject) in listOf(alice to "sub-alice", bob to "sub-bob")) {
            val stew = client.post("/api/recipes") { setBody(recipe) }.body<RecipeDetail>().summary
            val userId = db.userQueries.selectBySubject(subject).executeAsOne().id
            db.planQueries.upsertMeal(userId, week, 0, "DINNER", stew.id, stew.title, 2.0, 1000.0, 50.0, 0, 0, "PLANNED")
        }
        suspend fun HttpClient.list() = get("/api/plans/$week/shopping").body<ShoppingList>()
        val alicesItems = alice.list().items
        assertTrue(alicesItems.isNotEmpty())
        // Lines with no ingredient linked have the same keys in both lists ("t:beef").
        assertEquals(alicesItems.map { it.key }, bob.list().items.map { it.key })

        // Alice's ticks are hers: Bob's list of the same stew stays as it was.
        for (item in alicesItems) alice.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(item.key, true)) }
        assertTrue(alice.list().items.all { it.checked })
        assertTrue(bob.list().items.isNotEmpty())
        assertTrue(bob.list().items.none { it.checked })

        // Bob un-ticking her keys, or ticking them, changes only his own list.
        for (item in alicesItems) {
            assertEquals(HttpStatusCode.OK, bob.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(item.key, false)) }.status)
        }
        assertTrue(alice.list().items.all { it.checked })
        val first = alicesItems.first().key
        alice.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(first, false)) }
        bob.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(first, true)) }
        assertFalse(alice.list().items.single { it.key == first }.checked)
    }

    @Test
    fun eatingAMealUsesUpOnlyTheAccountsOwnStock() {
        val db = openDatabase("jdbc:sqlite::memory:").second
        val foods = FoodRepository(db)
        val users = UserRepository(db)
        val alice = IngredientRepository(db, foods, users.resolve(Identity("sub-alice", "alice")).id)
        val bob = IngredientRepository(db, foods, users.resolve(Identity("sub-bob", "bob")).id)
        val week = java.time.LocalDate.parse("2026-11-23")
        val aliceId = users.resolve(Identity("sub-alice", "alice")).id
        val chicken = alice.create(IngredientInput("Chicken thigh"))
        alice.putOnHandFrom(chicken.id, week, grams = 1000.0)
        fun left() = db.pantryQueries.selectStock(aliceId, week.toString(), listOf(chicken.id)).executeAsOneOrNull()?.grams

        // Bob naming Alice's ingredient as one his meal used takes nothing from her pantry, nor does
        // his marking it on hand by hand make her tick one set by hand.
        bob.useUp(mapOf(chicken.id to 400.0), week)
        bob.useUp(mapOf(chicken.id to null), week)
        assertFailsWith<NotFoundException> { bob.setOnHand(chicken.id, true) }
        assertEquals(1000.0, left())
        alice.useUp(mapOf(chicken.id to 400.0), week)
        assertEquals(600.0, left())
        alice.useUp(mapOf(chicken.id to null), week)
        assertEquals(emptySet(), alice.onHandFor(week))
    }

    @Test
    fun accountNamesAndLoginConfig() = testApplication {
        start()
        assertEquals(Me("alice", "Alice"), clientFor("alice").get("/api/me").body<Me>())
        // Public: clients need it before they can log in.
        val config = createClient { install(ContentNegotiation) { json() } }.get("/api/auth/config").body<AuthConfig>()
        assertEquals("mealplanner", config.clientId)
        assertTrue("offline_access" in config.scopes)
        assertEquals(HttpStatusCode.Unauthorized, clientFor("nope").get("/api/me").status)

        // The Android login redirect goes on into the app with the code and state: a redirect for
        // Android browsers, a page with a link for others.
        val intent = "intent://login?code=abc&state=x%2By#Intent;scheme=dev.tobins.mealplanner;package=dev.tobins.mealplanner;end"
        val plain = createClient { followRedirects = false }
        val android = plain.get("/app/login?code=abc&state=x%2By") { headers.append("User-Agent", "Mozilla/5.0 (Linux; Android 16) Chrome/157") }
        assertEquals(HttpStatusCode.Found, android.status)
        assertEquals(intent, android.headers["Location"])
        val page = plain.get("/app/login?code=abc&state=x%2By").bodyAsText()
        assertTrue(intent.replace("&", "&amp;") in page, page)
    }

    @Test
    fun assetLinksVouchForTheApp() {
        assertEquals("[]", assetLinks(emptyList()))
        val links = assetLinks(listOf("ab:cd"))
        assertTrue(""""package_name":"dev.tobins.mealplanner"""" in links && """"AB:CD"""" in links, links)
    }

    @Test
    fun eachAccountSetsItsOwnCookingRepo() = testApplication {
        start()
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        assertNull(bob.get("/api/sync/cooking").body<CookingSyncStatus>().repoUrl)
        assertEquals(HttpStatusCode.BadRequest, bob.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting("not a url")) }.status)

        val status = bob.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting("https://example.org/bob/cooking.git")) }.body<CookingSyncStatus>()
        assertEquals("https://example.org/bob/cooking.git", status.repoUrl)
        assertEquals(CookingSync.EVERY_HOURS, status.everyHours)
        assertNull(alice.get("/api/sync/cooking").body<CookingSyncStatus>().repoUrl, "alice has none")
        assertNull(bob.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting(null)) }.body<CookingSyncStatus>().repoUrl)
    }

    @Test
    fun recipePicturesStayWithTheirAccount() = testApplication {
        val site = PictureFixtures.site(
            "content/recipes/pizza/index.md" to PictureFixtures.page("Pizza", "image: pizza.jpg"),
            "content/recipes/pizza/pizza.jpg" to PictureFixtures.jpeg(40, 30),
        )
        // The server-wide site serves the owner: Alice, who logs in first.
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = site, imageDir = createTempDirectory("images").toFile())
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        assertEquals(HttpStatusCode.OK, alice.post("/api/sync/cooking").status)
        val pizza = alice.get("/api/recipes").body<List<RecipeSummary>>().single()
        val url = pizza.imageUrl!!
        assertEquals(HttpStatusCode.OK, alice.get(url).status)

        // Bob gets nothing, by the picture's URL or by the recipe's id.
        assertEquals(HttpStatusCode.NotFound, bob.get(url).status)
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/recipes/${pizza.id}/image").status)
        // Syncing, setting and clearing a site of his own touch only his account.
        assertEquals(HttpStatusCode.ServiceUnavailable, bob.post("/api/sync/cooking").status)
        bob.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting("https://example.org/bob/cooking.git")) }
        bob.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting(null)) }
        assertEquals(url, alice.get("/api/recipes").body<List<RecipeSummary>>().single().imageUrl)
        assertEquals(HttpStatusCode.OK, alice.get(url).status)

        // Alice moving to another site of her own removes her pictures until that one is synced.
        alice.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting("https://example.org/alice/cooking.git")) }
        assertNull(alice.get("/api/recipes").body<List<RecipeSummary>>().single().imageUrl)
        assertEquals(HttpStatusCode.NotFound, alice.get(url).status)
    }

    @Test
    fun theOwnerTakesOverTheDataFromBeforeAccounts() {
        val db = openDatabase("jdbc:sqlite::memory:").second
        db.userQueries.insertUser(null, null, null, "2026-11-01T00:00:00Z") // what migration 3 leaves

        val users = UserRepository(db)
        val bob = users.resolve(Identity("sub-bob", "bob"), owner = "alice")
        val alice = users.resolve(Identity("sub-alice", "alice"), owner = "alice")
        assertEquals(1L, alice.id, "alice claims the old data")
        assertNotEquals(1L, bob.id)
        assertEquals(alice.id, users.resolve(Identity("sub-alice"), owner = "alice").id, "and keeps it")

        // Without an owner set, the first login takes it.
        val other = openDatabase("jdbc:sqlite::memory:").second
        other.userQueries.insertUser(null, null, null, "2026-11-01T00:00:00Z")
        assertEquals(1L, UserRepository(other).resolve(Identity("sub-carol", "carol")).id)
    }
}
