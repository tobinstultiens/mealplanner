package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shopping list's state as the app keeps it, across leaving the screen, closing the app and changing week. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingSessionTest {
    private val monday = "2026-11-16"

    /**
     * A server that answers after [latency] ms and remembers what it was sent, in order. [answer]
     * replaces the answer to a tick, and a tick of a key in [hanging] is never answered.
     */
    private class FakeServer(var latency: Long = 2000) : ShoppingServer {
        val lists = mutableMapOf<String, ShoppingList>()
        val ticks = mutableListOf<Triple<String, String, Boolean>>()
        var answer: ((String, Boolean) -> Load<ShoppingList>)? = null
        val hanging = mutableSetOf<String>()

        fun listFor(week: String): ShoppingList = lists.getOrPut(week) {
            ShoppingList(week, listOf("a", "b", "c").map { ShoppingItem(it, it, 100.0, null, "Vegetables", listOf("Soup"), onHand = false, checked = false) }, staples = emptyList())
        }

        override suspend fun list(weekStart: String): Load<ShoppingList> {
            delay(latency)
            return Load.Ready(listFor(weekStart))
        }

        override suspend fun check(weekStart: String, key: String, checked: Boolean): Load<ShoppingList> {
            if (key in hanging) awaitCancellation()
            delay(latency)
            ticks += Triple(weekStart, key, checked)
            answer?.let { return it(key, checked) }
            lists[weekStart] = listFor(weekStart).withTicks(mapOf(key to checked))
            return Load.Ready(listFor(weekStart))
        }

        override suspend fun dismiss(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean): Load<ShoppingList> {
            delay(latency)
            lists[weekStart] = listFor(weekStart).copy(buyAheadDismissed = dismissed)
            return Load.Ready(listFor(weekStart))
        }
    }

    private val storage = MemorySettingsStorage()
    private val server = FakeServer()

    private fun TestScope.session(today: () -> String = { "2026-11-18" }) = ShoppingSession(backgroundScope, server, ShoppingCache(storage), today)

    /** Lets a minute pass, time for every request; advanceUntilIdle does not wait for the background scope the sessions run in. */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun ShoppingSession.shown(): ShoppingList = (state as Load.Ready).value

    private fun ShoppingSession.checked() = shown().items.map { it.checked }

    private fun pending(week: String = monday) = ShoppingCache(storage).pending(week)

    @Test
    fun ticksQueuedWhenTheScreenIsLeftStillReachTheServerInOrder() = runTest {
        val session = session()
        // What the screen runs in its own scope, which leaving it cancels.
        val screen = CoroutineScope(coroutineContext + Job())
        screen.launch { session.resume() }
        settle()

        screen.launch {
            session.tick("a", true)
            session.tick("b", true)
            session.tick("c", true)
        }
        runCurrent()
        assertEquals(listOf(true, true, true), session.checked(), "shown at once")
        assertEquals(emptyList(), server.ticks, "the first request is still unanswered")
        screen.cancel()

        settle()
        assertEquals(listOf("a", "b", "c").map { Triple(monday, it, true) }, server.ticks)
        assertEquals(listOf(true, true, true), session.checked())
        assertEquals(emptyMap(), pending())
    }

    @Test
    fun aTickOfAnAppThatWasClosedIsSentByTheNextSessionOnOpen() = runTest {
        val first = session()
        first.resume()
        settle()
        first.tick("b", true)
        advanceTimeBy(1000)
        first.close()
        settle()
        assertEquals(emptyList(), server.ticks, "the app was killed before the request was answered")

        val next = session()
        next.resume()
        assertEquals(listOf(false, true, false), next.checked(), "the stored copy with the tick, before the server answered")
        settle()
        assertEquals(listOf(Triple(monday, "b", true)), server.ticks)
        assertEquals(listOf(false, true, false), next.checked())
        assertEquals(emptyMap(), pending())
    }

    @Test
    fun aStoredTickTheServerRefusesIsDroppedAndTheListStillLoads() = runTest {
        ShoppingCache(storage).apply {
            save(server.listFor(monday), "earlier")
            choose(monday, "a", true)
        }
        server.answer = { _, _ -> Load.Failed("No such item", 404) }
        val session = session()
        session.resume()
        settle()
        assertEquals(listOf(false, false, false), session.checked())
        assertNull(session.offlineSince)
        assertEquals(emptyMap(), pending())
        assertEquals(listOf(Msg.TICK_FAILED to "No such item"), session.notices.map { it.msg to it.detail }, "said the next time the list shows")
        session.shown(session.notices.single())
        assertEquals(emptyList(), session.notices.toList())
    }

    @Test
    fun aRequestThatNeverAnswersIsGivenUpAfterTheTimeLimitAndHoldsUpNothing() = runTest {
        val session = session()
        session.resume()
        settle()
        server.hanging += "a"
        session.tick("a", true)
        session.tick("b", true)
        advanceTimeBy(ShoppingSession.TIME_LIMIT.inWholeMilliseconds - 1)
        assertNull(session.offlineSince, "still waiting")

        advanceTimeBy(2)
        runCurrent()
        assertNotNull(session.offlineSince, "no answer counts as no connection")
        assertEquals(mapOf("a" to true, "b" to true), pending(), "both kept for later; b was not held up behind a")
        assertEquals(listOf(true, true, false), session.checked())

        server.hanging.clear()
        session.retry()
        settle()
        assertEquals(listOf(Triple(monday, "a", true), Triple(monday, "b", true)), server.ticks)
        assertNull(session.offlineSince)
        assertEquals(emptyMap(), pending())
        assertEquals(listOf(true, true, false), session.checked())
    }

    @Test
    fun changingWeekNeverShowsAnotherWeeksList() = runTest {
        val session = session()
        val next = "2026-11-23"
        val later = "2026-11-30"
        fun check() {
            val state = session.state
            if (state is Load.Ready) assertEquals(session.weekStart, state.value.weekStart, "at ${testScheduler.currentTime} ms")
        }
        suspend fun stepThrough(ms: Long) = repeat((ms / 100).toInt()) {
            advanceTimeBy(100)
            runCurrent()
            check()
        }

        session.resume()
        stepThrough(2500)
        session.tick("a", true)
        session.open(next)
        check()
        assertEquals(Load.Loading, session.state, "nothing stored for next week: loading, not this week's rows")
        stepThrough(1000)
        session.open(later)
        stepThrough(500)
        session.open(next)
        stepThrough(1000)
        session.open(monday)
        check()
        assertTrue(session.shown().items.first().checked, "this week's stored copy at once, with its tick")
        stepThrough(20_000)
        assertEquals(monday, session.weekStart)
        assertEquals(listOf(true, false, false), session.checked())
    }

    @Test
    fun theWeekAndTheOpenedGroupsAreThereWhenTheScreenComesBack() = runTest {
        var today = "2026-11-18"
        val session = session { today }
        session.resume()
        settle()
        session.open("2026-11-23")
        session.toggle("done:Vegetables")
        session.scrollIndex = 12
        settle()

        // A recipe was opened; the screen shows again.
        session.resume()
        assertEquals("2026-11-23", session.weekStart)
        assertEquals(setOf("done:Vegetables"), session.expanded)
        assertEquals(12, session.scrollIndex)
        assertEquals("2026-11-23", session.shown().weekStart, "the list stays while it is fetched again")

        // Days later, by then the current week: stepping back to it means following the calendar again.
        today = "2026-11-25"
        session.open("2026-11-23")
        today = "2026-12-02"
        session.resume()
        assertEquals("2026-11-30", session.weekStart)
    }

    @Test
    fun aSessionMadeAgainAtItsPlaceShowsTheSamePlace() = runTest {
        val before = session()
        before.open("2026-11-23")
        before.toggle("elsewhere")
        before.scrollIndex = 7
        before.scrollOffset = 30
        // Android recreated the screen: the place was saved, the session is new.
        val after = ShoppingSession(backgroundScope, server, ShoppingCache(storage), { "2026-11-18" }, before.place)
        assertEquals(before.place, after.place)
        after.resume()
        assertEquals("2026-11-23", after.weekStart)
        settle()
        assertEquals("2026-11-23", after.shown().weekStart)
    }

    @Test
    fun withoutAConnectionATickStaysTickedAndGoesOutOnRetry() = runTest {
        val session = session()
        session.resume()
        settle()
        server.answer = { _, _ -> Load.Failed("Unable to resolve host") }
        session.tick("a", true)
        settle()
        assertEquals(listOf(true, false, false), session.checked(), "the row stays as chosen")
        assertNotNull(session.offlineSince, "the line that says the list is offline and ticks are sent later")
        assertEquals(mapOf("a" to true), pending())
        assertEquals(emptyList(), session.notices.toList())

        server.answer = null
        session.retry()
        settle()
        assertEquals(Triple(monday, "a", true), server.ticks.last())
        assertEquals(emptyMap(), pending())
        assertNull(session.offlineSince)
    }

    @Test
    fun aServerErrorKeepsTheTickForLaterToo() = runTest {
        val session = session()
        session.resume()
        settle()
        server.answer = { _, _ -> Load.Failed("Bad gateway", 502) }
        session.tick("a", true)
        settle()
        assertEquals(listOf(true, false, false), session.checked())
        assertEquals(mapOf("a" to true), pending())
        assertNotNull(session.offlineSince)
    }

    @Test
    fun aRefusedTickGoesBackAndIsReported() = runTest {
        val session = session()
        session.resume()
        settle()
        server.answer = { _, _ -> Load.Failed("No such item", 404) }
        session.tick("a", true)
        assertEquals(listOf(true, false, false), session.checked())
        settle()
        assertEquals(listOf(false, false, false), session.checked(), "back as the server has it")
        assertEquals(listOf(Msg.TICK_FAILED to "No such item"), session.notices.map { it.msg to it.detail })
        assertEquals(emptyMap(), pending())
        assertNull(session.offlineSince)
    }

    @Test
    fun afterClosingNothingIsSentOrStoredSoLoggingOutLeavesNothingBehind() = runTest {
        val session = session()
        session.resume()
        settle()
        session.tick("a", true)
        session.tick("b", true)
        runCurrent()
        session.close()
        storage.logOut()
        settle()
        assertEquals(emptyList(), server.ticks)
        assertNull(ShoppingCache(storage).load(monday))

        val nextAccount = session()
        nextAccount.resume()
        assertEquals(Load.Loading, nextAccount.state, "nothing of the previous account is shown")
        settle()
        assertEquals(listOf(false, false, false), nextAccount.checked())
        assertEquals(emptyList(), server.ticks)
    }

    @Test
    fun hidingAdviceGoesInTurnAndAFailureIsReported() = runTest {
        val session = session()
        session.resume()
        settle()
        session.tick("a", true)
        session.dismiss(ShoppingAdviceGroup.BUY_AHEAD, true)
        settle()
        assertTrue(session.shown().buyAheadDismissed)
        assertEquals(listOf(true, false, false), session.checked(), "the answer to the tick came first")

        val offline = ShoppingSession(backgroundScope, object : ShoppingServer by server {
            override suspend fun dismiss(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean) = Load.Failed("Server down", 503)
        }, ShoppingCache(storage), { "2026-11-18" })
        offline.resume()
        settle()
        offline.dismiss(ShoppingAdviceGroup.BUY_AHEAD, false)
        settle()
        assertEquals(listOf(Msg.ADVICE_CHANGE_FAILED to "Server down"), offline.notices.map { it.msg to it.detail })
    }
}
