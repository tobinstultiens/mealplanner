package dev.tobins.mealplanner.app

import kotlinx.browser.window
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Puts the page on a history entry with [state] (null: one the user made) and [hash], and says `hashchange` as the browser does. */
@JsFun("(state, hash) => { history.replaceState(state, '', hash); window.dispatchEvent(new HashChangeEvent('hashchange')); }")
private external fun arriveAt(state: String?, hash: String)

@JsFun("() => window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a' }))")
private external fun pressKey()

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
        arriveAt(state = "mealplanner:3", hash = "#/offers")
        stop()
        assertEquals(listOf(), heard, "a back handler or a step answers those")
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
        claimStartEntry(afterLogin = false)
        AddressRoutes.write("#/pantry")
        assertEquals("#/pantry", window.location.hash)
        assertTrue(onAppEntry(), "writing marks the entry, so going back to it is not read as a typed address")
    }

    /** Starts on place 5 showing [shown], with a watch that collects the addresses the app is asked to open. */
    private fun startAt5(shown: String, heard: MutableList<String>): () -> Unit {
        arriveAt(state = "mealplanner:5", hash = shown)
        claimStartEntry(afterLogin = false)
        AddressRoutes.write(shown)
        return AddressRoutes.watch { heard += it }
    }

    @Test
    fun aBackToAnEarlierEntryNoHandlerAnswersOpensTheScreenThatWasOpenThere() {
        val heard = mutableListOf<String>()
        val stop = startAt5("#/recipes", heard)
        arriveAt(state = "mealplanner:4", hash = "#/shopping")
        steppedToEntry { false }
        stop()
        assertEquals(listOf("#/shopping"), heard)
    }

    @Test
    fun aBackThatABackHandlerAnswersOpensNothing() {
        val heard = mutableListOf<String>()
        val stop = startAt5("#/profile", heard)
        arriveAt(state = "mealplanner:4", hash = "#/progress")
        steppedToEntry { true }
        stop()
        assertEquals(listOf(), heard)
    }

    @Test
    fun aForwardOpensTheScreenOfTheEntryWithoutAskingTheBackHandlers() {
        val heard = mutableListOf<String>()
        val stop = startAt5("#/shopping", heard)
        arriveAt(state = "mealplanner:6", hash = "#/recipes/2")
        steppedToEntry { error("a forward is not a back") }
        stop()
        assertEquals(listOf("#/recipes/2"), heard)
    }

    @Test
    fun theScreenAStepOpenedGetsNoNewEntryFromItsBackHandler() {
        val stop = startAt5("#/recipes", mutableListOf())
        arriveAt(state = "mealplanner:4", hash = "#/settings")
        steppedToEntry { false }
        AddressRoutes.write("#/settings")
        val entries = window.history.length
        pushBackEntry()
        assertEquals(entries, window.history.length, "the entry stepped to is the screen's entry, and a push would cut off those ahead")
        pushBackEntry()
        assertEquals(entries, window.history.length, "a screen can have several handlers")
        AddressRoutes.write("#/offers")
        pushBackEntry()
        assertEquals(entries + 1, window.history.length, "a later push is a new screen's")
        stop()
    }

    @Test
    fun aStepToTheEntryOfTheScreenOnShowOpensNothing() {
        val heard = mutableListOf<String>()
        val stop = startAt5("#/recipes", heard)
        arriveAt(state = "mealplanner:4", hash = "#/recipes")
        steppedToEntry { false }
        stop()
        assertEquals(listOf(), heard)
    }

    @Test
    fun aBackOntoTheEntryTheLoginPageReturnedToPushesNothingAndTheStepForwardAgainKeepsTheScreen() {
        val heard = mutableListOf<String>()
        val stop = startAt5("#/shopping", heard)
        arriveAt(state = "mealplanner:4:base", hash = "#/today")
        val entries = window.history.length
        steppedToEntry { false }
        assertEquals(entries, window.history.length, "a new guard entry would cut off the entries ahead")
        // The browser's step forward to the guard entry that is still there.
        arriveAt(state = "mealplanner:5", hash = "#/today")
        steppedToEntry { error("the way back to the guard is not a back") }
        stop()
        assertEquals(listOf(), heard)
        assertEquals("#/shopping", window.location.hash, "the guard entry shows the screen that stayed")
    }

    @Test
    fun aKeyPressAfterAStepMakesAHandlerAfterItANewThingToGoBackFrom() {
        val stop = startAt5("#/recipes", mutableListOf())
        arriveAt(state = "mealplanner:4", hash = "#/recipes/2")
        steppedToEntry { false }
        AddressRoutes.write("#/recipes/2")
        pressKey()
        val entries = window.history.length
        pushBackEntry()
        assertEquals(entries + 1, window.history.length, "selecting, say, turned on by the user's own input")
        stop()
    }

    @Test
    fun aBackHandlersEntryShowsTheScreensAddress() {
        startAt5("#/recipes/bank", mutableListOf())()
        arriveAt(state = "mealplanner:5", hash = "#/recipes")
        pushBackEntry()
        assertEquals("#/recipes/bank", window.location.hash)
    }
}
