package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.WeekPlan
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlanTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat|10|Rundvlees rauw|Beef raw||per 100g|150|20|4|2|0|0|0|50",
    ).joinToString("\r\n")

    /** Ten four-serving dinners of 450 to 720 kcal per serving, one unlinked recipe and one sauce. */
    private fun ApplicationTestBuilder.start(): HttpClient {
        val repo = createTempDirectory("cooking").toFile()
        fun write(path: String, text: String) = File(repo, "content/$path").apply { parentFile.mkdirs() }.writeText(text)
        for (i in 0..9) {
            write("recipes/dinner$i.md", "---\ntitle: Dinner $i\n---\n```ingredients\nservings: 4\n${1200 + i * 80} | g | beef\n```")
        }
        write("recipes/unlinked.md", "---\ntitle: Unlinked\n---\n```ingredients\nservings: 4\n500 | g | unobtainium\n```")
        write("sauces/sauce.md", "---\ntitle: Sauce\n---\n```ingredients\nservings: 4\n1200 | g | beef\n```")
        val config = ServerConfig(
            port = 0, apiToken = "t", databaseUrl = "jdbc:sqlite::memory:", cookingRepo = repo,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        application { module(config, openDatabase(config.databaseUrl).second) }
        return createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
    }

    private suspend fun HttpClient.ready(): HttpClient = apply {
        post("/api/sync/cooking")
        put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
    }

    private val week = "2026-11-16"

    @Test
    fun planningNeedsAProfile() = testApplication {
        val http = start()
        http.post("/api/sync/cooking")
        assertEquals(HttpStatusCode.Conflict, http.post("/api/plans/$week/generate").status)
        assertEquals(HttpStatusCode.BadRequest, http.ready().get("/api/plans/next-week").status)
    }

    @Test
    fun generatesAWeekFromCompleteMealRecipesOnly() = testApplication {
        val http = start().ready()
        assertEquals(0, http.get("/api/plans/$week").body<WeekPlan>().days.sumOf { it.meals.size })

        val plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
        assertEquals(2000, plan.targets.kcal)
        assertEquals((16..22).map { "2026-11-$it" }, plan.days.map { it.date })
        assertEquals(listOf(MealSlot.BREAKFAST, MealSlot.SNACK), plan.emptySlots, "no breakfast or snack recipes exist")

        val meals = plan.days.flatMap { it.meals }
        assertEquals(14, meals.size)
        assertTrue(meals.all { it.title.startsWith("Dinner ") }, "the unlinked recipe and the sauce are never planned")
        assertEquals(6, meals.count { it.leftover })
        for (day in plan.days) {
            assertEquals(day.meals.sumOf { it.kcal }, day.kcal, absoluteTolerance = 1e-9)
            // Lunch and dinner together get 68% of the calories.
            assertEquals(1360.0, day.kcal, absoluteTolerance = 250.0)
        }
        assertEquals(plan, http.get("/api/plans/$week").body<WeekPlan>(), "the plan is stored")
    }

    @Test
    fun lockedMealsSurviveRegeneration() = testApplication {
        val http = start().ready()
        val first = http.post("/api/plans/$week/generate").body<WeekPlan>()
        val kept = first.days[2].meals.first { it.slot == MealSlot.DINNER }

        http.put("/api/plans/$week/meals/2/dinner/lock")
        repeat(3) {
            val again = http.post("/api/plans/$week/generate").body<WeekPlan>()
            assertEquals(kept.copy(locked = true), again.days[2].meals.first { it.slot == MealSlot.DINNER })
            assertEquals(kept.recipeId, again.days[3].meals.first { it.slot == MealSlot.LUNCH }.recipeId, "its leftovers follow")
        }
        val unlocked = http.delete("/api/plans/$week/meals/2/dinner/lock").body<WeekPlan>()
        assertEquals(kept, unlocked.days[2].meals.first { it.slot == MealSlot.DINNER })
    }

    @Test
    fun rerollChangesOneDinnerAndItsLeftoverLunch() = testApplication {
        val http = start().ready()
        val before = http.post("/api/plans/$week/generate").body<WeekPlan>()
        val after = http.post("/api/plans/$week/meals/1/dinner/reroll").body<WeekPlan>()

        val oldDinner = before.days[1].meals.first { it.slot == MealSlot.DINNER }
        val newDinner = after.days[1].meals.first { it.slot == MealSlot.DINNER }
        assertNotEquals(oldDinner.recipeId, newDinner.recipeId)
        assertEquals(newDinner.recipeId, after.days[2].meals.first { it.slot == MealSlot.LUNCH }.recipeId)

        val untouched = { plan: WeekPlan ->
            plan.days.flatMap { it.meals }.filterNot { (it.day == 1 && it.slot == MealSlot.DINNER) || (it.day == 2 && it.slot == MealSlot.LUNCH) }
        }
        assertEquals(untouched(before), untouched(after))
    }

    @Test
    fun mealsCanBeRemovedAndBadCoordinatesAreRejected() = testApplication {
        val http = start().ready()
        http.post("/api/plans/$week/generate")

        val plan = http.delete("/api/plans/$week/meals/0/lunch").body<WeekPlan>()
        assertEquals(listOf(MealSlot.DINNER), plan.days[0].meals.map { it.slot })
        assertEquals(HttpStatusCode.NotFound, http.post("/api/plans/$week/meals/0/lunch/reroll").status)
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/plans/$week/meals/9/lunch/reroll").status)
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/plans/$week/meals/0/brunch/reroll").status)
    }

    @Test
    fun ownRecipesFillBreakfastAndSnackAndCanBeEdited() = testApplication {
        val http = start().ready()
        val created = http.post("/api/recipes") {
            setBody(RecipeInput("Beef bowl", servings = 1, ingredients = "300 | g | beef", mealSlots = listOf(MealSlot.BREAKFAST)))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val bowl = created.body<RecipeDetail>()
        assertEquals(RecipeSource.MANUAL, bowl.summary.source)
        assertEquals(450.0, bowl.summary.nutrition!!.perServing.kcal)
        assertEquals(listOf(MealSlot.BREAKFAST), bowl.summary.mealSlots)

        var plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
        assertEquals(listOf(MealSlot.SNACK), plan.emptySlots)
        assertEquals(7, plan.days.count { day -> day.meals.any { it.slot == MealSlot.BREAKFAST && it.title == "Beef bowl" } })

        // Editing replaces the ingredients; an imported recipe can become a snack through its settings.
        val edited = http.put("/api/recipes/${bowl.summary.id}") {
            setBody(RecipeInput("Beef bowl", 1, "200 | g | beef", mealSlots = listOf(MealSlot.BREAKFAST)))
        }.body<RecipeDetail>()
        assertEquals(300.0, edited.summary.nutrition!!.perServing.kcal)
        val dinner0 = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.title == "Dinner 0" }
        assertEquals(listOf(MealSlot.DINNER), dinner0.mealSlots, "the section's default")
        http.put("/api/recipes/${dinner0.id}/settings") { setBody(RecipeSettings(RecipeKind.MEAL, listOf(MealSlot.SNACK))) }
        plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
        assertEquals(emptyList(), plan.emptySlots)
        assertTrue(plan.days.all { day -> day.meals.first { it.slot == MealSlot.SNACK }.title == "Dinner 0" })

        // Settings survive a sync of the cooking repo.
        http.post("/api/sync/cooking")
        assertEquals(listOf(MealSlot.SNACK), http.get("/api/recipes/${dinner0.id}").body<RecipeDetail>().summary.mealSlots)

        assertEquals(HttpStatusCode.Conflict, http.put("/api/recipes/${dinner0.id}") { setBody(RecipeInput("X", 1, "1 | g | beef")) }.status)
        assertEquals(HttpStatusCode.Conflict, http.delete("/api/recipes/${dinner0.id}").status)
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/recipes") { setBody(RecipeInput("X", 1, "some | broken line")) }.status)
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/recipes") { setBody(RecipeInput(" ", 1, "1 | g | beef")) }.status)
        assertEquals(HttpStatusCode.NoContent, http.delete("/api/recipes/${bowl.summary.id}").status)
        assertEquals(HttpStatusCode.NotFound, http.get("/api/recipes/${bowl.summary.id}").status)
    }
}
