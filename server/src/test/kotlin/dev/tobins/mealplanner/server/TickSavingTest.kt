package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShoppingRowKey
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.offers.OfferSource
import dev.tobins.mealplanner.released.api.ShoppingList as ReleasedShoppingList
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
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A ticked row keeps what its offer saved when it was ticked (issue 116), so a store's saving on the list
 * says what the shop saved: a meal eaten or an offer ended since takes nothing back.
 */
class TickSavingTest {
    private class Offers(override val store: Store, var offers: List<Offer>) : OfferSource {
        override suspend fun fetch() = offers
    }

    private val week = "2026-11-16"
    private val monday = "2026-11-16"

    /** The day after Jumbo's offer ended: it ran on Monday and Tuesday. */
    private val wednesday = "2026-11-18"

    /** A kilo pack of chicken at a quarter off, on Monday and Tuesday only. */
    private val chickenOffer = Offer(Store.JUMBO, "Jumbo Kipfilet 1 kg", "25% korting", packSize = "1 kg", validFrom = week, validUntil = "2026-11-17")
    private val jumbo = Offers(Store.JUMBO, listOf(chickenOffer))

    private lateinit var db: Database

    private fun ApplicationTestBuilder.start(): HttpClient {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        db = openDatabase(config.databaseUrl).second
        application { module(config, db, listOf(jumbo), priceSource = null, generator = null, verifier = TestTokens) }
        return createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
    }

    /**
     * The offer stored, chicken at €10 a kg, and a dinner of 500 g of it on Monday and on Thursday: the
     * week needs a kilo, €10 at the regular price, so the offer saves €2.50 on it.
     */
    private suspend fun HttpClient.ready(): Ingredient = run {
        // Marking a meal eaten answers with the plan, which needs a profile.
        put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        post("/api/offers/refresh")
        val chicken = post("/api/ingredients") { setBody(IngredientInput("Kipfilet", pricePerKg = 10.0)) }.body<Ingredient>()
        for (day in listOf(0, 3)) dine(day, 500.0)
        chicken
    }

    /** Plans a dinner of [grams] of chicken on [day], as the planner would. */
    private suspend fun HttpClient.dine(day: Int, grams: Double) {
        val recipe = post("/api/recipes") { setBody(RecipeInput("Chicken $day", 1, "${grams.toInt()} | g | kipfilet")) }.body<RecipeDetail>().summary
        db.planQueries.upsertMeal(userId(), week, day.toLong(), "DINNER", recipe.id, recipe.title, 1.0, 500.0, 30.0, 0, 0, MealStatus.PLANNED.name)
    }

    private suspend fun HttpClient.eat(day: Int) =
        assertEquals(HttpStatusCode.OK, put("/api/plans/$week/meals/$day/DINNER/status") { setBody(MealStatus.EATEN) }.status)

    private suspend fun HttpClient.chicken(): ShoppingItem = get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.name == "Kipfilet" }

    private fun userId() = db.userQueries.selectBySubject("sub-t").executeAsOne().id

    /** The account's shopping list as the server is on [day]: the day a tick's saving is worked out for. */
    private fun shopping(day: String, user: Long = userId()): ShoppingListRepository {
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        val prices = PriceRepository(db, null, foods, offers)
        return UserScope(user, db, foods, offers, prices, null, createTempDirectory("pictures").toFile(), today = { LocalDate.parse(day) }).shopping
    }

    private fun tick(day: String, key: String, checked: Boolean = true, shown: ShoppingCheck.Shown? = null): ShoppingItem =
        shopping(day).setChecked(week, ShoppingCheck(key, checked, shown = shown)).items.single { it.key == key }

    /** What a phone that ticked the chicken row on Monday, while the offer ran, says when it sends the tick later. */
    private val mondaysTick = ShoppingCheck.Shown(grams = 1000.0, saving = 2.5, savingStore = Store.JUMBO, tickedOn = monday)

    private fun ShoppingItem.saved(): Pair<Double?, Store?> = boughtSaving to boughtOfferStore

    @Test
    fun aTickedRowKeepsWhatItSavedWhileItsMealsAreEaten() = testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        val row = http.chicken()
        assertEquals(10.0, assertNotNull(row.regularPrice), 1e-9)
        assertEquals(null to null, row.saved(), "not ticked")

        assertEquals(2.5 to Store.JUMBO, tick(monday, key).saved())
        assertEquals(2.5 to Store.JUMBO, http.chicken().saved())

        // Monday's dinner eaten: the meals to come need half of it, and what was bought saved as much as it did.
        http.eat(0)
        val eaten = http.chicken()
        assertEquals(500.0, eaten.toComeGrams)
        assertEquals(2.5 to Store.JUMBO, eaten.saved())
        http.eat(3)
        assertEquals(2.5 to Store.JUMBO, http.chicken().saved())
    }

    @Test
    fun unTickingGivesTheSavingUpAndTickingAgainTakesThatOfTheMoment() = testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        tick(monday, key)
        http.eat(0)

        assertEquals(null to null, tick(monday, key, checked = false).saved())
        // Ticked again with Monday's dinner eaten: the half still to buy saves half as much.
        assertEquals(1.25 to Store.JUMBO, tick(monday, key).saved())
        // Ticking a ticked row again (an app sending it twice) keeps what its tick saved.
        assertEquals(1.25 to Store.JUMBO, tick(wednesday, key).saved())

        // The plan comes to need more, and the rest is bought: ticked again, it saves what the row saves now,
        // on the 1 kg the meals to come need of the 1.5 kg (€15) of the week.
        http.dine(5, 500.0)
        val again = shopping(monday).tickAgain(week, ShoppingRowKey(key)).items.single { it.key == key }
        assertEquals(1500.0, again.boughtGrams)
        assertEquals(2.5, assertNotNull(again.boughtSaving), 1e-9)
        assertEquals(Store.JUMBO, again.boughtOfferStore)
    }

    @Test
    fun anOfferThatEndsAfterTheTickTakesNothingBack() = testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        // Ticked once the offer had ended, the row saved nothing, and says so rather than nothing known.
        assertEquals(0.0 to null, tick(wednesday, key).saved())
        tick(wednesday, key, checked = false)

        assertEquals(2.5 to Store.JUMBO, tick(monday, key).saved())
        // Gone from the store's page altogether: the row has no offer left, and what it saved stays.
        jumbo.offers = listOf(Offer(Store.JUMBO, "Jumbo Sla", "50% korting", validFrom = week, validUntil = "2026-11-22"))
        http.post("/api/offers/refresh")
        val row = http.chicken()
        assertEquals(emptyList(), row.offers)
        assertEquals(2.5 to Store.JUMBO, row.saved())
    }

    @Test
    fun anOfferOfAStoreTheAccountDoesNotShopAtSavesNothingAtTheTick() = testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        http.put("/api/shops") { setBody(ShopsSetting(listOf(Store.AH))) }
        assertEquals(0.0 to null, tick(monday, key).saved())
    }

    @Test
    fun ticksOfAdviceAndRowsNotOnTheListKeepNoSaving() = testApplication {
        val http = start()
        val id = http.ready().id
        for (key in listOf("stock:i$id", "ahead:i$id", "t:leek")) shopping(monday).setChecked(week, ShoppingCheck(key, true))
        http.put("/api/plans/$week/shopping/dismissed/buy-ahead")
        val stored = db.shoppingQueries.selectChecks(userId(), week).executeAsList()
        assertEquals(4, stored.size)
        assertEquals(listOf(null to null), stored.map { it.saving to it.savingStore }.distinct())
    }

    @Test
    fun theReleasedAppReadsATickedRowWithItsSavingAsBefore() = testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        tick(monday, key)
        val old = releasedJson.decodeFromString<ReleasedShoppingList>(http.get("/api/plans/$week/shopping").bodyAsText())
        val row = old.items.single { it.key == key }
        assertEquals(true to 10.0, row.checked to row.regularPrice)
    }

    @Test
    fun aTickSentAfterItsOfferEndedKeepsTheSavingThePhoneShowed() = testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        // Without it the tick saves what the row saves when it arrives, on the day after the offer: nothing.
        assertEquals(0.0 to null, tick(wednesday, key).saved())
        tick(wednesday, key, checked = false)

        assertEquals(2.5 to Store.JUMBO, tick(wednesday, key, shown = mondaysTick).saved())
        assertEquals(2.5 to Store.JUMBO, http.chicken().saved())
    }

    @Test
    fun aTickOfTodayIsWorkedOutByTheServerWhateverThePhoneSays() = testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        // Sent on the day it was made: the offer is on the server's list still, so the phone's figure counts for nothing.
        assertEquals(2.5 to Store.JUMBO, tick(monday, key, shown = mondaysTick.copy(saving = 9.0)).saved())
        tick(monday, key, checked = false)
        assertEquals(0.0 to null, tick(wednesday, key, shown = mondaysTick.copy(tickedOn = wednesday)).saved())
    }

    @Test
    fun aSavingOutOfBoundsIsLeftOutAndTheTickStoredWithTheServersOwn() = testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        val outOfBounds = listOf(
            mondaysTick.copy(saving = 10.01),
            mondaysTick.copy(saving = -1.0),
            mondaysTick.copy(saving = Double.NaN),
            // A phone whose clock runs ahead, a tick older than the phone keeps, and no day or a bad one.
            mondaysTick.copy(tickedOn = "2026-11-19"),
            mondaysTick.copy(tickedOn = "2026-09-01"),
            mondaysTick.copy(tickedOn = null),
            mondaysTick.copy(tickedOn = "last Monday"),
        )
        for (shown in outOfBounds) {
            assertEquals(0.0 to null, tick(wednesday, key, shown = shown).saved(), shown.toString())
            tick(wednesday, key, checked = false)
        }
        // The edge of what is allowed: the row's whole price, and the oldest day.
        assertEquals(10.0 to Store.JUMBO, tick(wednesday, key, shown = mondaysTick.copy(saving = 10.0, tickedOn = "2026-09-23")).saved())
    }

    @Test
    fun theRouteTakesWhatThePhoneShowedAsJson() = testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        val answer = http.put("/api/plans/$week/shopping") { setBody("""{"key":"$key","checked":true,"shown":{"grams":800.0,"saving":0.5,"tickedOn":"2020-01-01"}}""") }
        assertEquals(HttpStatusCode.OK, answer.status)
        // The tick is too old to be believed for its saving, but its amount counts.
        assertEquals(800.0, answer.body<ShoppingList>().items.single { it.key == key }.boughtGrams)
    }

    @Test
    fun aSavingOfNothingKeepsNoStore()= testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        assertEquals(0.0 to null, tick(wednesday, key, shown = mondaysTick.copy(saving = 0.0)).saved())
    }

    @Test
    fun aRowWithoutARegularPriceHasNoSavingToBelieve() = testApplication {
        val http = start()
        http.ready()
        val id = http.post("/api/ingredients") { setBody(IngredientInput("Spelt")) }.body<Ingredient>().id
        val dinner = http.post("/api/recipes") { setBody(RecipeInput("Spelt", 1, "300 | g | spelt")) }.body<RecipeDetail>().summary
        db.planQueries.upsertMeal(userId(), week, 1, "DINNER", dinner.id, dinner.title, 1.0, 500.0, 30.0, 0, 0, MealStatus.PLANNED.name)
        val row = tick(wednesday, "i$id", shown = mondaysTick)
        assertEquals(null, row.regularPrice)
        assertEquals(0.0 to null, row.saved())
    }

    @Test
    fun aTickSentLateKeepsTheAmountThePhoneShowed() = testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        // The row says a kilo; the phone showed 800 g, before a meal was added on another phone.
        assertEquals(800.0, tick(monday, key, shown = ShoppingCheck.Shown(grams = 800.0)).boughtGrams)
        tick(monday, key, checked = false)
        // Without it, the amount of the moment, as before.
        assertEquals(1000.0, tick(monday, key).boughtGrams)
    }

    @Test
    fun anAmountThatTheRowCouldNotHaveHadIsLeftOut() = testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        for (grams in listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY, 2001.0, 20_000.0)) {
            assertEquals(1000.0, tick(monday, key, shown = ShoppingCheck.Shown(grams = grams)).boughtGrams, "$grams")
            tick(monday, key, checked = false)
        }
        // Pieces of a row that counts none.
        assertEquals(null, tick(monday, key, shown = ShoppingCheck.Shown(pieces = 3.0)).boughtPieces)
        tick(monday, key, checked = false)
        // The most a row may have said: twice what it says now.
        assertEquals(2000.0, tick(monday, key, shown = ShoppingCheck.Shown(grams = 2000.0)).boughtGrams)
    }

    @Test
    fun aRowCountedInPiecesKeepsThePiecesThePhoneShowed() = testApplication {
        val http = start()
        http.ready()
        val egg = http.post("/api/ingredients") { setBody(IngredientInput("Ei", pricePerKg = 4.0)) }.body<Ingredient>().id
        val dinner = http.post("/api/recipes") { setBody(RecipeInput("Eggs", 1, "4 | ei | large")) }.body<RecipeDetail>().summary
        db.planQueries.upsertMeal(userId(), week, 2, "DINNER", dinner.id, dinner.title, 1.0, 500.0, 30.0, 0, 0, MealStatus.PLANNED.name)
        val key = "i$egg"
        assertEquals(4.0, http.get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.key == key }.pieces)
        assertEquals(3.0, tick(monday, key, shown = ShoppingCheck.Shown(pieces = 3.0)).boughtPieces)
        tick(monday, key, checked = false)
        assertEquals(4.0, tick(monday, key, shown = ShoppingCheck.Shown(pieces = 9.0)).boughtPieces)
    }

    @Test
    fun aLateTickOfAnotherPhoneDoesNotReplaceATickThatIsThereAlready() = testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        // The other phone ticked at once, on Monday; this one made its tick offline on Tuesday, and it arrives after.
        assertEquals(1000.0 to 2.5, tick(monday, key).let { it.boughtGrams to it.boughtSaving })
        val late = tick(wednesday, key, shown = mondaysTick.copy(grams = 800.0, saving = 1.0, tickedOn = "2026-11-17"))
        assertEquals(1000.0 to 2.5, late.boughtGrams to late.boughtSaving)
    }

    @Test
    fun aLateTickOfOneAccountStoresNothingForAnother() = testApplication {
        val http = start()
        val key = "i${http.ready().id}"
        val other = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("other")
                contentType(ContentType.Application.Json)
            }
        }
        other.get("/api/plans/$week/shopping")
        val otherId = db.userQueries.selectBySubject("sub-other").executeAsOne().id
        // The other account has no such ingredient or meals: its tick remembers nothing, and nothing of this account's changes.
        shopping(wednesday, otherId).setChecked(week, ShoppingCheck(key, true, shown = mondaysTick))
        assertEquals(listOf(null to null), db.shoppingQueries.selectChecks(otherId, week).executeAsList().map { it.saving to it.grams })
        assertEquals(emptyList(), db.shoppingQueries.selectChecks(userId(), week).executeAsList())

        assertEquals(2.5 to Store.JUMBO, tick(wednesday, key, shown = mondaysTick).saved())
        assertEquals(1, db.shoppingQueries.selectChecks(otherId, week).executeAsList().size)
    }
}
