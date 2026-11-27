package dev.tobins.mealplanner.server.recipes

import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.serverToday
import java.time.LocalDate

/**
 * One account's recipes: their store ([RecipeStore]) and the classes with their rules, built together as they call each
 * other. The account's id goes to the store alone, so every rule reads and writes that account's recipes and no other.
 * See docs/architecture.md for what each class does.
 */
class RecipeScope(
    db: Database,
    /** The catalogue the recipes' lines mean ingredients of. */
    ingredients: IngredientRepository,
    userId: Long,
    /** Today as the household sees it: this week's prices, and which planned weeks are over. */
    today: () -> LocalDate = ::serverToday,
    /** Prices with the offers valid between two dates. */
    pricing: (LocalDate, LocalDate) -> Pricing = { _, _ -> Pricing.NONE },
) {
    val store = RecipeStore(db, userId)
    val view = RecipeView(store, ingredients, today, pricing)
    val candidates = RecipeCandidates(store, ingredients)
    val mine = MyRecipes(store, view, ingredients, today)
    val edits = RecipeEdits(store, view, mine, candidates, ingredients)
    val bankSync = BankSync(store)
}
