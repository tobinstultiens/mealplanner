package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.WeekPlan
import java.time.LocalDate
import kotlin.io.path.createTempFile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the week leaves of fresh ingredients (issue 244): the small rests of the packs its meals open, shown under the
 * week's cost. The meals' portions are those the planner sizes, so the expected rests are worked out from them.
 */
class WeekRestTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Zuivel|Dairy|9301|Room|Cream||per 100g|330|3|35|20|3|3|0|40",
    ).joinToString("\r\n")

    private val week = "2026-11-16"
    private val nextWeek = "2026-11-23"
    private val db = openDatabase("jdbc:sqlite::memory:").second
    private val foods = FoodRepository(db).also { f -> f.importNevo(createTempFile().toFile().apply { writeText(nevo) }) }
    private val userId = UserRepository(db).resolve(Identity("a", "alice")).id
    private val ingredients = IngredientRepository(db, foods, userId)
    private val prices = PriceRepository(db, null, foods, OfferRepository(db, emptyList()))
    private val recipes = RecipeRepository(db, ingredients, userId)
    private val profiles = ProfileRepository(db, userId).also { it.save(Profile(75.0, 180.0, 35, Sex.MALE, ActivityLevel.MODERATE)) }
    private val stock = StockRepository(db, foods, userId)
    private val cream = ingredients.create(IngredientInput("Cream", nevoCode = 9301))
    private var keepsUntilNextWeek = false
    private val plans = PlanRepository(db, userId, recipes, profiles, stock, freshPacks = { prices.freshPacks(ingredients, mapOf(cream.id to keepsUntilNextWeek)) })
    private val preps = PrepRepository(db, userId, plans, recipes, profiles) { LocalDate.parse(week) }

    /** A pack of cream of [size], at 2 euros per 100 g. */
    private fun sellCream(size: String, euros: Double) =
        db.priceQueries.upsertPrice("Cream", "AH", "Cream", size, euros, 20.0, "2026-11-19T00:00:00Z", null, null, null)

    /** A recipe whose serving takes 100 g of cream, eaten at lunch and dinner. */
    private fun creamy(title: String) = recipes.create(RecipeInput(title, 4, "400 | g | Cream", "Cook.", mealSlots = listOf(MealSlot.LUNCH, MealSlot.DINNER)))

    /** The grams of cream the meals of the plan that are eaten or to eat take. */
    private fun WeekPlan.creamGrams() = days.flatMap { it.meals }.filter { it.status != MealStatus.SKIPPED }.sumOf { it.portions } * 100

    /** What is left of the last pack when [grams] are taken from packs of [pack] g, if that is under half a pack. */
    private fun small(grams: Double, pack: Int): Double? = (ceil(grams / pack) * pack - grams).takeIf { it > 0 && it < pack / 2.0 }

    private fun assertClose(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-6, "expected $expected, was $actual")

    @Test
    fun aSmallRestOfAPackIsToldWithItsGramsAndEuros() {
        sellCream("200 g", 4.0)
        val plan = plans.choose(week, 0, MealSlot.DINNER, creamy("Creamy").summary.id)

        val left = small(plan.creamGrams(), 200) ?: error("the test's pack must leave a small rest of ${plan.creamGrams()} g")
        val rest = plan.rest.single()
        assertEquals("Cream", rest.ingredient)
        assertClose(left, rest.grams)
        assertClose(left / 200 * 4.0, rest.euros)
    }

    @Test
    fun aBigRestIsNotToldAndNeitherIsOneOfAPackWithoutASizeOrOfAFoodThatKeeps() {
        sellCream("1000 g", 20.0)
        assertEquals(emptyList(), plans.choose(week, 0, MealSlot.DINNER, creamy("Creamy").summary.id).rest, "most of the pack is left")

        sellCream("200 g", 4.0)
        keepsUntilNextWeek = true
        assertEquals(emptyList(), plans.get(week).rest, "it keeps until next week")
        keepsUntilNextWeek = false
        assertEquals(1, plans.get(week).rest.size, "the same week, as a fresh food")
        sellCream("per bak", 1.5)
        assertEquals(emptyList(), plans.get(week).rest, "no size in grams")
    }

    @Test
    fun aSkippedMealBuysNothing() {
        sellCream("200 g", 4.0)
        plans.choose(week, 0, MealSlot.DINNER, creamy("Creamy").summary.id)

        assertEquals(emptyList(), plans.setStatus(week, 0, MealSlot.DINNER, MealStatus.SKIPPED).rest)
    }

    @Test
    fun aPreppedBatchIsOnePurchaseOfAllItsPortionsOnTheDayItIsMade() {
        sellCream("250 g", 5.0)
        val batch = creamy("Batch")
        recipes.setKeeps(batch.summary.id, RecipeKeeps(days = 4))

        val plan = preps.create(week, PrepInput(batch.summary.id, "2026-11-16", meals = (0..2).map { PrepSlot("2026-11-${16 + it}", MealSlot.DINNER) }))

        // One purchase of the cream of all three dinners, not a pack opened for each.
        val left = small(plan.creamGrams(), 250) ?: error("the test's batch must leave a small rest, it takes ${plan.creamGrams()} g")
        assertClose(left, plan.rest.single().grams)
    }

    @Test
    fun aBatchIsBoughtInTheWeekItIsMadeAndNotInTheOneItIsEatenIn() {
        sellCream("250 g", 5.0)
        val batch = creamy("Batch")
        recipes.setKeeps(batch.summary.id, RecipeKeeps(days = 4))
        // Made on the Sunday, eaten that day and on the Monday and Tuesday of the week after.
        val dates = listOf("2026-11-22", "2026-11-23", "2026-11-24")
        val made = preps.create(week, PrepInput(batch.summary.id, dates[0], meals = dates.map { PrepSlot(it, MealSlot.DINNER) }))

        val batchGrams = made.creamGrams() + plans.get(nextWeek).creamGrams()
        assertClose(small(batchGrams, 250) ?: error("the test's batch must leave a small rest, it takes $batchGrams g"), made.rest.single().grams)
        assertEquals(emptyList(), plans.get(nextWeek).rest, "the week after only eats it")
    }
}
