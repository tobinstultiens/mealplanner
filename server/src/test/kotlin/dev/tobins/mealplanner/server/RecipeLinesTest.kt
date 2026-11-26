package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MappedLine
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.StandardSwitch
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.api.UnmappedText
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.recipes.PlainLines
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
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A recipe's lines that know their ingredient, lines in the API, and a recipe taken from the cooking site that is the
 * account's own (issue 340).
 */
class RecipeLinesTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|10|Rundvlees rauw|Beef raw||per 100g|150|20|4|2|0|0|0|50",
        "NEVO-Online 2025 9.0|Graanproducten|Cereal products|5|Rijst wit rauw|Rice white raw||per 100g|350|7|1|0|78|0|1|5",
    ).joinToString("\r\n")

    private val week = "2026-11-16"

    /** The stew of the cooking site: tags, two groups and a line of the unit as ingredient. */
    private val stewPage = "---\ntitle: Stew\ntags: [winter, slow]\n---\n```ingredients\nservings: 4\n# Stew\n1200 | g | beef\n1 | onion | large\n" +
        "# To serve\n400 | g | rice\n| | salt to taste\n```\nBrown the beef.\n"

    private lateinit var repo: File
    private lateinit var db: Database

    /** A server whose cooking site has the stew and a curry, and a client of its owner with a profile. */
    private suspend fun ApplicationTestBuilder.start(): HttpClient {
        repo = createTempDirectory("cooking").toFile()
        page("recipes/stew.md", stewPage)
        page("recipes/curry.md", "---\ntitle: Curry\n---\n```ingredients\nservings: 2\n300 | g | beef\n```")
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = repo,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens, today = { LocalDate.parse(week) }) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
        http.put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        return http
    }

    private fun page(path: String, text: String) = File(repo, "content/$path").apply { parentFile.mkdirs() }.writeText(text)

    private suspend fun HttpClient.ingredient(name: String, nevoCode: Int, pricePerKg: Double) =
        post("/api/ingredients") { setBody(IngredientInput(name, nevoCode, pricePerKg = pricePerKg)) }.body<Ingredient>()

    private suspend fun HttpClient.recipe(id: Long) = get("/api/recipes/$id").body<RecipeDetail>()

    private suspend fun HttpClient.sync() = post("/api/sync/cooking").body<SyncResult>()

    private suspend fun HttpClient.bank() = get("/api/recipes/bank").body<List<RecipeSummary>>()

    private suspend fun HttpResponse.error() = body<Map<String, String>>().getValue("error")

    /** The texts the account has an alias for. */
    private fun aliases(): Set<String> = db.ingredientQueries.selectAliases(db.userQueries.selectBySubject("sub-t").executeAsOne().id)
        .executeAsList().mapTo(HashSet()) { it.text }

    /** [recipe] as it reads, as the body that saves it again: each line's own fields, its ingredient among them. */
    private fun asInput(recipe: RecipeDetail) = RecipeInput(
        title = recipe.summary.title,
        servings = recipe.summary.servings ?: 1,
        instructions = recipe.instructions,
        tags = recipe.summary.tags,
        mealSlots = recipe.summary.mealSlots,
        lines = recipe.ingredients.map { RecipeLineInput(it.line.amount, it.line.unit, it.line.item, it.line.ingredientId, it.line.group) },
    )

    @Test
    fun aLineSavedWithItsIngredientIsThatIngredientWhateverItsTextAndTheAliasesSay() = testApplication {
        val http = start()
        val beef = http.ingredient("Beef", 10, 10.0)
        val rice = http.ingredient("Rice", 5, 2.0)
        // Not even the default catalogue's aliases.
        for (text in aliases()) http.delete("/api/mappings?text=${text.encodeURLParameter()}")
        val lines = listOf(RecipeLineInput(400.0, "g", "lean ground meat", beef.id), RecipeLineInput(200.0, "g", "grains, cooked", rice.id))
        val made = http.post("/api/recipes") { setBody(RecipeInput("Bowl", 2, lines = lines)) }
        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        val bowl = made.body<RecipeDetail>()
        assertEquals(listOf(beef.id to beef.id, rice.id to rice.id), bowl.ingredients.map { it.line.ingredientId to it.ingredientId })
        assertEquals(emptySet(), aliases(), "no alias in the account")
        assertTrue(http.get("/api/mappings/unmapped").body<List<UnmappedText>>().isEmpty(), "nothing left to link")

        // The text changed, the ingredient kept.
        val renamed = asInput(bowl).let { it.copy(lines = it.lines!!.map { line -> if (line.ingredientId == beef.id) line.copy(text = "minced beef, browned") else line }) }
        val edited = http.put("/api/recipes/${bowl.summary.id}") { setBody(renamed) }.body<RecipeDetail>()
        assertEquals(listOf("minced beef, browned" to beef.id, "grains, cooked" to rice.id), edited.ingredients.map { it.line.item to it.ingredientId })

        // An alias of that text to the rice, and then none: the line is the beef throughout.
        http.put("/api/mappings") { setBody(MappingRequest("minced beef, browned", ingredientId = rice.id)) }
        assertEquals("minced beef, browned", aliases().single { it.startsWith("minced") })
        assertEquals(listOf(beef.id, rice.id), http.recipe(bowl.summary.id).ingredients.map { it.ingredientId })
        http.delete("/api/mappings?text=${"minced beef, browned".encodeURLParameter()}")
        assertTrue(aliases().none { it.startsWith("minced") })
        assertEquals(listOf(beef.id, rice.id), http.recipe(bowl.summary.id).ingredients.map { it.ingredientId })
    }

    /** Nutrition, cost, the shopping list, what a meal had in it and a standard meal's staples all read the lines' ingredients. */
    @Test
    fun everythingThatReadsTheLinesUsesTheirIngredients() = testApplication {
        val http = start()
        val beef = http.ingredient("Beef", 10, 10.0)
        val rice = http.ingredient("Rice", 5, 2.0)
        val lines = listOf(RecipeLineInput(400.0, "g", "mystery meat", beef.id), RecipeLineInput(200.0, "g", "grains", rice.id))
        val bowl = http.post("/api/recipes") { setBody(RecipeInput("Bowl", 2, lines = lines)) }.body<RecipeDetail>().summary

        // A serving is 200 g of beef (300 kcal, 40 g protein) and 100 g of rice (350 kcal, 7 g protein).
        val nutrition = bowl.nutrition!!
        assertEquals(Triple(true, 650.0, 47.0), Triple(nutrition.complete, nutrition.perServing.kcal, nutrition.perServing.proteinG))
        val cost = bowl.cost!!
        assertTrue(abs(cost.perServing - 2.2) < 0.01 && cost.estimated.isEmpty(), "2 euros of beef and 20 cents of rice: $cost")

        assertEquals(HttpStatusCode.OK, http.put("/api/plans/$week/meals/2/DINNER") { setBody(MealChoice(bowl.id)) }.status)
        val list = http.get("/api/plans/$week/shopping").body<ShoppingList>()
        assertEquals(setOf("i${beef.id}", "i${rice.id}"), list.items.map { it.key }.toSet(), "rows of the ingredients, none of the texts")
        assertEquals(listOf(beef.id, rice.id), http.get("/api/plans/$week/meals/2/DINNER/lines").body<MealLines>().lines.map { it.ingredientId })

        val standard = http.put("/api/recipes/${bowl.id}/standard") { setBody("""{"standard":true}""") }.body<StandardSwitch>()
        assertEquals(listOf("Beef", "Rice") to emptyList(), standard.addedStaples to standard.unlinked)
    }

    @Test
    fun aRecipeReadAndSavedAsItWasReadIsUnchanged() = testApplication {
        val http = start()
        val beef = http.ingredient("Beef", 10, 10.0)
        val lines = listOf(
            RecipeLineInput(500.0, "g", "stewing beef, cubed", beef.id, group = "Stew"),
            RecipeLineInput(1.0, "onion", "large", group = "Stew"),
            RecipeLineInput(0.5, "tsp", "cumin", group = "Spices"),
            RecipeLineInput(null, null, "salt to taste", group = "Spices"),
        )
        val made = http.post("/api/recipes") {
            setBody(RecipeInput("Stew", 2, instructions = "Brown the beef.", tags = listOf("winter", "slow"), lines = lines))
        }.body<RecipeDetail>()
        assertEquals(listOf("Stew", "Stew", "Spices", "Spices"), made.ingredients.map { it.line.group })

        val saved = http.put("/api/recipes/${made.summary.id}") { setBody(asInput(made)) }.body<RecipeDetail>()
        assertEquals(made, saved)
        assertEquals(made, http.recipe(made.summary.id))

        // A recipe of the site, taken: its groups and tags as the site had them.
        http.sync()
        val stew = http.bank().single { it.title == "Stew" }
        val taken = http.put("/api/recipes/${stew.id}/added") { setBody(AddedInput(added = true)) }.body<RecipeDetail>()
        assertEquals(listOf("winter", "slow") to listOf("Stew", "Stew", "To serve", "To serve"), taken.summary.tags to taken.ingredients.map { it.line.group })
        assertEquals(taken, http.put("/api/recipes/${stew.id}") { setBody(asInput(taken)) }.body<RecipeDetail>())
    }

    @Test
    fun aRecipeTakenFromTheCookingSiteIsTheAccountsOwnCopy() = testApplication {
        val http = start()
        http.sync()
        val stew = http.bank().single { it.title == "Stew" }
        assertEquals(HttpStatusCode.Conflict, http.put("/api/recipes/${stew.id}") { setBody(RecipeInput("Mine now", 2, "100 | g | beef")) }.status, "the bank's is the site's")
        assertEquals(HttpStatusCode.Conflict, http.delete("/api/recipes/${stew.id}").status)
        http.put("/api/recipes/${stew.id}/added") { setBody(AddedInput(added = true)) }

        val edited = http.put("/api/recipes/${stew.id}") { setBody(RecipeInput("My stew", 2, "# Stew\n600 | g | beef\n1 | onion | large", "Brown it.", listOf("winter"))) }
        assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())
        val mine = edited.body<RecipeDetail>()
        assertEquals(RecipeSource.COOKING_SITE to "recipes", mine.summary.source to mine.summary.section, "still from the site, for the apps")

        // The site changes the stew, and then removes it: the copy stays as it was edited, and the bank never offers it.
        page("recipes/stew.md", stewPage.replace("1200 | g | beef", "900 | g | beef"))
        assertEquals(SyncResult(0, 0, 2, 0, problems = emptyMap()), http.sync())
        assertEquals(mine, http.recipe(stew.id))
        File(repo, "content/recipes/stew.md").delete()
        assertEquals(SyncResult(0, 0, 1, 0, problems = emptyMap()), http.sync())
        assertEquals(mine, http.recipe(stew.id))
        assertEquals(listOf("My stew" to false), http.get("/api/recipes").body<List<RecipeSummary>>().map { it.title to it.removedFromSite })
        page("recipes/stew.md", stewPage)
        http.sync()
        assertEquals(listOf("Curry"), http.bank().map { it.title })

        // Changed in the app, it has no bank to go back to; deleted, the site's file is in the bank again, as new.
        val putBack = http.put("/api/recipes/${stew.id}/added") { setBody(AddedInput(added = false)) }
        assertEquals(HttpStatusCode.Conflict, putBack.status)
        assertTrue("delete it instead" in putBack.error(), putBack.error())
        assertEquals(HttpStatusCode.NoContent, http.delete("/api/recipes/${stew.id}").status)
        assertEquals(1, http.sync().added)
        val again = http.bank().single { it.title == "Stew" }
        assertNotEquals(stew.id, again.id)
        assertEquals(listOf("winter", "slow"), again.tags)
    }

    /** One taken and never changed in the app goes back to the bank, which follows the site again. */
    @Test
    fun aTakenRecipeNotChangedInTheAppGoesBackToTheBankAndFollowsTheSiteAgain() = testApplication {
        val http = start()
        http.sync()
        val curry = http.bank().single { it.title == "Curry" }
        http.put("/api/recipes/${curry.id}/added") { setBody(AddedInput(added = true)) }
        page("recipes/curry.md", "---\ntitle: Hot curry\n---\n```ingredients\nservings: 2\n300 | g | beef\n```")
        http.sync()
        assertEquals("Curry", http.recipe(curry.id).summary.title, "taken, it does not follow the site")

        assertEquals(HttpStatusCode.OK, http.put("/api/recipes/${curry.id}/added") { setBody(AddedInput(added = false)) }.status)
        assertEquals(1, http.sync().updated)
        assertEquals(listOf("Hot curry", "Stew"), http.bank().map { it.title })
    }

    /**
     * The totals the editor shows while a recipe is written (issue 342) are the saved recipe's: worked out in the app from
     * the catalogue (`GET /api/ingredients`) and what each line means, which for a pasted text the account linked comes from
     * `POST /api/recipes/lines`. Here a picked ingredient, a food the account did not have (made one of its ingredients as
     * the editor makes it) and a pasted line; once saved every line is linked and the nutrition complete.
     */
    @Test
    fun theTotalsWhileWritingAreTheSavedRecipes() = testApplication {
        val http = start()
        val beef = http.ingredient("Beef", 10, 10.0)
        http.put("/api/mappings") { setBody(MappingRequest("rundvlees", ingredientId = beef.id)) }
        val rice = http.post("/api/ingredients") { setBody(IngredientInput("Rice white raw", nevoCode = 5)) }.body<Ingredient>()
        val pasted = PlainLines.read("300 g rundvlees\n1 ui")
        val read = http.post("/api/recipes/lines") { setBody(pasted) }.body<List<MappedLine>>()
        assertEquals(listOf(beef.id, null), read.map { it.ingredientId }, "the onion is no ingredient of the account")
        assertEquals(listOf(300.0, null), read.map { it.grams })

        val catalogue = http.get("/api/ingredients").body<List<Ingredient>>().associateBy { it.id }
        val written = listOf(
            RecipeLineInput(400.0, "g", "Beef", beef.id) to beef,
            RecipeLineInput(1.5, "cup", "Rice white raw", rice.id) to rice,
            pasted[0] to catalogue[read[0].ingredientId],
        )
        val shown = NutritionCalculator.ofLines(written, servings = 3)!!
        val saved = http.post("/api/recipes") { setBody(RecipeInput("Beef and rice", 3, lines = written.map { it.first })) }.body<RecipeDetail>()
        assertEquals(shown.perServing.kcal to shown.perServing.proteinG, saved.summary.nutrition!!.perServing.kcal to saved.summary.nutrition!!.perServing.proteinG)
        assertEquals(shown, saved.summary.nutrition)
        assertTrue(shown.complete && shown.approximate, "the cup of rice is a volume without a density")
        assertEquals(listOf(beef.id, rice.id, beef.id), saved.ingredients.map { it.ingredientId })
    }

    /**
     * A recipe opened in the editor and saved as it was is not changed in the app (issue 342): a taken one can still go back
     * to the bank. With one amount changed it is changed (`edited`), and keeps all it had: tags, groups, steps, slots and
     * how long it keeps; it cannot go back, also after a later save that changes nothing.
     */
    @Test
    fun aSaveThatChangesNothingIsNoChangeInTheApp() = testApplication {
        val http = start()
        http.sync()
        val stew = http.bank().single { it.title == "Stew" }
        val taken = http.put("/api/recipes/${stew.id}/added") { setBody(AddedInput(added = true)) }.body<RecipeDetail>()
        val same = http.put("/api/recipes/${stew.id}") { setBody(asInput(taken)) }.body<RecipeDetail>()
        assertEquals(taken, same)
        assertFalse(same.summary.edited)
        assertEquals(HttpStatusCode.OK, http.put("/api/recipes/${stew.id}/added") { setBody(AddedInput(added = false)) }.status)

        http.put("/api/recipes/${stew.id}/added") { setBody(AddedInput(added = true)) }
        http.put("/api/recipes/${stew.id}/keeps") { setBody(RecipeKeeps(3, 6)) }
        val before = http.recipe(stew.id)
        val input = asInput(before)
        val more = input.copy(lines = input.lines!!.mapIndexed { i, line -> if (i == 0) line.copy(amount = 1500.0) else line })
        val edited = http.put("/api/recipes/${stew.id}") { setBody(more) }.body<RecipeDetail>()
        assertTrue(edited.summary.edited)
        assertEquals(listOf(1500.0, 1.0, 400.0, null), edited.ingredients.map { it.line.amount })
        fun kept(recipe: RecipeDetail) = listOf(
            recipe.summary.tags, recipe.ingredients.map { it.line.group }, recipe.instructions, recipe.summary.mealSlots,
            recipe.summary.keepsDays to recipe.summary.keepsVacuumDays,
        )
        assertEquals(kept(before), kept(edited))
        assertTrue(http.put("/api/recipes/${stew.id}") { setBody(asInput(edited)) }.body<RecipeDetail>().summary.edited)
        assertEquals(HttpStatusCode.Conflict, http.put("/api/recipes/${stew.id}/added") { setBody(AddedInput(added = false)) }.status)
    }

    /**
     * A recipe of the site with no `servings` is shown and saved by the editor with 1 (issue 344): saved as it is, that is
     * no change, the recipe stays without a number and can go back to the bank; with another change it gets the 1.
     */
    @Test
    fun aTakenRecipeWithoutServingsSavedAsTheEditorSendsItIsNotEdited() = testApplication {
        val http = start()
        page("recipes/soup.md", "---\ntitle: Soup\n---\n```ingredients\n300 | g | beef\n```\nSimmer.\n")
        http.sync()
        val soup = http.bank().single { it.title == "Soup" }
        val taken = http.put("/api/recipes/${soup.id}/added") { setBody(AddedInput(added = true)) }.body<RecipeDetail>()
        assertEquals(null, taken.summary.servings)

        val same = http.put("/api/recipes/${soup.id}") { setBody(asInput(taken).copy(servings = 1)) }.body<RecipeDetail>()
        assertEquals(taken, same)
        assertFalse(same.summary.edited)
        assertEquals(HttpStatusCode.OK, http.put("/api/recipes/${soup.id}/added") { setBody(AddedInput(added = false)) }.status)

        http.put("/api/recipes/${soup.id}/added") { setBody(AddedInput(added = true)) }
        val more = http.put("/api/recipes/${soup.id}") { setBody(asInput(taken).copy(servings = 2)) }.body<RecipeDetail>()
        assertTrue(more.summary.edited)
        assertEquals(2, more.summary.servings)
    }

    /** A unit is a word of its own: one with a `|` is refused, as the import format would read it as part of the text. */
    @Test
    fun aUnitWithABarIsRefused() = testApplication {
        val http = start()
        val bar = listOf(RecipeLineInput(1.0, "g|x", "beef"))
        val refused = http.post("/api/recipes") { setBody(RecipeInput("Bowl", 2, lines = bar)) }
        assertEquals(HttpStatusCode.BadRequest to "Line 1: a unit cannot hold |", refused.status to refused.error())
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/recipes/lines") { setBody(bar) }.status)
    }

    @Test
    fun linesThatSayNothingAreRefused() = testApplication {
        val http = start()
        val refused = listOf(
            listOf(RecipeLineInput(0.0, "g", "beef")),
            listOf(RecipeLineInput(null, " ", " ")),
            emptyList(),
        ).map { lines -> http.post("/api/recipes") { setBody(RecipeInput("Bowl", 2, lines = lines)) } }
        assertEquals(List(3) { HttpStatusCode.BadRequest }, refused.map { it.status })
        assertEquals(
            listOf("Line 1: the amount must be above 0, or none", "Line 1: a line needs an amount, a unit or a text", "at least one ingredient line is required"),
            refused.map { it.error() },
        )
        assertEquals(HttpStatusCode.NotFound, http.post("/api/recipes") { setBody(RecipeInput("Bowl", 2, lines = listOf(RecipeLineInput(1.0, "g", "x", 99999)))) }.status)
        assertEquals(emptyList(), http.get("/api/recipes").body<List<RecipeSummary>>())
    }
}
