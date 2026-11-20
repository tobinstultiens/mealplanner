package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.DinnerToday
import dev.tobins.mealplanner.api.EndingOffer
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.NotificationFacts
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepPastLastDay
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.SpareMove
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeighInDue
import dev.tobins.mealplanner.server.db.Database
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
import kotlin.test.assertNull

/**
 * What `GET /api/notifications` says is due (issue 225): each condition of the table in the issue, on the
 * server's day, which a test sets through [UserScope]'s today.
 */
class NotificationsTest {
    private class Offers(override val store: Store, val offers: List<Offer>) : OfferSource {
        override suspend fun fetch() = offers
    }

    /** The week of the plan: Monday 16 to Sunday 22 November 2026. */
    private val week = "2026-11-16"
    private val nextWeek = "2026-11-23"

    /** Chicken at Jumbo on Monday and Tuesday only. */
    private val chickenOffer = Offer(Store.JUMBO, "Jumbo Kipfilet 1 kg", "25% korting", packSize = "1 kg", validFrom = week, validUntil = "2026-11-17")

    private lateinit var db: Database

    private fun ApplicationTestBuilder.start(vararg offers: Offer) {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        db = openDatabase(config.databaseUrl).second
        val sources = offers.groupBy { it.store }.map { (store, theirs) -> Offers(store, theirs) }
        // The plan week's Monday, so that the preps of the tests are made on days still to come whenever they run.
        application { module(config, db, sources, priceSource = null, generator = null, verifier = TestTokens, today = { LocalDate.parse(week) }) }
    }

    private fun ApplicationTestBuilder.clientFor(token: String): HttpClient = createClient {
        install(ContentNegotiation) { json() }
        defaultRequest {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
        }
    }

    private fun accountId(token: String) = db.userQueries.selectBySubject("sub-$token").executeAsOne().id

    /** What is due for the account of [token] when the server's today is [day]. */
    private fun facts(day: String, token: String = "t"): NotificationFacts {
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        val prices = PriceRepository(db, null, foods, offers)
        return UserScope(accountId(token), db, foods, offers, prices, null, createTempDirectory("pictures").toFile(), today = { LocalDate.parse(day) })
            .notifications.facts()
    }

    /** A profile, which marking meals and the weigh-in need, and the offers stored. */
    private suspend fun HttpClient.ready(): HttpClient = apply {
        put("/api/profile") { setBody(Profile(90.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        post("/api/offers/refresh")
    }

    /** Plans [title], a recipe of 500 g of chicken, in [slot] of [day] of the week starting [weekStart], for [token]'s account. */
    private suspend fun HttpClient.plan(token: String, day: Int, slot: String = "DINNER", title: String = "Chicken", weekStart: String = week): Long {
        val recipe = post("/api/recipes") { setBody(RecipeInput(title, 1, "500 | g | kipfilet")) }.body<RecipeDetail>().summary
        db.planQueries.upsertMeal(accountId(token), weekStart, day.toLong(), slot, recipe.id, recipe.title, 1.0, 500.0, 30.0, 0, 0, MealStatus.PLANNED.name)
        return recipe.id
    }

    private suspend fun HttpClient.mark(day: Int, slot: String, status: MealStatus) =
        assertEquals(HttpStatusCode.OK, put("/api/plans/$week/meals/$day/$slot/status") { setBody(status) }.status)

    // --- Offers ending

    @Test
    fun anOfferForARowStillToBuyIsDueOnItsLastDayAndTheDayBefore() = testApplication {
        start(chickenOffer)
        val http = clientFor("t").ready()
        val chicken = http.post("/api/ingredients") { setBody(IngredientInput("Kipfilet", pricePerKg = 10.0)) }.body<Ingredient>()
        http.plan("t", day = 3)

        val endsTuesday = listOf(EndingOffer("Kipfilet", "2026-11-17"))
        assertEquals(endsTuesday, facts("2026-11-16").offersEnding, "the day before: it ends tomorrow")
        assertEquals(endsTuesday, facts("2026-11-17").offersEnding, "the last day")
        assertEquals(emptyList(), facts("2026-11-18").offersEnding, "ended")
        assertEquals(emptyList(), facts("2026-11-15").offersEnding, "not started yet, and last week's list")

        http.put("/api/plans/$week/shopping") { setBody(ShoppingCheck("i${chicken.id}", true)) }
        assertEquals(emptyList(), facts("2026-11-17").offersEnding, "ticked off: bought")
    }

    @Test
    fun anOfferElsewhereOrForARowNoMealNeedsAnyMoreIsNotDue() = testApplication {
        start(chickenOffer)
        val http = clientFor("t").ready()
        http.post("/api/ingredients") { setBody(IngredientInput("Kipfilet", pricePerKg = 10.0)) }
        http.plan("t", day = 0)

        http.put("/api/shops") { setBody(ShopsSetting(listOf(Store.AH, Store.LIDL))) }
        assertEquals(emptyList(), facts("2026-11-17").offersEnding, "not a store the account shops at")
        http.put("/api/shops") { setBody(ShopsSetting(null)) }
        assertEquals(listOf("Kipfilet"), facts("2026-11-17").offersEnding.map { it.name })

        // Monday's dinner eaten without the row being ticked: nothing is left to buy for it.
        http.mark(0, "DINNER", MealStatus.EATEN)
        assertEquals(emptyList(), facts("2026-11-17").offersEnding)
    }

    @Test
    fun aRowCoveredByWhatIsAtHomeIsNotDue() = testApplication {
        start(chickenOffer)
        val http = clientFor("t").ready()
        val chicken = http.post("/api/ingredients") { setBody(IngredientInput("Kipfilet", pricePerKg = 10.0)) }.body<Ingredient>()
        http.plan("t", day = 3)
        suspend fun stockUp(grams: Double) =
            assertEquals(HttpStatusCode.OK, http.put("/api/plans/$week/shopping") { setBody(ShoppingCheck("stock:i${chicken.id}", true, grams)) }.status)

        // 200 g at home of the 500 g Thursday's dinner needs: still to buy.
        stockUp(200.0)
        val row = http.get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.name == "Kipfilet" }
        assertEquals(true to 200.0, row.onHand to row.onHandGrams)
        assertEquals(listOf("Kipfilet"), facts("2026-11-17").offersEnding.map { it.name })

        // A kilo at home: nothing to buy, so the offer ending is no reason to hurry.
        http.put("/api/plans/$week/shopping") { setBody(ShoppingCheck("stock:i${chicken.id}", false)) }
        stockUp(1000.0)
        assertEquals(1000.0, http.get("/api/plans/$week/shopping").body<ShoppingList>().items.single { it.name == "Kipfilet" }.onHandGrams)
        assertEquals(emptyList(), facts("2026-11-17").offersEnding)
    }

    @Test
    fun stockingUpCountsUntilItIsDismissed() = testApplication {
        start(Offer(Store.JUMBO, "Jumbo Rijst 1 kg", "40% korting", packSize = "1 kg", validFrom = week, validUntil = "2026-11-18"))
        val http = clientFor("t").ready()
        val rice = http.post("/api/ingredients") { setBody(IngredientInput("Rijst", pricePerKg = 3.0)) }.body<Ingredient>()
        http.put("/api/ingredients/${rice.id}/section") { setBody(SectionChoice("WORLD")) }
        http.put("/api/pantry/staples/${rice.id}")
        assertEquals(listOf("stock:i${rice.id}"), http.get("/api/plans/$week/shopping").body<ShoppingList>().stockUp.map { it.key })

        assertEquals(listOf(EndingOffer("Rijst", "2026-11-18")), facts("2026-11-17").offersEnding)
        http.put("/api/plans/$week/shopping/dismissed/${ShoppingAdviceGroup.STOCK_UP.path}")
        assertEquals(emptyList(), facts("2026-11-17").offersEnding)
    }

    @Test
    fun theRowsEndingTodayComeFirst() = testApplication {
        start(chickenOffer, Offer(Store.AH, "AH Broccoli", "1+1 gratis", validFrom = week, validUntil = "2026-11-18"))
        val http = clientFor("t").ready()
        http.post("/api/ingredients") { setBody(IngredientInput("Broccoli", pricePerKg = 3.0)) }
        http.post("/api/ingredients") { setBody(IngredientInput("Kipfilet", pricePerKg = 10.0)) }
        val recipe = http.post("/api/recipes") { setBody(RecipeInput("Chicken and broccoli", 1, "300 | g | broccoli\n500 | g | kipfilet")) }.body<RecipeDetail>().summary
        db.planQueries.upsertMeal(accountId("t"), week, 4, "DINNER", recipe.id, recipe.title, 1.0, 500.0, 30.0, 0, 0, MealStatus.PLANNED.name)

        assertEquals(listOf(EndingOffer("Kipfilet", "2026-11-17"), EndingOffer("Broccoli", "2026-11-18")), facts("2026-11-17").offersEnding)
    }

    // --- Plan next week

    @Test
    fun nextWeekIsDueFromSaturdayWhileItHasNoMeals() = testApplication {
        start(chickenOffer)
        val alice = clientFor("t").ready()
        val bob = clientFor("bob").ready()
        bob.get("/api/me")

        assertNull(facts("2026-11-20").planNextWeek, "Friday")
        assertEquals(nextWeek, facts("2026-11-21").planNextWeek, "Saturday")
        assertEquals(nextWeek, facts("2026-11-22").planNextWeek, "Sunday")
        assertNull(facts("2026-11-23").planNextWeek, "Monday: that week is this week now, and its Saturday is to come")

        // A meal next week: planned already. Only the account's own plan counts.
        alice.plan("t", day = 0, weekStart = nextWeek)
        assertNull(facts("2026-11-21").planNextWeek)
        assertEquals(nextWeek, facts("2026-11-21", "bob").planNextWeek)
    }

    @Test
    fun nextWeekIsDueWithoutOffersAndForEveryStore() = testApplication {
        // No offer of next week anywhere (AH and Lidl list theirs only once the week has started), and an account at AH alone.
        start(Offer(Store.AH, "AH Broccoli", "1+1 gratis", validFrom = week, validUntil = "2026-11-22"))
        val bob = clientFor("bob").ready()
        bob.put("/api/shops") { setBody(ShopsSetting(listOf(Store.AH))) }
        assertEquals(nextWeek, facts("2026-11-21", "bob").planNextWeek)

        clientFor("t").ready().put("/api/shops") { setBody(ShopsSetting(listOf(Store.LIDL))) }
        assertEquals(nextWeek, facts("2026-11-21").planNextWeek)
    }

    // --- Weigh-in

    @Test
    fun aWeighInIsDueOnceTheNewestIsAWeekOld() = testApplication {
        start()
        val http = clientFor("t").ready()
        assertNull(facts("2026-11-17").weighIn, "never weighed")
        http.put("/api/weights/2026-11-03") { setBody(91.0) }
        http.put("/api/weights/2026-11-10") { setBody(90.0) }

        assertNull(facts("2026-11-16").weighIn, "six days")
        assertEquals(WeighInDue("2026-11-10", 7), facts("2026-11-17").weighIn)
        assertEquals(WeighInDue("2026-11-10", 9), facts("2026-11-19").weighIn)
        http.put("/api/weights/2026-11-18") { setBody(89.5) }
        assertNull(facts("2026-11-19").weighIn, "entered")

        // Without a profile there is nothing the weight is for.
        val bob = clientFor("bob")
        bob.put("/api/weights/2026-11-01") { setBody(80.0) }
        assertNull(facts("2026-11-19", "bob").weighIn)
    }

    // --- Today's dinner and the meals to mark

    @Test
    fun todaysDinnerIsDueUntilItIsEatenOrSkipped() = testApplication {
        start()
        val http = clientFor("t").ready()
        http.plan("t", day = 3, slot = "LUNCH", title = "Chicken salad")
        assertNull(facts("2026-11-19").dinner, "a lunch only")
        val id = http.plan("t", day = 3)

        assertEquals(DinnerToday(id, "Chicken", 1.0, 1.0, week, 3), facts("2026-11-19").dinner)
        assertNull(facts("2026-11-18").dinner, "another day")
        http.mark(3, "DINNER", MealStatus.SKIPPED)
        assertNull(facts("2026-11-19").dinner)
        http.mark(3, "DINNER", MealStatus.PLANNED)
        http.mark(3, "DINNER", MealStatus.EATEN)
        assertNull(facts("2026-11-19").dinner)
    }

    @Test
    fun theDinnerThatMakesAPrepIsDueWithTheBatchAndAPreppedLaterDinnerIsNot() = testApplication {
        start()
        val http = clientFor("t").ready()
        val salad = http.post("/api/recipes") { setBody(RecipeInput("Chicken salad", 4, "1200 | g | kipfilet")) }.body<RecipeDetail>().summary
        http.put("/api/recipes/${salad.id}/keeps") { setBody(RecipeKeeps(days = 2)) }
        val meals = listOf(PrepSlot("2026-11-17", MealSlot.DINNER), PrepSlot("2026-11-18", MealSlot.DINNER))
        val plan = http.post("/api/plans/$week/preps") { setBody(PrepInput(salad.id, "2026-11-17", meals = meals)) }.body<WeekPlan>()
        val portions = plan.days[1].meals.single().portions

        // The batch, said to be a prep, so the app speaks of its later meals rather than of tomorrow's lunch (issue 263).
        assertEquals(DinnerToday(salad.id, "Chicken salad", portions, 2 * portions, week, 1, prep = true), facts("2026-11-17").dinner, "Tuesday: cook the batch")
        assertNull(facts("2026-11-18").dinner, "Wednesday's was made on Tuesday: nothing to cook")
        assertEquals(1, facts("2026-11-18").mealsToMark, "but it is still to mark")
    }

    /** Issue 263: a dinner whose main was made on an earlier day, with a side whose batch is made tonight, is due with the side. */
    @Test
    fun aDinnerWithNothingToCookButASideMadeTonightIsDueWithTheSide() = testApplication {
        start()
        val http = clientFor("t").ready()
        suspend fun keeping(title: String, lines: String) = http.post("/api/recipes") { setBody(RecipeInput(title, 4, lines)) }.body<RecipeDetail>().summary
            .also { http.put("/api/recipes/${it.id}/keeps") { setBody(RecipeKeeps(days = 2)) } }
        val salad = keeping("Chicken salad", "1200 | g | kipfilet")
        val lentils = keeping("Lentils", "400 | g | kipfilet")
        http.prep(salad.id, "2026-11-17", "2026-11-17" to MealSlot.DINNER, "2026-11-18" to MealSlot.DINNER)
        http.plan("t", day = 3, slot = "LUNCH")
        // Half a serving of lentils next to Wednesday's dinner, which is made then, and Thursday's lunch.
        http.prep(lentils.id, "2026-11-18", "2026-11-18" to MealSlot.DINNER, "2026-11-19" to MealSlot.LUNCH, side = 0.5)

        assertEquals(DinnerToday(lentils.id, "Lentils", 0.5, 1.0, week, 2, prep = true, side = true), facts("2026-11-18").dinner)
        assertEquals(true, facts("2026-11-17").dinner?.let { it.recipeId == salad.id && !it.side }, "Tuesday cooks the salad itself")
        http.mark(2, "DINNER", MealStatus.SKIPPED)
        assertNull(facts("2026-11-18").dinner, "a dinner skipped is not cooked, nor is its side")
    }

    @Test
    fun aWeekWithOnlyThePrepOfTheWeekBeforeIsStillToPlan() = testApplication {
        start()
        val http = clientFor("t").ready()
        val salad = http.post("/api/recipes") { setBody(RecipeInput("Chicken salad", 4, "1200 | g | kipfilet")) }.body<RecipeDetail>().summary
        http.put("/api/recipes/${salad.id}/keeps") { setBody(RecipeKeeps(days = 2)) }
        val meals = listOf(PrepSlot("2026-11-22", MealSlot.DINNER), PrepSlot("2026-11-23", MealSlot.LUNCH))
        assertEquals(HttpStatusCode.OK, http.post("/api/plans/$week/preps") { setBody(PrepInput(salad.id, "2026-11-22", meals = meals)) }.status)

        assertEquals(nextWeek, facts("2026-11-21").planNextWeek, "Monday's lunch from Sunday's batch does not plan the week")
        http.plan("t", day = 2, weekStart = nextWeek)
        assertNull(facts("2026-11-21").planNextWeek)
    }

    @Test
    fun theMealsToMarkAreThoseOfTodayStillPlanned() = testApplication {
        start()
        val http = clientFor("t").ready()
        http.plan("t", day = 3, slot = "BREAKFAST", title = "Chicken toast")
        http.plan("t", day = 3, slot = "LUNCH", title = "Chicken salad")
        http.plan("t", day = 3)
        http.plan("t", day = 4)

        assertEquals(3, facts("2026-11-19").mealsToMark)
        http.mark(3, "BREAKFAST", MealStatus.EATEN)
        http.mark(3, "LUNCH", MealStatus.SKIPPED)
        assertEquals(1, facts("2026-11-19").mealsToMark)
        http.mark(3, "DINNER", MealStatus.EATEN)
        assertEquals(0, facts("2026-11-19").mealsToMark)
        assertEquals(1, facts("2026-11-20").mealsToMark)
    }

    // --- Prepped food to throw away (issue 264)

    /** A chicken salad of four servings that keeps [days] days in a box and [vacuumDays] vacuum sealed. */
    private suspend fun HttpClient.salad(days: Int = 3, vacuumDays: Int? = null): RecipeSummary {
        val salad = post("/api/recipes") { setBody(RecipeInput("Chicken salad", 4, "1200 | g | kipfilet")) }.body<RecipeDetail>().summary
        assertEquals(HttpStatusCode.OK, put("/api/recipes/${salad.id}/keeps") { setBody(RecipeKeeps(days, vacuumDays)) }.status)
        return salad
    }

    /** Preps [recipeId] made on [madeOn], eaten in [meals] (a date of this week and a slot each), as a meal or a [side]; the batch's id. */
    private suspend fun HttpClient.prep(recipeId: Long, madeOn: String, vararg meals: Pair<String, MealSlot>, vacuum: Boolean = false, side: Double? = null): Long {
        val input = PrepInput(recipeId, madeOn, vacuum, meals.map { PrepSlot(it.first, it.second) }, side)
        val response = post("/api/plans/$week/preps") { setBody(input) }
        assertEquals(HttpStatusCode.OK, response.status)
        val day = LocalDate.parse(madeOn).dayOfMonth - 16
        val meal = response.body<WeekPlan>().days[day].meals.single { it.slot == meals.first().second }
        return checkNotNull(if (side != null) meal.side?.prep?.id else meal.prep?.id)
    }

    @Test
    fun aBatchIsDueTheDayAfterItsLastDayWithWhatWasLeftAndWhatWasNotMarked() = testApplication {
        start()
        val http = clientFor("t").ready()
        val salad = http.salad(days = 3)
        // Made Tuesday, keeps until Friday: Tuesday's dinner eaten, Wednesday's lunch skipped, Thursday's dinner never marked, Friday's lunch eaten.
        val id = http.prep(salad.id, "2026-11-17", "2026-11-17" to MealSlot.DINNER, "2026-11-18" to MealSlot.LUNCH, "2026-11-19" to MealSlot.DINNER, "2026-11-20" to MealSlot.LUNCH)
        http.mark(1, "DINNER", MealStatus.EATEN)
        http.mark(2, "LUNCH", MealStatus.SKIPPED)
        http.mark(4, "LUNCH", MealStatus.EATEN)

        val due = PrepPastLastDay(id, salad.id, "Chicken salad", "2026-11-17", "2026-11-20", left = 1, unmarked = 1, firstUnmarked = "2026-11-19")
        assertEquals(listOf(due), facts("2026-11-21").prepsPastLastDay, "Saturday, the morning after its last day")
        assertEquals(emptyList(), facts("2026-11-20").prepsPastLastDay, "on its last day it may still be eaten")
        assertEquals(emptyList(), facts("2026-11-22").prepsPastLastDay, "a day later it was told already")

        // Thursday's dinner marked eaten after all: only the skipped share is left.
        http.mark(3, "DINNER", MealStatus.EATEN)
        assertEquals(listOf(due.copy(unmarked = 0, firstUnmarked = null)), facts("2026-11-21").prepsPastLastDay)
        // Marked skipped instead: two shares in the box.
        http.mark(3, "DINNER", MealStatus.PLANNED)
        http.mark(3, "DINNER", MealStatus.SKIPPED)
        assertEquals(listOf(due.copy(left = 2, unmarked = 0, firstUnmarked = null)), facts("2026-11-21").prepsPastLastDay)
    }

    @Test
    fun aBatchEatenCompletelyOrNeverMadeLeftNothingToThrowAway() = testApplication {
        start()
        val http = clientFor("t").ready()
        val salad = http.salad(days = 3)
        // Made Tuesday, keeps until Friday; Wednesday's lunch is skipped and its share eaten at Thursday's lunch instead.
        val id = http.prep(salad.id, "2026-11-17", "2026-11-17" to MealSlot.DINNER, "2026-11-18" to MealSlot.LUNCH, "2026-11-19" to MealSlot.DINNER)
        assertEquals(emptyList(), facts("2026-11-21").prepsPastLastDay, "never made: nothing of it is in a box")
        http.mark(2, "LUNCH", MealStatus.SKIPPED)
        assertEquals(emptyList(), facts("2026-11-21").prepsPastLastDay, "skipped but never made: still nothing")

        http.mark(1, "DINNER", MealStatus.EATEN)
        assertEquals(1, facts("2026-11-21").prepsPastLastDay.single().left)
        val move = SpareMove(PrepSlot("2026-11-18", MealSlot.LUNCH), PrepSlot("2026-11-19", MealSlot.LUNCH))
        assertEquals(HttpStatusCode.OK, http.post("/api/plans/$week/preps/$id/spare") { setBody(move) }.status)
        assertEquals(Triple(0, 2, "2026-11-19"), facts("2026-11-21").prepsPastLastDay.single().let { Triple(it.left, it.unmarked, it.firstUnmarked) }, "the moved share is not left")
        http.mark(3, "LUNCH", MealStatus.EATEN)
        http.mark(3, "DINNER", MealStatus.EATEN)
        assertEquals(emptyList(), facts("2026-11-21").prepsPastLastDay, "every share eaten")
    }

    @Test
    fun vacuumSealedAndBoxedBatchesGoByTheirOwnLastDay() = testApplication {
        start()
        val http = clientFor("t").ready()
        val salad = http.salad(days = 2, vacuumDays = 4)
        // Both made Tuesday, each with a meal skipped: the boxed one keeps until Thursday, the vacuum sealed one until Saturday.
        val boxed = http.prep(salad.id, "2026-11-17", "2026-11-17" to MealSlot.DINNER, "2026-11-18" to MealSlot.DINNER)
        val sealed = http.prep(salad.id, "2026-11-17", "2026-11-17" to MealSlot.LUNCH, "2026-11-21" to MealSlot.LUNCH, vacuum = true)
        for (slot in listOf("LUNCH", "DINNER")) http.mark(1, slot, MealStatus.EATEN)
        http.mark(2, "DINNER", MealStatus.SKIPPED)
        http.mark(5, "LUNCH", MealStatus.SKIPPED)

        assertEquals(listOf(boxed to "2026-11-19"), facts("2026-11-20").prepsPastLastDay.map { it.prepId to it.lastDay })
        assertEquals(emptyList(), facts("2026-11-21").prepsPastLastDay, "vacuum sealed it keeps until Saturday, its own last day")
        assertEquals(listOf(sealed to "2026-11-21"), facts("2026-11-22").prepsPastLastDay.map { it.prepId to it.lastDay })
    }

    @Test
    fun aSkippedSideLeftInTheBoxIsDueAsASide() = testApplication {
        start()
        val http = clientFor("t").ready()
        // Bowls on Tuesday's and Thursday's dinner and Wednesday's lunch and dinner, a salad of half a serving next to three of them.
        for ((day, slot) in listOf(1 to "DINNER", 2 to "LUNCH", 2 to "DINNER", 3 to "DINNER")) http.plan("t", day, slot, title = "Bowl $day $slot")
        val salad = http.salad(days = 3)
        val id = http.prep(salad.id, "2026-11-17", "2026-11-17" to MealSlot.DINNER, "2026-11-18" to MealSlot.LUNCH, "2026-11-19" to MealSlot.DINNER, side = 0.5)
        // Tuesday's dinner, which makes the batch, is skipped and its side eaten on Wednesday evening; Wednesday's lunch is skipped too.
        http.mark(1, "DINNER", MealStatus.SKIPPED)
        val move = SpareMove(PrepSlot("2026-11-17", MealSlot.DINNER), PrepSlot("2026-11-18", MealSlot.DINNER))
        assertEquals(HttpStatusCode.OK, http.post("/api/plans/$week/preps/$id/spare") { setBody(move) }.status)
        http.mark(2, "DINNER", MealStatus.EATEN)
        http.mark(2, "LUNCH", MealStatus.SKIPPED)

        val due = PrepPastLastDay(id, salad.id, "Chicken salad", "2026-11-17", "2026-11-20", side = true, left = 1, unmarked = 1, firstUnmarked = "2026-11-19", leftSides = 1, unmarkedSides = 1)
        assertEquals(listOf(due), facts("2026-11-21").prepsPastLastDay, "Wednesday lunch's side in the box, Thursday's not marked; Tuesday's was eaten elsewhere")
    }

    @Test
    fun aBatchOfMealsAndSidesIsDueWithItsPortionsAndSidesCountedApart() = testApplication {
        start()
        val http = clientFor("t").ready()
        // Issue 269: the salad is Tuesday's dinner and Wednesday's dinner, and a side next to Wednesday's lunch and Thursday's dinner bowl.
        for ((day, slot) in listOf(2 to "LUNCH", 3 to "DINNER")) http.plan("t", day, slot, title = "Bowl $day $slot")
        val salad = http.salad(days = 3)
        val shares = listOf(PrepSlot("2026-11-17", MealSlot.DINNER), PrepSlot("2026-11-18", MealSlot.LUNCH, side = 0.5), PrepSlot("2026-11-18", MealSlot.DINNER), PrepSlot("2026-11-19", MealSlot.DINNER, side = 0.25))
        val plan = http.post("/api/plans/$week/preps") { setBody(PrepInput(salad.id, "2026-11-17", meals = shares)) }.body<WeekPlan>()
        val id = plan.days[1].meals.single { it.slot == MealSlot.DINNER }.prep!!.id
        // Made on Tuesday; Wednesday's lunch with its side and Wednesday's dinner skipped; Thursday's dinner never marked.
        http.mark(1, "DINNER", MealStatus.EATEN)
        http.mark(2, "LUNCH", MealStatus.SKIPPED)
        http.mark(2, "DINNER", MealStatus.SKIPPED)

        val due = PrepPastLastDay(
            id, salad.id, "Chicken salad", "2026-11-17", "2026-11-20",
            side = false, left = 2, unmarked = 1, firstUnmarked = "2026-11-19", leftSides = 1, unmarkedSides = 1,
        )
        assertEquals(listOf(due), facts("2026-11-21").prepsPastLastDay, "a portion and a side in the box, and a side not marked")
    }

    @Test
    fun anotherAccountsBatchIsNotDue() = testApplication {
        start()
        val http = clientFor("t").ready()
        val salad = http.salad(days = 2)
        http.prep(salad.id, "2026-11-17", "2026-11-17" to MealSlot.DINNER, "2026-11-18" to MealSlot.DINNER)
        http.mark(1, "DINNER", MealStatus.EATEN)
        http.mark(2, "DINNER", MealStatus.SKIPPED)
        clientFor("other").ready()

        assertEquals(1, facts("2026-11-20").prepsPastLastDay.size)
        assertEquals(emptyList(), facts("2026-11-20", token = "other").prepsPastLastDay)
    }

    @Test
    fun anAccountWithNothingDueHasEmptyFacts() = testApplication {
        start(chickenOffer)
        clientFor("t").ready()
        assertEquals(NotificationFacts("2026-11-17"), facts("2026-11-17"))
    }
}
