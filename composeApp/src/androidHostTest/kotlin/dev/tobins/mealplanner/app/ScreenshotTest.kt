package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One way of looking at a screen: light or dark, how wide the phone is, and the font scale in the settings. */
data class Look(val dark: Boolean, val widthDp: Int, val fontScale: Float) {
    val name get() = "${if (dark) "dark" else "light"}-${widthDp}dp-x$fontScale"
}

@Composable
private fun shopping(api: ApiClient, shoppingMode: Boolean) {
    val storage = remember { MemorySettingsStorage().also { it.saveShoppingMode(shoppingMode) } }
    ShoppingScreen(api, ShoppingCache(storage), storage, {}, {})
}

/** The whole app as it starts for someone who is logged in: the bottom bar lives here, not in a screen. */
@Composable
private fun shell(settings: ServerSettings) {
    val storage = remember {
        MemorySettingsStorage().also {
            it.saveSession(Session("token", expiresAt = Long.MAX_VALUE))
            it.saveAppLanguage(AppLanguage.ENGLISH)
        }
    }
    val launcher = remember {
        object : LoginLauncher {
            override fun redirectUri(serverUrl: String) = serverUrl

            override fun open(url: String) = Unit
        }
    }
    App(settings, storage, launcher, emptyFlow())
}

/**
 * A screen under test and the sample data it gets. [heightDp] is the height of the picture: a
 * screen that scrolls gets a tall one, so what is lower down is checked too. The `empty` flags
 * leave that list of the fake backend empty.
 */
enum class Shot(
    val emptyWeek: Boolean = false,
    val emptyOffers: Boolean = false,
    val emptyPrices: Boolean = false,
    val emptyRecipes: Boolean = false,
    val heightDp: Int = 640,
    val content: @Composable (ApiClient, ServerSettings) -> Unit,
) {
    TODAY(heightDp = 1300, content = { api, _ -> TodayScreen(api, {}, {}) }),
    PLAN(heightDp = 4400, content = { api, _ -> PlanScreen(api, {}, {}) }),
    SHOPPING(heightDp = 1300, content = { api, _ -> shopping(api, shoppingMode = false) }),
    SHOPPING_MODE(heightDp = 1300, content = { api, _ -> shopping(api, shoppingMode = true) }),
    SHOPPING_EMPTY(emptyWeek = true, content = { api, _ -> shopping(api, shoppingMode = false) }),
    RECIPES(heightDp = 1100, content = { api, _ -> RecipeListScreen(api, {}, {}, {}) }),
    RECIPES_EMPTY(emptyRecipes = true, content = { api, _ -> RecipeListScreen(api, {}, {}, {}) }),
    RECIPE_DETAIL(heightDp = 1700, content = { api, _ -> RecipeDetailScreen(api, 4, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}) }),
    OFFERS(heightDp = 800, content = { api, _ -> OffersScreen(api, {}, {}) }),
    OFFERS_EMPTY(emptyOffers = true, content = { api, _ -> OffersScreen(api, {}, {}) }),
    PRICES(heightDp = 800, content = { api, _ -> PricesScreen(api, {}) }),
    PRICES_EMPTY(emptyPrices = true, content = { api, _ -> PricesScreen(api, {}) }),
    PROGRESS(heightDp = 1800, content = { api, _ -> ProgressScreen(api, {}, {}) }),
    SHELL(content = { _, settings -> shell(settings) }),
    SETTINGS(heightDp = 900, content = { _, _ ->
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
            language = AppLanguage.ENGLISH,
            onLanguage = {},
            systemColors = false,
            onSystemColors = {},
            productPictures = true,
            onProductPictures = {},
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

    private val backend = FakeBackend(shot.emptyWeek, shot.emptyOffers, shot.emptyPrices, shot.emptyRecipes)
    private val api = ApiClient(backend.settings)

    @Before
    fun fixTimeAndPlace() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        appClock = object : Clock {
            override fun now(): Instant = Instant.parse("${FakeBackend.TODAY}T10:30:00Z")
        }
        // The phone's size, as tall as the picture. mdpi makes one dp one pixel, which keeps the pictures small.
        // Night mode is set too, for the shell, which follows the device when nothing else is chosen.
        RuntimeEnvironment.setQualifiers("en-rGB-w${look.widthDp}dp-h${shot.heightDp}dp-${if (look.dark) "night" else "notnight"}-mdpi")
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
        // The language is pinned to English, whatever the machine's locale is.
        // Pictures come from a fake loader: a screenshot must never wait for, or depend on, the stores' servers.
        val pictures = remember { fakeImageLoader(RuntimeEnvironment.getApplication(), failing = { "feta" in it }) }
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, look.fontScale),
            LocalStrings provides Strings.English,
            LocalProductImageLoader provides pictures,
        ) {
            MealPlannerTheme(look.dark) {
                Surface(Modifier.testTag(FRAME).size(look.widthDp.dp, shot.heightDp.dp)) { content() }
            }
        }
    }

    companion object {
        private const val FRAME = "frame"

        /** Pictures at half the size keep the references in the repository small; any changed pixel counts.
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
