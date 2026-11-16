package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.units.Dimension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShoppingNeedsTest {
    private val spinach = Ingredient(1, "Spinach", null, null, null, null, null, isStaple = false)

    private fun grams(amount: Double, item: String) = IngredientLine(amount, "g", Dimension.WEIGHT, amount, item, null)
    private fun count(amount: Double, unit: String, item: String) = IngredientLine(amount, unit, Dimension.COUNT, null, item, null)
    private fun millilitres(amount: Double, item: String) = IngredientLine(amount, "ml", Dimension.VOLUME, amount, item, null)

    /** The needs of one-serving recipes, one per argument, each planned for [portions] portions. */
    private fun needs(portions: Double, vararg recipes: List<Pair<IngredientLine, Ingredient?>>): List<ShoppingNeed> {
        val planned = recipes.indices.map { PlannedRecipe(it.toLong(), "R$it", portions) }
        val lines = recipes.mapIndexed { i, l -> i.toLong() to RecipeLines(1, l) }.toMap()
        return shoppingNeeds(planned, lines, emptyMap()).needs
    }

    @Test
    fun weightAndAnUnweighableCountOfOneIngredientAreBothKept() {
        val need = needs(2.0, listOf(grams(200.0, "spinach") to spinach), listOf(count(1.0, "bag", "spinach") to spinach)).single()

        assertNull(need.totalGrams, "the weight of the whole is still unknown")
        assertEquals(400.0, need.knownGrams)
        assertEquals(listOf("2 bag"), need.otherAmounts)
        assertEquals(2.0, need.pieces, "pieces keeps its meaning")
    }

    @Test
    fun anUnlinkedVolumeIsKeptScaledToThePortions() {
        val need = needs(2.0, listOf(millilitres(125.0, "coconut milk") to null)).single()

        assertEquals(listOf("250 ml"), need.otherAmounts)
        assertNull(need.knownGrams)
        assertNull(need.totalGrams)
    }

    @Test
    fun amountsAreSummedPerUnitAndRoundedUp() {
        val need = needs(
            1.0,
            listOf(count(1.5, "can", "tomatoes") to null),
            listOf(count(1.0, "Can", "tomatoes") to null),
            listOf(count(0.2, "clove", "tomatoes") to null),
        ).single()

        assertEquals(listOf("3 can", "1 clove"), need.otherAmounts)
    }

    @Test
    fun aWeighableIngredientHasNoOtherAmounts() {
        val need = needs(1.0, listOf(grams(300.0, "spinach") to spinach)).single()

        assertEquals(300.0, need.totalGrams)
        assertNull(need.knownGrams)
        assertEquals(emptyList(), need.otherAmounts)
    }

    @Test
    fun aCountOfTheIngredientItselfStaysInPieces() {
        val onion = Ingredient(2, "Onion", null, null, null, null, null, isStaple = false)
        val need = needs(1.0, listOf(count(2.0, "onion", "") to onion)).single()

        assertEquals(2.0, need.pieces)
        assertEquals(emptyList(), need.otherAmounts)
    }

    @Test
    fun theOnionsAreKeptNextToABagOfThem() {
        val onion = Ingredient(2, "Onion", null, null, null, null, null, isStaple = false)
        val item = needs(
            1.0,
            listOf(count(2.0, "onion", "") to onion),
            listOf(count(1.0, "bag", "onions") to onion),
        ).single().toItem(ShoppingKey.Ingredient(2), checked = false, onHand = false, offers = emptyList(), productImage = null, perKg = null)

        assertEquals(listOf("1 bag"), item.otherAmounts)
        assertEquals(2.0, item.ownPieces, "the two onions themselves, which the bag is not")
    }

    @Test
    fun withoutOtherAmountsThePiecesSayItAll() {
        val onion = Ingredient(2, "Onion", null, null, null, null, null, isStaple = false)
        val item = needs(1.0, listOf(count(2.0, "onion", "") to onion)).single()
            .toItem(ShoppingKey.Ingredient(2), checked = false, onHand = false, offers = emptyList(), productImage = null, perKg = null)

        assertEquals(2.0, item.pieces)
        assertNull(item.ownPieces)
    }
}
