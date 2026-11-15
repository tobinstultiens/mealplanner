package dev.tobins.mealplanner.shopping

/**
 * The sections of a Dutch supermarket in the order you walk through it, modelled on Albert
 * Heijn: fresh produce at the entrance, then bread, the deli, sauces, the chilled wall (meat,
 * fish, dairy, ready meals), the aisles one theme at a time, frozen, and non-food last.
 * The shopping list is sorted in this order, so the entries' order is the route.
 *
 * The API names a section by its [title] (a shopping item's `category`) or by its key, the
 * entry's [name]; never by position, so sections can be added or moved without breaking
 * older apps.
 *
 * [shelfLife] is what the section's food typically keeps for at home: the default for whether an
 * ingredient may be bought ahead for next week or stocked up on, which an account can correct
 * per ingredient.
 */
enum class ShopSection(val title: String, val shelfLife: ShelfLife) {
    FRUIT_VEG("Fruit and vegetables", ShelfLife.SHORT),
    BREAD("Bread", ShelfLife.SHORT),
    DELI("Cold meats and cheese", ShelfLife.SHORT),
    SAUCES("Sauces, spices and oil", ShelfLife.LONG),
    MEAT("Meat", ShelfLife.FREEZER),
    FISH("Fish", ShelfLife.FREEZER),
    VEGETARIAN("Vegetarian and plant-based", ShelfLife.SHORT),
    // Mostly fresh milk and yoghurt; long-life milk is corrected per ingredient.
    DAIRY("Dairy and eggs", ShelfLife.SHORT),
    READY_MEALS("Ready meals and salads", ShelfLife.SHORT),
    WORLD("Pasta, rice and world foods", ShelfLife.LONG),
    TINS("Tins and jars", ShelfLife.LONG),
    BREAKFAST("Breakfast and spreads", ShelfLife.LONG),
    BAKING("Baking", ShelfLife.LONG),
    SNACKS("Snacks and sweets", ShelfLife.LONG),
    DRINKS("Drinks", ShelfLife.LONG),
    FROZEN("Frozen", ShelfLife.LONG),
    NON_FOOD("Household and non-food", ShelfLife.LONG),

    /** Unknown: last, so the rest of the list still reads in order. Assumed not to keep, to be safe. */
    OTHER("Other", ShelfLife.SHORT),
    ;

    companion object {
        /** The section with this key, or null for one this version does not know. */
        fun fromKey(key: String?): ShopSection? = entries.firstOrNull { it.name == key }
    }
}

/** How long food keeps at home once bought. */
enum class ShelfLife {
    /** Days: fresh produce, bread, chilled food. Bought in the week it is eaten. */
    SHORT,

    /** Until next week in the freezer: fresh meat and fish. Worth buying ahead for a planned meal. */
    FREEZER,

    /** Months on the shelf or in the freezer: tins, dry goods, frozen food. Worth stocking up on. */
    LONG,
    ;

    /** True when it can be bought this week for a meal of next week. */
    val keepsUntilNextWeek: Boolean get() = this != SHORT
}
