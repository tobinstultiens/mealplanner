package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.DinnerPick
import dev.tobins.mealplanner.api.DinnerSuggestion
import dev.tobins.mealplanner.api.DinnerSuggestions
import dev.tobins.mealplanner.api.HiddenRecipe
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PackRestUse
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PlanningTotals
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.SuggestionGround
import dev.tobins.mealplanner.api.SuggestionOffer
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeekPlanning
import dev.tobins.mealplanner.planner.DinnerPool
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.planner.PlanRequest
import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.SelectPicks
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.recipes.RecipeLines
import dev.tobins.mealplanner.server.recipes.MyRecipes
import dev.tobins.mealplanner.server.recipes.RecipeCandidates
import dev.tobins.mealplanner.server.serverToday
import dev.tobins.mealplanner.server.writing
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * One account's planning screen (issue 255): the dinners chosen for a week from the suggestions before they are put on
 * days, the recipes hidden for a week ("Not this week"), the suggestions, and planning the week with the chosen dinners
 * ([done]). The choices are stored as they are made (`planPick`), so leaving the screen or the app loses nothing; the
 * hidden recipes change nothing in the rotation. Both are forgotten once their week is over.
 */
class PlanningRepository(
    private val db: Database,
    private val userId: Long,
    private val weekMeals: WeekMeals,
    private val keptMeals: KeptMeals,
    private val weeks: WeekPlans,
    private val costs: WeekCosts,
    private val view: WeekView,
    private val recipes: RecipeCandidates,
    private val myRecipes: MyRecipes,
    private val stock: StockRepository,
    /** Today as the household sees it: which weeks are over. */
    private val today: () -> LocalDate = ::serverToday,
) {
    private val q = db.planQueries

    fun get(weekStart: String): WeekPlanning = planning(parseWeekStart(weekStart))

    /**
     * The next [count] suggestions for the week's dinners ([DinnerPool.Week.suggest]), leaving out the recipes [seen] on
     * the screen already, those chosen and those hidden. With a [query], the account's recipes that are meals and whose
     * title has each of its words, whatever their grounds, chosen and hidden ones too ("Something else",
     * [DinnerPool.Week.search]).
     */
    fun suggestions(weekStart: String, seen: Set<Long>, count: Int, query: String? = null): DinnerSuggestions {
        require(count in 1..MAX_SUGGESTIONS) { "count must be 1 to $MAX_SUGGESTIONS" }
        val start = parseWeekStart(weekStart)
        val week = Week(start)
        val page = if (query == null) {
            week.pool.suggest(week.hidden.mapTo(HashSet()) { it.recipeId }, seen, count)
        } else {
            val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            week.pool.search(week.every.filter { recipe -> words.all { it in recipe.title.lowercase() } }, seen, count)
        }
        return DinnerSuggestions(Details(start).of(page.suggestions), page.more)
    }

    /**
     * Chooses one of my recipes for the week's dinners, in place of hiding it; choosing it again changes nothing. Any of
     * my recipes that is a meal with amounts, as for a meal chosen by hand ([MealChanges.choose]), whatever its
     * calories: nothing is refused for those. A [ConflictException] when every day still to choose for has a dinner
     * chosen already.
     */
    fun pick(weekStart: String, recipeId: Long): WeekPlanning {
        val start = parseWeekStart(weekStart)
        val recipe = myRecipes.requireMine(recipeId)
        if (recipe.kind == RecipeKind.COMPONENT.name) throw ConflictException("This recipe is a building block, not a meal")
        recipes.candidate(recipeId) ?: throw ConflictException("This recipe has no amounts to plan with")
        db.writing {
            forgetEndedWeeks()
            val picked = rows(start).filter { it.refused == 0L }
            if (picked.none { it.recipeId == recipeId }) {
                val open = openDays(start, keptMeals.kept(start, weekMeals.of(start))).size
                if (picked.size >= open) throw ConflictException(noDayLeft(open))
                q.upsertPick(userId, start.toString(), recipeId, 0)
            }
        }
        return planning(start)
    }

    /** Takes a dinner out of the week's chosen ones; nothing when it is not one of them. */
    fun unpick(weekStart: String, recipeId: Long): WeekPlanning {
        val start = parseWeekStart(weekStart)
        db.writing { q.deletePick(userId, start.toString(), recipeId, 0) }
        return planning(start)
    }

    /**
     * Hides one of my recipes from the week's suggestions ("Not this week"), for that week only: its rotation and rating
     * stay as they are. A chosen dinner hidden is no longer chosen.
     */
    fun hide(weekStart: String, recipeId: Long): WeekPlanning {
        val start = parseWeekStart(weekStart)
        myRecipes.requireMine(recipeId)
        db.writing {
            forgetEndedWeeks()
            q.upsertPick(userId, start.toString(), recipeId, 1)
        }
        return planning(start)
    }

    /** Shows a hidden recipe in the week's suggestions again; nothing when it is not hidden. */
    fun unhide(weekStart: String, recipeId: Long): WeekPlanning {
        val start = parseWeekStart(weekStart)
        db.writing { q.deletePick(userId, start.toString(), recipeId, 1) }
        return planning(start)
    }

    /**
     * Plans the week with the dinners chosen for it ("Done", or "Fill the rest"): each goes on a day of its own
     * ([DinnerPool.place]), locked as a recipe chosen by hand, with its leftover lunch, and the planner fills everything
     * else as [WeekPlans.generate] does, keeping what that keeps, with none of the recipes hidden for the week: "Not
     * this week" says the user does not want it this week. Then the dinners are no longer chosen; the hidden recipes stay
     * hidden for the week. With nothing chosen nor hidden it is [WeekPlans.generate]. A [ConflictException] when more
     * dinners are chosen than there are days to put them on, or one can no longer be planned; nothing changes then.
     */
    fun done(weekStart: String, seed: Long = Random.nextLong()): WeekPlan {
        val start = parseWeekStart(weekStart)
        db.writing {
            forgetEndedWeeks()
            val (picked, hidden) = rows(start).partition { it.refused == 0L }
            weeks.fillWeek(start, seed, hidden = hidden.mapTo(HashSet()) { it.recipeId }) { kept ->
                if (picked.isEmpty()) return@fillWeek emptyList()
                val every = weeks.candidates(start, everyMeal = true).associateBy { it.recipeId }
                val dinners = picked.map { row -> candidateOf(row.recipeId, every) ?: throw ConflictException("\"${row.title}\" has no amounts to plan with: take it out first") }
                val days = openDays(start, kept)
                if (dinners.size > days.size) throw ConflictException(noDayLeft(days.size, chosen = dinners.size))
                DinnerPool.place(dinners, days, kept, every, weeks.request(start, locked = kept, seed = seed))
            }
            q.deletePicksPlaced(userId, start.toString())
        }
        return view.of(start)
    }

    /**
     * The week starting [start] as the planning screen shows it: the days still to choose a dinner for, the dinners chosen,
     * the hidden recipes and the strip's totals. The totals are of the week's meals that stay and the chosen dinners on the
     * days [DinnerPool.place] gives them now, with their leftover lunches: the meals [done] would store for them, with the
     * numbers the Plan gives them ([WeekCosts.withNumbers], [WeekCosts.costOfWeek], [WeekCosts.sums]).
     * Chosen dinners beyond the days left count in no total until one is taken out.
     */
    private fun planning(start: LocalDate): WeekPlanning {
        val week = Week(start)
        val costOf = costs.costsIn(start)
        val days = openDays(start, week.kept)
        val placed = DinnerPool.place(week.picked.take(days.size), days, week.kept, week.byId, week.request)
        val counted = costs.withNumbers(start, week.kept, placed, costOf)
        val (cost, costWithoutOffers) = costs.costOfWeek(counted, costOf)
        val sums = costs.sums(counted)
        val eaten = counted.filter { it.status != MealStatus.SKIPPED }
        val targets = week.request.targets
        return WeekPlanning(
            weekStart = start.toString(),
            targets = targets,
            openDays = days,
            picks = week.chosen.map { row ->
                val candidate = week.picked.firstOrNull { it.recipeId == row.recipeId }
                    ?: return@map DinnerPick(row.recipeId, row.title, 0.0, 0.0, 0.0, plannable = false)
                val dinner = week.pool.dinnerOf(candidate)
                val price = costOf(candidate.recipeId)
                DinnerPick(
                    candidate.recipeId, candidate.title, dinner.portions, dinner.kcal, dinner.proteinG, candidate.fibreG * dinner.portions,
                    price?.let { it.perServingWithOffers * dinner.portions }, price?.let { it.perServing * dinner.portions },
                )
            },
            notThisWeek = week.hidden.map { HiddenRecipe(it.recipeId, it.title) },
            totals = PlanningTotals(
                meals = eaten.size,
                cost = cost,
                costWithoutOffers = costWithoutOffers,
                kcal = sums.kcal,
                proteinG = sums.proteinG,
                fibreG = sums.fibreG,
                targetKcal = eaten.sumOf { targets.kcal * it.slot.share },
                targetProteinG = eaten.sumOf { targets.proteinG * it.slot.share },
                targetFibreG = targets.fibreG?.let { fibre -> eaten.sumOf { fibre * it.slot.share } },
            ),
        )
    }

    /**
     * What the week starting [start] is planned around: its meals that stay ([KeptMeals.kept]), the planner's
     * [request] with its candidates, [every] meal of mine as a candidate, the dinners [picked] and the recipes [hidden],
     * and the [pool] they make.
     */
    private inner class Week(start: LocalDate) {
        val kept: List<PlannedMeal> = keptMeals.kept(start, weekMeals.of(start))
        val request: PlanRequest = weeks.request(start, locked = kept, seed = 0)
        val every: List<PlanCandidate> = weeks.candidates(start, everyMeal = true)
        val byId: Map<Long, PlanCandidate> = every.associateBy { it.recipeId }
        private val rows = rows(start)
        val hidden = rows.filter { it.refused != 0L }
        /** The rows of the dinners chosen, also one whose recipe can no longer be planned, which the screen lists to take out. */
        val chosen = rows.filter { it.refused == 0L }
        /** The dinners chosen that can be planned, as the planner sees them. */
        val picked: List<PlanCandidate> = chosen.mapNotNull { candidateOf(it.recipeId, byId) }
        val pool = DinnerPool.Week(request, kept, picked, weeksSinceEaten(start))
    }

    /**
     * What a suggestion card says of each ground, for the week starting [start]: the offers at the account's stores and
     * what each takes off a serving, what is on hand, the rests of packs, the rating and how long ago it was eaten.
     */
    private inner class Details(start: LocalDate) {
        private val costOf = costs.costsIn(start)
        private val prices = costs.prices(start)
        private val offers = costs.bestOffers(start)
        private val onHand = stock.onHandFor(start)
        private val names = recipes.ingredients.list().associate { it.id to it.name }

        fun of(rated: List<DinnerPool.Rated>): List<DinnerSuggestion> {
            val lines = recipes.linesWithIngredients(rated.map { it.candidate.recipeId })
            return rated.map { suggestion ->
                val candidate = suggestion.candidate
                val recipe = lines[candidate.recipeId]
                val ingredients = recipe?.lines.orEmpty().mapNotNull { it.second?.id }.distinct()
                val cost = costOf(candidate.recipeId)
                DinnerSuggestion(
                    recipeId = candidate.recipeId,
                    title = candidate.title,
                    grounds = suggestion.grounds,
                    portions = suggestion.meal.portions,
                    kcal = suggestion.meal.kcal,
                    proteinG = suggestion.meal.proteinG,
                    fibreG = candidate.fibreG * suggestion.meal.portions,
                    costPerServing = cost?.perServingWithOffers,
                    savingPerServing = cost?.let { it.perServing - it.perServingWithOffers },
                    offers = if (SuggestionGround.ON_OFFER !in suggestion.grounds || recipe == null) emptyList() else offersOf(recipe, ingredients),
                    onHand = ingredients.filter { it in onHand }.mapNotNull { names[it] },
                    packRests = suggestion.packRests.mapNotNull { (id, pick) -> names[id]?.let { PackRestUse(it, pick.title) } },
                    rating = candidate.rating,
                    weeksSinceEaten = suggestion.weeksSinceEaten,
                )
            }
        }

        /**
         * The offers [recipe] uses, each with what it alone takes off a serving, the largest saving first. One that saves
         * less than a cent, such as one whose deal can't be read, is left out: it is no reason to cook the recipe.
         */
        private fun offersOf(recipe: RecipeLines, ingredients: List<Long>): List<SuggestionOffer> =
            ingredients.mapNotNull { id ->
                val offer = offers[id] ?: return@mapNotNull null
                val alone = Pricing(prices.perKg, prices.discounts.filterKeys { it == id }).cost(recipe.lines, recipe.servings)
                SuggestionOffer(names[id].orEmpty(), offer.store, offer.title, offer.deal, alone?.let { it.perServing - it.perServingWithOffers })
            }.filter { (it.savingPerServing ?: LEAST_SAVING) >= LEAST_SAVING }.sortedByDescending { it.savingPerServing ?: 0.0 }
    }

    /**
     * The days of the week starting [start] that a chosen dinner can go on: today and the days after it, without a dinner
     * among the week's [kept] meals. A day that has passed is no day to plan a dinner for; of a week wholly ahead, every
     * day counts.
     */
    private fun openDays(start: LocalDate, kept: List<PlannedMeal>): List<Int> = (0 until WeekMeals.DAYS).filter { day ->
        !start.plusDays(day.toLong()).isBefore(today()) && kept.none { it.day == day && it.slot == MealSlot.DINNER }
    }

    /** The week's rows, picks and hidden recipes, in the order they were made. */
    private fun rows(start: LocalDate): List<SelectPicks> = q.selectPicks(userId, start.toString()).executeAsList()

    /**
     * Recipe [recipeId] as the planner sees it: one of [every] meal of mine, or else as any recipe of the account with
     * amounts ([RecipeCandidates.candidate]), such as one put back in the bank after it was chosen; null without amounts.
     */
    private fun candidateOf(recipeId: Long, every: Map<Long, PlanCandidate>): PlanCandidate? = every[recipeId] ?: recipes.candidate(recipeId)

    /** The whole weeks since each recipe was last eaten before the week starting [start], by recipe id. */
    private fun weeksSinceEaten(start: LocalDate): Map<Long, Int> = q.selectLastEaten(userId, start.toString()).executeAsList()
        .associate { row -> row.recipeId to (ChronoUnit.DAYS.between(LocalDate.parse(row.weekStart), start) / 7).toInt() }

    /** Forgets the rows of every week that is over: a week that started more than six days before today. */
    private fun forgetEndedWeeks() = q.deletePicksEnded(userId, today().minusDays(WeekMeals.DAYS - 1L).toString())

    companion object {
        /** The most suggestions one page holds. */
        const val MAX_SUGGESTIONS = 50

        /** Euros a serving an offer must save to be named on a suggestion. */
        private const val LEAST_SAVING = 0.01

        /** Why no more dinners can be chosen, or put on days, than the [open] days (those from today on without a dinner). */
        private fun noDayLeft(open: Int, chosen: Int? = null): String = when {
            open == 0 -> "Every day of this week from today on has a dinner that stays, so there is no day for a chosen one"
            chosen != null -> "$chosen dinners are chosen for $open days from today on without a dinner: take ${chosen - open} out first"
            else -> "Each of the $open days from today on without a dinner has one chosen already: take one out first"
        }
    }
}
