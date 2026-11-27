package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.app.recipes.recipes
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.load
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Ktor's browser engine itself, not a stand-in: a fetch the browser rejects is a lost connection, caught like any other (#200). */
class BrowserFetchFailureTest {
    @Test
    fun aServerNothingAnswersAtIsALostConnection() = runTest {
        // Port 1: the browser refuses it without connecting, the same TypeError as a stopped server gives.
        val api = ApiClient(ServerSettings("http://127.0.0.1:1"))
        val failed = assertIs<Load.Failed>(load { api.recipes() })
        assertTrue(failed.lostConnection, "$failed")
        api.close()
    }
}
