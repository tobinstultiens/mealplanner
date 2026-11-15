package dev.tobins.mealplanner.shopping

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShopSectionTest {
    private fun ShopSection.before(other: ShopSection) = ordinal < other.ordinal

    @Test
    fun sectionsFollowTheRouteThroughADutchSupermarket() {
        // Produce, bread, deli, sauces, the chilled wall, the aisles, frozen, non-food.
        val route = listOf(
            ShopSection.FRUIT_VEG, ShopSection.BREAD, ShopSection.DELI, ShopSection.SAUCES, ShopSection.MEAT,
            ShopSection.WORLD, ShopSection.FROZEN, ShopSection.NON_FOOD,
        )
        route.zipWithNext().forEach { (a, b) -> assertTrue(a.before(b), "$a before $b") }
        // The chilled wall comes before the aisles.
        listOf(ShopSection.MEAT, ShopSection.FISH, ShopSection.VEGETARIAN, ShopSection.DAIRY, ShopSection.READY_MEALS)
            .forEach { assertTrue(it.before(ShopSection.WORLD), "$it before the aisles") }
        assertEquals(ShopSection.OTHER, ShopSection.entries.last(), "unknown items go last")
    }

    @Test
    fun sectionsAreFoundByKeyAndHaveDistinctTitles() {
        ShopSection.entries.forEach { assertEquals(it, ShopSection.fromKey(it.name)) }
        assertNull(ShopSection.fromKey("AISLE_42"), "a section from a newer server")
        assertNull(ShopSection.fromKey(null))
        assertEquals(ShopSection.entries.size, ShopSection.entries.map { it.title }.toSet().size)
    }

    @Test
    fun whatKeepsUntilNextWeekFollowsTheSection() {
        // Fresh meat and fish keep in the freezer: bought ahead for a meal, not stocked up on.
        assertEquals(ShelfLife.FREEZER, ShopSection.MEAT.shelfLife)
        assertEquals(ShelfLife.FREEZER, ShopSection.FISH.shelfLife)
        listOf(ShopSection.TINS, ShopSection.WORLD, ShopSection.FROZEN, ShopSection.BAKING, ShopSection.SAUCES)
            .forEach { assertEquals(ShelfLife.LONG, it.shelfLife, "$it") }
        listOf(ShopSection.FRUIT_VEG, ShopSection.BREAD, ShopSection.DAIRY, ShopSection.READY_MEALS, ShopSection.OTHER)
            .forEach { assertEquals(ShelfLife.SHORT, it.shelfLife, "$it") }
        assertTrue(ShelfLife.FREEZER.keepsUntilNextWeek && ShelfLife.LONG.keepsUntilNextWeek)
        assertTrue(!ShelfLife.SHORT.keepsUntilNextWeek)
    }
}
