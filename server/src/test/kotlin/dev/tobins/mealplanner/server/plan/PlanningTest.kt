package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.DinnerMove
import dev.tobins.mealplanner.api.DinnerSuggestions
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.OfferExclusionRequest
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RatingInput
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RotationInput
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.SuggestionGround
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.planner.OpenPacks
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.NotFoundException
import dev.tobins.mealplanner.server.ServerConfig
import dev.tobins.mealplanner.server.TestTokens
import dev.tobins.mealplanner.server.UserScope
import dev.tobins.mealplanner.server.accounts.Identity
import dev.tobins.mealplanner.server.accounts.UserRepository
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.module
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
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
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The planning screen (issue 255): the suggestions for the week's dinners and why each is there, the dinners chosen and
 * hidden, "Done" (or "Fill the rest"), which plans the week with them, and moving a dinner to another day on the Plan.
 *
 * The recipes are four-serving lunches and dinners of beef, chicken, cream and lentils, priced at AH; chicken is on
 * offer at AH (30% off) and beef at Jumbo, where the account does not shop. Cream is fresh, in a 250 g tub.
 */
class PlanningTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|10|Rundvlees rauw|Beef raw||per 100g|150|20|4|2|0|0|0|50",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|1634|Kipfilet rauw|Chicken fillet raw||per 100g|110|23|2|1|0|0|0|60",
        "NEVO-Online 2025 9.0|Zuivel|Dairy|9301|Room|Cream||per 100g|330|3|35|20|3|3|0|40",
        "NEVO-Online 2025 9.0|Peulvruchten|Legumes|2000|Linzen gekookt|Lentils boiled||per 100g|120|9|0|0|17|1|7|2",
        "NEVO-Online 2025 9.0|Groente|Vegetables|2346|Sla rauw|Lettuce raw||per 100g|15|1|0|0|2|1|2|5",
    ).joinToString("\r\n")

    private val week = "2026-11-16"
    private val nextWeek = "2026-11-23"
    private val start: LocalDate = LocalDate.parse(week)
    private val db = openDatabase("jdbc:sqlite::memory:").second
    private val foods = FoodRepository(db).also { f -> f.importNevo(createTempFile().toFile().apply { writeText(nevo) }) }
    private val offers = OfferRepository(db, emptyList())
    private val prices = PriceRepository(db, null, foods, offers)

    /** The server's today: the Monday of [week] unless a test moves it. */
    private var today: LocalDate = start

    /** The repositories of the account [subject], as a request gets them: made anew each time, as after a restart. */
    private fun scope(subject: String) =
        UserScope(UserRepository(db).resolve(Identity(subject, subject)).id, db, foods, offers, prices, null, createTempDirectory("pictures").toFile(), today = { today })

    private val alice = scope("alice")

    private val beef = alice.ingredients.create(IngredientInput("Beef", nevoCode = 10))
    private val chicken = alice.ingredients.create(IngredientInput("Chicken", nevoCode = 1634))
    private val cream = alice.ingredients.create(IngredientInput("Cream", nevoCode = 9301)).also { alice.ingredients.setKeeps(it.id, false) }
    private val lentils = alice.ingredients.create(IngredientInput("Lentils", nevoCode = 2000))
    private val lettuce = alice.ingredients.create(IngredientInput("Lettuce", nevoCode = 2346))

    init {
        // Only the cream is fresh: the packs the tests look at are its tubs.
        listOf(beef, chicken, lentils, lettuce).forEach { alice.ingredients.setKeeps(it.id, true) }
        alice.profiles.save(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000))
        alice.shops.save(ShopsSetting(listOf(Store.AH)))
        fun price(name: String, pack: String, euros: Double, perKg: Double) =
            db.priceQueries.upsertPrice(name, "AH", "AH $name", pack, euros, perKg, "2026-11-15T00:00:00Z", null, null, null)
        price("Beef", "500 g", 5.0, 10.0)
        price("Chicken", "1 kg", 10.0, 10.0)
        price("Cream", "250 g", 2.5, 10.0)
        price("Lentils", "500 g", 2.0, 4.0)
        price("Lettuce", "200 g", 1.0, 5.0)
        db.planQueries.insertOffer("AH", "AH Chicken 1 kg", "30% korting", 7.0, 10.0, "1 kg", null, week, "2026-11-22", null, null)
        db.planQueries.insertOffer("JUMBO", "Jumbo Beef 1 kg", "50% korting", 5.0, 10.0, "1 kg", null, week, "2026-11-22", null, null)
    }

    /** The meals of the history [recipe] writes so far, each in a slot of its own. */
    private var pastMeals = 0

    /**
     * A four-serving lunch or dinner of mine of [lines], in the rotation unless said otherwise; its id. Unless it is to be
     * one never [tried], it was eaten two weeks before [week]: neither last week's, nor a favourite again.
     */
    private fun recipe(title: String, lines: String, rotation: Boolean = true, tried: Boolean = true, scope: UserScope = alice): Long {
        val id = scope.recipes.create(RecipeInput(title, 4, lines, "Cook.", mealSlots = listOf(MealSlot.LUNCH, MealSlot.DINNER))).summary.id
        if (!rotation) scope.recipes.setRotation(id, RotationInput(false))
        if (tried) {
            val (day, slot) = pastMeals % 7 to MealSlot.entries[pastMeals++ / 7]
            db.planQueries.upsertMeal(scope.accountId, start.minusWeeks(2).toString(), day.toLong(), slot.name, id, title, 1.0, 600.0, 40.0, 0, 0, MealStatus.EATEN.name)
        }
        return id
    }

    /** Beef of about 600 to 750 kcal a serving, neither on offer nor rated: a recipe of no ground. */
    private fun beefDish(i: Int) = recipe("Beef $i", "${1600 + i * 40} | g | Beef")

    /** Chicken on offer at AH, with lentils. */
    private fun chickenDish(i: Int) = recipe("Chicken $i", "${1800 + i * 40} | g | Chicken\n400 | g | Lentils")

    /** Beef with a tub's worth of cream between two dinners: 100 g a serving. */
    private fun creamy(i: Int) = recipe("Creamy $i", "${1400 + i * 40} | g | Beef\n400 | g | Cream")

    private fun suggest(scope: UserScope = alice, seen: Set<Long> = emptySet(), count: Int = 6, query: String? = null, weekStart: String = week) =
        scope.planning.suggestions(weekStart, seen, count, query).suggestions

    private fun WeekPlan.meals(): List<PlannedMeal> = days.flatMap { it.meals }

    private fun assertClose(expected: Double?, actual: Double?, what: String) =
        assertTrue(expected != null && actual != null && abs(expected - actual) < 1e-6, "$what: expected $expected, was $actual")

    /** "Not this ingredient" (issue 316): an offer the account excluded saves nothing on the plan and suggests no dinner. */
    @Test
    fun anOfferTheAccountSaidIsNotItsIngredientIsNoGroundToSuggestADinnerAndSavesNothingOnThePlan() {
        (0..7).forEach(::chickenDish)
        (0..3).forEach(::beefDish)
        val excluded = OfferExclusionRequest(Store.AH, "AH Chicken 1 kg", chicken.id)
        assertTrue(suggest().any { SuggestionGround.ON_OFFER in it.grounds })
        val plan = alice.plans.generate(week, seed = 1)
        assertTrue(plan.cost!! < plan.costWithoutOffers!!, "chicken's offer saves on the plan")

        alice.offerExclusions.exclude(excluded)

        val suggestions = suggest()
        assertTrue(suggestions.none { SuggestionGround.ON_OFFER in it.grounds || it.offers.isNotEmpty() }, "$suggestions")
        assertTrue(suggestions.all { (it.savingPerServing ?: 0.0) < 1e-9 }, "$suggestions")
        val costed = alice.plans.get(week)
        assertClose(costed.costWithoutOffers, costed.cost, "the plan's cost without the offer")
        // Taken back, it counts again.
        alice.offerExclusions.takeBack(excluded)
        assertTrue(suggest().any { SuggestionGround.ON_OFFER in it.grounds })
        assertTrue(alice.plans.get(week).let { it.cost!! < it.costWithoutOffers!! })
    }

    @Test
    fun fillTheRestWithNothingChosenIsGenerateForTheSameSeed() {
        val dinners = (0..5).map(::beefDish) + (0..3).map(::chickenDish) + (0..1).map(::creamy)
        // Meals that stay: Wednesday's dinner chosen by hand, Tuesday's eaten.
        alice.plans.choose(week, 2, MealSlot.DINNER, dinners[3])
        alice.plans.generate(week, seed = 1)
        alice.plans.setStatus(week, 1, MealSlot.DINNER, MealStatus.EATEN)

        val generated = alice.plans.generate(week, seed = 42)
        val done = alice.planning.done(week, seed = 42)

        assertEquals(generated, done)
        assertTrue((43L..60L).any { alice.planning.done(week, seed = it).meals() != generated.meals() }, "another seed plans another week: the comparison means something")
        assertEquals(generated, alice.plans.generate(week, seed = 42))
    }

    @Test
    fun aboutSixSuggestionsEachWithAGroundAPriceAndForAnOfferWhatItSavesAtTheAccountsStoresOnly() {
        (0..7).forEach(::chickenDish)
        (0..3).forEach(::beefDish)
        val favourite = beefDish(9).also { alice.recipes.setRating(it, 5) }

        val suggestions = suggest()

        assertEquals(6, suggestions.size)
        assertTrue(suggestions.all { it.grounds.isNotEmpty() && it.costPerServing != null }, "$suggestions")
        val onOffer = suggestions.filter { SuggestionGround.ON_OFFER in it.grounds }
        assertTrue(onOffer.isNotEmpty())
        for (suggestion in onOffer) {
            val offer = suggestion.offers.single()
            assertEquals(Triple("Chicken", Store.AH, "30% korting"), Triple(offer.ingredient, offer.store, offer.deal))
            assertTrue(offer.savingPerServing!! > 1.0 && abs(offer.savingPerServing!! - suggestion.savingPerServing!!) < 1e-9, "$suggestion")
        }
        // Beef is on offer only at Jumbo, where the account does not shop: no beef dish is suggested for it.
        assertTrue(suggestions.filter { it.title.startsWith("Beef") }.all { it.grounds == listOf(SuggestionGround.FAVOURITE) && it.offers.isEmpty() })
        assertTrue(suggestions.any { it.recipeId == favourite && it.rating == 5 })

        alice.shops.save(ShopsSetting(listOf(Store.AH, Store.JUMBO)))
        assertTrue(suggest(count = 20).any { s -> s.title.startsWith("Beef") && s.offers.any { it.store == Store.JUMBO } }, "with Jumbo, its offer counts")
    }

    @Test
    fun withEnoughRecipesOnOfferToFillTheScreenItStillHoldsRecipesOfTheOtherGrounds() {
        val onOffer = (0..9).map(::chickenDish).toSet()
        val favourites = (0..1).map { beefDish(it).also { id -> alice.recipes.setRating(id, 5) } }
        val withLettuce = recipe("Beef and lettuce", "1600 | g | Beef\n400 | g | Lettuce")
        alice.stock.setOnHand(lettuce.id, true)

        val first = suggest()

        assertEquals(6, first.size)
        assertTrue(first.count { it.recipeId in onOffer } in 1..5, "offers, not only offers: $first")
        assertTrue(first.any { it.recipeId in favourites && SuggestionGround.FAVOURITE in it.grounds })
        val there = first.single { it.recipeId == withLettuce }
        assertEquals(listOf(SuggestionGround.USES_WHAT_IS_THERE) to listOf("Lettuce"), there.grounds to there.onHand)
    }

    @Test
    fun aRecipeNeverPlannedIsSuggestedAsNotTriedYetAndOffersDoNotCrowdItOut() {
        (0..9).forEach(::chickenDish)
        val (rated, unrated) = listOf("New stew", "New soup").map { recipe(it, "1600 | g | Beef", tried = false) }
        alice.recipes.setRating(rated, 4)
        val skippedOnly = recipe("Skipped stew", "1600 | g | Beef", tried = false)
        db.planQueries.upsertMeal(alice.accountId, start.minusWeeks(1).toString(), 0, MealSlot.DINNER.name, skippedOnly, "Skipped stew", 1.0, 600.0, 40.0, 0, 0, MealStatus.SKIPPED.name)

        val first = suggest()

        val untried = first.filter { SuggestionGround.NOT_TRIED_YET in it.grounds }
        assertEquals(listOf(rated, unrated, skippedOnly).toSet(), untried.map { it.recipeId }.toSet(), "only skipped is never eaten: $first")
        assertTrue(untried.all { it.weeksSinceEaten == null })
        assertEquals(rated, first.first { SuggestionGround.NOT_TRIED_YET in it.grounds }.recipeId, "rated first among them")
        assertTrue(first.count { SuggestionGround.ON_OFFER in it.grounds } in 1..3, "the offers have their turns, no more: $first")
        alice.planning.pick(week, unrated)
        alice.planning.done(week, seed = 1)
        assertTrue(suggest(weekStart = nextWeek, count = 50).none { it.recipeId == unrated && SuggestionGround.NOT_TRIED_YET in it.grounds }, "planned once, it is tried")
    }

    @Test
    fun showOthersDoesNotRepeatAndSomethingElseFindsAnyOfMyRecipes() {
        (0..9).forEach(::chickenDish)
        val outOfRotation = recipe("Grandma's beef", "1600 | g | Beef", rotation = false)

        val first = suggest()
        val others = suggest(seen = first.map { it.recipeId }.toSet())

        assertTrue(others.isNotEmpty() && others.none { other -> first.any { it.recipeId == other.recipeId } }, "$first then $others")
        assertEquals(10, (first + others).map { it.recipeId }.distinct().size, "all of them on two screens")
        assertEquals(listOf(outOfRotation), suggest(query = "grandma BEEF").map { it.recipeId })
        assertEquals(11, suggest(query = "", count = 50).size, "every meal of mine")
    }

    @Test
    fun notThisWeekHidesARecipeForThisWeekOnlyChangesNothingInTheRotationAndIsForgottenWhenTheWeekIsOver() {
        // Rated, so that they are suggested next week too, when nothing is on offer.
        val dinners = (0..7).map { chickenDish(it).also { id -> alice.recipes.setRating(id, 5) } }
        val hidden = suggest().first().recipeId

        val planning = alice.planning.hide(week, hidden)

        assertEquals(listOf(hidden), planning.notThisWeek.map { it.recipeId })
        assertTrue(suggest(count = 20).none { it.recipeId == hidden })
        assertTrue(suggest(count = 20, weekStart = nextWeek).any { it.recipeId == hidden }, "next week it is there")
        assertTrue(alice.recipes.list().single { it.id == hidden }.inRotation)
        // "Fill the rest" does not plan it either: the user does not want it this week.
        assertTrue(alice.planning.done(week, seed = 3).meals().none { it.recipeId == hidden })
        assertTrue(alice.planning.get(week).notThisWeek.isNotEmpty(), "still hidden after planning the week")

        alice.planning.unhide(week, hidden)
        assertTrue(suggest(count = 20).any { it.recipeId == hidden })

        alice.planning.hide(week, hidden)
        alice.planning.pick(week, dinners.last())
        today = start.plusDays(7)
        alice.planning.hide(nextWeek, dinners.first())
        assertEquals("0", db.planQueries.countPicks(alice.accountId, week).executeAsOne().toString(), "the week is over")
        assertEquals(emptyList(), alice.planning.get(week).notThisWeek)
    }

    @Test
    fun aChosenRecipeIsNotSuggestedAgainAndOneThatUsesTheRestOfAPackAPickOpensMovesUp() {
        (0..5).forEach { chickenDish(it) }
        val (opener, finisher) = (0..1).map(::creamy)
        fun rankOf(id: Long) = suggest(count = 50).indexOfFirst { it.recipeId == id }
        val before = rankOf(finisher)

        alice.planning.pick(week, opener)

        val after = suggest(count = 50)
        assertTrue(after.none { it.recipeId == opener }, "a chosen recipe is not suggested again")
        val rest = after.single { it.recipeId == finisher }
        assertEquals("Cream" to "Creamy 0", rest.packRests.single().ingredient to rest.packRests.single().openedBy)
        assertTrue(SuggestionGround.USES_WHAT_IS_THERE in rest.grounds)
        assertTrue(before == -1 || rankOf(finisher) < before, "ranked $before before the pick, ${rankOf(finisher)} after")
        assertTrue(rankOf(finisher) in 0..5, "on the first screen")
    }

    @Test
    fun noPickIsRefusedForItsCaloriesAndPicksAreKeptAsTheyAreMade() {
        (0..3).forEach(::beefDish)
        val salad = recipe("Lettuce", "400 | g | Lettuce")
        val feast = recipe("Feast", "6000 | g | Beef")

        alice.planning.pick(week, salad)
        alice.planning.pick(week, feast)

        // Read through repositories made anew, as after a restart of the server or the app.
        val again = scope("alice")
        assertEquals(listOf(salad, feast), again.planning.get(week).picks.map { it.recipeId })
        assertEquals(2, again.plans.get(week).dinnersChosen)
        assertEquals(0, again.plans.get(nextWeek).dinnersChosen)
        alice.planning.unpick(week, salad)
        assertEquals(listOf(feast), again.planning.get(week).picks.map { it.recipeId })
    }

    @Test
    fun theStripAgreesWithThePlanAfterDone() {
        val dinners = (0..2).map(::chickenDish) + (0..1).map(::creamy) + (0..1).map(::beefDish)
        // Monday's lunch chosen by hand, and no breakfast or snack recipes: the chosen dinners and their lunches are the whole week.
        alice.plans.choose(week, 0, MealSlot.LUNCH, recipe("Lunch", "1600 | g | Beef"))
        dinners.forEach { alice.planning.pick(week, it) }

        val planning = alice.planning.get(week)
        val plan = alice.planning.done(week, seed = 5)

        val totals = planning.totals
        assertEquals(plan.meals().size, totals.meals)
        assertClose(plan.cost, totals.cost, "cost")
        assertClose(plan.costWithoutOffers, totals.costWithoutOffers, "cost without offers")
        assertTrue(totals.cost!! < totals.costWithoutOffers!!, "the offers save something")
        assertClose(plan.days.sumOf { it.kcal }, totals.kcal, "kcal")
        assertClose(plan.days.sumOf { it.proteinG }, totals.proteinG, "protein")
        assertClose(plan.days.sumOf { it.fibreG!! }, totals.fibreG, "fibre")
        assertTrue(totals.fibreG > 0)
        val targets = plan.targets
        assertClose(plan.meals().sumOf { targets.kcal * it.slot.share }, totals.targetKcal, "kcal target of these meals")
        // Each chosen dinner is on the Plan as it was on the screen.
        for (pick in planning.picks) {
            val meal = plan.meals().single { it.recipeId == pick.recipeId && it.slot == MealSlot.DINNER }
            assertEquals(listOf(pick.portions, pick.kcal, pick.proteinG), listOf(meal.portions, meal.kcal, meal.proteinG))
            assertClose(pick.cost, meal.cost, "cost of ${pick.title}")
            assertClose(pick.fibreG, meal.fibreG, "fibre of ${pick.title}")
        }
    }

    @Test
    fun donePutsEveryChosenDinnerOnADayKeepsWhatStaysAndKeepsDinnersSharingAPackWithinItsDays() {
        (0..5).forEach(::beefDish)
        val batch = recipe("Batch", "1600 | g | Beef").also { alice.recipes.setKeeps(it, RecipeKeeps(days = 3)) }
        // Tuesday's dinner eaten, Thursday's locked, and a batch made on Friday for its dinner and Saturday's lunch.
        alice.plans.generate(week, seed = 1)
        // Recipes of their own for the dinners chosen, which no meal that stays has.
        val (creamA, creamB) = (0..1).map(::creamy)
        val chickenDinner = chickenDish(0)
        alice.plans.setStatus(week, 1, MealSlot.DINNER, MealStatus.EATEN)
        alice.plans.setLocked(week, 3, MealSlot.DINNER, true)
        alice.preps.create(week, PrepInput(batch, "2026-11-20", meals = listOf(PrepSlot("2026-11-20", MealSlot.DINNER), PrepSlot("2026-11-21", MealSlot.LUNCH))))
        val before = alice.plans.get(week).meals()
        val staying = before.filter { it.slot == MealSlot.DINNER && it.day in setOf(1, 3, 4) } + before.single { it.day == 5 && it.slot == MealSlot.LUNCH }
        // Chosen with the two creamy dinners first and last.
        listOf(creamA, chickenDinner, creamB).forEach { alice.planning.pick(week, it) }
        assertEquals(listOf(0, 2, 5, 6), alice.planning.get(week).openDays)

        val plan = alice.planning.done(week, seed = 9)

        val dinners = plan.meals().filter { it.slot == MealSlot.DINNER }
        val dayOf = listOf(creamA, chickenDinner, creamB).associateWith { id -> dinners.single { it.recipeId == id && it.locked }.day }
        assertTrue(dayOf.values.all { it in setOf(0, 2, 5, 6) } && dayOf.values.toSet().size == 3, "each on a day of its own: $dayOf")
        assertTrue(abs(dayOf.getValue(creamA) - dayOf.getValue(creamB)) <= OpenPacks.WINDOW_DAYS, "within the days the cream keeps: $dayOf")
        for (meal in staying) assertEquals(meal, plan.meals().single { it.day == meal.day && it.slot == meal.slot }.copy(cost = meal.cost, cookPortions = meal.cookPortions, fibreG = meal.fibreG))
        assertEquals(0, plan.dinnersChosen)
        assertEquals(emptyList(), alice.planning.get(week).picks)
        assertEquals(7, dinners.size, "and the free day the planner's")
    }

    @Test
    fun aChosenDinnerWhoseRecipeLostItsAmountsIsListedAsNotPlannableUntilItIsTakenOut() {
        (0..3).forEach(::beefDish)
        val (kept, lost) = listOf("Kept", "Lost").map { recipe(it, "1600 | g | Beef") }
        listOf(kept, lost).forEach { alice.planning.pick(week, it) }

        alice.recipes.update(lost, RecipeInput("Lost", 4, "| | salt to taste", "Cook.", mealSlots = listOf(MealSlot.LUNCH, MealSlot.DINNER)))

        val planning = alice.planning.get(week)
        assertEquals(listOf(kept to true, lost to false), planning.picks.map { it.recipeId to it.plannable })
        assertEquals(listOf(0.0, 0.0, 0.0), planning.picks[1].let { listOf(it.portions, it.kcal, it.proteinG) })
        assertEquals(2, alice.plans.get(week).dinnersChosen)
        val refused = assertFailsWith<ConflictException> { alice.planning.done(week) }
        assertEquals("\"Lost\" has no amounts to plan with: take it out first", refused.message)

        alice.planning.unpick(week, lost)
        assertTrue(alice.planning.done(week, seed = 1).meals().any { it.recipeId == kept && it.slot == MealSlot.DINNER && it.locked })
    }

    @Test
    fun hidingTheRecipeOfAKeptDinnerKeepsItsLeftoverLunchAndPlansItNowhereElse() {
        val dinners = (0..7).map(::beefDish)
        alice.plans.choose(week, 2, MealSlot.DINNER, dinners[0])
        alice.planning.hide(week, dinners[0])

        val plan = alice.planning.done(week, seed = 4)

        val thursday = plan.meals().single { it.day == 3 && it.slot == MealSlot.LUNCH }
        assertEquals(dinners[0] to true, thursday.recipeId to thursday.leftover, "Wednesday's dinner, kept, leaves its leftovers")
        assertEquals(listOf(2 to MealSlot.DINNER, 3 to MealSlot.LUNCH), plan.meals().filter { it.recipeId == dinners[0] }.map { it.day to it.slot })
    }

    @Test
    fun daysThatHavePassedAreNoDaysToChooseForWhileFillTheRestPlansThemAsGenerateDoes() {
        val dinners = (0..7).map(::beefDish)
        // Opened on Thursday: Monday to Wednesday have passed.
        today = start.plusDays(3)

        assertEquals(listOf(3, 4, 5, 6), alice.planning.get(week).openDays)
        assertEquals((0..6).toList(), alice.planning.get(nextWeek).openDays, "a week wholly ahead is as before")
        dinners.take(4).forEach { alice.planning.pick(week, it) }
        assertTrue(assertFailsWith<ConflictException> { alice.planning.pick(week, dinners[4]) }.message!!.contains("4 days from today on"))

        val plan = alice.planning.done(week, seed = 6)

        val dinnersOf = plan.meals().filter { it.slot == MealSlot.DINNER }
        assertEquals(dinners.take(4).toSet(), dinnersOf.filter { it.locked }.mapTo(HashSet()) { it.recipeId })
        assertTrue(dinnersOf.filter { it.locked }.all { it.day >= 3 }, "no chosen dinner on a day that has passed: $dinnersOf")
        // The days that have passed are planned as Generate plans them: by the planner, unlocked.
        assertEquals(listOf(0, 1, 2), dinnersOf.filter { !it.locked }.map { it.day })
    }

    @Test
    fun aDinnerMovesNeitherToNorFromADayThatHasPassed() {
        (0..6).forEach(::beefDish)
        alice.plans.generate(week, seed = 2)
        today = start.plusDays(3)

        assertEquals(
            "Tuesday 17 November has passed: a dinner moves only between today and the days after it",
            assertFailsWith<ConflictException> { alice.plans.moveDinner(week, 1, 4) }.message,
        )
        assertTrue(assertFailsWith<ConflictException> { alice.plans.moveDinner(week, 4, 2) }.message!!.startsWith("Wednesday 18 November has passed"))
        val moved = alice.plans.moveDinner(week, 4, 3).meals()
        assertEquals(2, moved.count { it.slot == MealSlot.DINNER && it.day in 3..4 })
    }

    @Test
    fun letThePlannerChooseLeavesOutTheRecipesHiddenForTheWeek() {
        val (stew, hidden) = (0..1).map(::beefDish)
        alice.plans.choose(week, 0, MealSlot.DINNER, stew)
        alice.plans.setLocked(week, 0, MealSlot.DINNER, false)
        alice.planning.hide(week, hidden)

        // The hidden recipe is the only other one: there is none to give, as for a week with nothing else that fits.
        val refused = assertFailsWith<ConflictException> { alice.plans.reroll(week, 0, MealSlot.DINNER, seed = 1) }
        assertEquals("No other recipe fits this meal", refused.message)
        alice.planning.unhide(week, hidden)
        assertEquals(hidden, alice.plans.reroll(week, 0, MealSlot.DINNER, seed = 1).meals().single { it.day == 0 && it.slot == MealSlot.DINNER }.recipeId)
    }

    @Test
    fun noMoreDinnersCanBeChosenThanThereAreDaysToPutThemOn() {
        val dinners = (0..7).map(::beefDish)
        dinners.take(6).forEach { alice.planning.pick(week, it) }
        alice.plans.choose(week, 0, MealSlot.DINNER, dinners[6])
        assertEquals(6, alice.planning.get(week).openDays.size)

        val refused = assertFailsWith<ConflictException> { alice.planning.pick(week, dinners[7]) }
        assertTrue(refused.message!!.contains("take one out first"), refused.message)
        alice.planning.pick(week, dinners[0])

        // A seventh dinner kept after they were chosen leaves one too many: Done says so and changes nothing.
        alice.plans.choose(week, 1, MealSlot.DINNER, dinners[7])
        val before = alice.plans.get(week)
        val tooMany = assertFailsWith<ConflictException> { alice.planning.done(week) }
        assertTrue(tooMany.message!!.contains("6 dinners are chosen for 5 days"), tooMany.message)
        assertEquals(before, alice.plans.get(week))
    }

    @Test
    fun aDinnerMovesToAnotherDayWithItsLeftoverLunchAndWhatWasInIt() {
        (0..6).forEach(::beefDish)
        val plan = alice.plans.generate(week, seed = 2)
        fun dinner(day: Int) = plan.meals().single { it.day == day && it.slot == MealSlot.DINNER }
        val (monday, thursday) = dinner(0) to dinner(3)
        alice.plans.setLocked(week, 0, MealSlot.DINNER, true)
        alice.plans.adjust(week, 0, MealSlot.DINNER, listOf(MealLineChange(beef.id, 100.0)))

        val moved = alice.plans.moveDinner(week, 0, 3).meals()

        fun at(day: Int, slot: MealSlot) = moved.single { it.day == day && it.slot == slot }
        assertEquals(monday.recipeId to true, at(3, MealSlot.DINNER).recipeId to at(3, MealSlot.DINNER).locked)
        assertTrue(at(3, MealSlot.DINNER).adjusted, "what was in it went with it")
        assertEquals(thursday.recipeId to false, at(0, MealSlot.DINNER).recipeId to at(0, MealSlot.DINNER).adjusted)
        assertEquals(monday.recipeId to true, at(4, MealSlot.LUNCH).recipeId to at(4, MealSlot.LUNCH).leftover, "Monday's leftovers follow it to Friday")
        assertEquals(thursday.recipeId to true, at(1, MealSlot.LUNCH).recipeId to at(1, MealSlot.LUNCH).leftover, "and Thursday's to Tuesday")
        assertEquals(plan.meals().filter { it.day !in setOf(0, 1, 3, 4) }.map { it.copy(cost = null) }, moved.filter { it.day !in setOf(0, 1, 3, 4) }.map { it.copy(cost = null) })

        // To a day without a dinner: it goes there, and its leftovers can't come along to a Monday of next week.
        alice.plans.remove(week, 6, MealSlot.DINNER)
        val toSunday = alice.plans.moveDinner(week, 2, 6).meals()
        assertEquals(dinner(2).recipeId, toSunday.single { it.day == 6 && it.slot == MealSlot.DINNER }.recipeId)
        assertTrue(toSunday.none { it.day == 2 && it.slot == MealSlot.DINNER })
        assertTrue(toSunday.none { it.day == 3 && it.slot == MealSlot.LUNCH }, "Thursday's lunch was Wednesday's leftovers, which went with them")
    }

    @Test
    fun aLunchOfItsOwnTakesThePlaceOfLeftoversThatLeft() {
        val stew = beefDish(0)
        val sandwich = alice.recipes.create(RecipeInput("Sandwich", 1, "150 | g | Beef", "Make.", mealSlots = listOf(MealSlot.LUNCH))).summary.id
        // Monday's dinner is a single serving, with a sandwich on Tuesday; Wednesday's stew leaves leftovers, and Thursday has no lunch.
        alice.plans.choose(week, 0, MealSlot.DINNER, alice.recipes.create(RecipeInput("Single", 1, "500 | g | Beef", "Cook.")).summary.id)
        alice.plans.choose(week, 1, MealSlot.LUNCH, sandwich)
        alice.plans.setLocked(week, 1, MealSlot.LUNCH, false)
        alice.plans.choose(week, 2, MealSlot.DINNER, stew)

        val moved = alice.plans.moveDinner(week, 0, 2).meals()

        fun lunch(day: Int) = moved.singleOrNull { it.day == day && it.slot == MealSlot.LUNCH }
        assertEquals(stew to true, lunch(1)?.let { it.recipeId to it.leftover }, "the stew, now on Monday, leaves its leftovers for Tuesday")
        assertEquals(sandwich to false, lunch(3)?.let { it.recipeId to it.leftover }, "and the sandwich goes to Thursday, after the single serving")
    }

    @Test
    fun anEatenDinnerAndOneThatMakesABatchStayOnTheirDays() {
        (0..6).forEach(::beefDish)
        val batch = recipe("Batch", "1600 | g | Beef").also { alice.recipes.setKeeps(it, RecipeKeeps(days = 3)) }
        alice.plans.generate(week, seed = 2)
        alice.plans.setStatus(week, 1, MealSlot.DINNER, MealStatus.EATEN)
        alice.preps.create(week, PrepInput(batch, "2026-11-20", meals = listOf(PrepSlot("2026-11-20", MealSlot.DINNER), PrepSlot("2026-11-21", MealSlot.DINNER))))
        val before = alice.plans.get(week)

        fun refusal(day: Int, toDay: Int) = assertFailsWith<ConflictException> { alice.plans.moveDinner(week, day, toDay) }.message!!
        assertEquals("This meal was eaten already", refusal(1, 2))
        assertEquals("This meal was eaten already", refusal(2, 1), "nor can another take its day")
        assertTrue(refusal(4, 0).startsWith("This meal makes the prepped batch for Saturday dinner"), refusal(4, 0))
        assertTrue(refusal(5, 0).contains("is a meal of a prepped batch"), refusal(5, 0))
        assertFailsWith<IllegalArgumentException> { alice.plans.moveDinner(week, 0, 0) }
        assertFailsWith<NotFoundException> { scope("bob").plans.moveDinner(week, 0, 2) }
        assertEquals(before, alice.plans.get(week))
    }

    @Test
    fun theRoutesAnswerAndRefuseAsTheyShould() = testApplication {
        val config = ServerConfig(
            port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null,
            nevoFile = createTempFile("nevo", ".csv").toFile().apply { writeText(nevo) },
        )
        val server = openDatabase(config.databaseUrl).second
        application { module(config, server, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens, today = { start }) }
        val http: HttpClient = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("t")
                contentType(ContentType.Application.Json)
            }
        }
        http.put("/api/profile") { setBody(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000)) }
        http.post("/api/ingredients") { setBody(IngredientInput("Beef", nevoCode = 10)) }
        val ids = (0..7).map { i ->
            http.post("/api/recipes") { setBody(RecipeInput("Stew $i", 4, "${1600 + i * 40} | g | Beef", "Stew.", mealSlots = listOf(MealSlot.DINNER))) }
                .body<dev.tobins.mealplanner.api.RecipeDetail>().summary.id
        }
        ids.take(2).forEach { http.put("/api/recipes/$it/rating") { setBody(RatingInput(5)) } }

        // None was ever planned: each is one not tried yet, the rated two favourites too.
        val first = http.get("/api/plans/$week/planning/suggestions").body<DinnerSuggestions>()
        assertEquals(6 to true, first.suggestions.size to first.more)
        assertEquals(listOf(ids[0]), http.get("/api/plans/$week/planning/suggestions?seen=${ids.drop(1).joinToString(",")}").body<DinnerSuggestions>().suggestions.map { it.recipeId })
        assertEquals(3, http.get("/api/plans/$week/planning/suggestions?q=stew&count=3").body<DinnerSuggestions>().suggestions.size)
        assertEquals(HttpStatusCode.BadRequest, http.get("/api/plans/$week/planning/suggestions?seen=x").status)
        assertEquals(HttpStatusCode.BadRequest, http.get("/api/plans/$week/planning/suggestions?count=0").status)
        assertEquals(listOf(ids[0]), http.put("/api/plans/$week/planning/picks/${ids[0]}").body<dev.tobins.mealplanner.api.WeekPlanning>().picks.map { it.recipeId })
        assertEquals(HttpStatusCode.NotFound, http.put("/api/plans/$week/planning/picks/999").status)
        assertEquals(HttpStatusCode.OK, http.put("/api/plans/$week/planning/not-this-week/${ids[1]}").status)
        val plan = http.post("/api/plans/$week/planning/done").body<WeekPlan>()
        assertTrue(plan.days.any { day -> day.meals.any { it.recipeId == ids[0] && it.locked } })
        val dinner = plan.days.first { day -> day.meals.any { it.slot == MealSlot.DINNER } }.meals.first { it.slot == MealSlot.DINNER }
        val other = (dinner.day + 1) % 7
        val moved = http.post("/api/plans/$week/meals/${dinner.day}/DINNER/move") { setBody(DinnerMove(other)) }
        assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
        assertEquals(dinner.recipeId, moved.body<WeekPlan>().days[other].meals.single { it.slot == MealSlot.DINNER }.recipeId)
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/plans/$week/meals/0/LUNCH/move") { setBody(DinnerMove(1)) }.status)
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/plans/$week/meals/0/DINNER/move") { setBody(DinnerMove(7)) }.status)
    }
}
