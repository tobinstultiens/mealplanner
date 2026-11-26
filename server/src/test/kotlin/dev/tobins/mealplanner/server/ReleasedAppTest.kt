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
import dev.tobins.mealplanner.released.api.PlannedMeal
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
import dev.tobins.mealplanner.released.api.ShoppingItem
import dev.tobins.mealplanner.released.api.ShoppingList
import dev.tobins.mealplanner.released.api.ShopsSetting
import dev.tobins.mealplanner.released.api.SparePortion
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
import io.ktor.http.encodeURLParameter
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

    /**
     * The server's today: the Monday of [week] unless a test moves it, so that the preps made in a newer app are made on days
     * still to come and a batch ends when the test says, whenever it runs.
     */
    private var today: java.time.LocalDate = java.time.LocalDate.parse(week)

    /**
     * A server with a NEVO fixture, five recipes of the cooking site, offers and a plan's worth of everything else, and
     * the installed app logged in with [token]; [group] is `MEALPLANNER_OIDC_GROUP`, unset as by default.
     */
    private fun ApplicationTestBuilder.start(group: String? = null, token: String = "t"): HttpClient {
        val repo = createTempDirectory("cooking").toFile()
        for (i in 0..4) File(repo, "content/recipes/meal-$i.md").apply { parentFile.mkdirs() }.writeText(meal("Meal $i", if (i == 0) "1 | g | dragon fruit\n" else ""))
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = repo,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
            oidcGroup = group,
        )
        val jumbo = FixedOffers(
            Store.JUMBO,
            listOf(
                Offer(Store.JUMBO, "Jumbo Kipfilet 1 kg", "30% korting", price = 7.0, originalPrice = 10.0, packSize = "1 kg", validFrom = week, validUntil = "2026-11-22"),
                Offer(Store.JUMBO, "Jumbo Rijst 1 kg", "1+1 gratis", packSize = "1 kg", validFrom = week, validUntil = "2026-11-29"),
            ),
        )
        db = openDatabase(config.databaseUrl).second
        application { module(config, db, listOf(jumbo), priceSource = null, generator = FakeGenerator(), verifier = TestTokens, today = { today }) }
        return createClient {
            install(ContentNegotiation) { json(releasedJson) }
            defaultRequest {
                bearerAuth(token)
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
        // Without the group setting the scopes are those of before: the provider's client may not allow the groups one.
        assertEquals(LOGIN_SCOPES, http.read<AuthConfig>("/api/auth/config").scopes)

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
        http.readsALateTick(chicken.id)
        http.readsOffersAndTheListAfterAnExclusion()
        http.readsAWeekWithAPrepOf(mine.summary.id)
        http.readsAWeekWithASideOf(mine.summary.id)
        http.readsAWeekWithABatchOfMealsAndSidesOf(mine.summary.id)

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
     * An offer said in a newer app not to be its ingredient ("Not this ingredient", issue 316): the installed app reads the
     * offers and the shopping list as before, without the match; taken back, the match is there again.
     */
    private suspend fun HttpClient.readsOffersAndTheListAfterAnExclusion() {
        val offers = "/api/offers?date=2026-11-17"
        val shopping = "/api/plans/$week/shopping"
        val (row, deal) = read<ShoppingList>(shopping).items.firstNotNullOf { item -> item.offers.firstOrNull()?.let { item to it } }
        val ingredientId = row.key.removePrefix("i").toLong()
        fun OffersState.ingredientOf() = this.offers.single { it.store == deal.store && it.title == deal.title }.ingredientId
        assertEquals(ingredientId, read<OffersState>(offers).ingredientOf())
        val excluded = put("/api/offers/exclusions") { setBody("""{"store":"${deal.store}","title":"${deal.title}","ingredientId":$ingredientId}""") }
        assertTrue(excluded.status.isSuccess(), excluded.bodyAsText())

        assertEquals(null, read<OffersState>(offers).ingredientOf())
        assertTrue(read<ShoppingList>(shopping).items.single { it.key == row.key }.offers.none { it.title == deal.title })
        val takenBack = delete("/api/offers/exclusions?store=${deal.store}&title=${deal.title.encodeURLParameter()}&ingredientId=$ingredientId")
        assertTrue(takenBack.status.isSuccess(), takenBack.bodyAsText())
        assertEquals(ingredientId, read<OffersState>(offers).ingredientOf())
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
     * Ingredient [ingredientId], on the list, ticked as an installed app does after it was offline (its tick says nothing of
     * what the row showed), then by a newer app that does (issue 116): the installed app reads the row either way, and the
     * newer one's tick stores what that app showed.
     */
    private suspend fun HttpClient.readsALateTick(ingredientId: Long) {
        val key = "i$ingredientId"
        suspend fun tick(body: String): ShoppingItem {
            val answer = put("/api/plans/$week/shopping") { setBody(body) }
            assertTrue(answer.status.isSuccess(), answer.bodyAsText())
            return answer.read<ShoppingList>().items.single { it.key == key }
        }
        suspend fun tickAsInstalled(checked: Boolean) = tick(releasedJson.encodeToString(ShoppingCheck(key, checked)))
        tickAsInstalled(false)
        val old = tickAsInstalled(true)
        assertTrue(old.checked && old.boughtGrams != null, "$old")
        tickAsInstalled(false)
        val shown = tick("""{"key":"$key","checked":true,"shown":{"grams":${old.boughtGrams!! / 2},"saving":0.0,"tickedOn":"2026-11-16"}}""")
        assertEquals(true to old.boughtGrams!! / 2, shown.checked to shown.boughtGrams)
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
     * What a meal shows of its main: its own fields, without those a later release added for a side. Spelled out so
     * the test holds whichever release is frozen (a frozen [PlannedMeal] that knows sides has one more field than one
     * that does not, and a comparison of whole meals would then differ by exactly the side).
     */
    private fun PlannedMeal.main() = listOf(day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked, status, cost, cookPortions, fibreG)

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
        assertEquals(
            hosts.map { it.main() },
            hosts.map { host -> after.single { it.day == host.day && it.slot == host.slot }.main() },
            "each meal shows its main as it was",
        )
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
     * Recipe [recipeId] prepped in a newer app as one batch eaten partly as meals and partly as a side (issue 269): the meal
     * of Saturday's and Sunday's lunch, and a quarter of a serving next to Saturday's dinner. The installed app knows a
     * batch of meals and a batch of sides, not one of both, and can't send one ([PrepSlot] has no side). It reads the week:
     * Saturday's lunch makes the batch and cooks all of it, the side's share too; Sunday's lunch is a locked leftover of
     * it; Saturday's dinner keeps its main, with the side next to it; each share of the batch says whether it is a side
     * ([PrepMeal.side]), which is what the app's remove question notes. It marks Sunday's lunch eaten and not again, reads
     * the day, what is due and the list, and gets a sentence naming the side for removing Saturday's lunch. Its "Remove the
     * side" and "Remove the side's prep…" work: the batch gets smaller, and then goes with its meals, the dinner staying.
     */
    private suspend fun HttpClient.readsAWeekWithABatchOfMealsAndSidesOf(recipeId: Long) {
        val host = read<WeekPlan>("/api/plans/$week").days[5].meals.single { it.slot == MealSlot.DINNER }
        assertEquals(MealStatus.PLANNED to null, host.status to host.side)
        val shares = """[{"date":"2026-11-21","slot":"LUNCH"},{"date":"2026-11-21","slot":"DINNER","side":0.25},{"date":"2026-11-22","slot":"LUNCH"}]"""
        val days = post("/api/plans/$week/preps") { setBody("""{"recipeId":$recipeId,"madeOn":"2026-11-21","meals":$shares}""") }.read<WeekPlan>().days

        val saturday = days[5].meals.single { it.slot == MealSlot.LUNCH }
        val sunday = days[6].meals.single { it.slot == MealSlot.LUNCH }
        val dinner = days[5].meals.single { it.slot == MealSlot.DINNER }
        val prep = checkNotNull(saturday.prep)
        assertEquals(listOf(recipeId, recipeId), listOf(saturday.recipeId, sunday.recipeId))
        assertEquals(listOf(false to true, true to true), listOf(saturday, sunday).map { it.leftover to it.locked })
        assertEquals(listOf(saturday.portions + 0.25 + sunday.portions, 0.0), listOf(saturday.cookPortions, sunday.cookPortions))
        assertEquals(listOf(false, true, false), prep.meals.map { it.side }, "each share says whether it is a side")
        assertEquals(prep, sunday.prep)
        assertEquals(Triple(host.recipeId, host.title, host.portions), Triple(dinner.recipeId, dinner.title, dinner.portions), "the side's meal keeps its main")
        val side = checkNotNull(dinner.side)
        assertEquals(Triple(recipeId, 0.25, 0.0), Triple(side.recipeId, side.portions, side.cookPortions))
        assertEquals(prep, side.prep, "the side shows the same batch, meals and side alike")

        val lunch = "/api/plans/$week/meals/6/LUNCH"
        assertEquals(MealStatus.EATEN, put("$lunch/status") { setBody(MealStatus.EATEN) }.read<WeekPlan>().days[6].meals.single { it.slot == MealSlot.LUNCH }.status)
        put("$lunch/status") { setBody(MealStatus.PLANNED) }.read<WeekPlan>()
        assertTrue(read<DayView>("/api/days/2026-11-22").meals.single { it.slot == MealSlot.LUNCH }.leftover)
        read<NotificationFacts>("/api/notifications")
        read<ShoppingList>("/api/plans/$week/shopping")
        val removal = delete("/api/plans/$week/meals/5/LUNCH")
        assertEquals(HttpStatusCode.Conflict, removal.status)
        assertTrue(removal.bodyAsText().contains("Saturday dinner (a side), Sunday lunch"), removal.bodyAsText())

        // "Remove the side" on the dinner: the batch is the two lunches; "Remove the prep…": they go, the dinner stays.
        val smaller = delete("/api/plans/$week/meals/5/DINNER/side").read<WeekPlan>().days[5]
        assertEquals(saturday.portions + sunday.portions, smaller.meals.single { it.slot == MealSlot.LUNCH }.cookPortions)
        val removed = delete("/api/plans/$week/preps/${prep.id}").read<WeekPlan>().days
        assertTrue(removed[5].meals.none { it.slot == MealSlot.LUNCH } && removed[6].meals.none { it.slot == MealSlot.LUNCH })
        assertEquals(dinner.copy(side = null).main(), removed[5].meals.single { it.slot == MealSlot.DINNER }.main())
    }

    /**
     * A batch whose last day was yesterday with a share skipped and a meal never marked (issue 264), prepped in a newer
     * app: the installed app reads what is due, of which it does not know this, and marks the meal it shows. On a server
     * of its own, whose today moves from the day the batch is made to the day after its last day.
     */
    @Test
    fun theInstalledAppReadsWhatIsDueWithABatchPastItsLastDay() = testApplication {
        val http = start()
        http.put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }.read<ProfileState>()
        val stew = http.post("/api/recipes") { setBody(RecipeInput("My stew", 2, "400 | g | chicken fillet", "Stew.")) }.read<RecipeDetail>().summary
        val keeps = http.put("/api/recipes/${stew.id}/keeps") { setBody("""{"days":2}""") }
        assertTrue(keeps.status.isSuccess(), keeps.bodyAsText())
        // Made on Tuesday and kept two days: its last day is Thursday, so it is told on Friday.
        val madeOn = java.time.LocalDate.parse("2026-11-17")
        today = madeOn
        val dates = (0L..2L).map { madeOn.plusDays(it) }
        fun dayOf(date: java.time.LocalDate) = (date.toEpochDay() - Batch.mondayOf(date).toEpochDay()).toInt()
        fun mealPath(date: java.time.LocalDate) = "/api/plans/${Batch.mondayOf(date)}/meals/${dayOf(date)}/DINNER"
        val meals = dates.joinToString(",", "[", "]") { """{"date":"$it","slot":"DINNER"}""" }
        http.post("/api/plans/${Batch.mondayOf(madeOn)}/preps") { setBody("""{"recipeId":${stew.id},"madeOn":"$madeOn","meals":$meals}""") }.read<WeekPlan>()
        http.put("${mealPath(dates[0])}/status") { setBody(MealStatus.EATEN) }.read<WeekPlan>()
        http.put("${mealPath(dates[1])}/status") { setBody(MealStatus.SKIPPED) }.read<WeekPlan>()
        today = madeOn.plusDays(3)

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
     * A batch eaten as meals and as a side (issue 269), made by eating Tuesday's dinner; Wednesday's dinner, with the side
     * next to it, skipped; Thursday's dinner never marked. The installed app reads the side in the box as a side
     * ([SparePortion.side]) and, the morning after the last day, the batch as one of meals ([PrepPastLastDay.side] is
     * only true when every share is a side), with its portion and its side counted together: what it tells is "Throw
     * away 1 portion", where a newer app tells the side apart.
     */
    @Test
    fun theInstalledAppReadsTheBoxAndWhatIsDueOfABatchOfMealsAndSides() = testApplication {
        val http = start()
        http.put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }.read<ProfileState>()
        val stew = http.post("/api/recipes") { setBody(RecipeInput("My stew", 2, "400 | g | chicken fillet", "Stew.")) }.read<RecipeDetail>().summary
        val bowl = http.post("/api/recipes") { setBody(RecipeInput("My bowl", 1, "100 | g | rice", "Cook.")) }.read<RecipeDetail>().summary
        assertTrue(http.put("/api/recipes/${stew.id}/keeps") { setBody("""{"days":2}""") }.status.isSuccess())
        today = java.time.LocalDate.parse("2026-11-17")
        http.put("/api/plans/$week/meals/2/DINNER") { setBody("""{"recipeId":${bowl.id}}""") }.read<WeekPlan>()
        val shares = """[{"date":"2026-11-17","slot":"DINNER"},{"date":"2026-11-18","slot":"DINNER","side":0.5},{"date":"2026-11-19","slot":"DINNER"}]"""
        http.post("/api/plans/$week/preps") { setBody("""{"recipeId":${stew.id},"madeOn":"2026-11-17","meals":$shares}""") }.read<WeekPlan>()
        http.put("/api/plans/$week/meals/1/DINNER/status") { setBody(MealStatus.EATEN) }.read<WeekPlan>()
        http.put("/api/plans/$week/meals/2/DINNER/status") { setBody(MealStatus.SKIPPED) }.read<WeekPlan>()

        val spare = http.read<List<SparePortion>>("/api/preps/spare/2026-11-18").single()
        assertEquals(Triple("My stew", 0.5, true), Triple(spare.title, spare.portions, spare.side), "the side in the box is a side")

        today = java.time.LocalDate.parse("2026-11-20")
        val due = http.read<NotificationFacts>("/api/notifications").prepsPastLastDay.single()
        assertEquals(listOf(false, 1, 1, "2026-11-19"), listOf(due.side, due.left, due.unmarked, due.firstUnmarked), "told as a batch of meals")
        assertTrue(http.get("/api/notifications").bodyAsText().contains("\"leftSides\":1"), "a newer app tells the side apart")
    }

    /**
     * A standard meal (issue 265) a newer app marked and chose, eaten, in place of a skipped later portion of a prepped
     * batch that was made, whose share is in the box. The installed app reads the week, the day, the box, both lists,
     * what is due and the recipes, of which it knows none of this; it saves the standard meal's settings, which leaves it
     * one, and un-eats and removes it, which puts the skipped portion back in its slot.
     */
    @Test
    fun theInstalledAppReadsAStandardMealInPlaceOfASkippedPreppedPortion() = testApplication {
        val http = start()
        http.put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }.read<ProfileState>()
        val stew = http.post("/api/recipes") { setBody(RecipeInput("My stew", 4, "800 | g | chicken fillet", "Stew.", mealSlots = listOf(MealSlot.LUNCH, MealSlot.DINNER))) }
            .read<RecipeDetail>().summary
        val toast = http.post("/api/recipes") { setBody(RecipeInput("My toast", 1, "150 | g | rice", "Toast.", mealSlots = listOf(MealSlot.LUNCH))) }
            .read<RecipeDetail>().summary
        // In a newer app: the stew keeps three days and is prepped for Tuesday's dinner and Wednesday's lunch; the toast is a standard meal.
        assertTrue(http.put("/api/recipes/${stew.id}/keeps") { setBody("""{"days":3}""") }.status.isSuccess())
        val shares = """[{"date":"2026-11-17","slot":"DINNER"},{"date":"2026-11-18","slot":"LUNCH"}]"""
        http.post("/api/plans/$week/preps") { setBody("""{"recipeId":${stew.id},"madeOn":"2026-11-17","meals":$shares}""") }.read<WeekPlan>()
        assertTrue(http.put("/api/recipes/${toast.id}/standard") { setBody("""{"standard":true}""") }.status.isSuccess())
        http.put("/api/plans/$week/meals/1/DINNER/status") { setBody(MealStatus.EATEN) }.read<WeekPlan>()
        http.put("/api/plans/$week/meals/2/LUNCH/status") { setBody(MealStatus.SKIPPED) }.read<WeekPlan>()
        assertTrue(http.put("/api/plans/$week/meals/2/LUNCH/standard") { setBody("""{"recipeId":${toast.id},"eaten":true}""") }.status.isSuccess())

        val lunch = http.read<WeekPlan>("/api/plans/$week").days[2].meals.single { it.slot == MealSlot.LUNCH }
        assertEquals(Triple(toast.id, MealStatus.EATEN, true), Triple(lunch.recipeId, lunch.status, lunch.locked))
        assertEquals(toast.id, http.read<DayView>("/api/days/2026-11-18").meals.single { it.slot == MealSlot.LUNCH }.recipeId)
        val spare = http.read<List<SparePortion>>("/api/preps/spare/2026-11-18").single()
        assertEquals("2026-11-18" to MealSlot.LUNCH, spare.skipped.date to spare.skipped.slot, "the skipped portion is in the box")
        http.read<ShoppingList>("/api/plans/$week/shopping")
        http.read<NotificationFacts>("/api/notifications")
        assertTrue(http.read<List<RecipeSummary>>("/api/recipes").any { it.id == toast.id })

        // Its settings saved as the installed app saves them: still a standard meal.
        http.put("/api/recipes/${toast.id}/settings") { setBody(RecipeSettings(RecipeKind.MEAL, listOf(MealSlot.LUNCH))) }.read<RecipeDetail>()
        assertTrue(http.get("/api/recipes/${toast.id}").bodyAsText().contains("\"standardMeal\":true"))

        // Un-eaten and removed: the skipped portion of the stew is back in Wednesday's lunch.
        http.put("/api/plans/$week/meals/2/LUNCH/status") { setBody(MealStatus.PLANNED) }.read<WeekPlan>()
        val back = http.delete("/api/plans/$week/meals/2/LUNCH").read<WeekPlan>().days[2].meals.single { it.slot == MealSlot.LUNCH }
        assertEquals(Triple(stew.id, MealStatus.SKIPPED, true), Triple(back.recipeId, back.status, back.leftover))
    }

    /**
     * A week planned on the planning screen of a newer app (issue 255): two dinners chosen and a recipe hidden, not yet on
     * days, then "Done", then one of the dinners moved to another day. The installed app reads the week, its day, the list
     * and what is due at each step, of which it knows none of this, and its own Generate keeps the chosen dinners, which
     * are locked as recipes chosen by hand.
     */
    @Test
    fun theInstalledAppReadsAWeekPlannedOnThePlanningScreen() = testApplication {
        val http = start()
        http.post("/api/sync/cooking").read<SyncResult>()
        http.addWholeBank()
        http.put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }.read<ProfileState>()
        val recipes = http.read<List<RecipeSummary>>("/api/recipes").map { it.id }
        suspend fun readsTheWeek(): WeekPlan {
            http.read<DayView>("/api/days/2026-11-18")
            http.read<ShoppingList>("/api/plans/$week/shopping")
            http.read<NotificationFacts>("/api/notifications")
            return http.read<WeekPlan>("/api/plans/$week")
        }

        for (id in recipes.take(2)) assertTrue(http.put("/api/plans/$week/planning/picks/$id").status.isSuccess())
        assertTrue(http.put("/api/plans/$week/planning/not-this-week/${recipes[2]}").status.isSuccess())
        assertTrue(http.get("/api/plans/$week").bodyAsText().contains("\"dinnersChosen\":2"), "a newer app sees the dinners chosen")
        assertTrue(readsTheWeek().days.all { it.meals.isEmpty() }, "chosen, not yet on days")

        val done = http.post("/api/plans/$week/planning/done")
        assertTrue(done.status.isSuccess(), done.bodyAsText())
        fun WeekPlan.chosen() = days.flatMap { it.meals }.filter { it.slot == MealSlot.DINNER && it.recipeId in recipes.take(2) && it.locked }
        val chosen = readsTheWeek().chosen()
        assertEquals(recipes.take(2).toSet(), chosen.map { it.recipeId }.toSet())
        assertEquals(chosen, http.post("/api/plans/$week/generate").read<WeekPlan>().chosen(), "the installed app's Generate keeps them")

        val to = (chosen[0].day + 1) % 7
        val moved = http.post("/api/plans/$week/meals/${chosen[0].day}/DINNER/move") { setBody("""{"toDay":$to}""") }
        assertTrue(moved.status.isSuccess(), moved.bodyAsText())
        assertEquals(chosen[0].recipeId, readsTheWeek().days[to].meals.single { it.slot == MealSlot.DINNER }.recipeId)
    }

    /**
     * A recipe whose lines a newer app saved with their ingredients (issue 340). The installed app reads it, each line with
     * its ingredient, and saves it as it does, as text. A line whose text it left as it was keeps its ingredient, also when
     * its amount changed; one whose text it changed is looked up by the new text, as any line of text is.
     */
    @Test
    fun theInstalledAppReadsAndSavesAsTextARecipeWithLinesOfTheirOwnIngredient() = testApplication {
        val http = start()
        val meat = http.post("/api/ingredients") { setBody(IngredientInput("Chicken", nevoCode = 1634)) }.read<Ingredient>()
        val lines = """[{"amount":400.0,"unit":"g","text":"mystery meat","ingredientId":${meat.id}},{"amount":200.0,"unit":"g","text":"rice"}]"""
        val made = http.post("/api/recipes") { setBody("""{"title":"Bowl","servings":2,"lines":$lines}""") }.read<RecipeDetail>()
        val id = made.summary.id
        val rice = made.ingredients[1].ingredientId
        assertEquals(Triple(meat.id, "Chicken", 400.0), made.ingredients[0].let { Triple(it.ingredientId, it.ingredientName, it.grams) })
        assertEquals(made, http.read<RecipeDetail>("/api/recipes/$id"))

        // Saved as its editor shows the lines, with more meat: the meat is the chicken still.
        val more = http.put("/api/recipes/$id") { setBody(RecipeInput("Bowl", 2, "500 | g | mystery meat\n200 | g | rice")) }.read<RecipeDetail>()
        assertEquals(listOf(meat.id, rice), more.ingredients.map { it.ingredientId })
        assertEquals(listOf(500.0, 200.0), more.ingredients.map { it.line.amount })
        // Its text changed: no ingredient of its own any more, and nothing the account linked that text to.
        val renamed = http.put("/api/recipes/$id") { setBody(RecipeInput("Bowl", 2, "500 | g | mystery meat, diced\n200 | g | rice")) }.read<RecipeDetail>()
        assertEquals(listOf(null, rice), renamed.ingredients.map { it.ingredientId })
    }

    /**
     * A line a newer app saved with a `|` or a line break in its text, unit or group (issue 342) is stored in the form the
     * installed app's editor reads back: a line break as a space, a `|` in a text as it is, which the import format reads as
     * part of the text after the second `|`. The installed app shows the lines as that text, one per line and without the
     * headings (it never showed them), and saves them as they were.
     */
    @Test
    fun theInstalledAppSavesAgainALineWhoseTextHadABarOrALineBreak() = testApplication {
        val http = start()
        val lines = """[{"amount":500.0,"unit":"g","text":"chicken | skin on"},{"amount":2.0,"unit":"tbsp\n","text":"soy\nsauce","group":"Sauce\r\nand dip"},""" +
            """{"text":"salt\nto taste | or not","group":"Sauce\r\nand dip"}]"""
        val made = http.post("/api/recipes") { setBody("""{"title":"Bowl","servings":2,"lines":$lines}""") }.read<RecipeDetail>()
        assertEquals(listOf("chicken | skin on", "soy sauce", "salt to taste | or not"), made.ingredients.map { it.line.item })
        assertEquals(listOf(null, "Sauce and dip", "Sauce and dip"), made.ingredients.map { it.line.group })

        // As the installed editor writes a line: `amount | unit | item`, the amount in English, an empty part for none.
        val text = made.ingredients.joinToString("\n") { mapped ->
            with(mapped.line) { "${amount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()} | ${unit.orEmpty()} | $item" }
        }
        val saved = http.put("/api/recipes/${made.summary.id}") { setBody(RecipeInput("Bowl", 2, text)) }.read<RecipeDetail>()
        assertEquals(made.ingredients.map { it.line.copy(group = null) }, saved.ingredients.map { it.line })
    }

    /**
     * No enum of the frozen types may have a value the current one lacks: an installed app fails to
     * read a response with a value it does not know, whatever else the response holds. The check
     * covers every enum under `released/` by itself, so a new frozen enum is covered after a freeze.
     */
    /**
     * With `MEALPLANNER_OIDC_GROUP` on (issue 314). The installed app asks the provider for exactly the scopes the
     * server names (v0.1.9's `Login.start` joins `AuthConfig.scopes`), so a login it starts from then on asks for the
     * groups as well; a member then uses it as before. A login refused reads as `{"error": ...}`, the sentence the
     * installed app shows for any request that fails.
     */
    @Test
    fun theInstalledAppIsToldTheGroupsScopeAndAMemberReadsAsBefore() = testApplication {
        val http = start(group = "mealplanner", token = "t~mealplanner")
        assertEquals(LOGIN_SCOPES + "groups", http.read<AuthConfig>("/api/auth/config").scopes)
        assertEquals("t", http.read<Me>("/api/me").username)
        assertTrue(http.post("/api/sync/cooking").read<SyncResult>().added > 0)
        http.read<CookingSyncStatus>("/api/sync/cooking")

        val others = createClient {}
        for (token in listOf("t~family", "t")) {
            val refused = others.get("/api/me") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, refused.status, token)
            val error = releasedJson.decodeFromString<Map<String, String>>(refused.bodyAsText())["error"]
            assertTrue(error != null && error.endsWith("ask the person who runs this server.", ignoreCase = true), "$token: $error")
        }
    }

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
