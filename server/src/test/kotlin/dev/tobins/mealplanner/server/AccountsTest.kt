package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.ManyAddedInput
import dev.tobins.mealplanner.api.ManyRotationInput
import dev.tobins.mealplanner.api.RotationInput
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.UnmappedText
import java.io.File
import dev.tobins.mealplanner.api.AuthConfig
import dev.tobins.mealplanner.api.CookingRepoSetting
import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.IngredientSection
import dev.tobins.mealplanner.api.KeepsChoice
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.Me
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.PlanPriority
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.RatingInput
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingExtraInput
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShoppingRowKey
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeightLog
import dev.tobins.mealplanner.server.history.Verdict
import dev.tobins.mealplanner.server.offers.OfferSource
import dev.tobins.mealplanner.shopping.ShopSection
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
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every account sees only its own data; offers and prices are the only shared things. */
class AccountsTest {
    private fun ApplicationTestBuilder.clientFor(token: String): HttpClient = createClient {
        install(ContentNegotiation) { json() }
        defaultRequest {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
        }
    }

    private fun ApplicationTestBuilder.start() {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
    }

    private val recipe = RecipeInput(title = "Alice's stew", servings = 2, ingredients = "400 | g | beef\n1 | onion | large", instructions = "Stew.")

    @Test
    fun accountsCannotSeeOrChangeEachOthersData() = testApplication {
        start()
        val alice = clientFor("alice")
        val bob = clientFor("bob")

        // Alice fills her account.
        val stew = alice.post("/api/recipes") { setBody(recipe) }.body<RecipeDetail>().summary
        val onion = alice.post("/api/ingredients") { setBody(IngredientInput("Shallot")) }.body<Ingredient>()
        alice.put("/api/pantry/on-hand/${onion.id}")
        alice.put("/api/profile") { setBody(Profile(weightKg = 70.0, heightCm = 170.0, ageYears = 30, sex = Sex.FEMALE, activity = ActivityLevel.LIGHT)) }
        alice.put("/api/weights/2026-11-10") { setBody(70.0) }
        val day = alice.post("/api/log") { setBody(LogEntry(0, "2026-11-10", "Apple", 80.0, 0.5)) }.body<DayView>()
        val logId = day.extras.single().id

        // Bob sees none of it.
        assertEquals(emptyList(), bob.get("/api/recipes").body<List<RecipeSummary>>())
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/recipes/${stew.id}").status)
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/recipes/${stew.id}/rating") { setBody(RatingInput(1)) }.status)
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/recipes/${stew.id}") { setBody(recipe.copy(title = "Bob's now")) }.status)
        assertEquals(HttpStatusCode.NotFound, bob.delete("/api/recipes/${stew.id}").status)
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/ingredients/${onion.id}") { setBody(IngredientInput("Mine")) }.status)
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/pantry/staples/${onion.id}").status)
        assertTrue(bob.get("/api/ingredients").body<List<Ingredient>>().none { it.name == "Shallot" })
        assertEquals(emptyList(), bob.get("/api/pantry").body<Pantry>().onHand)
        assertNull(bob.get("/api/profile").body<ProfileState>().profile)
        assertEquals(emptyList(), bob.get("/api/weights").body<WeightLog>().entries)
        assertEquals(emptyList(), bob.get("/api/days/2026-11-10").body<DayView>().extras)
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/plans/2026-11-09/meals/4/dinner") { setBody(MealChoice(stew.id)) }.status, "a recipe of another account")
        bob.delete("/api/log/$logId")
        assertEquals(HttpStatusCode.Conflict, bob.post("/api/plans/2026-11-09/generate").status, "no profile of his own yet")

        // ...and Alice's data is untouched.
        assertEquals(listOf("Alice's stew"), alice.get("/api/recipes").body<List<RecipeSummary>>().map { it.title })
        assertNull(alice.get("/api/recipes/${stew.id}").body<RecipeDetail>().summary.rating)
        assertEquals(1, alice.get("/api/days/2026-11-10").body<DayView>().extras.size)
        assertEquals(listOf("Shallot"), alice.get("/api/pantry").body<Pantry>().onHand.map { it.name })

        // Shop sections set by hand are per account: Bob can neither see nor change Alice's, and
        // his ingredient of the same name keeps its automatic section.
        val placed = alice.put("/api/ingredients/${onion.id}/section") { setBody(SectionChoice("FRUIT_VEG")) }.body<IngredientSection>()
        assertTrue(placed.byHand)
        assertTrue(bob.get("/api/ingredients/sections").body<List<IngredientSection>>().none { it.ingredientId == onion.id })
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/ingredients/${onion.id}/section") { setBody(SectionChoice("FROZEN")) }.status)
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/ingredients/${onion.id}/section") { setBody(SectionChoice(null)) }.status)
        val bobsShallot = bob.post("/api/ingredients") { setBody(IngredientInput("Shallot")) }.body<Ingredient>()
        val bobsSection = bob.get("/api/ingredients/sections").body<List<IngredientSection>>().single { it.ingredientId == bobsShallot.id }
        assertEquals("OTHER", bobsSection.section)
        assertFalse(bobsSection.byHand)
        val alicesSection = alice.get("/api/ingredients/sections").body<List<IngredientSection>>().single { it.ingredientId == onion.id }
        assertEquals("FRUIT_VEG", alicesSection.section)
        assertEquals(HttpStatusCode.BadRequest, alice.put("/api/ingredients/${onion.id}/section") { setBody(SectionChoice("AISLE_42")) }.status)

        // Plans are per account too (without NEVO data here, Alice's plan stays empty, but it is hers).
        assertEquals(HttpStatusCode.OK, alice.post("/api/plans/2026-11-09/generate").status)
        bob.put("/api/profile") { setBody(Profile(weightKg = 80.0, heightCm = 180.0, ageYears = 30, sex = Sex.MALE, activity = ActivityLevel.LIGHT)) }
        assertTrue(bob.get("/api/plans/2026-11-09").body<WeekPlan>().days.all { it.meals.isEmpty() })
    }

    @Test
    fun eachAccountKeepsItsOwnPortionRange() = testApplication {
        start()
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        val body = Profile(weightKg = 70.0, heightCm = 170.0, ageYears = 30, sex = Sex.FEMALE, activity = ActivityLevel.LIGHT)
        suspend fun HttpClient.range() = get("/api/profile").body<ProfileState>().profile?.let { it.minPortions to it.maxPortions }

        alice.put("/api/profile") { setBody(body.copy(minPortions = 1.0, maxPortions = 2.0)) }
        assertNull(bob.range(), "Bob sees no profile, so no range of Alice's")
        // Bob's own range, and a save without one as an older app sends it, leave Alice's as it is.
        bob.put("/api/profile") { setBody(body.copy(minPortions = 0.5, maxPortions = 1.0)) }
        bob.put("/api/profile") { setBody("""{"weightKg":80.0,"heightCm":180.0,"ageYears":30,"sex":"MALE","activity":"LIGHT"}""") }
        assertEquals(0.5 to 1.0, bob.range())
        assertEquals(1.0 to 2.0, alice.range())
    }

    @Test
    fun eachAccountKeepsItsOwnPriorityAndFibreTarget() = testApplication {
        start()
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        val body = Profile(weightKg = 70.0, heightCm = 170.0, ageYears = 30, sex = Sex.FEMALE, activity = ActivityLevel.LIGHT)
        suspend fun HttpClient.choice() = get("/api/profile").body<ProfileState>().let { it.profile?.priority to it.targets?.fibreG }

        alice.put("/api/profile") { setBody(body.copy(priority = PlanPriority.FIBRE, fibreOverrideG = 45)) }
        assertEquals(null to null, bob.choice(), "Bob sees no profile, so neither Alice's priority nor her fibre target")
        // Bob's own choice, and a save without one as an older app sends it, leave Alice's as it is.
        bob.put("/api/profile") { setBody(body.copy(priority = PlanPriority.PRICE, fibreOverrideG = 20)) }
        bob.put("/api/profile") { setBody("""{"weightKg":80.0,"heightCm":180.0,"ageYears":30,"sex":"MALE","activity":"LIGHT"}""") }
        assertEquals(PlanPriority.PRICE to 20, bob.choice())
        assertEquals(PlanPriority.FIBRE to 45, alice.choice())
    }

    @Test
    fun eachAccountChoosesItsOwnStores() = testApplication {
        start()
        val alice = clientFor("alice")
        val bob = clientFor("bob")

        assertEquals(ShopsSetting(listOf(Store.AH)), alice.put("/api/shops") { setBody(ShopsSetting(listOf(Store.AH))) }.body<ShopsSetting>())
        // Bob neither sees Alice's choice nor changes it with his own.
        assertNull(bob.get("/api/shops").body<ShopsSetting>().stores, "never chosen: every store")
        bob.put("/api/shops") { setBody(ShopsSetting(listOf(Store.LIDL, Store.JUMBO))) }
        assertEquals(listOf(Store.AH), alice.get("/api/shops").body<ShopsSetting>().stores)
        bob.put("/api/shops") { setBody(ShopsSetting(null)) }
        assertEquals(listOf(Store.AH), alice.get("/api/shops").body<ShopsSetting>().stores)
        assertNull(bob.get("/api/shops").body<ShopsSetting>().stores)
    }

    @Test
    fun theVerdictsOnOffersGoByTheAccountsOwnIngredientsAndStores() = testApplication {
        val today = serverToday()
        val samphire = Offer(Store.AH, "AH Zeekraal 100 gram", "1+1 gratis", category = "Groente, aardappelen", validFrom = today.toString(), validUntil = today.plusDays(6).toString())
        val ah = object : OfferSource {
            override val store = Store.AH
            override suspend fun fetch() = listOf(samphire)
        }
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        val db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = listOf(ah), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        suspend fun HttpClient.verdicts() = get("/api/offers/verdicts").body<DealVerdicts>().verdicts.map { it.ingredient to it.verdict }

        alice.post("/api/ingredients") { setBody(IngredientInput("Zeekraal")) }
        alice.post("/api/offers/refresh")
        assertEquals(listOf("Zeekraal"), db.priceHistoryQueries.selectOfferHistory().executeAsList().map { it.ingredient }, "the shared history names her ingredient")

        assertEquals(listOf("Zeekraal" to Verdict.NOT_ENOUGH_HISTORY), alice.verdicts())
        // Bob has nothing like it in his catalogue: nothing of hers reaches him through the history.
        assertEquals(emptyList(), bob.verdicts())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/offers/verdicts").status)
        // The route only reads; what Bob chooses or calls changes nothing of Alice's.
        assertFalse(bob.post("/api/offers/verdicts").status.isSuccess())
        bob.put("/api/shops") { setBody(ShopsSetting(listOf(Store.JUMBO))) }
        assertEquals(listOf("Zeekraal" to Verdict.NOT_ENOUGH_HISTORY), alice.verdicts())
        // Her own choice of stores is what limits hers.
        alice.put("/api/shops") { setBody(ShopsSetting(listOf(Store.LIDL))) }
        assertEquals(emptyList(), alice.verdicts())
    }

    @Test
    fun buyingAheadAndStockingUpStayWithTheirAccount() = testApplication {
        start()
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        val week = "2026-11-16"
        val chicken = alice.post("/api/ingredients") { setBody(IngredientInput("Chicken thigh")) }.body<Ingredient>()

        // Bob can't tick Alice's ingredient onto anyone's pantry, nor say whether it keeps.
        for (key in listOf("ahead:i${chicken.id}", "stock:i${chicken.id}")) {
            assertEquals(HttpStatusCode.NotFound, bob.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, true)) }.status)
            assertEquals(HttpStatusCode.NotFound, bob.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, false)) }.status)
        }
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/ingredients/${chicken.id}/keeps") { setBody(KeepsChoice(false)) }.status)
        assertEquals(emptyList(), alice.get("/api/pantry").body<Pantry>().onHand)
        assertFalse(alice.get("/api/ingredients/sections").body<List<IngredientSection>>().single { it.ingredientId == chicken.id }.keepsByHand)

        // Alice's tick puts it in her pantry only; her correction is hers.
        alice.put("/api/plans/$week/shopping") { setBody(ShoppingCheck("ahead:i${chicken.id}", true)) }
        assertEquals(listOf("Chicken thigh"), alice.get("/api/pantry").body<Pantry>().onHand.map { it.name })
        assertEquals(emptyList(), bob.get("/api/pantry").body<Pantry>().onHand)
        alice.put("/api/ingredients/${chicken.id}/keeps") { setBody(KeepsChoice(true)) }
        assertTrue(bob.get("/api/ingredients/sections").body<List<IngredientSection>>().none { it.ingredientId == chicken.id })

        // Bob can't make her tick one set by hand: un-ticking still takes it back.
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/pantry/on-hand/${chicken.id}").status)
        alice.put("/api/plans/$week/shopping") { setBody(ShoppingCheck("ahead:i${chicken.id}", false)) }
        assertEquals(emptyList(), alice.get("/api/pantry").body<Pantry>().onHand)

        // The switch and a dismissed group are per account.
        alice.put("/api/shopping-advice") { setBody(ShoppingAdvice(buyAhead = false, stockUp = false)) }
        assertEquals(ShoppingAdvice(), bob.get("/api/shopping-advice").body<ShoppingAdvice>())
        bob.put("/api/plans/$week/shopping/dismissed/buy-ahead")
        assertFalse(alice.get("/api/plans/$week/shopping").body<ShoppingList>().buyAheadDismissed)
        assertTrue(bob.get("/api/plans/$week/shopping").body<ShoppingList>().buyAheadDismissed)
        assertEquals(ShoppingAdvice(buyAhead = false, stockUp = false), alice.get("/api/shopping-advice").body<ShoppingAdvice>())
    }

    @Test
    fun shoppingListTicksStayWithTheirAccount() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        val db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        val week = "2026-11-16"
        // Without NEVO data the planner plans nothing, so each account's stew is put in its plan directly.
        for ((client, subject) in listOf(alice to "sub-alice", bob to "sub-bob")) {
            val stew = client.post("/api/recipes") { setBody(recipe) }.body<RecipeDetail>().summary
            val userId = db.userQueries.selectBySubject(subject).executeAsOne().id
            db.planQueries.upsertMeal(userId, week, 0, "DINNER", stew.id, stew.title, 2.0, 1000.0, 50.0, 0, 0, "PLANNED")
        }
        suspend fun HttpClient.list() = get("/api/plans/$week/shopping").body<ShoppingList>()
        val alicesItems = alice.list().items
        assertTrue(alicesItems.isNotEmpty())
        // Lines with no ingredient linked have the same keys in both lists ("t:beef").
        assertEquals(alicesItems.map { it.key }, bob.list().items.map { it.key })

        // Alice's ticks are hers: Bob's list of the same stew stays as it was.
        for (item in alicesItems) alice.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(item.key, true)) }
        assertTrue(alice.list().items.all { it.checked })
        assertTrue(bob.list().items.isNotEmpty())
        assertTrue(bob.list().items.none { it.checked })

        // Bob un-ticking her keys, or ticking them, changes only his own list.
        for (item in alicesItems) {
            assertEquals(HttpStatusCode.OK, bob.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(item.key, false)) }.status)
        }
        assertTrue(alice.list().items.all { it.checked })
        val first = alicesItems.first().key
        alice.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(first, false)) }
        bob.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(first, true)) }
        assertFalse(alice.list().items.single { it.key == first }.checked)
    }

    @Test
    fun linkingATextMovesOnlyTheAccountsOwnTicks() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        val db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val week = "2026-11-16"
        val accounts = listOf("alice", "bob").associateWith { clientFor(it) }
        suspend fun HttpClient.list() = get("/api/plans/$week/shopping").body<ShoppingList>()
        // Both have the stew, whose beef no ingredient is linked to yet, and both bought it (t:beef).
        for ((name, client) in accounts) {
            val stew = client.post("/api/recipes") { setBody(recipe) }.body<RecipeDetail>().summary
            db.planQueries.upsertMeal(db.userQueries.selectBySubject("sub-$name").executeAsOne().id, week, 0, "DINNER", stew.id, stew.title, 2.0, 1000.0, 50.0, 0, 0, "PLANNED")
            client.put("/api/plans/$week/shopping") { setBody(ShoppingCheck("t:beef", true)) }
        }
        val alice = accounts.getValue("alice")
        val bob = accounts.getValue("bob")

        // Alice's new ingredient links her text: her tick follows her row; Bob's list and tick are as they were.
        val beef = alice.post("/api/ingredients") { setBody(IngredientInput("Beef")) }.body<Ingredient>()
        val hers = alice.list()
        assertEquals(true to 400.0, hers.items.single { it.key == "i${beef.id}" }.let { it.checked to it.boughtGrams })
        assertEquals(emptyList(), hers.noLongerNeeded)
        val his = bob.list()
        assertEquals(true to 400.0, his.items.single { it.key == "t:beef" }.let { it.checked to it.boughtGrams })
        assertEquals(emptyList(), his.noLongerNeeded)
        assertEquals(listOf("t:beef"), db.shoppingQueries.selectChecks(db.userQueries.selectBySubject("sub-bob").executeAsOne().id, week).executeAsList().map { it.itemKey })
    }

    @Test
    fun whatAnAccountBoughtIsSeenAndChangedByItAlone() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        val db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        val week = "2026-11-16"
        val accounts = listOf(alice to "sub-alice", bob to "sub-bob")
        suspend fun HttpClient.list() = get("/api/plans/$week/shopping").body<ShoppingList>()
        suspend fun HttpClient.tick(key: String, checked: Boolean = true) = put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, checked)) }
        // Without NEVO data the planner plans nothing, so each account's dinner is put in its plan directly.
        suspend fun HttpClient.dine(subject: String, input: RecipeInput) {
            val recipe = post("/api/recipes") { setBody(input) }.body<RecipeDetail>().summary
            val userId = db.userQueries.selectBySubject(subject).executeAsOne().id
            db.planQueries.upsertMeal(userId, week, 0, "DINNER", recipe.id, recipe.title, 2.0, 1000.0, 50.0, 0, 0, "PLANNED")
        }
        // Alice's beef is an ingredient of her catalogue (key i<id>); the onion is linked in neither
        // account, so both lists have its row under the same key.
        val beef = alice.post("/api/ingredients") { setBody(IngredientInput("Beef")) }.body<Ingredient>()
        for ((client, subject) in accounts) client.dine(subject, recipe)
        val alicesItems = alice.list().items
        assertTrue("i${beef.id}" in alicesItems.map { it.key })
        assertTrue(alicesItems.map { it.key }.intersect(bob.list().items.map { it.key }.toSet()).isNotEmpty())

        // Alice buys everything: her ticks remember it, Bob's list knows nothing of it.
        for (item in alicesItems) alice.tick(item.key)
        assertEquals(400.0, alice.list().items.single { it.key == "i${beef.id}" }.boughtGrams)
        assertTrue(bob.list().items.none { it.checked || it.boughtGrams != null })

        // Both plans change, so nothing on the lists is needed any more: only Alice bought it.
        for ((client, subject) in accounts) client.dine(subject, RecipeInput("Salad", 2, "200 | g | lettuce"))
        val bought = alice.list().noLongerNeeded
        assertEquals(alicesItems.map { it.key }.toSet(), bought.map { it.key }.toSet())
        assertEquals(emptyList(), bob.list().noLongerNeeded)

        // Bob ticking and un-ticking her keys touches his own ticks only, and as no row of his list they remember nothing.
        for (item in alicesItems) {
            assertEquals(HttpStatusCode.OK, bob.tick(item.key, checked = false).status)
            assertEquals(HttpStatusCode.OK, bob.tick(item.key).status)
        }
        assertEquals(emptyList(), bob.list().noLongerNeeded)
        assertEquals(bought, alice.list().noLongerNeeded)
    }

    @Test
    fun onlyTheAccountThatBoughtItUsesItUpOrTicksItAgain() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        val db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        val week = "2026-11-16"
        suspend fun HttpClient.list() = get("/api/plans/$week/shopping").body<ShoppingList>()
        suspend fun HttpClient.tick(key: String) = put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, true)) }
        suspend fun HttpClient.again(key: String) = put("/api/plans/$week/shopping/again") { setBody(ShoppingRowKey(key)) }
        suspend fun HttpClient.useUp(key: String) = post("/api/plans/$week/shopping/use-up") { setBody(ShoppingRowKey(key)) }
        suspend fun HttpClient.dine(subject: String, input: RecipeInput) {
            val recipe = post("/api/recipes") { setBody(input) }.body<RecipeDetail>().summary
            val userId = db.userQueries.selectBySubject(subject).executeAsOne().id
            db.planQueries.upsertMeal(userId, week, 0, "DINNER", recipe.id, recipe.title, 2.0, 1000.0, 50.0, 0, 0, "PLANNED")
        }
        // Alice's beef is hers (i<id>); the onion is linked in neither account, so both lists have its row under one key.
        val beef = alice.post("/api/ingredients") { setBody(IngredientInput("Beef")) }.body<Ingredient>()
        val beefKey = "i${beef.id}"
        alice.dine("sub-alice", recipe)
        bob.dine("sub-bob", recipe)
        val onion = alice.list().items.first { it.key.startsWith("t:") }.key
        alice.tick(onion)

        // Bob has no tick of the onion, so he can't tick hers again; his own tick of it stays apart.
        assertEquals(HttpStatusCode.NotFound, bob.again(onion).status)
        assertEquals(HttpStatusCode.NotFound, bob.again(beefKey).status)
        assertTrue(alice.list().items.single { it.key == onion }.checked)
        bob.tick(onion)
        assertEquals(HttpStatusCode.OK, bob.again(onion).status)
        assertTrue(alice.list().items.single { it.key == onion }.checked)

        // Alice bought the beef, then her plan changed: only she can use it up.
        alice.tick(beefKey)
        alice.dine("sub-alice", RecipeInput("Salad", 2, "200 | g | lettuce"))
        assertEquals(listOf(beefKey), alice.list().noLongerNeeded.filter { it.key == beefKey }.map { it.key })
        assertEquals(HttpStatusCode.NotFound, bob.useUp(beefKey).status)
        // Not even after ticking her key himself: his tick is no bought item of his list.
        bob.tick(beefKey)
        assertEquals(HttpStatusCode.NotFound, bob.useUp(beefKey).status)
        assertEquals(emptyList(), alice.get("/api/pantry").body<Pantry>().onHand)
        assertEquals(emptyList(), bob.get("/api/pantry").body<Pantry>().onHand)
        assertTrue(alice.list().noLongerNeeded.any { it.key == beefKey })

        // Alice's use-up stocks her pantry with what she bought, and only hers.
        assertTrue(alice.useUp(beefKey).body<ShoppingList>().noLongerNeeded.none { it.key == beefKey })
        assertEquals(mapOf(beef.id to 400.0), alice.get("/api/pantry").body<Pantry>().onHandGrams)
        assertTrue(bob.get("/api/pantry").body<Pantry>().onHandGrams.isEmpty())
        assertEquals(emptyList(), bob.get("/api/pantry").body<Pantry>().onHand)
    }

    @Test
    fun whatABoughtRowSavedIsSeenAndChangedByItsAccountAlone() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        val db = openDatabase(config.databaseUrl).second
        // Offers are shared: chicken at a quarter off, valid whatever day the test runs on.
        val jumbo = object : OfferSource {
            override val store = Store.JUMBO
            override suspend fun fetch() = listOf(Offer(Store.JUMBO, "Jumbo Kipfilet 1 kg", "25% korting", packSize = "1 kg", validFrom = "2000-01-01", validUntil = "2999-12-31"))
        }
        application { module(config, db, offerSources = listOf(jumbo), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        val week = "2026-11-16"
        alice.post("/api/offers/refresh")
        suspend fun HttpClient.list() = get("/api/plans/$week/shopping").body<ShoppingList>()
        suspend fun HttpClient.tick(key: String, checked: Boolean = true) = put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, checked)) }
        // Each has a kilo of chicken to buy, at a regular price of their own: €10 for Alice, €20 for Bob.
        val keys = mapOf("alice" to 10.0, "bob" to 20.0).mapValues { (name, price) ->
            val client = if (name == "alice") alice else bob
            val chicken = client.post("/api/ingredients") { setBody(IngredientInput("Kipfilet", pricePerKg = price)) }.body<Ingredient>()
            val recipe = client.post("/api/recipes") { setBody(RecipeInput("Chicken", 1, "1000 | g | kipfilet")) }.body<RecipeDetail>().summary
            db.planQueries.upsertMeal(db.userQueries.selectBySubject("sub-$name").executeAsOne().id, week, 0, "DINNER", recipe.id, recipe.title, 1.0, 1000.0, 50.0, 0, 0, "PLANNED")
            "i${chicken.id}"
        }
        val hers = keys.getValue("alice")
        val his = keys.getValue("bob")
        suspend fun HttpClient.saved(key: String) = list().items.single { it.key == key }.let { it.boughtSaving to it.boughtOfferStore }

        // Alice's tick keeps what her row saved; Bob's list knows nothing of it.
        alice.tick(hers)
        assertEquals(2.5 to Store.JUMBO, alice.saved(hers))
        assertEquals(null to null, bob.saved(his))
        assertTrue(bob.list().items.none { it.key == hers })

        // Bob ticking and un-ticking her key touches only a tick of his own, which keeps nothing: her row isn't on his list.
        bob.tick(hers)
        bob.tick(hers, checked = false)
        bob.tick(hers)
        assertEquals(2.5 to Store.JUMBO, alice.saved(hers))
        val bobId = db.userQueries.selectBySubject("sub-bob").executeAsOne().id
        assertEquals(listOf(Triple(hers, null, null)), db.shoppingQueries.selectChecks(bobId, week).executeAsList().map { Triple(it.itemKey, it.saving, it.savingStore) })

        // His own tick keeps his own saving, at his price; hers stays as it was.
        bob.tick(his)
        assertEquals(5.0 to Store.JUMBO, bob.saved(his))
        assertEquals(2.5 to Store.JUMBO, alice.saved(hers))
    }

    @Test
    fun ownShoppingItemsStayWithTheirAccount() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        val db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        val week = "2026-11-16"
        suspend fun HttpClient.list() = get("/api/plans/$week/shopping").body<ShoppingList>()
        suspend fun HttpClient.add(name: String) = post("/api/plans/$week/shopping/extras") { setBody(ShoppingExtraInput(name)) }
        val coffee = alice.add("Coffee").body<ShoppingList>().items.single()
        val id = coffee.key.removePrefix("x:")

        // Bob doesn't see it, and can neither tick it off, un-tick it nor delete it.
        assertEquals(emptyList(), bob.list().items)
        alice.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(coffee.key, true)) }
        for (checked in listOf(true, false)) {
            assertEquals(HttpStatusCode.NotFound, bob.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(coffee.key, checked)) }.status)
        }
        assertEquals(HttpStatusCode.NotFound, bob.delete("/api/plans/$week/shopping/extras/$id").status)
        assertEquals(listOf("Coffee" to true), alice.list().items.map { it.name to it.checked })
        alice.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(coffee.key, false)) }
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(coffee.key, true)) }.status)
        assertEquals(listOf("Coffee" to false), alice.list().items.map { it.name to it.checked })
        assertEquals(emptyList(), bob.list().items)

        // An own item of the same name is his alone, and its section comes from his catalogue, not hers.
        val shallot = alice.post("/api/ingredients") { setBody(IngredientInput("Shallot")) }.body<Ingredient>()
        alice.put("/api/ingredients/${shallot.id}/section") { setBody(SectionChoice("FRUIT_VEG")) }
        bob.add("Coffee")
        val bobs = bob.add("Shallot").body<ShoppingList>().items
        assertEquals(listOf("Coffee", "Shallot"), bobs.map { it.name })
        assertTrue(bobs.all { it.category == ShopSection.OTHER.title })
        val alices = alice.add("Shallot").body<ShoppingList>().items
        assertEquals(listOf(ShopSection.FRUIT_VEG.title to "Shallot", ShopSection.OTHER.title to "Coffee"), alices.map { it.category to it.name })
        assertTrue(alices.none { it.key in bobs.map { b -> b.key } })

        // The limit on open items is per account: Bob's full list doesn't stop Alice.
        val bobId = db.userQueries.selectBySubject("sub-bob").executeAsOne().id
        repeat(ShoppingExtraInput.MAX_OPEN - 2) { db.shoppingQueries.insertExtra(bobId, "Item $it", week) }
        assertEquals(HttpStatusCode.Conflict, bob.add("Tea").status)
        assertEquals(HttpStatusCode.Created, alice.add("Tea").status)
        assertEquals(3, alice.list().items.size)
    }

    @Test
    fun eatingAMealUsesUpOnlyTheAccountsOwnStock() {
        val db = openDatabase("jdbc:sqlite::memory:").second
        val foods = FoodRepository(db)
        val users = UserRepository(db)
        val aliceId = users.resolve(Identity("sub-alice", "alice")).id
        val bobId = users.resolve(Identity("sub-bob", "bob")).id
        val alice = StockRepository(db, foods, aliceId)
        val bob = StockRepository(db, foods, bobId)
        val week = java.time.LocalDate.parse("2026-11-23")
        // Both have a dinner on Monday: the same place in the plan, so the same key for what it took.
        val dinner = MealRef(week, 0, MealSlot.DINNER)
        for (id in listOf(aliceId, bobId)) {
            db.planQueries.upsertMeal(id, week.toString(), 0, "DINNER", 1, "Stew", 2.0, 800.0, 40.0, 0, 0, "EATEN")
        }
        val chicken = IngredientRepository(db, foods, aliceId).create(IngredientInput("Chicken thigh"))
        alice.putOnHandFrom(chicken.id, week, grams = 1000.0, source = StockSource.STOCK_UP)
        fun left() = db.pantryQueries.selectStock(aliceId, week.toString(), listOf(chicken.id)).executeAsOneOrNull()?.grams

        // Bob naming Alice's ingredient as one his meal used takes nothing from her pantry, nor does
        // his marking it on hand by hand make her tick one set by hand.
        bob.useUp(dinner, mapOf(chicken.id to 400.0))
        assertFailsWith<NotFoundException> { bob.setOnHand(chicken.id, true) }
        assertEquals(1000.0, left())
        alice.useUp(dinner, mapOf(chicken.id to 400.0))
        assertEquals(600.0, left())

        // Un-eating his dinner gives nothing of hers back and leaves what hers took to give back;
        // he can't take her stock off either, by hand or by un-ticking.
        bob.giveBack(dinner)
        assertFailsWith<NotFoundException> { bob.setOnHand(chicken.id, false) }
        assertFailsWith<NotFoundException> { bob.takeBackOnHand(chicken.id, week, StockSource.STOCK_UP) }
        assertEquals(600.0, left())
        alice.giveBack(dinner)
        assertEquals(1000.0, left())
    }

    @Test
    fun theStatementsThatChangeThePantryNameTheAccount() {
        val db = openDatabase("jdbc:sqlite::memory:").second
        val users = UserRepository(db)
        val aliceId = users.resolve(Identity("sub-alice", "alice")).id
        val bobId = users.resolve(Identity("sub-bob", "bob")).id
        val alice = StockRepository(db, FoodRepository(db), aliceId)
        val week = "2026-11-23"
        val chicken = IngredientRepository(db, FoodRepository(db), aliceId).create(IngredientInput("Chicken thigh")).id
        val rice = IngredientRepository(db, FoodRepository(db), aliceId).create(IngredientInput("Rice")).id
        alice.putOnHandFrom(chicken, java.time.LocalDate.parse(week), grams = 1000.0, source = StockSource.STOCK_UP)
        alice.setOnHand(rice, true)
        val p = db.pantryQueries
        fun pantry() = p.selectStock(aliceId, week, listOf(chicken, rice)).executeAsList() to p.selectOnHandByHand(aliceId).executeAsList()
        val before = pantry()

        // Called with Bob's account and Alice's ingredients, as a caller that forgot its own check would.
        val source = StockSource.STOCK_UP.name
        assertEquals(0L, p.insertOnHand(chicken, "2026-11-15T00:00:00Z", bobId).value)
        assertEquals(0L, p.insertStock(week, StockSource.USE_UP.name, "2026-11-15T00:00:00Z", 500.0, chicken, bobId).value)
        assertEquals(0L, p.addToStock(500.0, chicken, week, source, bobId).value)
        assertEquals(0L, p.setStockGrams(1.0, chicken, week, source, bobId).value)
        assertEquals(0L, p.deleteStock(chicken, week, source, bobId).value)
        assertEquals(0L, p.deleteStockOf(chicken, bobId).value)
        assertEquals(0L, p.deleteOnHand(rice, bobId).value)
        assertEquals(before, pantry())
        assertEquals(emptyList(), p.selectOnHand(bobId).executeAsList())
        assertEquals(emptyList(), p.selectStockOfAccount(bobId).executeAsList())
        assertEquals(emptyList(), p.selectOnHandByHand(bobId).executeAsList())
    }

    @Test
    fun eatingAndUnEatingTouchOnlyTheAccountsOwnStock() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        val db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val week = "2026-11-16"
        // Each has a stew of their own on Monday (400 g of beef for 2 servings) and 1 kg of beef bought for it.
        val accounts = listOf("alice", "bob").associateWith { name ->
            val client = clientFor(name)
            client.put("/api/profile") { setBody(Profile(80.0, 175.0, 40, Sex.FEMALE, ActivityLevel.LIGHT)) }
            val beef = client.post("/api/ingredients") { setBody(IngredientInput("Beef")) }.body<Ingredient>()
            val stew = client.post("/api/recipes") { setBody(recipe) }.body<RecipeDetail>().summary
            val id = db.userQueries.selectBySubject("sub-$name").executeAsOne().id
            db.planQueries.upsertMeal(id, week, 0, "DINNER", stew.id, stew.title, 2.0, 800.0, 40.0, 0, 0, "PLANNED")
            StockRepository(db, FoodRepository(db), id).putOnHandFrom(beef.id, java.time.LocalDate.parse(week), grams = 1000.0, source = StockSource.STOCK_UP)
            Triple(client, id, beef.id)
        }
        fun left(name: String): Double? {
            val (_, id, beef) = accounts.getValue(name)
            return db.pantryQueries.selectStock(id, week, listOf(beef)).executeAsOneOrNull()?.grams
        }
        suspend fun mark(name: String, status: MealStatus) =
            assertEquals(HttpStatusCode.OK, accounts.getValue(name).first.put("/api/plans/$week/meals/0/dinner/status") { setBody(status) }.status)

        // What each is shown of the stock, on the list's row and in the pantry: their own amount, and nothing of the other's.
        suspend fun shown(name: String): Pair<Double?, Map<Long, Double>> {
            val client = accounts.getValue(name).first
            val row = client.get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.name == "Beef" }
            return row.onHandGrams to client.get("/api/pantry").body<Pantry>().onHandGrams
        }
        val (bob, _, bobsBeef) = accounts.getValue("bob")
        val alicesBeef = accounts.getValue("alice").third

        mark("bob", MealStatus.EATEN)
        assertEquals(600.0, left("bob"))
        assertEquals(1000.0, left("alice"), "Bob's dinner is eaten from his beef only")
        assertEquals(600.0 to mapOf(bobsBeef to 600.0), shown("bob"))
        assertEquals(1000.0 to mapOf(alicesBeef to 1000.0), shown("alice"))

        // A late tick of Bob's naming Alice's beef, with an amount, is refused and changes nothing of hers.
        val late = ShoppingCheck("stock:i$alicesBeef", true, grams = 300.0)
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/plans/$week/shopping") { setBody(late) }.status)
        assertEquals(1000.0, left("alice"))
        mark("alice", MealStatus.EATEN)
        mark("bob", MealStatus.SKIPPED)
        assertEquals(1000.0, left("bob"))
        assertEquals(600.0, left("alice"), "un-eating Bob's dinner gives back only what his took")
        mark("alice", MealStatus.PLANNED)
        assertEquals(1000.0, left("alice"))
    }

    @Test
    fun unTickingTakesBackOnlyTheAccountsOwnEntryOfItsStock() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        val db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val week = "2026-11-16"
        suspend fun HttpClient.tick(key: String, checked: Boolean = true, grams: Double? = null) =
            put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, checked, grams)) }
        suspend fun HttpClient.grams() = get("/api/pantry").body<Pantry>().onHandGrams
        fun ticks(subject: String) =
            db.shoppingQueries.selectChecks(db.userQueries.selectBySubject(subject).executeAsOne().id, week).executeAsList().map { it.itemKey }.toSet()

        // Each buys beef three ways: 400 g for a stew the plan then dropped (used up), 500 g stocked up, 300 g bought ahead.
        val beefOf = listOf("alice", "bob").associateWith { name ->
            val client = clientFor(name)
            val userId = { db.userQueries.selectBySubject("sub-$name").executeAsOne().id }
            val beef = client.post("/api/ingredients") { setBody(IngredientInput("Beef")) }.body<Ingredient>().id
            val stew = client.post("/api/recipes") { setBody(recipe) }.body<RecipeDetail>().summary
            val salad = client.post("/api/recipes") { setBody(RecipeInput("Salad", 2, "200 | g | lettuce")) }.body<RecipeDetail>().summary
            db.planQueries.upsertMeal(userId(), week, 0, "DINNER", stew.id, stew.title, 2.0, 1000.0, 50.0, 0, 0, "PLANNED")
            client.tick("i$beef")
            db.planQueries.upsertMeal(userId(), week, 0, "DINNER", salad.id, salad.title, 2.0, 300.0, 5.0, 0, 0, "PLANNED")
            assertEquals(HttpStatusCode.OK, client.post("/api/plans/$week/shopping/use-up") { setBody(ShoppingRowKey("i$beef")) }.status)
            client.tick("stock:i$beef", grams = 500.0)
            client.tick("ahead:i$beef", grams = 300.0)
            assertEquals(mapOf(beef to 1200.0), client.grams())
            beef
        }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        val alicesBeef = beefOf.getValue("alice")
        val bobsBeef = beefOf.getValue("bob")
        val bobsTicks = ticks("sub-bob")

        // Bob can't take back what Alice's ticks put in her pantry.
        for (key in listOf("stock:i$alicesBeef", "ahead:i$alicesBeef")) {
            assertEquals(HttpStatusCode.NotFound, bob.tick(key, checked = false).status)
        }
        assertEquals(mapOf(alicesBeef to 1200.0), alice.grams())
        assertEquals(setOf("stock:i$alicesBeef", "ahead:i$alicesBeef"), ticks("sub-alice"))

        // Alice un-ticks both: what she used up stays, and Bob's stock and ticks are as they were.
        alice.tick("stock:i$alicesBeef", checked = false)
        alice.tick("ahead:i$alicesBeef", checked = false)
        assertEquals(mapOf(alicesBeef to 400.0), alice.grams())
        assertEquals(mapOf(bobsBeef to 1200.0), bob.grams())
        assertEquals(bobsTicks, ticks("sub-bob"))
        assertEquals(setOf("stock:i$bobsBeef", "ahead:i$bobsBeef"), bobsTicks)
    }

    @Test
    fun whatIsStillToBuyGoesByTheAccountsOwnMealsAndStock() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        val db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val week = "2026-11-16"
        // Each has the same two stews this week (400 g of beef each); only Alice has 500 g of beef at home.
        val clients = listOf("alice", "bob").associateWith { name ->
            val client = clientFor(name)
            client.put("/api/profile") { setBody(Profile(80.0, 175.0, 40, Sex.FEMALE, ActivityLevel.LIGHT)) }
            val beef = client.post("/api/ingredients") { setBody(IngredientInput("Beef")) }.body<Ingredient>()
            val stew = client.post("/api/recipes") { setBody(recipe) }.body<RecipeDetail>().summary
            val id = db.userQueries.selectBySubject("sub-$name").executeAsOne().id
            for (day in 0L..1L) db.planQueries.upsertMeal(id, week, day, "DINNER", stew.id, stew.title, 2.0, 800.0, 40.0, 0, 0, "PLANNED")
            if (name == "alice") StockRepository(db, FoodRepository(db), id).putOnHandFrom(beef.id, java.time.LocalDate.parse(week), grams = 500.0, source = StockSource.STOCK_UP)
            client
        }
        // What each is told the meals to come need, and what is at home: their own amounts only.
        suspend fun shown(name: String) =
            clients.getValue(name).get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.name == "Beef" }
                .let { it.toComeGrams to it.onHandGrams }
        suspend fun eat(name: String, day: Int) = assertEquals(
            HttpStatusCode.OK,
            clients.getValue(name).put("/api/plans/$week/meals/$day/dinner/status") { setBody(MealStatus.EATEN) }.status,
        )
        assertEquals(800.0 to 500.0, shown("alice"))
        assertEquals(800.0 to null, shown("bob"))

        // Alice's Monday stew eaten from her beef: 400 g to come for her, and Bob's list is as it was.
        eat("alice", 0)
        assertEquals(400.0 to 100.0, shown("alice"))
        assertEquals(800.0 to null, shown("bob"), "Alice's meal is not his")
        // Bob eats both of his, without any stock: nothing to come for him, nothing changes for her.
        eat("bob", 0)
        eat("bob", 1)
        assertEquals(0.0 to null, shown("bob"))
        assertEquals(400.0 to 100.0, shown("alice"))
    }

    @Test
    fun accountNamesAndLoginConfig() = testApplication {
        start()
        assertEquals(Me("alice", "Alice"), clientFor("alice").get("/api/me").body<Me>())
        // Public: clients need it before they can log in.
        val config = createClient { install(ContentNegotiation) { json() } }.get("/api/auth/config").body<AuthConfig>()
        assertEquals("mealplanner", config.clientId)
        assertTrue("offline_access" in config.scopes)
        assertEquals(HttpStatusCode.Unauthorized, clientFor("nope").get("/api/me").status)

        // The Android login redirect goes on into the app with the code and state: a redirect for
        // Android browsers, a page with a link for others.
        val intent = "intent://login?code=abc&state=x%2By#Intent;scheme=dev.tobins.mealplanner;package=dev.tobins.mealplanner;end"
        val plain = createClient { followRedirects = false }
        val android = plain.get("/app/login?code=abc&state=x%2By") { headers.append("User-Agent", "Mozilla/5.0 (Linux; Android 16) Chrome/157") }
        assertEquals(HttpStatusCode.Found, android.status)
        assertEquals(intent, android.headers["Location"])
        val page = plain.get("/app/login?code=abc&state=x%2By").bodyAsText()
        assertTrue(intent.replace("&", "&amp;") in page, page)
    }

    @Test
    fun assetLinksVouchForTheApp() {
        assertEquals("[]", assetLinks(emptyList()))
        val links = assetLinks(listOf("ab:cd"))
        assertTrue(""""package_name":"dev.tobins.mealplanner"""" in links && """"AB:CD"""" in links, links)
    }

    @Test
    fun eachAccountSetsItsOwnCookingRepo() = testApplication {
        start()
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        assertNull(bob.get("/api/sync/cooking").body<CookingSyncStatus>().repoUrl, "no site on this server, none of his own")
        assertEquals(HttpStatusCode.BadRequest, bob.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting("not a url")) }.status)

        val status = bob.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting("https://example.org/bob/cooking.git")) }.body<CookingSyncStatus>()
        assertEquals("https://example.org/bob/cooking.git", status.repoUrl)
        assertEquals(CookingSync.EVERY_HOURS, status.everyHours)
        assertNull(alice.get("/api/sync/cooking").body<CookingSyncStatus>().repoUrl, "alice has none")
        assertNull(bob.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting(null)) }.body<CookingSyncStatus>().repoUrl)
    }

    @Test
    fun recipePicturesStayWithTheirAccount() = testApplication {
        val site = PictureFixtures.site(
            "content/recipes/pizza/index.md" to PictureFixtures.page("Pizza", "image: pizza.jpg"),
            "content/recipes/pizza/pizza.jpg" to PictureFixtures.jpeg(40, 30),
        )
        // The server's site is the bank of both: Alice, the owner, who logs in first, and Bob.
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = site, imageDir = createTempDirectory("images").toFile())
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        assertEquals(HttpStatusCode.OK, alice.post("/api/sync/cooking").status)
        alice.addWholeBank()
        val pizza = alice.get("/api/recipes").body<List<RecipeSummary>>().single()
        val url = pizza.imageUrl!!
        assertEquals(HttpStatusCode.OK, alice.get(url).status)

        // Bob sees the picture of the pizza in his own bank, a copy of his own at a URL of his own...
        val bobsPizza = bob.get("/api/recipes/bank").body<List<RecipeSummary>>().single()
        assertNotEquals(pizza.id, bobsPizza.id)
        val bobsUrl = bobsPizza.imageUrl!!
        assertEquals(HttpStatusCode.OK, bob.get(bobsUrl).status)
        // ...and gets nothing of Alice's, by the picture's URL or by the recipe's id, nor she of his.
        assertEquals(HttpStatusCode.NotFound, bob.get(url).status)
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/recipes/${pizza.id}/image").status)
        assertEquals(HttpStatusCode.NotFound, alice.get(bobsUrl).status)
        // Syncing, setting a site of his own and going back to the server's touch only his account.
        assertEquals(HttpStatusCode.OK, bob.post("/api/sync/cooking").status)
        bob.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting("https://example.org/bob/cooking.git")) }
        assertEquals(emptyList(), bob.get("/api/recipes/bank").body<List<RecipeSummary>>(), "the server's bank-only recipes left with its site")
        assertEquals(HttpStatusCode.NotFound, bob.get(bobsUrl).status)
        bob.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting(null)) }
        assertEquals(HttpStatusCode.OK, bob.get(bob.get("/api/recipes/bank").body<List<RecipeSummary>>().single().imageUrl!!).status, "back on the server's site")
        assertEquals(url, alice.get("/api/recipes").body<List<RecipeSummary>>().single().imageUrl)
        assertEquals(HttpStatusCode.OK, alice.get(url).status)

        // Alice moving to another site of her own removes her pictures until that one is synced; Bob keeps his.
        alice.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting("https://example.org/alice/cooking.git")) }
        assertNull(alice.get("/api/recipes").body<List<RecipeSummary>>().single().imageUrl)
        assertEquals(HttpStatusCode.NotFound, alice.get(url).status)
        assertTrue(bob.get("/api/recipes/bank").body<List<RecipeSummary>>().single().imageUrl != null)
    }

    /** A cooking site's pages, each a recipe of 400 g of a [meat] that no catalogue links. */
    private fun site(vararg titles: String, meat: String = "mystery meat"): File = createTempDirectory("cooking").toFile().also { site ->
        for (title in titles) {
            File(site, "content/recipes/${title.lowercase()}.md").apply { parentFile.mkdirs() }
                .writeText("---\ntitle: $title\n---\n```ingredients\nservings: 2\n400 | g | $meat\n```")
        }
    }

    /**
     * The server's cooking site is the bank of every account (issue 208), as rows of each account's own: Bob's first
     * look at the bank finds it without a sync, and adding, rating and the rotation in one account leave the other's alone.
     */
    @Test
    fun theServersSiteIsEveryAccountsBankAsItsOwnRecipes() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = site("Stew", "Curry"))
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        alice.post("/api/sync/cooking")
        val alices = alice.get("/api/recipes/bank").body<List<RecipeSummary>>().associate { it.title to it.id }

        // Bob, who never synced, finds the server's recipes in his bank, and none of them in his recipes.
        val status = bob.get("/api/sync/cooking").body<CookingSyncStatus>()
        assertTrue(status.shared && status.serverSite, "$status")
        val bobs = bob.get("/api/recipes/bank").body<List<RecipeSummary>>().associate { it.title to it.id }
        assertEquals(setOf("Curry", "Stew"), bobs.keys)
        assertTrue(bobs.values.none { it in alices.values }, "rows of his own: $bobs, Alice's $alices")
        assertEquals(emptyList(), bob.get("/api/recipes").body<List<RecipeSummary>>())
        assertNotNull(bob.get("/api/sync/cooking").body<CookingSyncStatus>().syncedAt, "the first look imported")

        // Bob adds the stew, rates it and takes it out of his rotation: Alice's stew stays in her bank, unrated.
        bob.put("/api/recipes/${bobs.getValue("Stew")}/added") { setBody(AddedInput(added = true)) }
        bob.put("/api/recipes/${bobs.getValue("Stew")}/rating") { setBody(RatingInput(5)) }
        bob.put("/api/recipes/${bobs.getValue("Stew")}/rotation") { setBody(RotationInput(false)) }
        assertEquals(emptyList(), alice.get("/api/recipes").body<List<RecipeSummary>>())
        assertEquals(setOf("Curry", "Stew"), alice.get("/api/recipes/bank").body<List<RecipeSummary>>().map { it.title }.toSet())
        assertNull(alice.get("/api/recipes/${alices.getValue("Stew")}").body<RecipeDetail>().summary.rating)

        // Alice adds both, in her rotation, and rates the curry: Bob's stay as he left them.
        alice.addWholeBank()
        alice.put("/api/recipes/${alices.getValue("Curry")}/rating") { setBody(RatingInput(2)) }
        val bobsStew = bob.get("/api/recipes").body<List<RecipeSummary>>().single()
        assertEquals(listOf("Stew", "5", "false"), listOf(bobsStew.title, "${bobsStew.rating}", "${bobsStew.inRotation}"))
        assertEquals(listOf("Curry"), bob.get("/api/recipes/bank").body<List<RecipeSummary>>().map { it.title })
        assertNull(bob.get("/api/recipes/${bobs.getValue("Curry")}").body<RecipeDetail>().summary.rating)
        assertTrue(alice.get("/api/recipes").body<List<RecipeSummary>>().all { it.inRotation })

        // Neither reads nor changes the other's recipes by id.
        for ((reader, ids) in listOf(bob to alices.values, alice to bobs.values)) {
            for (id in ids) {
                assertEquals(HttpStatusCode.NotFound, reader.get("/api/recipes/$id").status)
                assertEquals(HttpStatusCode.NotFound, reader.get("/api/recipes/$id/image").status)
                assertEquals(HttpStatusCode.NotFound, reader.put("/api/recipes/$id/rating") { setBody(RatingInput(1)) }.status)
                assertEquals(HttpStatusCode.NotFound, reader.put("/api/recipes/$id/added") { setBody(AddedInput(added = true)) }.status)
                assertEquals(HttpStatusCode.NotFound, reader.put("/api/recipes/$id/rotation") { setBody(RotationInput(true)) }.status)
            }
        }
        assertNull(bob.get("/api/recipes/${bobs.getValue("Curry")}").body<RecipeDetail>().summary.rating)
        assertEquals(2, alice.get("/api/recipes/${alices.getValue("Curry")}").body<RecipeDetail>().summary.rating)
    }

    /** `COOKING_REPO_SHARED=0`: the server's site is the owner's alone, as before issue 208. */
    @Test
    fun unsharedTheServersSiteIsOnlyTheOwners() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = site("Stew"), cookingRepoShared = false)
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        assertEquals(listOf("Stew"), alice.get("/api/recipes/bank").body<List<RecipeSummary>>().map { it.title }, "the owner's first look imports too")
        assertTrue(alice.get("/api/sync/cooking").body<CookingSyncStatus>().shared)

        assertEquals(emptyList(), bob.get("/api/recipes/bank").body<List<RecipeSummary>>())
        assertEquals(HttpStatusCode.ServiceUnavailable, bob.post("/api/sync/cooking").status)
        bob.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting(null)) }
        assertEquals(emptyList(), bob.get("/api/recipes/bank").body<List<RecipeSummary>>(), "not his to choose")
        val status = bob.get("/api/sync/cooking").body<CookingSyncStatus>()
        assertEquals(listOf(false, false), listOf(status.shared, status.serverSite))
        assertNull(status.serverRepoUrl)
    }

    /** An account may have no bank at all: what it added stays its own, the rest leaves its bank. */
    @Test
    fun anAccountCanChooseNoSite() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = site("Stew", "Curry"))
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        alice.get("/api/recipes/bank")
        val stew = bob.get("/api/recipes/bank").body<List<RecipeSummary>>().single { it.title == "Stew" }
        bob.put("/api/recipes/${stew.id}/added") { setBody(AddedInput(added = true)) }

        val none = bob.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting(null, noSite = true)) }.body<CookingSyncStatus>()
        assertEquals(listOf(false, true), listOf(none.shared, none.serverSite))
        assertNull(none.repoUrl)
        assertEquals(emptyList(), bob.get("/api/recipes/bank").body<List<RecipeSummary>>())
        assertEquals(listOf("Stew" to true), bob.get("/api/recipes").body<List<RecipeSummary>>().map { it.title to it.removedFromSite })
        assertEquals(HttpStatusCode.ServiceUnavailable, bob.post("/api/sync/cooking").status)
        assertEquals(2, alice.get("/api/recipes/bank").body<List<RecipeSummary>>().size, "Alice's bank is hers")

        // Back to the server's site: the curry is in the bank again, and the stew on the site.
        assertTrue(bob.put("/api/sync/cooking/repo") { setBody(CookingRepoSetting(null)) }.body<CookingSyncStatus>().shared)
        assertEquals(listOf("Curry"), bob.get("/api/recipes/bank").body<List<RecipeSummary>>().map { it.title })
        assertEquals(listOf("Stew" to false), bob.get("/api/recipes").body<List<RecipeSummary>>().map { it.title to it.removedFromSite })
    }

    /** The server's address may hold a token; only the owner, who configured it, sees it. */
    @Test
    fun onlyTheOwnerSeesTheServersSiteWithItsCredentials() = testApplication {
        val url = "http://reader:s3cret@127.0.0.1:1/cooking.git"
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = createTempDirectory("clone").toFile(), cookingRepoUrl = url,
            dataDir = createTempDirectory("data").toFile(),
        )
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        assertEquals(url, alice.get("/api/sync/cooking").body<CookingSyncStatus>().repoUrl, "the owner's, as before")
        // Nothing listens there: the fetch fails, and its message is kept for Bob too.
        assertFalse(bob.post("/api/sync/cooking").status.isSuccess())
        val text = bob.get("/api/sync/cooking").bodyAsText()
        val status = Json { ignoreUnknownKeys = true }.decodeFromString<CookingSyncStatus>(text)
        assertEquals("http://127.0.0.1:1/cooking.git", status.repoUrl)
        assertEquals("http://127.0.0.1:1/cooking.git", status.serverRepoUrl)
        assertNotNull(status.error)
        assertFalse("s3cret" in text || "reader" in text, text)
        assertEquals(listOf("http://127.0.0.1:1/x and git@host:y"), listOf(CookingSync.withoutCredentials("http://a:b@127.0.0.1:1/x and git@host:y")))
    }

    /** The bank, adding and the rotation (issue 205): no route reads or changes another account's recipe. */
    @Test
    fun theBankAndTheRotationStayWithTheirAccount() = testApplication {
        // The server's site is the bank of both: Alice, the owner, who logs in first, and Bob.
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = site("Stew", "Curry"))
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val alice = clientFor("alice")
        val bob = clientFor("bob")
        alice.post("/api/sync/cooking")
        val bank = alice.get("/api/recipes/bank").body<List<RecipeSummary>>().associate { it.title to it.id }
        val (stew, curry) = listOf(bank.getValue("Stew"), bank.getValue("Curry"))
        alice.put("/api/recipes/$stew/added") { setBody(AddedInput(added = true)) }
        suspend fun alicesState() = listOf(alice.get("/api/recipes").body<List<RecipeSummary>>(), alice.get("/api/recipes/bank").body<List<RecipeSummary>>())
        val before = alicesState()
        val bobsStew = bob.post("/api/recipes") { setBody(recipe) }.body<RecipeDetail>().summary.id

        // Bob reads none of Alice's: not her bank (his has the same recipes, as rows of his own), not her recipes,
        // not her texts to link.
        val bobsBank = bob.get("/api/recipes/bank").body<List<RecipeSummary>>()
        assertEquals(setOf("Stew", "Curry"), bobsBank.map { it.title }.toSet())
        assertTrue(bobsBank.none { it.id == stew || it.id == curry })
        assertEquals(listOf(bobsStew), bob.get("/api/recipes").body<List<RecipeSummary>>().map { it.id })
        assertTrue(bob.get("/api/mappings/unmapped").body<List<UnmappedText>>().none { it.text == "mystery meat" })
        assertTrue(alice.get("/api/mappings/unmapped").body<List<UnmappedText>>().any { it.text == "mystery meat" }, "Alice's added stew has it")

        // ...and changes none of them, one at a time or many at once: each a 404, and nothing of his own changes either.
        for (id in listOf(stew, curry)) {
            assertEquals(HttpStatusCode.NotFound, bob.get("/api/recipes/$id").status)
            assertEquals(HttpStatusCode.NotFound, bob.put("/api/recipes/$id/added") { setBody(AddedInput(added = true)) }.status)
            assertEquals(HttpStatusCode.NotFound, bob.put("/api/recipes/$id/added") { setBody(AddedInput(added = false)) }.status)
            assertEquals(HttpStatusCode.NotFound, bob.put("/api/recipes/$id/rotation") { setBody(RotationInput(false)) }.status)
            assertEquals(HttpStatusCode.NotFound, bob.put("/api/routines") { setBody(Routines(breakfast = id)) }.status)
        }
        for (added in listOf(true, false)) {
            assertEquals(HttpStatusCode.NotFound, bob.put("/api/recipes/added") { setBody(ManyAddedInput(listOf(bobsStew, stew, curry), added)) }.status)
        }
        assertEquals(HttpStatusCode.NotFound, bob.put("/api/recipes/rotation") { setBody(ManyRotationInput(listOf(bobsStew, stew), inRotation = false)) }.status)
        assertTrue(bob.get("/api/recipes").body<List<RecipeSummary>>().single().inRotation, "his own unchanged: all or nothing")
        assertEquals(before, alicesState())

        // Alice's own changes leave Bob's recipes alone, also with his recipe's id among hers.
        assertEquals(HttpStatusCode.NotFound, alice.put("/api/recipes/rotation") { setBody(ManyRotationInput(listOf(stew, bobsStew), inRotation = false)) }.status)
        assertEquals(HttpStatusCode.NotFound, alice.put("/api/recipes/$bobsStew/added") { setBody(AddedInput(added = false)) }.status)
        assertEquals(before, alicesState())
        assertTrue(bob.get("/api/recipes").body<List<RecipeSummary>>().single().added)
        assertEquals(bobsBank, bob.get("/api/recipes/bank").body<List<RecipeSummary>>())
    }

    @Test
    fun theOwnerTakesOverTheDataFromBeforeAccounts() {
        val db = openDatabase("jdbc:sqlite::memory:").second
        db.userQueries.insertUser(null, null, null, "2026-11-01T00:00:00Z") // what migration 3 leaves

        val users = UserRepository(db)
        val bob = users.resolve(Identity("sub-bob", "bob"), owner = "alice")
        val alice = users.resolve(Identity("sub-alice", "alice"), owner = "alice")
        assertEquals(1L, alice.id, "alice claims the old data")
        assertNotEquals(1L, bob.id)
        assertEquals(alice.id, users.resolve(Identity("sub-alice"), owner = "alice").id, "and keeps it")

        // Without an owner set, the first login takes it.
        val other = openDatabase("jdbc:sqlite::memory:").second
        other.userQueries.insertUser(null, null, null, "2026-11-01T00:00:00Z")
        assertEquals(1L, UserRepository(other).resolve(Identity("sub-carol", "carol")).id)
    }
}
