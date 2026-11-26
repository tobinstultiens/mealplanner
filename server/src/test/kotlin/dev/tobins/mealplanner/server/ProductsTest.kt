package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.LabelNutrition
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.UnmappedText
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.nutrition.Salt
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
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
import java.time.LocalDate
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An ingredient with nutrition of its own and named portions (issue 351), through the API. */
class ProductsTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Snacks|Savoury snacks|20|Mueslireep|Muesli bar||per 100g|450|7|18|8|60|30|4|100",
    ).joinToString("\r\n")

    private val week = "2026-11-16"

    private suspend fun ApplicationTestBuilder.start(): HttpClient {
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        application {
            module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens, today = { LocalDate.parse(week) })
        }
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

    /** A protein bar's label: per 100 g 400 kcal, 30 g protein, 0.5 g salt; its fibre not given. */
    private val barLabel = LabelNutrition(FoodBasis.PER_100_G, Nutrition(400.0, 30.0, 15.0, 8.0, 35.0, 5.0, null, Salt.sodiumMg(0.5)))

    private suspend fun HttpClient.product(input: IngredientInput) = post("/api/ingredients") { setBody(input) }.body<Ingredient>()

    private suspend fun HttpClient.change(id: Long, input: IngredientInput) = put("/api/ingredients/$id") { setBody(input) }.body<Ingredient>()

    private suspend fun HttpClient.catalogue() = get("/api/ingredients").body<List<Ingredient>>()

    private suspend fun HttpClient.recipe(title: String, vararg lines: RecipeLineInput) =
        post("/api/recipes") { setBody(RecipeInput(title, 1, mealSlots = listOf(MealSlot.DINNER), lines = lines.toList())) }.body<RecipeDetail>()

    private suspend fun HttpClient.recipe(id: Long) = get("/api/recipes/$id").body<RecipeDetail>()

    private suspend fun HttpResponse.error() = body<Map<String, String>>().getValue("error")

    @Test
    fun aRecipeCountsAProductByTheValuesOfItsOwnLabel() = testApplication {
        val http = start()
        val bar = http.product(IngredientInput("Protein bar", label = barLabel))
        assertEquals(barLabel, bar.label)
        assertNull(bar.label!!.per100.fibreG, "a value the label does not give stays unknown")
        assertEquals(barLabel, http.catalogue().single { it.id == bar.id }.label)

        val snack = http.recipe("Snack", RecipeLineInput(45.0, "g", "Protein bar", bar.id)).summary.nutrition!!
        assertEquals(listOf(180.0, 13.5), listOf(snack.perServing.kcal, snack.perServing.proteinG))
        assertEquals(Salt.sodiumMg(0.225), snack.perServing.sodiumMg!!, 1e-9)
        assertTrue(snack.complete && !snack.approximate)
    }

    @Test
    fun anIngredientWithBothCountsByItsOwnValuesAndWithoutThemByItsFood() = testApplication {
        val http = start()
        val bar = http.product(IngredientInput("Muesli bar", nevoCode = 20, label = barLabel))
        val snack = http.recipe("Snack", RecipeLineInput(45.0, "g", "Muesli bar", bar.id))
        assertEquals(180.0, snack.summary.nutrition!!.perServing.kcal)

        http.change(bar.id, IngredientInput("Muesli bar", nevoCode = 20, label = null))
        assertEquals(202.5, http.recipe(snack.summary.id).summary.nutrition!!.perServing.kcal!!, 1e-9, "45 g of NEVO's 450 kcal per 100 g")
    }

    @Test
    fun aDrinkPer100MlCountsALineInMlWithoutADensity() = testApplication {
        val http = start()
        val shake = http.product(IngredientInput("Protein shake", label = LabelNutrition(FoodBasis.PER_100_ML, Nutrition(60.0, 10.0, null, null, null, null, null, null))))
        assertNull(shake.gramsPerMl)
        val drink = http.recipe("Shake", RecipeLineInput(330.0, "ml", "Protein shake", shake.id)).summary.nutrition!!
        assertEquals(198.0, drink.perServing.kcal!!, 1e-9)
        assertTrue(drink.complete && !drink.approximate, "a volume counted per volume needs no density")
    }

    @Test
    fun aLineInAPortionCountsItsWeightUntilThePortionIsRemoved() = testApplication {
        val http = start()
        val bar = http.product(IngredientInput("Protein bar", label = barLabel, portions = listOf(Portion("bar", grams = 45.0))))
        assertEquals(listOf(Portion("bar", grams = 45.0)), bar.portions)
        val snack = http.recipe("Snack", RecipeLineInput(2.0, "bar", "Protein bar", bar.id))
        assertEquals(90.0, snack.ingredients.single().grams)
        assertEquals(360.0, snack.summary.nutrition!!.perServing.kcal)
        assertEquals("bar", http.catalogue().single { it.id == bar.id }.lastUnit, "a portion is the unit last saved like any other")

        // A week with it: its shopping list and its meal (what the stock takes) weigh the portions.
        assertEquals(HttpStatusCode.OK, http.put("/api/plans/$week/meals/2/DINNER") { setBody(MealChoice(snack.summary.id)) }.status)
        val portions = http.get("/api/plans/$week").body<WeekPlan>().days[2].meals.single { it.slot == MealSlot.DINNER }.portions
        val row = http.get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.key == "i${bar.id}" }
        assertEquals(90.0 * portions, row.grams!!, 1e-9)
        assertNull(row.pieces, "a bar is weighed, not a piece of what the store sells")
        val eaten = http.get("/api/plans/$week/meals/2/DINNER/lines").body<MealLines>().lines.single()
        assertEquals(90.0 * portions, eaten.grams!!, 1e-9)

        // Its weight corrected: every recipe in it is corrected.
        http.change(bar.id, IngredientInput("Protein bar", label = barLabel, portions = listOf(Portion("bar", grams = 50.0))))
        assertEquals(100.0, http.recipe(snack.summary.id).ingredients.single().grams)

        // Removed, even with a weight per piece known: the line is said not to be counted, never counted as pieces.
        val gone = http.change(bar.id, IngredientInput("Protein bar", gramsPerPiece = 40.0, label = barLabel, portions = emptyList()))
        assertEquals(emptyList(), gone.portions)
        assertEquals(listOf("bar"), gone.removedPortions)
        val uncounted = http.recipe(snack.summary.id)
        assertNull(uncounted.ingredients.single().grams)
        assertFalse(uncounted.summary.nutrition!!.complete)
        assertEquals(listOf("bar, Protein bar"), uncounted.summary.nutrition!!.uncounted)
        val unweighed = http.get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.key == "i${bar.id}" }
        assertEquals(Triple(null, null, listOf("${kotlin.math.ceil(2 * portions - 1e-9).toInt()} bar")), Triple(unweighed.grams, unweighed.pieces, unweighed.otherAmounts))
        assertTrue(http.get("/api/mappings/unmapped").body<List<UnmappedText>>().none { it.linkedIngredient?.id == bar.id }, "a weight per piece would not count it")

        // Given again, under any case: the line counts again.
        val back = http.change(bar.id, IngredientInput("Protein bar", gramsPerPiece = 40.0, label = barLabel, portions = listOf(Portion("Bar", grams = 45.0))))
        assertEquals(listOf(Portion("Bar", grams = 45.0)) to emptyList(), back.portions to back.removedPortions)
        assertEquals(90.0, http.recipe(snack.summary.id).ingredients.single().grams)
    }

    @Test
    fun portionsAreLeftAsTheyAreByAChangeThatDoesNotGiveThem() = testApplication {
        val http = start()
        val bar = http.product(IngredientInput("Protein bar", label = barLabel, portions = listOf(Portion("bar", grams = 45.0), Portion("half", grams = 22.5))))
        val renamed = http.change(bar.id, IngredientInput("Brand protein bar", label = barLabel))
        assertEquals(bar.portions, renamed.portions)
        assertEquals(listOf("bar", "half"), renamed.portions.map { it.name }, "in the order given")
    }

    @Test
    fun aPortionOrALabelThatCannotBeUsedIsRefusedSayingWhy() = testApplication {
        val http = start()
        val bar = http.product(IngredientInput("Protein bar", label = barLabel, portions = listOf(Portion("bar", grams = 45.0))))
        suspend fun refused(input: IngredientInput, why: String) {
            val response = http.put("/api/ingredients/${bar.id}") { setBody(input) }
            assertEquals(HttpStatusCode.BadRequest, response.status, why)
            assertTrue(why in response.error(), "'${response.error()}' says $why")
        }
        fun with(vararg portions: Portion) = IngredientInput("Protein bar", label = barLabel, portions = portions.toList())
        refused(with(Portion("g", grams = 45.0)), "is a unit already")
        refused(with(Portion("EL", grams = 15.0)), "is a unit already")
        refused(with(Portion("stuks", grams = 15.0)), "is a unit already")
        refused(with(Portion(" ", grams = 45.0)), "needs a name")
        refused(with(Portion("bar | big", grams = 45.0)), "cannot hold | or a line break")
        refused(with(Portion("bar\nbig", grams = 45.0)), "cannot hold | or a line break")
        refused(with(Portion("Bar", grams = 45.0), Portion("bar ", grams = 50.0)), "Two portions are named")
        refused(with(Portion("bar", grams = 45.0, ml = 50.0)), "not both")
        refused(with(Portion("bar")), "needs a weight in grams or a volume in ml")
        refused(with(Portion("bar", grams = 0.0)), "above 0")
        refused(IngredientInput("Protein bar", label = barLabel.copy(per100 = barLabel.per100.copy(kcal = null))), "energy (kcal) is needed")
        refused(IngredientInput("Protein bar", label = barLabel.copy(per100 = barLabel.per100.copy(fatG = -1.0))), "0 or more")
        assertEquals(bar, http.catalogue().single { it.id == bar.id }, "nothing of a refused change is kept")
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/ingredients") { setBody(IngredientInput("Shake", portions = listOf(Portion("ml", ml = 250.0)))) }.status)
        assertTrue(http.catalogue().none { it.name == "Shake" })
    }
}
