package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.ManyAddedInput
import dev.tobins.mealplanner.api.ManyRotationInput
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RatingInput
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.RotationInput
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.Sex
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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The recipe bank (issue 205): adding a cooking-site recipe to my recipes, putting it back, and the rotation. */
class RecipeBankTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|10|Rundvlees rauw|Beef raw||per 100g|150|20|4|2|0|0|0|50",
    ).joinToString("\r\n")

    /** The Monday of this week: a meal planned in it keeps its recipe in my recipes. */
    private val thisWeek = serverToday().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** A server whose cooking site has three beef dinners and a sauce, and a client of its owner with a profile. */
    private suspend fun ApplicationTestBuilder.start(): HttpClient {
        val repo = createTempDirectory("cooking").toFile()
        for ((path, title) in listOf("recipes/stew.md" to "Stew", "recipes/curry.md" to "Curry", "recipes/chili.md" to "Chili", "sauces/salsa.md" to "Salsa")) {
            File(repo, "content/$path").apply { parentFile.mkdirs() }
                .writeText("---\ntitle: $title\n---\n```ingredients\nservings: 4\n1200 | g | beef\n```")
        }
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = repo,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, verifier = TestTokens) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
        http.put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        http.post("/api/sync/cooking")
        return http
    }

    private suspend fun HttpClient.mine() = get("/api/recipes").body<List<RecipeSummary>>()

    private suspend fun HttpClient.bank() = get("/api/recipes/bank").body<List<RecipeSummary>>()

    /** The id of the recipe [title], in my recipes or the bank. */
    private suspend fun HttpClient.id(title: String) = (mine() + bank()).single { it.title == title }.id

    private suspend fun HttpClient.add(title: String, inRotation: Boolean = true) =
        put("/api/recipes/${id(title)}/added") { setBody(AddedInput(added = true, inRotation = inRotation)) }

    private suspend fun HttpClient.putBack(title: String) = put("/api/recipes/${id(title)}/added") { setBody(AddedInput(added = false)) }

    /** The message of a refusal, `{"error": ...}`. */
    private suspend fun HttpResponse.error() = body<Map<String, String>>().getValue("error")

    @Test
    fun aDecimalCommaInAnAmountReadsAsHalfAKiloAndAnUnreadableLineIsNamed() = testApplication {
        val http = start()
        val saved = http.post("/api/recipes") { setBody(RecipeInput("Kwark", 2, "200 | g | magere kwark\n0,5 | kg | banaan")) }
        assertEquals(HttpStatusCode.Created, saved.status)
        assertEquals(listOf(0.5, 200.0), saved.body<RecipeDetail>().ingredients.map { it.line.amount }.sortedBy { it })

        val refused = http.post("/api/recipes") { setBody(RecipeInput("Kwark", 2, "200 | g | kwark\n1 of 2 | kg | banaan\n3 | st | ei")) }
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals("Unreadable ingredient line: 1 of 2 | kg | banaan", refused.error())
    }

    @Test
    fun addingMovesARecipeFromTheBankToMyRecipesAndPuttingItBackKeepsItsRating() = testApplication {
        val http = start()
        assertEquals(emptyList(), http.mine())
        assertEquals(listOf("Chili", "Curry", "Salsa", "Stew"), http.bank().map { it.title })
        val inBank = http.bank().single { it.title == "Stew" }
        assertEquals(listOf(false, false), listOf(inBank.added, inBank.inRotation))
        assertEquals(false, inBank.nutrition?.complete, "nothing of the bank is linked yet, and the summary says so")
        assertEquals(inBank.id, http.get("/api/recipes/${inBank.id}").body<RecipeDetail>().summary.id, "a bank recipe can be read")
        http.put("/api/recipes/${inBank.id}/rating") { setBody(RatingInput(4)) }

        // Added: one of mine, in the rotation, with its exact-name ingredients linked as a sync would.
        val added = http.add("Stew").body<RecipeDetail>()
        assertEquals(listOf(true, true), listOf(added.summary.added, added.summary.inRotation))
        assertEquals("Beef raw", added.ingredients.single().ingredientName)
        assertEquals(true, added.summary.nutrition?.complete)
        assertEquals(listOf("Stew"), http.mine().map { it.title })
        assertFalse(http.bank().any { it.title == "Stew" })
        assertEquals(added.summary, http.add("Stew").body<RecipeDetail>().summary, "adding it again changes nothing")

        // Put back: in the bank again, out of the rotation, its rating kept for when it is added again.
        val back = http.putBack("Stew").body<RecipeDetail>()
        assertEquals(listOf(false, false, 4), listOf(back.summary.added, back.summary.inRotation, back.summary.rating))
        assertEquals(emptyList(), http.mine())
        assertEquals(listOf("Chili", "Curry", "Salsa", "Stew"), http.bank().map { it.title })
        assertEquals(HttpStatusCode.OK, http.putBack("Stew").status, "putting back what is in the bank changes nothing")
        assertEquals(4, http.add("Stew").body<RecipeDetail>().summary.rating)
    }

    @Test
    fun aRecipeIsNotPutBackWhilePlannedOrARoutine() = testApplication {
        val http = start()
        http.add("Stew")
        val stew = http.id("Stew")
        http.put("/api/plans/$thisWeek/meals/2/dinner") { setBody(MealChoice(stew)) }

        val planned = http.putBack("Stew")
        assertEquals(HttpStatusCode.Conflict, planned.status)
        val day = thisWeek.plusDays(2).format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH))
        assertTrue(day in planned.error(), planned.error())
        assertEquals(listOf("Stew"), http.mine().map { it.title }, "still mine")

        // Its meal gone, it can go back.
        http.delete("/api/plans/$thisWeek/meals/2/dinner")
        assertEquals(HttpStatusCode.OK, http.putBack("Stew").status)

        // A routine stays mine and in the rotation until another is chosen.
        http.add("Stew")
        http.put("/api/routines") { setBody(Routines(breakfast = stew)) }
        val routine = http.putBack("Stew")
        assertEquals(HttpStatusCode.Conflict, routine.status)
        assertTrue("breakfast routine" in routine.error(), routine.error())
        assertEquals(HttpStatusCode.Conflict, http.put("/api/recipes/$stew/rotation") { setBody(RotationInput(false)) }.status)
        assertEquals(HttpStatusCode.Conflict, http.add("Stew", inRotation = false).status)
        assertEquals(listOf(true, true), http.mine().single().let { listOf(it.added, it.inRotation) })
        http.put("/api/routines") { setBody(Routines()) }
        assertEquals(false, http.put("/api/recipes/$stew/rotation") { setBody(RotationInput(false)) }.body<RecipeDetail>().summary.inRotation)
    }

    @Test
    fun manyAreChangedAtOnceOrNoneAre() = testApplication {
        val http = start()
        val (stew, curry, chili) = listOf("Stew", "Curry", "Chili").map { http.id(it) }

        assertEquals(HttpStatusCode.NotFound, http.put("/api/recipes/added") { setBody(ManyAddedInput(listOf(stew, curry, 99999), added = true)) }.status)
        assertEquals(emptyList(), http.mine(), "nothing added")
        val mine = http.put("/api/recipes/added") { setBody(ManyAddedInput(listOf(stew, curry), added = true)) }.body<List<RecipeSummary>>()
        assertEquals(listOf("Curry", "Stew"), mine.map { it.title })
        assertTrue(mine.all { it.added && it.inRotation })

        val out = http.put("/api/recipes/rotation") { setBody(ManyRotationInput(listOf(stew, curry), inRotation = false)) }.body<List<RecipeSummary>>()
        assertTrue(out.none { it.inRotation })
        // The chili is only in the bank: neither it nor the stew goes in the rotation.
        val bankOne = http.put("/api/recipes/rotation") { setBody(ManyRotationInput(listOf(stew, chili), inRotation = true)) }
        assertEquals(HttpStatusCode.Conflict, bankOne.status)
        assertTrue("Add \"Chili\" to your recipes first" in bankOne.error(), bankOne.error())
        assertTrue(http.mine().none { it.inRotation })
        assertEquals(HttpStatusCode.Conflict, http.put("/api/recipes/$chili/rotation") { setBody(RotationInput(true)) }.status)

        // The curry is planned: neither it nor the stew goes back to the bank.
        http.put("/api/plans/$thisWeek/meals/0/dinner") { setBody(MealChoice(curry)) }
        assertEquals(HttpStatusCode.Conflict, http.put("/api/recipes/added") { setBody(ManyAddedInput(listOf(stew, curry), added = false)) }.status)
        assertEquals(listOf("Curry", "Stew"), http.mine().map { it.title })
        val left = http.put("/api/recipes/added") { setBody(ManyAddedInput(listOf(stew), added = false)) }.body<List<RecipeSummary>>()
        assertEquals(listOf("Curry"), left.map { it.title })
    }

    @Test
    fun aRecipeMadeInTheAppIsMineAndInTheRotationAndIsDeletedRatherThanPutBack() = testApplication {
        val http = start()
        val own = http.post("/api/recipes") { setBody(RecipeInput("Own stew", 2, "600 | g | beef")) }.body<RecipeDetail>().summary
        assertEquals(Triple(RecipeSource.MANUAL, true, true), Triple(own.source, own.added, own.inRotation))
        assertEquals(listOf("Own stew"), http.mine().map { it.title })
        assertFalse(http.bank().any { it.id == own.id })

        val putBack = http.put("/api/recipes/${own.id}/added") { setBody(AddedInput(added = false)) }
        assertEquals(HttpStatusCode.Conflict, putBack.status)
        assertTrue("delete it instead" in putBack.error(), putBack.error())
        assertEquals(listOf("Own stew"), http.mine().map { it.title })
        assertEquals(HttpStatusCode.NoContent, http.delete("/api/recipes/${own.id}").status)
    }

    @Test
    fun savingSettingsAsAReleasedAppDoesNotChangeWhetherARecipeIsAddedOrInTheRotation() = testApplication {
        val http = start()
        val stew = http.add("Stew", inRotation = false).body<RecipeDetail>().summary
        val saved = http.put("/api/recipes/${stew.id}/settings") { setBody(RecipeSettings(RecipeKind.MEAL, listOf(MealSlot.LUNCH))) }.body<RecipeDetail>()
        assertEquals(listOf(true, false), listOf(saved.summary.added, saved.summary.inRotation))
        val chili = http.id("Chili")
        val inBank = http.put("/api/recipes/$chili/settings") { setBody(RecipeSettings(RecipeKind.MEAL, listOf(MealSlot.DINNER))) }.body<RecipeDetail>()
        assertEquals(listOf(false, false), listOf(inBank.summary.added, inBank.summary.inRotation))
    }

    @Test
    fun pinningARoutinePutsItInTheRotationAndABankRecipeCannotBePinned() = testApplication {
        val http = start()
        val stew = http.add("Stew", inRotation = false).body<RecipeDetail>().summary
        http.put("/api/routines") { setBody(Routines(snack = stew.id)) }
        assertTrue(http.mine().single().inRotation)

        val chili = http.id("Chili")
        assertEquals(HttpStatusCode.Conflict, http.put("/api/routines") { setBody(Routines(snack = stew.id, breakfast = chili)) }.status)
        assertEquals(Routines(snack = stew.id), http.get("/api/routines").body<Routines>())
        assertEquals(HttpStatusCode.NotFound, http.put("/api/routines") { setBody(Routines(breakfast = 99999)) }.status)
    }
}
