package dev.tobins.mealplanner.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The recipe screen's one switch ("As written"); the planner chips above it are toggleable too. */
private val isSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

/** A recipe opened from a meal keeps "As written" when Android re-creates the screen (rotation, dark mode, language). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RecipeRestorationTest {
    @get:Rule
    val compose = createComposeRule()

    private val backend = FakeBackend(false, false, false, false)
    private val api = ApiClient(backend.settings)

    @After
    fun tearDown() {
        api.close()
        backend.close()
    }

    @Test
    fun theAsWrittenSwitchSurvivesARecreatedScreen() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                RecipeDetailScreen(api, 4, null, {}, {}, meal = MealToCook(4, 0.75, 1.5, skipped = false))
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(isSwitch).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("525 g chicken thigh fillet", substring = true).assertExists()

        compose.onNode(isSwitch).performScrollTo().performClick()
        compose.onNodeWithText("700 g chicken thigh fillet", substring = true).assertExists()

        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(isSwitch).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isSwitch).assertIsOn()
        compose.onNodeWithText("700 g chicken thigh fillet", substring = true).assertExists()
    }
}
