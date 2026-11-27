package dev.tobins.mealplanner.server.shopping.sections

import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.prices.StoreAisle
import dev.tobins.mealplanner.shopping.ShopSection

/**
 * The shop section of a product from where the store files it. Only AH's categories are known:
 * the regular prices come from AH. Its main categories mostly follow the shop floor; the
 * sub-categories below are those seen in AH's data (2026-11-15) that sit elsewhere in a shop
 * than their main category says, such as flour under "Bakkerij" and canned kidney beans under
 * "Groente, aardappelen".
 */
object StoreSections {
    private val byCategory: Map<String, ShopSection> = mapOf(
        "Groente, aardappelen" to ShopSection.FRUIT_VEG,
        "Fruit, verse sappen" to ShopSection.FRUIT_VEG,
        "Bakkerij" to ShopSection.BREAD,
        "Vleeswaren" to ShopSection.DELI,
        "Kaas" to ShopSection.DELI,
        "Soepen, sauzen, kruiden, olie" to ShopSection.SAUCES,
        "Vlees" to ShopSection.MEAT,
        "Vis" to ShopSection.FISH,
        "Vegetarisch, vegan en plantaardig" to ShopSection.VEGETARIAN,
        "Zuivel, eieren" to ShopSection.DAIRY,
        "Maaltijden, salades" to ShopSection.READY_MEALS,
        "Pasta, rijst, wereldkeuken" to ShopSection.WORLD,
        "Ontbijtgranen, beleg" to ShopSection.BREAKFAST,
        "Koek, snoep, chocolade" to ShopSection.SNACKS,
        "Borrel, chips, snacks" to ShopSection.SNACKS,
        "Koffie, thee" to ShopSection.DRINKS,
        "Frisdrank, sappen, water" to ShopSection.DRINKS,
        "Bier, wijn, aperitieven" to ShopSection.DRINKS,
        "Diepvries" to ShopSection.FROZEN,
        "Drogisterij" to ShopSection.NON_FOOD,
        "Huishouden" to ShopSection.NON_FOOD,
    )

    private val bySubCategory: Map<String, ShopSection> = mapOf(
        "Tarwebloem" to ShopSection.BAKING,
        "Bindmiddel" to ShopSection.BAKING,
        "Suiker, poedersuiker" to ShopSection.BAKING,
        "Tomatenblokjes" to ShopSection.TINS,
        "Kidney bonen" to ShopSection.TINS,
        "Kokosmelk" to ShopSection.WORLD,
    )

    /** The section for a product filed under [aisle], or null when the category is unknown. */
    fun forAisle(aisle: StoreAisle): ShopSection? {
        if (aisle.store != Store.AH) return null
        return bySubCategory[aisle.subCategory] ?: byCategory[aisle.category]
    }
}
