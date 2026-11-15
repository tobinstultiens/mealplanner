package dev.tobins.mealplanner.server.sections

import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.prices.StoreAisle
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
import dev.tobins.mealplanner.shopping.ShopSection.NON_FOOD
import dev.tobins.mealplanner.shopping.ShopSection.OTHER
import dev.tobins.mealplanner.shopping.ShopSection.READY_MEALS
import dev.tobins.mealplanner.shopping.ShopSection.SAUCES
import dev.tobins.mealplanner.shopping.ShopSection.SNACKS
import dev.tobins.mealplanner.shopping.ShopSection.TINS
import dev.tobins.mealplanner.shopping.ShopSection.VEGETARIAN
import dev.tobins.mealplanner.shopping.ShopSection.WORLD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SectionMappingTest {
    private fun ah(category: String, subCategory: String? = null) = StoreSections.forAisle(StoreAisle(Store.AH, category, subCategory))

    @Test
    fun ahCategoriesGiveTheirShopSection() {
        val expected = mapOf(
            "Groente, aardappelen" to FRUIT_VEG,
            "Fruit, verse sappen" to FRUIT_VEG,
            "Bakkerij" to BREAD,
            "Vleeswaren" to DELI,
            "Kaas" to DELI,
            "Soepen, sauzen, kruiden, olie" to SAUCES,
            "Vlees" to MEAT,
            "Vis" to FISH,
            "Vegetarisch, vegan en plantaardig" to VEGETARIAN,
            "Zuivel, eieren" to DAIRY,
            "Maaltijden, salades" to READY_MEALS,
            "Pasta, rijst, wereldkeuken" to WORLD,
            "Ontbijtgranen, beleg" to BREAKFAST,
            "Koek, snoep, chocolade" to SNACKS,
            "Borrel, chips, snacks" to SNACKS,
            "Koffie, thee" to DRINKS,
            "Frisdrank, sappen, water" to DRINKS,
            "Bier, wijn, aperitieven" to DRINKS,
            "Diepvries" to FROZEN,
            "Drogisterij" to NON_FOOD,
        )
        for ((category, section) in expected) assertEquals(section, ah(category), category)
    }

    @Test
    fun ahSubCategoriesThatSitElsewhereInTheShopMoveTheProduct() {
        assertEquals(BAKING, ah("Bakkerij", "Tarwebloem"), "flour is on the baking shelf, not in the bread corner")
        assertEquals(BAKING, ah("Bakkerij", "Bindmiddel"))
        assertEquals(BAKING, ah("Koffie, thee", "Suiker, poedersuiker"))
        assertEquals(TINS, ah("Pasta, rijst, wereldkeuken", "Tomatenblokjes"))
        assertEquals(TINS, ah("Groente, aardappelen", "Kidney bonen"))
        assertEquals(WORLD, ah("Zuivel, eieren", "Kokosmelk"))
        assertEquals(FRUIT_VEG, ah("Groente, aardappelen", "Bloemkool"), "other sub-categories keep their main category's section")
    }

    @Test
    fun unknownCategoriesAndOtherStoresGiveNoSection() {
        assertNull(ah("Something new"))
        assertNull(StoreSections.forAisle(StoreAisle(Store.JUMBO, "Kaas", null)), "Jumbo's categories are not AH's")
    }

    private fun nevo(group: String, name: String) = FoodSections.forFood(
        Food(1, name, name, null, group, FoodBasis.PER_100_G, Nutrition(null, null, null, null, null, null, null, null)),
    )

    @Test
    fun everyNevoFoodGroupHasASection() {
        val expected = mapOf(
            "Vegetables" to FRUIT_VEG,
            "Potatoes and tubers" to FRUIT_VEG,
            "Fruits" to FRUIT_VEG,
            "Legumes" to TINS,
            "Herbs and spices" to SAUCES,
            "Savoury sauces" to SAUCES,
            "Soups" to SAUCES,
            "Fats and oils" to DAIRY,
            "Meat and poultry" to MEAT,
            "Cold meat cuts" to DELI,
            "Fish, crustacean and shellfish" to FISH,
            "Milk and milk products" to DAIRY,
            "Eggs" to DAIRY,
            "Cheese" to DELI,
            "Meat substitutes and dairy substitutes" to VEGETARIAN,
            "Cereal products and types of flour" to WORLD,
            "Bread" to BREAD,
            "Savoury bread spreads" to BREAKFAST,
            "Sugar, sweets and sweet sauces" to SNACKS,
            "Nuts and seeds" to SNACKS,
            "Savoury snacks" to SNACKS,
            "Pastry and biscuits" to SNACKS,
            "Alcoholic beverages" to DRINKS,
            "Non-alcoholic beverages" to DRINKS,
            "Mixed dishes" to READY_MEALS,
            // Too mixed to say without the name; the words below sort most of it.
            "Miscellaneous foods" to OTHER,
            "Foods for special nutritional use" to OTHER,
        )
        for ((group, section) in expected) assertEquals(section, nevo(group, "Something plain"), group)
        assertEquals(OTHER, nevo("A group from a later NEVO release", "Something plain"))
    }

    @Test
    fun wordsOfTheFoodNamePickTheSectionWithinAGroup() {
        val expected: List<Triple<String, String, ShopSection>> = listOf(
            // Real NEVO names of foods in the catalogue.
            Triple("Vegetables", "Tomatoes tinned", TINS),
            Triple("Vegetables", "Gherkins sour pickled", TINS),
            Triple("Vegetables", "Capers", TINS),
            Triple("Vegetables", "Seaweed nori dried", WORLD),
            Triple("Vegetables", "Sweetcorn boiled", FRUIT_VEG),
            Triple("Vegetables", "Spinach frozen", FROZEN),
            Triple("Legumes", "Beans black canned", TINS),
            Triple("Legumes", "Lentils dried", WORLD),
            Triple("Fruits", "Pineapple in syrup", TINS),
            Triple("Fruits", "Apricots dried", SNACKS),
            Triple("Herbs and spices", "Basil fresh", FRUIT_VEG),
            Triple("Herbs and spices", "Ginger root", FRUIT_VEG),
            Triple("Herbs and spices", "Paprika powder", SAUCES),
            Triple("Fats and oils", "Butter unsalted", DAIRY),
            Triple("Fats and oils", "Oil olive", SAUCES),
            Triple("Fish, crustacean and shellfish", "Tuna in oil canned", TINS),
            Triple("Cereal products and types of flour", "Flour wheat white", BAKING),
            Triple("Cereal products and types of flour", "Corn starch", BAKING),
            Triple("Cereal products and types of flour", "Bread crumbs", BAKING),
            Triple("Cereal products and types of flour", "Muesli crunchy plain", BREAKFAST),
            Triple("Cereal products and types of flour", "Pasta white raw", WORLD),
            Triple("Cereal products and types of flour", "Wrap/tortilla wheat white", WORLD),
            Triple("Sugar, sweets and sweet sauces", "Sugar castor brown", BAKING),
            Triple("Sugar, sweets and sweet sauces", "Honey", BREAKFAST),
            Triple("Sugar, sweets and sweet sauces", "Chocolate dark", SNACKS),
            Triple("Pastry and biscuits", "Croissant", BREAD),
            Triple("Miscellaneous foods", "Yeast dried", BAKING),
            Triple("Miscellaneous foods", "Cocoa powder", BAKING),
            Triple("Miscellaneous foods", "Salt fortified w iodine", SAUCES),
            Triple("Miscellaneous foods", "Vinegar", SAUCES),
        )
        for ((group, name, section) in expected) assertEquals(section, nevo(group, name), name)
    }
}
