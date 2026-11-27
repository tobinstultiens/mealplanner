package dev.tobins.mealplanner.app

import androidx.compose.runtime.CompositionLocalProvider
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Pantry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import dev.tobins.mealplanner.app.ingredients.PantryScreen
import dev.tobins.mealplanner.app.recipes.RecipeListScreen
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.assertTrue
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
                RecipeListScreen(current, onOpen = {}, onOpenMapping = {}, onOpenBank = {}, onNewRecipe = {})
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
                RecipeListScreen(api, onOpen = {}, onOpenMapping = {}, onOpenBank = {}, onNewRecipe = {})
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

    @Test
    fun theLineHasRoomOfItsOwnBelowTheEndOfTheList() {
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                RecipeListScreen(api, onOpen = {}, onOpenMapping = {}, onOpenBank = {}, onNewRecipe = {})
            }
        }
        waitFor("Overnight oats")
        backend.close()
        compose.onNodeWithContentDescription("Sync the cooking site").performClick()
        waitFor("Could not refresh")

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Shakshuka", substring = true))
        val lastRow = compose.onNode(hasClickAction() and hasText("Shakshuka", substring = true)).getBoundsInRoot()
        val notice = compose.onNodeWithTag(RefreshNoticeTag).getBoundsInRoot()
        assertTrue(lastRow.bottom <= notice.top, "the last row (ends at ${lastRow.bottom}) must end above the line (starts at ${notice.top})")
    }

    /** A server that answers with an empty recipe list while [up], and with an error when it is not. */
    private class Flaky {
        var up = true
        val api = ApiClient(
            ServerSettings("http://localhost"),
            engine = MockEngine { request ->
                if (up && request.url.encodedPath == "/api/recipes") {
                    respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    respond("""{"error":"down"}""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        )
    }

    @Test
    fun theLineStaysWhenARetryFailsAndGoesWhenItSucceeds() {
        val server = Flaky()
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                RecipeListScreen(server.api, onOpen = {}, onOpenMapping = {}, onOpenBank = {}, onNewRecipe = {})
            }
        }
        waitFor("No recipes yet")
        server.up = false
        compose.onNodeWithContentDescription("Sync the cooking site").performClick()
        waitFor("Could not refresh")

        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RefreshNoticeTag).assertExists()

        server.up = true
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithTag(RefreshNoticeTag).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("No recipes yet", substring = true).assertExists()
    }

    @Test
    fun theLineOnAScreenWithoutNavigationBarClearsTheSystemBar() {
        val server = Flaky().apply { up = false }
        val memory = ScreenMemory().apply { keep("pantry", emptyList<Ingredient>() to Pantry(emptyList(), emptyList())) }
        lateinit var window: View
        compose.setContent {
            window = LocalView.current
            CompositionLocalProvider(LocalStrings provides Strings.English, LocalScreenMemory provides memory) {
                PantryScreen(server.api, onBack = {})
            }
        }
        waitFor("Could not refresh")
        val before = compose.onNodeWithTag(RefreshNoticeTag).getBoundsInRoot()

        // Robolectric has no system bars of its own, so the test hands the window a navigation bar of 48 pixels.
        compose.runOnUiThread {
            val bar = Insets.of(0, 0, 0, 48)
            val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.navigationBars(), bar).build()
            ViewCompat.dispatchApplyWindowInsets(window, insets)
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag(RefreshNoticeTag).getBoundsInRoot()
        assertTrue(after.bottom - after.top > before.bottom - before.top, "the line must grow by the system bar (was ${before.bottom - before.top}, is ${after.bottom - after.top})")
    }
}
