package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.released.Health
import dev.tobins.mealplanner.released.api.ActivityLevel
import dev.tobins.mealplanner.released.api.AuthConfig
import dev.tobins.mealplanner.released.api.CookingSyncStatus
import dev.tobins.mealplanner.released.api.DayView
import dev.tobins.mealplanner.released.api.Food
import dev.tobins.mealplanner.released.api.FoodDataset
import dev.tobins.mealplanner.released.api.GenerateRequest
import dev.tobins.mealplanner.released.api.GenerationMode
import dev.tobins.mealplanner.released.api.Ingredient
import dev.tobins.mealplanner.released.api.IngredientInput
import dev.tobins.mealplanner.released.api.IngredientSection
import dev.tobins.mealplanner.released.api.LogEntry
import dev.tobins.mealplanner.released.api.MappingRequest
import dev.tobins.mealplanner.released.api.Me
import dev.tobins.mealplanner.released.api.MealSlot
import dev.tobins.mealplanner.released.api.MealStatus
import dev.tobins.mealplanner.released.api.OffersState
import dev.tobins.mealplanner.released.api.Pantry
import dev.tobins.mealplanner.released.api.PricesState
import dev.tobins.mealplanner.released.api.Profile
import dev.tobins.mealplanner.released.api.ProfileState
import dev.tobins.mealplanner.released.api.RatingInput
import dev.tobins.mealplanner.released.api.RecipeDetail
import dev.tobins.mealplanner.released.api.RecipeInput
import dev.tobins.mealplanner.released.api.RecipeSettings
import dev.tobins.mealplanner.released.api.RecipeKind
import dev.tobins.mealplanner.released.api.RecipeSummary
import dev.tobins.mealplanner.released.api.Routines
import dev.tobins.mealplanner.released.api.SectionChoice
import dev.tobins.mealplanner.released.api.Sex
import dev.tobins.mealplanner.released.api.Store as ReleasedStore
import dev.tobins.mealplanner.released.api.ShoppingCheck
import dev.tobins.mealplanner.released.api.ShoppingList
import dev.tobins.mealplanner.released.api.ShopsSetting
import dev.tobins.mealplanner.released.api.SyncResult
import dev.tobins.mealplanner.released.api.UnmappedText
import dev.tobins.mealplanner.released.api.WeekPlan
import dev.tobins.mealplanner.released.api.WeightLog
import dev.tobins.mealplanner.server.generation.GeneratedIngredient
import dev.tobins.mealplanner.server.generation.GeneratedRecipe
import dev.tobins.mealplanner.server.generation.GenerationContext
import dev.tobins.mealplanner.server.generation.RecipeGenerator
import dev.tobins.mealplanner.server.offers.OfferSource
import io.ktor.client.HttpClient
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
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The JSON settings of the released `ApiClient`: unknown fields are skipped, unknown enum values fail. */
internal val releasedJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    allowSpecialFloatingPointValues = true
    allowStructuredMapKeys = true
    prettyPrint = false
    useArrayPolymorphism = false
}

/**
 * Every response of the API, read with the types of the app that is installed (frozen in `released/`,
 * see README "API compatibility"), and every enum those types have. What fails here fails in the
 * installed app: a field the frozen type needs that the server no longer sends, or a value of an
 * enum the frozen type does not know. Fields the frozen types lack are ignored, as the app does.
 *
 * Not covered: what the fixture leaves empty (a recipe with a picture, a weight trend suggestion, which
 * needs weeks of weights, a failed offer source) and `GET /api/shopping-advice`,
 * which the frozen release has no type for. Whatever the installed app does not read cannot break it.
 */
class ReleasedAppTest {
    private class FixedOffers(override val store: Store, val offers: List<Offer>) : OfferSource {
        override suspend fun fetch() = offers
    }

    private class FakeGenerator : RecipeGenerator {
        override suspend fun generate(context: GenerationContext) = GeneratedRecipe(
            title = "Generated ${context.slot} ${context.wish}",
            servings = 2,
            ingredients = listOf(GeneratedIngredient(300.0, "g", "chicken fillet"), GeneratedIngredient(100.0, "g", "rice")),
            steps = listOf("Cook it."),
        )
    }

    private val week = "2026-11-16"

    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|1634|Kipfilet rauw|Chicken fillet raw||per 100g|200|20|13|5|0|0|0|60",
        "NEVO-Online 2025 9.0|Groente|Vegetables|2346|Sla rauw|Lettuce raw||per 100g|15|1|0|0|2|1|2|5",
        "NEVO-Online 2025 9.0|Graanproducten|Cereal products|5|Rijst wit rauw|Rice white raw||per 100g|350|7|1|0|78|0|1|5",
        "NEVO-Online 2025 9.0|Zuivel|Dairy|305|Melk halfvol|Milk semi-skimmed||per 100ml|46|3|2|1|5|5|0|40",
    ).joinToString("\r\n")

    private fun meal(title: String, extra: String = "") =
        "---\ntitle: $title\n---\n```ingredients\nservings: 4\n1200 | g | chicken fillet\n200 | g | lettuce\n200 | g | rice\n$extra```"

    private lateinit var db: dev.tobins.mealplanner.server.db.Database

    /** A server with a NEVO fixture, five recipes of the cooking site, offers and a plan's worth of everything else. */
    private fun ApplicationTestBuilder.start(): HttpClient {
        val repo = createTempDirectory("cooking").toFile()
        for (i in 0..4) File(repo, "content/recipes/meal-$i.md").apply { parentFile.mkdirs() }.writeText(meal("Meal $i", if (i == 0) "1 | g | dragon fruit\n" else ""))
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = repo,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        val jumbo = FixedOffers(
            Store.JUMBO,
            listOf(
                Offer(Store.JUMBO, "Jumbo Kipfilet 1 kg", "30% korting", price = 7.0, originalPrice = 10.0, packSize = "1 kg", validFrom = week, validUntil = "2026-11-22"),
                Offer(Store.JUMBO, "Jumbo Rijst 1 kg", "1+1 gratis", packSize = "1 kg", validFrom = week, validUntil = "2026-11-29"),
            ),
        )
        db = openDatabase(config.databaseUrl).second
        application { module(config, db, listOf(jumbo), priceSource = null, generator = FakeGenerator(), verifier = TestTokens) }
        return createClient {
            install(ContentNegotiation) { json(releasedJson) }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
    }

    private suspend inline fun <reified T> HttpResponse.read(): T {
        assertTrue(status.isSuccess(), "$status: ${bodyAsText()}")
        return releasedJson.decodeFromString<T>(bodyAsText())
    }

    private suspend inline fun <reified T> HttpClient.read(path: String): T = get(path).read()

    @Test
    fun theInstalledAppReadsEveryResponse() = testApplication {
        val http = start()
        val account = http.read<Me>("/api/me")
        assertEquals("t", account.username)
        http.read<Health>("/health")
        http.read<AuthConfig>("/api/auth/config")

        // Setting up an account: the cooking site, offers, a profile, shops, a routine.
        assertTrue(http.post("/api/sync/cooking").read<SyncResult>().added > 0)
        http.read<CookingSyncStatus>("/api/sync/cooking")
        http.post("/api/offers/refresh").read<OffersState>()
        val profile = Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000, likes = listOf("chicken"))
        assertTrue(http.put("/api/profile") { setBody(profile) }.read<ProfileState>().targets != null)
        http.read<ProfileState>("/api/profile")
        http.put("/api/shops") { setBody(ShopsSetting(listOf(ReleasedStore.AH, ReleasedStore.JUMBO))) }.read<ShopsSetting>()
        http.read<ShopsSetting>("/api/shops")
        http.read<Routines>("/api/routines")
        http.put("/api/routines") { setBody(http.read<Routines>("/api/routines")) }.read<Routines>()

        // Recipes: read, written by hand, rated, set and generated.
        val recipes = http.read<List<RecipeSummary>>("/api/recipes")
        assertTrue(recipes.size >= 5)
        http.read<RecipeDetail>("/api/recipes/${recipes.first().id}")
        val mine = http.post("/api/recipes") { setBody(RecipeInput("My stew", 2, "400 | g | chicken fillet\n1 | onion | large", "Stew.", listOf("quick"), listOf(MealSlot.DINNER))) }
            .read<RecipeDetail>()
        http.put("/api/recipes/${mine.summary.id}") { setBody(RecipeInput("My stew", 2, "400 | g | chicken fillet", "Stew.")) }.read<RecipeDetail>()
        http.put("/api/recipes/${mine.summary.id}/rating") { setBody(RatingInput(4)) }.read<RecipeDetail>()
        http.put("/api/recipes/${mine.summary.id}/settings") { setBody(RecipeSettings(RecipeKind.MEAL, listOf(MealSlot.LUNCH))) }.read<RecipeDetail>()
        http.post("/api/recipes/generate") { setBody(GenerateRequest(GenerationMode.PREFERENCE, MealSlot.DINNER, "spicy")) }.read<RecipeDetail>()

        // Catalogue, pantry, mappings and foods.
        val ingredients = http.read<List<Ingredient>>("/api/ingredients")
        val chicken = ingredients.first { it.name.contains("hicken") }
        http.read<List<IngredientSection>>("/api/ingredients/sections")
        http.put("/api/ingredients/${chicken.id}/section") { setBody(SectionChoice("MEAT")) }.read<IngredientSection>()
        http.post("/api/ingredients") { setBody(IngredientInput("Coffee", pricePerKg = 12.0)) }.read<Ingredient>()
        http.put("/api/pantry/staples/${chicken.id}").read<Pantry>()
        http.put("/api/pantry/on-hand/${chicken.id}").read<Pantry>()
        assertTrue(http.read<Pantry>("/api/pantry").onHand.isNotEmpty())
        http.delete("/api/pantry/on-hand/${chicken.id}").read<Pantry>()
        http.delete("/api/pantry/staples/${chicken.id}").read<Pantry>()
        val unmapped = http.read<List<UnmappedText>>("/api/mappings/unmapped")
        assertTrue(unmapped.any { it.text == "dragon fruit" }, "the fixture has an unmapped line")
        http.put("/api/mappings") { setBody(MappingRequest("dragon fruit", nevoCode = 305, name = "Dragon fruit", gramsPerPiece = 300.0)) }.read<Ingredient>()
        assertTrue(http.read<FoodDataset>("/api/foods/dataset").foodCount > 0)
        assertTrue(http.read<List<Food>>("/api/foods?q=chicken").isNotEmpty())

        // Offers and prices; the weekly lookup of regular prices fills the table for every account.
        db.priceQueries.upsertPrice("chicken fillet", "AH", "AH Kipfilet", "500 g", 6.0, 12.0, "2026-11-15T00:00:00Z", "Vlees", null, null)
        assertTrue(http.read<OffersState>("/api/offers?date=2026-11-17").offers.isNotEmpty())
        assertTrue(http.read<PricesState>("/api/prices").prices.isNotEmpty())

        // The plan: made, read, rerolled, locked, set, with a generated recipe in it.
        val plan = http.post("/api/plans/$week/generate").read<WeekPlan>()
        assertTrue(plan.days.any { it.meals.isNotEmpty() })
        val first = plan.days.first { it.meals.isNotEmpty() }.meals.first()
        val at = "/api/plans/$week/meals/${first.day}/${first.slot}"
        http.read<WeekPlan>("/api/plans/$week")
        http.post("$at/reroll").read<WeekPlan>()
        http.put("$at/lock").read<WeekPlan>()
        http.delete("$at/lock").read<WeekPlan>()
        // A new recipe before eating: an eaten meal keeps its recipe.
        http.post("$at/generate") { setBody(GenerateRequest(GenerationMode.PREFERENCE, first.slot, "")) }.read<WeekPlan>()
        http.put("$at/status") { setBody(MealStatus.EATEN) }.read<WeekPlan>()

        // The shopping list, an own item (a row of it for the released app, which cannot add one) and a tick.
        assertTrue(http.post("/api/plans/$week/shopping/extras") { setBody(mapOf("name" to "Coffee")) }.read<ShoppingList>().items.any { it.name == "Coffee" })
        val list = http.read<ShoppingList>("/api/plans/$week/shopping")
        assertTrue(list.items.isNotEmpty())
        assertTrue(http.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(list.items.first().key, true)) }.read<ShoppingList>().items.first().checked)

        // The day, a log entry and weights.
        val date = "2026-11-17"
        assertTrue(http.read<DayView>("/api/days/$date").meals.isNotEmpty())
        val entry = http.post("/api/log") { setBody(LogEntry(0, date, "Apple", 80.0, 0.5)) }.read<DayView>().extras.single()
        http.delete("/api/log/${entry.id}")
        http.put("/api/weights/$date") { setBody(90.5) }.read<WeightLog>()
        assertTrue(http.read<WeightLog>("/api/weights").entries.isNotEmpty())
        http.delete("/api/weights/$date").read<WeightLog>()
    }

    /**
     * No enum of the frozen types may have a value the current one lacks: an installed app fails to
     * read a response with a value it does not know, whatever else the response holds. The check
     * covers every enum under `released/` by itself, so a new frozen enum is covered after a freeze.
     */
    @Test
    fun noEnumHasAValueTheInstalledAppLacks() {
        val frozen = frozenEnums()
        assertTrue(frozen.any { it.simpleName == "Store" } && frozen.size >= 10, "found ${frozen.map { it.simpleName }}")
        val problems = frozen.flatMap { old ->
            val name = old.name.replace(".released", "")
            val current = Class.forName(name).takeIf { it.isEnum }?.enumConstants?.map { (it as Enum<*>).name }
                ?: return@flatMap listOf("$name is gone or no longer an enum")
            (current - old.enumConstants.map { (it as Enum<*>).name }.toSet()).map { value ->
                "$name.$value: installed apps cannot read this value of ${old.simpleName} and fail on the whole response; " +
                    "it must wait until a release that has it is installed and scripts/freeze-api.sh has frozen that release"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /** The enum classes of the `released` package and below, found in the test classes. */
    private fun frozenEnums(): List<Class<*>> {
        val root = File(checkNotNull(javaClass.classLoader.getResource("dev/tobins/mealplanner/released")).toURI())
        return root.walkTopDown().filter { it.extension == "class" }.map {
            Class.forName("dev.tobins.mealplanner.released." + it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.'))
        }.filter { it.isEnum }.toList()
    }
}
