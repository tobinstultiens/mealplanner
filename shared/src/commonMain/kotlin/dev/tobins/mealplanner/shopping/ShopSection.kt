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
 */
enum class ShopSection(val title: String) {
    FRUIT_VEG("Fruit and vegetables"),
    BREAD("Bread"),
    DELI("Cold meats and cheese"),
    SAUCES("Sauces, spices and oil"),
    MEAT("Meat"),
    FISH("Fish"),
    VEGETARIAN("Vegetarian and plant-based"),
    DAIRY("Dairy and eggs"),
    READY_MEALS("Ready meals and salads"),
    WORLD("Pasta, rice and world foods"),
    TINS("Tins and jars"),
    BREAKFAST("Breakfast and spreads"),
    BAKING("Baking"),
    SNACKS("Snacks and sweets"),
    DRINKS("Drinks"),
    FROZEN("Frozen"),
    NON_FOOD("Household and non-food"),

    /** Unknown: last, so the rest of the list still reads in order. */
    OTHER("Other"),
    ;

    companion object {
        /** The section with this key, or null for one this version does not know. */
        fun fromKey(key: String?): ShopSection? = entries.firstOrNull { it.name == key }
    }
}
