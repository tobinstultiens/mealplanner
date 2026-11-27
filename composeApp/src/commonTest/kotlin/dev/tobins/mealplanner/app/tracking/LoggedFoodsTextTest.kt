package dev.tobins.mealplanner.app.tracking

import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.LoggedFood
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.recipes.PIECE
import dev.tobins.mealplanner.app.ui.unbrokenParts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What an entry of the log of food eaten outside the plan (issue 362) says on Today, in the app's language. */
class LoggedFoodsTextTest {
    private fun food(amount: Double, unit: String, ingredientId: Long? = 7, counted: Boolean = true) =
        LogEntry(1, "2026-11-18", "$amount $unit Banana", 216.0, 2.4, fibreG = 4.8, food = LoggedFood(ingredientId, "Banana", amount, unit, counted))

    @Test
    fun aLoggedFoodSaysItsAmountInItsUnitAndWhatItCounted() {
        assertEquals(unbrokenParts("2 pieces · 216 kcal · 2 g protein"), food(2.0, PIECE).numbersText(Strings.English))
        assertEquals(unbrokenParts("1 bar · 216 kcal · 2 g eiwit"), food(1.0, "bar").numbersText(Strings.Dutch))
        assertEquals(unbrokenParts("1,5 stuks · 216 kcal · 2 g eiwit"), food(1.5, PIECE).numbersText(Strings.Dutch))
        assertEquals(unbrokenParts("150 g · not counted"), food(150.0, "g", counted = false).numbersText(Strings.English))
        // Named by the food, not by the server's English line.
        assertEquals("Banana", food(2.0, PIECE).shownName)
    }

    @Test
    fun aTypedEntrySaysWhatWasTyped() {
        val typed = LogEntry(2, "2026-11-18", "Apple", 80.0, 0.5)
        assertEquals("80 kcal · 1 g protein", typed.numbersText(Strings.English))
        assertEquals("Apple" to "Apple", typed.shownName to typed.typedText(Strings.Dutch))
        assertFalse(typed.recountable)
    }

    /** A food gone from the catalogue is typed over, starting from its amount and name in the app's language. */
    @Test
    fun aFoodThatIsGoneIsNoLongerRecountedAndIsTypedOverFromHowItReads() {
        assertTrue(food(2.0, PIECE).recountable)
        val gone = food(2.0, PIECE, ingredientId = null)
        assertFalse(gone.recountable)
        assertEquals("2 stuks Banana", gone.typedText(Strings.Dutch))
        assertEquals(unbrokenParts("2 pieces · 216 kcal · 2 g protein"), gone.numbersText(Strings.English), "it reads as it did")
    }
}
