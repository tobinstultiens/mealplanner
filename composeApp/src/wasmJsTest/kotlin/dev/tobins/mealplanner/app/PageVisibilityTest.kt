package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.OfferExclusions
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Hides the page and shows it again as the browser does: `document.visibilityState` says so and a
 * `visibilitychange` event follows. A headless browser cannot hide its own tab, so the state is
 * stood in for on the page's document; the event and the listeners are the browser's own.
 */
@JsFun(
    """(hidden) => {
        Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => hidden ? 'hidden' : 'visible' });
        document.dispatchEvent(new Event('visibilitychange'));
    }""",
)
private external fun switchPage(hidden: Boolean)

/** Gives the document its own `visibilityState` back. */
@JsFun("() => { delete document.visibilityState; }")
private external fun restoreVisibility()

/** The web reports hiding and showing the page the moment it happens, with no frame drawn in between (#200). */
@OptIn(ExperimentalCoroutinesApi::class)
class PageVisibilityTest {
    @AfterTest
    fun restore() = restoreVisibility()

    @Test
    fun hidingAndShowingThePageIsReportedAtOnce() {
        val front = AppFront(inFront = true)
        val stop = watchPageVisibility(front::report)

        switchPage(hidden = true)
        assertFalse(front.inFront.value)
        switchPage(hidden = false)
        assertTrue(front.inFront.value)
        assertEquals(1, front.returns, "one return, for the screen in front to fetch once")

        stop()
        switchPage(hidden = true)
        assertTrue(front.inFront.value, "no longer listening")
    }

    /** Counts what the shopping list asks; every list is the same empty one. */
    private class CountingServer : ShoppingServer {
        var lists = 0
        private fun noItems(week: String) = Load.Ready(ShoppingList(week, listOf(), staples = listOf()))
        override suspend fun list(weekStart: String): Load<ShoppingList> = noItems(weekStart).also { lists++ }
        override suspend fun check(weekStart: String, key: String, checked: Boolean, grams: Double?, shown: ShoppingCheck.Shown?) = noItems(weekStart)
        override suspend fun dismiss(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean) = noItems(weekStart)
        override suspend fun addExtra(weekStart: String, name: String) = noItems(weekStart)
        override suspend fun removeExtra(weekStart: String, id: Long) = noItems(weekStart)
        override suspend fun tickAgain(weekStart: String, key: String) = noItems(weekStart)
        override suspend fun useUp(weekStart: String, key: String) = noItems(weekStart)
        override suspend fun putOnHand(ingredientId: Long): Load<Unit> = Load.Ready(Unit)
        override suspend fun setExcluded(change: ExclusionChange): Load<OfferExclusions> = Load.Ready(OfferExclusions(emptyList()))
        override suspend fun exclusions(): Load<OfferExclusions> = Load.Ready(OfferExclusions(emptyList()))
    }

    @Test
    fun aHiddenPageStopsTheShoppingChecksAndTheClockAndShowingItFetchesOnce() = runTest {
        val server = CountingServer()
        val session = ShoppingSession(backgroundScope, server, ShoppingCache(MemorySettingsStorage()), { "2026-11-18" }, shoppingMode = { true })
        val front = AppFront(inFront = true)
        val stop = watchPageVisibility(front::report)
        var clockReads = 0
        backgroundScope.launch { session.follow(front, loggedIn = true) }
        backgroundScope.launch { front.whileInFront(30.seconds) { clockReads++ } }
        session.resume()
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(3, server.lists, "on showing and every 30 s in shopping mode")
        assertEquals(3, clockReads)

        switchPage(hidden = true)
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(3, server.lists, "no checks while hidden")
        assertEquals(3, clockReads, "no clock while hidden")

        switchPage(hidden = false)
        runCurrent()
        assertEquals(4, server.lists, "fetched once on return")
        assertEquals(4, clockReads, "the clock read at once on return")
        stop()
    }
}
