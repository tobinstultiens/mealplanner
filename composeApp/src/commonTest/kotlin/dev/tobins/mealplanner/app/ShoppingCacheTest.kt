package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShoppingCacheTest {
    private fun item(key: String, checked: Boolean = false) =
        ShoppingItem(key, key, 100.0, null, "Vegetables", listOf("Soup"), onHand = false, checked = checked)

    private val list = ShoppingList("2026-11-16", listOf(item("a"), item("b", checked = true)), staples = emptyList())

    @Test
    fun keepsTheLastListAndAppliesOfflineTicks() {
        val cache = ShoppingCache(MemorySettingsStorage())
        assertNull(cache.load("2026-11-16"))

        cache.save(list, "Fri 13 Nov 12:00")
        cache.tickOffline("2026-11-16", "a", true)
        cache.tickOffline("2026-11-16", "b", false)

        val (stored, savedAt) = cache.load("2026-11-16")!!
        assertEquals("Fri 13 Nov 12:00", savedAt)
        assertEquals(listOf(true, false), stored.items.map { it.checked })
        assertEquals(mapOf("a" to true, "b" to false), cache.pending("2026-11-16"))

        cache.clearPending("2026-11-16")
        assertEquals(emptyMap(), cache.pending("2026-11-16"))
        assertEquals(listOf(false, true), cache.load("2026-11-16")!!.first.items.map { it.checked }, "back to the server's ticks")
        assertNull(cache.load("2026-11-23"), "weeks are kept apart")
    }

    @Test
    fun aFreshListFromTheServerReplacesTheCopy() {
        val cache = ShoppingCache(MemorySettingsStorage())
        cache.save(list, "earlier")
        cache.tickOffline(list.weekStart, "a", true)
        cache.save(list.copy(items = listOf(item("c"))), "later")
        assertEquals(listOf("c"), cache.load(list.weekStart)!!.first.items.map { it.key })
        assertEquals(emptyMap(), cache.pending(list.weekStart))
    }
}
