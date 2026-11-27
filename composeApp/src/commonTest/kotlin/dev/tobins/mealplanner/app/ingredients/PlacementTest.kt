package dev.tobins.mealplanner.app.ingredients

import dev.tobins.mealplanner.api.IngredientSection
import dev.tobins.mealplanner.app.Strings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saving an ingredient's section and "keeps" from the dialog on the link screen. */
class PlacementTest {
    private val beef = IngredientSection(7, "Minced beef", "MEAT", "MEAT", "test", byHand = false)
    private val moved = beef.copy(section = "FROZEN", byHand = true)
    private val kept = moved.copy(keeps = true, keepsByHand = true)

    @Test
    fun bothAreSavedAndTheRowTakesTheLastAnswer() = runTest {
        val placement = place(beef, "FROZEN", true, { _, _ -> moved }, { _, keeps -> kept.also { assertEquals(true, keeps) } })
        assertEquals(kept, placement.saved)
        assertNull(placement.failure)
        assertEquals("Minced beef is now under Frozen", placement.message(Strings.English, beef))
    }

    @Test
    fun keepsIsLeftAloneWhenItWasNotChanged() = runTest {
        val placement = place(beef, "FROZEN", null, { _, _ -> moved }, { _, _ -> error("not called") })
        assertEquals(moved, placement.saved)
    }

    @Test
    fun whenKeepsFailsTheSectionIsStillShownAndOnlyKeepsIsReported() = runTest {
        val placement = place(beef, "FROZEN", true, { _, _ -> moved }, { _, _ -> throw IllegalStateException("offline") })
        assertEquals(moved, placement.saved, "the row shows what was saved")
        assertTrue(placement.keepsFailed)
        val line = placement.message(Strings.English, beef)
        assertTrue(line.startsWith("Minced beef was moved, but whether it keeps could not be saved"), line)
        assertFalse("section" in line)
    }

    @Test
    fun whenTheSectionFailsNothingIsSavedAndKeepsIsNotTried() = runTest {
        val placement = place(beef, "FROZEN", true, { _, _ -> throw IllegalStateException("offline") }, { _, _ -> error("not called") })
        assertNull(placement.saved)
        assertFalse(placement.keepsFailed)
        assertTrue(placement.message(Strings.English, beef).startsWith("Could not change the section"))
    }
}
