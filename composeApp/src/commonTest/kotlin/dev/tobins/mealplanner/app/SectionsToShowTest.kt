package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.IngredientSection
import kotlin.test.Test
import kotlin.test.assertEquals

class SectionsToShowTest {
    private fun entry(id: Long, name: String, section: String) = IngredientSection(id, name, section, section, "test", byHand = false)

    private val all = listOf(
        entry(1, "Onion", "FRUIT_VEG"),
        entry(2, "Baking paper", "OTHER"),
        entry(3, "Red onion", "FRUIT_VEG"),
        entry(4, "Unobtainium", "OTHER"),
    )

    @Test
    fun withoutASearchTheIngredientsUnderOtherAreListed() {
        assertEquals(listOf("Baking paper", "Unobtainium"), sectionsToShow(all, " ").map { it.name })
    }

    @Test
    fun aSearchFindsAnyIngredientByPartOfItsName() {
        assertEquals(listOf("Onion", "Red onion"), sectionsToShow(all, "ONION").map { it.name })
        assertEquals(emptyList(), sectionsToShow(all, "saffron"))
    }
}
