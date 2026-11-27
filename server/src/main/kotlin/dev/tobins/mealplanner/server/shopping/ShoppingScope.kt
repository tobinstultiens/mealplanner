package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.accounts.ProfileRepository
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.plan.PlanScope
import dev.tobins.mealplanner.server.prices.PriceHistoryRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
import dev.tobins.mealplanner.server.recipes.RecipeScope
import dev.tobins.mealplanner.server.serverToday
import java.time.LocalDate

/**
 * One account's shopping list: its store ([ShoppingStore]) and the classes with its rules, built together as they
 * call each other. The account's id goes to the store alone, so every rule reads and writes that account's list and no
 * other. See docs/architecture.md for what each class does.
 */
class ShoppingScope(
    db: Database,
    userId: Long,
    plan: PlanScope,
    recipes: RecipeScope,
    profiles: ProfileRepository,
    ingredients: IngredientRepository,
    stock: StockRepository,
    /** The prices and offers of the account's ingredients, which are shared. */
    prices: PriceRepository,
    sections: ShopSections,
    shoppingAdvice: ShoppingAdviceRepository,
    /** The stores the account shops at: only their offers count for the advice and for what a tick saves. */
    shops: () -> Set<Store>,
    /** Offers with the price verdicts on them, for the stock-up advice. */
    withVerdicts: (List<Offer>) -> List<Offer>,
    /** Today as the household sees it: the day a tick's saving is worked out for. */
    today: () -> LocalDate = ::serverToday,
) {
    val store = ShoppingStore(db, userId)
    val needs = WeekNeeds(store, plan.weekMeals, plan.shortfalls, recipes.candidates, ingredients, sections::byIngredient)
    val buyingAhead = BuyingAhead(plan.weekMeals, plan.costs, recipes.candidates, profiles, stock)
    val advice = ListAdvice(buyingAhead, ingredients, shoppingAdvice::setting, shops, sections::keepsByIngredient, withVerdicts)
    val view = ShoppingListView(
        store, needs, advice, ingredients, stock,
        offersByIngredient = { from, until -> prices.offers(ingredients, from, until) },
        regularPerKg = { prices.perKg(ingredients) },
        productImages = { prices.productImages(ingredients) },
    )
    val ownItems = OwnItems(store, view)
    val ticking = Ticking(store, view, ownItems, stock, shops, PriceHistoryRepository(db)::offered, today)
}
