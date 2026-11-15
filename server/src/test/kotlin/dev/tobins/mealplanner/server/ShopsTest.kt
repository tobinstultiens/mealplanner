package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.server.generation.GenerationContext
import dev.tobins.mealplanner.server.generation.RecipeGenerator
import dev.tobins.mealplanner.server.offers.OfferSource
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Stores I shop at" as an account setting, and the saving per store on the shopping list. */
class ShopsTest {
    private class FixedOffers(override val store: Store, val offers: List<Offer>) : OfferSource {
        override suspend fun fetch() = offers
    }

    private val week = "2026-11-16"

    /**
     * Five chicken and five beef dinners, the same in calories and protein. Chicken costs €20 a kg
     * and beef €10, but Jumbo has chicken at 70% off this week, which makes it the cheaper one by
     * far: the gaps in cost outweigh the planner's random jitter either way, so which meat it
     * prefers depends only on whether Jumbo's offer counts.
     */
    private fun ApplicationTestBuilder.start(): HttpClient {
        val nevo = listOf(
            "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
            "NEVO-Online 2025 9.0|Vlees|Meat and poultry|2336|Rundvlees rauw|Beef raw||per 100g|200|20|13|5|0|0|0|60",
            "NEVO-Online 2025 9.0|Vlees|Meat and poultry|1634|Kipfilet rauw|Chicken fillet raw||per 100g|200|20|13|5|0|0|0|60",
        ).joinToString("\r\n")
        val repo = createTempDirectory("cooking").toFile()
        for (i in 0..4) {
            for (meat in listOf("beef", "chicken fillet")) {
                File(repo, "content/recipes/$meat-$i.md").apply { parentFile.mkdirs() }
                    .writeText("---\ntitle: ${meat.substringBefore(' ').replaceFirstChar { it.uppercase() }} $i\n---\n```ingredients\nservings: 4\n1200 | g | $meat\n```")
            }
        }
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = repo,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        val jumbo = FixedOffers(Store.JUMBO, listOf(Offer(Store.JUMBO, "Jumbo Kipfilet 1 kg", "70% korting", packSize = "1 kg", validFrom = week, validUntil = "2026-11-22")))
        application { module(config, openDatabase(config.databaseUrl).second, listOf(jumbo), priceSource = null, generator = null, verifier = TestTokens) }
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
        post("/api/offers/refresh")
        put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        for ((name, price) in listOf("Beef" to 10.0, "Chicken fillet" to 20.0)) {
            val ingredient = get("/api/ingredients").body<List<Ingredient>>().single { it.name == name }
            put("/api/ingredients/${ingredient.id}") {
                setBody(IngredientInput(ingredient.name, ingredient.food?.nevoCode, ingredient.gramsPerPiece, ingredient.gramsPerMl, ingredient.category, pricePerKg = price))
            }
        }
    }

    /** How many meals of each meat are cooked fresh (not leftovers): "Chicken" and "Beef". */
    private fun WeekPlan.cooked(): Map<String, Int> = days.flatMap { it.meals }
        .filter { !it.leftover }
        .groupingBy { it.title.substringBefore(' ') }.eachCount()

    /** Each of the five recipes of [meat] is cooked, and fewer of the other meat's. */
    private fun assertPrefers(meat: String, plan: WeekPlan, message: String) {
        val cooked = plan.cooked()
        val other = if (meat == "Chicken") "Beef" else "Chicken"
        assertEquals(5, cooked[meat], "$message: $cooked")
        assertTrue(cooked.getValue(other) < 5, "$message: $cooked")
    }

    @Test
    fun aStoreSwitchedOffNoLongerPullsMealsIntoThePlan() = testApplication {
        val http = start().ready()

        // Every store counts until the account chooses: Jumbo's chicken fills the week first.
        val withJumbo = http.post("/api/plans/$week/generate").body<WeekPlan>()
        assertPrefers("Chicken", withJumbo, "on offer at Jumbo")
        assertNotEquals(withJumbo.costWithoutOffers, withJumbo.cost)

        assertEquals(ShopsSetting(listOf(Store.AH, Store.LIDL)), http.put("/api/shops") { setBody(ShopsSetting(listOf(Store.LIDL, Store.AH))) }.body<ShopsSetting>())
        val withoutJumbo = http.post("/api/plans/$week/generate").body<WeekPlan>()
        assertPrefers("Beef", withoutJumbo, "chicken is dear again without Jumbo")
        assertEquals(withoutJumbo.costWithoutOffers, withoutJumbo.cost, "no offer at a store in use")

        // The shopping list still names Jumbo's offer: the app decides what to show of it.
        val chicken = http.get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.name == "Chicken fillet" }
        assertEquals(listOf(Store.JUMBO), chicken.offers.map { it.store })

        // Back to every store.
        assertNull(http.put("/api/shops") { setBody(ShopsSetting(null)) }.body<ShopsSetting>().stores)
        assertPrefers("Chicken", http.post("/api/plans/$week/generate").body<WeekPlan>(), "every store again")
    }

    @Test
    fun recipesAreWrittenAroundOffersAtStoresInUseOnly() = runBlocking {
        val db = openDatabase("jdbc:sqlite::memory:").second
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, listOf(FixedOffers(Store.JUMBO, listOf(Offer(Store.JUMBO, "Jumbo Kipfilet 1 kg", "70% korting", validFrom = week, validUntil = "2026-11-22")))))
        offers.refresh()
        val notCalled = object : RecipeGenerator {
            override suspend fun generate(context: GenerationContext) = error("only the context is checked")
        }
        val user = UserScope(UserRepository(db).resolve(Identity("sub-a", "a")).id, db, foods, offers, PriceRepository(db, null, foods, offers), notCalled, createTempDirectory("images").toFile())
        val chicken = user.ingredients.create(IngredientInput("Chicken fillet"))
        user.ingredients.map(MappingRequest("kipfilet", ingredientId = chicken.id))
        user.profiles.save(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT))
        val today = LocalDate.parse(week)

        assertEquals(listOf("Chicken fillet"), user.generation!!.context(GenerateRequest(), today).onOffer)
        user.shops.save(ShopsSetting(listOf(Store.AH, Store.LIDL)))
        assertEquals(emptyList(), user.generation!!.context(GenerateRequest(), today).onOffer, "Jumbo's offer is not named to Claude")
    }

    @Test
    fun theStoresMustBeKnownAndAtLeastOne() = testApplication {
        val http = start()
        assertEquals(HttpStatusCode.BadRequest, http.put("/api/shops") { setBody(ShopsSetting(emptyList())) }.status)
        assertEquals(HttpStatusCode.BadRequest, http.put("/api/shops") { setBody("""{"stores":["ALDI"]}""") }.status)
        assertNull(http.get("/api/shops").body<ShopsSetting>().stores, "nothing was saved")
        val twice = http.put("/api/shops") { setBody("""{"stores":["LIDL","AH","LIDL"]}""") }.body<ShopsSetting>()
        assertEquals(listOf(Store.AH, Store.LIDL), twice.stores)
    }

    @Test
    fun shoppingItemsCarryTheRegularPriceAndEachOffersShare() = testApplication {
        val http = start().ready()
        http.post("/api/plans/$week/generate")

        val items = http.get("/api/plans/$week/shopping").body<ShoppingList>().items
        val chicken = items.single { it.name == "Chicken fillet" }
        val grams = assertNotNull(chicken.grams)
        assertEquals(grams / 1000 * 20.0, assertNotNull(chicken.regularPrice), 1e-9)
        assertEquals(0.3, assertNotNull(chicken.offers.single().paidShare), 1e-9)
        val beef = items.single { it.name == "Beef" }
        assertEquals(assertNotNull(beef.grams) / 1000 * 10.0, assertNotNull(beef.regularPrice), 1e-9)
        assertEquals(emptyList(), beef.offers)
    }

    @Test
    fun theAppOfV012StillReadsTheShoppingListAndThePlan() = testApplication {
        val http = start().ready()
        http.put("/api/shops") { setBody(ShopsSetting(listOf(Store.AH))) }
        http.post("/api/plans/$week/generate")

        // v0.1.2 reads with ignoreUnknownKeys (its ApiClient); its types lack the new fields.
        val v012 = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        val list = v012.decodeFromString<V012ShoppingList>(http.get("/api/plans/$week/shopping").bodyAsText())
        assertEquals(listOf(Store.JUMBO), list.items.single { it.name == "Chicken fillet" }.offers.map { it.store })
        // The plan's types did not change since v0.1.2: it reads as before.
        assertEquals(7, v012.decodeFromString<WeekPlan>(http.get("/api/plans/$week").bodyAsText()).days.size)
    }

    /** `ShoppingList`, `ShoppingItem` and `Offer` exactly as v0.1.2 has them. */
    @Serializable
    private data class V012ShoppingList(val weekStart: String, val items: List<V012ShoppingItem>, val staples: List<String>, val toTaste: List<String> = emptyList())

    @Serializable
    private data class V012ShoppingItem(
        val key: String,
        val name: String,
        val grams: Double?,
        val pieces: Double?,
        val category: String,
        val recipes: List<String>,
        val onHand: Boolean,
        val checked: Boolean,
        val offers: List<V012Offer> = emptyList(),
        val imageUrl: String? = null,
    )

    @Serializable
    private data class V012Offer(
        val store: Store,
        val title: String,
        val deal: String?,
        val price: Double? = null,
        val originalPrice: Double? = null,
        val packSize: String? = null,
        val category: String? = null,
        val validFrom: String,
        val validUntil: String,
        val url: String? = null,
        val ingredientId: Long? = null,
        val ingredientName: String? = null,
        val imageUrl: String? = null,
    )
}
