package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import dev.tobins.mealplanner.api.LogFoodInput
import dev.tobins.mealplanner.api.LogTextInput
import dev.tobins.mealplanner.api.MealFoodInput
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Food eaten outside the plan on Today (issue 362), on the fake server, which keeps the log it is sent: a food searched and
 * picked with an amount and a unit, then its amount changed; and a food no longer in the catalogue, which is typed over in
 * the dialog that says why.
 *
 * What is tapped is scrolled to first. The food is picked on a screen of its own; the dialog that types an entry over has
 * text fields, which is fine on Robolectric's own window but not on a much taller one (see [RecipeEditorScreenTest]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LoggedFoodsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend(writing = true, loggedFoods = true)
    private val api = ApiClient(backend.settings)

    @Before
    fun fixToday() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        appClock = object : Clock {
            override fun now(): Instant = Instant.parse("${FakeBackend.TODAY}T10:30:00Z")
        }
    }

    @After
    fun tearDown() {
        api.close()
        backend.close()
        appClock = Clock.System
    }

    private fun waitFor(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun showToday() {
        compose.setContent { CompositionLocalProvider(LocalStrings provides Strings.English) { TodayScreen(api, { _, _ -> }, {}) } }
        waitFor("Also eaten")
    }

    /** The changes the fake server got so far, copied under the list's lock: its thread adds to it while the test reads. */
    private fun changes(): List<String> = synchronized(backend.changes) { backend.changes.toList() }

    /** The body of the one change that starts with [start]. */
    private inline fun <reified T> sent(start: String): T = Json.decodeFromString(changes().single { it.startsWith("$start ") }.substringAfter("$start "))

    /** Scrolls Today to the node [matcher] finds and taps it. */
    private fun tap(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollAction()).performScrollToNode(matcher)
        compose.onNode(matcher).performClick()
    }

    @Test
    fun aFoodIsSearchedAndPickedWithAnAmountAndAUnitAndItsAmountIsChanged() {
        showToday()
        tap(hasText("Add a food you ate") and hasClickAction())

        // The food screen of the log: searched as in a slot, the protein bar picked, starting in 100 g; two of its bars instead.
        waitFor("Also eaten")
        val search = hasText("Add an ingredient") and hasSetTextAction()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(search).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(search).performTextReplacement("protein")
        val found = hasText("Protein bar chocolate") and hasText("per 100", substring = true) and hasClickAction()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(found).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(found).performScrollTo().performClick()
        waitFor("Amount")
        compose.onNode(hasText("g") and hasClickAction()).performClick()
        compose.onNode(hasText("bar") and hasAnyAncestor(isPopup())).performClick()
        compose.onNode(hasText("Amount") and hasSetTextAction()).performTextReplacement("2")
        waitFor("Protein bar chocolate · 90 g · 342 kcal")
        compose.onNodeWithText("Add").performClick()

        // Back on Today, the entry is the bar in its unit with what it counted.
        val two = unbrokenParts("2 bar · 342 kcal · 28 g protein")
        waitFor(two)
        assertEquals(LogFoodInput(FakeBackend.TODAY, 40, 2.0, "bar"), sent<LogFoodInput>("POST /api/log/foods"))

        // Opened, it starts in its own amount and unit; one bar instead, which the server recounts.
        tap(hasText(two, substring = true) and hasClickAction())
        val amount = hasText("Amount") and hasSetTextAction()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(amount).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(amount).performTextReplacement("1")
        waitFor("Protein bar chocolate · 45 g · 171 kcal")
        compose.onNodeWithText("Save").performClick()
        waitFor("Also eaten")
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(two, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        val put = changes().single { it.startsWith("PUT /api/log/") }
        assertEquals(MealFoodInput(40, 1.0, "bar"), Json.decodeFromString(put.substringAfter("/food ")))
    }

    @Test
    fun aFoodNoLongerInTheCatalogueIsTypedOverInADialogThatSaysSo() {
        showToday()
        tap(hasText("Muesli bar") and hasClickAction())

        // The dialog says why, and starts as the entry reads: the amount and the name, its numbers.
        waitFor("Muesli bar is no longer among your foods")
        val kcal = hasText("Calories", substring = true) and hasSetTextAction() and hasAnyAncestor(isDialog())
        compose.onNode(kcal).assertExists()
        compose.onNode(hasText("1 bar Muesli bar") and hasSetTextAction()).assertExists()
        compose.onNode(kcal).performTextReplacement("150")
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog())).performClick()

        compose.waitUntil(timeoutMillis = 10_000) { changes().any { it.startsWith("PUT /api/log/") } }
        // `PUT /api/log/{id} {body}`: the entry typed over, no longer a food.
        val put = changes().single { it.startsWith("PUT /api/log/") }
        assertEquals(LogTextInput("1 bar Muesli bar", 150.0, 2.5), Json.decodeFromString(put.substringAfter("PUT ").substringAfter(" ")))
        // Back on Today as a typed entry, with the typed numbers.
        waitFor("150 kcal · 3 g protein")
    }
}
