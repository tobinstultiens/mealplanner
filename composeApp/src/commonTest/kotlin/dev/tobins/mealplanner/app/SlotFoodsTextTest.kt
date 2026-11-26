package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.MealFood
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a food of a slot (issue 358) and a slot of foods alone say on the Plan and Today. */
class SlotFoodsTextTest {
    private fun food(amount: Double, unit: String, kcal: Double = 108.0, counted: Boolean = true) =
        MealFood(1, 2, "Banana", amount, unit, kcal = kcal, counted = counted)

    @Test
    fun aFoodSaysItsAmountInTheUnitItWasPickedIn() {
        assertEquals(unbrokenParts("1 piece · 108 kcal"), food(1.0, PIECE).amountText(Strings.English))
        assertEquals(unbrokenParts("2 pieces · 216 kcal"), food(2.0, PIECE, kcal = 216.0).amountText(Strings.English))
        assertEquals(unbrokenParts("2 bar · 342 kcal"), food(2.0, "bar", kcal = 342.0).amountText(Strings.English))
        assertEquals(unbrokenParts("1,5 stuks · 162 kcal"), food(1.5, PIECE, kcal = 162.0).amountText(Strings.Dutch))
        assertEquals(unbrokenParts("150 g · not counted"), food(150.0, "g", counted = false).amountText(Strings.English))
    }

    @Test
    fun aSlotOfFoodsIsNoServingsOfARecipe() {
        val foods = PlannedMeal(0, MealSlot.SNACK, 9, "Banana, Protein bar", 1.0, 450.0, 28.0, fibreG = 6.0, foodsOnly = true)
        assertEquals(macrosText(450.0, 28.0, 6.0, Strings.English), foods.macrosText(Strings.English))
        assertEquals("${unbrokenParts("1 serving")} · ${macrosText(450.0, 28.0, 6.0, Strings.English)}", foods.copy(foodsOnly = false).macrosText(Strings.English))
    }
}
