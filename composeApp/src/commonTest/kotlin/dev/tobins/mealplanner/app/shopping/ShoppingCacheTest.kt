package dev.tobins.mealplanner.app.shopping

import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.app.MemorySettingsStorage
import dev.tobins.mealplanner.app.logOut
import dev.tobins.mealplanner.app.prices.ExclusionChange
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
    fun anAdviceTickKeepsTheGramsAdvisedUntilTheServerHasIt() {
        val cache = ShoppingCache(MemorySettingsStorage())
        val rice = item("stock:i7").copy(grams = 1000.0)
        cache.save(list.copy(stockUp = listOf(rice)), "earlier")
        cache.choose(list.weekStart, "stock:i7", true)
        cache.choose(list.weekStart, "a", true)
        assertEquals(1000.0, cache.advised(list.weekStart, "stock:i7"))
        assertNull(cache.advised(list.weekStart, "a"), "a row of the meals' needs has no advice")

        // The answer to the other tick comes from a server whose list no longer advises the rice.
        cache.confirm(list, "later", "a", true)
        cache.save(list, "latest")
        assertEquals(1000.0, cache.advised(list.weekStart, "stock:i7"), "kept with the tick, not with the list")
        cache.confirm(list, "latest", "stock:i7", true)
        assertNull(cache.advised(list.weekStart, "stock:i7"), "the server has it")

        // Un-ticked, nothing was bought; refused, it is dropped with the tick.
        cache.save(list.copy(stockUp = listOf(rice)), "again")
        cache.choose(list.weekStart, "stock:i7", true)
        cache.choose(list.weekStart, "stock:i7", false)
        assertNull(cache.advised(list.weekStart, "stock:i7"))
        cache.choose(list.weekStart, "stock:i7", true)
        cache.forget(list.weekStart, "stock:i7", true)
        assertNull(cache.advised(list.weekStart, "stock:i7"))
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
    fun aCacheMadeBeforeTheListsWereClearedWritesNothingAfter() {
        val storage = MemorySettingsStorage()
        val previousAccount = ShoppingCache(storage)
        previousAccount.save(list, "earlier")
        storage.logOut()
        // Answers to requests that were under way when the login ended.
        previousAccount.save(list, "later")
        previousAccount.confirm(list, "later", "a", true)
        previousAccount.choose(list.weekStart, "a", true)
        previousAccount.tidy(today = "2026-11-18")
        assertNull(ShoppingCache(storage).load(list.weekStart))
        assertEquals(emptyList(), ShoppingCache(storage).weeksWithTicks())

        val nextAccount = ShoppingCache(storage)
        nextAccount.save(list, "now")
        assertEquals("now", nextAccount.load(list.weekStart)!!.second, "a cache made after the clear works as before")
    }

    @Test
    fun weeksWithTicksAreListedOldestFirst() {
        val cache = ShoppingCache(MemorySettingsStorage())
        listOf("2026-11-16", "2026-11-09", "2026-11-23").forEach { cache.save(list.copy(weekStart = it), "earlier") }
        cache.choose("2026-11-16", "a", true)
        cache.choose("2026-11-09", "b", false)
        assertEquals(listOf("2026-11-09", "2026-11-16"), cache.weeksWithTicks())
    }

    @Test
    fun tidyingDropsOldListsWithoutTicksAndKeepsTheRest() {
        val storage = MemorySettingsStorage()
        val cache = ShoppingCache(storage)
        val fiveWeeksOld = "2026-10-12"
        val fiveWeeksOldWithTicks = "2026-10-05"
        val fourWeeksOld = "2026-10-19"
        listOf(fiveWeeksOld, fiveWeeksOldWithTicks, fourWeeksOld, "2026-11-16", "2026-11-23").forEach { cache.save(list.copy(weekStart = it), "earlier") }
        cache.choose(fiveWeeksOldWithTicks, "a", true)
        storage.set("shopping.2026-11-02", "not a list")

        cache.tidy(today = "2026-11-18")
        assertNull(cache.load(fiveWeeksOld))
        assertNull(storage.get("shopping.$fiveWeeksOld"), "removed, not left empty")
        assertNull(storage.get("shopping.2026-11-02"), "one that no longer reads goes too")
        assertEquals(mapOf("a" to true), cache.pending(fiveWeeksOldWithTicks), "its tick has still to reach the server")
        listOf(fourWeeksOld, "2026-11-16", "2026-11-23").forEach { assertEquals(it, cache.load(it)?.first?.weekStart) }

        cache.forget(fiveWeeksOldWithTicks, "a", true)
        cache.tidy(today = "2026-11-18")
        assertNull(cache.load(fiveWeeksOldWithTicks), "once its tick was dealt with")
    }

    @Test
    fun tidyingTakesListsStoredBeforeTheListOfWeeksAlong() {
        val storage = MemorySettingsStorage()
        // As the installed app (v0.1.3) stores it: the entry only, with a tick that never reached the server.
        val legacy = """{"list":{"weekStart":"2026-11-09","items":[{"key":"a","name":"a","grams":100.0,"pieces":null,"category":"Vegetables","recipes":["Soup"],"onHand":false,"checked":false}],"staples":[]},"savedAt":"earlier","pending":{"a":true}}"""
        storage.set("shopping.2026-11-09", legacy)
        val cache = ShoppingCache(storage)
        assertEquals(emptyList(), cache.weeksWithTicks())
        cache.tidy(today = "2026-11-18")
        assertEquals(listOf("2026-11-09"), cache.weeksWithTicks(), "so its tick is sent with the rest")
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

    private fun change(excluded: Boolean, at: String = "2026-11-18T09:00:00Z") =
        ExclusionChange(Store.JUMBO, "Hela curry", 3, "Curry powder", excluded, at)

    @Test
    fun aWaitingExclusionSurvivesTheAppAndOneChangeOfAProductWaitsAtATime() {
        val storage = MemorySettingsStorage()
        // An app from before "Not this ingredient" stored none.
        assertEquals(emptyList(), ShoppingCache(storage).waitingExclusions())
        ShoppingCache(storage).chooseExclusion(change(excluded = true))
        val undone = change(excluded = false, at = "2026-11-18T09:00:05Z")
        ShoppingCache(storage).chooseExclusion(undone)

        // The app was closed and opened again: the latest choice for the product waits.
        assertEquals(listOf(undone), ShoppingCache(storage).waitingExclusions())
    }

    @Test
    fun theServersAnswerSettlesAChangeUnlessTheUserChoseAgainSince() {
        val cache = ShoppingCache(MemorySettingsStorage())
        val excluded = change(excluded = true)
        cache.chooseExclusion(excluded)
        // Undone while the exclusion was on its way.
        val undone = change(excluded = false, at = "2026-11-18T09:00:05Z")
        cache.chooseExclusion(undone)

        cache.confirmExclusion(excluded, listOf(excluded.asExclusion()))

        assertEquals(listOf(excluded.asExclusion()), cache.knownExclusions())
        assertEquals(listOf(undone), cache.waitingExclusions(), "the take-back still goes out")
        cache.confirmExclusion(undone, emptyList())
        assertEquals(emptyList(), cache.waitingExclusions())
        assertEquals(emptyList(), cache.knownExclusions())
    }

    @Test
    fun loggingOutForgetsTheExclusionsAndAnAnswerArrivingLaterStoresNone() {
        val storage = MemorySettingsStorage()
        val cache = ShoppingCache(storage)
        val excluded = change(excluded = true)
        cache.chooseExclusion(excluded)
        cache.saveKnownExclusions(listOf(excluded.asExclusion()))

        storage.logOut()
        // The previous account's request answered after logging out.
        cache.confirmExclusion(excluded, listOf(excluded.asExclusion()))
        cache.chooseExclusion(change(excluded = true, at = "2026-11-18T09:01:00Z"))

        val next = ShoppingCache(storage)
        assertEquals(emptyList(), next.knownExclusions())
        assertEquals(emptyList(), next.waitingExclusions())
    }
}
