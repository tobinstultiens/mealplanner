package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.planner.PackWeights
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.planner.PlanRequest
import dev.tobins.mealplanner.planner.WeekPlanner
import dev.tobins.mealplanner.planner.OpenPacks
import dev.tobins.mealplanner.targets.TargetCalculator
import java.io.File
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

    /** What one plan was measured as; protein and fibre as the mean share of the daily target the days reach. */
    private data class Measure(val restEuros: Double, val calorieErrorPercent: Double, val proteinPercent: Double, val fibrePercent: Double, val repeats: Int)

    private val profile = Profile(75.0, 180.0, 35, Sex.MALE, ActivityLevel.MODERATE)
    private val targets = TargetCalculator.targets(profile)

    private fun measure(meals: List<PlannedMeal>, real: Map<Long, PlanCandidate>): Measure {
        fun perDay(of: (List<PlannedMeal>) -> Double) = (0 until 7).map { day -> of(meals.filter { it.day == day }) }.average()
        // A recipe cooked again in the week; breakfast and snack are routines and a leftover is no new cooking.
        val cooked = meals.filter { !it.leftover && (it.slot == MealSlot.LUNCH || it.slot == MealSlot.DINNER) }.groupingBy { it.recipeId }.eachCount()
        return Measure(
            OpenPacks.restOf(meals, real),
            perDay { abs(it.sumOf { m -> m.kcal } - targets.kcal) / targets.kcal * 100 },
            perDay { it.sumOf { m -> m.proteinG } / targets.proteinG * 100 },
            perDay { it.sumOf { m -> m.fibreG ?: 0.0 } / checkNotNull(targets.fibreG) * 100 },
            cooked.values.sumOf { it - 1 },
        )
    }

    /** The recipes as candidates with their fresh uses. */
    private fun fixture(): List<PlanCandidate>? = RealRecipeFixture.candidates(cooking, nevo)

    /** Plans 50 seeds with the pack term at [weights], measuring the rest at the real euros. */
    private fun fifty(candidates: List<PlanCandidate>, weights: PackWeights): List<Measure> {
        val real = candidates.associateBy { it.recipeId }
        return (0L until 50L).map { seed -> measure(WeekPlanner.plan(PlanRequest(targets, candidates, packWeights = weights, seed = seed)), real) }
    }

    private fun List<Measure>.mean(of: (Measure) -> Double) = map(of).average()

    @Test
    fun theTermLowersTheRestWithoutCostingMoreThanAPointOfCalorieError() {
        val candidates = fixture() ?: return
        // No weights is the planner without the term: the same recipes, no preference for packs.
        val baseline = fifty(candidates, PackWeights(reuse = 0.0, waste = 0.0))
        println("reuse | waste | rest euros/week | calorie error % | protein % of target | fibre % of target | repeats/week")
        fun row(weights: PackWeights, runs: List<Measure>) = println(
            "%5.2f | %5.2f | %6.2f | %5.2f | %6.2f | %6.2f | %5.2f".format(
                weights.reuse, weights.waste, runs.mean { it.restEuros }, runs.mean { it.calorieErrorPercent },
                runs.mean { it.proteinPercent }, runs.mean { it.fibrePercent }, runs.mean { it.repeats.toDouble() },
            ),
        )
        row(PackWeights(0.0, 0.0), baseline)
        val tried = listOf(0.0, 0.05, 0.1, 0.15, 0.2, 0.25, 0.5).flatMap { waste -> listOf(0.75, 1.0, 1.5).map { reuse -> PackWeights(reuse, waste) } } + PackWeights()
        val results = tried.distinct().associateWith { w -> fifty(candidates, w).also { row(w, it) } }

        val shipped = results.getValue(PackWeights())
        assertTrue(shipped.mean { it.restEuros } < baseline.mean { it.restEuros }, "the rest falls")
        assertTrue(shipped.mean { it.calorieErrorPercent } <= baseline.mean { it.calorieErrorPercent } + 1.0, "the calorie error rises by no more than a point")
        assertTrue(shipped.mean { it.proteinPercent } >= baseline.mean { it.proteinPercent } - 2.0, "protein does not fall by more than two points of the target")
        assertTrue(shipped.mean { it.fibrePercent } >= baseline.mean { it.fibrePercent } - 2.0, "fibre does not fall by more than two points of the target")
    }

    /**
     * What the line under the week's cost would say over 50 seeds (issue 244): the small rests (under half a pack,
     * [OpenPacks.smallRestsOf]) of each plan, how many items and euros, and the line itself for a few different weeks.
     */
    @Test
    fun theRestLineOfATypicalWeekIsShortAndNamesFoodThatCanBeUsed() {
        val real = RealRecipeFixture.load(cooking, nevo) ?: return
        val byId = real.candidates.associateBy { it.recipeId }
        val weeks = (0L until 50L).map { seed ->
            val meals = WeekPlanner.plan(PlanRequest(targets, real.candidates, seed = seed))
            OpenPacks.smallRestsOf(meals, byId) to OpenPacks.restOf(meals, byId)
        }
        val rests = weeks.map { it.first }
        println(
            "small rests per week: items mean %.1f (min %d, max %d), euros mean %.2f of %.2f all rest".format(
                rests.map { it.size }.average(), rests.minOf { it.size }, rests.maxOf { it.size },
                rests.map { r -> r.sumOf { it.euros } }.average(), weeks.map { it.second }.average(),
            ),
        )
        for (week in rests.distinctBy { r -> r.map { it.ingredientId to it.grams.toInt() } }.take(5)) {
            println(week.take(3).joinToString(", ") { "%.0f g %s (%.2f)".format(it.grams, real.names[it.ingredientId], it.euros) } + " | total %.2f, ${week.size} items".format(week.sumOf { it.euros }))
        }
        assertTrue(rests.all { week -> week.all { it.grams < it.packGrams / 2 } }, "only small rests are told")
    }
}
