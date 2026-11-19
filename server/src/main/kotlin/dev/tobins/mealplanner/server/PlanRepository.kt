package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.LaterShortfall
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlanPriority
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.planner.Pack
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.planner.PlanRequest
import dev.tobins.mealplanner.planner.PortionRange
import dev.tobins.mealplanner.planner.WeekPlanner
import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.server.db.Database
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.random.Random

/**
 * Week plans: generating them with [WeekPlanner] and storing the result. The shopping list of a
 * week is [ShoppingListRepository]'s; it reads the week's [meals] here.
 */
class PlanRepository(
    private val db: Database,
    private val userId: Long,
    private val recipes: RecipeRepository,
    private val profiles: ProfileRepository,
    private val stock: StockRepository,
    /** Offers valid between two dates at every store, grouped by ingredient id. */
    private val offersByIngredient: (LocalDate, LocalDate) -> Map<Long, List<Offer>> = { _, _ -> emptyMap() },
    /** Prices with the offers valid between two dates, at the stores the account shops at. */
    private val pricing: (LocalDate, LocalDate) -> Pricing = { _, _ -> Pricing.NONE },
    /** The stores the account shops at: only their offers make the planner prefer a meal. */
    private val shops: () -> Set<Store> = { Store.entries.toSet() },
    /** The packs of the account's fresh ingredients, by ingredient id: the planner uses up the rest of an opened one. */
    private val freshPacks: () -> Map<Long, Pack> = { emptyMap() },
) {
    private val q = db.planQueries
    private val adjustments = MealAdjustments(db, userId, recipes, recipes.ingredients)

    fun get(weekStart: String): WeekPlan = view(parseWeekStart(weekStart))

    /**
     * Replaces every meal of the week with a newly planned one, except those it keeps: locked ones, those
     * already eaten or skipped (history), one with a side added by hand (issue 234), and any other meal the
     * stock went down for ([whyKeepsItsRecipe]: the leftover lunch of an eaten dinner). The planner plans around
     * the kept meals as it does around locked ones, so a kept leftover lunch is not planned twice, and no new one
     * of an eaten dinner ([plan]); it never adds a side.
     */
    fun generate(weekStart: String, seed: Long = Random.nextLong()): WeekPlan {
        val start = parseWeekStart(weekStart)
        db.writing {
            val week = meals(start)
            val kept = week.filter { it.locked || it.status != MealStatus.PLANNED || it.side != null || whyKeepsItsRecipe(start, it, week) != null }
            val planned = plan(request(start, locked = kept, seed = seed))
            for (old in week.filterNot { it in kept }) q.deleteMeal(userId, start.toString(), old.day.toLong(), old.slot.name)
            planned.filter { new -> kept.none { it.day == new.day && it.slot == new.slot } }.forEach { save(start, it.copy(locked = false)) }
        }
        return view(start)
    }

    /**
     * [WeekPlanner.plan], but with no new leftover lunch of an eaten dinner (issue 169). The planner fills
     * the lunch after a locked dinner with its leftovers, and an eaten dinner is kept as locked. When it
     * still has its leftover lunch, that lunch is kept too and the slot is not free; when the lunch was
     * swapped for another recipe before the dinner was eaten, the dinner was cooked for itself only and
     * took only its own share of the stock. A leftover lunch of it then would be cooked by nobody: it
     * cooks nothing itself ([cookPortions] 0) and the shopping list would not buy for it ([toCook]). Such a
     * lunch is planned again like any other meal, around everything else, without the dinner's recipe.
     */
    private fun plan(request: PlanRequest): List<PlannedMeal> {
        val planned = WeekPlanner.plan(request)
        val uncooked = planned.filter { meal ->
            request.locked.none { it.day == meal.day && it.slot == meal.slot } && dinnerOf(meal, planned)?.status == MealStatus.EATEN
        }
        if (uncooked.isEmpty()) return planned
        return WeekPlanner.plan(request.copy(locked = planned - uncooked.toSet(), excluded = request.excluded + uncooked.map { it.recipeId }))
    }

    /**
     * Picks a different recipe for one meal and keeps the rest of the week. When a dinner
     * changes, the next day's lunch made of its leftovers changes with it ([leftoversToReplace]).
     * When no other recipe fits the meal, it is kept and a [ConflictException] says so; so it is
     * when the meal was cooked ([requireNotCooked]). A side stays next to the new main ([save]), so the new main is
     * never the side's recipe: that would be one recipe twice on one plate.
     */
    fun reroll(weekStart: String, day: Int, slot: MealSlot, seed: Long = Random.nextLong()): WeekPlan {
        val start = parseWeekStart(weekStart)
        // Read and written in one transaction, so a meal eaten meanwhile is not replaced after all.
        db.writing {
            val current = meals(start)
            val target = current.firstOrNull { it.day == day && it.slot == slot }
                ?: throw NotFoundException("No meal planned for day $day $slot")
            requireNotCooked(start, target, current)
            val replaced = listOfNotNull(target, leftoversToReplace(start, current, day, slot))
            val excluded = setOfNotNull(target.recipeId, target.side?.recipeId)
            val planned = plan(request(start, locked = current - replaced.toSet(), seed = seed).copy(excluded = excluded))
            val replacement = planned.firstOrNull { it.day == day && it.slot == slot }
            if (replacement == null || replacement.recipeId == target.recipeId) throw ConflictException("No other recipe fits this meal")
            for (old in replaced) {
                val new = planned.firstOrNull { it.day == old.day && it.slot == old.slot }
                if (new != null) save(start, new.copy(locked = false, status = MealStatus.PLANNED)) else q.deleteMeal(userId, start.toString(), old.day.toLong(), old.slot.name)
            }
        }
        return view(start)
    }

    /**
     * Puts the account's recipe [recipeId] in a slot because the user chose it: [place]d and locked,
     * so that replanning keeps it. The recipe need not be marked for the slot, and an empty slot can be
     * filled. A [NotFoundException] when the recipe is not the account's, a [ConflictException] when it
     * has no amounts to plan with, is a component or when the slot's meal was cooked ([requireNotCooked]).
     * Any of my recipes can be chosen, in the rotation or not; one only in the bank is a [ConflictException]
     * until it is added ([RecipeRepository.requireMine]).
     */
    fun choose(weekStart: String, day: Int, slot: MealSlot, recipeId: Long): WeekPlan {
        val recipe = recipes.requireMine(recipeId)
        if (recipe.kind == RecipeKind.COMPONENT.name) throw ConflictException("This recipe is a building block, not a meal")
        val candidate = recipes.candidate(recipeId) ?: throw ConflictException("This recipe has no amounts to plan with")
        return place(weekStart, day, slot, candidate, locked = true)
    }

    /**
     * Why [meal] keeps its recipe and its place in the plan, or null when it may be replaced or removed.
     * The stock went down for it: it was eaten, or it is the leftover lunch of a dinner that was eaten
     * ([eatFromStock] took its share too), or what was taken for it is kept under its slot all the same
     * (a leftover lunch whose eaten dinner was removed, which servers before issue 165 allowed). What a
     * meal took is kept under its day and slot and goes with the meal, so replacing or removing it would
     * lose that stock for good; the user un-eats the meal (or its dinner) first.
     */
    internal fun whyKeepsItsRecipe(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>): String? = when {
        meal.status == MealStatus.EATEN -> "This meal was eaten already"
        meal.prep != null -> whyKeepsItsBatch(start, meal)
        dinnerOf(meal, week)?.status == MealStatus.EATEN -> "The dinner before it was eaten, and these leftovers with it"
        holdsStock(start, meal) -> "These leftovers were cooked with a dinner that was eaten"
        else -> null
    }

    /**
     * Why [meal], of a prepped batch, keeps its place: it makes the batch, which goes only as a whole (`DELETE
     * .../preps/{id}`, after the app named the later meals), or the batch was made, so its share is in the box
     * and in what the stock gave. A later meal of a batch still to make may be replaced or removed: the batch
     * is then made smaller.
     */
    private fun whyKeepsItsBatch(start: LocalDate, meal: PlannedMeal): String? {
        val batch = batchesOf(listOf(meal)).values.singleOrNull() ?: return null
        return when {
            batch.made -> "This meal is from a prepped batch that was made already: mark it skipped instead"
            batch.isCook(start, meal) && batch.meals.size > 1 ->
                "This meal makes the prepped batch for ${batch.laterMealsText()}: remove the prep to take those out too"
            batch.isCook(start, meal) -> "This meal makes a prepped batch: remove the prep instead"
            else -> null
        }
    }

    /**
     * Whether some of the stock was taken for [meal] and is kept under its slot, to give back when it is un-eaten. Its
     * own share only: that of its side goes with the side's batch, and stays when the meal's main is replaced ([save]).
     */
    private fun holdsStock(start: LocalDate, meal: PlannedMeal): Boolean =
        db.pantryQueries.selectStockUses(userId, start.toString(), meal.day.toLong(), meal.slot.name, MealRef.MEAL).executeAsList().isNotEmpty()

    /** Refuses to replace or remove [meal] when it keeps its recipe ([whyKeepsItsRecipe]). */
    private fun requireNotCooked(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>) {
        whyKeepsItsRecipe(start, meal, week)?.let { throw ConflictException(it) }
    }

    /**
     * [requireNotCooked] for whatever is in a slot; an empty slot can be filled. Writing a new recipe
     * asks it first, so that Claude does not write one for a meal it may not replace.
     */
    fun requireReplaceable(weekStart: String, day: Int, slot: MealSlot) {
        val start = parseWeekStart(weekStart)
        requireReplaceable(start, meals(start), day, slot)
    }

    private fun requireReplaceable(start: LocalDate, week: List<PlannedMeal>, day: Int, slot: MealSlot) {
        week.firstOrNull { it.day == day && it.slot == slot }?.let { requireNotCooked(start, it, week) }
    }

    /**
     * The next day's leftover lunch that changes along with the dinner in [day]'s [slot]: only one
     * still planned, not locked and that may be replaced ([whyKeepsItsRecipe]). An eaten or skipped one
     * is history and stays as it is; an eaten one, or one that holds stock, keeps what was taken from
     * the stock, to give it back when it is un-eaten. One with a side added by hand stays as it is too, as
     * it would go when the new dinner leaves no leftovers, and its side with it.
     */
    internal fun leftoversToReplace(start: LocalDate, week: List<PlannedMeal>, day: Int, slot: MealSlot): PlannedMeal? = week.firstOrNull {
        slot == MealSlot.DINNER && it.day == day + 1 && it.slot == MealSlot.LUNCH && it.leftover && it.prep == null && !it.locked &&
            it.side == null && it.status == MealStatus.PLANNED && whyKeepsItsRecipe(start, it, week) == null
    }

    /**
     * Puts [candidate] in a slot, sized to the slot's share of the targets within the profile's portion
     * range, [locked] or not; also a recipe too light to be a meal there, as the user chose it. A dinner's
     * leftovers replace the next day's leftover lunch ([leftoversToReplace]). A meal that was cooked is
     * not replaced ([requireNotCooked]): checked in the transaction that writes, as a recipe can take a
     * minute to write and the meal may be eaten meanwhile.
     */
    fun place(weekStart: String, day: Int, slot: MealSlot, candidate: PlanCandidate, locked: Boolean = false): WeekPlan {
        val start = parseWeekStart(weekStart)
        val targets = targets()
        val range = profiles.portions()
        val portions = WeekPlanner.portionsFor(candidate, slot, targets, range)
        val meal = PlannedMeal(day, slot, candidate.recipeId, candidate.title, portions, candidate.kcal * portions, candidate.proteinG * portions, locked = locked)
        db.writing {
            val week = meals(start)
            requireReplaceable(start, week, day, slot)
            val nextLunch = leftoversToReplace(start, week, day, slot)
            save(start, meal)
            val remaining = candidate.servings - portions
            if (nextLunch != null) {
                if (remaining >= PortionRange.LEAST_LEFTOVER) {
                    val lunchPortions = WeekPlanner.portionsFor(candidate, MealSlot.LUNCH, targets, range, available = remaining)
                    save(start, nextLunch.copy(recipeId = candidate.recipeId, title = candidate.title, portions = lunchPortions,
                        kcal = candidate.kcal * lunchPortions, proteinG = candidate.proteinG * lunchPortions))
                } else {
                    // Nothing left over: the old dinner's leftovers are gone too.
                    q.deleteMeal(userId, start.toString(), nextLunch.day.toLong(), nextLunch.slot.name)
                }
            }
        }
        return view(start)
    }

    /** Locks a meal or unlocks it. A prepped meal is always locked: the batch is made for it, so unlocking it is a [ConflictException]. */
    fun setLocked(weekStart: String, day: Int, slot: MealSlot, locked: Boolean): WeekPlan {
        val start = parseWeekStart(weekStart)
        db.writing {
            if (!locked && meals(start).any { it.day == day && it.slot == slot && it.prep != null }) {
                throw ConflictException("A prepped meal stays in the plan: remove it or the prep instead")
            }
            q.setLocked(if (locked) 1 else 0, userId, start.toString(), day.toLong(), slot.name)
        }
        return view(start)
    }

    /**
     * Marks a meal eaten, skipped or planned again. Eating it is eating from what was bought ahead or
     * stocked up for it ([eatFromStock]); marking it skipped or planned again puts that back
     * ([giveBackToStock]), and marking it eaten when it already is takes nothing more. Its side is eaten,
     * skipped or planned with it, which may make its batch or un-make it ([settle]).
     */
    fun setStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus): WeekPlan {
        val start = parseWeekStart(weekStart)
        db.writing {
            val meals = meals(start)
            val meal = meals.firstOrNull { it.day == day && it.slot == slot }
            val batch = meal?.let { batchesOf(listOf(it)).values.singleOrNull() }
            val sideBatch = meal?.side?.let { Batch.load(db, userId, listOf(it.prep.id)).values.singleOrNull() }
            if (batch != null) {
                setBatchStatus(batch, batch.mealAt(start.plusDays(day.toLong()), slot)!!, status)
            } else {
                q.setStatus(status.name, userId, start.toString(), day.toLong(), slot.name)
                if (meal != null && (meal.status == MealStatus.EATEN) != (status == MealStatus.EATEN)) {
                    if (status == MealStatus.EATEN) eatFromStock(start, meal, meals) else giveBackToStock(start, meal, meals)
                }
            }
            sideBatch?.let(::settle)
        }
        return view(start)
    }

    /**
     * Marks [meal] of [batch] eaten, skipped or planned again. A meal whose share went to another meal
     * ([BatchMeal.share] 0) can only be skipped: there is nothing left to eat.
     */
    private fun setBatchStatus(batch: Batch, meal: BatchMeal, status: MealStatus) {
        if (meal.share == 0.0 && status != MealStatus.SKIPPED) {
            throw ConflictException("Its share of the prepped batch is eaten in another meal already")
        }
        q.setStatus(status.name, userId, meal.week.toString(), meal.meal.day.toLong(), meal.meal.slot.name)
        settle(batch)
    }

    /**
     * After one of [before]'s meals was marked: the batch is made once, so the first of its meals eaten, whichever,
     * takes every meal's and side's share from the stock, each kept under its own meal and from the stock of the week
     * the batch is made in ([cookBatch]); un-eating the last eaten one gives all of it back.
     */
    private fun settle(before: Batch) {
        val after = Batch.load(db, userId, listOf(before.id)).getValue(before.id)
        when {
            !before.made && after.made -> cookBatch(after)
            before.made && !after.made -> after.meals.forEach { stock.giveBack(it.ref) }
        }
    }

    /**
     * Takes each meal's share of [batch] from the stock under the meal, from what was at home in the week it is made: a
     * meal's as adjusted, a side's as its recipe has it, as a side can't be adjusted.
     */
    private fun cookBatch(batch: Batch) {
        val recipe = recipes.linesWithIngredients(listOf(batch.recipeId))[batch.recipeId] ?: return
        for (meal in batch.meals.filter { it.share > 0.0 }) {
            val grams = if (meal.side) gramsUsed(recipe, meal.share) else adjustments.gramsEaten(meal.week, meal.meal.copy(portions = meal.share), recipe)
            stock.useUp(meal.ref, grams, cookedIn = batch.week)
        }
    }

    /**
     * Takes from the stock what eating [meal] used ([StockRepository.useUp]), each share kept
     * under the meal whose portions it is for. A dinner is cooked with its leftovers, so it takes the
     * next day's leftover lunch's share too, unless that lunch was eaten already and took its own. A
     * leftover lunch takes its own share only when its dinner was not eaten: then it was cooked for
     * the lunch. Whichever is eaten first, the two take the same in the end.
     */
    private fun eatFromStock(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>) {
        if (dinnerOf(meal, week)?.status != MealStatus.EATEN) take(start, meal)
        leftoversOf(meal, week)?.takeIf { it.status != MealStatus.EATEN }?.let { take(start, it) }
    }

    /**
     * The inverse of [eatFromStock]: puts back what was taken for [meal]'s portions, unless it is a
     * leftover lunch whose dinner is still eaten (cooked, so used whether eaten or not), and for its
     * leftover lunch's, unless that lunch is eaten.
     */
    private fun giveBackToStock(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>) {
        if (dinnerOf(meal, week)?.status != MealStatus.EATEN) stock.giveBack(MealRef(start, meal.day, meal.slot))
        leftoversOf(meal, week)?.takeIf { it.status != MealStatus.EATEN }?.let { stock.giveBack(MealRef(start, it.day, it.slot)) }
    }

    /** Takes [meal]'s portions of its recipe from the stock, as it was adjusted ([MealAdjustments.gramsEaten]), kept under [meal]. */
    private fun take(start: LocalDate, meal: PlannedMeal) {
        val recipe = recipes.linesWithIngredients(listOf(meal.recipeId))[meal.recipeId] ?: return
        stock.useUp(MealRef(start, meal.day, meal.slot), adjustments.gramsEaten(start, meal, recipe))
    }

    /**
     * The meal in [day]'s [slot] with what was in it ([MealAdjustments.lines]) and the later meals it leaves
     * short ([warnings]); a [NotFoundException] for an empty slot.
     */
    fun lines(weekStart: String, day: Int, slot: MealSlot): MealLines {
        val start = parseWeekStart(weekStart)
        val week = meals(start)
        val meal = mealAt(week, day, slot)
        return adjustments.lines(start, meal).copy(shortfalls = warnings(start, meal, week))
    }

    /**
     * What the adjustments of the week starting [start] leave its meals still to cook short of (issue 226,
     * [dev.tobins.mealplanner.server.shortfalls]), from what was at home for them ([StockRepository.atHome]).
     * The meals walked are those cooked ([shareTaken]) or still to cook ([stillToCook]), in the order they
     * are cooked ([cookingOrder]). An ingredient the week's list has no weighed row for ([withoutWeighedRow]:
     * a staple, one to taste, one some line of the week can't weigh) counts as not known at home, so it is
     * never short: there would be no row to ask for it again, and the warning would say what the list doesn't.
     * Nothing without an adjusted meal in the week. [week] is the week as the shopping list counts it
     * ([forShopping]): a prepped batch made this week is cooked as one meal, with what each of its meals had; one
     * made next to a meal as its side is a meal of its own there, which can't be adjusted, so it has no changes.
     */
    fun shortfallsIn(start: LocalDate, week: List<PlannedMeal>): Shortfalls {
        val lines = recipes.linesWithIngredients(week.map { it.recipeId })
        val changes = changesForShopping(start, week, lines)
        if (changes.isEmpty()) return Shortfalls.NONE
        // By the meals themselves, not their slots: a batch cooked as a side shares its meal's slot.
        val toCook = stillToCook(start, week).toSet()
        val cooked = week.filter { it in toCook || shareTaken(start, it, week) }.sortedBy { cookingOrder(it, week) }
        val noRow = lines.values.flatMapTo(HashSet(), ::withoutWeighedRow)
        val changesOf = { meal: PlannedMeal -> if (isCookedAsSide(start, meal)) null else changes[meal.day to meal.slot] }
        val (elsewhere, here) = cookedAcrossWeeks(start)
        return shortfalls(adjustments.demands(cooked, lines, changesOf, toCook), stock.atHome(start, elsewhere, here) + noRow.associateWith { null })
    }

    /**
     * The meals and sides of batches eaten across two weeks, for what was at home for the meals of the week starting
     * [start] ([StockRepository.atHome]): first those of this week of a batch made in the week before, then those of the
     * next week of a batch made in this one. A batch is cooked from the stock of the week it is made in ([cookBatch]).
     */
    private fun cookedAcrossWeeks(start: LocalDate): Pair<Set<MealRef>, List<MealRef>> {
        val batches = Batch.load(db, userId, meals(start).flatMap { listOfNotNull(it.prep?.id, it.side?.prep?.id) }).values
        val elsewhere = batches.filter { it.week != start }.flatMap { batch -> batch.meals.filter { it.week == start }.map { it.ref } }
        val here = batches.filter { it.week == start }.flatMap { batch -> batch.meals.filter { it.week != start }.map { it.ref } }
        return elsewhere.toSet() to here
    }

    /**
     * The changes of [week]'s meals by day and slot ([MealAdjustments.changesIn]), [week] as [forShopping] has it:
     * a prepped batch, one meal there, has what all its meals ate of each ingredient one of them changed, in
     * whichever week they are. A batch cooked as a side has none ([isCookedAsSide]) and leaves its meal's alone.
     * [lines] are the recipes' lines.
     */
    private fun changesForShopping(start: LocalDate, week: List<PlannedMeal>, lines: Map<Long, RecipeLines>): Map<Pair<Int, MealSlot>, Map<Long, Double>> {
        val changes = adjustments.changesIn(start).toMutableMap()
        val ofBatches = week.filterNot { isCookedAsSide(start, it) }
            .mapNotNull { meal -> meal.prep?.let { prep -> batchesOf(listOf(meal))[prep.id]?.let { meal to it } } }
        for ((meal, batch) in ofBatches) {
            val recipe = lines[batch.recipeId]
            val shares = batch.meals.filter { it.share > 0.0 }
            // A side can't be adjusted: what its meal changed is the main's.
            val changed = shares.associateWith { if (it.side) emptyMap() else adjustments.changesOf(it.week, it.meal) }
            val ids = changed.values.flatMapTo(HashSet()) { it.keys }
            changes.remove(meal.day to meal.slot)
            if (ids.isEmpty() || recipe == null) continue
            changes[meal.day to meal.slot] = ids.associateWith { id ->
                shares.sumOf { share -> changed.getValue(share)[id] ?: gramsUsed(recipe, share.share)[id] ?: 0.0 }
            }
        }
        return changes
    }

    /**
     * The meals cooked after [meal] that its week leaves short ([shortfallsIn]) of an ingredient [meal] took more
     * of than its recipe from what was at home: what the Adjust screen warns of. A meal cooked before it took
     * its share first, and an ingredient [meal] had from the fridge took nothing another meal needed. A meal of
     * a prepped batch took what it had when the batch was made, so it is where the batch is in the week.
     */
    private fun warnings(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>): List<LaterShortfall> {
        val view = forShopping(start, week)
        val result = shortfallsIn(start, view)
        val cooked = meal.prep?.let { prep -> view.firstOrNull { it.prep?.id == prep.id } } ?: meal
        val order = cookingOrder(cooked, view)
        val names = recipes.ingredients.list().associate { it.id to it.name }
        return result.short.filter { short ->
            cookingOrder(short.meal, view) > order &&
                result.taken[short.ingredientId].orEmpty().any { it.demand.meal.day == cooked.day && it.demand.meal.slot == cooked.slot }
        }.map { LaterShortfall(it.meal.day, it.meal.slot, it.meal.title, it.ingredientId, names[it.ingredientId].orEmpty(), it.grams) }
    }

    /**
     * Where [meal] comes in the order [week]'s meals are cooked: by day and slot, but a leftover lunch with the
     * dinner it is cooked with, unless that dinner was skipped and the lunch is cooked for itself.
     */
    private fun cookingOrder(meal: PlannedMeal, week: List<PlannedMeal>): Double {
        val cookedWith = dinnerOf(meal, week)?.takeIf { it.status != MealStatus.SKIPPED }
        return cookedWith?.let { at(it) + 0.5 } ?: at(meal)
    }

    /** [meal]'s place in its week, slot by slot: what [cookingOrder] counts from. */
    private fun at(meal: PlannedMeal): Double = (meal.day * MealSlot.entries.size + meal.slot.ordinal).toDouble()

    /**
     * Says what was in a meal (issue 226): [changes] replace those it had ([MealAdjustments.replace]); none
     * puts it back to its recipe. Any meal that is not skipped can be adjusted, eaten or still to eat, as
     * toppings are often decided while cooking; a skipped one counts for nothing, so that is a
     * [ConflictException]. When the meal's share of the stock was taken already ([shareTaken]), the stock
     * moves by the difference ([StockRepository.retake]), and un-eating it later gives back what it holds
     * then. A leftover lunch is adjusted on its own: its dinner keeps its lines.
     */
    fun adjust(weekStart: String, day: Int, slot: MealSlot, changes: List<MealLineChange>): MealLines {
        val start = parseWeekStart(weekStart)
        return db.writing {
            val week = meals(start)
            val meal = mealAt(week, day, slot)
            if (meal.status == MealStatus.SKIPPED) throw ConflictException("This meal was skipped: mark it eaten or planned to say what was in it")
            val recipe = recipes.linesWithIngredients(listOf(meal.recipeId))[meal.recipeId]
            val before = recipe?.let { adjustments.gramsEaten(start, meal, it) }
            adjustments.replace(start, meal, changes)
            if (recipe != null && before != null && shareTaken(start, meal, week)) {
                val after = adjustments.gramsEaten(start, meal, recipe)
                val moved = (before.keys + after.keys).filter { abs((before[it] ?: 0.0) - (after[it] ?: 0.0)) > 1e-9 }
                stock.retake(MealRef(start, day, slot), moved.associateWith { after[it] ?: 0.0 })
            }
            adjustments.lines(start, meal).copy(shortfalls = warnings(start, meal, week))
        }
    }

    /**
     * Whether [meal]'s share of the stock was taken ([eatFromStock]): it was eaten, it is the leftover lunch
     * of an eaten dinner (cooked with it), or what was taken for it is kept under its slot all the same; for a
     * meal of a prepped batch, and a batch cooked next to a meal as [forShopping] has it, the batch was made
     * ([settle]).
     */
    private fun shareTaken(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>): Boolean =
        meal.prep?.let { prep -> prep.meals.any { it.status == MealStatus.EATEN } }
            ?: (meal.status == MealStatus.EATEN || dinnerOf(meal, week)?.status == MealStatus.EATEN || holdsStock(start, meal))

    private fun mealAt(week: List<PlannedMeal>, day: Int, slot: MealSlot): PlannedMeal =
        week.firstOrNull { it.day == day && it.slot == slot } ?: throw NotFoundException("No meal planned for day $day $slot")

    /** The meals planned for one date; the plan week is the one starting on that date's Monday. */
    fun mealsOn(date: LocalDate): Triple<LocalDate, Int, List<PlannedMeal>> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val index = (date.toEpochDay() - start.toEpochDay()).toInt()
        val week = meals(start)
        val holding = holdingStock(start, week)
        val ofDay = week.filter { it.day == index }.sortedBy { it.slot.ordinal }.map { it.copy(cookPortions = cookPortions(it, week, holding)) }
        return Triple(start, index, adjustments.withNutrition(start, ofDay))
    }

    /**
     * The meals of [week] (starting [start]) that hold a share of the stock although they are not eaten
     * ([holdsStock]), by day and slot, for [toCook] and [cookPortions]: the leftover lunch of an eaten
     * dinner, also one whose eaten dinner a server before issue 165 removed.
     */
    private fun holdingStock(start: LocalDate, week: List<PlannedMeal>): Set<Pair<Int, MealSlot>> =
        week.filter { it.status != MealStatus.EATEN && holdsStock(start, it) }.mapTo(HashSet()) { it.day to it.slot }

    /** The meals of [week], the week starting [start] as [forShopping] has it, still to cook and to buy for ([toCook]). */
    fun stillToCook(start: LocalDate, week: List<PlannedMeal>): List<PlannedMeal> = toCook(week, holdingStock(start, week))

    /** Titles of meals planned from two weeks before [today] up to [today], newest first. */
    fun recentTitles(today: LocalDate): List<String> {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return listOf(start, start.minusDays(7), start.minusDays(14))
            .flatMap { week -> meals(week).filter { !week.plusDays(it.day.toLong()).isAfter(today) }.sortedByDescending { it.day } }
            .map { it.title }
            .distinct()
    }

    /**
     * Takes a meal out of the plan, unless it keeps its recipe ([requireNotCooked]): what the stock gave
     * for it would go with it, so un-eating could not give it back. The leftover lunch of an eaten dinner
     * is refused rather than given its share back: the dinner was cooked with it, so that share is used
     * whether the lunch is eaten or not (as skipping the lunch says, which keeps it). Its side goes with it,
     * unless the side stays ([whySideStays]).
     */
    fun remove(weekStart: String, day: Int, slot: MealSlot): WeekPlan {
        val start = parseWeekStart(weekStart)
        db.writing {
            val week = meals(start)
            requireReplaceable(start, week, day, slot)
            week.firstOrNull { it.day == day && it.slot == slot }?.let { meal -> whySideStays(start, meal)?.let { throw ConflictException(it) } }
            q.deleteMeal(userId, start.toString(), day.toLong(), slot.name)
        }
        return view(start)
    }

    /**
     * Why the side next to [meal] of the week starting [start] stays in the plan, or null when it has none or it may go
     * with its meal or on its own, which makes its batch smaller. It makes its batch, which goes only as a whole, as the
     * meal that makes one does ([whyKeepsItsBatch]); or its batch was made, so its share is in the box and in what the
     * stock gave.
     */
    internal fun whySideStays(start: LocalDate, meal: PlannedMeal): String? {
        val batch = meal.side?.let { Batch.load(db, userId, listOf(it.prep.id)).values.singleOrNull() } ?: return null
        return when {
            batch.made -> "The side is from a prepped batch that was made already: mark the meal skipped if it is not eaten"
            batch.isCook(start, meal, side = true) && batch.meals.size > 1 ->
                "The side makes the prepped batch for ${batch.laterMealsText()}: remove the prep to take those out too"
            batch.isCook(start, meal, side = true) -> "The side makes a prepped batch: remove the prep instead"
            else -> null
        }
    }

    private fun request(start: LocalDate, locked: List<PlannedMeal>, seed: Long) = PlanRequest(
        targets = targets(),
        candidates = recipes.planCandidates(
            onOffer = onOffer(start),
            dislikes = profiles.profile()?.dislikes.orEmpty(),
            prices = pricing(start, start.plusDays(DAYS - 1L)),
            onHand = stock.onHandFor(start),
            freshPacks = freshPacks(),
        ),
        routines = recipes.routines().let { r ->
            listOfNotNull(r.breakfast?.let { MealSlot.BREAKFAST to it }, r.snack?.let { MealSlot.SNACK to it }).toMap()
        },
        days = DAYS,
        locked = locked,
        // What was planned the week before counts as recently eaten.
        recent = meals(start.minusDays(DAYS.toLong())).mapTo(HashSet()) { it.recipeId },
        portions = profiles.portions(),
        priority = profiles.profile()?.priority ?: PlanPriority.BALANCED,
        seed = seed,
    )

    /**
     * The ingredients on offer during the week starting [start] at a store the account shops at:
     * those the planner prefers. The shopping list asks it for next week, to buy ahead only what
     * will not be on offer then.
     */
    fun onOffer(start: LocalDate): Set<Long> {
        val inUse = shops()
        return offersByIngredient(start, start.plusDays(DAYS - 1L)).filterValues { offers -> offers.any { it.store in inUse } }.keys
    }

    internal fun targets() = profiles.state().targets
        ?: throw ConflictException("Set up your profile first: the planner needs daily targets")

    internal fun view(start: LocalDate): WeekPlan {
        val prices = pricing(start, start.plusDays(DAYS - 1L))
        val costs = HashMap<Long, dev.tobins.mealplanner.api.RecipeCost?>()
        val week = meals(start)
        val holding = holdingStock(start, week)
        val costOf = { recipeId: Long -> costs.getOrPut(recipeId) { recipes.cost(recipeId, prices) } }
        val meals = adjustments.withNutrition(start, week).map { meal ->
            meal.copy(
                cost = costOf(meal.recipeId)?.let { it.perServingWithOffers * meal.portions },
                cookPortions = cookPortions(meal, week, holding),
                side = meal.side?.let { side -> side.copy(cost = costOf(side.recipeId)?.let { it.perServingWithOffers * side.portions }) },
            )
        }
        // A skipped meal is not eaten: it counts towards neither the day nor the week, nor does its side.
        val eaten = meals.filter { it.status != MealStatus.SKIPPED }
        // What the week costs with and without offers: of each meal's main and side whose cost is known.
        val priced = eaten.map { it.recipeId to it.portions } + eaten.mapNotNull { it.side }.map { it.recipeId to it.portions }
        val known = priced.mapNotNull { (id, portions) -> costOf(id)?.let { it to portions } }
        return WeekPlan(
            weekStart = start.toString(),
            targets = targets(),
            days = (0 until DAYS).map { day ->
                val ofDay = meals.filter { it.day == day }.sortedBy { it.slot.ordinal }
                val counted = ofDay.filter { it.status != MealStatus.SKIPPED }
                PlanDay(
                    start.plusDays(day.toLong()).toString(), ofDay,
                    counted.sumOf { it.totalKcal }, counted.sumOf { it.totalProteinG }, counted.sumOf { (it.fibreG ?: 0.0) + (it.side?.fibreG ?: 0.0) },
                )
            },
            emptySlots = MealSlot.entries.filter { slot -> meals.none { it.slot == slot } },
            cost = known.takeIf { it.isNotEmpty() }?.sumOf { (cost, portions) -> cost.perServingWithOffers * portions },
            costWithoutOffers = known.takeIf { it.isNotEmpty() }?.sumOf { (cost, portions) -> cost.perServing * portions },
        )
    }

    /**
     * The meals stored for the week starting [start], by day. A meal of a prepped batch has the batch
     * ([PlannedMeal.prep]), is locked, and is a leftover unless it is the meal that makes the batch
     * ([Batch.cook]): what an app that does not know preps shows.
     * A meal with a side taken from a batch has it ([PlannedMeal.side], [sideOf]).
     */
    fun meals(start: LocalDate): List<PlannedMeal> {
        val meals = q.selectWeek(userId, start.toString()).executeAsList().map {
            PlannedMeal(
                day = it.day.toInt(),
                slot = MealSlot.valueOf(it.slot),
                recipeId = it.recipeId,
                title = it.title,
                portions = it.portions,
                kcal = it.kcal,
                proteinG = it.proteinG,
                leftover = it.leftover != 0L,
                locked = it.locked != 0L,
                status = MealStatus.valueOf(it.status),
            )
        }
        val prepOf = q.selectWeekPrepIds(userId, start.toString()).executeAsList().associate { (it.day.toInt() to MealSlot.valueOf(it.slot)) to it.prepId }
        val sideOf = q.selectWeekSides(userId, start.toString()).executeAsList().associate { (it.day.toInt() to MealSlot.valueOf(it.slot)) to it.prepId }
        if (prepOf.isEmpty() && sideOf.isEmpty()) return meals
        val batches = Batch.load(db, userId, prepOf.values + sideOf.values)
        val recipesOfSides = HashMap<Long, PlanCandidate?>()
        return meals.map { meal ->
            val inBatch = prepOf[meal.day to meal.slot]?.let { batches[it] }
                ?.let { batch -> meal.copy(prep = batch.toApi(), leftover = !batch.isCook(start, meal), locked = true) }
                ?: meal
            val side = sideOf[meal.day to meal.slot]?.let { batches[it] }?.let { batch -> sideOf(start, meal, batch, recipesOfSides) }
            inBatch.copy(side = side)
        }
    }

    /**
     * The side of [batch] next to [meal] of the week starting [start], with the numbers of its portions of the recipe as
     * the recipe is now ([recipesOfSides] holds the recipes read so far, by id): there are none stored, as a side has no
     * row of its own. A recipe gone or without amounts counts as nothing.
     */
    private fun sideOf(start: LocalDate, meal: PlannedMeal, batch: Batch, recipesOfSides: MutableMap<Long, PlanCandidate?>): MealSide? {
        val side = batch.sideOf(start, meal) ?: return null
        val recipe = recipesOfSides.getOrPut(batch.recipeId) { recipes.candidate(batch.recipeId) }
        return MealSide(
            recipeId = batch.recipeId,
            title = batch.title,
            portions = side.share,
            kcal = (recipe?.kcal ?: 0.0) * side.share,
            proteinG = (recipe?.proteinG ?: 0.0) * side.share,
            fibreG = recipe?.let { it.fibreG * side.share },
            cookPortions = batch.cookPortions(start, meal, side = true),
            prep = batch.toApi(),
        )
    }

    /** The batches [meals] are eaten from, by id. */
    internal fun batchesOf(meals: List<PlannedMeal>): Map<Long, Batch> = Batch.load(db, userId, meals.mapNotNull { it.prep?.id })

    /**
     * The meals of the week starting [start] as its shopping list and the stock count them: a prepped batch
     * made this week is one meal of all its portions, where it is made, with the batch's status
     * ([Batch.status]); the other meals of a batch are not there, wherever it was made, as they cook and buy
     * nothing. So the batch is bought once, in the week it is made, also for meals the week after. A batch made
     * with a side is that one meal too, next to the meal the side goes with, in its slot ([isCookedAsSide]); the
     * meal's main is there as itself.
     */
    fun forShopping(start: LocalDate, week: List<PlannedMeal> = meals(start)): List<PlannedMeal> {
        val batches = batchesOf(week)
        val meals = week.mapNotNull { meal ->
            val prep = meal.prep ?: return@mapNotNull meal
            val batch = batches[prep.id]?.takeIf { it.isCook(start, meal) } ?: return@mapNotNull null
            meal.copy(portions = batch.portions, status = batch.status)
        }
        val sideBatches = Batch.load(db, userId, week.mapNotNull { it.side?.prep?.id })
        val cookedAsSides = week.mapNotNull { meal ->
            val side = meal.side ?: return@mapNotNull null
            val batch = sideBatches[side.prep.id]?.takeIf { it.isCook(start, meal, side = true) } ?: return@mapNotNull null
            // Of the recipe, not of the side: the side it is made with is 0 once its share is eaten elsewhere.
            val recipe = recipes.candidate(batch.recipeId)
            PlannedMeal(
                meal.day, meal.slot, batch.recipeId, batch.title, batch.portions,
                kcal = (recipe?.kcal ?: 0.0) * batch.portions, proteinG = (recipe?.proteinG ?: 0.0) * batch.portions,
                locked = true, status = batch.status, prep = side.prep,
            )
        }
        // A side is bought with its batch, where that is cooked: the meals here are their mains.
        return meals.map { it.copy(side = null) } + cookedAsSides
    }

    /**
     * Whether [meal], of the week starting [start] as [forShopping] has it, is a batch cooked next to the meal in its slot
     * as that meal's side, rather than the meal itself: its batch has a side there.
     */
    private fun isCookedAsSide(start: LocalDate, meal: PlannedMeal): Boolean {
        val date = start.plusDays(meal.day.toLong()).toString()
        return meal.prep?.meals?.any { it.side && it.date == date && it.slot == meal.slot } == true
    }

    /** Whether the week starting [start] has meals of its own: the meals of a batch made the week before are there before it is planned. */
    fun isPlanned(start: LocalDate): Boolean = forShopping(start).isNotEmpty()

    /** Saves [meal] in a batch: locked and eaten from batch [prepId]. Not a leftover in the table: that follows from the batch ([meals]). */
    internal fun saveInBatch(start: LocalDate, meal: PlannedMeal, prepId: Long) {
        save(start, meal.copy(leftover = false, locked = true, prep = null))
        q.insertPrepPortion(userId, start.toString(), meal.day.toLong(), meal.slot.name, prepId)
    }

    internal fun deleteMeal(start: LocalDate, day: Int, slot: MealSlot) = q.deleteMeal(userId, start.toString(), day.toLong(), slot.name)

    /**
     * Stores [meal] in its slot, in place of the meal there. What belonged to that meal goes with it (its share of a
     * batch, what it took from the stock, what was in it); its side stays, next to the new main, with what the stock
     * gave for it: the side was added to the slot by hand, and its batch is not made smaller by a new main.
     */
    internal fun save(start: LocalDate, meal: PlannedMeal) {
        val side = q.selectSidePortion(userId, start.toString(), meal.day.toLong(), meal.slot.name).executeAsOneOrNull()
        val sideRef = MealRef(start, meal.day, meal.slot, side = true)
        val taken = if (side == null) emptyList() else stock.taken(sideRef)
        q.upsertMeal(
            userId, start.toString(), meal.day.toLong(), meal.slot.name, meal.recipeId, meal.title, meal.portions,
            meal.kcal, meal.proteinG, if (meal.leftover) 1 else 0, if (meal.locked) 1 else 0, meal.status.name,
        )
        if (side != null) {
            q.insertSidePortion(userId, start.toString(), meal.day.toLong(), meal.slot.name, side.prepId, side.portions)
            stock.keep(sideRef, taken)
        }
    }

    companion object {
        /** The days of a plan week, Monday first. */
        const val DAYS = 7

        /**
         * The meals of [week] whose portions are still to be cooked, which is what is still to buy
         * for: the shopping list's [dev.tobins.mealplanner.api.ShoppingItem.toComeGrams]. It goes by
         * the rules of [eatFromStock], so the list never counts a share the stock went down for: an
         * eaten meal is cooked, and so is a leftover lunch whose dinner was eaten; a skipped meal is
         * not cooked, unless it is a leftover lunch whose dinner still is, as that dinner is cooked
         * with its leftovers (and takes their share when eaten). Un-eating a meal brings it back.
         *
         * [holding] are the meals, by day and slot, that hold a share of the stock although not eaten: their
         * share was taken, so they are cooked whatever the plan says now. That is only ever the leftover lunch
         * of an eaten dinner, which the rules above have as cooked while the dinner is in the plan; a server
         * before issue 165 could remove the dinner and leave the lunch with its share.
         */
        fun toCook(week: List<PlannedMeal>, holding: Set<Pair<Int, MealSlot>> = emptySet()): List<PlannedMeal> = week.filter { meal ->
            val dinner = dinnerOf(meal, week)
            when {
                meal.status == MealStatus.EATEN -> false
                // A prepped batch as [forShopping] has it: one meal with the batch's status. Before [holding]: a batch
                // cooked as a side is in the slot of a meal that may hold stock of its own.
                meal.prep != null -> meal.status == MealStatus.PLANNED
                (meal.day to meal.slot) in holding -> false
                dinner == null -> meal.status == MealStatus.PLANNED
                else -> dinner.status != MealStatus.EATEN && MealStatus.PLANNED in setOf(meal.status, dinner.status)
            }
        }

        /**
         * The servings to cook when [meal] is made ([PlannedMeal.cookPortions]): its own portions plus
         * those of the leftover lunch cooked with it, unless that lunch was eaten and took its own. A
         * skipped meal, an eaten leftover lunch (cooked already) and a leftover lunch whose dinner is in
         * the plan (cooked then, or skipped and then not cooked, as [toCook] has it) are 0, except the lunch of a skipped dinner, which
         * is cooked for itself. So for a week where a recipe is planned once, the amounts of
         * these servings are what the shopping list counts for it. A meal in [holding] was cooked, as for
         * [toCook]; a dinner still to cook does not cook such a lunch again.
         *
         * A meal of a prepped batch: the one that makes it cooks the whole batch, also when it was skipped itself,
         * as the batch is made that day all the same, unless every meal of it was skipped; the later ones cook nothing.
         */
        fun cookPortions(meal: PlannedMeal, week: List<PlannedMeal>, holding: Set<Pair<Int, MealSlot>> = emptySet()): Double {
            meal.prep?.let { prep ->
                return if (meal.leftover || prep.meals.all { it.status == MealStatus.SKIPPED }) 0.0 else prep.portions
            }
            if (meal.status == MealStatus.SKIPPED || (meal.leftover && meal.status == MealStatus.EATEN)) return 0.0
            if ((meal.day to meal.slot) in holding) return 0.0
            val dinner = dinnerOf(meal, week)
            if (dinner != null) return if (dinner.status == MealStatus.SKIPPED) meal.portions else 0.0
            // An eaten dinner says what it was cooked with; one still to cook leaves out what was cooked already.
            val leftovers = leftoversOf(meal, week)
                ?.takeIf { it.status != MealStatus.EATEN && (meal.status == MealStatus.EATEN || (it.day to it.slot) !in holding) }
            return meal.portions + (leftovers?.portions ?: 0.0)
        }

        /** The next day's leftover lunch cooked with [meal], when it is a dinner that has one. */
        private fun leftoversOf(meal: PlannedMeal, week: List<PlannedMeal>): PlannedMeal? = week.firstOrNull {
            meal.slot == MealSlot.DINNER && !meal.leftover && meal.prep == null && it.prep == null &&
                it.leftover && it.recipeId == meal.recipeId && it.day == meal.day + 1 && it.slot == MealSlot.LUNCH
        }

        /** The dinner [meal] is the leftovers of, when it is a leftover lunch and that dinner is in the plan. */
        private fun dinnerOf(meal: PlannedMeal, week: List<PlannedMeal>): PlannedMeal? = week.firstOrNull {
            meal.slot == MealSlot.LUNCH && meal.leftover && meal.prep == null && it.prep == null &&
                !it.leftover && it.recipeId == meal.recipeId && it.day == meal.day - 1 && it.slot == MealSlot.DINNER
        }
    }
}

/** The first day of a week's plan and shopping list, as the path gives it; a 400 when it is no date. */
internal fun parseWeekStart(weekStart: String): LocalDate = try {
    LocalDate.parse(weekStart)
} catch (e: DateTimeParseException) {
    throw IllegalArgumentException("weekStart must be an ISO date such as 2026-11-16")
}
