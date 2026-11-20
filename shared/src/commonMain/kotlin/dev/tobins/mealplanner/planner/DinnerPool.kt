package dev.tobins.mealplanner.planner

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.SuggestionGround
import kotlin.math.roundToLong

/**
 * The week's dinners chosen by hand from a pool (issue 255): which recipes to suggest, and on which days the chosen ones
 * go. Both by the planner's own measures, so that the screen and [WeekPlanner] never disagree on what is good: a
 * suggestion is rated as the planner rates a dinner ([WeekPlanner.rateDinner]), and the days are those on which the
 * packs the dinners open ([OpenPacks]) leave the least rest.
 */
object DinnerPool {
    /**
     * The day every chosen dinner is cooked on as [OpenPacks] counts them while they are a pool: they have no days yet,
     * and the days [place] gives them keep dinners that share a pack within the days it keeps where it can.
     */
    private const val POOL_DAY = 0

    /** A recipe rated this or higher is a favourite. */
    const val FAVOURITE_RATING = 4

    /** A recipe last eaten this many whole weeks before the week, or longer ago, is a favourite again: not had for a while. */
    const val UNEATEN_WEEKS = 3

    /** A saving or a pack's rest worth less than this many euros a serving is no reason to suggest a recipe. */
    private const val LEAST_EUROS = 0.05

    /**
     * A recipe as the pool rates it: [score] as the planner would rate it as a dinner, its [grounds] and what they rest
     * on, and [meal], the dinner it would be (day 0, as it has none yet). [packRests] are the fresh ingredients whose rest
     * in a pack a chosen dinner opens it would use, each with the latest chosen dinner that uses that ingredient.
     */
    data class Rated(
        val candidate: PlanCandidate,
        val score: Double,
        val grounds: List<SuggestionGround>,
        val meal: PlannedMeal,
        val packRests: List<Pair<Long, PlanCandidate>>,
        val weeksSinceEaten: Int?,
    )

    /** A page of suggestions, and whether there are [more] after it. */
    data class Page(val suggestions: List<Rated>, val more: Boolean)

    /**
     * What the pool is rated against: the planner's [request] (its candidates, targets, portions, recent meals, pack
     * weights and priority), the week's [kept] meals, which stay as they are, the dinners [picked] so far in the order
     * they were, and for each recipe the whole weeks since it was last eaten ([weeksSinceEaten]). A recipe not there,
     * and in no meal of the week nor chosen for it, was never eaten: it is one not tried yet.
     */
    class Week(
        val request: PlanRequest,
        val kept: List<PlannedMeal>,
        val picked: List<PlanCandidate>,
        val weeksSinceEaten: Map<Long, Int> = emptyMap(),
    ) {
        /** The chosen dinners as meals, for counting repeats as the planner does ([WeekPlanner.uses]). */
        private val pickedMeals = picked.map { dinnerOf(it) }

        /** The packs the chosen dinners open, cooked in the order they were chosen. */
        private val open = OpenPacks().apply { picked.zip(pickedMeals).forEach { (pick, meal) -> cook(POOL_DAY, pick.freshUses, meal.portions) } }

        fun dinnerOf(candidate: PlanCandidate): PlannedMeal =
            WeekPlanner.mealOf(candidate, POOL_DAY, MealSlot.DINNER, request.targets, request.portions)

        /** [candidate] rated as one more dinner of the week, with its grounds. */
        fun rate(candidate: PlanCandidate): Rated {
            val meal = dinnerOf(candidate)
            val uses = WeekPlanner.uses(candidate.recipeId, kept + pickedMeals)
            val score = WeekPlanner.rateDinner(candidate, request, uses, packs = WeekPlanner.packTerm(candidate, open, POOL_DAY, MealSlot.DINNER, request))
            val rests = candidate.freshUses
                .filter { use -> open.reusable(POOL_DAY, listOf(use), meal.portions) >= LEAST_EUROS }
                .mapNotNull { use -> picked.lastOrNull { pick -> pick.freshUses.any { it.ingredientId == use.ingredientId } }?.let { use.ingredientId to it } }
            val weeks = weeksSinceEaten[candidate.recipeId]
            val grounds = listOfNotNull(
                SuggestionGround.ON_OFFER.takeIf { isOnOffer(candidate) },
                SuggestionGround.USES_WHAT_IS_THERE.takeIf { candidate.onHandCount > 0 || rests.isNotEmpty() },
                SuggestionGround.FAVOURITE.takeIf { (candidate.rating ?: 0) >= FAVOURITE_RATING || (weeks ?: -1) >= UNEATEN_WEEKS },
                SuggestionGround.NOT_TRIED_YET.takeIf { weeks == null && uses == 0 },
            )
            return Rated(candidate, score, grounds, meal, rests, weeks)
        }

        /**
         * The next [count] suggestions for the week's dinners, leaving out the recipes chosen, those [hidden] this week and
         * those [seen] on the screen already ("Show others"). The recipes are the request's candidates that the planner
         * would take as a dinner and that have a ground. A recipe of a batch prepped this week comes only when no other is
         * left, as the planner plans one again only then. They are mixed from the grounds in turn ([mixed]), each ground's
         * best first.
         */
        fun suggest(hidden: Set<Long>, seen: Set<Long>, count: Int): Page {
            val chosen = picked.mapTo(HashSet()) { it.recipeId }
            val fitting = request.candidates.filter {
                it.recipeId !in chosen && it.recipeId !in hidden && it.recipeId !in seen &&
                    WeekPlanner.fitsAsMeal(it, MealSlot.DINNER, request.targets, request.portions)
            }
            val prepped = WeekPlanner.prepped(kept)
            val pool = fitting.filter { it.recipeId !in prepped }.ifEmpty { fitting }
            val ranked = mixed(best(pool.map(::rate)))
            return Page(ranked.take(count), ranked.size > count)
        }

        /** [candidates] rated, best first, leaving out those [seen] already: what a search finds, whatever its grounds. */
        fun search(candidates: List<PlanCandidate>, seen: Set<Long>, count: Int): Page {
            val ranked = best(candidates.filter { it.recipeId !in seen }.map(::rate))
            return Page(ranked.take(count), ranked.size > count)
        }
    }

    /** Highest score first; equal scores keep their order. */
    private fun best(rated: List<Rated>): List<Rated> = rated.sortedByDescending { it.score }

    /**
     * [ranked] (best first) mixed from the grounds in turn, in [SuggestionGround]'s order: the first of each ground not
     * taken yet ([firstOf]), then the next of each, so that a page holds recipes of every ground that has some, and no
     * ground crowds out the others, however many recipes it has. A recipe of several grounds is taken in the turn of the
     * first; one without a ground is left out.
     */
    fun mixed(ranked: List<Rated>): List<Rated> {
        val left = ranked.filter { it.grounds.isNotEmpty() }.toMutableList()
        val mixed = ArrayList<Rated>(left.size)
        while (left.isNotEmpty()) {
            for (ground in SuggestionGround.entries) {
                val next = firstOf(ground, left) ?: continue
                mixed += next
                left.remove(next)
            }
        }
        return mixed
    }

    /**
     * The first of [ranked] in [ground]'s turn: the best by the planner's score, except among recipes not tried yet, which
     * go by their rating first (a recipe rated without being planned; unrated counts as 3, as the planner counts it), as
     * the owner's word on a recipe weighs more than how well it fits the week. Equals keep their order.
     */
    private fun firstOf(ground: SuggestionGround, ranked: List<Rated>): Rated? {
        val ofGround = ranked.filter { ground in it.grounds }
        if (ground != SuggestionGround.NOT_TRIED_YET) return ofGround.firstOrNull()
        return ofGround.minWithOrNull(compareByDescending<Rated> { it.candidate.rating ?: 3 }.thenByDescending { it.score })
    }

    /**
     * Whether an offer of the week makes [candidate] cheaper: one of its ingredients is on offer at the account's stores,
     * and where both its prices are known, the offers take at least [LEAST_EUROS] off a serving. An offer of a pinch of
     * something is no reason to cook it.
     */
    private fun isOnOffer(candidate: PlanCandidate): Boolean {
        if (candidate.offerCount == 0) return false
        val regular = candidate.regularCostPerServing ?: return true
        val withOffers = candidate.costPerServing ?: return true
        return regular - withOffers >= LEAST_EUROS
    }

    /**
     * Puts each of [picked] on one of [days] (the free dinner days, 0 being the week's first) as a locked dinner at the
     * portion the planner gives it, with its leftover lunch on the next day when the recipe leaves enough
     * ([WeekPlanner.leftoverServings]) and that lunch is neither one of [kept] nor past the week's [weekDays]. Every
     * chosen dinner gets a day; [kept] meals stay as they are. Of all the ways to put them on days, the one taken is the
     * one whose packs leave the least rest ([OpenPacks.restOf], over the kept meals not skipped too, by [known] recipes),
     * so dinners that share a fresh pack land within the days it keeps; then the one with the fewest leftovers that have
     * no lunch to go to; then the one with the fewest dinners of the same first tag as the day before, as the planner
     * avoids; then the earliest days in the order the dinners were chosen.
     */
    fun place(
        picked: List<PlanCandidate>,
        days: List<Int>,
        kept: List<PlannedMeal>,
        known: Map<Long, PlanCandidate>,
        request: PlanRequest,
        weekDays: Int = request.days,
    ): List<PlannedMeal> {
        require(picked.size <= days.size) { "${picked.size} dinners for ${days.size} days" }
        val eaten = kept.filter { it.status != MealStatus.SKIPPED }
        val lunchTaken = kept.filter { it.slot == MealSlot.LUNCH }.mapTo(HashSet()) { it.day }
        val tagOf = (known + picked.associateBy { it.recipeId }).mapValues { (_, c) -> c.tags.firstOrNull() }
        val keptDinners = kept.filter { it.slot == MealSlot.DINNER }.associate { it.day to it.recipeId }

        fun meals(onDays: List<Int>): List<PlannedMeal> = picked.zip(onDays).flatMap { (pick, day) ->
            val dinner = WeekPlanner.mealOf(pick, day, MealSlot.DINNER, request.targets, request.portions).copy(locked = true)
            val lunch = WeekPlanner.leftoverServings(pick, dinner)
                ?.takeIf { day + 1 < weekDays && day + 1 !in lunchTaken }
                ?.let { WeekPlanner.mealOf(pick, day + 1, MealSlot.LUNCH, request.targets, request.portions, leftover = true, available = it) }
            listOfNotNull(dinner, lunch)
        }

        fun cost(onDays: List<Int>): Triple<Long, Int, Int> {
            val placed = meals(onDays)
            val rest = OpenPacks.restOf(eaten + placed, known + picked.associateBy { it.recipeId })
            val lost = picked.zip(onDays).count { (pick, day) ->
                val dinner = placed.first { it.day == day && it.slot == MealSlot.DINNER }
                WeekPlanner.leftoverServings(pick, dinner) != null && placed.none { it.day == day + 1 && it.slot == MealSlot.LUNCH }
            }
            val dinners = keptDinners + picked.zip(onDays).associate { (pick, day) -> day to pick.recipeId }
            val sameTag = dinners.count { (day, id) ->
                val tag = tagOf[id]
                tag != null && dinners[day - 1]?.let { tagOf[it] } == tag
            }
            // In cents, so that sums of the same rest in another order are equal.
            return Triple((rest * 100).roundToLong(), lost, sameTag)
        }

        // Every way to give each chosen dinner a day of its own, the earliest days in the order chosen first.
        fun ways(chosen: List<Int>): Sequence<List<Int>> =
            if (chosen.size == picked.size) sequenceOf(chosen) else days.asSequence().filter { it !in chosen }.flatMap { ways(chosen + it) }
        // The first of equals: minWith keeps the first minimum it meets.
        val best = ways(emptyList()).map { it to cost(it) }.minWith(compareBy({ it.second.first }, { it.second.second }, { it.second.third }))
        return meals(best.first)
    }
}
