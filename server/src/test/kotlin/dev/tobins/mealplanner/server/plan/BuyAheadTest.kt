package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.IngredientSection
import dev.tobins.mealplanner.api.KeepsChoice
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferExclusionRequest
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingExtraInput
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShoppingRowKey
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.server.ServerConfig
import dev.tobins.mealplanner.server.TestTokens
import dev.tobins.mealplanner.server.UserScope
import dev.tobins.mealplanner.server.accounts.Identity
import dev.tobins.mealplanner.server.accounts.UserRepository
import dev.tobins.mealplanner.server.addWholeBank
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.ingredients.StockSource
import dev.tobins.mealplanner.server.module
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
import dev.tobins.mealplanner.server.recipes.GenerationService
import dev.tobins.mealplanner.server.recipes.RecipeLines
import dev.tobins.mealplanner.server.recipes.generation.GeneratedRecipe
import dev.tobins.mealplanner.server.recipes.generation.GenerationContext
import dev.tobins.mealplanner.server.recipes.generation.RecipeGenerator
import dev.tobins.mealplanner.server.prices.offers.OfferSource
import dev.tobins.mealplanner.server.releasedJson
import dev.tobins.mealplanner.server.shopping.gramsUsed
import dev.tobins.mealplanner.units.Dimension
import dev.tobins.mealplanner.released.api.ShoppingList as ReleasedShoppingList
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
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    private lateinit var db: Database

    /**
     * This week's offers, all at Jumbo: chicken at 70% off (this week only), lettuce at half price
     * (this week only, but it doesn't keep), rice at 40% off (this week and next) and olive oil at
     * 25% off.
     */
    private fun ApplicationTestBuilder.start(extraOffers: List<Offer> = emptyList()): HttpClient {
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
            ) + extraOffers,
        )
        db = openDatabase(config.databaseUrl).second
        // The plan week's Monday, so that a prep of the tests is made on a day still to come whenever they run.
        application { module(config, db, listOf(jumbo), priceSource = null, generator = null, verifier = TestTokens, today = { LocalDate.parse(week) }) }
        return createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
    }

    /** The test account's repositories, in this process: for what the API leaves to chance (the planner's seed) or doesn't show. */
    private fun scope(): UserScope {
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        val id = UserRepository(db).resolve(Identity("sub-t", "t", "T")).id
        return UserScope(id, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile())
    }

    private fun write(name: String, text: String) = File(repo, "content/recipes/$name.md").apply { parentFile.mkdirs() }.writeText(text)

    private suspend fun HttpClient.ingredient(name: String) = get("/api/ingredients").body<List<Ingredient>>().single { it.name == name }

    /**
     * Plans this week from the fillers, then adds five chicken and five beef dinners that are alike
     * in everything but the meat; both meats cost €10 a kg. Olive oil and spelt flour are staples.
     */
    private suspend fun HttpClient.ready(): HttpClient = apply {
        post("/api/sync/cooking")
        addWholeBank()
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
        addWholeBank()
    }

    private suspend fun HttpClient.list(weekStart: String = week) = get("/api/plans/$weekStart/shopping").body<ShoppingList>()

    private suspend fun HttpClient.tick(key: String, checked: Boolean = true, grams: Double? = null) =
        put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, checked, grams)) }

    private suspend fun HttpClient.onHand() = get("/api/pantry").body<Pantry>().onHand.map { it.name }.toSet()

    private suspend fun HttpClient.eat(weekStart: String, meal: PlannedMeal) =
        put("/api/plans/$weekStart/meals/${meal.day}/${meal.slot}/status") { setBody(MealStatus.EATEN) }

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
    fun theAdviceSaysWhenItsMealsAreOnlyASuggestion() = testApplication {
        val http = start().ready()
        assertTrue(http.list().buyAheadProvisional, "next week has no plan: the meals are a provisional pick")

        http.post("/api/plans/$nextWeek/generate")
        assertFalse(http.list().buyAheadProvisional, "next week is planned: the meals are its own")

    }

    /** Issue 263: next week's meals of a batch made this week leave fewer dinners to suggest, and none when they fill them all. */
    @Test
    fun aNextWeekWithOnlyABatchsMealsGetsDinnersOnlyForTheDaysItLeavesFree() = testApplication {
        val http = start().ready()
        val filler = http.get("/api/recipes").body<List<RecipeSummary>>().first { it.title == "Filler 0" }
        http.put("/api/recipes/${filler.id}/keeps") { setBody(RecipeKeeps(days = 7)) }
        // Made on Sunday's dinner and eaten at the next week's dinners from Monday to Saturday: Sunday's is the only one free.
        val dinners = (22..28).map { PrepSlot("2026-11-$it", MealSlot.DINNER) }
        val made = http.post("/api/plans/$week/preps") { setBody(PrepInput(filler.id, "2026-11-22", meals = dinners)) }
        assertEquals(HttpStatusCode.OK, made.status, made.bodyAsText())

        val list = http.list()
        assertTrue(list.buyAheadProvisional, "next week has no meals of its own yet")
        val chicken = list.buyAhead.single()
        assertEquals(1, chicken.recipes.size, "one dinner for the one day the batch leaves free: ${chicken.recipes}")
        assertEquals(1200.0, assertNotNull(chicken.grams), 1e-9)

        // With Sunday's dinner too the batch fills every dinner of next week: nothing to buy ahead for.
        val prep = http.get("/api/plans/$week").body<WeekPlan>().days.flatMap { it.meals }.firstNotNullOf { it.prep }
        http.delete("/api/plans/$week/preps/${prep.id}")
        val all = http.post("/api/plans/$week/preps") { setBody(PrepInput(filler.id, "2026-11-22", meals = dinners + PrepSlot("2026-11-29", MealSlot.DINNER))) }
        assertEquals(HttpStatusCode.OK, all.status, all.bodyAsText())
        val full = http.list()
        assertEquals(emptyList(), full.buyAhead)
        assertTrue(full.buyAheadProvisional, "next week still has no plan of its own, as the docs say")
    }

    @Test
    fun noSuggestionIsFlaggedWhenBuyAheadIsDismissedOrOff() = testApplication {
        val http = start().ready()
        http.put("/api/plans/$week/shopping/dismissed/buy-ahead")
        assertFalse(http.list().buyAheadProvisional, "dismissed")
        http.delete("/api/plans/$week/shopping/dismissed/buy-ahead")
        assertTrue(http.list().buyAheadProvisional)
        http.put("/api/shopping-advice") { setBody(ShoppingAdvice(buyAhead = false)) }
        assertFalse(http.list().buyAheadProvisional, "switched off")
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

        // Eating a chicken meal next week eats from it; bought for three, the rest stays on hand
        // (until it is used up: whatWasBoughtAheadLastsForTheMealsItWasBoughtFor).
        val chickenMeal = next.days.flatMap { it.meals }.first { it.title.startsWith("Chicken") }
        http.eat(nextWeek, chickenMeal)
        assertTrue("Chicken fillet" in http.onHand())
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

    /** "Not this ingredient" (issue 316): an offer the account excluded is no reason to buy ahead or to stock up. */
    @Test
    fun anOfferTheAccountSaidIsNotItsIngredientIsNoAdviceToBuyAheadOrStockUp() = testApplication {
        val http = start().ready()
        val before = http.list()
        assertEquals(listOf("Chicken fillet"), before.buyAhead.map { it.name })
        assertEquals(listOf("Olive oil", "White rice"), before.stockUp.map { it.name })
        val chicken = OfferExclusionRequest(Store.JUMBO, "Jumbo Kipfilet 1 kg", http.ingredient("Chicken fillet").id)
        val oil = OfferExclusionRequest(Store.JUMBO, "Jumbo Olijfolie 1 l", http.ingredient("Olive oil").id)

        for (exclusion in listOf(chicken, oil)) assertEquals(HttpStatusCode.OK, http.put("/api/offers/exclusions") { setBody(exclusion) }.status)

        val after = http.list()
        assertEquals(emptyList(), after.buyAhead)
        assertEquals(listOf("White rice"), after.stockUp.map { it.name })
        http.delete("/api/offers/exclusions") {
            url.parameters.append("store", "JUMBO")
            url.parameters.append("title", chicken.title)
            url.parameters.append("ingredientId", chicken.ingredientId.toString())
        }
        assertEquals(listOf("Chicken fillet"), http.list().buyAhead.map { it.name }, "taken back, the offer counts again")
    }

    @Test
    fun aDealThatNeedsSeveralPacksIsAdvisedInThosePacksAndTheirSavingCounts() = testApplication {
        val flour = Offer(Store.JUMBO, "Jumbo Spelt flour 1 kg", "1+1 gratis", packSize = "1 kg", validFrom = week, validUntil = "2026-11-22")
        val http = start(listOf(flour)).ready()
        val spelt = http.ingredient("Spelt flour")
        val row = http.list().stockUp.single { it.name == "Spelt flour" }
        assertEquals(2000.0, assertNotNull(row.grams), 1e-9, "two packs of 1 kg")
        assertEquals(2.0 * 1.0, assertNotNull(row.regularPrice), 1e-9)
        assertEquals(1.0, row.saving(), 1e-9, "the second pack is free")

        // The tick stocks both packs, whatever the app sends.
        http.tick(row.key, grams = 5.0)
        assertEquals(2000.0, left(spelt, week))
    }

    @Test
    fun aDealForMoreThanAReasonableNumberOfPacksIsNotAdvised() = testApplication {
        val flour = Offer(Store.JUMBO, "Jumbo Spelt flour 1 kg", "9 voor 5,00", packSize = "1 kg", validFrom = week, validUntil = "2026-11-22")
        val http = start(listOf(flour)).ready()
        assertTrue(http.list().stockUp.none { it.name == "Spelt flour" })
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
    fun whatWasBoughtAheadLastsForTheMealsItWasBoughtFor() = testApplication {
        val http = start().ready()
        // Bought ahead: one batch of 1.2 kg for each of three chicken dinners. Stocked up: a 1 kg bag of rice.
        val chicken = http.list().buyAhead.single()
        http.tick(chicken.key)
        http.tick(http.list().stockUp.single { it.name == "White rice" }.key)

        val next = http.post("/api/plans/$nextWeek/generate").body<WeekPlan>()
        val meals = next.days.flatMap { it.meals }
        val dinners = meals.filter { it.title.startsWith("Chicken") && !it.leftover }.sortedBy { it.day }
        assertTrue(dinners.size >= 3, "$dinners")
        // Each dinner is cooked with its leftovers: 1.2 kg for the four servings of a batch, scaled.
        var left = assertNotNull(chicken.grams)
        for (dinner in dinners) {
            val leftovers = meals.filter { it.leftover && it.recipeId == dinner.recipeId && it.day == dinner.day + 1 && it.slot == MealSlot.LUNCH }
            http.eat(nextWeek, dinner)
            http.eat(nextWeek, dinner) // marked eaten twice, it is eaten once
            left -= 1200.0 * (dinner.portions + leftovers.sumOf { it.portions }) / 4
            assertEquals(left >= IngredientRepository.USED_UP_GRAMS, "Chicken fillet" in http.onHand(), "$left g left after ${dinner.title}")
            if (dinner == dinners.first()) {
                assertTrue("Chicken fillet" in http.onHand(), "the first of its meals leaves the rest")
                assertTrue("White rice" in http.onHand(), "a meal uses 200 g of the bag at most")
            }
            // Its leftovers were cooked with it.
            leftovers.forEach { http.eat(nextWeek, it) }
            assertEquals(left >= IngredientRepository.USED_UP_GRAMS, "Chicken fillet" in http.onHand(), "$left g left after the leftovers")
        }
    }

    @Test
    fun withoutAnAmountTheFirstMealUsesItUpAsBefore() = testApplication {
        val http = start().ready()
        // The list doesn't advise beef, so a tick of it says nothing about how much was bought: like
        // an entry ticked before amounts were kept.
        http.tick("ahead:i${http.ingredient("Beef").id}")
        val next = http.post("/api/plans/$nextWeek/generate").body<WeekPlan>()
        val beef = next.days.flatMap { it.meals }.first { it.title.startsWith("Beef") && !it.leftover }
        assertTrue("Beef" in http.onHand())
        http.eat(nextWeek, beef)
        assertFalse("Beef" in http.onHand())
    }

    @Test
    fun markedOnHandByHandItStaysWhateverHappensToTheTick() = testApplication {
        val http = start().ready()
        val key = http.list().buyAhead.single().key
        val chicken = http.ingredient("Chicken fillet")
        http.tick(key)
        // Already on hand from the tick, the owner marks it on hand in the pantry too.
        http.put("/api/pantry/on-hand/${chicken.id}")
        http.tick(key, checked = false)
        assertTrue("Chicken fillet" in http.onHand(), "un-ticking leaves what was marked by hand")
        assertTrue(http.list().buyAhead.none { it.name == "Chicken fillet" }, "on hand by hand, it isn't advised any more")

        val next = http.post("/api/plans/$nextWeek/generate").body<WeekPlan>()
        next.days.flatMap { it.meals }.filter { it.title.startsWith("Chicken") }.forEach { http.eat(nextWeek, it) }
        assertTrue("Chicken fillet" in http.onHand(), "eating doesn't take away what was marked by hand")
        http.delete("/api/pantry/on-hand/${chicken.id}")
        assertFalse("Chicken fillet" in http.onHand(), "removing it by hand does, at once")
    }

    @Test
    fun thisWeeksListAndPlanLeaveOutWhatWasBoughtForNextWeek() = testApplication {
        val http = start().ready()
        // The list: lettuce ticked as bought ahead is for next week's meals; this week's need their own.
        val lettuce = http.ingredient("Lettuce")
        http.tick("ahead:i${lettuce.id}")
        assertEquals(setOf(lettuce.id), scope().stock.onHandFor(LocalDate.parse(nextWeek)))
        assertEquals(emptySet(), scope().stock.onHandFor(LocalDate.parse(week)))
        assertFalse(http.list().items.single { it.name == "Lettuce" }.onHand)
        http.post("/api/plans/$nextWeek/generate")
        assertTrue(http.list(nextWeek).items.single { it.name == "Lettuce" }.onHand)
        http.tick("ahead:i${lettuce.id}", checked = false)

        // The plan: this week's, with the same seeds, is the same whether or not rice was bought ahead
        // for next week. Rice on hand this week would change it: the chicken and beef dinners use rice,
        // the cheaper fillers that end most weeks don't.
        val weeks = scope().plan.weeks
        val seeds = 1L..12L
        fun plans() = seeds.map { seed -> weeks.generate(week, seed).days.flatMap { it.meals }.map { Triple(it.day, it.slot, it.recipeId) } }
        val without = plans()
        val rice = http.ingredient("White rice")
        http.tick("ahead:i${rice.id}")
        assertEquals(without, plans(), "bought ahead for next week, it isn't on hand this week")
        http.put("/api/pantry/on-hand/${rice.id}")
        assertNotEquals(without, plans(), "on hand this week, it would change the plan")
    }

    @Test
    fun recipeGenerationIsToldOnlyWhatIsAtHandForThisWeek() = testApplication {
        val http = start().ready()
        http.tick(http.list().buyAhead.single().key)
        http.tick(http.list().stockUp.single { it.name == "White rice" }.key)
        http.put("/api/pantry/on-hand/${http.ingredient("Lettuce").id}")
        val user = scope()
        val notCalled = object : RecipeGenerator {
            override suspend fun generate(context: GenerationContext): GeneratedRecipe = error("only the context is asked for")
        }
        val generation = GenerationService(notCalled, user.recipes, user.ingredients, user.stock, user.profiles, user.plan.weekMeals, OfferRepository(db, emptyList()))
        val wednesday = LocalDate.parse(week).plusDays(2)

        // The chicken is in the freezer for next week's meals: not something to use up now.
        assertEquals(setOf("White rice", "Lettuce"), generation.context(GenerateRequest(), wednesday).onHand.toSet())
        assertEquals(setOf("Chicken fillet", "White rice", "Lettuce"), generation.context(GenerateRequest(), wednesday.plusDays(7)).onHand.toSet())
    }

    @Test
    fun nextWeeksCostStaysAtItsOwnPricesWhenFoodWasBoughtAhead() = testApplication {
        val http = start().ready()
        http.post("/api/plans/$nextWeek/generate")
        val before = http.get("/api/plans/$nextWeek").body<WeekPlan>()
        assertNotNull(before.cost)

        // The saving shows where the money is spent: on this week's list. Next week's plan prices its
        // meals at next week's prices, so counting it there as well would show the saving twice.
        http.tick("ahead:i${http.ingredient("Chicken fillet").id}")
        val after = http.get("/api/plans/$nextWeek").body<WeekPlan>()
        assertEquals(before.cost, after.cost)
        assertEquals(before.costWithoutOffers, after.costWithoutOffers)
    }

    @Test
    fun aMealUsesTheWeightOfItsLinesScaledToItsPortions() {
        val chicken = Ingredient(1, "Chicken fillet", null, null, null, null, null, isStaple = false)
        val oil = Ingredient(2, "Olive oil", null, null, null, null, null, isStaple = true)
        val coriander = Ingredient(3, "Coriander", null, null, null, null, null, isStaple = false)
        val recipe = RecipeLines(
            servings = 4,
            lines = listOf(
                IngredientLine(800.0, "g", Dimension.WEIGHT, 800.0, "chicken fillet", null) to chicken,
                IngredientLine(400.0, "g", Dimension.WEIGHT, 400.0, "chicken fillet", null) to chicken,
                IngredientLine(null, null, null, null, "olive oil", null) to oil,
                IngredientLine(2.0, "tbsp", Dimension.VOLUME, 30.0, "olive oil", null) to oil,
                IngredientLine(1.0, "bunch", Dimension.COUNT, null, "coriander", null) to coriander,
                IngredientLine(1.0, "g", Dimension.WEIGHT, 1.0, "mystery", null) to null,
            ),
        )
        // Two lines of chicken add up. Oil "to taste" has no weight and adds nothing; 30 ml of oil
        // without a density weighs as water. A bunch without a weight per piece can't be weighed, so
        // the meal takes no coriander; a line without an ingredient is left out.
        assertEquals(mapOf(1L to 600.0, 2L to 15.0), gramsUsed(recipe, portions = 2.0))
    }

    /** The test account's id, for what the API doesn't show. */
    private fun accountId() = UserRepository(db).resolve(Identity("sub-t", "t", "T")).id

    /** Grams left of the stock of [ingredient] for meals of [weekStart], all entries; null when there is none or an amount is unknown. */
    private fun left(ingredient: Ingredient, weekStart: String): Double? {
        val grams = db.pantryQueries.selectStock(accountId(), weekStart, listOf(ingredient.id)).executeAsList().map { it.grams }
        return grams.takeIf { it.isNotEmpty() && null !in it }?.sumOf { it!! }
    }

    /** Puts [portions] of the recipe titled [title] in a slot of the week starting [weekStart], as the planner would. */
    private suspend fun HttpClient.plan(weekStart: String, day: Int, slot: MealSlot, title: String, portions: Double, leftover: Boolean = false): PlannedMeal {
        val recipe = get("/api/recipes").body<List<dev.tobins.mealplanner.api.RecipeSummary>>().single { it.title == title }
        db.planQueries.upsertMeal(accountId(), weekStart, day.toLong(), slot.name, recipe.id, title, portions, 500.0, 30.0, if (leftover) 1 else 0, 0, MealStatus.PLANNED.name)
        return PlannedMeal(day, slot, recipe.id, title, portions, 500.0, 30.0, leftover)
    }

    private suspend fun HttpClient.mark(weekStart: String, meal: PlannedMeal, status: MealStatus) {
        val response = put("/api/plans/$weekStart/meals/${meal.day}/${meal.slot}/status") { setBody(status) }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun aLineWithoutAWeightTakesNothingFromTheStock() = testApplication {
        val http = start().ready()
        // A stock-up of a litre of olive oil, then two dinners that use it: one "to taste" and by the
        // splash, which can't be weighed, and one by the tablespoon.
        http.tick(http.list().stockUp.single { it.name == "Olive oil" }.key)
        val oil = http.ingredient("Olive oil")
        val bought = assertNotNull(left(oil, week))
        write("oil-to-taste", "---\ntitle: Oil to taste\n---\n```ingredients\nservings: 1\n200 | g | lettuce\n | | olive oil\n1 | splash | olive oil\n```")
        write("oil-by-the-spoon", "---\ntitle: Oil by the spoon\n---\n```ingredients\nservings: 1\n200 | g | lettuce\n2 | tbsp | olive oil\n```")
        http.post("/api/sync/cooking")
        http.addWholeBank()

        http.mark(week, http.plan(week, 0, MealSlot.DINNER, "Oil to taste", 1.0), MealStatus.EATEN)
        assertEquals(bought, left(oil, week), "nothing taken: not the whole litre, as before")
        http.mark(week, http.plan(week, 1, MealSlot.DINNER, "Oil by the spoon", 1.0), MealStatus.EATEN)
        assertEquals(bought - 2 * 14.7868 * (oil.gramsPerMl ?: 1.0), assertNotNull(left(oil, week)), 1e-6)
    }

    @Test
    fun aLeftoverLunchTakesItsShareOnceWhicheverIsEatenAndUnEatenFirst() = testApplication {
        val http = start().ready()
        // Bought ahead: three batches of chicken for next week (1.2 kg for four servings each). Next
        // week has a chicken dinner for two with its leftovers for two the next day: 600 g each.
        http.tick(http.list().buyAhead.single().key)
        val chicken = http.ingredient("Chicken fillet")
        val dinner = http.plan(nextWeek, 0, MealSlot.DINNER, "Chicken 0", 2.0)
        val lunch = http.plan(nextWeek, 1, MealSlot.LUNCH, "Chicken 0", 2.0, leftover = true)
        fun left() = left(chicken, nextWeek)
        assertEquals(3600.0, left())

        // The dinner skipped, the leftovers were cooked for lunch: the lunch takes its own share.
        http.mark(nextWeek, dinner, MealStatus.SKIPPED)
        http.mark(nextWeek, lunch, MealStatus.EATEN)
        assertEquals(3000.0, left())
        http.mark(nextWeek, lunch, MealStatus.PLANNED)
        assertEquals(3600.0, left(), "un-eaten, it gives that back")

        // The dinner eaten: cooked with its leftovers, it takes both shares, once.
        http.mark(nextWeek, dinner, MealStatus.EATEN)
        http.mark(nextWeek, dinner, MealStatus.EATEN)
        assertEquals(2400.0, left())
        http.mark(nextWeek, lunch, MealStatus.EATEN)
        assertEquals(2400.0, left(), "the leftovers were cooked with the dinner")

        // Un-eaten by mistake while the lunch stays eaten: only the dinner's share comes back, and
        // eaten again it takes only that.
        http.mark(nextWeek, dinner, MealStatus.PLANNED)
        assertEquals(3000.0, left())
        http.mark(nextWeek, dinner, MealStatus.EATEN)
        assertEquals(2400.0, left())

        // The lunch un-eaten while the dinner stays eaten: the leftovers were cooked all the same.
        http.mark(nextWeek, lunch, MealStatus.SKIPPED)
        assertEquals(2400.0, left())
        http.mark(nextWeek, dinner, MealStatus.SKIPPED)
        assertEquals(3600.0, left(), "nothing eaten, nothing taken")
    }

    @Test
    fun unEatingAMealPutsBackWhatItUsedUp() = testApplication {
        val http = start().ready()
        http.tick(http.list().buyAhead.single().key)
        val chicken = http.ingredient("Chicken fillet")
        // A feast of twelve servings uses all 3.6 kg of chicken.
        val feast = http.plan(nextWeek, 2, MealSlot.DINNER, "Chicken 1", 12.0)
        http.mark(nextWeek, feast, MealStatus.EATEN)
        assertFalse("Chicken fillet" in http.onHand())
        http.mark(nextWeek, feast, MealStatus.PLANNED)
        assertEquals(3600.0, left(chicken, nextWeek))
        assertTrue("Chicken fillet" in http.onHand())
        assertTrue(scope().stock.onHandFor(LocalDate.parse(week)).isEmpty(), "still for next week only")
    }

    @Test
    fun whatTheOwnerSaysOfTheStockOutlastsUnEating() = testApplication {
        val http = start().ready()
        val key = http.list().buyAhead.single().key
        http.tick(key)
        val dinner = http.plan(nextWeek, 0, MealSlot.DINNER, "Chicken 0", 2.0)

        // Un-ticked after a meal was eaten from it: it wasn't bought, so un-eating brings nothing back.
        http.mark(nextWeek, dinner, MealStatus.EATEN)
        assertTrue("Chicken fillet" in http.onHand())
        http.tick(key, checked = false)
        http.mark(nextWeek, dinner, MealStatus.PLANNED)
        assertFalse("Chicken fillet" in http.onHand())

        // Taken off in the pantry after a meal was eaten from it: the owner says it is gone. (A kilo
        // of rice stocked up this week; the dinner takes 100 g.)
        http.tick(http.list().stockUp.single { it.name == "White rice" }.key)
        val rice = http.ingredient("White rice")
        val thisWeeks = http.plan(week, 3, MealSlot.DINNER, "Chicken 2", 2.0)
        http.mark(week, thisWeeks, MealStatus.EATEN)
        assertEquals(900.0, assertNotNull(left(rice, week)), 1e-9)
        http.delete("/api/pantry/on-hand/${rice.id}")
        http.mark(week, thisWeeks, MealStatus.PLANNED)
        assertFalse("White rice" in http.onHand())
    }

    private suspend fun HttpClient.pantryGrams() = get("/api/pantry").body<Pantry>().onHandGrams

    private suspend fun HttpClient.tickOn(weekStart: String, key: String, checked: Boolean = true, grams: Double? = null) =
        put("/api/plans/$weekStart/shopping") { setBody(ShoppingCheck(key, checked, grams)) }

    @Test
    fun thePantrySaysTheEarliestWeekAnIngredientsStockCountsFrom() = testApplication {
        val http = start().ready()
        val chicken = http.ingredient("Chicken fillet")
        val lettuce = http.ingredient("Lettuce")
        suspend fun pantry() = http.get("/api/pantry").body<Pantry>()

        // One entry: bought ahead on this week's list, for next week.
        http.tick(http.list().buyAhead.single().key)
        assertEquals(mapOf(chicken.id to nextWeek), pantry().onHandFrom)
        assertEquals(mapOf(chicken.id to listOf(nextWeek)), pantry().onHandWeeks)

        // Two entries in neighbouring weeks (stocked up this week as well): one week per ingredient, as apps
        // read it, the earliest, from which meals eat it; the grams are both added up.
        http.tick("stock:i${chicken.id}", grams = 300.0)
        assertEquals(mapOf(chicken.id to week), pantry().onHandFrom)
        assertEquals(mapOf(chicken.id to 3900.0), pantry().onHandGrams)
        assertEquals(mapOf(chicken.id to listOf(week, nextWeek)), pantry().onHandWeeks, "the pantry says both weeks, earliest first")
        http.tick("stock:i${chicken.id}", checked = false)
        assertEquals(mapOf(chicken.id to nextWeek), pantry().onHandFrom, "that entry gone, the other's week")
        assertEquals(mapOf(chicken.id to listOf(nextWeek)), pantry().onHandWeeks)

        // On hand by hand only: it counts at once, and has no week.
        http.put("/api/pantry/on-hand/${lettuce.id}")
        assertTrue("Lettuce" in http.onHand())
        assertEquals(setOf(chicken.id), pantry().onHandFrom.keys)
        assertEquals(setOf(chicken.id), pantry().onHandWeeks.keys)
    }

    @Test
    fun aStockAMealUsedUpComesBackToItsOwnEntryWhichAnotherWeeksUnTickLeaves() = testApplication {
        val http = start().ready()
        val rice = http.ingredient("White rice")
        // A kilo of rice stocked up this week, all of it eaten by a feast of twenty servings this week.
        http.tick(http.list().stockUp.single { it.name == "White rice" }.key)
        val feast = http.plan(week, 2, MealSlot.DINNER, "Chicken 1", 20.0)
        http.mark(week, feast, MealStatus.EATEN)
        assertNull(left(rice, nextWeek), "used up")

        // Stocked up again on next week's list, then the feast is un-eaten: its kilo comes back, from this week.
        http.tickOn(nextWeek, "stock:i${rice.id}", grams = 800.0)
        val bought = assertNotNull(left(rice, nextWeek))
        http.mark(week, feast, MealStatus.PLANNED)
        assertEquals(1000.0, left(rice, week))
        assertEquals(bought + 1000.0, left(rice, nextWeek))

        // Un-ticking next week's takes back what that tick put there, and no more (issue 107). Before, the kilo
        // given back had joined its entry, which then counted from this week, so the un-tick found nothing.
        http.tickOn(nextWeek, "stock:i${rice.id}", checked = false)
        assertEquals(1000.0, left(rice, nextWeek))
        assertEquals(1000.0, http.pantryGrams()[rice.id])
    }

    @Test
    fun aMealEatsTheOldestEntryFirstAndUnEatenGivesEachItsOwnShareBack() = testApplication {
        val http = start().ready()
        val chicken = http.ingredient("Chicken fillet")
        // 3.6 kg bought ahead on this week's list, then 500 g stocked up on next week's: both for next week's meals.
        val ahead = http.list().buyAhead.single().key
        http.tick(ahead)
        http.tickOn(nextWeek, "stock:i${chicken.id}", grams = 500.0)
        fun entries() = db.pantryQueries.selectStock(accountId(), nextWeek, listOf(chicken.id)).executeAsList().map { it.source to it.grams }
        assertEquals(listOf("BUY_AHEAD" to 3600.0, "STOCK_UP" to 500.0), entries())

        // A feast of thirteen servings takes 3.9 kg: all of the older entry, and 300 g of the other.
        val feast = http.plan(nextWeek, 2, MealSlot.DINNER, "Chicken 1", 13.0)
        http.mark(nextWeek, feast, MealStatus.EATEN)
        assertEquals(listOf("STOCK_UP" to 200.0), entries())
        http.mark(nextWeek, feast, MealStatus.PLANNED)
        assertEquals(listOf("BUY_AHEAD" to 3600.0, "STOCK_UP" to 500.0), entries(), "each entry gets its own share back")

        // Eaten again, then the buy-ahead tick taken back: what the feast took of that entry was not bought, so
        // un-eating gives back only what it took of the other.
        http.mark(nextWeek, feast, MealStatus.EATEN)
        http.tick(ahead, checked = false)
        http.mark(nextWeek, feast, MealStatus.PLANNED)
        assertEquals(listOf("STOCK_UP" to 500.0), entries())
    }

    @Test
    fun anItemUsedUpOnNextWeeksListIsForNextWeeksMeals() = testApplication {
        val http = start().ready()
        val beef = http.ingredient("Beef")
        // A beef dinner next week, its row ticked on next week's list (600 g), then the dinner removed.
        http.plan(nextWeek, 0, MealSlot.DINNER, "Beef 0", 2.0)
        http.tickOn(nextWeek, "i${beef.id}")
        http.delete("/api/plans/$nextWeek/meals/0/dinner")
        assertEquals(listOf("i${beef.id}"), http.list(nextWeek).noLongerNeeded.map { it.key })
        http.post("/api/plans/$nextWeek/shopping/use-up") { setBody(ShoppingRowKey("i${beef.id}")) }

        // Bought on next week's list, it counts from next week, as what is bought ahead does: this week's list
        // and meals (the fillers use beef) don't count on it. The pantry shows it, as it is at home.
        assertEquals(600.0, left(beef, nextWeek))
        assertNull(left(beef, week))
        assertFalse(http.list().items.single { it.name == "Beef" }.onHand)
        val filler = http.get("/api/plans/$week").body<WeekPlan>().days.flatMap { it.meals }.first { it.title.startsWith("Filler") }
        http.eat(week, filler)
        assertEquals(600.0, left(beef, nextWeek))
        assertEquals(600.0, http.pantryGrams()[beef.id])
    }

    @Test
    fun theListSaysHowMuchIsOnHandWhereThatIsKnown() = testApplication {
        val http = start().ready()
        val rice = http.ingredient("White rice")
        val lettuce = http.ingredient("Lettuce")
        val chicken = http.ingredient("Chicken fillet")
        // Ten servings of a dinner of 200 g of rice for four: 500 g needed this week, 3 kg of chicken.
        http.plan(week, 0, MealSlot.DINNER, "Chicken 0", 10.0)
        fun ShoppingList.row(name: String) = items.single { it.name == name }

        // 300 g left of rice stocked up this week; lettuce on hand by hand; chicken bought ahead for next week.
        scope().stock.putOnHandFrom(rice.id, LocalDate.parse(week), grams = 300.0, source = StockSource.STOCK_UP)
        http.put("/api/pantry/on-hand/${lettuce.id}")
        scope().stock.putOnHandFrom(chicken.id, LocalDate.parse(nextWeek), grams = 2000.0, source = StockSource.BUY_AHEAD)
        val list = http.list()
        assertEquals(500.0, assertNotNull(list.row("White rice").grams), 1e-9)
        assertTrue(list.row("White rice").onHand)
        assertEquals(300.0, list.row("White rice").onHandGrams)
        assertTrue(list.row("Lettuce").onHand)
        assertNull(list.row("Lettuce").onHandGrams, "set by hand: on hand, how much is not known")
        assertFalse(list.row("Chicken fillet").onHand)
        assertNull(list.row("Chicken fillet").onHandGrams, "it counts from next week")

        // The pantry: every amount that is known, next week's stock too, as it is at home.
        assertEquals(mapOf(rice.id to 300.0, chicken.id to 2000.0), http.pantryGrams())

        // Marked on hand by hand, the amount is no longer known.
        http.put("/api/pantry/on-hand/${rice.id}")
        assertTrue(http.list().row("White rice").onHand)
        assertNull(http.list().row("White rice").onHandGrams)
        assertEquals(mapOf(chicken.id to 2000.0), http.pantryGrams())
    }

    @Test
    fun theAmountOnHandFollowsAMealEatenAndUnEaten() = testApplication {
        val http = start().ready()
        // A kilo of rice stocked up this week, and a dinner of this week that takes 100 g of it.
        http.tick(http.list().stockUp.single { it.name == "White rice" }.key)
        val rice = http.ingredient("White rice")
        val dinner = http.plan(week, 3, MealSlot.DINNER, "Chicken 2", 2.0)
        fun ShoppingList.rice() = items.single { it.name == "White rice" }
        assertEquals(1000.0, http.list().rice().onHandGrams)

        http.mark(week, dinner, MealStatus.EATEN)
        assertEquals(900.0, assertNotNull(http.list().rice().onHandGrams), 1e-9)
        assertEquals(900.0, http.pantryGrams().getValue(rice.id), 1e-9)
        http.mark(week, dinner, MealStatus.PLANNED)
        assertEquals(1000.0, http.list().rice().onHandGrams)
        assertEquals(1000.0, http.pantryGrams()[rice.id])
    }

    @Test
    fun whatTheMealsToComeNeedLeavesOutTheMealsEatenFromTheStock() = testApplication {
        val http = start().ready()
        val chicken = http.ingredient("Chicken fillet")
        // Three meals of 600 g of chicken this week: a dinner with its leftover lunch the next day, and
        // another dinner. 1.5 kg of it bought ahead last week.
        val dinner = http.plan(week, 0, MealSlot.DINNER, "Chicken 0", 2.0)
        val lunch = http.plan(week, 1, MealSlot.LUNCH, "Chicken 0", 2.0, leftover = true)
        val other = http.plan(week, 2, MealSlot.DINNER, "Chicken 1", 2.0)
        scope().stock.putOnHandFrom(chicken.id, LocalDate.parse(week), grams = 1500.0, source = StockSource.STOCK_UP)
        suspend fun row() = http.list().items.single { it.name == "Chicken fillet" }
        // What is still to buy, as the app works it out: what the meals to come need, less the stock.
        suspend fun toBuy() = row().let { assertNotNull(it.toComeGrams) - (it.onHandGrams ?: 0.0) }
        assertEquals(1800.0, assertNotNull(row().grams), 1e-9)
        assertEquals(1800.0, assertNotNull(row().toComeGrams), 1e-9)
        assertEquals(300.0, toBuy(), 1e-9)

        // The dinner eaten, cooked with its leftovers: 1.2 kg less in the freezer, and those two meals
        // need nothing more. Still 300 g to buy, not the 1.5 kg the whole week's need less the stock says.
        http.mark(week, dinner, MealStatus.EATEN)
        assertEquals(300.0, assertNotNull(row().onHandGrams), 1e-9)
        assertEquals(1800.0, assertNotNull(row().grams), 1e-9, "the week's need, as installed apps read it")
        assertEquals(600.0, assertNotNull(row().toComeGrams), 1e-9)
        assertEquals(300.0, toBuy(), 1e-9)
        assertEquals(listOf("Chicken 0", "Chicken 1"), row().recipes, "the week's meals, as installed apps read them")
        assertEquals(listOf("Chicken 1"), row().toComeRecipes, "only the meal still to cook is its source")
        http.mark(week, lunch, MealStatus.EATEN)
        assertEquals(600.0, assertNotNull(row().toComeGrams), 1e-9, "the leftovers were cooked with the dinner")

        // Un-eaten, exactly as before; a skipped meal needs nothing.
        http.mark(week, lunch, MealStatus.PLANNED)
        http.mark(week, dinner, MealStatus.PLANNED)
        assertEquals(1500.0, assertNotNull(row().onHandGrams), 1e-9)
        assertEquals(1800.0, assertNotNull(row().toComeGrams), 1e-9)
        http.mark(week, other, MealStatus.SKIPPED)
        assertEquals(1200.0, assertNotNull(row().toComeGrams), 1e-9, "the two meals still to cook; 1.5 kg at home covers them")

        // A leftover lunch eaten before its dinner was cooked for itself, from the stock: the dinner is
        // still to cook, for its own share only, and what is to buy stays as it was.
        http.mark(week, other, MealStatus.PLANNED)
        http.mark(week, lunch, MealStatus.EATEN)
        assertEquals(900.0, assertNotNull(row().onHandGrams), 1e-9)
        assertEquals(1200.0, assertNotNull(row().toComeGrams), 1e-9)
        assertEquals(300.0, toBuy(), 1e-9)

        // A leftover lunch skipped while its dinner is still to cook is cooked with that dinner: it counts.
        http.mark(week, lunch, MealStatus.SKIPPED)
        assertEquals(1800.0, assertNotNull(row().toComeGrams), 1e-9)

        // Every meal eaten: nothing needed, and the row stays on the list rather than becoming "no longer needed".
        http.mark(week, dinner, MealStatus.EATEN)
        http.mark(week, other, MealStatus.EATEN)
        val list = http.list()
        assertEquals(0.0, list.items.single { it.name == "Chicken fillet" }.toComeGrams)
        assertEquals(emptyList(), list.items.single { it.name == "Chicken fillet" }.toComeRecipes, "no meal left to name")
        assertTrue(list.noLongerNeeded.isEmpty())
    }

    @Test
    fun aMealEatenWithoutAStockOrATickNeedsNothingMoreAndATickStaysAsItWas() = testApplication {
        val http = start().ready()
        // Lettuce is never on hand: 100 g for each of the two meals below, and the fillers' own.
        val dinner = http.plan(week, 0, MealSlot.DINNER, "Chicken 0", 2.0)
        http.plan(week, 1, MealSlot.LUNCH, "Chicken 0", 2.0, leftover = true)
        suspend fun row(name: String) = http.list().items.single { it.name == name }
        val lettuce = row("Lettuce")
        assertEquals(assertNotNull(lettuce.grams), assertNotNull(lettuce.toComeGrams), 1e-9, "nothing eaten")

        // Eaten without the row ticked: whatever it was made with, those meals need no more.
        http.mark(week, dinner, MealStatus.EATEN)
        assertEquals(lettuce.grams, row("Lettuce").grams)
        assertEquals(lettuce.toComeGrams!! - 200.0, assertNotNull(row("Lettuce").toComeGrams), 1e-9)

        // Ticked, then its only meals eaten: the tick and what it remembers stay as they were, so the
        // row needs no "more" and doesn't leave the list; the meals to come need none of it.
        val rice = row("White rice")
        http.tick(rice.key)
        http.mark(week, dinner, MealStatus.PLANNED)
        val ticked = row("White rice")
        assertEquals(200.0, assertNotNull(ticked.toComeGrams), 1e-9)
        http.mark(week, dinner, MealStatus.EATEN)
        val after = row("White rice")
        assertTrue(after.checked)
        assertEquals(ticked.grams, after.grams)
        assertEquals(ticked.grams, after.boughtGrams)
        assertEquals(0.0, after.toComeGrams)
        assertTrue(http.list().noLongerNeeded.isEmpty())
    }

    @Test
    fun aRowWithoutAWeightIsDoneWithOnceNoMealToComeUsesIt() = testApplication {
        val http = start().ready()
        // A text row without an amount and one counted in jars, which can't be weighed; nothing else uses them.
        write("pesto-toast", "---\ntitle: Pesto toast\n---\n```ingredients\nservings: 1\n | | a bit of pesto\n2 | jar | tapenade\n```")
        http.post("/api/sync/cooking")
        http.addWholeBank()
        val toast = http.plan(week, 4, MealSlot.LUNCH, "Pesto toast", 1.0)
        suspend fun rows() = http.list().items.filter { it.recipes == listOf("Pesto toast") }
        assertEquals(2, rows().size)
        assertTrue(rows().all { it.grams == null && it.toComeGrams == null }, "still to cook, and no weight to say")

        // Eaten or skipped, no meal to come needs them: 0, whatever their unit.
        for (status in listOf(MealStatus.EATEN, MealStatus.SKIPPED)) {
            http.mark(week, toast, status)
            assertEquals(listOf(0.0, 0.0), rows().map { it.toComeGrams }, "$status")
        }
        http.mark(week, toast, MealStatus.PLANNED)
        assertTrue(rows().all { it.toComeGrams == null })
        // An own item is for no meal: never anything to say.
        http.post("/api/plans/$week/shopping/extras") { setBody(ShoppingExtraInput("Coffee")) }
        assertNull(http.list().items.single { it.name == "Coffee" }.toComeGrams)
    }

    @Test
    fun aLateAdviceTickCarriesTheGramsTheAppWasAdvised() = testApplication {
        val http = start().ready()
        val beef = http.ingredient("Beef")
        val lettuce = http.ingredient("Lettuce")

        // The list advises no beef (any more): the grams the app sends are what was bought.
        http.tick("ahead:i${beef.id}", grams = 800.0)
        assertEquals(800.0, left(beef, nextWeek))
        assertEquals(800.0, http.pantryGrams()[beef.id])

        // While the list still advises the item, its own amount counts.
        val chicken = http.list().buyAhead.single()
        http.tick(chicken.key, grams = 5.0)
        assertEquals(chicken.grams, left(http.ingredient("Chicken fillet"), nextWeek))

        // Nonsense is refused, and nothing is stored.
        for (grams in listOf(0.0, -200.0, ShoppingCheck.MAX_GRAMS + 1)) {
            assertEquals(HttpStatusCode.BadRequest, http.tick("stock:i${lettuce.id}", grams = grams).status, "$grams g")
        }
        assertFalse("Lettuce" in http.onHand())
        assertTrue(db.shoppingQueries.selectChecks(accountId(), week).executeAsList().none { it.itemKey == "stock:i${lettuce.id}" })

        // Without grams it is on hand in an amount nobody knows, as before.
        assertEquals(HttpStatusCode.OK, http.tick("stock:i${lettuce.id}").status)
        assertTrue("Lettuce" in http.onHand())
        assertNull(http.pantryGrams()[lettuce.id])
    }

    @Test
    fun theReleasedAppReadsOnlyThisWeeksNeedsAsItsList() = testApplication {
        val http = start().ready()
        http.post("/api/plans/$week/shopping/extras") { setBody(ShoppingExtraInput("Coffee")) }
        val list = http.list()
        assertTrue(list.buyAhead.isNotEmpty() && list.stockUp.isNotEmpty())

        // The released app's types lack the advice. What it shows is exactly this week's needs, ticks and all.
        val old = releasedJson.decodeFromString<ReleasedShoppingList>(http.get("/api/plans/$week/shopping").bodyAsText())
        assertEquals(list.items.map { it.key to it.name }, old.items.map { it.key to it.name })
        assertTrue(old.items.none { it.name == "Chicken fillet" || it.name == "Olive oil" })
        // Ticking with its keys works as before, and the advice stays out of its list.
        val ticked = releasedJson.decodeFromString<ReleasedShoppingList>(http.tick(old.items.first().key).bodyAsText())
        assertTrue(ticked.items.first().checked)
        assertEquals(old.items.map { it.key }, ticked.items.map { it.key })
        // An own item is a row of its list like any other: it shows it and ticks it off with its key.
        val coffee = old.items.single { it.name == "Coffee" }
        assertTrue(releasedJson.decodeFromString<ReleasedShoppingList>(http.tick(coffee.key).bodyAsText()).items.single { it.key == coffee.key }.checked)
    }
}
