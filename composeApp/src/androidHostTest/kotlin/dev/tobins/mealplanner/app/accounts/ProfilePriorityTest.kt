package dev.tobins.mealplanner.app.accounts

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.PlanPriority
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Strings
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The profile's priority and fibre target (issue 223): shown as stored, checked as typed, saved with the rest. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProfilePriorityTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend()
    private val api = ApiClient(backend.settings)

    @After
    fun tearDown() {
        api.close()
        backend.close()
    }

    private fun show(strings: Strings = Strings.English) = compose.setContent {
        CompositionLocalProvider(LocalStrings provides strings) { ProfileScreen(api, onBack = {}) }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private val stored = Profile(80.0, 180.0, 40, Sex.MALE, ActivityLevel.LIGHT, priority = PlanPriority.PRICE, fibreOverrideG = 40)

    @Test
    fun theStoredPriorityAndFibreTargetAreShown() {
        backend.profile = stored
        show(Strings.Dutch)
        waitFor("Waar de planner het zwaarst op let")
        compose.onNodeWithText("Prijs").performScrollTo().assertIsSelected()
        compose.onNodeWithText("In balans").assertIsNotSelected()
        compose.onNodeWithText("Vezels per dag (g)").assertTextContains("40")
    }

    @Test
    fun aChosenPriorityAndFibreTargetAreSavedWithTheProfile() {
        backend.profile = stored
        show()
        waitFor("What the planner weighs most")
        compose.onNodeWithText("Protein").performScrollTo().performClick()
        compose.onNodeWithText("Fibre per day (g)").performScrollTo().performTextReplacement("45")
        compose.onNodeWithText("Save").performScrollTo().assertIsEnabled().performClick()
        waitFor("Profile saved")
        assertEquals(stored.copy(priority = PlanPriority.PROTEIN, fibreOverrideG = 45), backend.profile)
    }

    @Test
    fun anEmptiedFibreTargetIsSentAsNoneSoTheServerDropsIt() {
        backend.profile = stored
        show()
        waitFor("Fibre per day (g)")
        compose.onNodeWithText("Fibre per day (g)").performScrollTo().performTextReplacement("")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        waitFor("Profile saved")
        assertEquals(null, backend.profile!!.fibreOverrideG)
        // Written out, not left out: the server keeps a stored target for an app that leaves the field out.
        val sent = backend.changes.last { it.startsWith("PUT /api/profile") }
        assertTrue("\"fibreOverrideG\":null" in sent, sent)
    }

    @Test
    fun aFibreTargetOutsideTenToAHundredIsExplainedAndNotSaved() {
        backend.profile = stored
        show()
        waitFor("Fibre per day (g)")
        compose.onNodeWithText("Fibre per day (g)").performScrollTo().performTextReplacement("5")
        waitFor("Fibre per day (g) must be between 10 and 100")
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList(), backend.changes.filter { it.startsWith("PUT /api/profile") })
    }
}
