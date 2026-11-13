package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.GenerationMode
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RatingInput
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeightLog
import dev.tobins.mealplanner.server.generation.GeneratedIngredient
import dev.tobins.mealplanner.server.generation.GeneratedRecipe
import dev.tobins.mealplanner.server.generation.GenerationContext
import dev.tobins.mealplanner.server.generation.GenerationException
import dev.tobins.mealplanner.server.generation.RecipeGenerator
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
    /** Records what it was asked and returns a fixed beef recipe. */
    private class FakeGenerator : RecipeGenerator {
        val contexts = mutableListOf<GenerationContext>()
        var fail = false

        override suspend fun generate(context: GenerationContext): GeneratedRecipe {
            contexts += context
            if (fail) throw GenerationException("Claude declined to write this recipe.")
            return GeneratedRecipe(
                title = "Beef bowl deluxe",
                servings = context.servings,
                ingredients = listOf(GeneratedIngredient(150.0 * context.servings, "g", "beef raw"), GeneratedIngredient(null, "", "salt")),
                steps = listOf("Sear the beef.", "Serve."),
                tags = listOf("Beef", " "),
                description = "High in protein.",
            )
        }
    }

    private val fake = FakeGenerator()

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
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), generator = fake) }
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

    @Test
    fun shoppingListScalesRecipesToPlannedPortionsAndSkipsStaples() = testApplication {
        val http = start().ready()
        http.post("/api/recipes") {
            setBody(RecipeInput("Snack plate", 1, "100 | g | beef\n| | gizmo to taste", mealSlots = listOf(MealSlot.SNACK)))
        }
        val plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
        val meals = plan.days.flatMap { it.meals }

        var list = http.get("/api/plans/$week/shopping").body<ShoppingList>()
        val beef = list.items.single { it.name == "Beef raw" }
        // Every recipe is beef only: grams per serving times the portions planned, summed over the week.
        val expected = meals.sumOf { meal ->
            val gramsPerServing = if (meal.title == "Snack plate") 100.0 else (1200 + meal.title.removePrefix("Dinner ").toInt() * 80) / 4.0
            gramsPerServing * meal.portions
        }
        assertEquals(expected, beef.grams!!, absoluteTolerance = 1e-6)
        assertEquals("Meat", beef.category)
        assertTrue("Snack plate" in beef.recipes)

        // "To taste" lines are a reminder, not something to buy.
        assertTrue(list.items.none { it.name.startsWith("Gizmo") })
        assertEquals(listOf("gizmo to taste"), list.toTaste)

        // Ticking off, on-hand marking and staples.
        list = http.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(beef.key, true)) }.body()
        assertTrue(list.items.single { it.key == beef.key }.checked)
        list = http.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(beef.key, false)) }.body()
        assertTrue(list.items.none { it.checked })

        val beefId = beef.key.removePrefix("i")
        http.put("/api/pantry/on-hand/$beefId")
        assertTrue(http.get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.key == beef.key }.onHand)
        http.put("/api/pantry/staples/$beefId")
        list = http.get("/api/plans/$week/shopping").body()
        assertTrue(list.items.none { it.key == beef.key })
        assertEquals(listOf("Beef raw"), list.staples)
    }

    @Test
    fun eatenAndSkippedMealsAndExtrasGiveTheDaysTotals() = testApplication {
        val http = start().ready()
        http.post("/api/plans/$week/generate")
        val tuesday = "2026-11-17"

        var day = http.get("/api/days/$tuesday").body<DayView>()
        assertEquals(week, day.weekStart)
        assertEquals(1, day.dayIndex)
        assertEquals(2000, day.targets?.kcal)
        val (lunch, dinner) = day.meals
        assertEquals(0.0, day.eatenKcal)
        assertEquals(lunch.kcal + dinner.kcal, day.expectedKcal, absoluteTolerance = 1e-9)

        http.put("/api/plans/$week/meals/1/lunch/status") { setBody(MealStatus.EATEN) }
        http.put("/api/plans/$week/meals/1/dinner/status") { setBody(MealStatus.SKIPPED) }
        day = http.post("/api/log") { setBody(LogEntry(date = tuesday, description = "Pizza out", kcal = 900.0, proteinG = 35.0)) }.body()
        assertEquals(lunch.kcal + 900, day.eatenKcal, absoluteTolerance = 1e-9)
        assertEquals(lunch.proteinG + 35, day.eatenProteinG, absoluteTolerance = 1e-9)
        assertEquals(day.eatenKcal, day.expectedKcal, absoluteTolerance = 1e-9, message = "the skipped dinner no longer counts")
        assertEquals(listOf("Pizza out"), day.extras.map { it.description })

        // Replanning keeps what already happened.
        val replanned = http.post("/api/plans/$week/generate").body<WeekPlan>().days[1].meals
        assertEquals(listOf(MealStatus.EATEN, MealStatus.SKIPPED), replanned.map { it.status })
        assertEquals(listOf(lunch.recipeId, dinner.recipeId), replanned.map { it.recipeId })

        http.delete("/api/log/${day.extras.single().id}")
        assertEquals(emptyList(), http.get("/api/days/$tuesday").body<DayView>().extras)
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/log") { setBody(LogEntry(date = tuesday, description = "", kcal = 1.0, proteinG = 1.0)) }.status)
        assertEquals(HttpStatusCode.BadRequest, http.get("/api/days/tuesday").status)
    }

    @Test
    fun weightLogGivesATrendUpdatesTheProfileAndSuggestsATarget() = testApplication {
        val http = start().ready()
        assertEquals(WeightLog(emptyList(), null, null), http.get("/api/weights").body<WeightLog>())

        // Four weeks at a steady 90 kg while the goal is 0.5 kg a week.
        var log = WeightLog(emptyList(), null, null)
        for (day in 1..28) log = http.put("/api/weights/2026-10-${"%02d".format(day)}") { setBody(90.0) }.body()
        assertEquals(28, log.entries.size)
        assertEquals(0.0, log.actualKgPerWeek!!, absoluteTolerance = 1e-9)
        val suggestion = log.suggestion!!
        assertEquals(2000, suggestion.currentKcal)
        assertEquals(1750, suggestion.suggestedKcal, "not losing: 250 kcal less, the largest single step")

        // The newest weighing becomes the profile weight; an older one does not.
        http.put("/api/weights/2026-10-29") { setBody(88.5) }
        assertEquals(88.5, http.get("/api/profile").body<ProfileState>().profile?.weightKg)
        http.put("/api/weights/2026-09-01") { setBody(95.0) }
        assertEquals(88.5, http.get("/api/profile").body<ProfileState>().profile?.weightKg)

        assertEquals(29, http.delete("/api/weights/2026-09-01").body<WeightLog>().entries.size)
        assertEquals(HttpStatusCode.BadRequest, http.put("/api/weights/2026-10-30") { setBody(5.0) }.status)
    }

    @Test
    fun ratingsAndPinnedRoutinesAreStoredAndUsed() = testApplication {
        val http = start().ready()
        val dinners = http.get("/api/recipes").body<List<RecipeSummary>>().filter { it.title.startsWith("Dinner") }
        val pick = dinners.first()

        val rated = http.put("/api/recipes/${pick.id}/rating") { setBody(RatingInput(4)) }.body<RecipeDetail>()
        assertEquals(4, rated.summary.rating)
        assertEquals(HttpStatusCode.BadRequest, http.put("/api/recipes/${pick.id}/rating") { setBody(RatingInput(6)) }.status)
        assertEquals(null, http.put("/api/recipes/${pick.id}/rating") { setBody(RatingInput(null)) }.body<RecipeDetail>().summary.rating)

        assertEquals(Routines(), http.get("/api/routines").body<Routines>())
        http.put("/api/recipes/${pick.id}/settings") { setBody(RecipeSettings(RecipeKind.MEAL, listOf(MealSlot.BREAKFAST, MealSlot.DINNER))) }
        assertEquals(Routines(breakfast = pick.id), http.put("/api/routines") { setBody(Routines(breakfast = pick.id)) }.body<Routines>())
        val plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
        assertTrue(plan.days.all { day -> day.meals.first { it.slot == MealSlot.BREAKFAST }.recipeId == pick.id })
        assertEquals(HttpStatusCode.NotFound, http.put("/api/routines") { setBody(Routines(snack = 99999)) }.status)
    }

    @Test
    fun generatedRecipesAreSizedStoredLinkedAndEditable() = testApplication {
        val http = start()
        http.post("/api/sync/cooking")
        assertEquals(HttpStatusCode.Conflict, http.post("/api/recipes/generate") { setBody(GenerateRequest()) }.status, "needs a profile")
        http.ready()
        val beef = http.get("/api/ingredients").body<List<Ingredient>>().first { it.food?.nevoCode == 10 }
        http.put("/api/pantry/on-hand/${beef.id}")
        // Plan the current week, so today's and earlier meals count as recently eaten.
        val thisWeek = java.time.LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        http.post("/api/plans/$thisWeek/generate")

        val created = http.post("/api/recipes/generate") {
            setBody(GenerateRequest(GenerationMode.TASTE_BREAKER, MealSlot.DINNER, "something warm"))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val recipe = created.body<RecipeDetail>()

        val context = fake.contexts.single()
        assertEquals(GenerationMode.TASTE_BREAKER, context.mode)
        assertEquals(4, context.servings)
        assertEquals(760, context.kcalPerServing, "38% of the 2000 kcal override")
        assertEquals("something warm", context.wish)
        assertEquals(listOf(beef.name), context.onHand)
        assertTrue(context.existing.containsAll((0..9).map { "Dinner $it" }))
        assertTrue(context.recent.isNotEmpty() && context.recent.all { it.startsWith("Dinner") }, "the planned week counts as recent")

        assertEquals(RecipeSource.GENERATED, recipe.summary.source)
        assertEquals(listOf(MealSlot.LUNCH, MealSlot.DINNER), recipe.summary.mealSlots)
        assertEquals(listOf("Beef"), recipe.summary.tags)
        assertTrue(recipe.instructions.startsWith("High in protein.") && "1. Sear the beef." in recipe.instructions)
        // "beef raw" is exactly the NEVO name, so it is linked and counted: 150 g at 150 kcal per 100 g.
        assertEquals(225.0, recipe.summary.nutrition!!.perServing.kcal!!, absoluteTolerance = 1e-9)
        assertTrue(recipe.summary.nutrition!!.complete)

        // Generated recipes can be edited and deleted like your own.
        assertEquals(HttpStatusCode.OK, http.put("/api/recipes/${recipe.summary.id}") { setBody(RecipeInput("Beef bowl", 4, "600 | g | beef raw")) }.status)
        assertEquals(HttpStatusCode.NoContent, http.delete("/api/recipes/${recipe.summary.id}").status)

        fake.fail = true
        val failed = http.post("/api/recipes/generate") { setBody(GenerateRequest(slot = MealSlot.BREAKFAST)) }
        assertEquals(HttpStatusCode.BadGateway, failed.status)
        assertEquals(1, fake.contexts.last().servings)
    }

    @Test
    fun generationIsOffWithoutAGenerator() = testApplication {
        val config = ServerConfig(port = 0, apiToken = "t", databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), generator = null) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, http.post("/api/recipes/generate") { setBody(GenerateRequest()) }.status)
    }

    @Test
    fun aGeneratedRecipeGoesStraightIntoAPlanSlotWithItsLeftovers() = testApplication {
        val http = start().ready()
        val before = http.post("/api/plans/$week/generate").body<WeekPlan>()
        assertTrue(before.days[2].meals.first { it.slot == MealSlot.LUNCH }.leftover)

        val after = http.post("/api/plans/$week/meals/1/dinner/generate") { setBody(GenerateRequest(wish = "beef")) }.body<WeekPlan>()
        val dinner = after.days[1].meals.first { it.slot == MealSlot.DINNER }
        assertEquals("Beef bowl deluxe", dinner.title)
        assertEquals(MealSlot.DINNER, fake.contexts.single().slot)
        // 225 kcal a serving against a 760 kcal dinner budget: two servings, the most allowed.
        assertEquals(2.0, dinner.portions)
        val lunch = after.days[2].meals.first { it.slot == MealSlot.LUNCH }
        assertEquals(dinner.recipeId, lunch.recipeId, "the next lunch is the new dinner's leftovers")
        assertTrue(lunch.leftover)
        assertEquals(2.0, lunch.portions, "four servings made, two eaten at dinner")

        val untouched = { plan: WeekPlan -> plan.days.flatMap { it.meals }.filterNot { (it.day == 1 && it.slot == MealSlot.DINNER) || (it.day == 2 && it.slot == MealSlot.LUNCH) } }
        assertEquals(untouched(before), untouched(after))
    }

    @Test
    fun dislikedIngredientsAreNeverPlannedAndLikesReachGeneration() = testApplication {
        val http = start().ready()
        val profile = http.get("/api/profile").body<ProfileState>().profile!!
        http.put("/api/profile") { setBody(profile.copy(likes = listOf("Korean"), dislikes = listOf("beef"))) }

        val plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
        assertEquals(0, plan.days.sumOf { it.meals.size }, "every test recipe contains beef")

        http.put("/api/profile") { setBody(profile.copy(likes = listOf("Korean"), dislikes = listOf("bee"))) }
        assertTrue(http.post("/api/plans/$week/generate").body<WeekPlan>().days.sumOf { it.meals.size } > 0, "whole words only")

        http.post("/api/recipes/generate") { setBody(GenerateRequest()) }
        assertEquals(listOf("Korean"), fake.contexts.last().likes)
        assertEquals(listOf("bee"), fake.contexts.last().dislikes)
    }
}
