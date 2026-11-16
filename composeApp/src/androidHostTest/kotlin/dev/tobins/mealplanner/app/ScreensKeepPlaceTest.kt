package dev.tobins.mealplanner.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The whole app on a fake server: a screen opened from a tab and closed again leaves the tab where it was
 * (the week, the scroll position, the search, the day), also when Android re-creates the activity in between.
 */
@OptIn(ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreensKeepPlaceTest {
    @get:Rule
    val compose = createComposeRule()

    private val backend = FakeBackend()
    private val restoration by lazy { StateRestorationTester(compose) }

    @Before
    fun fixTimeAndLanguage() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        appClock = object : Clock {
            override fun now(): Instant = Instant.parse("${FakeBackend.TODAY}T10:30:00Z")
        }
        RuntimeEnvironment.setQualifiers("en-rGB-w411dp-h900dp")
    }

    @After
    fun tearDown() {
        backend.close()
        appClock = Clock.System
    }

    private fun startApp() {
        val storage = MemorySettingsStorage().apply { saveSession(Session("anna-token", expiresAt = Long.MAX_VALUE)) }
        val launcher = object : LoginLauncher {
            override fun redirectUri(serverUrl: String) = "$serverUrl/app/login"
            override fun open(url: String) = Unit
        }
        restoration.setContent { App(backend.settings, storage, launcher, emptyFlow()) }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** Back from the recipe that was opened: the button is there once the recipe screen is. */
    private fun goBack() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
    }

    private fun openTab(name: String) {
        compose.onAllNodesWithText(name).onFirst().performClick()
    }

    @Test
    fun theWeekAndTheScrollOfThePlanSurviveOpeningARecipeAndARecreatedActivity() {
        startApp()
        waitFor("Today")
        openTab("Plan")
        waitFor("Week of 16 Nov")
        compose.onNodeWithContentDescription("Next week").performClick()
        waitFor("Week of 23 Nov")
        waitFor("Mon 16 Nov")
        compose.onNode(hasScrollAction()).performScrollToIndex(6)
        waitFor("Sat 21 Nov")
        compose.onNodeWithText("Sat 21 Nov").assertIsDisplayed()

        // Open a recipe from the plan and come back.
        compose.onAllNodesWithText("Lentil soup").onLast().performClick()
        goBack()
        waitFor("Week of 23 Nov")
        compose.onNodeWithText("Sat 21 Nov").assertIsDisplayed()

        // Android re-creates the activity: still next week, still scrolled.
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Week of 23 Nov")
        waitFor("Sat 21 Nov")
        compose.onNodeWithText("Sat 21 Nov").assertIsDisplayed()
        compose.onAllNodesWithText("Mon 16 Nov").assertCountEquals(0)
    }

    @Test
    fun theSearchOfTheRecipeListSurvivesOpeningARecipe() {
        startApp()
        waitFor("Today")
        openTab("Recipes")
        waitFor("Shakshuka")
        compose.onNodeWithText("Search title or tag").performTextInput("lentil")
        waitFor("Lentil soup")
        compose.onAllNodesWithText("Shakshuka").assertCountEquals(0)

        compose.onNodeWithText("Lentil soup").performClick()
        goBack()

        waitFor("Lentil soup")
        compose.onAllNodesWithText("Shakshuka").assertCountEquals(0)
        compose.onNodeWithText("lentil").assertExists()
    }

    @Test
    fun theDayOfTodaySurvivesOpeningAMeal() {
        startApp()
        waitFor("Today")
        compose.onNodeWithContentDescription("Next day").performClick()
        waitFor("Thu 19 Nov")
        waitFor("Overnight oats")

        compose.onNodeWithText("Overnight oats", substring = true).performClick()
        goBack()

        waitFor("Thu 19 Nov")
        waitFor("Overnight oats")
    }
}
