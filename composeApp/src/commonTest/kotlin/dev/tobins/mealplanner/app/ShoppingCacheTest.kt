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
        cache.choose("2026-11-16", "a", true)
        cache.choose("2026-11-16", "b", false)

        val (stored, savedAt) = cache.load("2026-11-16")!!
        assertEquals("Fri 13 Nov 12:00", savedAt)
        assertEquals(listOf(true, false), stored.items.map { it.checked })
        assertEquals(mapOf("a" to true, "b" to false), cache.pending("2026-11-16"))

        cache.forget("2026-11-16", "a", true)
        cache.forget("2026-11-16", "b", false)
        assertEquals(emptyMap(), cache.pending("2026-11-16"))
        assertEquals(listOf(false, true), cache.load("2026-11-16")!!.first.items.map { it.checked }, "back to the server's ticks")
        assertNull(cache.load("2026-11-23"), "weeks are kept apart")
    }

    @Test
    fun aFreshListFromTheServerReplacesTheCopy() {
        val cache = ShoppingCache(MemorySettingsStorage())
        cache.save(list, "earlier")
        cache.choose(list.weekStart, "a", true)
        cache.save(list.copy(items = listOf(item("c"))), "later")
        assertEquals(listOf("c"), cache.load(list.weekStart)!!.first.items.map { it.key })
        // The fresh list does not confirm a tick: it waits until the server answers that tick itself.
        assertEquals(mapOf("a" to true), cache.pending(list.weekStart))
    }

    @Test
    fun theAnswerToATickConfirmsOnlyTheChoiceItWasFor() {
        val cache = ShoppingCache(MemorySettingsStorage())
        cache.save(list, "earlier")
        cache.choose(list.weekStart, "a", true)
        cache.choose(list.weekStart, "b", false)
        cache.confirm(list.withTicks(mapOf("a" to true)), "later", "a", true)
        assertEquals(mapOf("b" to false), cache.pending(list.weekStart))
        assertEquals("later", cache.load(list.weekStart)!!.second)

        cache.choose(list.weekStart, "b", true)
        cache.confirm(list, "latest", "b", false)
        cache.forget(list.weekStart, "b", false)
        assertEquals(mapOf("b" to true), cache.pending(list.weekStart), "the user chose again since: the newer choice stays")
    }

    @Test
    fun aTickIsOnlyStoredOverAStoredList() {
        val cache = ShoppingCache(MemorySettingsStorage())
        cache.choose(list.weekStart, "a", true)
        assertEquals(emptyMap(), cache.pending(list.weekStart))
    }

    @Test
    fun clearingForgetsEveryWeekAndItsWaitingTicks() {
        val storage = MemorySettingsStorage()
        val cache = ShoppingCache(storage)
        cache.save(list, "earlier")
        cache.save(list.copy(weekStart = "2027-03-01"), "earlier")
        cache.choose(list.weekStart, "a", true)
        cache.clear(today = "2026-11-18")
        assertNull(cache.load(list.weekStart))
        assertNull(cache.load("2027-03-01"), "found by the list of weeks, though far from today")
        assertEquals(emptyMap(), cache.pending(list.weekStart))
    }

    @Test
    fun clearingAlsoFindsListsStoredBeforeTheListOfWeeksExisted() {
        val storage = MemorySettingsStorage()
        // As the app stored it until now: the entry only, with a tick that never reached the server.
        val legacy = """{"list":{"weekStart":"2026-11-09","items":[{"key":"a","name":"a","grams":100.0,"pieces":null,"category":"Vegetables","recipes":["Soup"],"onHand":false,"checked":false}],"staples":[]},"savedAt":"earlier","pending":{"a":true}}"""
        storage.set("shopping.2026-11-09", legacy)
        val cache = ShoppingCache(storage)
        assertEquals(mapOf("a" to true), cache.pending("2026-11-09"), "an entry stored by the installed app still loads")
        cache.clear(today = "2026-11-18")
        assertNull(cache.load("2026-11-09"))
    }

    @Test
    fun loggingOutForgetsTheStoredListsAndTheirTicks() {
        val storage = MemorySettingsStorage()
        val cache = ShoppingCache(storage)
        cache.save(list, "earlier")
        cache.choose(list.weekStart, "a", true)
        storage.logOut()
        assertNull(cache.load(list.weekStart))
        assertEquals(emptyMap(), cache.pending(list.weekStart))
    }
}
