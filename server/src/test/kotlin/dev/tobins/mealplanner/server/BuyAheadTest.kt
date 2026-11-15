package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.IngredientSection
import dev.tobins.mealplanner.api.KeepsChoice
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.server.offers.OfferSource
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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Buying keepable offers of this week for meals of next week, and stocking up on staples (issue 70). */
class BuyAheadTest {
    private class FixedOffers(override val store: Store, val offers: List<Offer>) : OfferSource {
        override suspend fun fetch() = offers
    }

    private val week = "2026-11-16"
    private val nextWeek = "2026-11-23"

    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|2336|Rundvlees rauw|Beef raw||per 100g|200|20|13|5|0|0|0|60",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|1634|Kipfilet rauw|Chicken fillet raw||per 100g|200|20|13|5|0|0|0|60",
        "NEVO-Online 2025 9.0|Groente|Vegetables|2346|Sla rauw|Lettuce raw||per 100g|15|1|0|0|2|1|2|5",
        "NEVO-Online 2025 9.0|Graanproducten|Cereal products|5|Rijst wit rauw|Rice white raw||per 100g|350|7|1|0|78|0|1|5",
        "NEVO-Online 2025 9.0|Vetten|Fats and oils|601|Olijfolie|Olive oil||per 100g|900|0|100|14|0|0|0|0",
    ).joinToString("\r\n")

    /** Four servings of a dinner of 1.2 kg of [meat] with lettuce, and with rice unless [rice] is false. */
    private fun meal(title: String, meat: String, rice: Boolean = true) =
        "---\ntitle: $title\n---\n```ingredients\nservings: 4\n1200 | g | $meat\n200 | g | lettuce\n" + (if (rice) "200 | g | rice\n" else "") + "```"

    private lateinit var repo: File

    /**
     * This week's offers, all at Jumbo: chicken at 70% off (this week only), lettuce at half price
     * (this week only, but it doesn't keep), rice at 40% off (this week and next) and olive oil at
     * 25% off.
     */
    private fun ApplicationTestBuilder.start(): HttpClient {
        repo = createTempDirectory("cooking").toFile()
        // This week's plan is made of fillers only, so next week's provisional choice has the rest to choose from.
        for (i in 0..2) write("filler-$i", meal("Filler $i", "beef", rice = false))
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = repo,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        val thisWeekOnly = "2026-11-22"
        val jumbo = FixedOffers(
            Store.JUMBO,
            listOf(
                Offer(Store.JUMBO, "Jumbo Kipfilet 1 kg", "70% korting", packSize = "1 kg", validFrom = week, validUntil = thisWeekOnly),
                Offer(Store.JUMBO, "Jumbo Sla", "50% korting", validFrom = week, validUntil = thisWeekOnly),
                Offer(Store.JUMBO, "Jumbo Rijst 1 kg", "40% korting", packSize = "1 kg", validFrom = week, validUntil = "2026-11-29"),
                Offer(Store.JUMBO, "Jumbo Olijfolie 1 l", "25% korting", packSize = "1 l", validFrom = week, validUntil = thisWeekOnly),
            ),
        )
        application { module(config, openDatabase(config.databaseUrl).second, listOf(jumbo), priceSource = null, generator = null, verifier = TestTokens) }
        return createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
    }

    private fun write(name: String, text: String) = File(repo, "content/recipes/$name.md").apply { parentFile.mkdirs() }.writeText(text)

    private suspend fun HttpClient.ingredient(name: String) = get("/api/ingredients").body<List<Ingredient>>().single { it.name == name }

    /**
     * Plans this week from the fillers, then adds five chicken and five beef dinners that are alike
     * in everything but the meat; both meats cost €10 a kg. Olive oil and spelt flour are staples.
     */
    private suspend fun HttpClient.ready(): HttpClient = apply {
        post("/api/sync/cooking")
        post("/api/offers/refresh")
        put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        post("/api/ingredients") { setBody(IngredientInput("Spelt flour", pricePerKg = 1.0)) }
        for ((name, price, section) in listOf(
            Triple("Beef", 10.0, "MEAT"), Triple("Chicken fillet", 10.0, "MEAT"), Triple("Lettuce", 10.0, "FRUIT_VEG"),
            Triple("White rice", 3.0, "WORLD"), Triple("Olive oil", 8.0, "SAUCES"), Triple("Spelt flour", 1.0, "BAKING"),
        )) {
            val ingredient = ingredient(name)
            put("/api/ingredients/${ingredient.id}") {
                setBody(IngredientInput(ingredient.name, ingredient.food?.nevoCode, ingredient.gramsPerPiece, ingredient.gramsPerMl, ingredient.category, pricePerKg = price))
            }
            put("/api/ingredients/${ingredient.id}/section") { setBody(SectionChoice(section)) }
        }
        for (staple in listOf("Olive oil", "Spelt flour")) put("/api/pantry/staples/${ingredient(staple).id}")
        post("/api/plans/$week/generate")
        for (i in 0..4) {
            write("chicken-$i", meal("Chicken $i", "chicken fillet"))
            write("beef-$i", meal("Beef $i", "beef"))
        }
        post("/api/sync/cooking")
    }

    private suspend fun HttpClient.list(weekStart: String = week) = get("/api/plans/$weekStart/shopping").body<ShoppingList>()

    private suspend fun HttpClient.tick(key: String, checked: Boolean = true) =
        put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, checked)) }

    private suspend fun HttpClient.onHand() = get("/api/pantry").body<Pantry>().onHand.map { it.name }.toSet()

    private fun ShoppingItem.saving() = assertNotNull(regularPrice) * (1 - assertNotNull(offers.mapNotNull { it.paidShare }.minOrNull()))

    @Test
    fun aKeepableOfferOfThisWeekIsBoughtAheadForMealsOfNextWeek() = testApplication {
        val http = start().ready()
        val list = http.list()

        // Chicken keeps (in the freezer) and is on offer this week only: bought ahead for the three
        // chicken dinners that save most (all alike here), one batch of 1.2 kg each.
        val chicken = list.buyAhead.single()
        assertEquals("Chicken fillet", chicken.name)
        assertEquals("ahead:i${http.ingredient("Chicken fillet").id}", chicken.key)
        assertEquals(3, chicken.recipes.size, "${chicken.recipes}")
        assertTrue(chicken.recipes.all { it.startsWith("Chicken ") }, "${chicken.recipes}")
        assertEquals(3600.0, assertNotNull(chicken.grams), 1e-9)
        assertEquals(36.0 * 0.7, chicken.saving(), 1e-9)
        assertEquals("Meat", chicken.category)
        assertFalse(chicken.checked)
        // Lettuce is on offer this week only too, but doesn't keep; rice keeps, but is on offer next
        // week as well; beef is not on offer at all.
        assertTrue(list.buyAhead.none { it.name in setOf("Lettuce", "White rice", "Beef") })
        // This week's list itself is the fillers' and has no chicken: the advice is apart from it.
        assertTrue(list.items.none { it.name == "Chicken fillet" })
        assertTrue(list.items.none { it.key.startsWith("ahead:") || it.key.startsWith("stock:") })
    }

    @Test
    fun aTickedBuyAheadItemIsOnHandNextWeekAndPullsItsMealsIntoThatPlan() = testApplication {
        val http = start().ready()
        val key = http.list().buyAhead.single().key
        assertFalse("Chicken fillet" in http.onHand(), "not before it is ticked")

        val ticked = http.tick(key).body<ShoppingList>()
        assertTrue(ticked.buyAhead.single().checked, "it stays on the list, ticked")
        assertTrue("Chicken fillet" in http.onHand())

        // Next week's plan is made as usual, with the chicken on hand: of two meats alike in every
        // other way (next week neither is on offer), it cooks every chicken dinner first. The bonus
        // for an ingredient on hand (0.5) is larger than the planner's random jitter (0.3).
        val next = http.post("/api/plans/$nextWeek/generate").body<WeekPlan>()
        val cooked = next.days.flatMap { it.meals }.filter { !it.leftover }.groupingBy { it.title.substringBefore(' ') }.eachCount()
        assertEquals(5, cooked["Chicken"], "$cooked")
        assertTrue(cooked.getValue("Beef") < 5, "$cooked")
        assertTrue(http.list(nextWeek).items.single { it.name == "Chicken fillet" }.onHand, "check the freezer before buying")

        // Eating a chicken meal next week uses it up.
        val chickenMeal = next.days.flatMap { it.meals }.first { it.title.startsWith("Chicken") }
        http.put("/api/plans/$nextWeek/meals/${chickenMeal.day}/${chickenMeal.slot}/status") { setBody(MealStatus.EATEN) }
        assertFalse("Chicken fillet" in http.onHand())
    }

    @Test
    fun anItemNotTickedIsNotOnHandAndUntickingTakesItBack() = testApplication {
        val http = start().ready()
        val key = http.list().buyAhead.single().key
        http.post("/api/plans/$nextWeek/generate")
        assertFalse(http.list(nextWeek).items.single { it.name == "Chicken fillet" }.onHand)

        http.tick(key)
        val chicken = http.ingredient("Chicken fillet")
        assertTrue(http.list(nextWeek).items.single { it.name == "Chicken fillet" }.onHand)
        http.tick(key, checked = false)
        assertFalse("Chicken fillet" in http.onHand())
        assertFalse(http.list(nextWeek).items.single { it.name == "Chicken fillet" }.onHand)

        // What was on hand by hand stays when a tick is taken back.
        http.put("/api/pantry/on-hand/${chicken.id}")
        http.tick(key)
        http.tick(key, checked = false)
        assertTrue("Chicken fillet" in http.onHand())
    }

    @Test
    fun aStapleOnOfferIsAdvisedToStockUpOnAndTickingItPutsItOnHand() = testApplication {
        val http = start().ready()
        val stockUp = http.list().stockUp
        // Olive oil is a staple on offer; rice keeps for months, a recipe uses it and it is on offer.
        // Spelt flour is a staple that is not on offer; lettuce is on offer but doesn't keep.
        assertEquals(listOf("Olive oil", "White rice"), stockUp.map { it.name }, "in walking order")
        val oil = stockUp.first()
        assertEquals(920.0, assertNotNull(oil.grams), 1e-9, "one pack of 1 l")
        assertEquals(0.92 * 8.0 * 0.25, oil.saving(), 1e-9)
        val rice = stockUp.last()
        assertEquals(3.0 * 0.4, rice.saving(), 1e-9)
        assertEquals(listOf(Store.JUMBO), rice.offers.map { it.store })

        val ticked = http.tick(rice.key).body<ShoppingList>()
        assertTrue(ticked.stockUp.single { it.name == "White rice" }.checked)
        assertTrue("White rice" in http.onHand())
        assertEquals(stockUp.map { it.name }, http.list().stockUp.map { it.name }, "ticked, it stays on the list")

        // One already on hand by hand is not advised.
        http.put("/api/pantry/on-hand/${http.ingredient("Olive oil").id}")
        assertEquals(listOf("White rice"), http.list().stockUp.map { it.name })
    }

    @Test
    fun theAdviceGoesByTheStoresInUseAndWhatKeeps() = testApplication {
        val http = start().ready()
        val chicken = http.ingredient("Chicken fillet")
        val lettuce = http.ingredient("Lettuce")

        // Corrected by hand: chicken that is never frozen doesn't keep, lettuce from a long-life bag does.
        val corrected = http.put("/api/ingredients/${chicken.id}/keeps") { setBody(KeepsChoice(false)) }.body<IngredientSection>()
        assertFalse(corrected.keeps)
        assertTrue(corrected.keepsAutomatic && corrected.keepsByHand)
        http.put("/api/ingredients/${lettuce.id}/keeps") { setBody(KeepsChoice(true)) }
        assertEquals(listOf("Lettuce"), http.list().buyAhead.map { it.name })
        val automatic = http.put("/api/ingredients/${chicken.id}/keeps") { setBody(KeepsChoice(null)) }.body<IngredientSection>()
        assertTrue(automatic.keeps && !automatic.keepsByHand)
        assertEquals(listOf("Lettuce", "Chicken fillet"), http.list().buyAhead.map { it.name }, "in walking order")
        val sections = http.get("/api/ingredients/sections").body<List<IngredientSection>>()
        assertTrue(sections.single { it.ingredientId == lettuce.id }.keeps)
        assertFalse(sections.single { it.ingredientId == lettuce.id }.keepsAutomatic)

        // Jumbo has every offer: without it there is nothing to buy ahead or stock up on.
        http.put("/api/shops") { setBody(ShopsSetting(listOf(Store.AH, Store.LIDL))) }
        val withoutJumbo = http.list()
        assertEquals(emptyList(), withoutJumbo.buyAhead)
        assertEquals(emptyList(), withoutJumbo.stockUp)
    }

    @Test
    fun dismissingOrSwitchingOffTheAdviceChangesNothingElse() = testApplication {
        val http = start().ready()
        val plan = http.get("/api/plans/$week").bodyAsText()
        val before = http.list()
        assertTrue(before.buyAhead.isNotEmpty() && before.stockUp.isNotEmpty())

        val dismissed = http.put("/api/plans/$week/shopping/dismissed/buy-ahead").body<ShoppingList>()
        assertTrue(dismissed.buyAheadDismissed)
        assertEquals(emptyList(), dismissed.buyAhead)
        assertEquals(before.items, dismissed.items)
        assertEquals(before.stockUp, dismissed.stockUp)
        assertEquals(plan, http.get("/api/plans/$week").bodyAsText())
        assertTrue(http.list().buyAheadDismissed, "until shown again")
        val shown = http.delete("/api/plans/$week/shopping/dismissed/buy-ahead").body<ShoppingList>()
        assertFalse(shown.buyAheadDismissed)
        assertEquals(before.buyAhead, shown.buyAhead)

        http.put("/api/plans/$week/shopping/dismissed/stock-up")
        assertEquals(emptyList(), http.list().stockUp)
        assertEquals(before.buyAhead, http.list().buyAhead)
        // Only for that week.
        assertFalse(http.list(nextWeek).stockUpDismissed)
        assertEquals(HttpStatusCode.BadRequest, http.put("/api/plans/$week/shopping/dismissed/everything").status)
        assertEquals(HttpStatusCode.BadRequest, http.tick("dismissed:buy-ahead").status, "not through the ticks")

        // The switch in the settings: off for every week until switched on again.
        assertEquals(ShoppingAdvice(), http.get("/api/shopping-advice").body<ShoppingAdvice>(), "on until switched off")
        http.put("/api/shopping-advice") { setBody(ShoppingAdvice(buyAhead = false)) }
        val off = http.list()
        assertEquals(emptyList(), off.buyAhead)
        assertFalse(off.buyAheadDismissed)
        assertEquals(before.items, off.items)
        assertEquals(plan, http.get("/api/plans/$week").bodyAsText())
        http.put("/api/shopping-advice") { setBody(ShoppingAdvice(stockUp = false)) }
        assertEquals(before.buyAhead, http.list().buyAhead)
    }

    @Test
    fun buyAheadKeysMustNameAnIngredient() = testApplication {
        val http = start().ready()
        assertEquals(HttpStatusCode.BadRequest, http.tick("ahead:ichicken").status)
        assertEquals(HttpStatusCode.NotFound, http.tick("ahead:i999999").status)
        assertTrue(http.list().buyAhead.none { it.checked }, "nothing was stored")
    }

    @Test
    fun theAppOfV012ReadsOnlyThisWeeksNeedsAsItsList() = testApplication {
        val http = start().ready()
        val list = http.list()
        assertTrue(list.buyAhead.isNotEmpty() && list.stockUp.isNotEmpty())

        // v0.1.2 reads with ignoreUnknownKeys (its ApiClient); its types lack the advice. What it
        // shows is exactly this week's needs, ticks and all.
        val v012 = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        val old = v012.decodeFromString<V012ShoppingList>(http.get("/api/plans/$week/shopping").bodyAsText())
        assertEquals(list.items.map { it.key to it.name }, old.items.map { it.key to it.name })
        assertTrue(old.items.none { it.name == "Chicken fillet" || it.name == "Olive oil" })
        // Ticking with its keys works as before, and the advice stays out of its list.
        val ticked = v012.decodeFromString<V012ShoppingList>(http.tick(old.items.first().key).bodyAsText())
        assertTrue(ticked.items.first().checked)
        assertEquals(old.items.map { it.key }, ticked.items.map { it.key })
    }

    /** `ShoppingList` and `ShoppingItem` exactly as v0.1.2 has them (offers left out: the store test reads those). */
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
        val imageUrl: String? = null,
    )
}
