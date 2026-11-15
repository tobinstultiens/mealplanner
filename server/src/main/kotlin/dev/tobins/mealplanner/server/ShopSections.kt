package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientSection
import dev.tobins.mealplanner.api.KeepsChoice
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.server.prices.StoreAisle
import dev.tobins.mealplanner.server.sections.FoodSections
import dev.tobins.mealplanner.server.sections.StoreSections
import dev.tobins.mealplanner.shopping.ShopSection

/**
 * Where one account's ingredients are in the shop, best source first: a section the account set
 * by hand; the store category of the product its regular price comes from (shared, like the
 * price, because both are looked up per ingredient name); the NEVO food it is linked to; else
 * Other, at the end of the list.
 *
 * Whether an ingredient keeps until next week follows from its section's shelf life, unless the
 * account set it by hand: the same kind of correction, for buying ahead.
 */
class ShopSections(private val ingredients: IngredientRepository, private val prices: PriceRepository) {
    /**
     * Every ingredient that can be on the shopping list, with its section. Negligible ones
     * (spices and the like) never are, so they have no section to correct.
     */
    fun list(): List<IngredientSection> {
        val byHand = ingredients.sectionsByHand()
        val keepsByHand = ingredients.keepsByHand()
        val aisles = prices.aisles()
        return ingredients.list().filterNot { it.negligible }.map { describe(it, byHand[it.id], keepsByHand[it.id], aisles) }
    }

    /** The section each ingredient's items go under, by ingredient id. */
    fun byIngredient(): Map<Long, ShopSection> {
        val byHand = ingredients.sectionsByHand()
        val aisles = prices.aisles()
        return ingredients.list().associate { it.id to (byHand[it.id] ?: automatic(it, aisles).first) }
    }

    /** Sets an ingredient's section by hand, or with a null section goes back to the automatic one. */
    fun set(id: Long, choice: SectionChoice): IngredientSection {
        val section = choice.section?.let {
            ShopSection.fromKey(it) ?: throw IllegalArgumentException("Unknown section $it; known are ${ShopSection.entries.joinToString()}")
        }
        val ingredient = ingredients.find(id) ?: throw NotFoundException("No ingredient with id $id")
        ingredients.setSection(id, section)
        return describe(ingredient, section, ingredients.keepsByHand()[id], prices.aisles())
    }

    /** Sets by hand whether an ingredient keeps until next week, or with null goes back to its section's answer. */
    fun setKeeps(id: Long, choice: KeepsChoice): IngredientSection {
        val ingredient = ingredients.find(id) ?: throw NotFoundException("No ingredient with id $id")
        ingredients.setKeeps(id, choice.keeps)
        return describe(ingredient, ingredients.sectionsByHand()[id], choice.keeps, prices.aisles())
    }

    /** Whether each ingredient keeps until next week, by ingredient id; [sections] as [byIngredient] gives them. */
    fun keepsByIngredient(sections: Map<Long, ShopSection>): Map<Long, Boolean> {
        val byHand = ingredients.keepsByHand()
        return sections.mapValues { (id, section) -> byHand[id] ?: section.shelfLife.keepsUntilNextWeek }
    }

    private fun describe(ingredient: Ingredient, byHand: ShopSection?, keepsByHand: Boolean?, aisles: Map<String, StoreAisle>): IngredientSection {
        val (automatic, source) = automatic(ingredient, aisles)
        val section = byHand ?: automatic
        return IngredientSection(
            ingredientId = ingredient.id,
            name = ingredient.name,
            section = section.name,
            automatic = automatic.name,
            source = source,
            byHand = byHand != null,
            keeps = keepsByHand ?: section.shelfLife.keepsUntilNextWeek,
            keepsAutomatic = section.shelfLife.keepsUntilNextWeek,
            keepsByHand = keepsByHand != null,
        )
    }

    /** The section from the data, with where it comes from in words. */
    private fun automatic(ingredient: Ingredient, aisles: Map<String, StoreAisle>): Pair<ShopSection, String> {
        aisles[ingredient.name.lowercase()]?.let { aisle ->
            StoreSections.forAisle(aisle)?.let { return it to "${aisle.store.label}: ${listOfNotNull(aisle.category, aisle.subCategory).joinToString(" › ")}" }
        }
        ingredient.food?.let { return FoodSections.forFood(it) to "NEVO food group: ${it.groupEn}" }
        return ShopSection.OTHER to "No store category or NEVO food"
    }
}
