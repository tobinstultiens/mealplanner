package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.generation.RecipeGenerator
import java.io.File

/**
 * The repositories of one account. Built per request; they hold no state of their own,
 * so this is cheap. Everything a route reads or writes for a user goes through here,
 * which is what keeps one account's data away from another's.
 */
class UserScope(
    val accountId: Long,
    db: Database,
    foods: FoodRepository,
    offers: OfferRepository,
    prices: PriceRepository,
    generator: RecipeGenerator?,
    /** Where recipe pictures are kept, for every account (see [RecipePictures]). */
    imageDir: File,
) {
    val ingredients = IngredientRepository(db, foods, accountId)
    val shops = ShopsRepository(db, accountId)
    val recipes = RecipeRepository(db, ingredients, accountId) { from, until -> prices.pricing(ingredients, from, until, shops.inUse()) }
    val profiles = ProfileRepository(db, accountId)
    val pictures = RecipePictures(db, imageDir, accountId)
    val sections = ShopSections(ingredients, prices)
    val shoppingAdvice = ShoppingAdviceRepository(db, accountId)
    val plans by lazy {
        PlanRepository(
            db, accountId, recipes, profiles, ingredients,
            offersByIngredient = { from, until -> prices.offers(ingredients, from, until) },
            pricing = { from, until -> prices.pricing(ingredients, from, until, shops.inUse()) },
            shops = shops::inUse,
        )
    }
    val shopping by lazy {
        ShoppingListRepository(
            db, accountId, plans, recipes, profiles, ingredients,
            offersByIngredient = { from, until -> prices.offers(ingredients, from, until) },
            shops = shops::inUse,
            regularPerKg = { prices.perKg(ingredients) },
            sections = sections::byIngredient,
            keeps = sections::keepsByIngredient,
            advice = shoppingAdvice::setting,
            productImages = { prices.productImages(ingredients) },
        )
    }
    val tracking by lazy { TrackingRepository(db, accountId, plans, profiles) }
    val generation by lazy { generator?.let { GenerationService(it, recipes, ingredients, profiles, plans, offers, shops::inUse) } }
}
