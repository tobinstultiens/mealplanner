package dev.tobins.mealplanner.nutrition

import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.LabelNutrition
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.recipes.HugoRecipeParser
import dev.tobins.mealplanner.recipes.IngredientLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An ingredient's own values and its named portions (issue 351), as everything that counts a line counts them. */
class PortionsTest {
    private fun line(amount: Double, unit: String) = HugoRecipeParser().line(amount, unit, "protein bar", null)!!

    private val label = LabelNutrition(FoodBasis.PER_100_G, Nutrition(400.0, 30.0, 15.0, 8.0, 35.0, 5.0, null, Salt.sodiumMg(0.5)))

    private val bar = Ingredient(1, "Protein bar", null, null, null, null, null, false, label = label, portions = listOf(Portion("Bar", grams = 45.0)))

    @Test
    fun aLineOfAnIngredientWithValuesOfItsOwnCountsByThose() {
        val result = NutritionCalculator.perServing(listOf(line(45.0, "g") to bar), servings = 1)
        assertEquals(180.0, result.perServing.kcal)
        assertEquals(13.5, result.perServing.proteinG)
        assertEquals(Salt.sodiumMg(0.225), result.perServing.sodiumMg!!, 1e-9)
        assertTrue(result.complete)
    }

    /** What a food of a slot may be in (issue 358), and what the editor offers a row: only units that weigh something. */
    @Test
    fun anIngredientIsInGramsAndInTheUnitsItsWeightsAreKnownIn() {
        assertEquals(listOf("g", "Bar"), bar.units)
        assertEquals(listOf("g", "ml", PIECE_UNIT, "Bar"), bar.copy(gramsPerMl = 1.1, gramsPerPiece = 45.0).units)
        assertEquals(listOf("g", "ml"), bar.copy(label = label.copy(basis = FoodBasis.PER_100_ML), portions = emptyList()).units)
    }

    @Test
    fun itsOwnValuesCountRatherThanItsFoods() {
        val food = Food(1, "Reep", "Bar", null, "Snacks", FoodBasis.PER_100_G, Nutrition(500.0, 5.0, null, null, null, null, null, null))
        val both = bar.copy(food = food)
        assertEquals(180.0, NutritionCalculator.perServing(listOf(line(45.0, "g") to both), 1).perServing.kcal)
        assertEquals(225.0, NutritionCalculator.perServing(listOf(line(45.0, "g") to both.copy(label = null)), 1).perServing.kcal)
    }

    @Test
    fun aDrinkPer100MlCountsALineInMlWithNoDensityKnown() {
        val drink = Ingredient(2, "Protein shake", null, null, null, null, null, false, label = LabelNutrition(FoodBasis.PER_100_ML, Nutrition(60.0, 10.0, null, null, null, null, null, null)))
        val result = NutritionCalculator.perServing(listOf(line(330.0, "ml") to drink), 1)
        assertEquals(198.0, result.perServing.kcal!!, 1e-9)
        assertTrue(result.complete)
        assertFalse(result.approximate, "a volume of a drink counted per volume needs no density")
    }

    @Test
    fun aLineInAPortionWeighsWhatThePortionDoesWhateverItsCase() {
        assertEquals(90.0, NutritionCalculator.quantityOf(line(2.0, "bar"), bar)!!.grams)
        assertEquals(90.0, NutritionCalculator.quantityOf(line(2.0, "BAR"), bar)!!.grams)
        assertEquals(360.0, NutritionCalculator.perServing(listOf(line(2.0, "bar") to bar), 1).perServing.kcal)
        // A portion corrected counts at once: the line keeps the name, not the weight.
        assertEquals(100.0, NutritionCalculator.quantityOf(line(2.0, "bar"), bar.copy(portions = listOf(Portion("bar", grams = 50.0))))!!.grams)
    }

    @Test
    fun aPortionInMlOfADrinkCountsItsVolume() {
        val drink = Ingredient(2, "Shake", null, null, null, null, null, false, label = LabelNutrition(FoodBasis.PER_100_ML, Nutrition(60.0, 10.0, null, null, null, null, null, null)), portions = listOf(Portion("bottle", ml = 330.0)))
        val result = NutritionCalculator.perServing(listOf(line(1.0, "bottle") to drink), 1)
        assertEquals(198.0, result.perServing.kcal!!, 1e-9)
        assertFalse(result.approximate)
        assertEquals(330.0, NutritionCalculator.quantityOf(line(1.0, "bottle"), drink)!!.grams, "a millilitre weighs a gram where nothing else is known")
    }

    @Test
    fun aLineInAPortionThatIsGoneIsNotCountedNotEvenAsPieces() {
        val gone = bar.copy(portions = emptyList(), removedPortions = listOf("bar"), gramsPerPiece = 40.0)
        assertNull(NutritionCalculator.quantityOf(line(2.0, "bar"), gone))
        val result = NutritionCalculator.perServing(listOf(line(2.0, "bar") to gone), 1)
        assertFalse(result.complete)
        assertEquals(listOf("bar, protein bar"), result.uncounted)
        // Pieces still count by the weight per piece.
        assertEquals(80.0, NutritionCalculator.quantityOf(line(2.0, "piece"), gone)!!.grams)
    }

    @Test
    fun aPortionIsACountButNotOfPieces() {
        assertTrue(line(2.0, "bar").inPortionOf(bar))
        assertTrue(line(2.0, "bar").inPortionOf(bar.copy(portions = emptyList(), removedPortions = listOf("Bar"))))
        assertFalse(line(2.0, "piece").inPortionOf(bar))
        assertFalse(line(45.0, "g").inPortionOf(bar))
        assertFalse(IngredientLine(2.0, "bar", null, null, "x", null).inPortionOf(bar))
    }

    @Test
    fun aPortionsNameIsNoUnitAndHoldsNoBarOrLineBreak() {
        assertNull(portionNameProblem("bar"))
        assertNull(portionNameProblem("scoop (30 g)"))
        listOf("", "  ", "g", "EL", "tbsp", "stuks", "piece", "a|b", "a\nb", "a b").forEach { name ->
            assertTrue(portionNameProblem(name) != null, "'$name' is refused")
        }
    }

    @Test
    fun saltOnALabelIsSodiumTimesTwoAndAHalf() {
        assertEquals(400.0, Salt.sodiumMg(1.0))
        assertEquals(1.2, Salt.saltG(Salt.sodiumMg(1.2)), 1e-12)
    }
}
