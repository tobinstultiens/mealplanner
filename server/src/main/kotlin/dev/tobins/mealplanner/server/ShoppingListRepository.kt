package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.planner.BuyAhead
import dev.tobins.mealplanner.server.PlanRepository.Companion.DAYS
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.shopping.ShopSection
import java.time.LocalDate

/**
 * One account's shopping list for a week: what the planned meals need, the advice next to it, and
 * the ticks. The meals come from [plans], which plans them and never asks for the list; the ticks are
 * the account's own rows of `shoppingCheck`, kept under [ShoppingKey]s.
 */
class ShoppingListRepository(
    private val db: Database,
    private val userId: Long,
    private val plans: PlanRepository,
    private val recipes: RecipeRepository,
    private val profiles: ProfileRepository,
    private val ingredients: IngredientRepository,
    /** Offers valid between two dates at every store, grouped by ingredient id. */
    private val offersByIngredient: (LocalDate, LocalDate) -> Map<Long, List<Offer>> = { _, _ -> emptyMap() },
    /** The stores the account shops at: only their offers count for the advice. */
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

    /**
     * What the week's meals need. Each recipe is scaled to the portions planned for it,
     * leftovers included; staples are left out and only named as a reminder. Items come in
     * the order of the walk through the shop, by section and then by name; whether an item
     * is ticked or on hand does not move it. Next to it, the advice of [ShoppingList.buyAhead]
     * and [ShoppingList.stockUp], unless switched off or dismissed for the week.
     */
    fun get(weekStart: String): ShoppingList {
        val start = parseWeekStart(weekStart)
        val meals = plans.meals(start)
        val sectionOf = sections()
        val week = shoppingNeeds(planned(meals), recipes.linesWithIngredients(meals.map { it.recipeId }), sectionOf)
        val checked = checks(start)
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
        val buyAheadDismissed = ShoppingKey.Dismissed(ShoppingAdviceGroup.BUY_AHEAD) in checked
        val stockUpDismissed = ShoppingKey.Dismissed(ShoppingAdviceGroup.STOCK_UP) in checked
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
                buyAhead.mapNotNull { (ShoppingKey.parse(it.key) as? ShoppingKey.Ahead)?.id }
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

    /**
     * The keys ticked on the week's list, dismissed groups among them. A stored key that is no key
     * ([ShoppingKey.parseOrNull]) matched no row before either, so it is left out.
     */
    private fun checks(start: LocalDate): Set<ShoppingKey> =
        q.selectChecks(userId, start.toString()).executeAsList().mapNotNullTo(HashSet(), ShoppingKey::parseOrNull)

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
        val nextWeek = plans.meals(next)
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
            onOfferNextWeek = plans.onOffer(next),
        )
    }

    /**
     * Ticks an item off, or back on. A buy-ahead item ticked is on hand from next week, a stock-up
     * item from this week, in the amount the list advises buying; un-ticking takes that back (see
     * [IngredientRepository.putOnHandFrom]). A key no list has is refused.
     */
    fun setChecked(weekStart: String, check: ShoppingCheck): ShoppingList {
        val start = parseWeekStart(weekStart)
        val key = ShoppingKey.parse(check.key)
        require(key !is ShoppingKey.Dismissed) { "Groups are dismissed at /shopping/dismissed/{group}" }
        val bought = if (check.checked) adviceItem(weekStart, key) else null
        db.transaction {
            onHandFromTick(start, key, check.checked, bought?.grams)
            if (check.checked) q.insertCheck(userId, start.toString(), key.toString()) else q.deleteCheck(userId, start.toString(), key.toString())
        }
        return get(weekStart)
    }

    /** The buy-ahead or stock-up item under [key] on the week's list as it is; null for any other key. */
    private fun adviceItem(weekStart: String, key: ShoppingKey): ShoppingItem? {
        if (key !is ShoppingKey.Ahead && key !is ShoppingKey.Stock) return null
        val list = get(weekStart)
        return (list.buyAhead + list.stockUp).firstOrNull { it.key == key.toString() }
    }

    /** [grams] is the amount bought; null when not known, such as for an item the list no longer advises. */
    private fun onHandFromTick(start: LocalDate, key: ShoppingKey, checked: Boolean, grams: Double?) {
        val (id, from) = when (key) {
            is ShoppingKey.Ahead -> key.id to start.plusDays(DAYS.toLong())
            is ShoppingKey.Stock -> key.id to start
            else -> return
        }
        // Both refuse an ingredient of another account (NotFoundException), before anything is stored.
        if (checked) ingredients.putOnHandFrom(id, from, grams) else ingredients.takeBackOnHand(id, from)
    }

    /** Hides one of the advice groups of the week's list, or with [dismissed] false shows it again. Nothing else changes. */
    fun setDismissed(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean): ShoppingList {
        val start = parseWeekStart(weekStart)
        val key = ShoppingKey.Dismissed(group).toString()
        if (dismissed) q.insertCheck(userId, start.toString(), key) else q.deleteCheck(userId, start.toString(), key)
        return get(weekStart)
    }
}
