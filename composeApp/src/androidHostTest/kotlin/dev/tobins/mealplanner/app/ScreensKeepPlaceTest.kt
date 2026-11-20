package dev.tobins.mealplanner.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import kotlinx.coroutines.MainScope
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

    /** The device's storage, which tells the app of a login made elsewhere as a second browser tab's would ([SettingsStorage.watch]). */
    private val storage = object : SettingsStorage by MemorySettingsStorage() {
        var onChange: () -> Unit = {}

        override fun watch(onChange: () -> Unit): () -> Unit {
            this.onChange = onChange
            return { this.onChange = {} }
        }
    }

    private fun startApp() {
        storage.saveSession(Session("anna-token", expiresAt = Long.MAX_VALUE))
        val launcher = object : LoginLauncher {
            override fun redirectUri(serverUrl: String) = "$serverUrl/app/login"
            override fun open(url: String) = Unit
        }
        val logins = LoginExchange(storage, MainScope())
        restoration.setContent { App(backend.settings, storage, launcher, logins) }
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
    fun anotherLoginStartsThePlanAfreshThisWeekAtTheTop() {
        startApp()
        waitFor("Today")
        openTab("Plan")
        waitFor("Week of 16 Nov")
        compose.onNodeWithContentDescription("Next week").performClick()
        waitFor("Week of 23 Nov")
        waitFor("Mon 16 Nov")
        compose.onNode(hasScrollAction()).performScrollToIndex(6)
        waitFor("Sat 21 Nov")

        // Logged out in the settings, and in again, maybe as someone else (issue 263).
        openTab("Progress")
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor("Log out")
        compose.onNodeWithText("Log out").performScrollTo().performClick()
        waitFor("You are logged out.")
        storage.saveSession(Session("bert-token", expiresAt = Long.MAX_VALUE))
        compose.runOnIdle { storage.onChange() }

        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Plan").fetchSemanticsNodes().isNotEmpty() }
        openTab("Plan")
        waitFor("Week of 16 Nov")
        waitFor("Mon 16 Nov")
        compose.onNodeWithText("Mon 16 Nov").assertIsDisplayed()
        compose.onAllNodesWithText("Week of 23 Nov").assertCountEquals(0)
    }

    @Test
    fun theSearchOfTheRecipeListSurvivesOpeningARecipe() {
        // Tall enough for every row of the list, so that "Shakshuka" being gone is the search's doing.
        RuntimeEnvironment.setQualifiers("en-rGB-w411dp-h1200dp")
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
    fun theChoiceOfTheRecipeListSurvivesOpeningARecipeAndARecreatedActivity() {
        startApp()
        waitFor("Today")
        openTab("Recipes")
        waitFor("Not in rotation (2)")
        compose.onNodeWithText("Rotation (4)").performClick()
        waitFor("Lentil soup")
        compose.onAllNodesWithText("Tuna pasta salad").assertCountEquals(0)

        compose.onNodeWithText("Lentil soup").performClick()
        goBack()
        waitFor("Lentil soup")
        compose.onAllNodesWithText("Tuna pasta salad").assertCountEquals(0)

        // Android re-creates the activity: still the rotation, still without the recipes outside it.
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Lentil soup")
        compose.onAllNodesWithText("Tuna pasta salad").assertCountEquals(0)
        compose.onAllNodesWithText("Shakshuka").assertCountEquals(0)
    }

    @Test
    fun theSearchOfTheBankSurvivesOpeningARecipeAndARecreatedActivity() {
        startApp()
        waitFor("Today")
        openTab("Recipes")
        waitFor("Browse the bank")
        compose.onNodeWithText("Browse the bank").performClick()
        waitFor("Greek yoghurt pancakes")
        compose.onNodeWithText("Search title or tag").performTextInput("quick")
        waitFor("Greek yoghurt pancakes")
        compose.onAllNodesWithText("Soba noodle salad").assertCountEquals(0)

        // A bank recipe opens read-only; back from it the bank is as it was.
        compose.onNodeWithText("Greek yoghurt pancakes").performClick()
        waitFor("Add to my recipes")
        goBack()
        waitFor("Greek yoghurt pancakes")
        compose.onAllNodesWithText("Soba noodle salad").assertCountEquals(0)

        restoration.emulateSavedInstanceStateRestore()
        waitFor("Greek yoghurt pancakes")
        compose.onAllNodesWithText("Soba noodle salad").assertCountEquals(0)
        compose.onNodeWithText("quick").assertExists()
    }

    @Test
    fun theScrollOfTheBankSurvivesOpeningARecipeAndARecreatedActivity() {
        // A short phone, so that the five recipes of the bank do not all fit.
        RuntimeEnvironment.setQualifiers("en-rGB-w411dp-h480dp")
        startApp()
        waitFor("Today")
        openTab("Recipes")
        waitFor("Browse the bank")
        compose.onNodeWithText("Browse the bank").performClick()
        waitFor("Miso aubergine with sesame rice")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        waitFor("Banana oat cookies")
        compose.onNodeWithText("Banana oat cookies").assertIsDisplayed()

        compose.onNodeWithText("Banana oat cookies").performClick()
        waitFor("Add to my recipes")
        goBack()
        waitFor("Banana oat cookies")
        compose.onNodeWithText("Banana oat cookies").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        waitFor("Banana oat cookies")
        compose.onNodeWithText("Banana oat cookies").assertIsDisplayed()
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
