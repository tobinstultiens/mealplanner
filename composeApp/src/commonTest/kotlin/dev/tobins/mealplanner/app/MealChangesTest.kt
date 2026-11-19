package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MealLine
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.MealSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the Adjust screen sends for each edit, and how it shows amounts (issue 226). */
class MealChangesTest {
    private val rice = MealLine(1, "Rice", 150.0, recipeGrams = 100.0, changed = true)
    private val beef = MealLine(2, "Beef", 150.0, recipeGrams = 150.0)
    private val edamame = MealLine(3, "Edamame", 0.0, recipeGrams = 50.0, changed = true)
    private val egg = MealLine(4, "Egg", 60.0, fromRecipe = false, changed = true, gramsPerPiece = 60.0)
    private val unlinked = MealLine(null, "Furikake", null)
    private val meal = MealLines("2026-11-16", 0, MealSlot.DINNER, 7, "Rice bowl", 1.0, listOf(rice, beef, edamame, egg, unlinked), 834.0, 47.7, 3.0)

    @Test
    fun theChangesAreEveryLineThatDiffersFromTheRecipe() {
        assertEquals(listOf(MealLineChange(1, 150.0), MealLineChange(3, 0.0), MealLineChange(4, 60.0)), meal.changes())
    }

    @Test
    fun eachEditSendsTheWholeListWithThatOneChange() {
        assertEquals(listOf(MealLineChange(3, 0.0), MealLineChange(4, 60.0), MealLineChange(1, 80.0)), meal.withAmount(1, 80.0))
        assertEquals(listOf(MealLineChange(1, 150.0), MealLineChange(3, 0.0), MealLineChange(4, 60.0), MealLineChange(2, 0.0)), meal.leavingOut(beef))
        // An added ingredient left out is not added at all; a recipe line put back is the recipe's again.
        assertEquals(listOf(MealLineChange(1, 150.0), MealLineChange(3, 0.0)), meal.leavingOut(egg))
        assertEquals(listOf(MealLineChange(1, 150.0), MealLineChange(4, 60.0)), meal.asInRecipe(edamame))
        assertEquals(meal.changes(), meal.leavingOut(unlinked), "a line linked to nothing can't be changed")
    }

    @Test
    fun aLineSaysItsAmountInPiecesWhenTheCatalogueKnowsTheirWeight() {
        assertEquals("1 piece · 60 g", amountText(60.0, 60.0, Strings.English))
        assertEquals("1.5 pieces · 90 g", amountText(90.0, 60.0, Strings.English))
        assertEquals("1,5 stuks · 90 g", amountText(90.0, 60.0, Strings.Dutch))
        assertEquals("150 g", amountText(150.0, null, Strings.English))
        assertNull(amountText(null, 60.0, Strings.English))
    }

    @Test
    fun aLineSaysHowItDiffersFromTheRecipe() {
        assertEquals("150 g · recipe: 100 g", rice.note(Strings.English))
        assertEquals("150 g", beef.note(Strings.English))
        assertEquals("Left out · recipe: 50 g", edamame.note(Strings.English))
        assertEquals("Weggelaten · recept: 50 g", edamame.note(Strings.Dutch))
        assertEquals("added · 1 piece · 60 g", egg.note(Strings.English))
        assertEquals("amount not known\nNot one of your ingredients, so not counted and not changeable", unlinked.note(Strings.English))
    }

    @Test
    fun anAmountIsTypedInPiecesOrGramsWithEitherDecimalSeparator() {
        assertEquals(30.0, gramsEntered("0,5", inPieces = true, gramsPerPiece = 60.0))
        assertEquals(90.0, gramsEntered(" 1.5 ", inPieces = true, gramsPerPiece = 60.0))
        assertEquals(1.5, gramsEntered("1.5", inPieces = false, gramsPerPiece = 60.0))
        assertEquals(125.0, gramsEntered("125", inPieces = true, gramsPerPiece = null), "without a weight per piece it is grams")
        for (text in listOf("", "0", "-5", "a lot", "5001")) assertNull(gramsEntered(text, inPieces = false, gramsPerPiece = null), text)
        assertNull(gramsEntered("100", inPieces = true, gramsPerPiece = 60.0), "6 kg of eggs is a typing mistake")
    }

    @Test
    fun theFieldStartsWithTheAmountInItsUnit() {
        assertEquals("2.5", amountFieldText(150.0, inPieces = true, gramsPerPiece = 60.0))
        assertEquals("150", amountFieldText(150.0, inPieces = false, gramsPerPiece = 60.0))
        assertEquals("", amountFieldText(null, inPieces = false, gramsPerPiece = null))
    }

    @Test
    fun theIngredientsToAddLeaveOutWhatTheMealHasAndPutNamesThatStartWithTheSearchFirst() {
        fun ingredient(id: Long, name: String) = Ingredient(id, name, null, null, null, null, null, isStaple = false)
        val all = listOf(ingredient(1, "Rice"), ingredient(5, "Brown rice"), ingredient(6, "Rice vinegar"), ingredient(7, "Avocado"))
        assertEquals(listOf("Rice vinegar", "Brown rice"), ingredientsMatching(all, "RICE ", inMeal = setOf(1)).map { it.name })
        assertEquals(listOf("Avocado", "Brown rice", "Rice vinegar"), ingredientsMatching(all, "", inMeal = setOf(1)).map { it.name })
    }
}
