package dev.tobins.mealplanner.server.shopping.sections

import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.shopping.ShopSection
import dev.tobins.mealplanner.shopping.ShopSection.BAKING
import dev.tobins.mealplanner.shopping.ShopSection.BREAD
import dev.tobins.mealplanner.shopping.ShopSection.BREAKFAST
import dev.tobins.mealplanner.shopping.ShopSection.DAIRY
import dev.tobins.mealplanner.shopping.ShopSection.DELI
import dev.tobins.mealplanner.shopping.ShopSection.DRINKS
import dev.tobins.mealplanner.shopping.ShopSection.FISH
import dev.tobins.mealplanner.shopping.ShopSection.FROZEN
import dev.tobins.mealplanner.shopping.ShopSection.FRUIT_VEG
import dev.tobins.mealplanner.shopping.ShopSection.MEAT
import dev.tobins.mealplanner.shopping.ShopSection.OTHER
import dev.tobins.mealplanner.shopping.ShopSection.READY_MEALS
import dev.tobins.mealplanner.shopping.ShopSection.SAUCES
import dev.tobins.mealplanner.shopping.ShopSection.SNACKS
import dev.tobins.mealplanner.shopping.ShopSection.TINS
import dev.tobins.mealplanner.shopping.ShopSection.VEGETARIAN
import dev.tobins.mealplanner.shopping.ShopSection.WORLD

/**
 * The shop section of a NEVO food, for ingredients without a store category. A food group is a
 * nutrition grouping, not a shop's: "Vegetables" holds tinned tomatoes, "Herbs and spices"
 * fresh ginger and "Cereal products" both pasta and flour. So within a group, words of the
 * food's English name ("tinned", "fresh", "flour") pick the section where they say more.
 */
object FoodSections {
    /** A group's section, and words of a food's name that move it elsewhere; the first match wins. */
    private class Rule(val section: ShopSection, vararg val byWords: Pair<List<String>, ShopSection>)

    private val preserved = listOf("tinned", "canned", "bottled", "pickled", "in jar", "in syrup", "in juice")

    private val byGroup: Map<String, Rule> = mapOf(
        "Vegetables" to Rule(FRUIT_VEG, preserved + "capers" to TINS, listOf("seaweed") to WORLD),
        "Potatoes and tubers" to Rule(FRUIT_VEG),
        "Fruits" to Rule(FRUIT_VEG, preserved to TINS, listOf("dried") to SNACKS),
        // Mostly bought in tins and jars; dry ones are with the rice.
        "Legumes" to Rule(TINS, listOf("dried") to WORLD),
        "Herbs and spices" to Rule(SAUCES, listOf("fresh", "root") to FRUIT_VEG),
        "Savoury sauces" to Rule(SAUCES),
        "Soups" to Rule(SAUCES),
        "Fats and oils" to Rule(DAIRY, listOf("oil", "lard", "frying") to SAUCES),
        "Meat and poultry" to Rule(MEAT),
        "Cold meat cuts" to Rule(DELI),
        "Fish, crustacean and shellfish" to Rule(FISH, preserved to TINS),
        "Milk and milk products" to Rule(DAIRY),
        "Eggs" to Rule(DAIRY),
        "Cheese" to Rule(DELI),
        "Meat substitutes and dairy substitutes" to Rule(VEGETARIAN),
        "Cereal products and types of flour" to Rule(
            WORLD,
            listOf("flour", "starch", "bread crumbs", "breadcrumbs") to BAKING,
            listOf("muesli", "oat", "oats", "oatmeal", "cornflakes", "cereal", "granola") to BREAKFAST,
        ),
        "Bread" to Rule(BREAD),
        "Savoury bread spreads" to Rule(BREAKFAST),
        "Sugar, sweets and sweet sauces" to Rule(
            SNACKS,
            listOf("chocolate spread", "honey", "jam", "syrup", "sprinkles") to BREAKFAST,
            listOf("sugar") to BAKING,
        ),
        "Nuts and seeds" to Rule(SNACKS),
        "Savoury snacks" to Rule(SNACKS),
        "Pastry and biscuits" to Rule(SNACKS, listOf("croissant", "pastry", "pie", "tart") to BREAD),
        "Alcoholic beverages" to Rule(DRINKS),
        "Non-alcoholic beverages" to Rule(DRINKS),
        "Mixed dishes" to Rule(READY_MEALS),
        "Miscellaneous foods" to Rule(
            OTHER,
            listOf("yeast", "cocoa", "baking", "gelatine", "gelatin", "vanilla") to BAKING,
            listOf("salt", "vinegar", "stock") to SAUCES,
        ),
    )

    /** The section for [food]: frozen foods are in the freezer whatever their group; unknown groups are Other. */
    fun forFood(food: Food): ShopSection {
        val name = " " + food.nameEn.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }.joinToString(" ") + " "
        if (" frozen " in name) return FROZEN
        val rule = byGroup[food.groupEn] ?: return OTHER
        return rule.byWords.firstOrNull { (words, _) -> words.any { " $it " in name } }?.second ?: rule.section
    }
}
