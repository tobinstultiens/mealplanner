package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.ManyRotationInput
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.GenerationMode
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
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
import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.server.generation.GeneratedIngredient
import dev.tobins.mealplanner.server.generation.GeneratedRecipe
import dev.tobins.mealplanner.server.generation.GenerationContext
import dev.tobins.mealplanner.server.generation.GenerationException
import dev.tobins.mealplanner.server.generation.RecipeGenerator
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlanTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|10|Rundvlees rauw|Beef raw||per 100g|150|20|4|2|0|0|0|50",
    ).joinToString("\r\n")

    /** Ten four-serving dinners of 450 to 720 kcal per serving, one unlinked recipe and one sauce. */
    /** Records what it was asked and returns a fixed beef recipe. */
    private class FakeGenerator : RecipeGenerator {
        val contexts = mutableListOf<GenerationContext>()
        var fail = false

        /** What happens elsewhere while the recipe is written, such as the meal being eaten on another device. */
        var meanwhile: () -> Unit = {}

        override suspend fun generate(context: GenerationContext): GeneratedRecipe {
            contexts += context
            meanwhile()
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

    /** The database of the server [start] built, to read what the API does not show. */
    private lateinit var db: dev.tobins.mealplanner.server.db.Database

    /** Starts the server on a database in memory, or at [databaseUrl] to share it with other connections. */
    private fun ApplicationTestBuilder.start(databaseUrl: String = "jdbc:sqlite::memory:"): HttpClient {
        val repo = createTempDirectory("cooking").toFile()
        fun write(path: String, text: String) = File(repo, "content/$path").apply { parentFile.mkdirs() }.writeText(text)
        for (i in 0..9) {
            write("recipes/dinner$i.md", "---\ntitle: Dinner $i\n---\n```ingredients\nservings: 4\n${1200 + i * 80} | g | beef\n```")
        }
        write("recipes/unlinked.md", "---\ntitle: Unlinked\n---\n```ingredients\nservings: 4\n500 | g | unobtainium\n```")
        write("recipes/bare.md", "---\ntitle: Bare\n---\nJust a note, no amounts.")
        write("sauces/sauce.md", "---\ntitle: Sauce\n---\n```ingredients\nservings: 4\n1200 | g | beef\n```")
        val config = ServerConfig(
            port = 0, databaseUrl = databaseUrl, cookingRepo = repo,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), generator = fake, verifier = TestTokens) }
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
        addWholeBank()
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

    private fun WeekPlan.meal(day: Int, slot: MealSlot) = days[day].meals.firstOrNull { it.slot == slot }

    /** Grams of beef per serving of the test's "Dinner n" recipes. */
    private fun gramsPerServing(title: String) = (1200 + title.removePrefix("Dinner ").toInt() * 80) / 4.0

    @Test
    fun aChosenDinnerIsLockedSizedToItsSlotAndCooksTomorrowsLunch() = testApplication {
        val http = start().ready()
        val before = http.post("/api/plans/$week/generate").body<WeekPlan>()
        val chosen = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.title == "Dinner 9" }

        val plan = http.put("/api/plans/$week/meals/4/dinner") { setBody(MealChoice(chosen.id)) }.body<WeekPlan>()
        val dinner = plan.meal(4, MealSlot.DINNER)!!
        assertEquals(chosen.id, dinner.recipeId)
        assertTrue(dinner.locked)
        assertEquals(MealStatus.PLANNED, dinner.status)
        val perServing = chosen.nutrition!!.perServing.kcal!!
        assertEquals(2000 * MealSlot.DINNER.share, dinner.kcal, absoluteTolerance = perServing / 2, message = "sized to the dinner's share")
        val lunch = plan.meal(5, MealSlot.LUNCH)!!
        assertTrue(lunch.leftover)
        assertEquals(chosen.id, lunch.recipeId)
        assertTrue(lunch.portions + dinner.portions <= 4.0, "no more than the recipe makes")

        // Nothing else changed, and replanning keeps the choice and its leftovers.
        val others = { p: WeekPlan -> p.days.flatMap { it.meals }.filterNot { (it.day == 4 && it.slot == MealSlot.DINNER) || (it.day == 5 && it.slot == MealSlot.LUNCH) } }
        assertEquals(others(before), others(plan))
        repeat(2) {
            val again = http.post("/api/plans/$week/generate").body<WeekPlan>()
            assertEquals(dinner, again.meal(4, MealSlot.DINNER))
            assertEquals(lunch, again.meal(5, MealSlot.LUNCH))
        }
    }

    @Test
    fun aChosenRecipeNeedNotBeMarkedForTheSlotAndFillsAnEmptyOne() = testApplication {
        val http = start().ready()
        val breakfastOnly = http.post("/api/recipes") {
            setBody(RecipeInput("Beef bowl", 1, "300 | g | beef", mealSlots = listOf(MealSlot.BREAKFAST)))
        }.body<RecipeDetail>().summary
        assertEquals(null, http.get("/api/plans/$week").body<WeekPlan>().meal(0, MealSlot.DINNER), "nothing planned yet")

        val plan = http.put("/api/plans/$week/meals/0/dinner") { setBody(MealChoice(breakfastOnly.id)) }.body<WeekPlan>()
        val dinner = plan.meal(0, MealSlot.DINNER)!!
        assertEquals(breakfastOnly.id, dinner.recipeId)
        assertTrue(dinner.locked)
        // A recipe of one serving leaves nothing for the next day's lunch.
        assertEquals(null, plan.meal(1, MealSlot.LUNCH))
    }

    /** Saves the test's profile with the portion range [min] to [max]. */
    private suspend fun HttpClient.portions(min: Double, max: Double) = apply {
        val profile = Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000, minPortions = min, maxPortions = max)
        assertEquals(HttpStatusCode.OK, put("/api/profile") { setBody(profile) }.status)
    }

    /**
     * A beef recipe of 195 kcal a serving: half the dinner's 760 kcal at two servings, not at 1.5; nor
     * half the lunch's 600 at 1.5.
     */
    private suspend fun HttpClient.lightDinner(): RecipeSummary =
        post("/api/recipes") { setBody(RecipeInput("Beef taster", 3, "390 | g | beef", mealSlots = listOf(MealSlot.DINNER))) }.body<RecipeDetail>().summary

    @Test
    fun aChosenRecipeIsSizedWithinTheProfilesRangeAlsoWhenItIsTooLightToBeAMeal() = testApplication {
        val http = start().ready()
        // Dinner 0 is 450 kcal a serving: 1.75 servings come closest to the dinner's 760, 1.5 within the defaults.
        val dinner0 = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.title == "Dinner 0" }
        val dinner9 = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.title == "Dinner 9" }
        val light = http.lightDinner()
        suspend fun choose(day: Int, slot: MealSlot, recipe: RecipeSummary) =
            http.put("/api/plans/$week/meals/$day/${slot.name.lowercase()}") { setBody(MealChoice(recipe.id)) }.body<WeekPlan>().meal(day, slot)!!

        assertEquals(1.5, choose(0, MealSlot.DINNER, dinner0).portions)
        assertEquals(1.5, choose(1, MealSlot.DINNER, light).portions, "the user chose it, so it is planned, at the largest portion")
        // A 720 kcal dinner as a snack of 200 kcal: the smallest portion, not less.
        assertEquals(0.75, choose(1, MealSlot.SNACK, dinner9).portions)

        http.portions(0.25, 2.0)
        assertEquals(1.75, choose(2, MealSlot.DINNER, dinner0).portions)
        assertEquals(2.0, choose(3, MealSlot.DINNER, light).portions)
        assertEquals(0.25, choose(3, MealSlot.SNACK, dinner9).portions)
        // Meals planned before the range changed keep their portions.
        assertEquals(1.5, http.get("/api/plans/$week").body<WeekPlan>().meal(0, MealSlot.DINNER)!!.portions)
    }

    @Test
    fun aRotationOfDinnersTooLightForTheRangeLeavesTheSlotsEmptyAndThePlanSaysSo() = testApplication {
        val http = start().ready()
        val site = http.get("/api/recipes").body<List<RecipeSummary>>().map { it.id }
        http.put("/api/recipes/rotation") { setBody(ManyRotationInput(site, inRotation = false)) }
        val light = http.lightDinner()

        val plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
        assertEquals(MealSlot.entries, plan.emptySlots)
        assertTrue(plan.days.all { it.meals.isEmpty() })

        // Up to two servings it fills every dinner, and every lunch with leftovers or cooked fresh.
        val wide = http.portions(0.5, 2.0).post("/api/plans/$week/generate").body<WeekPlan>()
        assertEquals(listOf(MealSlot.BREAKFAST, MealSlot.SNACK), wide.emptySlots)
        assertTrue(wide.days.all { day -> day.meals.all { it.recipeId == light.id } })
    }

    @Test
    fun choosingARecipeWithNothingLeftOverTakesTheOldDinnersLeftoversAway() = testApplication {
        val http = start().ready()
        val before = http.post("/api/plans/$week/generate").body<WeekPlan>()
        assertTrue(before.meal(2, MealSlot.LUNCH)!!.leftover)
        val single = http.post("/api/recipes") { setBody(RecipeInput("Beef bowl", 1, "300 | g | beef")) }.body<RecipeDetail>().summary

        val plan = http.put("/api/plans/$week/meals/1/dinner") { setBody(MealChoice(single.id)) }.body<WeekPlan>()
        assertEquals(single.id, plan.meal(1, MealSlot.DINNER)!!.recipeId)
        assertEquals(null, plan.meal(2, MealSlot.LUNCH), "the old dinner's leftovers are gone")
    }

    @Test
    fun aChoiceNeedsARecipeWithAmountsAndAMealNotEatenYet() = testApplication {
        val http = start().ready()
        val recipes = http.get("/api/recipes").body<List<RecipeSummary>>()
        http.post("/api/plans/$week/generate")
        val bare = recipes.first { it.title == "Bare" }
        assertEquals(HttpStatusCode.Conflict, http.put("/api/plans/$week/meals/0/dinner") { setBody(MealChoice(bare.id)) }.status)
        assertEquals(HttpStatusCode.NotFound, http.put("/api/plans/$week/meals/0/dinner") { setBody(MealChoice(99_999)) }.status)
        assertEquals(HttpStatusCode.BadRequest, http.put("/api/plans/$week/meals/9/dinner") { setBody(MealChoice(bare.id)) }.status)

        http.put("/api/plans/$week/meals/0/dinner/status") { setBody(MealStatus.EATEN) }
        val dinner = recipes.first { it.title == "Dinner 3" }
        assertEquals(HttpStatusCode.Conflict, http.put("/api/plans/$week/meals/0/dinner") { setBody(MealChoice(dinner.id)) }.status)
        // A skipped meal can be chosen for after all: it is planned again.
        http.put("/api/plans/$week/meals/1/dinner/status") { setBody(MealStatus.SKIPPED) }
        val plan = http.put("/api/plans/$week/meals/1/dinner") { setBody(MealChoice(dinner.id)) }.body<WeekPlan>()
        assertEquals(MealStatus.PLANNED, plan.meal(1, MealSlot.DINNER)!!.status)
    }

    @Test
    fun aRemovedRecipeIsNotThereAndAComponentIsNoMeal() = testApplication {
        val http = start().ready()
        val bowl = http.post("/api/recipes") { setBody(RecipeInput("Beef bowl", 1, "300 | g | beef")) }.body<RecipeDetail>().summary
        val sauce = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.title == "Sauce" }
        assertEquals(RecipeKind.COMPONENT, sauce.kind)
        assertEquals(HttpStatusCode.Conflict, http.put("/api/plans/$week/meals/0/dinner") { setBody(MealChoice(sauce.id)) }.status)

        assertEquals(HttpStatusCode.NoContent, http.delete("/api/recipes/${bowl.id}").status)
        assertEquals(HttpStatusCode.NotFound, http.put("/api/plans/$week/meals/0/dinner") { setBody(MealChoice(bowl.id)) }.status)
        assertEquals(null, http.get("/api/plans/$week").body<WeekPlan>().meal(0, MealSlot.DINNER))
    }

    /** Stocks up 20 kg of beef for [week]; what is left of it is what the returned function reads. */
    private suspend fun HttpClient.stockUpBeef(): () -> Double {
        val beef = get("/api/ingredients").body<List<Ingredient>>().first { it.name == "Beef raw" }.id
        val userId = db.userQueries.selectBySubject("sub-t").executeAsOne().id
        StockRepository(db, FoodRepository(db), userId).putOnHandFrom(beef, LocalDate.parse(week), grams = 20_000.0, source = StockSource.STOCK_UP)
        return { db.pantryQueries.selectStock(userId, week, listOf(beef)).executeAsOne().grams!! }
    }

    @Test
    fun aLeftoverLunchOfAnEatenDinnerCannotBeReplacedAndTheStockStaysWhole() = testApplication {
        val http = start().ready()
        http.post("/api/plans/$week/generate")
        val stock = http.stockUpBeef()
        val other = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.title == "Dinner 9" }
        val lunch = http.get("/api/plans/$week").body<WeekPlan>().meal(5, MealSlot.LUNCH)!!
        assertTrue(lunch.leftover)

        http.put("/api/plans/$week/meals/4/dinner/status") { setBody(MealStatus.EATEN) }
        val afterEating = stock()
        assertTrue(afterEating < 20_000.0)

        // Neither choosing nor "Another recipe" may replace the lunch: the stock went down for it too.
        assertEquals(HttpStatusCode.Conflict, http.put("/api/plans/$week/meals/5/lunch") { setBody(MealChoice(other.id)) }.status)
        assertEquals(HttpStatusCode.Conflict, http.post("/api/plans/$week/meals/5/lunch/reroll").status)
        assertEquals(HttpStatusCode.Conflict, http.post("/api/plans/$week/meals/4/dinner/reroll").status, "the eaten dinner itself too")
        assertEquals(lunch, http.get("/api/plans/$week").body<WeekPlan>().meal(5, MealSlot.LUNCH))
        assertEquals(afterEating, stock())

        http.put("/api/plans/$week/meals/4/dinner/status") { setBody(MealStatus.PLANNED) }
        assertEquals(20_000.0, stock(), 1e-6)
    }

    @Test
    fun anotherRecipeForADinnerLeavesItsEatenOrSkippedLeftoverLunchAndTheStockStaysWhole() = testApplication {
        val http = start().ready()
        val before = http.post("/api/plans/$week/generate").body<WeekPlan>()
        val stock = http.stockUpBeef()
        assertTrue(before.meal(5, MealSlot.LUNCH)!!.leftover && before.meal(3, MealSlot.LUNCH)!!.leftover)

        // The leftover lunch is eaten first: it takes its own share, as its dinner was not cooked yet.
        http.put("/api/plans/$week/meals/5/lunch/status") { setBody(MealStatus.EATEN) }
        val eaten = http.get("/api/plans/$week").body<WeekPlan>().meal(5, MealSlot.LUNCH)!!
        val afterEating = stock()
        assertTrue(afterEating < 20_000.0)

        // "Another recipe" for its dinner changes the dinner only: the eaten lunch stays the eaten lunch.
        val swapped = http.post("/api/plans/$week/meals/4/dinner/reroll")
        assertEquals(HttpStatusCode.OK, swapped.status)
        val plan = swapped.body<WeekPlan>()
        assertNotEquals(before.meal(4, MealSlot.DINNER)!!.recipeId, plan.meal(4, MealSlot.DINNER)!!.recipeId)
        assertEquals(eaten, plan.meal(5, MealSlot.LUNCH))
        assertEquals(afterEating, stock())

        // So un-eating it gives its share back, and eating it again takes the same share once.
        http.put("/api/plans/$week/meals/5/lunch/status") { setBody(MealStatus.PLANNED) }
        assertEquals(20_000.0, stock(), 1e-6)
        http.put("/api/plans/$week/meals/5/lunch/status") { setBody(MealStatus.EATEN) }
        assertEquals(afterEating, stock(), 1e-6)
        http.put("/api/plans/$week/meals/5/lunch/status") { setBody(MealStatus.PLANNED) }
        assertEquals(20_000.0, stock(), 1e-6)

        // A skipped leftover lunch is left as it is too.
        http.put("/api/plans/$week/meals/3/lunch/status") { setBody(MealStatus.SKIPPED) }
        val skipped = http.get("/api/plans/$week").body<WeekPlan>().meal(3, MealSlot.LUNCH)!!
        assertEquals(skipped, http.post("/api/plans/$week/meals/2/dinner/reroll").body<WeekPlan>().meal(3, MealSlot.LUNCH))
        assertEquals(20_000.0, stock(), 1e-6)
    }

    @Test
    fun aNewRecipeIsNotWrittenForAMealWhoseStockWasTaken() = testApplication {
        val http = start().ready()
        http.post("/api/plans/$week/generate")
        val stock = http.stockUpBeef()

        // An eaten dinner, and its leftover lunch, which the dinner took the stock for.
        http.put("/api/plans/$week/meals/4/dinner/status") { setBody(MealStatus.EATEN) }
        val afterEating = stock()
        assertTrue(afterEating < 20_000.0)
        val plan = http.get("/api/plans/$week").body<WeekPlan>()
        for ((day, slot, reason) in listOf(
            Triple(4, "dinner", "This meal was eaten already"),
            Triple(5, "lunch", "The dinner before it was eaten, and these leftovers with it"),
        )) {
            val refused = http.post("/api/plans/$week/meals/$day/$slot/generate") { setBody(GenerateRequest(wish = "beef")) }
            // Not 409: the app's generate dialog reads a 409 as "set up your profile first", and shows this one's text.
            assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
            assertTrue(reason in refused.bodyAsText(), refused.bodyAsText())
        }
        assertTrue(fake.contexts.isEmpty(), "refused before a recipe is written")
        assertEquals(plan, http.get("/api/plans/$week").body<WeekPlan>())
        assertEquals(afterEating, stock())
        http.put("/api/plans/$week/meals/4/dinner/status") { setBody(MealStatus.PLANNED) }
        assertEquals(20_000.0, stock(), 1e-6)

        // A dinner whose leftover lunch was eaten first gets its new recipe; the eaten lunch stays as it is.
        http.put("/api/plans/$week/meals/2/lunch/status") { setBody(MealStatus.EATEN) }
        val eaten = http.get("/api/plans/$week").body<WeekPlan>().meal(2, MealSlot.LUNCH)!!
        val written = http.post("/api/plans/$week/meals/1/dinner/generate") { setBody(GenerateRequest(wish = "beef")) }.body<WeekPlan>()
        assertEquals("Beef bowl deluxe", written.meal(1, MealSlot.DINNER)!!.title)
        assertEquals(eaten, written.meal(2, MealSlot.LUNCH))
        http.put("/api/plans/$week/meals/2/lunch/status") { setBody(MealStatus.PLANNED) }
        assertEquals(20_000.0, stock(), 1e-6)
    }

    @Test
    fun aMealEatenWhileItsRecipeIsWrittenKeepsItsRecipeAndTheNewOneIsKeptInTheRecipes() = testApplication {
        val http = start().ready()
        val plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
        fake.meanwhile = { db.planQueries.setStatus(MealStatus.EATEN.name, userId(), week, 4, MealSlot.DINNER.name) }

        val refused = http.post("/api/plans/$week/meals/4/dinner/generate") { setBody(GenerateRequest(wish = "beef")) }
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        // The dialog shows "Could not generate: " and this, so it says where the recipe went.
        val message = refused.bodyAsText()
        assertTrue("This meal was eaten already; the new recipe \\\"Beef bowl deluxe\\\" is kept in your recipes" in message, message)
        assertTrue(http.get("/api/recipes").body<List<RecipeSummary>>().any { it.title == "Beef bowl deluxe" })
        assertEquals(plan.meal(4, MealSlot.DINNER)!!.copy(status = MealStatus.EATEN), http.get("/api/plans/$week").body<WeekPlan>().meal(4, MealSlot.DINNER))
    }

    /** Beef the stock gave for [meal], a meal of the test's "Dinner n" recipes. */
    private fun share(meal: PlannedMeal) = meal.portions * gramsPerServing(meal.title)

    private fun userId() = db.userQueries.selectBySubject("sub-t").executeAsOne().id

    @Test
    fun replanningKeepsTheLeftoverLunchOfAnEatenDinnerAndTheStockStaysWhole() = testApplication {
        val http = start().ready()
        http.post("/api/plans/$week/generate")
        val stock = http.stockUpBeef()
        http.put("/api/plans/$week/meals/4/dinner/status") { setBody(MealStatus.EATEN) }
        val eaten = http.get("/api/plans/$week").body<WeekPlan>()
        val dinner = eaten.meal(4, MealSlot.DINNER)!!
        val lunch = eaten.meal(5, MealSlot.LUNCH)!!
        assertTrue(lunch.leftover && lunch.recipeId == dinner.recipeId)
        val afterEating = stock()
        assertEquals(20_000.0 - share(dinner) - share(lunch), afterEating, 1e-6, "the dinner was cooked with its leftovers")

        repeat(2) {
            val replanned = http.post("/api/plans/$week/generate").body<WeekPlan>()
            assertEquals(dinner, replanned.meal(4, MealSlot.DINNER))
            assertEquals(lunch, replanned.meal(5, MealSlot.LUNCH), "kept, not planned anew")
            assertEquals(afterEating, stock(), 1e-6)
            // The day counts the kept lunch, as it is still to eat; the list does not buy for it again, as its share was taken.
            assertEquals(replanned.days[5].meals.sumOf { it.kcal }, replanned.days[5].kcal, 1e-9)
            val toCook = PlanRepository.toCook(replanned.days.flatMap { it.meals })
            assertFalse(lunch in toCook)
            val beef = http.get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.name == "Beef raw" }
            assertEquals(toCook.sumOf(::share), beef.toComeGrams!!, 1e-6)
        }

        http.put("/api/plans/$week/meals/4/dinner/status") { setBody(MealStatus.PLANNED) }
        assertEquals(20_000.0, stock(), 1e-6)
    }

    /**
     * Issue 169: a dinner whose leftover lunch was swapped for another recipe is cooked for itself only, and
     * eating it takes only its own share. Planning around it must not put a leftover lunch of it back: nothing
     * was cooked or taken for that lunch, so the shopping list would not buy for it either.
     */
    @Test
    fun aDinnerEatenWithoutLeftoversGetsNoLeftoverLunchWhenTheWeekIsReplanned() = testApplication {
        val http = start().ready()
        http.post("/api/plans/$week/generate")
        val stock = http.stockUpBeef()
        assertFalse(http.post("/api/plans/$week/meals/5/lunch/reroll").body<WeekPlan>().meal(5, MealSlot.LUNCH)!!.leftover)
        val eaten = http.put("/api/plans/$week/meals/4/dinner/status") { setBody(MealStatus.EATEN) }.body<WeekPlan>()
        val dinner = eaten.meal(4, MealSlot.DINNER)!!
        assertEquals(20_000.0 - share(dinner), stock(), 1e-6, "the dinner was cooked for itself only")

        for ((change, run) in listOf<Pair<String, suspend () -> HttpResponse>>(
            "a replan" to { http.post("/api/plans/$week/generate") },
            "another recipe for the lunch" to { http.post("/api/plans/$week/meals/5/lunch/reroll") },
        )) {
            val plan = run().body<WeekPlan>()
            val lunch = plan.meal(5, MealSlot.LUNCH)!!
            assertFalse(lunch.leftover && lunch.recipeId == dinner.recipeId, "$change planned leftovers of a dinner eaten without them: $lunch")
            // Planned like any other meal: cooked for itself, and bought for.
            assertEquals(lunch.portions, lunch.cookPortions, change)
            val toCook = PlanRepository.toCook(plan.days.flatMap { it.meals })
            assertTrue(lunch in toCook, change)
            val beef = http.get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.name == "Beef raw" }
            assertEquals(toCook.sumOf(::share), beef.toComeGrams!!, 1e-6, change)
            assertEquals(20_000.0 - share(dinner), stock(), 1e-6, change)
        }

        http.put("/api/plans/$week/meals/4/dinner/status") { setBody(MealStatus.PLANNED) }
        assertEquals(20_000.0, stock(), 1e-6)
    }

    @Test
    fun aMealTheStockWentDownForIsNotRemovedAndTheStockStaysWhole() = testApplication {
        val http = start().ready()
        http.post("/api/plans/$week/generate")
        val stock = http.stockUpBeef()
        http.put("/api/plans/$week/meals/4/dinner/status") { setBody(MealStatus.EATEN) }
        val plan = http.get("/api/plans/$week").body<WeekPlan>()
        val afterEating = stock()

        // Neither the eaten dinner nor its leftover lunch goes: un-eating must find what was taken for them.
        for ((day, slot, reason) in listOf(
            Triple(4, "dinner", "This meal was eaten already"),
            Triple(5, "lunch", "The dinner before it was eaten, and these leftovers with it"),
        )) {
            val refused = http.delete("/api/plans/$week/meals/$day/$slot")
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertTrue(reason in refused.bodyAsText(), refused.bodyAsText())
        }
        assertEquals(plan, http.get("/api/plans/$week").body<WeekPlan>())
        assertEquals(afterEating, stock())

        // Skipping the lunch says it is not eaten; its share stays taken, as the leftovers were cooked, and it stays.
        http.put("/api/plans/$week/meals/5/lunch/status") { setBody(MealStatus.SKIPPED) }
        assertEquals(afterEating, stock(), 1e-6)
        assertEquals(HttpStatusCode.Conflict, http.delete("/api/plans/$week/meals/5/lunch").status)

        http.put("/api/plans/$week/meals/4/dinner/status") { setBody(MealStatus.PLANNED) }
        assertEquals(20_000.0, stock(), 1e-6)
        // Un-eaten, both can go, and the stock stays as it is.
        assertEquals(HttpStatusCode.OK, http.delete("/api/plans/$week/meals/5/lunch").status)
        assertEquals(HttpStatusCode.OK, http.delete("/api/plans/$week/meals/4/dinner").status)
        assertEquals(20_000.0, stock(), 1e-6)
    }

    @Test
    fun aLeftoverLunchThatKeptTheShareOfARemovedEatenDinnerKeepsIt() = testApplication {
        val http = start().ready()
        http.post("/api/plans/$week/generate")
        val stock = http.stockUpBeef()
        http.put("/api/plans/$week/meals/4/dinner/status") { setBody(MealStatus.EATEN) }
        val eaten = http.get("/api/plans/$week").body<WeekPlan>()
        val dinner = eaten.meal(4, MealSlot.DINNER)!!
        // As stored: without its dinner, the lunch is counted as cooked for itself (cookPortions), as the shopping list counts it.
        fun PlannedMeal?.stored() = this?.copy(cookPortions = null)
        val lunch = eaten.meal(5, MealSlot.LUNCH).stored()!!
        val afterEating = stock()
        // Removed as servers before issue 165 did: the dinner's own share stays taken, and its lunch keeps its share.
        db.planQueries.deleteMeal(userId(), week, 4, MealSlot.DINNER.name)

        // Its share was taken, so it was cooked (issue 169): it cooks nothing, and the shopping list does not buy for it again.
        suspend fun countedAsCooked(case: String) {
            val plan = http.get("/api/plans/$week").body<WeekPlan>()
            assertEquals(0.0, plan.meal(5, MealSlot.LUNCH)!!.cookPortions, case)
            assertEquals(0.0, http.get("/api/days/2026-11-21").body<DayView>().meals.single { it.slot == MealSlot.LUNCH }.cookPortions, case)
            val toCook = plan.days.flatMap { it.meals }.filter { it.status != MealStatus.EATEN }.sumOf { it.cookPortions!! * gramsPerServing(it.title) }
            val beef = http.get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.name == "Beef raw" }
            assertEquals(toCook, beef.toComeGrams!!, 1e-6, case)
        }
        countedAsCooked("its dinner removed")

        // A recipe for the empty dinner, a replan, "Another recipe" and removing all leave that lunch as it is.
        val other = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.title.startsWith("Dinner") && it.id != lunch.recipeId }
        val chosen = http.put("/api/plans/$week/meals/4/dinner") { setBody(MealChoice(other.id)) }.body<WeekPlan>()
        assertEquals(other.id, chosen.meal(4, MealSlot.DINNER)!!.recipeId)
        assertEquals(lunch, chosen.meal(5, MealSlot.LUNCH).stored())
        countedAsCooked("another dinner chosen")
        assertEquals(lunch, http.post("/api/plans/$week/generate").body<WeekPlan>().meal(5, MealSlot.LUNCH).stored())
        countedAsCooked("replanned")
        val refused = http.post("/api/plans/$week/meals/5/lunch/reroll")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("These leftovers were cooked with a dinner that was eaten" in refused.bodyAsText(), refused.bodyAsText())
        assertEquals(HttpStatusCode.Conflict, http.delete("/api/plans/$week/meals/5/lunch").status)
        assertEquals(lunch, http.get("/api/plans/$week").body<WeekPlan>().meal(5, MealSlot.LUNCH).stored())
        assertEquals(afterEating, stock(), 1e-6)

        // Its own recipe chosen for the dinner again: the lunch is that dinner's leftovers once more, but cooked already.
        val again = http.put("/api/plans/$week/meals/4/dinner") { setBody(MealChoice(lunch.recipeId)) }.body<WeekPlan>().meal(4, MealSlot.DINNER)!!
        assertEquals(again.portions, again.cookPortions, "the dinner cooks only its own")
        countedAsCooked("its recipe chosen for the dinner again")

        // Eaten, it takes nothing more; un-eaten, it gives its share back.
        http.put("/api/plans/$week/meals/5/lunch/status") { setBody(MealStatus.EATEN) }
        assertEquals(afterEating, stock(), 1e-6)
        http.put("/api/plans/$week/meals/5/lunch/status") { setBody(MealStatus.PLANNED) }
        assertEquals(20_000.0 - share(dinner), stock(), 1e-6)
    }

    @Test
    fun aPlanChangeWaitsForAnotherConnectionThatWritesMeanwhile() = testApplication {
        val dir = createTempDirectory("plans").toFile()
        val url = "jdbc:sqlite:${File(dir, "plans.db").absolutePath}"
        try {
            val http = start(url).ready()
            http.post("/api/plans/$week/generate")
            // A connection of its own, as the offer refresh and the price lookup have.
            val other = openDatabase(url).second
            other.planQueries.ensureSource("AH")
            fun lastWrite() = other.planQueries.selectSources().executeAsList().single().error

            // While the plan is made (the planner asks for prices), the other connection writes and commits if it can.
            var change = ""
            var writer: Thread? = null
            val userId = userId()
            val ingredients = IngredientRepository(db, FoodRepository(db), userId)
            val plans = PlanRepository(db, userId, RecipeRepository(db, ingredients, userId), ProfileRepository(db, userId), StockRepository(db, FoodRepository(db), userId), pricing = { _, _ ->
                if (writer == null) writer = thread { other.planQueries.recordFailure("written during $change", "AH") }.apply { join(500) }
                Pricing.NONE
            })
            for ((name, run) in listOf<Pair<String, () -> Unit>>(
                "another recipe" to { plans.reroll(week, 1, MealSlot.DINNER) },
                "a replan" to { plans.generate(week) },
            )) {
                change = name
                writer = null
                run()
                // The other write waited for the plan's, and then went through as well.
                writer!!.join()
                assertEquals("written during $name", lastWrite())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theShoppingListFollowsAChosenRecipeAndALeftOutMeal() = testApplication {
        val http = start().ready()
        val before = http.post("/api/plans/$week/generate").body<WeekPlan>()
        suspend fun beef() = http.get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.name == "Beef raw" }
        val old = before.meal(4, MealSlot.DINNER)!!
        val oldLunch = before.meal(5, MealSlot.LUNCH)!!
        val start = beef()

        // Choosing another dinner swaps what its portions need, and its leftover lunch's.
        val chosen = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.title == "Dinner 9" }
        val plan = http.put("/api/plans/$week/meals/4/dinner") { setBody(MealChoice(chosen.id)) }.body<WeekPlan>()
        val dinner = plan.meal(4, MealSlot.DINNER)!!
        val lunch = plan.meal(5, MealSlot.LUNCH)!!
        fun grams(vararg meals: PlannedMeal) = meals.sumOf { it.portions * gramsPerServing(it.title) }
        val chosenState = beef()
        assertEquals(start.grams!! - grams(old, oldLunch) + grams(dinner, lunch), chosenState.grams!!, absoluteTolerance = 1e-6)

        // A ticked row stays ticked, and keeps what was bought.
        http.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(chosenState.key, true)) }
        val ticked = beef()
        assertTrue(ticked.checked)
        assertEquals(chosenState.grams, ticked.boughtGrams)

        // Leaving the dinner out takes its portions off what is still to buy; the leftover lunch, still cooked with it, stays.
        http.put("/api/plans/$week/meals/4/dinner/status") { setBody(MealStatus.SKIPPED) }
        val skipped = beef()
        assertEquals(chosenState.toComeGrams!! - grams(dinner), skipped.toComeGrams!!, absoluteTolerance = 1e-6)
        assertTrue(skipped.checked)
        http.put("/api/plans/$week/meals/4/dinner/status") { setBody(MealStatus.PLANNED) }
        assertEquals(chosenState.toComeGrams, beef().toComeGrams, "eating it after all brings it back")
    }

    @Test
    fun aSkippedMealLeavesTheDayTotals() = testApplication {
        val http = start().ready()
        val plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
        val dinner = plan.meal(2, MealSlot.DINNER)!!

        http.put("/api/plans/$week/meals/2/dinner/status") { setBody(MealStatus.SKIPPED) }
        val after = http.get("/api/plans/$week").body<WeekPlan>()
        assertEquals(plan.days[2].kcal - dinner.kcal, after.days[2].kcal, absoluteTolerance = 1e-6)
        assertEquals(plan.days[2].proteinG - dinner.proteinG, after.days[2].proteinG, absoluteTolerance = 1e-6)
        assertEquals(dinner.title, after.meal(2, MealSlot.DINNER)!!.title, "it stays in the plan, struck through")
        assertEquals(plan.days[1], after.days[1])
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
    fun rerollingALeftoverLunchCooksSomethingElse() = testApplication {
        val http = start().ready()
        val before = http.post("/api/plans/$week/generate").body<WeekPlan>()
        val old = before.days[2].meals.first { it.slot == MealSlot.LUNCH }
        assertTrue(old.leftover)

        val after = http.post("/api/plans/$week/meals/2/lunch/reroll").body<WeekPlan>()
        val lunch = after.days[2].meals.first { it.slot == MealSlot.LUNCH }
        assertNotEquals(old.recipeId, lunch.recipeId)
        assertFalse(lunch.leftover)
        // The dinner before it no longer cooks for that lunch.
        val others = { plan: WeekPlan -> plan.days.flatMap { it.meals }.filterNot { it.day == 2 && it.slot == MealSlot.LUNCH }.map { it.copy(cookPortions = null) } }
        assertEquals(others(before), others(after))
        val dinner = after.days[1].meals.first { it.slot == MealSlot.DINNER }
        assertEquals(dinner.portions, dinner.cookPortions)
    }

    @Test
    fun rerollingAPinnedBreakfastNeedsAnotherBreakfastRecipe() = testApplication {
        val http = start().ready()
        val pin = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.title == "Dinner 0" }
        http.put("/api/recipes/${pin.id}/settings") { setBody(RecipeSettings(RecipeKind.MEAL, listOf(MealSlot.BREAKFAST))) }
        http.put("/api/routines") { setBody(Routines(breakfast = pin.id)) }
        val before = http.post("/api/plans/$week/generate").body<WeekPlan>()

        // The pin is the only breakfast recipe: the swap is refused and the meal kept.
        val refused = http.post("/api/plans/$week/meals/3/breakfast/reroll")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("No other recipe fits this meal" in refused.bodyAsText())
        assertEquals(before, http.get("/api/plans/$week").body<WeekPlan>())

        http.post("/api/recipes") { setBody(RecipeInput("Beef bowl", 1, "300 | g | beef", mealSlots = listOf(MealSlot.BREAKFAST))) }
        val after = http.post("/api/plans/$week/meals/3/breakfast/reroll").body<WeekPlan>()
        val breakfasts = after.days.map { day -> day.meals.first { it.slot == MealSlot.BREAKFAST }.title }
        assertEquals((0..6).map { if (it == 3) "Beef bowl" else "Dinner 0" }, breakfasts, "only the rerolled day changes")
    }

    @Test
    fun aRecipeStillPlannedIsNotDeletedButOneOnlyInPastWeeksIs() = testApplication {
        val http = start().ready()
        val thisWeek = serverToday().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val bowl = http.post("/api/recipes") {
            setBody(RecipeInput("Beef bowl", 1, "300 | g | beef", mealSlots = listOf(MealSlot.BREAKFAST)))
        }.body<RecipeDetail>().summary.id

        // Planned in the current week: refused, naming the days, and nothing is lost.
        val plan = http.post("/api/plans/$thisWeek/generate").body<WeekPlan>()
        val planned = plan.days.filter { day -> day.meals.any { it.recipeId == bowl } }
        assertTrue(planned.isNotEmpty())
        val refused = http.delete("/api/recipes/$bowl")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        val message = refused.bodyAsText()
        val names = planned.map { LocalDate.parse(it.date).format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)) }
        assertTrue(names.all { it in message }, message)
        assertTrue("choose another recipe there first" in message, message)
        assertEquals(HttpStatusCode.OK, http.get("/api/recipes/$bowl").status)

        // Once the days are cleared, or when only a past week uses it, the recipe can go.
        for (day in plan.days.indices) http.delete("/api/plans/$thisWeek/meals/$day/breakfast")
        val past = thisWeek.minusWeeks(3)
        http.post("/api/plans/$past/generate")
        assertEquals(HttpStatusCode.NoContent, http.delete("/api/recipes/$bowl").status)
        assertEquals(HttpStatusCode.NotFound, http.get("/api/recipes/$bowl").status)
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
        http.addWholeBank()
        assertEquals(listOf(MealSlot.SNACK), http.get("/api/recipes/${dinner0.id}").body<RecipeDetail>().summary.mealSlots)

        assertEquals(HttpStatusCode.Conflict, http.put("/api/recipes/${dinner0.id}") { setBody(RecipeInput("X", 1, "1 | g | beef")) }.status)
        assertEquals(HttpStatusCode.Conflict, http.delete("/api/recipes/${dinner0.id}").status)
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/recipes") { setBody(RecipeInput("X", 1, "some | broken line")) }.status)
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/recipes") { setBody(RecipeInput(" ", 1, "1 | g | beef")) }.status)
        // A recipe that is still planned is kept (see aRecipeStillPlanned...), so clear the plan first.
        for (day in 0..6) http.delete("/api/plans/$week/meals/$day/breakfast")
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
        assertEquals("Meat", beef.category, "the shop section of NEVO's meat group")
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
    fun theMealsStillToCookAreThoseWhoseShareTheStockStillHas() {
        fun meal(day: Int, slot: MealSlot, status: MealStatus, leftover: Boolean = false, recipeId: Long = 1) =
            PlannedMeal(day, slot, recipeId, "Stew", 2.0, 800.0, 40.0, leftover = leftover, status = status)
        fun toCook(dinner: MealStatus, lunch: MealStatus) =
            PlanRepository.toCook(listOf(meal(0, MealSlot.DINNER, dinner), meal(1, MealSlot.LUNCH, lunch, leftover = true))).map { it.slot }
        val p = MealStatus.PLANNED
        val e = MealStatus.EATEN
        val s = MealStatus.SKIPPED

        assertEquals(listOf(MealSlot.DINNER, MealSlot.LUNCH), toCook(p, p))
        // Eaten, a dinner was cooked with its leftovers, whatever the lunch says.
        assertEquals(emptyList(), toCook(e, p))
        assertEquals(emptyList(), toCook(e, s))
        // A lunch eaten first was cooked for itself; the dinner still is to cook.
        assertEquals(listOf(MealSlot.DINNER), toCook(p, e))
        // A skipped lunch is cooked with a dinner still to cook; a skipped dinner leaves the lunch to cook for itself.
        assertEquals(listOf(MealSlot.DINNER, MealSlot.LUNCH), toCook(p, s))
        assertEquals(listOf(MealSlot.LUNCH), toCook(s, p))
        assertEquals(emptyList(), toCook(s, s))
        // A leftover lunch of another recipe is no leftover of this dinner: on its own, skipped is not cooked.
        assertEquals(listOf(MealSlot.DINNER), PlanRepository.toCook(listOf(meal(0, MealSlot.DINNER, p), meal(1, MealSlot.LUNCH, s, leftover = true, recipeId = 2))).map { it.slot })
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
        http.addWholeBank()
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
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), generator = null, verifier = TestTokens) }
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
        // 225 kcal a serving against a 760 kcal dinner budget: 1.5 servings, the most the profile's range allows.
        assertEquals(1.5, dinner.portions)
        val lunch = after.days[2].meals.first { it.slot == MealSlot.LUNCH }
        assertEquals(dinner.recipeId, lunch.recipeId, "the next lunch is the new dinner's leftovers")
        assertTrue(lunch.leftover)
        assertEquals(1.5, lunch.portions, "four servings made, 1.5 eaten at dinner, and 1.5 of the 2.5 left is the most allowed")

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

    @Test
    fun aMealSaysHowManyServingsToCookForItAndTheDayAgrees() = testApplication {
        val http = start().ready()
        val plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
        val dinner = plan.meal(1, MealSlot.DINNER)!!
        val lunch = plan.meal(2, MealSlot.LUNCH)!!
        assertTrue(lunch.leftover)
        assertEquals(dinner.portions + lunch.portions, dinner.cookPortions)
        assertEquals(0.0, lunch.cookPortions, "cooked the evening before")

        // The day says the same as the week.
        val day = http.get("/api/days/2026-11-17").body<DayView>().meals.single { it.slot == MealSlot.DINNER }
        assertEquals(dinner.cookPortions, day.cookPortions)
        assertEquals(0.0, http.get("/api/days/2026-11-18").body<DayView>().meals.single { it.slot == MealSlot.LUNCH }.cookPortions)

        // A breakfast is cooked as eaten.
        val bowl = http.post("/api/recipes") { setBody(RecipeInput("Beef bowl", 1, "300 | g | beef")) }.body<RecipeDetail>().summary
        val breakfast = http.put("/api/plans/$week/meals/0/breakfast") { setBody(MealChoice(bowl.id)) }.body<WeekPlan>().meal(0, MealSlot.BREAKFAST)!!
        assertEquals(breakfast.portions, breakfast.cookPortions)
    }

    @Test
    fun aDinnerWhoseLeftoverLunchWasSwappedCooksOnlyItsOwn() = testApplication {
        val http = start().ready()
        val before = http.post("/api/plans/$week/generate").body<WeekPlan>()
        val dinner = before.meal(1, MealSlot.DINNER)!!
        val other = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.id != dinner.recipeId && it.title.startsWith("Dinner") }

        val plan = http.put("/api/plans/$week/meals/2/lunch") { setBody(MealChoice(other.id)) }.body<WeekPlan>()
        assertEquals(dinner.portions, plan.meal(1, MealSlot.DINNER)!!.cookPortions)
        assertEquals(plan.meal(2, MealSlot.LUNCH)!!.portions, plan.meal(2, MealSlot.LUNCH)!!.cookPortions, "a lunch of its own is cooked")
    }

    @Test
    fun aSkippedMealCooksNothingAndTheLunchOfASkippedDinnerCooksItself() = testApplication {
        val http = start().ready()
        val before = http.post("/api/plans/$week/generate").body<WeekPlan>()
        val lunch = before.meal(2, MealSlot.LUNCH)!!

        val plan = http.put("/api/plans/$week/meals/1/dinner/status") { setBody(MealStatus.SKIPPED) }.body<WeekPlan>()
        assertEquals(0.0, plan.meal(1, MealSlot.DINNER)!!.cookPortions)
        assertEquals(lunch.portions, plan.meal(2, MealSlot.LUNCH)!!.cookPortions, "the shopping list still buys for it")

        val both = http.put("/api/plans/$week/meals/2/lunch/status") { setBody(MealStatus.SKIPPED) }.body<WeekPlan>()
        assertEquals(0.0, both.meal(2, MealSlot.LUNCH)!!.cookPortions)
    }

    @Test
    fun theServingsToCookAddUpToWhatTheShoppingListBuys() = testApplication {
        val http = start().ready()
        val plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
        val shopping = http.get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.name == "Beef raw" }

        // The recipe's own amounts (grams of beef for its servings) scaled to what each meal cooks.
        var expected = 0.0
        for (meal in plan.days.flatMap { it.meals }) {
            val recipe = http.get("/api/recipes/${meal.recipeId}").body<RecipeDetail>()
            val grams = recipe.ingredients.sumOf { it.grams ?: 0.0 }
            expected += grams * meal.cookPortions!! / recipe.summary.servings!!
        }
        assertEquals(shopping.grams!!, expected, absoluteTolerance = 1e-6)
    }

    /**
     * What the shopping list still counts of an ingredient is its recipes' grams for [PlannedMeal.cookPortions]
     * servings over the meals not eaten yet, whatever happened to a dinner (day 1) and its leftover lunch (day 2).
     * Each case gets a week of its own.
     */
    @Test
    fun theServingsStillToCookAddUpToWhatTheShoppingListStillCounts() = testApplication {
        val http = start().ready()
        val recipes = HashMap<Long, RecipeDetail>()
        suspend fun recipe(id: Long) = recipes.getOrPut(id) { http.get("/api/recipes/$id").body() }
        // Every change must go through, or its case would not be the one tested.
        fun HttpResponse.ok() = assertEquals(HttpStatusCode.OK, status, call.request.url.toString())
        suspend fun mark(week: String, day: Int, slot: String, status: MealStatus) = http.put("/api/plans/$week/meals/$day/$slot/status") { setBody(status) }.ok()
        val dinners = http.get("/api/recipes").body<List<RecipeSummary>>().filter { it.title.startsWith("Dinner") }
        var weeks = 0L

        suspend fun check(case: String, change: suspend (String, WeekPlan) -> Unit) {
            val at = LocalDate.parse(week).plusWeeks(weeks++).toString()
            val plan = http.post("/api/plans/$at/generate").body<WeekPlan>()
            assertTrue(plan.meal(2, MealSlot.LUNCH)!!.leftover, case)
            change(at, plan)
            var expected = 0.0
            for (meal in http.get("/api/plans/$at").body<WeekPlan>().days.flatMap { it.meals }.filter { it.status != MealStatus.EATEN }) {
                val detail = recipe(meal.recipeId)
                expected += detail.ingredients.sumOf { it.grams ?: 0.0 } * meal.cookPortions!! / detail.summary.servings!!
            }
            val beef = http.get("/api/plans/$at/shopping").body<ShoppingList>().items.single { it.name == "Beef raw" }
            assertEquals(expected, beef.toComeGrams!!, 1e-6, case)
        }

        for (dinner in MealStatus.entries) for (lunch in MealStatus.entries) {
            check("dinner $dinner, lunch $lunch") { at, _ -> mark(at, 1, "dinner", dinner); mark(at, 2, "lunch", lunch) }
        }
        check("dinner removed") { at, _ -> http.delete("/api/plans/$at/meals/1/dinner").ok() }
        check("lunch removed") { at, _ -> http.delete("/api/plans/$at/meals/2/lunch").ok() }
        check("the dinner's recipe chosen for the lunch") { at, plan ->
            http.put("/api/plans/$at/meals/2/lunch") { setBody(MealChoice(plan.meal(1, MealSlot.DINNER)!!.recipeId)) }.ok()
        }
        check("a dinner chosen while its leftover lunch was eaten") { at, plan ->
            mark(at, 2, "lunch", MealStatus.EATEN)
            val other = dinners.first { it.id != plan.meal(1, MealSlot.DINNER)!!.recipeId }
            http.put("/api/plans/$at/meals/1/dinner") { setBody(MealChoice(other.id)) }.ok()
        }
        check("a skipped dinner rerolled") { at, _ -> mark(at, 1, "dinner", MealStatus.SKIPPED); http.post("/api/plans/$at/meals/1/dinner/reroll").ok() }
        check("dinner eaten, then the week replanned (its leftover lunch is kept)") { at, _ ->
            mark(at, 1, "dinner", MealStatus.EATEN)
            http.post("/api/plans/$at/generate").ok()
        }
        check("lunch swapped, dinner eaten, then the week replanned (no leftovers of it come back)") { at, _ ->
            http.post("/api/plans/$at/meals/2/lunch/reroll").ok()
            mark(at, 1, "dinner", MealStatus.EATEN)
            http.post("/api/plans/$at/generate").ok()
        }
    }

    @Test
    fun aRerolledDinnerCooksForItsNewLeftoverLunch() = testApplication {
        val http = start().ready()
        val oldLunch = http.post("/api/plans/$week/generate").body<WeekPlan>().meal(2, MealSlot.LUNCH)!!
        val after = http.post("/api/plans/$week/meals/1/dinner/reroll").body<WeekPlan>()
        val dinner = after.meal(1, MealSlot.DINNER)!!
        val lunch = after.meal(2, MealSlot.LUNCH)!!
        assertNotEquals(oldLunch.recipeId, lunch.recipeId)
        assertEquals(dinner.portions + lunch.portions, dinner.cookPortions)
        assertEquals(0.0, lunch.cookPortions)
    }

    @Test
    fun aRerolledDinnerCooksOnlyItsOwnWhenTheOldLeftoverLunchWasEaten() = testApplication { rerollKeepingLunch(MealStatus.EATEN) }

    @Test
    fun aRerolledDinnerCooksOnlyItsOwnWhenTheOldLeftoverLunchWasSkipped() = testApplication { rerollKeepingLunch(MealStatus.SKIPPED) }

    /**
     * The random check from the review of PR #167, kept (issue 169). Two weeks eat from one stock entry, which
     * counts from the first; fixed seeds give sequences of what the app can do to the lunches and dinners of
     * their first three days (few enough meals that the steps meet: two dinners with the next day's lunch):
     * mark eaten, skipped or planned, "Another recipe", choose one, remove, plan the week, lock or unlock, write
     * one. After every step the stock left plus what the meals recorded taking is what was stocked up, and no
     * leftover lunch of an eaten dinner goes without the share it was cooked with (issue 169). After each
     * sequence the shopping lists count what the meals still to cook need, and un-eating every meal in random
     * order makes the stock whole again with nothing recorded. A failure names the seed and the step.
     */
    @Test
    fun randomChangesToTwoWeeksNeverLoseOrMakeStock() = testApplication {
        val http = start().ready()
        val weeks = listOf(week, LocalDate.parse(week).plusWeeks(1).toString())
        val beef = http.get("/api/ingredients").body<List<Ingredient>>().first { it.name == "Beef raw" }.id
        val userId = userId()
        val stocked = 100_000.0 // more than both weeks can eat, so every eaten meal records what it took
        StockRepository(db, FoodRepository(db), userId).putOnHandFrom(beef, LocalDate.parse(week), stocked, StockSource.STOCK_UP)
        val dinners = http.get("/api/recipes").body<List<RecipeSummary>>().filter { it.title.startsWith("Dinner") }
        val slots = listOf(MealSlot.LUNCH, MealSlot.DINNER)
        val recipes = HashMap<Long, RecipeDetail>()
        suspend fun recipe(id: Long) = recipes.getOrPut(id) { http.get("/api/recipes/$id").body() }

        fun left() = db.pantryQueries.selectStock(userId, weeks.last(), listOf(beef)).executeAsList().sumOf { it.grams!! }
        fun takenFor(week: String, meal: PlannedMeal) = db.pantryQueries.selectStockUses(userId, week, meal.day.toLong(), meal.slot.name, MealRef.MEAL).executeAsList()
        suspend fun plans() = weeks.associateWith { http.get("/api/plans/$it").body<WeekPlan>().days.flatMap { day -> day.meals } }

        /** Stock plus recorded use is what was stocked up, and every leftover lunch of an eaten dinner holds its share. */
        suspend fun check(where: String) {
            val plans = plans()
            val used = plans.entries.sumOf { (week, meals) -> meals.sumOf { meal -> takenFor(week, meal).sumOf { it.grams!! } } }
            assertEquals(stocked, left() + used, 1e-6, "$where: stock ${left()} + recorded use $used")
            for ((week, meals) in plans) for (lunch in meals.filter { it.slot == MealSlot.LUNCH && it.leftover }) {
                val dinner = meals.firstOrNull { it.day == lunch.day - 1 && it.slot == MealSlot.DINNER && !it.leftover && it.recipeId == lunch.recipeId }
                if (dinner?.status == MealStatus.EATEN) {
                    assertTrue(takenFor(week, lunch).isNotEmpty(), "$where: in the week of $week, $lunch is the leftovers of an eaten dinner that took nothing for it")
                }
            }
        }

        val seeds = 1L..8L
        for (seed in seeds) {
            val random = Random(seed)
            weeks.forEach { http.post("/api/plans/$it/generate") }
            repeat(60) { step ->
                val at = weeks.random(random)
                val day = random.nextInt(3)
                val slot = slots.random(random).name.lowercase()
                val meal = "/api/plans/$at/meals/$day/$slot"
                val (action, response) = when (random.nextInt(8)) {
                    0, 1 -> MealStatus.entries.random(random).let { status -> "mark $status" to http.put("$meal/status") { setBody(status) } }
                    2 -> "another recipe" to http.post("$meal/reroll")
                    3 -> dinners.random(random).let { "choose ${it.title}" to http.put(meal) { setBody(MealChoice(it.id)) } }
                    4 -> "remove" to http.delete(meal)
                    5 -> "plan the week" to http.post("/api/plans/$at/generate")
                    6 -> random.nextBoolean().let { lock -> (if (lock) "lock" to http.put("$meal/lock") else "unlock" to http.delete("$meal/lock")) }
                    else -> "write a recipe" to http.post("$meal/generate") { setBody(GenerateRequest(wish = "beef")) }
                }
                val where = "seed $seed, step $step: $action at $at day $day $slot"
                assertTrue(response.status.value in setOf(200, 404, 409, 422), "$where: ${response.status} ${response.bodyAsText()}")
                check(where)
            }

            // What the shopping list still counts is the recipes' grams for the servings still to cook.
            for ((at, meals) in plans()) {
                var expected = 0.0
                for (meal in meals.filter { it.status != MealStatus.EATEN }) {
                    val detail = recipe(meal.recipeId)
                    expected += detail.ingredients.sumOf { it.grams ?: 0.0 } * meal.cookPortions!! / detail.summary.servings!!
                }
                val listed = http.get("/api/plans/$at/shopping").body<ShoppingList>().items.singleOrNull { it.name == "Beef raw" }?.toComeGrams ?: 0.0
                assertEquals(expected, listed, 1e-6, "seed $seed: the shopping list of the week of $at")
            }

            // Un-eaten in any order, every meal gives back exactly what it took.
            val eaten = plans().flatMap { (at, meals) -> meals.filter { it.status == MealStatus.EATEN }.map { at to it } }.shuffled(random)
            for ((at, meal) in eaten) {
                http.put("/api/plans/$at/meals/${meal.day}/${meal.slot.name.lowercase()}/status") { setBody(MealStatus.PLANNED) }
                check("seed $seed, un-eating ${meal.slot} of day ${meal.day} in the week of $at")
            }
            assertEquals(stocked, left(), 1e-6, "seed $seed: the stock after un-eating every meal")
        }
    }

    /**
     * The planner picks on its own from my recipes in the rotation only (issue 205), however well another
     * would score; any of my recipes can be placed by hand, and a recipe only in the bank cannot.
     */
    @Test
    fun theWeekIsPlannedFromTheRotationAndAnyOfMyRecipesByHand() = testApplication {
        val http = start().ready()
        val dinners = http.get("/api/recipes").body<List<RecipeSummary>>().filter { it.title.startsWith("Dinner") }.associate { it.title to it.id }
        val rotation = listOf("Dinner 0", "Dinner 1", "Dinner 2").map(dinners::getValue).toSet()
        http.put("/api/recipes/rotation") { setBody(ManyRotationInput(dinners.values - rotation, inRotation = false)) }
        // Rated best of all and the closest to a dinner's calories: the planner would take it if it could.
        val best = dinners.getValue("Dinner 9")
        http.put("/api/recipes/$best/rating") { setBody(RatingInput(5)) }
        fun WeekPlan.recipes() = days.flatMap { day -> day.meals.map { it.recipeId } }.toSet()

        repeat(3) {
            val plan = http.post("/api/plans/$week/generate").body<WeekPlan>()
            assertEquals(14, plan.days.sumOf { it.meals.size })
            assertEquals(rotation, plan.recipes(), "only the rotation, all of it")
        }
        val rerolled = http.post("/api/plans/$week/meals/2/dinner/reroll")
        assertEquals(HttpStatusCode.OK, rerolled.status)
        assertTrue(rerolled.body<WeekPlan>().recipes().all { it in rotation }, "nor by another recipe")

        // By hand any of mine, and replanning keeps it.
        val chosen = http.put("/api/plans/$week/meals/4/dinner") { setBody(MealChoice(best)) }
        assertEquals(HttpStatusCode.OK, chosen.status)
        val replanned = http.post("/api/plans/$week/generate").body<WeekPlan>()
        assertEquals(best, replanned.meal(4, MealSlot.DINNER)!!.recipeId)
        assertEquals(rotation + best, replanned.recipes())

        // A recipe leaving the rotation leaves the meals planned with it as they are.
        http.put("/api/recipes/rotation") { setBody(ManyRotationInput(rotation.toList(), inRotation = false)) }
        assertEquals(replanned, http.get("/api/plans/$week").body<WeekPlan>())

        // A recipe only in the bank is added first.
        val banked = dinners.getValue("Dinner 8")
        http.put("/api/recipes/$banked/added") { setBody(AddedInput(added = false)) }
        val refused = http.put("/api/plans/$week/meals/3/dinner") { setBody(MealChoice(banked)) }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        val error = refused.body<Map<String, String>>().getValue("error")
        assertEquals("Add \"Dinner 8\" to your recipes first", error)
    }

    /** With nothing to pick from, the planner leaves the slots empty and says nothing is wrong. */
    @Test
    fun anEmptyRotationOrNoRecipesLeavesTheSlotsEmpty() = testApplication {
        val http = start()
        http.post("/api/sync/cooking")
        http.put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        suspend fun plan() = http.post("/api/plans/$week/generate").also { assertEquals(HttpStatusCode.OK, it.status) }.body<WeekPlan>()

        // The site's recipes are in the bank only: no recipes of mine.
        assertEquals(emptyList(), http.get("/api/recipes").body<List<RecipeSummary>>())
        val none = plan()
        assertEquals(MealSlot.entries, none.emptySlots)
        assertTrue(none.days.all { it.meals.isEmpty() })

        // All of them mine, none in the rotation.
        http.addWholeBank()
        val mine = http.get("/api/recipes").body<List<RecipeSummary>>().map { it.id }
        http.put("/api/recipes/rotation") { setBody(ManyRotationInput(mine, inRotation = false)) }
        val empty = plan()
        assertEquals(MealSlot.entries, empty.emptySlots)
        assertTrue(empty.days.all { it.meals.isEmpty() })
    }

    /** The lunch is history and stays: it is the old dinner's leftovers, so nothing of the new dinner's is cooked for it, and it cooks nothing itself. */
    private suspend fun ApplicationTestBuilder.rerollKeepingLunch(status: MealStatus) {
        val http = start().ready()
        val oldLunch = http.post("/api/plans/$week/generate").body<WeekPlan>().meal(2, MealSlot.LUNCH)!!
        http.put("/api/plans/$week/meals/2/lunch/status") { setBody(status) }

        val after = http.post("/api/plans/$week/meals/1/dinner/reroll").body<WeekPlan>()
        val dinner = after.meal(1, MealSlot.DINNER)!!
        assertEquals(oldLunch.recipeId, after.meal(2, MealSlot.LUNCH)!!.recipeId, "kept")
        assertEquals(dinner.portions, dinner.cookPortions)
        assertEquals(0.0, after.meal(2, MealSlot.LUNCH)!!.cookPortions)
    }
}
