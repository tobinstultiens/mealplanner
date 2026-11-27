package dev.tobins.mealplanner.app.tracking

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.WeightEntry
import dev.tobins.mealplanner.api.WeightLog
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

/** Removing a weigh-in can be undone for a moment, and removing the only one says the profile's weight stays (issue 196). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProgressUndoTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend()
    private val api = ApiClient(backend.settings)
    private var openedProfile = 0

    @After
    fun tearDown() {
        api.close()
        backend.close()
    }

    private fun show(strings: Strings = Strings.English) = compose.setContent {
        CompositionLocalProvider(LocalStrings provides strings) { ProgressScreen(api, onOpenProfile = { openedProfile++ }, onOpenServer = {}) }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun profileAt(kg: Double) = Profile(kg, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT)

    private val two = listOf(WeightEntry("2026-11-10", 80.0, 80.0), WeightEntry("2026-11-12", 170.0, 170.0))

    private fun log(entries: List<WeightEntry>) = WeightLog(entries, actualKgPerWeek = null, suggestion = null)

    @Test
    fun undoPutsTheRemovedNewestWeighInBackAndTheProfileFollows() {
        backend.weights = log(two)
        backend.profile = profileAt(170.0)
        show()
        waitFor("Remove")

        compose.onAllNodesWithText("Remove").onFirst().performScrollTo().performClick()
        waitFor("Undo")
        assertEquals(listOf("2026-11-10"), backend.weights.entries.map { it.date })
        assertEquals(80.0, backend.profile!!.weightKg, "the profile went back to the weigh-in before")

        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { backend.weights.entries.size == 2 }
        assertEquals(two.map { it.date to it.kg }, backend.weights.entries.map { it.date to it.kg }, "its date and value are back")
        assertEquals(170.0, backend.profile!!.weightKg, "and the profile follows the newest weigh-in again")
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Remove").fetchSemanticsNodes().size == 2 }
    }

    @Test
    fun theSnackbarNamesTheDayInTheAppsLanguage() {
        backend.weights = log(two)
        show(Strings.Dutch)
        waitFor("Verwijderen")

        compose.onAllNodesWithText("Verwijderen").onFirst().performScrollTo().performClick()
        waitFor("Ongedaan maken")
        waitFor("Meting van do 12 nov verwijderd")
    }

    @Test
    fun removingTheOnlyWeighInSaysTheProfileKeepsItsWeightAndLeadsToTheProfile() {
        backend.weights = log(listOf(WeightEntry("2026-11-12", 170.0, 170.0)))
        backend.profile = profileAt(170.0)
        show()
        waitFor("Remove")

        compose.onNodeWithText("Remove").performClick()
        waitFor(Strings.English(TrackingMsg.WEIGHT_KEPT))
        assertEquals(170.0, backend.profile!!.weightKg)
        compose.onNodeWithText("Open profile").performClick()
        assertEquals(1, openedProfile)

        // Undoing brings the weigh-in back and the note goes.
        compose.onNodeWithText("Undo").performClick()
        waitFor("Remove")
        assertEquals(0, compose.onAllNodesWithText(Strings.English(TrackingMsg.WEIGHT_KEPT)).fetchSemanticsNodes().size)
    }

    @Test
    fun removingOneOfSeveralSaysNothingAboutTheProfile() {
        backend.weights = log(two)
        show()
        waitFor("Remove")
        compose.onAllNodesWithText("Remove").onFirst().performScrollTo().performClick()
        waitFor("Undo")
        assertEquals(0, compose.onAllNodesWithText(Strings.English(TrackingMsg.WEIGHT_KEPT)).fetchSemanticsNodes().size)
    }
}
