package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.AddOn
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.LaterShortfall
import dev.tobins.mealplanner.api.MealLine
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeTopping
import dev.tobins.mealplanner.api.ToppingInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the Adjust screen sends for each edit, how it shows amounts, its quick add-ons and its warning, and the recipe's toppings (issue 226). */
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
    fun anUndoTakesBackItsOwnEditAndLeavesAnEditMadeSince() {
        // The rice is set to 80 g; then, before that is undone, the beef is left out.
        val riceEdit = meal.withAmount(1, 80.0)
        assertEquals(setOf(1L), touched(meal.changes(), riceEdit))
        val beforeRice = meal.before(setOf(1L))
        val afterBoth = meal.copy(
            lines = listOf(rice.copy(grams = 80.0), beef.copy(grams = 0.0, changed = true), edamame, egg, unlinked),
        )
        assertEquals(
            listOf(MealLineChange(2, 0.0), MealLineChange(3, 0.0), MealLineChange(4, 60.0), MealLineChange(1, 150.0)),
            afterBoth.undoing(beforeRice),
            "the rice back to 150 g, the beef still left out",
        )

        // A reset undone puts back what was changed before it, and keeps an ingredient added since.
        val beforeReset = meal.before(touched(meal.changes(), emptyList()))
        val resetThenFeta = meal.copy(lines = listOf(rice.copy(grams = 100.0, changed = false), beef, edamame.copy(grams = 50.0, changed = false), MealLine(5, "Feta", 30.0, fromRecipe = false, changed = true)))
        assertEquals(
            listOf(MealLineChange(5, 30.0), MealLineChange(1, 150.0), MealLineChange(3, 0.0), MealLineChange(4, 60.0)),
            resetThenFeta.undoing(beforeReset),
        )
        // Undoing the edit that added an ingredient (it had no change before) takes it away again.
        val withFeta = meal.copy(lines = meal.lines + MealLine(5, "Feta", 30.0, fromRecipe = false, changed = true))
        assertEquals(meal.changes(), withFeta.undoing(meal.before(setOf(5L))))
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
        assertEquals(listOf("Rice vinegar", "Brown rice"), ingredientsMatching(all, "RICE ", left = setOf(1)).map { it.name })
        assertEquals(listOf("Avocado", "Brown rice", "Rice vinegar"), ingredientsMatching(all, "", left = setOf(1)).map { it.name })
    }

    @Test
    fun aQuickAddOnAddsItsAmountOnTopOfWhatTheMealHas() {
        val avocado = AddOn(8, "Avocado", 70.0, 140.0, topping = true)
        val feta = AddOn(5, "Feta", 40.0, times = 2)
        // A topping the recipe has too (the rice, 150 g in this meal), and a second egg on the one added.
        val moreRice = AddOn(1, "Rice", 50.0, topping = true)
        val egg = AddOn(4, "Egg", 60.0, 60.0, topping = true)
        val offered = meal.quickAddOns(listOf(avocado, egg, feta, moreRice))
        assertEquals(listOf(avocado, egg, feta, moreRice), offered.map { it.addOn })
        assertEquals(
            listOf("Avocado · 0.5 pieces", "+ 1 piece Egg", "Feta · 40 g", "+ 50 g Rice"),
            offered.map { it.chipText(Strings.English) },
        )
        assertEquals("Avocado · 0,5 stuks", offered[0].chipText(Strings.Dutch))
        assertEquals(listOf(MealLineChange(3, 0.0), MealLineChange(4, 60.0), MealLineChange(1, 200.0)), meal.adding(offered[3]))
        assertEquals(listOf(MealLineChange(1, 150.0), MealLineChange(3, 0.0), MealLineChange(4, 120.0)), meal.adding(offered[1]))

        // A recipe line left out gets the amount itself; a line nobody can weigh, and too much in all, get no chip.
        val edamame = meal.quickAddOns(listOf(AddOn(3, "Edamame", 30.0, topping = true))).single()
        assertEquals("Edamame · 30 g" to 30.0, edamame.chipText(Strings.English) to edamame.total)
        val bunch = meal.copy(lines = meal.lines + MealLine(9, "Coriander", null))
        assertEquals(emptyList(), bunch.quickAddOns(listOf(AddOn(9, "Coriander", 5.0), AddOn(2, "Beef", 4900.0))))
    }

    @Test
    fun anAmountIsSaidInPiecesOnlyWhenThatIsASensibleCount() {
        // 30 g of a 340 g cucumber is no count: grams alone, also on the Adjust screen's lines.
        assertEquals("30 g", amountText(30.0, 340.0, Strings.English))
        assertEquals("1.5 g", amountText(1.5, 4.0, Strings.English), "0.38 of a garlic clove")
        assertEquals("0.25 pieces · 15 g", amountText(15.0, 60.0, Strings.English))
        assertEquals("2.75 pieces · 165 g", amountText(165.0, 60.0, Strings.English))
        assertEquals("1 piece · 60 g", amountText(60.01, 60.0, Strings.English), "a count rounded on its way through grams")
        assertEquals("Cucumber · 30 g", QuickAddOn(AddOn(6, "Cucumber", 30.0, 340.0), 0.0).chipText(Strings.English))
        assertEquals("+ 30 g Cucumber", QuickAddOn(AddOn(6, "Cucumber", 30.0, 340.0), 60.0).chipText(Strings.English))
    }

    @Test
    fun theLaterMealsLeftShortAreSaidPerIngredientRoundedOnce() {
        // The emulator's case: 36.6 g and 56.7 g are 93.3 g, which the shopping list asks for as "93 g more".
        val beef = listOf(
            LaterShortfall(6, MealSlot.LUNCH, "Rice bowl", 2, "Beef", 36.6),
            LaterShortfall(6, MealSlot.DINNER, "Rice bowl", 2, "Beef", 56.7),
        )
        val short = meal.copy(shortfalls = beef + LaterShortfall(3, MealSlot.DINNER, "Spinach curry", 8, "Spinach", 100.0))
        assertEquals(
            listOf("Beef: 93 g short for Rice bowl, lunch Sun 22 Nov; Rice bowl, dinner Sun 22 Nov", "Spinach: 100 g short for Spinach curry, dinner Thu 19 Nov"),
            short.shortfallTexts(Strings.English),
        )
        assertEquals("Spinach: 100 g te weinig voor Spinach curry, avondeten do 19 nov", short.shortfallTexts(Strings.Dutch)[1])
    }

    @Test
    fun aToppingIsChangedWhereItIsAddedAtTheEndOrRemoved() {
        val toppings = listOf(RecipeTopping(4, "Egg", 60.0, 60.0), RecipeTopping(5, "Feta", 30.0))
        assertEquals(listOf(ToppingInput(4, 120.0), ToppingInput(5, 30.0)), toppings.withTopping(4, 120.0))
        assertEquals(listOf(ToppingInput(4, 60.0), ToppingInput(5, 30.0), ToppingInput(8, 70.0)), toppings.withTopping(8, 70.0))
        assertEquals(listOf(ToppingInput(5, 30.0)), toppings.withoutTopping(4))
    }
}
