package dev.tobins.mealplanner.server.prices

/**
 * Where AH sells each kind of food: NEVO food groups mapped to AH's main categories.
 * A product for an ingredient must come from one of its group's aisles, which keeps out
 * flavoured products from other aisles ("croissants cinnamon", "cumin cheese", "sugar snaps").
 * Checked against AH's search on 2026-11-13; canned goods sit under "Pasta, rijst, wereldkeuken"
 * and sugar under "Koffie, thee".
 */
object AhAisles {
    private const val VEGETABLES = "Groente, aardappelen"
    private const val FRUIT = "Fruit, verse sappen"
    private const val WORLD = "Pasta, rijst, wereldkeuken"
    private const val SAUCES = "Soepen, sauzen, kruiden, olie"
    private const val DAIRY = "Zuivel, eieren"
    private const val MEAT = "Vlees"
    private const val FISH = "Vis"
    private const val CHEESE = "Kaas"
    private const val BAKERY = "Bakkerij"
    private const val BREAKFAST = "Ontbijtgranen, beleg"
    private const val SWEETS = "Koek, snoep, chocolade"
    private const val COFFEE = "Koffie, thee"
    private const val VEGETARIAN = "Vegetarisch, vegan en plantaardig"
    private const val ALCOHOL = "Bier, wijn, aperitieven"
    private const val DRINKS = "Frisdrank, sappen, water"
    private const val FROZEN = "Diepvries"
    private const val SNACKS = "Borrel, chips, snacks"
    private const val COLD_CUTS = "Vleeswaren"

    private val byGroup: Map<String, Set<String>> = mapOf(
        "Vegetables" to setOf(VEGETABLES, WORLD, FROZEN),
        "Potatoes and tubers" to setOf(VEGETABLES, FROZEN),
        "Fruits" to setOf(FRUIT, VEGETABLES, WORLD, FROZEN),
        "Legumes" to setOf(VEGETABLES, WORLD, VEGETARIAN),
        "Herbs and spices" to setOf(SAUCES, VEGETABLES, WORLD),
        "Savoury sauces" to setOf(SAUCES, WORLD),
        "Soups" to setOf(SAUCES),
        "Fats and oils" to setOf(SAUCES, DAIRY),
        // Frozen meat is mostly prepared (rösti with bacon, schnitzels), so fresh only.
        "Meat and poultry" to setOf(MEAT),
        "Cold meat cuts" to setOf(COLD_CUTS),
        "Fish, crustacean and shellfish" to setOf(FISH, WORLD, FROZEN),
        "Milk and milk products" to setOf(DAIRY),
        "Eggs" to setOf(DAIRY),
        "Cheese" to setOf(CHEESE),
        "Meat substitutes and dairy substitutes" to setOf(VEGETARIAN, DAIRY, WORLD),
        "Cereal products and types of flour" to setOf(WORLD, BAKERY, BREAKFAST),
        "Bread" to setOf(BAKERY),
        "Savoury bread spreads" to setOf(BREAKFAST),
        "Sugar, sweets and sweet sauces" to setOf(COFFEE, BREAKFAST, SWEETS, BAKERY),
        "Nuts and seeds" to setOf(BREAKFAST, SAUCES, WORLD, SNACKS),
        "Savoury snacks" to setOf(SNACKS),
        "Alcoholic beverages" to setOf(ALCOHOL, WORLD),
        "Non-alcoholic beverages" to setOf(DRINKS, FRUIT, SAUCES),
    )

    /** The aisles for a NEVO food group, or null when unknown (then any food aisle will do). */
    fun forGroup(group: String?): Set<String>? = group?.let(byGroup::get)
}
