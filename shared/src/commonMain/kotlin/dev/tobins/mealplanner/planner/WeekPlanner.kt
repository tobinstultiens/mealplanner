package dev.tobins.mealplanner.planner

import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlanPriority
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
    /** Euros per serving with the week's offers taken off; null when unknown. */
    val costPerServing: Double? = null,
    /** 1 to 5, or null when not rated (counted as an average 3). */
    val rating: Int? = null,
    /** Grams of fibre per serving. A food without a fibre value counts as none; the recipe is still planned. */
    val fibreG: Double = 0.0,
    /**
     * The fresh ingredients it uses that are not on hand and whose pack is known, for [WeekPlanner] to prefer a
     * recipe that uses up the rest of a pack another meal of the week opened. Empty: the recipe counts as
     * opening no packs.
     */
    val freshUses: List<FreshUse> = emptyList(),
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
    /** Recipes to use every day for breakfast or snack, instead of the planner's choice. */
    val routines: Map<MealSlot, Long> = emptyMap(),
    /** How large a meal may be: the profile's range. */
    val portions: PortionRange = PortionRange(),
    /** How much the planner makes of the rest of fresh packs ([PackWeights]). */
    val packWeights: PackWeights = PackWeights(),
    /** What the profile has the planner weigh most ([WeekPlanner]'s score). */
    val priority: PlanPriority = PlanPriority.BALANCED,
    /** Same seed, same plan. */
    val seed: Long = 0,
) {
    /** The typical price of a calorie among the candidates: what "cheap" and "dear" are measured against. */
    val medianCostPerKcal: Double? by lazy {
        candidates.mapNotNull { c -> c.costPerServing?.takeIf { c.kcal > 0 }?.let { it / c.kcal } }.sorted()
            .let { if (it.isEmpty()) null else it[it.size / 2] }
    }
}

/**
 * Fills a week of meals so that each day lands near the calorie target and reaches
 * the protein and fibre targets where the recipes allow it.
 *
 * - Breakfast and snack are routines: one choice, repeated all week. A pinned routine
 *   is used as long as it is plannable; otherwise the planner chooses.
 * - Dinner is a different recipe each day; what a dinner recipe yields beyond the
 *   portion eaten becomes the next day's lunch.
 * - Portions are adjusted in quarter servings to fit the slot's share of the calories, within
 *   the request's [PortionRange]; a recipe too light to fill half its share at the largest
 *   portion is not a meal there.
 * - A lunch with neither leftovers nor a lunch recipe is cooked from a dinner recipe.
 * - A breakfast or snack slot with no suitable recipe stays empty.
 * - An excluded recipe is never planned, not even as a pinned routine or as leftovers;
 *   only locked meals keep it.
 */
object WeekPlanner {
    fun plan(request: PlanRequest): List<PlannedMeal> {
        val random = Random(request.seed)
        val meals = request.locked.map { it.copy(locked = true) }.toMutableList()
        val usable = request.candidates.filter { it.recipeId !in request.excluded && it.kcal > 0 }
        fun taken(day: Int, slot: MealSlot) = meals.any { it.day == day && it.slot == slot }

        // Routines: the best breakfast and snack, every day.
        for (slot in listOf(MealSlot.BREAKFAST, MealSlot.SNACK)) {
            // An excluded pin is being swapped out on a reroll: the planner chooses instead.
            val pinned = request.routines[slot]?.takeIf { it !in request.excluded }
                ?.let { id -> request.candidates.firstOrNull { it.recipeId == id } }
            val routine = pinned ?: usable.filter { slot in it.slots }
                .maxByOrNull { score(it, slot, request, uses = 0, sameTagAsYesterday = false, jitter = random.nextDouble()) }
                ?: continue
            for (day in 0 until request.days) {
                if (!taken(day, slot)) meals += meal(routine, day, slot, request)
            }
        }

        val byId = request.candidates.associateBy { it.recipeId }
        var yesterdaysDinner: PlanCandidate? = null
        // What yesterday's dinner left over: the recipe and how many servings remain.
        var leftoverForLunch: Pair<PlanCandidate, Double>? = null
        for (day in 0 until request.days) {
            if (!taken(day, MealSlot.LUNCH)) {
                // Leftovers of an excluded recipe are what a reroll is replacing, so they are not offered again.
                val leftover = leftoverForLunch?.takeIf { it.first.recipeId !in request.excluded }
                // Without leftovers or a lunch recipe, cook a dinner recipe for lunch rather than skip the meal.
                val lunch = leftover?.first
                    ?: pick(MealSlot.LUNCH, day, usable, meals, byId, request, yesterdaysDinner, random)
                    ?: pick(MealSlot.DINNER, day, usable, meals, byId, request, yesterdaysDinner, random, budgetSlot = MealSlot.LUNCH)
                if (lunch != null) {
                    meals += meal(lunch, day, MealSlot.LUNCH, request, leftover = leftover != null, available = leftover?.second)
                }
            }
            leftoverForLunch = null

            val lockedDinner = meals.firstOrNull { it.day == day && it.slot == MealSlot.DINNER }
            val dinner = lockedDinner?.let { locked -> request.candidates.firstOrNull { it.recipeId == locked.recipeId } }
                ?: if (lockedDinner == null) pick(MealSlot.DINNER, day, usable, meals, byId, request, yesterdaysDinner, random) else null
            if (dinner != null) {
                val eaten = lockedDinner ?: meal(dinner, day, MealSlot.DINNER, request).also { meals += it }
                val remaining = dinner.servings - eaten.portions
                if (remaining >= PortionRange.LEAST_LEFTOVER) leftoverForLunch = dinner to remaining
            }
            yesterdaysDinner = dinner
        }
        return meals.sortedWith(compareBy({ it.day }, { it.slot.ordinal }))
    }

    private fun pick(
        slot: MealSlot,
        day: Int,
        usable: List<PlanCandidate>,
        meals: List<PlannedMeal>,
        byId: Map<Long, PlanCandidate>,
        request: PlanRequest,
        yesterdaysDinner: PlanCandidate?,
        random: Random,
        /** The slot whose calorie share applies, when a recipe of one slot fills another: the slot being filled. */
        budgetSlot: MealSlot = slot,
    ): PlanCandidate? {
        // The packs the meals before this one opened; none to look at when no recipe says what it uses.
        val packs = if (request.candidates.any { it.freshUses.isNotEmpty() }) OpenPacks.before(meals, byId, day, budgetSlot) else null
        return usable.filter { slot in it.slots && isAMeal(it, budgetSlot, request.targets, request.portions) }.maxByOrNull { candidate ->
            score(
                candidate, budgetSlot, request,
                uses = meals.count { it.recipeId == candidate.recipeId && !it.leftover },
                sameTagAsYesterday = yesterdaysDinner != null && candidate.tags.firstOrNull() == yesterdaysDinner.tags.firstOrNull() &&
                    candidate.tags.isNotEmpty(),
                jitter = random.nextDouble(),
                packs = packs?.let { open ->
                    val servings = portionsFor(candidate, budgetSlot, request.targets, request.portions)
                    request.packWeights.reuse * open.reusable(day, candidate.freshUses, servings) -
                        request.packWeights.waste * open.wasted(day, candidate.freshUses, servings)
                } ?: 0.0,
            )
        }
    }

    /**
     * Higher is better. The weights are judgement calls, tuned so calorie fit and variety dominate.
     * The request's [PlanPriority] raises the weight of protein, fibre or price ([Weights]); calorie fit
     * and the penalties for repeats are the same under every priority, so that none plans days far off
     * the calorie target or the same dinner all week.
     */
    private fun score(
        candidate: PlanCandidate,
        slot: MealSlot,
        request: PlanRequest,
        uses: Int,
        sameTagAsYesterday: Boolean,
        jitter: Double,
        /** The pack term ([PackWeights]): euros of open packs the recipe uses up, less euros of rest it leaves by opening new ones. */
        packs: Double = 0.0,
    ): Double {
        val budget = request.targets.kcal * slot.share
        val portions = portionsFor(candidate, slot, request.targets, request.portions)
        val calorieFit = 1 - (abs(candidate.kcal * portions - budget) / budget).coerceAtMost(1.0)
        val proteinFit = (candidate.proteinG * portions / (request.targets.proteinG * slot.share)).coerceAtMost(1.5)
        val weights = Weights.of(request.priority)
        return 2.0 * calorieFit +
            weights.protein * proteinFit +
            weights.fibre * fibreFit(candidate, slot, portions, request.targets) +
            0.5 * candidate.onHandCount.coerceAtMost(3) +
            weights.savings * savings(candidate, request) +
            packs +
            0.4 * ((candidate.rating ?: 3) - 3) -
            3.0 * uses -
            (if (sameTagAsYesterday) 0.5 else 0.0) -
            (if (candidate.recipeId in request.recent) 1.0 else 0.0) +
            0.3 * jitter
    }

    /**
     * How far [portions] of [candidate] go towards [slot]'s share of the fibre target, as protein's fit
     * does: capped at 1.5, so that fibre beyond one and a half times the meal's share adds nothing. Without
     * a fibre target (targets from before fibre counted) fibre does not count.
     */
    private fun fibreFit(candidate: PlanCandidate, slot: MealSlot, portions: Double, targets: DailyTargets): Double {
        val target = targets.fibreG?.takeIf { it > 0 } ?: return 0.0
        return (candidate.fibreG * portions / (target * slot.share)).coerceAtMost(1.5)
    }

    /**
     * How much protein fit, fibre fit and [savings] count in [score] (issue 223). A priority raises its
     * own weight and lowers none, so that a price plan still prefers the higher-protein, higher-fibre
     * recipe among equally cheap ones.
     */
    private data class Weights(val protein: Double, val fibre: Double, val savings: Double) {
        companion object {
            private val BALANCED = Weights(protein = 2.0, fibre = 1.0, savings = 1.0)

            fun of(priority: PlanPriority): Weights = when (priority) {
                PlanPriority.BALANCED -> BALANCED
                PlanPriority.PROTEIN -> BALANCED.copy(protein = 3.0)
                PlanPriority.FIBRE -> BALANCED.copy(fibre = 2.0)
                // What a meal's price adds or takes off goes from +0.6 to -1.2 to +1.2 to -2.4.
                PlanPriority.PRICE -> BALANCED.copy(savings = 2.0)
            }
        }
    }

    /**
     * Rewards cheap meals: up to +0.6 for one whose calories cost half the median or less,
     * down to -1.2 for one at double the median. Offers lower the cost, so they count here.
     * Per calorie, so a bigger portion is not penalised for costing more. Without costs,
     * each ingredient on offer counts a little instead.
     */
    private fun savings(candidate: PlanCandidate, request: PlanRequest): Double {
        val median = request.medianCostPerKcal
        val cost = candidate.costPerServing
        if (median == null || cost == null || candidate.kcal <= 0) return 0.3 * candidate.offerCount.coerceAtMost(3)
        val relative = cost / candidate.kcal / median
        return -(relative - 1).coerceIn(-0.5, 1.0) * 1.2
    }

    /**
     * The portion of [candidate] that comes closest to [slot]'s share of [targets]' calories, among
     * those [range] allows there ([PortionRange.steps]); with [available], as for leftovers, no more
     * than that many servings. Every meal the planner or the user puts in a plan is sized here.
     */
    fun portionsFor(candidate: PlanCandidate, slot: MealSlot, targets: DailyTargets, range: PortionRange, available: Double? = null): Double {
        val budget = targets.kcal * slot.share
        val steps = if (available == null) range.steps(slot) else range.steps(slot, available)
        return steps.minBy { abs(candidate.kcal * it - budget) }
    }

    /**
     * False for recipes that cannot reach half the slot's calories even at the largest portion
     * [range] allows, such as a side dish or a rub: planning them as a meal would leave the day
     * far short, or need a portion nobody eats.
     */
    private fun isAMeal(candidate: PlanCandidate, slot: MealSlot, targets: DailyTargets, range: PortionRange): Boolean =
        candidate.kcal * portionsFor(candidate, slot, targets, range) >= 0.5 * (targets.kcal * slot.share)

    /** True when the planner would take [candidate] as a [slot] meal for someone with [targets] and [range]. */
    fun fitsAsMeal(candidate: PlanCandidate, slot: MealSlot, targets: DailyTargets, range: PortionRange): Boolean =
        slot in candidate.slots && candidate.kcal > 0 && isAMeal(candidate, slot, targets, range)

    private fun meal(
        candidate: PlanCandidate,
        day: Int,
        slot: MealSlot,
        request: PlanRequest,
        leftover: Boolean = false,
        available: Double? = null,
    ): PlannedMeal {
        val portions = portionsFor(candidate, slot, request.targets, request.portions, available)
        return PlannedMeal(
            day = day,
            slot = slot,
            recipeId = candidate.recipeId,
            title = candidate.title,
            portions = portions,
            kcal = candidate.kcal * portions,
            proteinG = candidate.proteinG * portions,
            leftover = leftover,
            fibreG = candidate.fibreG * portions,
        )
    }
}
