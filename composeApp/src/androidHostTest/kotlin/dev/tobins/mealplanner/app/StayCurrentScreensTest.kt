package dev.tobins.mealplanner.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A screen stays current when its client is swapped (session ended, login again, another server) and when a refresh fails. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StayCurrentScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val backend = FakeBackend()
    private val otherBackend = FakeBackend(emptyRecipes = true)
    private val api = ApiClient(backend.settings)
    private val otherApi = ApiClient(otherBackend.settings)

    @After
    fun tearDown() {
        api.close()
        otherApi.close()
        backend.close()
        otherBackend.close()
    }

    private fun waitFor(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aScreenFetchesAgainWhenItsClientIsReplaced() {
        var current by mutableStateOf(api)
        compose.setContent {
            // As App does: a new client comes with a new memory, and the open screen stays where it is.
            val memory = remember(current) { ScreenMemory() }
            CompositionLocalProvider(LocalStrings provides Strings.English, LocalScreenMemory provides memory) {
                RecipeListScreen(current, onOpen = {}, onOpenMapping = {}, onNewRecipe = {})
            }
        }
        waitFor("Overnight oats")

        current = otherApi
        waitFor("No recipes yet")
        compose.onAllNodesWithText("Overnight oats").assertCountEquals(0)
    }

    @Test
    fun aFailedRefreshBehindKeptContentIsShownWithARetry() {
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                RecipeListScreen(api, onOpen = {}, onOpenMapping = {}, onNewRecipe = {})
            }
        }
        waitFor("Overnight oats")

        // The server goes away; syncing fails and the list is fetched again, which fails too.
        backend.close()
        compose.onNodeWithContentDescription("Sync the cooking site").performClick()
        waitFor("Could not refresh. Showing what was loaded before.")
        compose.onNodeWithText("Overnight oats", substring = true).assertExists()
        compose.onNodeWithText("Retry").assertExists()
    }
}
