package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.MealFood
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PlannedSlot
import dev.tobins.mealplanner.api.ShoppingItem
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

    /** The app words a slot of foods alone itself, in its language; the server's English title is for apps that do not know foods. */
    @Test
    fun aSlotOfFoodsAloneIsNamedByItsFoodsInTheAppsLanguage() {
        val names = listOf("Potato", "Onion", "Salmon").mapIndexed { i, name -> MealFood(i.toLong(), i.toLong(), name, 1.0, PIECE) }
        val meal = PlannedMeal(0, MealSlot.DINNER, 9, "Potato + 2 more", 1.0, 441.0, 30.0, foods = names, foodsOnly = true)
        assertEquals("Potato + 2 meer", meal.shownTitle(Strings.Dutch))
        assertEquals("Potato, Onion", meal.copy(foods = names.take(2)).shownTitle(Strings.Dutch))
        assertEquals("Stew", meal.copy(title = "Stew", foodsOnly = false).shownTitle(Strings.Dutch), "a recipe with foods next to it keeps its title")
    }

    /** A shopping row names a food by the slot it is planned in, never by another food or the recipe next to it. */
    @Test
    fun aShoppingRowNamesTheSlotsOfItsFoods() {
        val row = ShoppingItem(
            "i1", "Banana", 240.0, null, "Fruit", recipes = listOf("Stew"), onHand = false, checked = false,
            foodSlots = listOf(PlannedSlot("2026-11-16", MealSlot.SNACK), PlannedSlot("2026-11-17", MealSlot.DINNER)),
        )
        assertEquals(listOf("Stew", "snack on Mon 16 Nov", "dinner on Tue 17 Nov"), row.sources(Strings.English))
        val toCome = row.copy(toComeRecipes = emptyList(), toComeFoodSlots = listOf(PlannedSlot("2026-11-17", MealSlot.DINNER)))
        assertEquals(listOf("avondeten op di 17 nov"), toCome.sources(Strings.Dutch))
    }

    @Test
    fun aSlotOfFoodsIsNoServingsOfARecipe() {
        val foods = PlannedMeal(0, MealSlot.SNACK, 9, "Banana, Protein bar", 1.0, 450.0, 28.0, fibreG = 6.0, foodsOnly = true)
        assertEquals(macrosText(450.0, 28.0, 6.0, Strings.English), foods.macrosText(Strings.English))
        assertEquals("${unbrokenParts("1 serving")} · ${macrosText(450.0, 28.0, 6.0, Strings.English)}", foods.copy(foodsOnly = false).macrosText(Strings.English))
    }
}
