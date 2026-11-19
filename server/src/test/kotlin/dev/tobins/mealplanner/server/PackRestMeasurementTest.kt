package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.ManyAddedInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.planner.Pack
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.planner.PlanRequest
import dev.tobins.mealplanner.planner.WeekPlanner
import dev.tobins.mealplanner.planner.OpenPacks
import dev.tobins.mealplanner.targets.TargetCalculator
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures what the planner's pack term (issue 222) does to the plans of the real recipe set: over 50 seeds,
 * the euros of pack rest a week leaves, the mean error of the days' calories against the target, and the
 * recipes cooked more than once. The recipes are the cooking repo's, the nutrition NEVO's; the regular AH
 * prices (and so the packs) are those of `prices/ah-regular-prices-2026-11-19.csv`, looked up on that day.
 *
 * The term ships only if the rest falls and the calorie error rises by no more than a percentage point, which
 * is what this test asserts at the weight in use. Skipped when the cooking repo (`-PcookingRepo=`, by default
 * `../cooking`) or the NEVO file (`-PnevoCsv=`, by default `data/nevo/NEVO2025_v9.0.csv`) is missing, as
 * `CookingRepoParseTest` is. Prints the table of the weights tried:
 * `./gradlew :server:test --tests '*PackRestMeasurementTest*' -i`
 */
class PackRestMeasurementTest {
    private val cooking = File(System.getProperty("cookingRepo"))
    private val nevo = File(System.getProperty("nevoCsv"))

    /** What one plan was measured as. */
    private data class Measure(val restEuros: Double, val calorieErrorPercent: Double, val repeats: Int)

    private fun measure(meals: List<PlannedMeal>, real: Map<Long, PlanCandidate>, kcalTarget: Int): Measure {
        val dayErrors = (0 until 7).map { day -> abs(meals.filter { it.day == day }.sumOf { it.kcal } - kcalTarget) / kcalTarget * 100 }
        // A recipe cooked again in the week; breakfast and snack are routines and a leftover is no new cooking.
        val cooked = meals.filter { !it.leftover && (it.slot == MealSlot.LUNCH || it.slot == MealSlot.DINNER) }.groupingBy { it.recipeId }.eachCount()
        return Measure(OpenPacks.restOf(meals, real), dayErrors.average(), cooked.values.sumOf { it - 1 })
    }

    /** The recipes as candidates with their fresh uses, and the calories a day the targets ask. */
    private fun fixture(): Pair<List<PlanCandidate>, Int>? {
        if (!File(cooking, "content").isDirectory || !nevo.isFile) {
            println("Cooking repo at ${cooking.absolutePath} or NEVO file at ${nevo.absolutePath} not found; skipping")
            return null
        }
        val db = openDatabase("jdbc:sqlite::memory:").second
        val foods = FoodRepository(db)
        foods.importNevo(nevo)
        val userId = UserRepository(db).resolve(Identity("measure", "measure")).id
        val ingredients = IngredientRepository(db, foods, userId)
        ingredients.applySeed()
        val offers = OfferRepository(db, emptyList())
        val prices = PriceRepository(db, null, foods, offers)
        val rows = parseDelimited(checkNotNull(javaClass.getResource("/prices/ah-regular-prices-2026-11-19.csv")).readText(), ',').drop(1)
        for (row in rows) {
            val (name, packSize, packPrice, perKg) = row
            db.priceQueries.upsertPrice(
                name, "AH", name, packSize.ifBlank { null }, packPrice.toDoubleOrNull(), perKg.toDouble(), "2026-11-19T00:00:00Z",
                row[4].ifBlank { null }, row[5].ifBlank { null }, null,
            )
        }
        val recipes = RecipeRepository(db, ingredients, userId) { from, until -> prices.pricing(ingredients, from, until) }
        recipes.syncCookingSite(checkNotNull(CookingSiteReader(cooking).readAll()))
        recipes.setAdded(ManyAddedInput(recipes.bank().map { it.id }, added = true))
        val sections = ShopSections(ingredients, prices)
        val packs = prices.freshPacks(ingredients, sections.keepsByIngredient(sections.byIngredient()))
        val week = LocalDate.of(2026, 11, 16)
        val pricing = prices.pricing(ingredients, week, week.plusDays(6))
        val candidates = recipes.planCandidates(prices = pricing, freshPacks = packs)
        println("${candidates.size} candidates, ${candidates.count { it.freshUses.isNotEmpty() }} with fresh uses, ${packs.size} fresh ingredients with a pack")
        return candidates to TargetCalculator.targets(profile).kcal
    }

    /** Plans 50 seeds with the pack euros of each recipe scaled so that the planner's effective weight is [weight] (the term is linear in them: they are divided by [WeekPlanner.PACK_WEIGHT], which it multiplies in again), measured at the real euros. */
    private val profile = Profile(75.0, 180.0, 35, Sex.MALE, ActivityLevel.MODERATE)

    private fun fifty(candidates: List<PlanCandidate>, weight: Double, kcal: Int): List<Measure> {
        val real = candidates.associateBy { it.recipeId }
        val scaled = candidates.map { c -> c.copy(freshUses = c.freshUses.map { it.copy(pack = Pack(it.pack.grams, it.pack.euros * weight / WeekPlanner.PACK_WEIGHT)) }) }
        val targets = TargetCalculator.targets(profile)
        return (0L until 50L).map { seed -> measure(WeekPlanner.plan(PlanRequest(targets, scaled, seed = seed)), real, kcal) }
    }

    @Test
    fun theTermLowersTheRestWithoutCostingMoreThanAPointOfCalorieError() {
        val (candidates, kcal) = fixture() ?: return
        // Weight 0 is the planner without the term: the same recipes, no preference for packs.
        val baseline = fifty(candidates, 0.0, kcal)
        println("weight | rest euros/week | calorie error % | repeats/week")
        fun row(label: String, runs: List<Measure>) =
            println("%-6s | %6.2f | %5.2f | %5.2f".format(label, runs.map { it.restEuros }.average(), runs.map { it.calorieErrorPercent }.average(), runs.map { it.repeats }.average()))
        row("0", baseline)
        val results = (listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 5.0) + WEIGHT).distinct().associateWith { w -> fifty(candidates, w, kcal).also { row("$w", it) } }

        val shipped = results.getValue(WEIGHT)
        assertTrue(shipped.map { it.restEuros }.average() < baseline.map { it.restEuros }.average(), "the rest falls")
        assertTrue(
            shipped.map { it.calorieErrorPercent }.average() <= baseline.map { it.calorieErrorPercent }.average() + 1.0,
            "the calorie error rises by no more than a percentage point",
        )
    }

    private companion object {
        /** The weight in use, which the table is read at. */
        const val WEIGHT = WeekPlanner.PACK_WEIGHT
    }
}
