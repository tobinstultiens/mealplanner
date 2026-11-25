package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.tobins.mealplanner.api.ShopsSetting
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
 * "Not this ingredient" (issue 316) as it is used: on a deal of the shopping list, on an offer of the Offers screen and in
 * the list of Settings, each one tap that the server hears of at once and that Undo takes back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class NotThisIngredientScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend()
    private val api = ApiClient(backend.settings)
    private val storage = MemorySettingsStorage()

    /** The fake's week, whose offers run until Sunday. */
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

    private fun show(content: @Composable (ShoppingSession) -> Unit) = compose.setContent {
        val scope = rememberCoroutineScope()
        val session = remember { ShoppingSession(scope, api.shoppingServer(), ShoppingCache(storage)) }
        CompositionLocalProvider(LocalStrings provides Strings.English) { content(session) }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = compose.waitUntil(timeoutMillis = 10_000) { shown(text) }

    private fun waitUntilGone(text: String) = compose.waitUntil(timeoutMillis = 10_000) { !shown(text) }

    private fun waitForChange(change: String) = compose.waitUntil(timeoutMillis = 10_000) { change in backend.changes }

    @Test
    fun aDealOfTheShoppingListLeavesItsRowAtOnceAndUndoBringsItBack() {
        show { session -> ShoppingScreen(session, storage, remember { AccountShops(storage, { ShopsSetting() }, { it }) }, {}, {}) }
        waitFor("AH €2.24")

        compose.onNodeWithText("AH €2.24").performClick()
        waitFor("Not this ingredient")
        compose.onNodeWithText("Not this ingredient").performClick()

        waitFor("“Rode paprika 500 g” at AH no longer counts as Red bell pepper")
        waitForChange("PUT /api/offers/exclusions AH Rode paprika 500 g 9")
        // The fake server still sends the deal: the app keeps it off by the list the server answered with.
        waitUntilGone("AH €2.24")

        compose.onNodeWithText("Undo").performClick()
        waitForChange("DELETE /api/offers/exclusions AH Rode paprika 500 g 9")
        waitFor("AH €2.24")
    }

    @Test
    fun anOfferOfTheOffersScreenOpensToNotThisIngredientAndLeavesMyIngredients() {
        show { session -> OffersScreen(api, session.exclusions, {}, {}) }
        waitFor("Kipdijfilet 600 g")

        compose.onNodeWithText("Kipdijfilet 600 g").performClick()
        waitFor("Not this ingredient")
        compose.onNodeWithText("Not this ingredient").performClick()

        waitForChange("PUT /api/offers/exclusions JUMBO Kipdijfilet 600 g 1")
        waitFor("no longer counts as Chicken thigh fillet")
        // Matched to nothing now, so not among "My ingredients", which counts one fewer.
        waitFor("My ingredients (2)")

        compose.onNodeWithText("Undo").performClick()
        waitForChange("DELETE /api/offers/exclusions JUMBO Kipdijfilet 600 g 1")
        waitFor("My ingredients (3)")
    }

    @Test
    fun theListInSettingsTakesOneBackAndUndoMakesItAgain() {
        show { session ->
            val snackbar = remember { SnackbarHostState() }
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                Column(Modifier.padding(padding)) { ExcludedOffers(session.exclusions, snackbar) }
            }
        }
        waitFor("Jumbo · not counted as Curry powder")
        waitFor("AH · not counted as Beef steak")

        compose.onAllNodesWithText("Take back").onFirst().performClick()

        waitFor("“Hela curry” at Jumbo counts as Curry powder again")
        waitForChange("DELETE /api/offers/exclusions JUMBO Hela curry 10")
        waitUntilGone("Jumbo · not counted as Curry powder")
        assertEquals(listOf("AH Excellent hertenbiefstuk"), backend.exclusions.map { it.title })

        compose.onNodeWithText("Undo").performClick()
        waitForChange("PUT /api/offers/exclusions JUMBO Hela curry 10")
        waitFor("Jumbo · not counted as Curry powder")
    }
}
