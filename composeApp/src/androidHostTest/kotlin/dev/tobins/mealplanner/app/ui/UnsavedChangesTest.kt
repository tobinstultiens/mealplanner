package dev.tobins.mealplanner.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.accounts.ProfileScreen
import dev.tobins.mealplanner.app.recipes.RecipeEditorScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Going back from the profile and the recipe editor with something typed asks "Discard your changes?";
 * without changes, or after saving, it leaves at once. The arrow and the system back (the gesture, the
 * browser's back) behave the same, and what was typed survives a re-created activity.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UnsavedChangesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend()
    private val api = ApiClient(backend.settings)
    private var left = 0

    @After
    fun tearDown() {
        api.close()
        backend.close()
    }

    private val stored = Profile(80.0, 180.0, 40, Sex.MALE, ActivityLevel.LIGHT)

    private fun showProfile() = compose.setContent {
        CompositionLocalProvider(LocalStrings provides Strings.English) { ProfileScreen(api, onBack = { left++ }) }
    }

    private fun showEditor(recipeId: Long?) = compose.setContent {
        CompositionLocalProvider(LocalStrings provides Strings.English) { RecipeEditorScreen(api, recipeId, onDone = {}, onCancel = { left++ }) }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun arrow(description: String = "Back") = compose.onNodeWithContentDescription(description).performClick()

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun questionAsked() = compose.onAllNodesWithText("Discard your changes?").fetchSemanticsNodes().isNotEmpty()

    private val profileSaves get() = backend.changes.filter { it.startsWith("PUT /api/profile") }

    @Test
    fun theProfileAsksBeforeTheArrowLeavesWithChangesAndKeepEditingKeepsThem() {
        backend.profile = stored
        showProfile()
        waitFor("Weight (kg)")
        compose.onNodeWithText("Weight (kg)").performTextInput("5")

        arrow()
        assertTrue(questionAsked())
        assertEquals(0, left)

        compose.onNodeWithText("Keep editing").performClick()
        assertFalse(questionAsked())
        assertEquals(0, left)
        compose.onNodeWithText("Weight (kg)").assertTextContains("580")
    }

    @Test
    fun discardingLeavesAndSavesNothing() {
        backend.profile = stored
        showProfile()
        waitFor("Weight (kg)")
        compose.onNodeWithText("Weight (kg)").performTextInput("5")

        arrow()
        compose.onNodeWithText("Discard").performClick()
        assertEquals(1, left)
        assertEquals(emptyList(), profileSaves)
        assertEquals(stored, backend.profile)
    }

    @Test
    fun theSystemBackAsksToo() {
        backend.profile = stored
        showProfile()
        waitFor("Weight (kg)")
        compose.onNodeWithText("Age").performTextInput("1")
        // Back first hides the keyboard of a field in focus; the test has none up.
        compose.onNodeWithText("Daily targets").performClick()

        systemBack()
        assertTrue(questionAsked())
        assertEquals(0, left)

        compose.onNodeWithText("Keep editing").performClick()
        systemBack()
        compose.onNodeWithText("Discard").performClick()
        assertEquals(1, left)
    }

    @Test
    fun aFirstVisitLeavesWhenEmptyAndAsksOnceSomethingIsTyped() {
        showProfile()
        waitFor("Weight (kg)")
        arrow()
        assertEquals(1, left)

        compose.onNodeWithText("Weight (kg)").performTextInput("70")
        arrow()
        assertTrue(questionAsked())
    }

    @Test
    fun theProfileLeavesWithoutAQuestionWhenNothingChangedAndAfterSaving() {
        backend.profile = stored
        showProfile()
        waitFor("Weight (kg)")
        systemBack()
        assertEquals(1, left)
        assertFalse(questionAsked())

        compose.onNodeWithText("Likes, comma separated").performTextInput("spicy")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        waitFor("Profile saved")
        assertEquals(1, profileSaves.size)

        arrow()
        assertEquals(2, left)
        assertFalse(questionAsked())
    }

    @Test
    fun whatWasTypedInTheProfileSurvivesARecreatedActivityAndIsStillUnsaved() {
        backend.profile = stored
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) { ProfileScreen(api, onBack = { left++ }) }
        }
        waitFor("Weight (kg)")
        compose.onNodeWithText("Weight (kg)").performTextInput("5")

        restoration.emulateSavedInstanceStateRestore()
        waitFor("Weight (kg)")
        compose.onNodeWithText("Weight (kg)").assertTextContains("580")
        arrow()
        assertTrue(questionAsked())
        assertEquals(0, left)
    }

    @Test
    fun aNewRecipeAsksOnceSomethingIsTypedAndLeavesWhenEmpty() {
        showEditor(null)
        waitFor("Name")
        arrow("Cancel")
        assertEquals(1, left)

        compose.onNodeWithText("Name").performTextInput("Quark with berries")
        arrow("Cancel")
        assertTrue(questionAsked())
        assertEquals(1, left)

        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Name").assertTextContains("Quark with berries")

        systemBack()
        assertTrue(questionAsked())
        compose.onNodeWithText("Discard").performClick()
        assertEquals(2, left)
        assertEquals(emptyList(), backend.changes)
    }

    @Test
    fun anEditedRecipeAsksOnlyAfterAChangeAndDiscardingSavesNothing() {
        showEditor(4)
        waitFor("Name")
        systemBack()
        assertEquals(1, left)
        assertFalse(questionAsked())

        compose.onNodeWithText("Name").performTextInput("!")
        arrow("Cancel")
        assertTrue(questionAsked())
        compose.onNodeWithText("Discard").performClick()
        assertEquals(2, left)
        assertEquals(emptyList(), backend.changes)
    }

    @Test
    fun whatWasTypedInTheEditorSurvivesARecreatedActivityAndIsStillUnsaved() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) { RecipeEditorScreen(api, null, onDone = {}, onCancel = { left++ }) }
        }
        waitFor("Name")
        compose.onNodeWithText("Name").performTextInput("Quark")

        restoration.emulateSavedInstanceStateRestore()
        waitFor("Name")
        compose.onNodeWithText("Name").assertTextContains("Quark")
        arrow("Cancel")
        assertTrue(questionAsked())
    }
}
