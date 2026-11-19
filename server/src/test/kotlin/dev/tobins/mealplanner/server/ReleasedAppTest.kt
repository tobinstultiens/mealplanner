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
import dev.tobins.mealplanner.released.api.MealAdjustment
import dev.tobins.mealplanner.released.api.MealLineChange
import dev.tobins.mealplanner.released.api.MealLines
import dev.tobins.mealplanner.released.api.MealSlot
import dev.tobins.mealplanner.released.api.MealStatus
import dev.tobins.mealplanner.released.api.NotificationFacts
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
import io.ktor.http.HttpStatusCode
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
        // The site's recipes start in the bank (issue 205), which the installed app does not know; the account
        // adds them in a newer app, or had them before the bank. The installed app lists them from then on.
        http.addWholeBank()
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
        http.readsARecipeWithAToppingOf(mine.summary.id, chicken.id)

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
        val planned = http.put("$at/status") { setBody(MealStatus.EATEN) }.read<WeekPlan>().days[first.day].meals.single { it.slot == first.slot }
        // Adjusted in a newer app (issue 226): the installed app reads the plan with the meal's own numbers.
        val adjusted = http.put("$at/lines") { setBody("""{"lines":[{"ingredientId":${chicken.id},"grams":50.0}]}""") }
        assertTrue(adjusted.status.isSuccess(), adjusted.bodyAsText())
        val eaten = http.read<WeekPlan>("/api/plans/$week").days[first.day].meals.single { it.slot == first.slot }
        assertTrue(eaten.kcal < planned.kcal, "50 g of chicken is less than the recipe's: ${eaten.kcal} of ${planned.kcal}")

        // The shopping list, an own item (a row of it for the released app, which cannot add one) and a tick.
        assertTrue(http.post("/api/plans/$week/shopping/extras") { setBody(mapOf("name" to "Coffee")) }.read<ShoppingList>().items.any { it.name == "Coffee" })
        val list = http.read<ShoppingList>("/api/plans/$week/shopping")
        assertTrue(list.items.isNotEmpty())
        assertTrue(http.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(list.items.first().key, true)) }.read<ShoppingList>().items.first().checked)
        http.readsARowAskedForAgain(chicken.id)
        http.readsAWeekWithAPrepOf(mine.summary.id)
        http.readsAWeekWithASideOf(mine.summary.id)

        // The day, a log entry and weights.
        val date = "2026-11-17"
        assertTrue(http.read<DayView>("/api/days/$date").meals.isNotEmpty())
        val entry = http.post("/api/log") { setBody(LogEntry(0, date, "Apple", 80.0, 0.5)) }.read<DayView>().extras.single()
        http.delete("/api/log/${entry.id}")
        http.put("/api/weights/$date") { setBody(90.5) }.read<WeightLog>()
        assertTrue(http.read<WeightLog>("/api/weights").entries.isNotEmpty())
        http.delete("/api/weights/$date").read<WeightLog>()
    }

    /** A topping of ingredient [ingredientId] set on recipe [recipeId] in a newer app (issue 226): the installed app reads the recipe as before. */
    private suspend fun HttpClient.readsARecipeWithAToppingOf(recipeId: Long, ingredientId: Long) {
        val topped = put("/api/recipes/$recipeId/toppings") { setBody("""{"toppings":[{"ingredientId":$ingredientId,"grams":50.0}]}""") }
        assertTrue(topped.status.isSuccess(), topped.bodyAsText())
        read<RecipeDetail>("/api/recipes/$recipeId")
    }

    /**
     * Ingredient [ingredientId] ticked as bought for the week, then added in a newer app to the first meal still to
     * cook out of what the meals after it need (issue 226): the installed app reads the row, which needs more.
     */
    private suspend fun HttpClient.readsARowAskedForAgain(ingredientId: Long) {
        val key = "i$ingredientId"
        val bought = put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, true)) }.read<ShoppingList>().items.single { it.key == key }
        val next = read<WeekPlan>("/api/plans/$week").days.flatMap { it.meals }.filter { it.status == MealStatus.PLANNED }.minBy { it.day * 4 + it.slot.ordinal }
        val more = put("/api/plans/$week/meals/${next.day}/${next.slot}/lines") { setBody("""{"lines":[{"ingredientId":$ingredientId,"grams":1500.0}]}""") }
        assertTrue(more.status.isSuccess(), more.bodyAsText())
        val asked = read<ShoppingList>("/api/plans/$week/shopping").items.single { it.key == key }
        assertTrue(asked.checked && asked.grams!! > bought.grams!!, "${asked.grams} after ${bought.grams}")
    }

    /**
     * Recipe [recipeId] prepped in a newer app (issue 234), made on Sunday and eaten on Monday and Tuesday of the week
     * after: the installed app reads both weeks, the later meals as locked leftovers with nothing to cook, marks one
     * eaten and not again, reads its day, reads and adjusts what was in one, and reads what is due and the shopping lists. Removing the meal that makes the batch is refused with a
     * sentence, which the app shows.
     */
    private suspend fun HttpClient.readsAWeekWithAPrepOf(recipeId: Long) {
        val keeps = put("/api/recipes/$recipeId/keeps") { setBody("""{"days":3}""") }
        assertTrue(keeps.status.isSuccess(), keeps.bodyAsText())
        read<RecipeDetail>("/api/recipes/$recipeId")
        val meals = """[{"date":"2026-11-22","slot":"DINNER"},{"date":"2026-11-23","slot":"LUNCH"},{"date":"2026-11-24","slot":"DINNER"}]"""
        post("/api/plans/$week/preps") { setBody("""{"recipeId":$recipeId,"madeOn":"2026-11-22","meals":$meals}""") }.read<WeekPlan>()

        val made = read<WeekPlan>("/api/plans/$week").days[6].meals.single { it.slot == MealSlot.DINNER }
        val next = "2026-11-23"
        val later = read<WeekPlan>("/api/plans/$next").days.flatMap { it.meals }
        assertEquals(listOf(true, true), later.map { it.leftover && it.locked })
        assertEquals(listOf(0.0, 0.0), later.map { it.cookPortions })
        assertEquals(made.portions + later.sumOf { it.portions }, made.cookPortions)
        val lunch = "/api/plans/$next/meals/0/LUNCH"
        assertEquals(MealStatus.EATEN, put("$lunch/status") { setBody(MealStatus.EATEN) }.read<WeekPlan>().days[0].meals.single().status)
        put("$lunch/status") { setBody(MealStatus.PLANNED) }.read<WeekPlan>()
        assertTrue(read<DayView>("/api/days/2026-11-23").meals.single().leftover)
        // What was in a prepped meal, read and adjusted as the installed app does it (issue 226), and what is due.
        val lines = read<MealLines>("$lunch/lines")
        assertEquals(later.first().portions, lines.portions)
        put("$lunch/lines") { setBody(MealAdjustment(lines.lines.mapNotNull { line -> line.ingredientId?.let { MealLineChange(it, 10.0) } })) }.read<MealLines>()
        read<NotificationFacts>("/api/notifications")
        read<ShoppingList>("/api/plans/$next/shopping")
        read<ShoppingList>("/api/plans/$week/shopping")
        val removal = delete("/api/plans/$week/meals/6/DINNER")
        assertEquals(HttpStatusCode.Conflict, removal.status)
        assertTrue(removal.bodyAsText().contains("Monday lunch, Tuesday dinner"), removal.bodyAsText())
    }

    /**
     * Recipe [recipeId], which keeps since [readsAWeekWithAPrepOf], eaten in a newer app as a side of half a serving next to
     * two planned lunches or dinners of the week (issue 234, step 2): the installed app reads the week and the day with
     * each meal's main as it was, marks the later one eaten and not again, reads what is due and the list, and gets a
     * sentence for removing the meal the side is made with, which the app shows.
     */
    private suspend fun HttpClient.readsAWeekWithASideOf(recipeId: Long) {
        val before = read<WeekPlan>("/api/plans/$week").days.flatMap { it.meals }
        val hosts = before.filter { it.day in 2..4 && it.status == MealStatus.PLANNED && it.slot in setOf(MealSlot.LUNCH, MealSlot.DINNER) }.take(2)
        assertEquals(2, hosts.size, "two meals for the side to go with: $before")
        fun date(day: Int) = java.time.LocalDate.parse(week).plusDays(day.toLong()).toString()
        val meals = hosts.joinToString(",", "[", "]") { """{"date":"${date(it.day)}","slot":"${it.slot}"}""" }
        val made = post("/api/plans/$week/preps") { setBody("""{"recipeId":$recipeId,"madeOn":"${date(hosts[0].day)}","meals":$meals,"side":0.5}""") }
        assertTrue(made.bodyAsText().contains("\"side\""), "a newer app sees the sides")

        val after = made.read<WeekPlan>().days.flatMap { it.meals }
        assertEquals(hosts, hosts.map { host -> after.single { it.day == host.day && it.slot == host.slot } }, "each meal shows its main as it was")
        assertTrue(read<DayView>("/api/days/${date(hosts[0].day)}").meals.any { it.slot == hosts[0].slot && it.recipeId == hosts[0].recipeId })
        val later = "/api/plans/$week/meals/${hosts[1].day}/${hosts[1].slot}"
        assertEquals(MealStatus.EATEN, put("$later/status") { setBody(MealStatus.EATEN) }.read<WeekPlan>().days[hosts[1].day].meals.single { it.slot == hosts[1].slot }.status)
        put("$later/status") { setBody(MealStatus.PLANNED) }.read<WeekPlan>()
        read<NotificationFacts>("/api/notifications")
        read<ShoppingList>("/api/plans/$week/shopping")
        val removal = delete("/api/plans/$week/meals/${hosts[0].day}/${hosts[0].slot}")
        assertEquals(HttpStatusCode.Conflict, removal.status)
        assertTrue(removal.bodyAsText().contains("(a side)"), removal.bodyAsText())
    }

    /**
     * A batch whose last day was yesterday with a share skipped and a meal never marked (issue 264), prepped in a newer
     * app: the installed app reads what is due, of which it does not know this, and marks the meal it shows. On a server
     * of its own, as the batch must end on the server's yesterday, which falls on any day of the main test's week.
     */
    @Test
    fun theInstalledAppReadsWhatIsDueWithABatchPastItsLastDay() = testApplication {
        val http = start()
        http.put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }.read<ProfileState>()
        val stew = http.post("/api/recipes") { setBody(RecipeInput("My stew", 2, "400 | g | chicken fillet", "Stew.")) }.read<RecipeDetail>().summary
        val keeps = http.put("/api/recipes/${stew.id}/keeps") { setBody("""{"days":2}""") }
        assertTrue(keeps.status.isSuccess(), keeps.bodyAsText())
        val madeOn = serverToday().minusDays(3)
        val dates = (0L..2L).map { madeOn.plusDays(it) }
        fun dayOf(date: java.time.LocalDate) = (date.toEpochDay() - Batch.mondayOf(date).toEpochDay()).toInt()
        fun mealPath(date: java.time.LocalDate) = "/api/plans/${Batch.mondayOf(date)}/meals/${dayOf(date)}/DINNER"
        val meals = dates.joinToString(",", "[", "]") { """{"date":"$it","slot":"DINNER"}""" }
        http.post("/api/plans/${Batch.mondayOf(madeOn)}/preps") { setBody("""{"recipeId":${stew.id},"madeOn":"$madeOn","meals":$meals}""") }.read<WeekPlan>()
        http.put("${mealPath(dates[0])}/status") { setBody(MealStatus.EATEN) }.read<WeekPlan>()
        http.put("${mealPath(dates[1])}/status") { setBody(MealStatus.SKIPPED) }.read<WeekPlan>()

        suspend fun readsWhatIsDue() {
            val due = http.get("/api/notifications")
            assertTrue(due.bodyAsText().contains("\"prepsPastLastDay\":[{"), "a newer app is told: ${due.bodyAsText()}")
            due.read<NotificationFacts>()
        }
        readsWhatIsDue()
        // The last dinner, which a newer app asks about, marked eaten in the installed app; the skipped share is still told.
        val week = http.put("${mealPath(dates[2])}/status") { setBody(MealStatus.EATEN) }.read<WeekPlan>()
        assertEquals(MealStatus.EATEN, week.days[dayOf(dates[2])].meals.single { it.slot == MealSlot.DINNER }.status)
        readsWhatIsDue()
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
