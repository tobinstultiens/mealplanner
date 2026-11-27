package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.ManyAddedInput
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.server.accounts.Identity
import dev.tobins.mealplanner.server.accounts.UserRepository
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.ingredients.parseDelimited
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
import dev.tobins.mealplanner.server.recipes.CookingSiteReader
import dev.tobins.mealplanner.server.recipes.RecipeScope
import dev.tobins.mealplanner.server.shopping.ShopSections
import java.io.File
import java.time.LocalDate

/**
 * The owner's real recipes as the planner sees them, for the measuring tests ([PackRestMeasurementTest],
 * [PortionOvershootMeasurementTest]): the cooking repo's recipes, NEVO's nutrition, and the regular AH prices (and
 * so the packs) of `prices/ah-regular-prices-2026-11-19.csv`, looked up on that day.
 */
object RealRecipeFixture {
    /** The recipes as candidates with their fresh uses, and the names of the ingredients (by id) for saying what a plan leaves. */
    class Real(val candidates: List<PlanCandidate>, val names: Map<Long, String>)

    /** The recipes as candidates with their fresh uses; null, after saying so, when [cooking] or [nevo] is missing. */
    fun candidates(cooking: File, nevo: File): List<PlanCandidate>? = load(cooking, nevo)?.candidates

    /** As [candidates], with the names of the ingredients. */
    fun load(cooking: File, nevo: File): Real? {
        if (!File(cooking, "content").isDirectory || !nevo.isFile) {
            println("Cooking repo at ${cooking.absolutePath} or NEVO file at ${nevo.absolutePath} not found; skipping")
            return null
        }
        val db = openDatabase("jdbc:sqlite::memory:").second
        val foods = FoodRepository(db)
        foods.importNevo(nevo)
        val userId = UserRepository(db).resolve(Identity("measure", "measure")).id
        val ingredients = IngredientRepository(db, foods, userId)
        ingredients.applySeed()
        val offers = OfferRepository(db, emptyList())
        val prices = PriceRepository(db, null, foods, offers)
        val rows = parseDelimited(checkNotNull(javaClass.getResource("/prices/ah-regular-prices-2026-11-19.csv")).readText(), ',').drop(1)
        for (row in rows) {
            val (name, packSize, packPrice, perKg) = row
            db.priceQueries.upsertPrice(
                name, "AH", name, packSize.ifBlank { null }, packPrice.toDoubleOrNull(), perKg.toDouble(), "2026-11-19T00:00:00Z",
                row[4].ifBlank { null }, row[5].ifBlank { null }, null,
            )
        }
        val recipes = RecipeScope(db, ingredients, userId) { from, until -> prices.pricing(ingredients, from, until) }
        recipes.bankSync.syncCookingSite(checkNotNull(CookingSiteReader(cooking).readAll()))
        recipes.mine.setAdded(ManyAddedInput(recipes.view.bank().map { it.id }, added = true))
        val sections = ShopSections(ingredients, prices)
        val packs = prices.freshPacks(ingredients, sections.keepsByIngredient(sections.byIngredient()))
        val week = LocalDate.of(2026, 11, 16)
        val pricing = prices.pricing(ingredients, week, week.plusDays(6))
        val candidates = recipes.candidates.planCandidates(prices = pricing, freshPacks = packs)
        println("${candidates.size} candidates, ${candidates.count { it.freshUses.isNotEmpty() }} with fresh uses, ${packs.size} fresh ingredients with a pack")
        return Real(candidates, ingredients.list().associate { it.id to it.name })
    }
}
