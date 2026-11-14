package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.AuthConfig
import dev.tobins.mealplanner.api.CookingRepoSetting
import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.Me
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.RatingInput
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Sex
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
import kotlin.test.Test
import kotlin.test.assertEquals
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

        // Plans are per account too (without NEVO data here, Alice's plan stays empty, but it is hers).
        assertEquals(HttpStatusCode.OK, alice.post("/api/plans/2026-11-09/generate").status)
        bob.put("/api/profile") { setBody(Profile(weightKg = 80.0, heightCm = 180.0, ageYears = 30, sex = Sex.MALE, activity = ActivityLevel.LIGHT)) }
        assertTrue(bob.get("/api/plans/2026-11-09").body<WeekPlan>().days.all { it.meals.isEmpty() })
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
