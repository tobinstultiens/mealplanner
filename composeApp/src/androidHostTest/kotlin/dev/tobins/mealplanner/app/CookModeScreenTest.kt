package dev.tobins.mealplanner.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.tobins.mealplanner.api.MealSlot
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cook mode as it is used: opened from the recipe, ticked off, kept through a re-created screen, and the meal marked eaten at the end. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CookModeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend(false, false, false, false)
    private val api = ApiClient(backend.settings)
    private val planned = MealToCook(4, 0.75, 1.5, skipped = false, place = MealPlace("2026-11-16", 2, MealSlot.DINNER), planned = true)

    @After
    fun tearDown() {
        api.close()
        backend.close()
    }

    private fun show(meal: MealToCook?, initiallyCooking: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                RecipeDetailScreen(api, 4, null, {}, {}, meal = meal, initiallyCooking = initiallyCooking)
            }
        }
    }

    private fun waitFor(text: String) = compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun tapStep(number: Int) = compose.onNodeWithText("Step $number", substring = true).performScrollTo().performClick()

    @Test
    fun tappingAStepMovesTheCurrentOneOnAndMarkEatenComesWithTheLastStep() {
        show(planned)
        waitFor("Step 1 · Now")
        compose.onNodeWithText("525 g chicken thigh fillet", substring = true).assertExists()
        compose.onNodeWithText("Mark as eaten").assertDoesNotExist()

        tapStep(1)
        compose.onNodeWithText("Step 2 · Now").assertExists()
        tapStep(2)
        tapStep(3)
        compose.onNodeWithText("Mark as eaten").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = 10_000) { backend.changes.any { it.startsWith("PUT /api/plans/2026-11-16/meals/2/DINNER/status") } }
        assertTrue(backend.changes.single { it.contains("/status") }.endsWith("\"EATEN\""))
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Mark as eaten").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aMealThatIsNotPlannedHasNothingToMarkEaten() {
        show(planned.copy(planned = false))
        waitFor("Step 1 · Now")
        tapStep(1)
        tapStep(2)
        tapStep(3)
        compose.onNodeWithText("All steps done", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("Mark as eaten").assertDoesNotExist()
        assertEquals(emptyList(), backend.changes.filter { it.contains("/status") })
    }

    @Test
    fun theTicksSurviveARecreatedScreen() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                RecipeDetailScreen(api, 4, null, {}, {}, meal = planned, initiallyCooking = true)
            }
        }
        waitFor("Step 1 · Now")
        tapStep(1)
        compose.onNodeWithText("Step 2 · Now").assertExists()

        restoration.emulateSavedInstanceStateRestore()
        waitFor("Step 2 · Now")
        compose.onNodeWithText("Step 1 · Now").assertDoesNotExist()
    }

    @Test
    fun theAsWrittenSwitchChangesTheAmountsInCookMode() {
        show(planned)
        waitFor("Step 1 · Now")
        compose.onNodeWithText("525 g chicken thigh fillet", substring = true).assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        compose.onNodeWithText("700 g chicken thigh fillet", substring = true).assertExists()
    }

    @Test
    fun theSystemBackLeavesCookModeForTheRecipe() {
        var left = false
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                RecipeDetailScreen(api, 4, null, { left = true }, {}, meal = planned, initiallyCooking = true)
            }
        }
        waitFor("Step 1 · Now")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("Cook")
        compose.onNodeWithText("Step 1 · Now").assertDoesNotExist()
        assertEquals(false, left, "the recipe stays open")
    }

    @Test
    fun theCookButtonOpensCookModeAndBackLeavesItForTheRecipe() {
        show(null, initiallyCooking = false)
        waitFor("Cook")
        compose.onNodeWithText("Cook").performClick()
        waitFor("Step 1 · Now")

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Cook")
        compose.onNodeWithText("Step 1 · Now").assertDoesNotExist()
    }
}
