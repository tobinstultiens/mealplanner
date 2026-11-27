package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.planner.Pick
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.planner.PlanRequest
import dev.tobins.mealplanner.planner.PortionRange
import dev.tobins.mealplanner.planner.WeekPlanner
import dev.tobins.mealplanner.server.prices.history.Measure
import dev.tobins.mealplanner.targets.TargetCalculator
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Measures how far a planned meal strays from its slot's calorie share on the real recipe set (issue 241), before
 * anything is changed: over 50 seeds and for a cutting and a maintaining profile, how often a meal is over or under its
 * share by more than a quarter, a half and (over) all of it, per slot; the days' calorie error, protein and fibre; and
 * the worst cases by name with the runner-up and what it lost by, so that the cause shows. Then the two fixes the
 * issue names, as far as the planner can be asked for them without changing what it does: a cap on how far over the
 * share a picked meal may be ([PlanRequest.overShareCap]) and day balancing, which here is applied to the plan afterwards
 * ([balanced]), so that it is an approximation of what the planner would do while planning.
 *
 * It asserts nothing about the numbers: the owner reads them and chooses. The one assertion is that watching the planner
 * ([PlanRequest.onPick]) does not change its plan. Skipped when the cooking repo (`-PcookingRepo=`, by default `../cooking`)
 * or the NEVO file (`-PnevoCsv=`, by default `data/nevo/NEVO2025_v9.0.csv`) is missing, as [PackRestMeasurementTest] is.
 * Prints markdown tables: `./gradlew :server:test --tests '*PortionOvershootMeasurementTest*' -i`
 */
class PortionOvershootMeasurementTest {
    private val cooking = File(System.getProperty("cookingRepo"))
    private val nevo = File(System.getProperty("nevoCsv"))

    /** The profile shape of [PackRestMeasurementTest], which has the default goal of losing half a kilo a week. */
    private val cutting = Profile(75.0, 180.0, 35, Sex.MALE, ActivityLevel.MODERATE)
    private val maintenance = cutting.copy(goalKgPerWeek = 0.0)

    /** What is tried: the planner as it is, with a cap, with day balancing, with both. */
    private enum class Variant(val label: String, val cap: Double?, val balance: Boolean) {
        AS_IS("as is", null, false),
        CAP_50("cap 50%", 0.5, false),
        CAP_25("cap 25%", 0.25, false),
        BALANCED("balanced days", null, true),
        CAP_50_BALANCED("cap 50% + balanced", 0.5, true),
    }

    private class Run(val meals: List<PlannedMeal>, val picks: List<Pick>)

    private fun plan(candidates: List<PlanCandidate>, profile: Profile, variant: Variant, seed: Long): Run {
        val picks = mutableListOf<Pick>()
        val request = PlanRequest(
            TargetCalculator.targets(profile), candidates, portions = PortionRange.of(profile), priority = profile.priority,
            overShareCap = variant.cap, onPick = { picks += it }, seed = seed,
        )
        val meals = WeekPlanner.plan(request)
        return Run(if (variant.balance) balanced(meals, request) else meals, picks)
    }

    /**
     * Day balancing after the fact: each day's meals, in the order of the day, are sized to what the earlier ones left, so
     * that a meal gets its share of what remains of the target rather than of the whole. The recipes stay as picked. A
     * dinner's leftover lunch is sized to what its dinner now leaves; where nothing is left it stays as it was. The
     * planner would also pick with the day so far in mind, which this does not.
     */
    private fun balanced(meals: List<PlannedMeal>, request: PlanRequest): List<PlannedMeal> {
        val byId = request.candidates.associateBy { it.recipeId }
        val dinners = mutableMapOf<Int, Double>()
        val result = mutableListOf<PlannedMeal>()
        for (day in meals.map { it.day }.distinct().sorted()) {
            val today = meals.filter { it.day == day }.sortedBy { it.slot.ordinal }
            var left = request.targets.kcal.toDouble()
            var shares = today.sumOf { it.slot.share }
            for (meal in today) {
                val candidate = byId.getValue(meal.recipeId)
                val budget = left * meal.slot.share / shares
                val available = if (meal.leftover) dinners[day - 1]?.let { candidate.servings - it } else null
                val steps = if (available == null) request.portions.steps(meal.slot) else if (available > 0) request.portions.steps(meal.slot, available) else listOf(meal.portions)
                val portions = steps.minBy { abs(candidate.kcal * it - budget) }
                val resized = meal.copy(
                    portions = portions, kcal = candidate.kcal * portions, proteinG = candidate.proteinG * portions, fibreG = candidate.fibreG * portions,
                )
                if (meal.slot == MealSlot.DINNER) dinners[day] = portions
                result += resized
                left -= resized.kcal
                shares -= meal.slot.share
            }
        }
        return result
    }

    /** What the runs of one profile and variant came to; protein and fibre as the mean share of the daily target the days reach. */
    private class Measure(val runs: List<Run>, val profile: Profile, val candidates: List<PlanCandidate>) {
        val targets = TargetCalculator.targets(profile)
        private val byId = candidates.associateBy { it.recipeId }
        private fun perDay(of: (List<PlannedMeal>) -> Double) = runs.flatMap { run -> (0 until 7).map { day -> of(run.meals.filter { it.day == day }) } }.average()

        val calorieError = perDay { abs(it.sumOf { m -> m.kcal } - targets.kcal) / targets.kcal * 100 }
        val calorieBias = perDay { (it.sumOf { m -> m.kcal } - targets.kcal) / targets.kcal * 100 }
        val protein = perDay { it.sumOf { m -> m.proteinG } / targets.proteinG * 100 }
        val fibre = perDay { it.sumOf { m -> m.fibreG ?: 0.0 } / checkNotNull(targets.fibreG) * 100 }
        val emptySlots = runs.map { 28 - it.meals.size }.average()

        /** Which slots stay empty, and for how many days a week on average; blank when none. */
        val emptyWhere = MealSlot.entries.mapNotNull { slot ->
            runs.map { run -> 7 - run.meals.count { it.slot == slot } }.average().takeIf { it > 0 }?.let { "${slot.name.lowercase()} ${"%.1f".format(it)}" }
        }.joinToString(", ")

        /** A recipe cooked again in the week; breakfast and snack are routines and a leftover is no new cooking. */
        val repeats = runs.map { run ->
            run.meals.filter { !it.leftover && (it.slot == MealSlot.LUNCH || it.slot == MealSlot.DINNER) }.groupingBy { it.recipeId }.eachCount().values.sumOf { it - 1 }
        }.average()

        /** Euros a day of what is eaten, at the recipes' cost per serving; a recipe without a price counts nothing. */
        val euros = runs.flatMap { run -> run.meals.map { (byId.getValue(it.recipeId).costPerServing ?: 0.0) * it.portions } }.sum() / (runs.size * 7)

        /** The meals of [slot] that are off its calorie share by more than [margin] (a fraction), over or under, as a percentage of those planned. */
        fun off(slot: MealSlot, margin: Double, over: Boolean): Double {
            val meals = runs.flatMap { it.meals }.filter { it.slot == slot }
            val budget = targets.kcal * slot.share
            val n = meals.count { if (over) it.kcal > (1 + margin) * budget else it.kcal < (1 - margin) * budget }
            return if (meals.isEmpty()) 0.0 else n * 100.0 / meals.size
        }
    }

    private fun f(x: Double, digits: Int = 1) = "%.${digits}f".format(x)

    private fun report(name: String, profile: Profile, candidates: List<PlanCandidate>) {
        val targets = TargetCalculator.targets(profile)
        println("\n### $name: ${targets.kcal} kcal, ${targets.proteinG} g protein, ${targets.fibreG} g fibre a day\n")
        val measures = Variant.entries.associateWith { v -> Measure((0L until 50L).map { plan(candidates, profile, v, it) }, profile, candidates) }

        println("Meals off their slot's calorie share, % of the meals planned (50 seeds)\n")
        println("| variant | slot | over >25% | over >50% | over >100% | under >25% | under >50% |")
        println("|---|---|---|---|---|---|---|")
        for ((v, m) in measures) for (slot in MealSlot.entries) {
            println("| ${v.label} | ${slot.name.lowercase()} | ${listOf(0.25, 0.5, 1.0).joinToString(" | ") { f(m.off(slot, it, over = true)) }} | ${listOf(0.25, 0.5).joinToString(" | ") { f(m.off(slot, it, over = false)) }} |")
        }

        census(profile, candidates)
        println("\nThe days, the week and what it costs (means over 50 seeds)\n")
        println("| variant | day kcal error % | day kcal bias % | protein % of target | fibre % of target | empty slots/week | repeats/week | euros/day |")
        println("|---|---|---|---|---|---|---|---|")
        for ((v, m) in measures) {
            println("| ${v.label} | ${f(m.calorieError)} | ${f(m.calorieBias)} | ${f(m.protein)} | ${f(m.fibre)} | ${f(m.emptySlots, 1)} ${m.emptyWhere.let { if (it.isEmpty()) "" else "($it)" }} | ${f(m.repeats, 2)} | ${f(m.euros, 2)} |")
        }
        worst(profile, candidates, measures.getValue(Variant.AS_IS))
    }

    /** How many recipes there are for each slot, and how many of them the planner would take as a meal there, with and without a 50% cap. */
    private fun census(profile: Profile, candidates: List<PlanCandidate>) {
        val targets = TargetCalculator.targets(profile)
        val range = PortionRange.of(profile)
        println("\nRecipes per slot\n")
        println("| slot | recipes | fit as a meal | and within 50% over the share |")
        println("|---|---|---|---|")
        for (slot in MealSlot.entries) {
            val of = candidates.filter { slot in it.slots }
            val fit = of.filter { WeekPlanner.fitsAsMeal(it, slot, targets, range) }
            val capped = fit.filter { it.kcal * WeekPlanner.portionsFor(it, slot, targets, range) <= 1.5 * targets.kcal * slot.share }
            println("| ${slot.name.lowercase()} | ${of.size} | ${fit.size} | ${capped.size} |")
        }
    }

    /** The same pick across seeds: what won, for which slot. */
    private data class Key(val recipeId: Long, val slot: MealSlot)

    /**
     * The picks of the planner as it is whose meal is most over its slot's share, by recipe and slot: how often, at what
     * portion, and the runner-up with the gap in score, split into the terms that [score][WeekPlanner] adds for calories,
     * protein and fibre (at the balanced priority's weights 2, 2 and 1) so that the rest (rating, repeats, price, packs,
     * jitter) is what is left over.
     */
    private fun worst(profile: Profile, candidates: List<PlanCandidate>, asIs: Measure) {
        val targets = asIs.targets
        val range = PortionRange.of(profile)
        fun portions(c: PlanCandidate, slot: MealSlot) = WeekPlanner.portionsFor(c, slot, targets, range)
        fun ratio(c: PlanCandidate, slot: MealSlot) = c.kcal * portions(c, slot) / (targets.kcal * slot.share)
        fun calorieFit(c: PlanCandidate, slot: MealSlot) = 1 - abs(ratio(c, slot) - 1).coerceAtMost(1.0)
        fun proteinFit(c: PlanCandidate, slot: MealSlot) = (c.proteinG * portions(c, slot) / (targets.proteinG * slot.share)).coerceAtMost(1.5)
        fun fibreFit(c: PlanCandidate, slot: MealSlot) = (c.fibreG * portions(c, slot) / (checkNotNull(targets.fibreG) * slot.share)).coerceAtMost(1.5)

        val picks = asIs.runs.flatMap { it.picks }.filter { ratio(it.chosen, it.budgetSlot) > 1.5 }
        println("\nWorst picks of the planner as it is (kcal against the slot's share over 150%), by recipe and slot, over 50 seeds\n")
        if (picks.isEmpty()) return println("None.")
        println("| recipe | slot | picked | portion | kcal (share) | over | runner-up (portion, kcal) | lost by | calories | protein | fibre | other | in the running |")
        println("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        val groups = picks.groupBy { Key(it.chosen.recipeId, it.budgetSlot) }.entries.sortedByDescending { (k, v) -> ratio(v.first().chosen, k.slot) }.take(12)
        for ((key, group) in groups) {
            val c = group.first().chosen
            val slot = key.slot
            val withRunnerUp = group.filter { it.runnerUp != null }
            val runnerUp = withRunnerUp.groupingBy { it.runnerUp!! }.eachCount().maxByOrNull { it.value }?.key
            val same = withRunnerUp.filter { it.runnerUp == runnerUp }
            fun gap(fit: (PlanCandidate, MealSlot) -> Double, weight: Double) =
                if (runnerUp == null) Double.NaN else weight * (fit(c, slot) - fit(runnerUp, slot))
            val terms = listOf(gap(::calorieFit, 2.0), gap(::proteinFit, 2.0), gap(::fibreFit, 1.0))
            val lostToThat = same.map { it.score - it.runnerUpScore }.average()
            println(
                "| ${c.title} | ${slot.name.lowercase()} | ${group.size} | ${f(portions(c, slot), 2)} | ${f(c.kcal * portions(c, slot), 0)} (${f(targets.kcal * slot.share, 0)}) | " +
                    "${f((ratio(c, slot) - 1) * 100, 0)}% | " +
                    (if (runnerUp == null) "none | | | | | | " else "${runnerUp.title} (${f(portions(runnerUp, slot), 2)}, ${f(runnerUp.kcal * portions(runnerUp, slot), 0)}) | ${f(lostToThat, 2)} | " +
                        "${f(terms[0], 2)} | ${f(terms[1], 2)} | ${f(terms[2], 2)} | ${f(lostToThat - terms.sum(), 2)} | ") +
                    "${group.map { it.fitting }.average().let { f(it, 0) }} |",
            )
        }
        val atFloor = picks.count { portions(it.chosen, it.budgetSlot) == range.steps(it.budgetSlot).first() }
        println("\n${picks.size} such picks in 50 seeds, ${atFloor} of them at the smallest portion the slot allows.")
    }

    @Test
    fun measure() {
        val candidates = RealRecipeFixture.candidates(cooking, nevo) ?: return
        // Watching the planner must not change what it plans.
        val targets = TargetCalculator.targets(cutting)
        for (seed in 0L until 5L) {
            assertEquals(
                WeekPlanner.plan(PlanRequest(targets, candidates, seed = seed)),
                WeekPlanner.plan(PlanRequest(targets, candidates, onPick = {}, seed = seed)),
            )
        }
        report("Cutting (the default goal, 0.5 kg a week)", cutting, candidates)
        report("Maintenance", maintenance, candidates)
    }
}
