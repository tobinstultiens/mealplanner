package dev.tobins.mealplanner.planner

import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import kotlin.math.abs
import kotlin.random.Random

/** A recipe the planner may use, reduced to what planning needs. Nutrition is per serving. */
data class PlanCandidate(
    val recipeId: Long,
    val title: String,
    val slots: Set<MealSlot>,
    val servings: Int,
    val kcal: Double,
    val proteinG: Double,
    val tags: List<String> = emptyList(),
    /** How many of its ingredients are on hand and should be used up. */
    val onHandCount: Int = 0,
    /** How many of its ingredients are on offer at a store during the plan's week. */
    val offerCount: Int = 0,
    val costPerServing: Double? = null,
)

data class PlanRequest(
    val targets: DailyTargets,
    val candidates: List<PlanCandidate>,
    val days: Int = 7,
    /** Meals to keep exactly as they are. */
    val locked: List<PlannedMeal> = emptyList(),
    /** Recipes not to pick, e.g. the one being replaced by a reroll. */
    val excluded: Set<Long> = emptySet(),
    /** Recipes eaten recently; picked only when nothing else fits as well. */
    val recent: Set<Long> = emptySet(),
    /** Same seed, same plan. */
    val seed: Long = 0,
)

/**
 * Fills a week of meals so that each day lands near the calorie target and reaches
 * the protein target where the recipes allow it.
 *
 * - Breakfast and snack are routines: one choice, repeated all week.
 * - Dinner is a different recipe each day; what a dinner recipe yields beyond the
 *   portion eaten becomes the next day's lunch.
 * - Portions are adjusted in quarter servings to fit the slot's share of the calories.
 * - A lunch with neither leftovers nor a lunch recipe is cooked from a dinner recipe.
 * - A breakfast or snack slot with no suitable recipe stays empty.
 */
object WeekPlanner {
    private val portionSteps = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

    fun plan(request: PlanRequest): List<PlannedMeal> {
        val random = Random(request.seed)
        val meals = request.locked.map { it.copy(locked = true) }.toMutableList()
        val usable = request.candidates.filter { it.recipeId !in request.excluded && it.kcal > 0 }
        fun taken(day: Int, slot: MealSlot) = meals.any { it.day == day && it.slot == slot }

        // Routines: the best breakfast and snack, every day.
        for (slot in listOf(MealSlot.BREAKFAST, MealSlot.SNACK)) {
            val routine = usable.filter { slot in it.slots }
                .maxByOrNull { score(it, slot, request, uses = 0, sameTagAsYesterday = false, jitter = random.nextDouble()) }
                ?: continue
            for (day in 0 until request.days) {
                if (!taken(day, slot)) meals += meal(routine, day, slot, request.targets)
            }
        }

        var yesterdaysDinner: PlanCandidate? = null
        // What yesterday's dinner left over: the recipe and how many servings remain.
        var leftoverForLunch: Pair<PlanCandidate, Double>? = null
        for (day in 0 until request.days) {
            if (!taken(day, MealSlot.LUNCH)) {
                val leftover = leftoverForLunch
                // Without leftovers or a lunch recipe, cook a dinner recipe for lunch rather than skip the meal.
                val lunch = leftover?.first
                    ?: pick(MealSlot.LUNCH, usable, meals, request, yesterdaysDinner, random)
                    ?: pick(MealSlot.DINNER, usable, meals, request, yesterdaysDinner, random, budgetSlot = MealSlot.LUNCH)
                if (lunch != null) {
                    meals += meal(lunch, day, MealSlot.LUNCH, request.targets, leftover = leftover != null, available = leftover?.second)
                }
            }
            leftoverForLunch = null

            val lockedDinner = meals.firstOrNull { it.day == day && it.slot == MealSlot.DINNER }
            val dinner = lockedDinner?.let { locked -> request.candidates.firstOrNull { it.recipeId == locked.recipeId } }
                ?: if (lockedDinner == null) pick(MealSlot.DINNER, usable, meals, request, yesterdaysDinner, random) else null
            if (dinner != null) {
                val eaten = lockedDinner ?: meal(dinner, day, MealSlot.DINNER, request.targets).also { meals += it }
                // Enough left for a lunch of at least half a serving?
                val remaining = dinner.servings - eaten.portions
                if (remaining >= 0.5) leftoverForLunch = dinner to remaining
            }
            yesterdaysDinner = dinner
        }
        return meals.sortedWith(compareBy({ it.day }, { it.slot.ordinal }))
    }

    private fun pick(
        slot: MealSlot,
        usable: List<PlanCandidate>,
        meals: List<PlannedMeal>,
        request: PlanRequest,
        yesterdaysDinner: PlanCandidate?,
        random: Random,
        /** The slot whose calorie share applies, when a recipe of one slot fills another. */
        budgetSlot: MealSlot = slot,
    ): PlanCandidate? = usable.filter { slot in it.slots && isAMeal(it, request.targets.kcal * budgetSlot.share) }.maxByOrNull { candidate ->
        score(
            candidate, budgetSlot, request,
            uses = meals.count { it.recipeId == candidate.recipeId && !it.leftover },
            sameTagAsYesterday = yesterdaysDinner != null && candidate.tags.firstOrNull() == yesterdaysDinner.tags.firstOrNull() &&
                candidate.tags.isNotEmpty(),
            jitter = random.nextDouble(),
        )
    }

    /** Higher is better. The weights are judgement calls, tuned so calorie fit and variety dominate. */
    private fun score(
        candidate: PlanCandidate,
        slot: MealSlot,
        request: PlanRequest,
        uses: Int,
        sameTagAsYesterday: Boolean,
        jitter: Double,
    ): Double {
        val budget = request.targets.kcal * slot.share
        val portions = portionsFor(candidate, budget)
        val calorieFit = 1 - (abs(candidate.kcal * portions - budget) / budget).coerceAtMost(1.0)
        val proteinFit = (candidate.proteinG * portions / (request.targets.proteinG * slot.share)).coerceAtMost(1.5)
        return 2.0 * calorieFit +
            2.0 * proteinFit +
            0.5 * candidate.onHandCount.coerceAtMost(3) +
            0.3 * candidate.offerCount.coerceAtMost(3) -
            3.0 * uses -
            (if (sameTagAsYesterday) 0.5 else 0.0) -
            (if (candidate.recipeId in request.recent) 1.0 else 0.0) -
            0.1 * (candidate.costPerServing ?: 0.0) * portions +
            0.3 * jitter
    }

    /**
     * The portion size that comes closest to [budget] calories, without exceeding
     * [available] servings. By default that is what one batch yields, but at least two:
     * a single-serving recipe such as a bowl of quark is simply made larger.
     */
    fun portionsFor(candidate: PlanCandidate, budget: Double, available: Double = maxOf(candidate.servings.toDouble(), 2.0)): Double =
        portionSteps.filter { it <= available }.ifEmpty { listOf(available) }
            .minBy { abs(candidate.kcal * it - budget) }

    /**
     * False for recipes that cannot come near [budget] even at the largest portion, such
     * as a side dish or a rub: planning them as a meal would leave the day far short.
     */
    private fun isAMeal(candidate: PlanCandidate, budget: Double): Boolean =
        candidate.kcal * portionsFor(candidate, budget) >= 0.5 * budget

    private fun meal(
        candidate: PlanCandidate,
        day: Int,
        slot: MealSlot,
        targets: DailyTargets,
        leftover: Boolean = false,
        available: Double? = null,
    ): PlannedMeal {
        val portions = available?.let { portionsFor(candidate, targets.kcal * slot.share, it) }
            ?: portionsFor(candidate, targets.kcal * slot.share)
        return PlannedMeal(
            day = day,
            slot = slot,
            recipeId = candidate.recipeId,
            title = candidate.title,
            portions = portions,
            kcal = candidate.kcal * portions,
            proteinG = candidate.proteinG * portions,
            leftover = leftover,
        )
    }
}
