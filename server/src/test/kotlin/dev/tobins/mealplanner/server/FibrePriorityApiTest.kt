package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlanPriority
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.RatingInput
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.released.api.ActivityLevel as ReleasedActivityLevel
import dev.tobins.mealplanner.released.api.Profile as ReleasedProfile
import dev.tobins.mealplanner.released.api.Sex as ReleasedSex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
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
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fibre next to protein and the profile's priority (issue 223): stored, validated, kept by an app that
 * does not know them, used by the planner, and the fibre of meals, days and the day view.
 */
class FibrePriorityApiTest {
    /** 2000 kcal, 150 g protein and 30 g fibre: a lunch's share is 600 kcal, 45 g protein and 9 g fibre. */
    private val profile = Profile(
        80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT,
        kcalOverride = 2000, proteinOverrideG = 150, fibreOverrideG = 30,
    )

    /** Beef with protein and no fibre, bran with fibre and no protein; both 150 kcal per 100 g. */
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|10|Rundvlees rauw|Beef raw||per 100g|150|20|4|2|0|0|0|50",
        "NEVO-Online 2025 9.0|Graan|Cereals|11|Zemelen rauw|Bran raw||per 100g|150|0|0|0|20|0|10|0",
    ).joinToString("\r\n")

    /** The server, with a cooking site of two four-serving meals of 600 kcal a serving when [withRecipes]. */
    private fun ApplicationTestBuilder.start(withRecipes: Boolean = false): HttpClient {
        val repo = createTempDirectory("cooking").toFile()
        fun write(path: String, text: String) = File(repo, "content/$path").apply { parentFile.mkdirs() }.writeText(text)
        // 80 g protein a serving: more than a lunch's share. 40 g fibre: more than a lunch's share.
        write("recipes/beef.md", "---\ntitle: Beef\n---\n```ingredients\nservings: 4\n1600 | g | beef raw\n```")
        write("recipes/bran.md", "---\ntitle: Bran\n---\n```ingredients\nservings: 4\n1600 | g | bran raw\n```")
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = repo.takeIf { withRecipes },
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        return createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
            }
        }
    }

    private suspend fun HttpClient.state(): ProfileState = get("/api/profile").body()

    @Test
    fun aPriorityAndAFibreTargetAreStoredAndTheTargetsFollow() = testApplication {
        val http = start()
        val plain = Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT)
        http.put("/api/profile") { setBody(plain) }
        // 1780 kcal resting * 1.375 = 2448 kcal to keep the weight, at 14 g per 1000 kcal.
        assertEquals(PlanPriority.BALANCED, http.state().profile!!.priority)
        assertEquals(34, http.state().suggested!!.fibreG)
        assertEquals(34, http.state().targets!!.fibreG)

        http.put("/api/profile") { setBody(plain.copy(priority = PlanPriority.PRICE, fibreOverrideG = 40)) }
        val reloaded = http.state()
        assertEquals(PlanPriority.PRICE, reloaded.profile!!.priority)
        assertEquals(40, reloaded.profile!!.fibreOverrideG)
        assertEquals(34, reloaded.suggested!!.fibreG, "the suggestion stays as it was")
        assertEquals(40, reloaded.targets!!.fibreG)
    }

    @Test
    fun theInstalledAppsProfileSaveKeepsThePriorityAndTheFibreTarget() = testApplication {
        val http = start()
        http.put("/api/profile") { setBody(profile.copy(priority = PlanPriority.FIBRE)) }

        // The released app's own Profile, with its JSON settings: it has neither to send.
        val released = ReleasedProfile(85.0, 180.0, 30, ReleasedSex.MALE, ReleasedActivityLevel.LIGHT, kcalOverride = 2000, proteinOverrideG = 150)
        val body = releasedJson.encodeToString(released)
        assertTrue("priority" !in body && "fibre" !in body, body)
        assertEquals(HttpStatusCode.OK, http.put("/api/profile") { setBody(body) }.status)

        assertEquals(profile.copy(weightKg = 85.0, priority = PlanPriority.FIBRE), http.state().profile, "the weight changed, the rest stayed")
        // What the installed app reads back is a profile it understands.
        releasedJson.decodeFromString<dev.tobins.mealplanner.released.api.ProfileState>(http.get("/api/profile").bodyAsText())
    }

    @Test
    fun anOlderAppsFirstProfileIsBalancedWithTheSuggestedFibre() = testApplication {
        val http = start()
        http.put("/api/profile") { setBody("""{"weightKg":80.0,"heightCm":180.0,"ageYears":30,"sex":"MALE","activity":"LIGHT"}""") }
        assertEquals(PlanPriority.BALANCED to null, http.state().profile!!.let { it.priority to it.fibreOverrideG })
    }

    @Test
    fun anAppThatKnowsTheFibreTargetTakesItAwayWithNull() = testApplication {
        val http = start()
        http.put("/api/profile") { setBody(profile) }
        // This app's JSON writes a null field out, so the server can tell it from one an older app leaves out.
        http.put("/api/profile") { setBody(profile.copy(fibreOverrideG = null)) }
        assertEquals(null, http.state().profile!!.fibreOverrideG)
        assertEquals(http.state().suggested!!.fibreG, http.state().targets!!.fibreG)
    }

    @Test
    fun aFibreTargetOutsideTenToAHundredIsRefused() = testApplication {
        val http = start()
        http.put("/api/profile") { setBody(profile) }
        for (grams in listOf(9, 101)) {
            val response = http.put("/api/profile") { setBody(profile.copy(fibreOverrideG = grams)) }
            assertEquals(HttpStatusCode.BadRequest, response.status, "$grams g")
            assertTrue("fibreOverrideG" in response.bodyAsText(), response.bodyAsText())
        }
        assertEquals(30, http.state().targets!!.fibreG, "a refused save changes nothing")
    }

    /** Syncs the two recipes, adds them, rates bran five stars and saves [profile] with [priority]. */
    private suspend fun HttpClient.ready(priority: PlanPriority): HttpClient = apply {
        post("/api/sync/cooking")
        addWholeBank()
        val bran = get("/api/recipes").body<List<RecipeSummary>>().single { it.title == "Bran" }
        put("/api/recipes/${bran.id}/rating") { setBody(RatingInput(5)) }
        put("/api/profile") { setBody(profile.copy(priority = priority)) }
    }

    private val week = "2026-11-16"

    @Test
    fun theProfilesPriorityChangesWhatThePlannerPicks() = testApplication {
        // The first meal planned is the first lunch. Beef's protein (+3.0) beats bran's fibre (+1.5) and five
        // stars (+0.8); under Fibre, bran's fibre counts twice (+3.0).
        suspend fun HttpClient.firstLunch() = post("/api/plans/$week/generate").body<WeekPlan>().days[0].meals.single { it.slot == MealSlot.LUNCH }.title
        val http = start(withRecipes = true).ready(PlanPriority.BALANCED)
        assertEquals("Beef", http.firstLunch())
        http.put("/api/profile") { setBody(profile.copy(priority = PlanPriority.FIBRE)) }
        assertEquals("Bran", http.firstLunch())
        http.put("/api/profile") { setBody(profile.copy(priority = PlanPriority.PROTEIN)) }
        assertEquals("Beef", http.firstLunch())
    }

    @Test
    fun mealsDaysAndTheDayCountFibre() = testApplication {
        val http = start(withRecipes = true).ready(PlanPriority.FIBRE)
        val plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
        assertEquals(30, plan.targets.fibreG)
        for (meal in plan.days.flatMap { it.meals }) {
            assertEquals(if (meal.title == "Bran") 40.0 * meal.portions else 0.0, meal.fibreG, "${meal.title} of ${meal.portions}")
        }
        for (day in plan.days) assertEquals(day.meals.sumOf { it.fibreG!! }, day.fibreG)

        val first = plan.days[0]
        val lunch = first.meals.single { it.slot == MealSlot.LUNCH }
        assertEquals("Bran", lunch.title)
        http.put("/api/plans/$week/meals/0/${MealSlot.LUNCH}/status") { setBody(MealStatus.EATEN) }
        http.put("/api/plans/$week/meals/0/${MealSlot.DINNER}/status") { setBody(MealStatus.SKIPPED) }
        val day = http.get("/api/days/${first.date}").body<DayView>()
        assertEquals(lunch.fibreG, day.eatenFibreG)
        assertEquals(lunch.fibreG, day.expectedFibreG, "the skipped dinner counts for nothing")
        assertEquals(lunch.fibreG, http.get("/api/plans/$week").body<WeekPlan>().days[0].fibreG, "nor in the plan's day")
    }
}
