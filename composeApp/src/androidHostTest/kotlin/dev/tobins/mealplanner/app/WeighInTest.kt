package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** Saving a weigh-in closes the number pad, which would otherwise stay up over the tab bar. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WeighInTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend()
    private val api = ApiClient(backend.settings)

    @After
    fun tearDown() {
        api.close()
        backend.close()
    }

    @Test
    fun theWeightFieldLosesItsFocusWhenTheWeighInIsSaved() {
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) { ProgressScreen(api, onOpenProfile = {}, onOpenServer = {}) }
        }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Today's weight (kg)").fetchSemanticsNodes().isNotEmpty() }

        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInput("81.5")
        field.assertIsFocused()

        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { backend.changes.any { it.startsWith("PUT /api/weights/") } }
        assertEquals(true, backend.changes.last().endsWith("81.5"))
        field.assertIsNotFocused()
    }
}
