package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One way of looking at a screen: light or dark, how wide the phone is, and the font scale in the settings. */
data class Look(val dark: Boolean, val widthDp: Int, val fontScale: Float) {
    val name get() = "${if (dark) "dark" else "light"}-${widthDp}dp-x$fontScale"
}

/** A screen under test and the sample data it gets. */
enum class Shot(val emptyWeek: Boolean = false, val content: @Composable (ApiClient, ServerSettings) -> Unit) {
    TODAY(content = { api, _ -> TodayScreen(api, {}, {}) }),
    PLAN(content = { api, _ -> PlanScreen(api, {}, {}) }),
    SHOPPING(content = { api, _ -> ShoppingScreen(api, ShoppingCache(MemorySettingsStorage()), {}, {}) }),
    SHOPPING_EMPTY(emptyWeek = true, content = { api, _ -> ShoppingScreen(api, ShoppingCache(MemorySettingsStorage()), {}, {}) }),
    RECIPES(content = { api, _ -> RecipeListScreen(api, {}, {}, {}) }),
    RECIPE_DETAIL(content = { api, _ -> RecipeDetailScreen(api, 4, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}) }),
    OFFERS(content = { api, _ -> OffersScreen(api, {}, {}) }),
    PRICES(content = { api, _ -> PricesScreen(api, {}) }),
    PROGRESS(content = { api, _ -> ProgressScreen(api, {}, {}) }),
    SETTINGS(content = { _, _ ->
        ServerSettingsScreen(
            current = ServerSettings("https://mealplanner.example.nl"),
            account = "Anna",
            loggedIn = true,
            loginError = null,
            onLogin = {},
            onLogout = {},
            updater = null,
            theme = ThemeMode.SYSTEM,
            onTheme = {},
            onSave = {},
            onBack = {},
        )
    }),
}

/**
 * Renders every screen with fixed sample data on the JVM and compares the picture with the
 * one in `src/androidHostTest/screenshots`. Run through `recordScreenshots` and
 * `verifyScreenshots` (see the README); the normal `allTests` leaves these out.
 *
 * Two looks per screen keep the job short and the pictures small: the narrowest phone with
 * the largest font in light mode, where text is most likely to be cut off, and the usual
 * width with the normal font in dark mode, where colours are most likely to be wrong.
 */
@OptIn(ExperimentalTime::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest(private val shot: Shot, private val look: Look) {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val backend = FakeBackend(shot.emptyWeek)
    private val api = ApiClient(backend.settings)

    @Before
    fun fixTimeAndPlace() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        appClock = object : Clock {
            override fun now(): Instant = Instant.parse("${FakeBackend.TODAY}T10:30:00Z")
        }
        // The phone's width; the height only has to be generous. mdpi makes one dp one pixel, which keeps the pictures small.
        RuntimeEnvironment.setQualifiers("en-rGB-w${look.widthDp}dp-h640dp-mdpi")
    }

    @After
    fun tearDown() {
        api.close()
        backend.close()
        appClock = Clock.System
    }

    @Test
    fun screenLooksAsRecorded() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { Frame { shot.content(api, backend.settings) } }
            }
            compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Loading…").fetchSemanticsNodes().isEmpty() }
            // Some screens load a second thing after the first; give it a moment, then let the UI settle.
            Thread.sleep(300)
            compose.waitForIdle()
            compose.onNodeWithTag(FRAME).captureRoboImage(
                filePath = "src/androidHostTest/screenshots/${shot.name.lowercase()}_${look.name}.png",
                roborazziOptions = options(),
            )
        }
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, look.fontScale)) {
            MealPlannerTheme(look.dark) {
                Surface(Modifier.testTag(FRAME).size(look.widthDp.dp, 640.dp)) { content() }
            }
        }
    }

    companion object {
        private const val FRAME = "frame"

        /** Pictures at three quarters of the size keep the references in the repository small; any changed pixel counts.
         * Made when used: Roborazzi reads Robolectric's state when it is created. */
        private fun options() = RoborazziOptions(
            recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        )

        @JvmStatic
        @Parameters(name = "{0} {1}")
        fun cases(): List<Array<Any>> = Shot.entries.flatMap { shot ->
            listOf(Look(dark = false, widthDp = 360, fontScale = 1.3f), Look(dark = true, widthDp = 411, fontScale = 1.0f))
                .map { arrayOf<Any>(shot, it) }
        }
    }
}
