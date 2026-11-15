package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.planner.BuyAhead
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.planner.PlanRequest
import dev.tobins.mealplanner.planner.WeekPlanner
import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.shopping.ShopSection
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeParseException
import kotlin.random.Random

/** Thrown when a request cannot be carried out in the current state; mapped to 409. */
class ConflictException(message: String) : RuntimeException(message)

/** Week plans: generating them with [WeekPlanner] and storing the result. */
class PlanRepository(
    private val db: Database,
    private val userId: Long,
    private val recipes: RecipeRepository,
    private val profiles: ProfileRepository,
    private val ingredients: IngredientRepository,
    /** Offers valid between two dates at every store, grouped by ingredient id. */
    private val offersByIngredient: (LocalDate, LocalDate) -> Map<Long, List<Offer>> = { _, _ -> emptyMap() },
    /** Prices with the offers valid between two dates, at the stores the account shops at. */
    private val pricing: (LocalDate, LocalDate) -> Pricing = { _, _ -> Pricing.NONE },
    /** The stores the account shops at: only their offers make the planner prefer a meal. */
    private val shops: () -> Set<Store> = { Store.entries.toSet() },
    /** Regular euros per kg of the account's ingredients, by id; without the offers. */
    private val regularPerKg: () -> Map<Long, Double> = { emptyMap() },
    /** The shop section of each of the account's ingredients, by id. */
    private val sections: () -> Map<Long, ShopSection> = { emptyMap() },
    /** Whether each ingredient keeps until next week, by id, given the [sections]. */
    private val keeps: (Map<Long, ShopSection>) -> Map<Long, Boolean> = { emptyMap() },
    /** Which advice the account's shopping list gives. */
    private val advice: () -> ShoppingAdvice = { ShoppingAdvice() },
    /** The picture of the product each of the account's ingredients is priced with, by id. */
    private val productImages: () -> Map<Long, String> = { emptyMap() },
) {
    private val q = db.planQueries

    fun get(weekStart: String): WeekPlan = view(parse(weekStart))

    /** Replaces every unlocked meal of the week with a newly planned one. */
    fun generate(weekStart: String, seed: Long = Random.nextLong()): WeekPlan {
        val start = parse(weekStart)
        // Meals already eaten or skipped are history: they stay, like locked ones.
        val kept = stored(start).filter { it.locked || it.status != MealStatus.PLANNED }
        val planned = WeekPlanner.plan(request(start, locked = kept, seed = seed))
        db.transaction {
            q.deleteUnlocked(userId, start.toString())
            planned.filter { new -> kept.none { it.day == new.day && it.slot == new.slot } }.forEach { save(start, it.copy(locked = false)) }
        }
        return view(start)
    }

    /**
     * Picks a different recipe for one meal and keeps the rest of the week. When a dinner
     * changes, the next day's lunch made of its leftovers changes with it. When no other
     * recipe fits the meal, it is kept and a [ConflictException] says so.
     */
    fun reroll(weekStart: String, day: Int, slot: MealSlot, seed: Long = Random.nextLong()): WeekPlan {
        val start = parse(weekStart)
        val current = stored(start)
        val target = current.firstOrNull { it.day == day && it.slot == slot }
            ?: throw NotFoundException("No meal planned for day $day $slot")
        val dependentLunch = current.firstOrNull {
            slot == MealSlot.DINNER && it.day == day + 1 && it.slot == MealSlot.LUNCH && it.leftover && !it.locked
        }
        val replaced = listOfNotNull(target, dependentLunch)
        val planned = WeekPlanner.plan(
            request(start, locked = current - replaced.toSet(), seed = seed).copy(excluded = setOf(target.recipeId)),
        )
        val replacement = planned.firstOrNull { it.day == day && it.slot == slot }
        if (replacement == null || replacement.recipeId == target.recipeId) throw ConflictException("No other recipe fits this meal")
        db.transaction {
            for (old in replaced) {
                q.deleteMeal(userId, start.toString(), old.day.toLong(), old.slot.name)
                planned.firstOrNull { it.day == old.day && it.slot == old.slot }
                    ?.let { save(start, it.copy(locked = false, status = MealStatus.PLANNED)) }
            }
        }
        return view(start)
    }

    /**
     * Puts [candidate] in a slot, sized to the slot's share of the targets. A dinner's
     * leftovers replace the next day's leftover lunch, unless that lunch is locked or done.
     */
    fun place(weekStart: String, day: Int, slot: MealSlot, candidate: PlanCandidate): WeekPlan {
        val start = parse(weekStart)
        val targets = targets()
        val portions = WeekPlanner.portionsFor(candidate, targets.kcal * slot.share)
        val meal = PlannedMeal(day, slot, candidate.recipeId, candidate.title, portions, candidate.kcal * portions, candidate.proteinG * portions)
        val nextLunch = stored(start).firstOrNull {
            slot == MealSlot.DINNER && it.day == day + 1 && it.slot == MealSlot.LUNCH && it.leftover && !it.locked && it.status == MealStatus.PLANNED
        }
        db.transaction {
            save(start, meal)
            val remaining = candidate.servings - portions
            if (nextLunch != null) {
                if (remaining >= 0.5) {
                    val lunchPortions = WeekPlanner.portionsFor(candidate, targets.kcal * MealSlot.LUNCH.share, remaining)
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

    fun setLocked(weekStart: String, day: Int, slot: MealSlot, locked: Boolean): WeekPlan {
        val start = parse(weekStart)
        q.setLocked(if (locked) 1 else 0, userId, start.toString(), day.toLong(), slot.name)
        return view(start)
    }

    /**
     * Marks a meal eaten, skipped or planned again. Eating it is eating from what was bought ahead or
     * stocked up for it ([IngredientRepository.useUp]); planning it again does not bring that back,
     * and marking it eaten when it already is takes nothing more.
     */
    fun setStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus): WeekPlan {
        val start = parse(weekStart)
        db.transaction {
            val meals = stored(start)
            val meal = meals.firstOrNull { it.day == day && it.slot == slot }
            q.setStatus(status.name, userId, start.toString(), day.toLong(), slot.name)
            if (meal != null && status == MealStatus.EATEN && meal.status != MealStatus.EATEN) {
                ingredients.useUp(gramsCooked(meal, meals), start)
            }
        }
        return view(start)
    }

    /**
     * What cooking [meal] took from the pantry, by ingredient id ([gramsUsed]). A dinner is cooked
     * with its leftovers, so it takes the next day's leftover lunch's share too, and that lunch takes
     * nothing more: whether the leftovers are eaten or not, they were cooked.
     */
    private fun gramsCooked(meal: PlannedMeal, week: List<PlannedMeal>): Map<Long, Double?> {
        if (meal.leftover) return emptyMap()
        val leftovers = week.filter { it.leftover && it.recipeId == meal.recipeId && it.day == meal.day + 1 && it.slot == MealSlot.LUNCH }
        val recipe = recipes.linesWithIngredients(listOf(meal.recipeId))[meal.recipeId] ?: return emptyMap()
        return gramsUsed(recipe, meal.portions + leftovers.sumOf { it.portions })
    }

    /** The meals planned for one date; the plan week is the one starting on that date's Monday. */
    fun mealsOn(date: LocalDate): Triple<LocalDate, Int, List<PlannedMeal>> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val index = (date.toEpochDay() - start.toEpochDay()).toInt()
        return Triple(start, index, stored(start).filter { it.day == index }.sortedBy { it.slot.ordinal })
    }

    /** Titles of meals planned from two weeks before [today] up to [today], newest first. */
    fun recentTitles(today: LocalDate): List<String> {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return listOf(start, start.minusDays(7), start.minusDays(14))
            .flatMap { week -> stored(week).filter { !week.plusDays(it.day.toLong()).isAfter(today) }.sortedByDescending { it.day } }
            .map { it.title }
            .distinct()
    }

    fun remove(weekStart: String, day: Int, slot: MealSlot): WeekPlan {
        val start = parse(weekStart)
        q.deleteMeal(userId, start.toString(), day.toLong(), slot.name)
        return view(start)
    }

    /**
     * What the week's meals need. Each recipe is scaled to the portions planned for it,
     * leftovers included; staples are left out and only named as a reminder. Items come in
     * the order of the walk through the shop, by section and then by name; whether an item
     * is ticked or on hand does not move it. Next to it, the advice of [ShoppingList.buyAhead]
     * and [ShoppingList.stockUp], unless switched off or dismissed for the week.
     */
    fun shoppingList(weekStart: String): ShoppingList {
        val start = parse(weekStart)
        val meals = stored(start)
        val sectionOf = sections()
        val week = shoppingNeeds(planned(meals), recipes.linesWithIngredients(meals.map { it.recipeId }), sectionOf)
        val checked = q.selectChecks(userId, start.toString()).executeAsList().toSet()
        val onHand = ingredients.onHandFor(start)
        // Every store's offers: the app chooses among those of the stores it shows.
        val offers = offersByIngredient(start, start.plusDays(DAYS - 1L))
        val images = productImages()
        val perKg = regularPerKg()
        val items = week.needs.map { need ->
            val id = need.ingredient?.id
            need.toItem(need.key, need.key in checked, id in onHand, id?.let { offers[it] }.orEmpty(), id?.let { images[it] }, id?.let { perKg[it] })
        }

        val wanted = advice()
        val buyAheadDismissed = dismissedKey(ShoppingAdviceGroup.BUY_AHEAD) in checked
        val stockUpDismissed = dismissedKey(ShoppingAdviceGroup.STOCK_UP) in checked
        // Advice goes by the offers of the stores the account shops at only: it is about saving money there.
        val inUse = shops()
        val advisor = ShoppingAdvisor(
            offers = offers.mapValues { (_, all) -> all.filter { it.store in inUse } }.filterValues { it.isNotEmpty() },
            perKg = perKg,
            sectionOf = sectionOf,
            keeps = keeps(sectionOf),
            productImages = images,
            checked = checked,
        )
        // Buying ahead goes with a plan: without meals this week there is nothing to shop with.
        val buyAhead = if (wanted.buyAhead && !buyAheadDismissed && meals.isNotEmpty()) buyAhead(start, meals, advisor) else emptyList()
        val stockUp = if (wanted.stockUp && !stockUpDismissed) {
            val listed = week.needs.mapNotNullTo(HashSet()) { it.ingredient?.id } +
                buyAhead.map { it.key.removePrefix(ShoppingAdvisor.AHEAD_PREFIX).toLong() }
            advisor.stockUp(ingredients.list(), ingredients.usedIds(), onHand, listed)
        } else {
            emptyList()
        }
        return ShoppingList(
            weekStart = start.toString(),
            items = items,
            staples = week.staples,
            toTaste = week.toTaste,
            buyAhead = buyAhead,
            stockUp = stockUp,
            buyAheadDismissed = buyAheadDismissed,
            stockUpDismissed = stockUpDismissed,
        )
    }

    /** Planned meals as recipes to shop for: each recipe once, with all its portions (leftovers included). */
    private fun planned(meals: List<PlannedMeal>): List<PlannedRecipe> =
        meals.groupBy { it.recipeId }.map { (id, ofRecipe) -> PlannedRecipe(id, ofRecipe.first().title, ofRecipe.sumOf { it.portions }) }

    /**
     * What to buy now for next week's meals. When next week is planned already, those are its
     * meals as planned; otherwise a provisional choice among the dinners the planner could pick
     * then ([BuyAhead.provisional]), one batch each. Nothing of it is stored: next week is planned
     * as usual later, with what was ticked here on hand.
     */
    private fun buyAhead(start: LocalDate, thisWeek: List<PlannedMeal>, advisor: ShoppingAdvisor): List<ShoppingItem> {
        val next = start.plusDays(DAYS.toLong())
        val nextWeek = stored(next)
        val (meals, ratings) = if (nextWeek.isNotEmpty()) {
            planned(nextWeek) to emptyMap()
        } else {
            val targets = profiles.state().targets ?: return emptyList()
            val candidates = BuyAhead.provisional(
                recipes.planCandidates(dislikes = profiles.profile()?.dislikes.orEmpty()),
                targets,
                plannedThisWeek = thisWeek.mapTo(HashSet()) { it.recipeId },
            )
            candidates.map { PlannedRecipe(it.recipeId, it.title, it.servings.toDouble()) } to candidates.associate { it.recipeId to it.rating }
        }
        return advisor.buyAhead(
            meals,
            recipes.linesWithIngredients(meals.map { it.recipeId }),
            ratings,
            onHand = ingredients.onHandFor(next),
            onOfferNextWeek = onOffer(next),
        )
    }

    /**
     * Ticks an item off, or back on. A buy-ahead item ticked is on hand from next week, a stock-up
     * item from this week, in the amount the list advises buying; un-ticking takes that back (see
     * [IngredientRepository.putOnHandFrom]).
     */
    fun setChecked(weekStart: String, check: ShoppingCheck): ShoppingList {
        val start = parse(weekStart)
        require(!check.key.startsWith(DISMISSED_PREFIX)) { "Groups are dismissed at /shopping/dismissed/{group}" }
        val bought = if (check.checked) adviceItem(weekStart, check.key) else null
        db.transaction {
            onHandFromTick(start, check, bought?.grams)
            if (check.checked) q.insertCheck(userId, start.toString(), check.key) else q.deleteCheck(userId, start.toString(), check.key)
        }
        return shoppingList(weekStart)
    }

    /** The buy-ahead or stock-up item under [key] on the week's list as it is; null for any other key. */
    private fun adviceItem(weekStart: String, key: String): ShoppingItem? {
        if (!key.startsWith(ShoppingAdvisor.AHEAD_PREFIX) && !key.startsWith(ShoppingAdvisor.STOCK_PREFIX)) return null
        val list = shoppingList(weekStart)
        return (list.buyAhead + list.stockUp).firstOrNull { it.key == key }
    }

    /** [grams] is the amount bought; null when not known, such as for an item the list no longer advises. */
    private fun onHandFromTick(start: LocalDate, check: ShoppingCheck, grams: Double?) {
        val (id, from) = when {
            check.key.startsWith(ShoppingAdvisor.AHEAD_PREFIX) -> ingredientIn(check.key, ShoppingAdvisor.AHEAD_PREFIX) to start.plusDays(DAYS.toLong())
            check.key.startsWith(ShoppingAdvisor.STOCK_PREFIX) -> ingredientIn(check.key, ShoppingAdvisor.STOCK_PREFIX) to start
            else -> return
        }
        // Both refuse an ingredient of another account (NotFoundException), before anything is stored.
        if (check.checked) ingredients.putOnHandFrom(id, from, grams) else ingredients.takeBackOnHand(id, from)
    }

    private fun ingredientIn(key: String, prefix: String): Long =
        key.removePrefix(prefix).toLongOrNull() ?: throw IllegalArgumentException("Unknown shopping item $key")

    /** Hides one of the advice groups of the week's list, or with [dismissed] false shows it again. Nothing else changes. */
    fun setDismissed(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean): ShoppingList {
        val start = parse(weekStart)
        val key = dismissedKey(group)
        if (dismissed) q.insertCheck(userId, start.toString(), key) else q.deleteCheck(userId, start.toString(), key)
        return shoppingList(weekStart)
    }

    /** A dismissed group is kept with the week's ticks, under a key no item has. */
    private fun dismissedKey(group: ShoppingAdviceGroup) = "$DISMISSED_PREFIX${group.path}"

    private fun request(start: LocalDate, locked: List<PlannedMeal>, seed: Long) = PlanRequest(
        targets = targets(),
        candidates = recipes.planCandidates(
            onOffer = onOffer(start),
            dislikes = profiles.profile()?.dislikes.orEmpty(),
            prices = pricing(start, start.plusDays(DAYS - 1L)),
            onHand = ingredients.onHandFor(start),
        ),
        routines = recipes.routines().let { r ->
            listOfNotNull(r.breakfast?.let { MealSlot.BREAKFAST to it }, r.snack?.let { MealSlot.SNACK to it }).toMap()
        },
        days = DAYS,
        locked = locked,
        // What was planned the week before counts as recently eaten.
        recent = stored(start.minusDays(DAYS.toLong())).mapTo(HashSet()) { it.recipeId },
        seed = seed,
    )

    /** The ingredients on offer during the week at a store the account shops at. */
    private fun onOffer(start: LocalDate): Set<Long> {
        val inUse = shops()
        return offersByIngredient(start, start.plusDays(DAYS - 1L)).filterValues { offers -> offers.any { it.store in inUse } }.keys
    }

    private fun targets() = profiles.state().targets
        ?: throw ConflictException("Set up your profile first: the planner needs daily targets")

    private fun view(start: LocalDate): WeekPlan {
        val prices = pricing(start, start.plusDays(DAYS - 1L))
        val costs = HashMap<Long, dev.tobins.mealplanner.api.RecipeCost?>()
        val meals = stored(start).map { meal ->
            val cost = costs.getOrPut(meal.recipeId) { recipes.cost(meal.recipeId, prices) }
            meal.copy(cost = cost?.let { it.perServingWithOffers * meal.portions })
        }
        val priced = meals.filter { it.cost != null }
        return WeekPlan(
            weekStart = start.toString(),
            targets = targets(),
            days = (0 until DAYS).map { day ->
                val ofDay = meals.filter { it.day == day }.sortedBy { it.slot.ordinal }
                PlanDay(start.plusDays(day.toLong()).toString(), ofDay, ofDay.sumOf { it.kcal }, ofDay.sumOf { it.proteinG })
            },
            emptySlots = MealSlot.entries.filter { slot -> meals.none { it.slot == slot } },
            cost = priced.takeIf { it.isNotEmpty() }?.sumOf { it.cost!! },
            costWithoutOffers = priced.takeIf { it.isNotEmpty() }?.sumOf { costs[it.recipeId]!!.perServing * it.portions },
        )
    }

    private fun stored(start: LocalDate): List<PlannedMeal> = q.selectWeek(userId, start.toString()).executeAsList().map {
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

    private fun save(start: LocalDate, meal: PlannedMeal) = q.upsertMeal(
        userId, start.toString(), meal.day.toLong(), meal.slot.name, meal.recipeId, meal.title, meal.portions,
        meal.kcal, meal.proteinG, if (meal.leftover) 1 else 0, if (meal.locked) 1 else 0, meal.status.name,
    )

    private fun parse(weekStart: String): LocalDate = try {
        LocalDate.parse(weekStart)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("weekStart must be an ISO date such as 2026-11-16")
    }

    private companion object {
        const val DAYS = 7
        const val DISMISSED_PREFIX = "dismissed:"
    }
}
