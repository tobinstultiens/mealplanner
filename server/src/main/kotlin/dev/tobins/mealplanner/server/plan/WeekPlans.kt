package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlanPriority
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.planner.Pack
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.planner.PlanRequest
import dev.tobins.mealplanner.planner.WeekPlanner
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.accounts.ProfileRepository
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.DAYS
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.dinnerOf
import dev.tobins.mealplanner.server.recipes.MyRecipes
import dev.tobins.mealplanner.server.recipes.RecipeCandidates
import java.time.LocalDate
import kotlin.random.Random

/**
 * Planning a week with [WeekPlanner] and storing the result: every meal that is not kept ([KeptMeals.kept]) is replaced by
 * a newly planned one ([generate]), and the planning screen plans the week around the dinners chosen there
 * ([PlanningRepository.done], [fillWeek]). What the planner is given for a week ([request]) is here too.
 */
class WeekPlans(
    private val store: PlanStore,
    private val weekMeals: WeekMeals,
    private val keptMeals: KeptMeals,
    private val costs: WeekCosts,
    private val view: WeekView,
    private val recipes: RecipeCandidates,
    private val myRecipes: MyRecipes,
    private val profiles: ProfileRepository,
    private val stock: StockRepository,
    /** The packs of the account's fresh ingredients, by ingredient id: the planner uses up the rest of an opened one. */
    private val freshPacks: () -> Map<Long, Pack>,
) {
    /**
     * Replaces every meal of the week with a newly planned one, except those it keeps: locked ones, those
     * already eaten or skipped (history), one with a side added by hand (issue 234), one with foods (issue 358), one with a
     * skipped prepped meal's share in the box under its slot (issue 265), and any other meal the
     * stock went down for ([KeptMeals.whyKeepsItsRecipe]: the leftover lunch of an eaten dinner). The planner plans around
     * the kept meals as it does around locked ones, so a kept leftover lunch is not planned twice, and no new one
     * of an eaten dinner ([plan]); it never adds a side.
     */
    fun generate(weekStart: String, seed: Long = Random.nextLong()): WeekPlan {
        val start = parseWeekStart(weekStart)
        store.writing { fillWeek(start, seed) { emptyList() } }
        return view.of(start)
    }

    /**
     * Plans the week starting [start] as [generate] says, around the meals it keeps ([KeptMeals.kept]) and the meals [chosen] makes
     * of them: the dinners chosen on the planning screen (issue 255), on their days. Every other meal is replaced by a
     * newly planned one. A chosen meal is stored as [chosen] has it, locked or not; a meal the planner adds, unlocked,
     * and never of the recipes [hidden] for the week ([leavingOut]). Every slot that is not kept is planned, on days that
     * have passed too, as [generate] has always done. With nothing chosen nor hidden this is [generate] itself. In the
     * caller's transaction, which takes the write lock.
     */
    internal fun fillWeek(start: LocalDate, seed: Long, hidden: Set<Long> = emptySet(), chosen: (kept: List<PlannedMeal>) -> List<PlannedMeal>) {
        val week = weekMeals.of(start)
        val kept = keptMeals.kept(start, week)
        val placed = chosen(kept)
        val planned = plan(request(start, locked = kept + placed, seed = seed).leavingOut(hidden))
        for (old in week.filterNot { it in kept }) store.deleteMeal(start, old.day, old.slot)
        val lockedByHand = placed.filter { it.locked }.mapTo(HashSet()) { it.day to it.slot }
        planned.filter { new -> kept.none { it.day == new.day && it.slot == new.slot } }
            .forEach { weekMeals.save(start, it.copy(locked = (it.day to it.slot) in lockedByHand)) }
    }

    /**
     * [WeekPlanner.plan], but with no new leftover lunch of an eaten dinner (issue 169). The planner fills
     * the lunch after a locked dinner with its leftovers, and an eaten dinner is kept as locked. When it
     * still has its leftover lunch, that lunch is kept too and the slot is not free; when the lunch was
     * swapped for another recipe before the dinner was eaten, the dinner was cooked for itself only and
     * took only its own share of the stock. A leftover lunch of it then would be cooked by nobody: it
     * cooks nothing itself ([WeekMeals.cookPortions] 0) and the shopping list would not buy for it ([WeekMeals.toCook]). Such a
     * lunch is planned again like any other meal, around everything else, without the dinner's recipe.
     */
    internal fun plan(request: PlanRequest): List<PlannedMeal> {
        val planned = WeekPlanner.plan(request)
        val uncooked = planned.filter { meal ->
            request.locked.none { it.day == meal.day && it.slot == meal.slot } && dinnerOf(meal, planned)?.status == MealStatus.EATEN
        }
        if (uncooked.isEmpty()) return planned
        return WeekPlanner.plan(request.copy(locked = planned - uncooked.toSet(), excluded = request.excluded + uncooked.map { it.recipeId }))
    }

    /** The recipes hidden for the week starting [start] on the planning screen ("Not this week", issue 255). */
    internal fun hiddenIn(start: LocalDate): Set<Long> =
        store.picks(start).filter { it.refused != 0L }.mapTo(HashSet()) { it.recipeId }

    /**
     * What the planner plans the week starting [start] with, around the [locked] meals: with the recipes of those that are
     * no candidates ([PlanRequest.known]), such as a dinner chosen by hand from outside the rotation, whose leftovers the
     * next lunch is then too.
     */
    internal fun request(start: LocalDate, locked: List<PlannedMeal>, seed: Long): PlanRequest {
        val targets = profiles.planTargets()
        val candidates = candidates(start)
        val unknown = locked.map { it.recipeId }.distinct().filter { id -> candidates.none { it.recipeId == id } }
        return PlanRequest(
            targets = targets,
            candidates = candidates,
            known = unknown.mapNotNull { recipes.candidate(it, freshPacks(), stock.onHandFor(start)) },
            routines = myRecipes.routines().let { r ->
                listOfNotNull(r.breakfast?.let { MealSlot.BREAKFAST to it }, r.snack?.let { MealSlot.SNACK to it }).toMap()
            },
            days = DAYS,
            locked = locked,
            // What was planned the week before counts as recently eaten.
            recent = weekMeals.of(start.minusDays(DAYS.toLong())).mapTo(HashSet()) { it.recipeId },
            portions = profiles.portions(),
            priority = profiles.profile()?.priority ?: PlanPriority.BALANCED,
            seed = seed,
        )
    }

    /**
     * The account's recipes as the planner sees them in the week starting [start] ([RecipeCandidates.planCandidates]),
     * with its offers, prices, what is on hand and the fresh packs; with [everyMeal], every one of my meals.
     */
    internal fun candidates(start: LocalDate, everyMeal: Boolean = false): List<PlanCandidate> = recipes.planCandidates(
        onOffer = costs.onOffer(start),
        dislikes = profiles.profile()?.dislikes.orEmpty(),
        prices = costs.prices(start),
        onHand = stock.onHandFor(start),
        freshPacks = freshPacks(),
        everyMeal = everyMeal,
    )
}

/** The account's daily targets, which the planner plans with; a [ConflictException] until the profile is set up. */
internal fun ProfileRepository.planTargets() = state().targets
    ?: throw ConflictException("Set up your profile first: the planner needs daily targets")

/**
 * This request without the recipes [hidden] for the week on the planning screen (issue 255): out of the candidates,
 * so the planner never picks one, and known ([PlanRequest.known]) rather than excluded, so a kept dinner of one still
 * leaves its leftovers for the next lunch, which an excluded recipe would not.
 */
internal fun PlanRequest.leavingOut(hidden: Set<Long>): PlanRequest {
    if (hidden.isEmpty()) return this
    val (notPicked, picked) = candidates.partition { it.recipeId in hidden }
    return copy(candidates = picked, known = known + notPicked)
}
