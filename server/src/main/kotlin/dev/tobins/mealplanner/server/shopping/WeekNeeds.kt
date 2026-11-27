package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PlannedSlot
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.server.db.ShoppingExtra
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.plan.Batch
import dev.tobins.mealplanner.server.plan.Shortfalls
import dev.tobins.mealplanner.server.plan.WeekMeals
import dev.tobins.mealplanner.server.plan.WeekShortfalls
import dev.tobins.mealplanner.server.recipes.RecipeCandidates
import dev.tobins.mealplanner.server.recipes.RecipeLines
import dev.tobins.mealplanner.shopping.ShopSection
import java.time.LocalDate

/**
 * What a week's shopping list is to hold, row by row in its shop section: what the planned meals need, all of them and
 * those still to cook, and the account's own items. The meals come from [weekMeals], as the plan has them, which never
 * asks for the list. [ShoppingListView] makes the list of it.
 */
class WeekNeeds(
    private val store: ShoppingStore,
    private val weekMeals: WeekMeals,
    private val weekShortfalls: WeekShortfalls,
    private val recipes: RecipeCandidates,
    private val ingredients: IngredientRepository,
    /** The shop section of each of the account's ingredients, by id. */
    private val sections: () -> Map<Long, ShopSection>,
) {
    /**
     * What a week needs: [meals] as the list shops for them, the shop section of each ingredient ([sectionOf], by
     * id), what the meals of the [week] need and what those still to cook need ([toCook], by key), and the own items
     * ([ownRows] as stored, [own] as rows of the list).
     */
    internal class Needs(
        val meals: List<PlannedMeal>,
        val sectionOf: Map<Long, ShopSection>,
        val week: ShoppingNeeds,
        val toCook: Map<ShoppingKey, ShoppingNeed>,
        val ownRows: List<ShoppingExtra>,
        val own: List<ShoppingNeed>,
    )

    /** What the week starting [start] needs ([Needs]). */
    internal fun of(start: LocalDate): Needs {
        // A prepped batch is bought in the week it is made, as one meal of all its portions.
        val meals = weekMeals.forShopping(start)
        val sectionOf = sections()
        val lines = recipes.linesWithIngredients(meals.map { it.recipeId })
        val week = needsOf(start, meals, sectionOf, lines)
        // The stock went down for the meals eaten, so what is still needed goes by the meals still to cook.
        val toCook = needsOf(start, weekMeals.stillToCook(start, meals), sectionOf, lines).needs.associateBy { it.key }
        countTaken(weekShortfalls.shortfallsIn(start, meals), week.needs.associateBy { it.key }, toCook)
        val ownRows = store.ownItemsOf(start)
        val own = ownNeeds(ownRows, sectionOf)
        return Needs(meals, sectionOf, week, toCook, ownRows, own)
    }

    /** The earliest week before the one starting [start] whose list bought a batch eaten in it ([dev.tobins.mealplanner.api.ShoppingList.preppedBoughtIn]). */
    internal fun preppedBoughtIn(start: LocalDate): String? =
        weekMeals.of(start).flatMap { listOfNotNull(it.prep, it.side?.prep) }
            .map { Batch.mondayOf(LocalDate.parse(it.madeOn)) }.filter { it < start }.minOrNull()?.toString()

    /**
     * What [meals] need, in walking order, with the staples and to-taste names apart. [lines] are their
     * recipes' lines, which the list reads once for the week's meals and for those still to cook.
     */
    private fun needsOf(start: LocalDate, meals: List<PlannedMeal>, sectionOf: Map<Long, ShopSection>, lines: Map<Long, RecipeLines>): ShoppingNeeds =
        shoppingNeeds(planned(start, meals), lines, sectionOf)

    /**
     * Counts what adjusted meals took of what was at home beyond their recipes as their need (issue 226), on
     * the rows of the ingredients that leaves a meal still to cook short of ([WeekShortfalls.shortfallsIn]): in
     * the week's need ([week]), and for a meal still to cook in that of the meals to come ([toCook]), with the
     * meal named among the row's. So a ticked row needs more than was bought, and one not ticked more than is on
     * hand, by what the later meal is short. Every other row stays as the recipes have it, as an adjustment buys
     * nothing itself, and so does a row whose weight is not known in full, as there is nothing to add it to.
     */
    private fun countTaken(shortfalls: Shortfalls, week: Map<ShoppingKey, ShoppingNeed>, toCook: Map<ShoppingKey, ShoppingNeed>) {
        for ((id, taken) in shortfalls.taken) {
            val key = ShoppingKey.Ingredient(id)
            val need = week[key]?.takeIf { it.totalGrams != null } ?: continue
            for (took in taken) {
                val rows = listOfNotNull(need, toCook[key]?.takeIf { took.demand.toCook })
                for (row in rows) {
                    row.grams += took.grams
                    row.recipes += took.demand.meal.title
                }
            }
        }
    }

    /**
     * The account's own items as rows of the list: no amounts and no recipes. One whose name is an
     * ingredient of the catalogue, found the way a recipe line's is, has that ingredient's section (and
     * with it its offers, picture and on-hand mark); it stays a row of its own next to the meals' row
     * for that ingredient, so the meals' amounts stay readable. Any other is in section Other.
     */
    private fun ownNeeds(rows: List<ShoppingExtra>, sectionOf: Map<Long, ShopSection>): List<ShoppingNeed> {
        if (rows.isEmpty()) return emptyList()
        val resolve = ingredients.resolver()
        return rows.map { row ->
            val ingredient = resolve(IngredientLine(null, null, null, null, row.name, null))
            ShoppingNeed(ShoppingKey.Extra(row.id), ingredient, row.name, ingredient?.let { sectionOf[it.id] } ?: ShopSection.OTHER)
        }
    }
}

/**
 * Planned meals of the week starting [start] as recipes to shop for: each recipe once, with all its portions (leftovers
 * included). The foods of a slot (issue 358) are a recipe of that slot alone, named by the slot rather than a title.
 */
internal fun planned(start: LocalDate, meals: List<PlannedMeal>): List<PlannedRecipe> =
    meals.groupBy { it.recipeId }.map { (id, ofRecipe) ->
        val first = ofRecipe.first()
        val slot = if (first.foodsOnly) PlannedSlot(start.plusDays(first.day.toLong()).toString(), first.slot) else null
        PlannedRecipe(id, first.title, ofRecipe.sumOf { it.portions }, foodsIn = slot)
    }
