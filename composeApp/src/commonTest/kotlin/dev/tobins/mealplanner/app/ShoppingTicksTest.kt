package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingTicksTest {
    private fun item(key: String, checked: Boolean = false) =
        ShoppingItem(key, key, 100.0, null, "Vegetables", listOf("Soup"), onHand = false, checked = checked)

    private val start = ShoppingList("2026-11-16", listOf(item("a"), item("b", checked = true)), staples = emptyList())

    /** A server that answers after [latency] ms and remembers the ticks it was sent, in order. */
    private class FakeServer(var list: ShoppingList, var latency: Long = 2000, var answer: ((String, Boolean) -> Load<ShoppingList>)? = null) {
        val received = mutableListOf<Pair<String, Boolean>>()

        suspend fun put(key: String, checked: Boolean): Load<ShoppingList> {
            delay(latency)
            received += key to checked
            answer?.let { return it(key, checked) }
            list = list.copy(items = list.items.map { if (it.key == key) it.copy(checked = checked) else it })
            return Load.Ready(list)
        }
    }

    private class Harness(scope: TestScope, val server: FakeServer, var offline: Boolean = false) {
        var shown: ShoppingList? = null
        val shownHistory = mutableListOf<List<Boolean>>()
        val rejections = mutableListOf<String>()
        val offlineTicks = mutableListOf<Pair<String, Boolean>>()
        val cache = ShoppingCache(MemorySettingsStorage()).also { it.save(server.list, "earlier") }
        val ticks = ShoppingTicks(
            scope,
            server.list.weekStart,
            cache,
            send = { key, checked -> server.put(key, checked) },
            isOffline = { offline },
            show = { shown = it; shownHistory += it.items.map { item -> item.checked } },
            offline = { key, checked -> offlineTicks += key to checked },
            rejected = { rejections += it },
        ).also { shown = it.reset(server.list) }

        fun checked() = shown!!.items.map { it.checked }
    }

    @Test
    fun aTapShowsAtOnceWhileTheServerIsStillAnswering() = runTest {
        val h = Harness(this, FakeServer(start, latency = 60_000))
        h.ticks.choose("b", false)
        assertEquals(listOf(false, false), h.checked(), "shown before any request had time to finish")
        h.ticks.choose("a", true)
        assertEquals(listOf(true, false), h.checked())
        assertEquals(emptyList(), h.server.received, "the server has not heard yet")
        advanceUntilIdle()
        assertEquals(listOf(true, false), h.checked())
    }

    @Test
    fun anOlderAnswerDoesNotFlipARowBack() = runTest {
        val h = Harness(this, FakeServer(start))
        h.ticks.choose("a", true)
        advanceTimeBy(1000)
        h.ticks.choose("a", false)
        advanceUntilIdle()
        assertEquals(false, h.server.list.items.first().checked, "the server ends in the last choice")
        // Entry 0 is the first tap; everything after the second tap must show the row un-ticked.
        assertEquals(true, h.shownHistory.drop(1).all { !it.first() }, "the answer to the first tap did not flip the row back")
    }

    @Test
    fun rapidTapsEndInTheLastChosenState() = runTest {
        val h = Harness(this, FakeServer(start))
        repeat(5) { h.ticks.choose("a", it % 2 == 0) }
        h.ticks.choose("b", false)
        h.ticks.choose("b", true)
        advanceUntilIdle()
        assertEquals(listOf(true, true), h.checked())
        assertEquals(listOf(true, true), h.server.list.items.map { it.checked })
    }

    @Test
    fun aRefusedTickRestoresTheRowAndTellsTheUser() = runTest {
        val server = FakeServer(start) { _, _ -> Load.Failed("No such item", 404) }
        val h = Harness(this, server)
        h.ticks.choose("a", true)
        h.ticks.choose("b", false)
        assertEquals(listOf(true, false), h.checked())
        advanceUntilIdle()
        assertEquals(listOf(false, true), h.checked(), "both back as the server has them")
        assertEquals(listOf("No such item", "No such item"), h.rejections)
    }

    @Test
    fun withoutAConnectionTheTickStaysAndIsKeptForLater() = runTest {
        val server = FakeServer(start) { _, _ -> Load.Failed("timeout") }
        val h = Harness(this, server)
        h.ticks.choose("a", true)
        advanceUntilIdle()
        assertEquals(listOf(true, true), h.checked())
        assertEquals(listOf("a" to true), h.offlineTicks)
        assertEquals(emptyList(), h.rejections)
    }

    @Test
    fun onceOfflineLaterTicksAreKeptWithoutAskingTheServer() = runTest {
        val h = Harness(this, FakeServer(start), offline = true)
        h.ticks.choose("a", true)
        h.ticks.choose("b", false)
        runCurrent()
        assertEquals(emptyList(), h.server.received)
        assertEquals(listOf("a", "b"), h.offlineTicks.map { it.first })
        assertEquals(listOf(true, false), h.checked())
    }

    @Test
    fun aTickIsStoredBeforeItIsSentAndTakenOffWhenTheServerHasIt() = runTest {
        val h = Harness(this, FakeServer(start))
        h.ticks.choose("a", true)
        assertEquals(mapOf("a" to true), h.cache.pending(start.weekStart), "stored while the request is still on its way")
        advanceUntilIdle()
        assertEquals(emptyMap(), h.cache.pending(start.weekStart))
        assertEquals(listOf(true, true), h.cache.load(start.weekStart)!!.first.items.map { it.checked }, "the server's answer is the stored copy")
    }

    @Test
    fun aTickWithoutAnAnswerStaysStoredAndARefusedOneIsDropped() = runTest {
        val h = Harness(this, FakeServer(start) { key, _ -> if (key == "a") Load.Failed("timeout") else Load.Failed("No such item", 404) })
        h.ticks.choose("a", true)
        h.ticks.choose("b", false)
        advanceUntilIdle()
        assertEquals(mapOf("a" to true), h.cache.pending(start.weekStart), "a waits for later; b was refused")
        assertEquals(listOf(true, true), h.checked())
        assertEquals(listOf("No such item"), h.rejections)
    }

    @Test
    fun aChoiceChangedWhileItsRequestWasUnderWayStaysStored() = runTest {
        val h = Harness(this, FakeServer(start))
        h.ticks.choose("a", true)
        advanceTimeBy(1000)
        h.ticks.choose("a", false)
        advanceTimeBy(1500)
        assertEquals(mapOf("a" to false), h.cache.pending(start.weekStart), "the first answer confirms only the first choice")
        advanceUntilIdle()
        assertEquals(emptyMap(), h.cache.pending(start.weekStart))
    }
}
