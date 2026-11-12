package dev.tobins.mealplanner.nutrition

import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.recipes.HugoRecipeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NutritionCalculatorTest {
    private fun food(kcal: Double, protein: Double, basis: FoodBasis = FoodBasis.PER_100_G) = Food(
        nevoCode = 1, nameNl = "x", nameEn = "x", synonym = null, groupEn = "g", basis = basis,
        per100 = Nutrition(kcal, protein, 0.0, 0.0, 0.0, 0.0, 0.0, null),
    )

    private fun ingredient(food: Food?, gramsPerPiece: Double? = null, gramsPerMl: Double? = null) =
        Ingredient(1, "x", food, gramsPerPiece, gramsPerMl, null, null, false)

    private fun lines(vararg text: String) =
        HugoRecipeParser().parse("x/y.md", "```ingredients\n${text.joinToString("\n")}\n```").ingredients

    private val chicken = ingredient(food(kcal = 100.0, protein = 20.0))

    @Test
    fun weightLinesScaleByGramsAndDivideByServings() {
        val (line) = lines("400 | g | chicken")
        val result = NutritionCalculator.perServing(listOf(line to chicken), servings = 2)

        assertEquals(200.0, result.perServing.kcal)
        assertEquals(40.0, result.perServing.proteinG)
        assertTrue(result.complete)
        assertFalse(result.approximate)
    }

    @Test
    fun countLinesNeedAWeightPerPiece() {
        val (line) = lines("2 | onion | large")
        val withoutWeight = NutritionCalculator.perServing(listOf(line to chicken), 1)
        assertFalse(withoutWeight.complete)
        assertEquals(listOf("onion, large"), withoutWeight.uncounted)
        assertEquals(0.0, withoutWeight.perServing.kcal)

        val withWeight = NutritionCalculator.perServing(listOf(line to ingredient(chicken.food, gramsPerPiece = 150.0)), 1)
        assertTrue(withWeight.complete)
        assertEquals(300.0, withWeight.perServing.kcal)
    }

    @Test
    fun volumeLinesUseDensityOrAssumeOneGramPerMl() {
        val (line) = lines("100 | ml | oil")
        val assumed = NutritionCalculator.perServing(listOf(line to chicken), 1)
        assertEquals(100.0, assumed.perServing.kcal)
        assertTrue(assumed.approximate)

        val known = NutritionCalculator.perServing(listOf(line to ingredient(chicken.food, gramsPerMl = 0.9)), 1)
        assertEquals(90.0, known.perServing.kcal!!, absoluteTolerance = 1e-9)
        assertFalse(known.approximate)
    }

    @Test
    fun foodsListedPer100MlUseTheVolumeDirectly() {
        val milk = ingredient(food(kcal = 47.0, protein = 3.5, basis = FoodBasis.PER_100_ML))
        val (byVolume, byWeight) = lines("200 | ml | milk", "206 | g | milk")

        val volume = NutritionCalculator.perServing(listOf(byVolume to milk), 1)
        assertEquals(94.0, volume.perServing.kcal)
        assertFalse(volume.approximate, "no density is needed for a volume of a per-100-ml food")

        val weight = NutritionCalculator.perServing(listOf(byWeight to ingredient(milk.food, gramsPerMl = 1.03)), 1)
        assertEquals(94.0, weight.perServing.kcal!!, absoluteTolerance = 1e-9)
        assertTrue(NutritionCalculator.perServing(listOf(byWeight to milk), 1).approximate)
    }

    @Test
    fun unmappedLinesAreReportedAndLinesWithoutAmountIgnored() {
        val (chickenLine, rice, salt) = lines("400 | g | chicken", "150 | g | rice", "| | salt to taste")
        val noFood = ingredient(food = null)
        val result = NutritionCalculator.perServing(listOf(chickenLine to chicken, rice to null, salt to null), 1)

        assertEquals(400.0, result.perServing.kcal)
        assertFalse(result.complete)
        assertEquals(listOf("rice"), result.uncounted)
        assertEquals(listOf("rice"), NutritionCalculator.perServing(listOf(rice to noFood), 1).uncounted)
    }

    @Test
    fun negligibleIngredientsAreLeftOutWithoutMakingTheRecipeIncomplete() {
        val (chickenLine, bay) = lines("400 | g | chicken", "2 | bay leaf |")
        val bayLeaf = ingredient(food = null).copy(negligible = true)
        val result = NutritionCalculator.perServing(listOf(chickenLine to chicken, bay to bayLeaf), 1)

        assertEquals(400.0, result.perServing.kcal)
        assertTrue(result.complete)
    }
}
