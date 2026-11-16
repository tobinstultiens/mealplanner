package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Pantry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PantryNoteTest {
    private fun ingredient(id: Long) = Ingredient(id, "Item $id", null, null, null, null, null, isStaple = false)

    private val pantry = Pantry(
        staples = emptyList(),
        onHand = listOf(ingredient(1), ingredient(2), ingredient(3), ingredient(4)),
        onHandGrams = mapOf(1L to 280.0, 2L to 650.0),
        onHandFrom = mapOf(1L to "2026-11-23", 3L to "2026-11-16"),
    )

    @Test
    fun aStockSaysWhatIsLeftAndWhichWeekItWasBoughtFor() {
        assertEquals("280 g left · for the week of 23 Nov", pantryNote(pantry, 1, Strings.English))
        assertEquals("nog 280 g · voor de week van 23 nov", pantryNote(pantry, 1, Strings.Dutch))
    }

    @Test
    fun eachPartIsLeftOutWhenNotKnownAndNothingIsSaidOfWhatIsNotOnHandOrKnownAtAll() {
        assertEquals("650 g left", pantryNote(pantry, 2, Strings.English))
        assertEquals("for the week of 16 Nov", pantryNote(pantry, 3, Strings.English), "an amount nobody knows is not shown as none")
        assertNull(pantryNote(pantry, 4, Strings.English), "put on hand by hand: nothing known")
        assertNull(pantryNote(pantry, 5, Strings.English), "not on hand")
    }
}
