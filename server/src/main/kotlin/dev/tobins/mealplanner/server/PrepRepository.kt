package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.SpareMove
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.planner.WeekPlanner
import dev.tobins.mealplanner.server.db.Database
import java.time.LocalDate

/**
 * One account's prepped meals (issue 234): a recipe made once and eaten over several days, started by hand
 * only. Making, removing and the share of a skipped meal are here; how the plan, the shopping list and the
 * stock count a batch is [PlanRepository]'s ([Batch]).
 */
class PrepRepository(
    private val db: Database,
    private val userId: Long,
    private val plans: PlanRepository,
    private val recipes: RecipeRepository,
    private val profiles: ProfileRepository,
) {
    private val q = db.planQueries

    /**
     * Makes [input]'s recipe on its made-on day and puts it in each of its meals, locked, each with the portion
     * the planner would give that slot within the profile's range; the batch is their sum. The recipe must be
     * one of mine, a meal, with amounts, and keep for the chosen way ([RecipeKeeps]): the meals are lunches and
     * dinners from the made-on day (the first of them is that day) to the last day it keeps. A slot may be
     * empty or hold a meal that may be replaced ([PlanRepository.whyKeepsItsRecipe]) and is of no other batch;
     * a dinner's leftover lunch goes with it. Answers the week starting [weekStart], the one the app shows.
     */
    fun create(weekStart: String, input: PrepInput): WeekPlan {
        val shown = parseWeekStart(weekStart)
        val madeOn = Batch.parseDate(input.madeOn)
        val places = input.meals.map(Batch::placeOf)
        require(input.meals.size >= 2) { "a prep is eaten in at least two meals" }
        require(places.distinct().size == places.size) { "each meal at most once" }
        require(input.meals.all { it.slot == MealSlot.LUNCH || it.slot == MealSlot.DINNER }) { "a prep is eaten at lunch or dinner" }
        val dates = input.meals.map { Batch.parseDate(it.date) }
        require(dates.min() == madeOn) { "the first meal is on the day it is made" }
        val recipe = recipes.requireMine(input.recipeId)
        if (recipe.kind == RecipeKind.COMPONENT.name) throw ConflictException("This recipe is a building block, not a meal")
        val keeps = (if (input.vacuum) recipe.keepsVacuumDays else recipe.keepsDays)
            ?: throw ConflictException("Set how many days \"${recipe.title}\" keeps ${if (input.vacuum) "vacuum sealed" else "in a box"} first")
        val lastDay = madeOn.plusDays(keeps)
        if (dates.max() > lastDay) throw ConflictException("\"${recipe.title}\" keeps until ${Batch.dayName(lastDay)}")
        val candidate = recipes.candidate(input.recipeId) ?: throw ConflictException("This recipe has no amounts to plan with")
        val targets = plans.targets()
        val range = profiles.portions()
        db.writing {
            val weeks = places.map { it.first }.distinct().associateWith { plans.meals(it) }
            for ((week, day, slot) in places) requireRoom(week, weeks.getValue(week), day, slot)
            q.insertPrep(userId, input.recipeId, madeOn.toString(), lastDay.toString(), if (input.vacuum) 1 else 0)
            val prepId = q.lastPrepId().executeAsOne()
            for ((week, day, slot) in places) {
                val taken = places.filter { it.first == week }.map { it.second to it.third }.toSet()
                plans.leftoversToReplace(week, weeks.getValue(week), day, slot)?.takeIf { (it.day to it.slot) !in taken }
                    ?.let { plans.deleteMeal(week, it.day, it.slot) }
                val portions = WeekPlanner.portionsFor(candidate, slot, targets, range)
                val meal = PlannedMeal(day, slot, candidate.recipeId, candidate.title, portions, candidate.kcal * portions, candidate.proteinG * portions)
                plans.saveInBatch(week, meal, prepId)
            }
        }
        return plans.view(shown)
    }

    /**
     * Takes batch [id] out of the plan with all its meals, in every week: what removing the meal that makes it
     * does, once the app has named the later meals. Not once the batch was made (one of its meals eaten): its
     * meals then hold what the stock gave, and the later ones are skipped instead. A batch of another account
     * is not there (404).
     */
    fun remove(weekStart: String, id: Long): WeekPlan {
        val shown = parseWeekStart(weekStart)
        db.writing {
            val batch = batch(id)
            if (batch.made) throw ConflictException("This prepped batch was made already: mark the meals you do not eat skipped instead")
            for (meal in batch.meals) plans.deleteMeal(meal.week, meal.meal.day, meal.meal.slot)
            q.deletePrep(userId, id)
        }
        return plans.view(shown)
    }

    /**
     * The shares of skipped meals still in the box on [date]: of every batch that keeps until then or later,
     * each skipped meal whose share was not eaten elsewhere, with the lunches and dinners it can still go to
     * ([room]). Never one past its last day: from the day after, it is not listed at all.
     */
    fun spare(date: String): List<SparePortion> {
        val day = Batch.parseDate(date)
        val ids = q.selectPrepsKeeping(userId, day.toString()).executeAsList().map { it.id }
        val weeks = HashMap<LocalDate, List<PlannedMeal>>()
        return Batch.load(db, userId, ids).values.sortedBy { it.madeOn }.flatMap { batch ->
            batch.meals.filter { it.meal.status == MealStatus.SKIPPED && it.share > 0.0 }.map { skipped ->
                SparePortion(
                    prepId = batch.id,
                    recipeId = batch.recipeId,
                    title = skipped.meal.title,
                    portions = skipped.share,
                    skipped = PrepSlot(skipped.date.toString(), skipped.meal.slot),
                    lastDay = batch.lastDay.toString(),
                    room = room(batch, skipped, from = day) { week -> weeks.getOrPut(week) { plans.meals(week) } },
                )
            }
        }
    }

    /**
     * Eats the share of the skipped meal [SpareMove.from] of batch [id] in [SpareMove.to] instead: a lunch or dinner
     * after it, up to the batch's last day, that has room ([hasRoom]); whatever was planned there goes. The skipped
     * meal stays skipped with a share of 0, and what the stock gave for its share and what was in it go along, so
     * the batch, the shopping list and the stock stay as they were. Answers the week starting [weekStart].
     */
    fun eatSpare(weekStart: String, id: Long, move: SpareMove): WeekPlan {
        val shown = parseWeekStart(weekStart)
        require(move.to.slot == MealSlot.LUNCH || move.to.slot == MealSlot.DINNER) { "a prep is eaten at lunch or dinner" }
        db.writing {
            val batch = batch(id)
            val from = batch.mealAt(Batch.parseDate(move.from.date), move.from.slot)
                ?.takeIf { it.meal.status == MealStatus.SKIPPED && it.share > 0.0 }
                ?: throw ConflictException("That meal of the batch is not skipped, or its share is eaten elsewhere already")
            val (week, day, slot) = Batch.placeOf(move.to)
            val target = week.plusDays(day.toLong())
            if (target > batch.lastDay) throw ConflictException("It keeps until ${Batch.dayName(batch.lastDay)}: it can't be eaten later")
            if (!after(target, slot, from)) throw ConflictException("It can only be eaten in a later meal")
            val meals = plans.meals(week)
            if (!hasRoom(week, meals, day, slot)) throw ConflictException("${Batch.slotName(target, slot)} has a meal that stays")
            plans.leftoversToReplace(week, meals, day, slot)?.let { plans.deleteMeal(week, it.day, it.slot) }
            plans.saveInBatch(week, from.meal.copy(day = day, slot = slot, status = MealStatus.PLANNED), batch.id)
            val old = from.ref
            q.clearPrepShare(userId, old.week.toString(), old.day.toLong(), old.slot.name)
            q.moveStockUses(week.toString(), day.toLong(), slot.name, userId, old.week.toString(), old.day.toLong(), old.slot.name)
            q.moveMealLines(week.toString(), day.toLong(), slot.name, userId, old.week.toString(), old.day.toLong(), old.slot.name)
        }
        return plans.view(shown)
    }

    /** Batch [id] of this account; a [NotFoundException] for any other. */
    private fun batch(id: Long): Batch = Batch.load(db, userId, listOf(id))[id] ?: throw NotFoundException("No prep with id $id")

    /**
     * The lunches and dinners [skipped]'s share may still be eaten in: after it, from [from] on, up to [batch]'s last
     * day, each one that [hasRoom]. [weekOf] gives a week's meals.
     */
    private fun room(batch: Batch, skipped: BatchMeal, from: LocalDate, weekOf: (LocalDate) -> List<PlannedMeal>): List<PrepSlot> =
        generateSequence(maxOf(from, skipped.date)) { it.plusDays(1) }.takeWhile { it <= batch.lastDay }.flatMap { date ->
            listOf(MealSlot.LUNCH, MealSlot.DINNER).filter { slot -> after(date, slot, skipped) }.map { PrepSlot(date.toString(), it) }
        }.filter { slot ->
            val (week, day, mealSlot) = Batch.placeOf(slot)
            hasRoom(week, weekOf(week), day, mealSlot)
        }.toList()

    private fun after(date: LocalDate, slot: MealSlot, meal: BatchMeal): Boolean =
        date > meal.date || (date == meal.date && slot.ordinal > meal.meal.slot.ordinal)

    /**
     * Whether a slot can take a meal of a batch: it is empty, or its meal is still to eat, not locked (the
     * owner chose it), of no batch, and may be replaced ([PlanRepository.whyKeepsItsRecipe]).
     */
    private fun hasRoom(week: LocalDate, meals: List<PlannedMeal>, day: Int, slot: MealSlot): Boolean {
        val meal = meals.firstOrNull { it.day == day && it.slot == slot } ?: return true
        return meal.prep == null && !meal.locked && meal.status == MealStatus.PLANNED && plans.whyKeepsItsRecipe(week, meal, meals) == null
    }

    /**
     * Refuses a slot for a meal of a batch unless it is empty or its meal may be replaced and is of no other batch:
     * a locked meal the owner chose may be, as making a prep there is choosing again.
     */
    private fun requireRoom(week: LocalDate, meals: List<PlannedMeal>, day: Int, slot: MealSlot) {
        val meal = meals.firstOrNull { it.day == day && it.slot == slot } ?: return
        val name = Batch.slotName(week.plusDays(day.toLong()), slot)
        if (meal.prep != null) throw ConflictException("$name is from another prepped batch already")
        plans.whyKeepsItsRecipe(week, meal, meals)?.let { throw ConflictException("$name: $it") }
    }
}
