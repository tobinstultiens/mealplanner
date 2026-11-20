package dev.tobins.mealplanner.app

import kotlinx.browser.window
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Puts the page on a history entry with [state] (null: one the user made) and [hash], and says `hashchange` as the browser does. */
@JsFun("(state, hash) => { history.replaceState(state, '', hash); window.dispatchEvent(new HashChangeEvent('hashchange')); }")
private external fun arriveAt(state: String?, hash: String)

@JsFun("(hash) => history.replaceState(null, '', hash)")
private external fun settleAt(hash: String)

/** A typed or pasted address is followed; the app's own history entries are not mistaken for one (#72, #204). */
class AddressTest {
    private val before = window.location.hash

    @AfterTest
    fun restore() = settleAt(before)

    @Test
    fun anAddressTypedIntoThePageIsReported() {
        val heard = mutableListOf<String>()
        val stop = watchAddress { heard += it }
        arriveAt(state = null, hash = "#/recipes/39")
        stop()
        assertEquals(listOf("#/recipes/39"), heard)
    }

    @Test
    fun theBrowsersBackAndForwardBetweenTheAppsOwnEntriesAreNotAnAddress() {
        val heard = mutableListOf<String>()
        val stop = watchAddress { heard += it }
        arriveAt(state = "mealplanner", hash = "#/offers")
        stop()
        assertEquals(listOf(), heard, "a back handler answers those")
    }

    @Test
    fun aStoppedWatchHearsNothing() {
        val heard = mutableListOf<String>()
        watchAddress { heard += it }()
        arriveAt(state = null, hash = "#/offers")
        assertEquals(listOf(), heard)
    }

    @Test
    fun theAppsOwnWritesAreItsOwnEntry() {
        settleAt("#/today")
        AddressRoutes.write("#/pantry")
        assertEquals("#/pantry", window.location.hash)
        assertTrue(onAppEntry(), "writing marks the entry, so going back to it is not read as a typed address")
    }

    @Test
    fun aStaleEntryGetsTheAddressOfTheScreenOnShowBack() {
        AddressRoutes.write("#/shopping")
        arriveAt(state = "mealplanner", hash = "#/settings")
        restoreShownAddress()
        assertEquals("#/shopping", window.location.hash)
    }

    @Test
    fun aBackHandlersEntryShowsTheScreensAddress() {
        AddressRoutes.write("#/recipes/bank")
        arriveAt(state = "mealplanner", hash = "#/recipes")
        pushBackEntry()
        assertEquals("#/recipes/bank", window.location.hash)
    }
}
