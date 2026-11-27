package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.server.accounts.ProfileRepository
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.plan.DueNotifications
import dev.tobins.mealplanner.server.plan.PlanScope
import dev.tobins.mealplanner.server.plan.PlanningRepository
import dev.tobins.mealplanner.server.plan.PrepRepository
import dev.tobins.mealplanner.server.plan.StandardMealRepository
import dev.tobins.mealplanner.server.prices.DealVerdictRepository
import dev.tobins.mealplanner.server.prices.OfferExclusionRepository
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceHistoryRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
import dev.tobins.mealplanner.server.recipes.GenerationService
import dev.tobins.mealplanner.server.recipes.RecipePictures
import dev.tobins.mealplanner.server.recipes.RecipeScope
import dev.tobins.mealplanner.server.recipes.RecipeToppings
import dev.tobins.mealplanner.server.recipes.generation.RecipeGenerator
import dev.tobins.mealplanner.server.shopping.ShopSections
import dev.tobins.mealplanner.server.shopping.ShoppingAdviceRepository
import dev.tobins.mealplanner.server.shopping.ShoppingListRepository
import dev.tobins.mealplanner.server.shopping.ShopsRepository
import dev.tobins.mealplanner.server.tracking.TrackingRepository
import java.io.File
import java.time.Instant
import java.time.LocalDate

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
    /** Today as the household sees it; a test may set the day. */
    today: () -> LocalDate = ::serverToday,
) {
    val ingredients = IngredientRepository(db, foods, accountId)
    val stock = StockRepository(db, foods, accountId)
    val shops = ShopsRepository(db, accountId)
    /** The recipes' store and the classes with their rules ([RecipeScope]). */
    val recipes = RecipeScope(db, ingredients, accountId, today) { from, until -> prices.pricing(ingredients, from, until, shops.inUse()) }
    val profiles = ProfileRepository(db, accountId)
    val toppings = RecipeToppings(db, accountId, ingredients, recipes.view, recipes.candidates)
    val pictures = RecipePictures(db, imageDir, accountId)
    val sections = ShopSections(ingredients, prices)
    val shoppingAdvice = ShoppingAdviceRepository(db, accountId)
    val dealVerdicts = DealVerdictRepository(db, offers, ingredients, shops::inUse)
    /** The verdicts on today's offers, judged once for the request: the shopping list's advice and its answer share them. */
    val judgement by lazy { dealVerdicts.judgement(Instant.now()) }
    val offerExclusions = OfferExclusionRepository(db, accountId, ingredients)
    /** The plan's store and the classes with its rules ([PlanScope]). */
    val plan by lazy {
        PlanScope(
            db, accountId, recipes, profiles, stock,
            offersByIngredient = { from, until -> prices.offers(ingredients, from, until) },
            pricing = { from, until -> prices.pricing(ingredients, from, until, shops.inUse()) },
            shops = shops::inUse,
            freshPacks = { prices.freshPacks(ingredients, sections.keepsByIngredient(sections.byIngredient())) },
            today = today,
        )
    }
    val preps by lazy { PrepRepository(db, accountId, plan.weekMeals, plan.keptMeals, plan.view, recipes.candidates, recipes.mine, profiles, today) }
    val planning by lazy { PlanningRepository(db, accountId, plan.weekMeals, plan.keptMeals, plan.weeks, plan.costs, plan.view, recipes.candidates, recipes.mine, stock, today) }
    val standardMeals by lazy { StandardMealRepository(db, recipes.candidates, recipes.mine, ingredients, stock, plan.changes, plan.keptMeals, profiles) }
    val shopping by lazy {
        ShoppingListRepository(
            db, accountId, plan.weekMeals, plan.shortfalls, plan.costs, recipes.candidates, profiles, ingredients, stock,
            offersByIngredient = { from, until -> prices.offers(ingredients, from, until) },
            shops = shops::inUse,
            regularPerKg = { prices.perKg(ingredients) },
            sections = sections::byIngredient,
            keeps = sections::keepsByIngredient,
            withVerdicts = { judgement.onOffers(it) },
            advice = shoppingAdvice::setting,
            productImages = { prices.productImages(ingredients) },
            offered = PriceHistoryRepository(db)::offered,
            today = today,
        )
    }
    val tracking by lazy { TrackingRepository(db, accountId, plan.weekMeals, profiles, ingredients) }
    val notifications by lazy { DueNotifications(shopping, plan.weekMeals, preps, tracking, profiles, shops::inUse, today) }
    val generation by lazy { generator?.let { GenerationService(it, recipes.edits, recipes.view, recipes.mine, ingredients, stock, profiles, plan.weekMeals, offers, shops::inUse) } }
}
