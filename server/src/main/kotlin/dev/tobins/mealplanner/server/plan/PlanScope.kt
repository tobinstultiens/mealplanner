package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.planner.Pack
import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.server.accounts.ProfileRepository
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.recipes.RecipeScope
import dev.tobins.mealplanner.server.serverToday
import java.time.LocalDate

/**
 * One account's plan: its store ([PlanStore]) and the classes with its rules, built together as they call each other.
 * The account's id goes to the store and to the classes of [MealAdjustments] and [SlotFoods] only, so every rule reads
 * and writes that account's plan and no other. See docs/architecture.md for what each class does.
 */
class PlanScope(
    db: Database,
    userId: Long,
    recipes: RecipeScope,
    profiles: ProfileRepository,
    stock: StockRepository,
    /** Offers valid between two dates at every store, grouped by ingredient id. */
    offersByIngredient: (LocalDate, LocalDate) -> Map<Long, List<Offer>> = { _, _ -> emptyMap() },
    /** Prices with the offers valid between two dates, at the stores the account shops at. */
    pricing: (LocalDate, LocalDate) -> Pricing = { _, _ -> Pricing.NONE },
    /** The stores the account shops at: only their offers make the planner prefer a meal. */
    shops: () -> Set<Store> = { Store.entries.toSet() },
    /** The packs of the account's fresh ingredients, by ingredient id: the planner uses up the rest of an opened one. */
    freshPacks: () -> Map<Long, Pack> = { emptyMap() },
    /** Today as the household sees it: a dinner moves only between today and the days after it (issue 255). */
    today: () -> LocalDate = ::serverToday,
) {
    val store = PlanStore(db, userId)
    val adjustments = MealAdjustments(db, userId, recipes.candidates, recipes.candidates.ingredients)
    val foods = SlotFoods(db, userId, recipes.candidates)
    val weekMeals = WeekMeals(store, recipes.candidates, stock, adjustments, foods)
    val keptMeals = KeptMeals(store, weekMeals)
    val costs = WeekCosts(weekMeals, recipes.candidates, stock, adjustments, foods, offersByIngredient, pricing, shops, freshPacks)
    val view = WeekView(store, weekMeals, costs, adjustments, foods, profiles)
    val shortfalls = WeekShortfalls(store, weekMeals, adjustments, recipes.candidates, stock)
    val weeks = WeekPlans(store, weekMeals, keptMeals, costs, view, recipes.candidates, recipes.mine, profiles, stock, freshPacks)
    val eating = Eating(store, weekMeals, view, shortfalls, adjustments, foods, recipes.candidates, stock)
    val changes = MealChanges(store, weekMeals, keptMeals, weeks, eating, view, adjustments, foods, recipes.candidates, recipes.mine, profiles, stock, today)
    val foodChanges = FoodChanges(store, weekMeals, changes, eating, view, foods)
}
