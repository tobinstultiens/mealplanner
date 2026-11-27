package dev.tobins.mealplanner.app.accounts

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.tobins.mealplanner.api.ActivityLevel
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

/** The profile's portion per meal (issue 227): shown as stored, checked as typed, saved with the rest. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProfilePortionsTest {
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

    private val stored = Profile(80.0, 180.0, 40, Sex.MALE, ActivityLevel.LIGHT, minPortions = 1.0, maxPortions = 1.75)

    @Test
    fun theStoredRangeIsShownInTheAppsLanguage() {
        backend.profile = stored
        show(Strings.Dutch)
        waitFor("Portie per maaltijd")
        compose.onNodeWithText("Van (porties)").assertTextContains("1")
        compose.onNodeWithText("Tot (porties)").assertTextContains("1,75")
    }

    @Test
    fun anAccountWithoutAProfileStartsFromTheDefaultsInTheAppsLanguage() {
        show(Strings.Dutch)
        waitFor("Portie per maaltijd")
        compose.onNodeWithText("Van (porties)").assertTextContains("0,75")
        compose.onNodeWithText("Tot (porties)").assertTextContains("1,5")
        compose.onNodeWithText("Afvallen per week (kg, 0–1)").assertTextContains("0,5")
    }

    @Test
    fun aRangeNotInQuartersOrUpsideDownIsExplainedAndNotSaved() {
        backend.profile = stored
        show()
        waitFor("Portion per meal")
        compose.onNodeWithText("From (servings)").performScrollTo().performTextReplacement("0.8")
        waitFor("Smallest portion must be in quarter servings, such as 1.25")
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()

        compose.onNodeWithText("From (servings)").performTextReplacement("2")
        waitFor("The smallest portion must not be larger than the largest")
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList(), backend.changes.filter { it.startsWith("PUT /api/profile") })
    }

    @Test
    fun aTypedRangeIsSavedWithTheProfile() {
        backend.profile = stored
        show()
        waitFor("Portion per meal")
        compose.onNodeWithText("From (servings)").performScrollTo().performTextReplacement("0,5")
        compose.onNodeWithText("To (servings)").performTextReplacement("2.25")
        compose.onNodeWithText("Save").performScrollTo().assertIsEnabled().performClick()
        waitFor("Profile saved")
        assertEquals(stored.copy(minPortions = 0.5, maxPortions = 2.25), backend.profile)
    }
}
