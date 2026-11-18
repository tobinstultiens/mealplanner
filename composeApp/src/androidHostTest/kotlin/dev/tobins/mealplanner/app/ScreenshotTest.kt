package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.requestFocus
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
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
import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.IngredientSection
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.Store
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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One way of looking at a screen: light or dark, how wide the phone is, and the font scale in the settings. */
data class Look(val dark: Boolean, val widthDp: Int, val fontScale: Float) {
    val name get() = "${if (dark) "dark" else "light"}-${widthDp}dp-x$fontScale"
}

@Composable
private fun shopping(api: ApiClient, shoppingMode: Boolean, view: ShoppingView = ShoppingView.SECTION, store: Store? = null, shops: Set<Store>? = null) {
    val storage = remember {
        MemorySettingsStorage().also {
            it.saveShoppingMode(shoppingMode)
            it.saveShoppingView(view)
            it.saveShoppingStore(store)
        }
    }
    val scope = rememberCoroutineScope()
    val session = remember { ShoppingSession(scope, api.shoppingServer(), ShoppingCache(storage)) }
    ShoppingScreen(session, storage, remember { AccountShops(storage, { shops?.let { ShopsSetting(it.toList()) } ?: api.shops() }, { it }) }, {}, {})
}

/** A saved profile with a portion range other than the defaults, for the profile screen. */
private val sampleProfile = Profile(
    82.5, 180.0, 40, Sex.MALE, ActivityLevel.LIGHT, likes = listOf("Korean", "spicy"), dislikes = listOf("mushroom"),
    minPortions = 1.0, maxPortions = 1.75,
)

/** Controls of three kinds; the test gives the one tagged [FOCUSED] keyboard focus, to see the ring on each. */
/** The recipe picker's body for a dinner, over the fake backend's recipes: a dialog is a window the picture does not include. */
@Composable
private fun recipePicker(api: ApiClient) {
    val recipes by produceState<List<RecipeSummary>?>(null) { value = api.recipes() }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(LocalStrings.current(Msg.PLAN_CHOOSE_FOR, "dinner", "Friday"), style = MaterialTheme.typography.headlineSmall)
        // Two recipes of each group: the dialog lists 480 dp at most, and both headings are to be seen.
        recipes?.filter { it.id in setOf(2L, 3L, 5L, 6L) }?.let { RecipePickerBody(it, MealSlot.DINNER, onBrowseBank = {}, onPick = {}) }
    }
}

/** The cooking-site dialog's body on a server that offers its site, with [choice] made and [url] typed. */
@Composable
private fun cookingSiteChoices(choice: SiteChoice, url: String) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(LocalStrings.current(Msg.COOKING_SITE), style = MaterialTheme.typography.headlineSmall)
        CookingSiteChoices(true, "https://github.com/example/cooking.git", choice, {}, url, {}, error = null)
    }
}

/** The snackbar of a recipe added with ingredients to link, shown the way [announceAdded] shows it. */
@Composable
private fun addedSnackbar() {
    val t = LocalStrings.current
    val host = remember { SnackbarHostState() }
    LaunchedEffect(host) {
        host.announceAdded(t, t(Msg.ADDED_ONE, "Asperge soup"), unlinked = 1, onLink = {}, onUndo = {})
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { AppSnackbarHost(host) }
}

@Composable
private fun focusRing(focused: Int) {
    // Controls only take focus when the last input was the keyboard, as when someone tabs through the screen.
    @OptIn(ExperimentalComposeUiApi::class)
    LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
    fun Modifier.tagged(index: Int) = if (index == focused) testTag(FOCUSED) else this
    Column(Modifier.fillMaxSize().focusRing().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = {}, Modifier.tagged(0)) { Text("Save") }
        OutlinedButton(onClick = {}, Modifier.tagged(1)) { Text("Cancel") }
        Row { IconButton(onClick = {}, Modifier.tagged(2)) { Icon(AppIcons.Edit, contentDescription = "Edit") } }
    }
}

private const val FOCUSED = "focused"

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
    val scope = rememberCoroutineScope()
    App(settings, storage, launcher, remember { LoginExchange(storage, scope) })
}

/**
 * A screen under test and the sample data it gets. [heightDp] is the height of the picture: a
 * screen that scrolls gets a tall one, so what is lower down is checked too. The `empty` flags
 * leave that list of the fake backend empty; [profile] is the one the fake backend holds.
 */
enum class Shot(
    val emptyWeek: Boolean = false,
    val emptyOffers: Boolean = false,
    val emptyPrices: Boolean = false,
    val emptyRecipes: Boolean = false,
    /** The screen in Dutch (the others are English), for a screen whose texts are longer there. */
    val dutch: Boolean = false,
    val heightDp: Int = 640,
    val profile: Profile? = null,
    val content: @Composable (ApiClient, ServerSettings) -> Unit,
) {
    TODAY(heightDp = 1300, content = { api, _ -> TodayScreen(api, { _, _ -> }, {}) }),
    PLAN(heightDp = 4400, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    // No recipe of mine is in the rotation, so the week stays empty and says why, with a way to the Recipes tab.
    PLAN_EMPTY_ROTATION(emptyWeek = true, emptyRecipes = true, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    RECIPE_PICKER(heightDp = 1300, content = { api, _ -> recipePicker(api) }),
    SHOPPING(heightDp = 2200, content = { api, _ -> shopping(api, shoppingMode = false) }),
    SHOPPING_MODE(heightDp = 2200, content = { api, _ -> shopping(api, shoppingMode = true) }),
    SHOPPING_WITHOUT_JUMBO(heightDp = 2200, content = { api, _ -> shopping(api, shoppingMode = false, shops = setOf(Store.AH, Store.LIDL)) }),
    SHOPPING_EMPTY(emptyWeek = true, content = { api, _ -> shopping(api, shoppingMode = false) }),
    SHOPPING_STORES(heightDp = 2200, content = { api, _ -> shopping(api, shoppingMode = false, view = ShoppingView.STORE) }),
    SHOPPING_AT_JUMBO(heightDp = 1500, content = { api, _ -> shopping(api, shoppingMode = false, view = ShoppingView.STORE, store = Store.JUMBO) }),
    RECIPES(heightDp = 1100, content = { api, _ -> RecipeListScreen(api, {}, {}, {}, {}) }),
    RECIPES_ROTATION(heightDp = 1100, content = { api, _ -> RecipeListScreen(api, {}, {}, {}, {}, initialChoice = RotationChoice.IN_ROTATION) }),
    RECIPES_NOT_IN_ROTATION(heightDp = 800, content = { api, _ -> RecipeListScreen(api, {}, {}, {}, {}, initialChoice = RotationChoice.OUT_OF_ROTATION) }),
    // Two recipes ticked: the bar has the count, "Select all" and the three buttons.
    RECIPES_SELECTING(heightDp = 1100, content = { api, _ -> RecipeListScreen(api, {}, {}, {}, {}, initiallySelected = setOf(3L, 6L)) }),
    // In Dutch at 360 dp with the larger font, where the choices and the bar's buttons are longest (#190).
    RECIPES_NL(dutch = true, heightDp = 1100, content = { api, _ -> RecipeListScreen(api, {}, {}, {}, {}) }),
    RECIPES_SELECTING_NL(dutch = true, heightDp = 1100, content = { api, _ -> RecipeListScreen(api, {}, {}, {}, {}, initiallySelected = setOf(3L, 6L)) }),
    BANK(heightDp = 1100, content = { api, _ -> RecipeBankScreen(api, {}, {}, {}) }),
    BANK_NL(dutch = true, heightDp = 1100, content = { api, _ -> RecipeBankScreen(api, {}, {}, {}) }),
    // A short screen, as in landscape, in Dutch with the larger font: the header scrolls away with the rows and the sections slide sideways.
    BANK_SHORT_NL(dutch = true, heightDp = 360, content = { api, _ -> RecipeBankScreen(api, {}, {}, {}) }),
    // A recipe of the bank opened: read-only, with the way to add it and the planner's choice under it.
    BANK_RECIPE(heightDp = 1700, content = { api, _ -> RecipeDetailScreen(api, 9, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}) }),
    // One of my recipes outside the rotation, and one whose file has left the cooking site.
    RECIPE_NOT_IN_ROTATION(heightDp = 1700, content = { api, _ -> RecipeDetailScreen(api, 3, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}) }),
    RECIPE_REMOVED_FROM_SITE(heightDp = 1700, content = { api, _ -> RecipeDetailScreen(api, 6, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}) }),
    // The list was loaded, then the server could not be reached on a refresh: the list stays, with the line and its Retry below.
    RECIPES_REFRESH_FAILED(heightDp = 1100, content = { api, _ -> recipesRefreshFailed(api) }),
    RECIPES_EMPTY(emptyRecipes = true, content = { api, _ -> RecipeListScreen(api, {}, {}, {}, {}) }),
    RECIPE_DETAIL(heightDp = 1700, content = { api, _ -> RecipeDetailScreen(api, 4, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}) }),
    // Opened from a planned meal: the amounts of 1.5 of the recipe's 2 servings, and nothing to cook for leftovers.
    RECIPE_FROM_MEAL(heightDp = 1700, content = { api, _ ->
        RecipeDetailScreen(api, 4, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}, meal = MealToCook(4, 0.75, 1.5, skipped = false))
    }),
    RECIPE_FROM_LEFTOVERS(heightDp = 1700, content = { api, _ ->
        RecipeDetailScreen(api, 4, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}, meal = MealToCook(4, 0.75, 0.0, skipped = false))
    }),
    // Cook mode of a planned meal, the way it opens: scaled amounts, the first step current.
    COOK_MODE(heightDp = 1400, content = { api, _ ->
        RecipeDetailScreen(api, 4, null, {}, {}, meal = MealToCook(4, 0.75, 1.5, skipped = false), initiallyCooking = true)
    }),
    COOK_MODE_NL(dutch = true, heightDp = 1400, content = { api, _ ->
        RecipeDetailScreen(api, 4, null, {}, {}, meal = MealToCook(4, 0.75, 1.5, skipped = false), initiallyCooking = true)
    }),
    // Two ingredients and the first step ticked, so that the dimmed rows and the moved highlight are to be seen.
    COOK_MODE_TICKED(heightDp = 1400, content = { api, _ -> cookModeTicked(api, setOf(0, 1), setOf(0), eatable = false) }),
    // Every step done on a planned meal: the button that marks it eaten.
    COOK_MODE_DONE(heightDp = 1400, content = { api, _ -> cookModeTicked(api, setOf(0, 1, 2, 3, 4), setOf(0, 1, 2), eatable = true) }),
    OFFERS(heightDp = 800,content = { api, _ -> OffersScreen(api, {}, {}) }),
    OFFERS_EMPTY(emptyOffers = true, content = { api, _ -> OffersScreen(api, {}, {}) }),
    PRICES(heightDp = 800, content = { api, _ -> PricesScreen(api, {}) }),
    PRICES_EMPTY(emptyPrices = true, content = { api, _ -> PricesScreen(api, {}) }),
    PROGRESS(heightDp = 1800, content = { api, _ -> ProgressScreen(api, {}, {}) }),
    // The profile with a portion range of its own, in both languages: the range's fields side by side.
    PROFILE(heightDp = 1800, profile = sampleProfile, content = { api, _ -> ProfileScreen(api, {}) }),
    PROFILE_NL(dutch = true, heightDp = 1800, profile = sampleProfile, content = { api, _ -> ProfileScreen(api, {}) }),
    // Going back from the profile or the recipe editor with unsaved changes: the dialog's body, as a dialog is a window of its own.
    DISCARD_QUESTION(heightDp = 260, content = { _, _ -> DiscardQuestion({}, {}) }),
    DISCARD_QUESTION_NL(dutch = true, heightDp = 260, content = { _, _ -> DiscardQuestion({}, {}) }),
    // A screen's load error: the plain sentence, with the exception's own text behind "Details".
    LOAD_FAILED(content = { _, _ ->
        ErrorMessage(
            LocalStrings.current(Msg.WEIGHT_LOAD_FAILED),
            Load.Failed("unexpected end of stream on http://192.168.1.2:8080/api/weights", technical = true, lostConnection = true),
        ) {}
    }),
    PANTRY(heightDp = 760, content = { api, _ -> PantryScreen(api, {}) }),
    FOCUS_FILLED_BUTTON(heightDp = 200, content = { _, _ -> focusRing(0) }),
    FOCUS_OUTLINED_BUTTON(heightDp = 200, content = { _, _ -> focusRing(1) }),
    FOCUS_ICON_BUTTON(heightDp = 200, content = { _, _ -> focusRing(2) }),
    // What adding a recipe with an ingredient still to link says: two actions, in a snackbar.
    SNACKBAR_LINK(heightDp = 200, content = { _, _ -> addedSnackbar() }),
    SNACKBAR_LINK_NL(dutch = true, heightDp = 200, content = { _, _ -> addedSnackbar() }),
    SHELL(content = { _, settings -> shell(settings) }),
    // The dialog's body: a dialog is a window of its own that the picture of the frame does not include.
    UPDATE_NOTES(content = { _, _ ->
        val update = AppUpdate(
            "0.2.0",
            "- Shopping mode: a list made for use in the shop\n- Dutch interface, with a language setting\n- Recipe photos from the cooking site\n- Swap never gives the same recipe again",
            "", "",
        )
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(LocalStrings.current(Msg.UPDATE_AVAILABLE, update.version), style = MaterialTheme.typography.headlineSmall)
            UpdateBody(update, "0.1.1")
        }
    }),
    // The cooking-site dialog's body, for the same reason: a repository of one's own being typed, and in Dutch the server's site chosen.
    COOKING_SITE_CHOICES(heightDp = 700, content = { _, _ -> cookingSiteChoices(SiteChoice.OWN, "https://github.com/anna/koken.git") }),
    COOKING_SITE_CHOICES_NL(dutch = true, heightDp = 700, content = { _, _ -> cookingSiteChoices(SiteChoice.SERVER, "") }),
    // The correction dialog of an ingredient's section and "keeps": its body, for the same reason.
    SECTION_CHOICES(heightDp = 1050, content = { _, _ ->
        val entry = IngredientSection(7, "Minced beef", "MEAT", "MEAT", "Albert Heijn: Rundergehakt", byHand = false, keeps = true, keepsAutomatic = true)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(LocalStrings.current(Msg.SECTION_FOR, entry.name), style = MaterialTheme.typography.headlineSmall)
            SectionChoices(entry, choice = null, onChoice = {}, keeps = true, onKeeps = {})
        }
    }),
    // Logged out after the provider would not renew the session, with the server built in.
    LOGIN(heightDp = 760, content = { _, _ ->
        LoginScreen(ServerSettings("https://mealplanner.example.nl"), serverKnown = true, LoggedOut.EXPIRED, error = null, busy = false, {}, {})
    }),
    // No server known yet (a debug build): the address is asked for; the last attempt could not reach it.
    LOGIN_ASKS_SERVER(heightDp = 860, content = { _, _ ->
        LoginScreen(
            ServerSettings("http://10.0.2.2:8080"), serverKnown = false, reason = null,
            error = "Could not start the login: Connection refused", busy = false, onLogin = {}, onChangeServer = {},
        )
    }),
    SETTINGS(heightDp = 1350, content = { _, _ ->
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
            accountShops = AccountShops(MemorySettingsStorage().also { it.saveShops(setOf(Store.AH, Store.LIDL)) }, { ShopsSetting() }, { it }),
            loadShoppingAdvice = { ShoppingAdvice(stockUp = false) },
            saveShoppingAdvice = { it },
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

    private val backend = FakeBackend(shot.emptyWeek, shot.emptyOffers, shot.emptyPrices, shot.emptyRecipes).also { it.profile = shot.profile }
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
            compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(if (shot.dutch) "Laden…" else "Loading…").fetchSemanticsNodes().isEmpty() }
            // Some screens load a second thing after the first; give it a moment, then let the UI settle.
            Thread.sleep(300)
            compose.waitForIdle()
            if (shot.name.startsWith("FOCUS")) {
                compose.onNodeWithTag(FOCUSED).requestFocus().assertIsFocused()
                compose.waitForIdle()
            }
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
            LocalStrings provides if (shot.dutch) Strings.Dutch else Strings.English,
            LocalProductImageLoader provides pictures,
            LocalRecipeImages provides RecipeImages(pictures, "http://fake", { null }),
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

/** Cook mode of the fake backend's recipe 4 with [ingredients] and [steps] ticked, as it is while cooking; [eatable] for a meal still planned. */
@Composable
private fun cookModeTicked(api: ApiClient, ingredients: Set<Int>, steps: Set<Int>, eatable: Boolean) {
    val recipe by produceState<RecipeDetail?>(null) { value = api.recipe(4) }
    // The test waits for the loading text to go, so it stays until the recipe is there.
    recipe?.let { CookMode(it, 1.5 / 2, CookProgress(ingredients, steps), {}, onMarkEaten = if (eatable) ({}) else null) } ?: LoadingState()
}

/** The recipe list as loaded from [api], then shown with a client that cannot reach the server for its refresh. */
@Composable
private fun recipesRefreshFailed(api: ApiClient) {
    val memory = remember { ScreenMemory().apply { keep("recipes", runBlocking { api.recipes() }) } }
    val unreachable = remember {
        ApiClient(ServerSettings("http://localhost"), engine = MockEngine { respond("""{"error":"down"}""", HttpStatusCode.InternalServerError) })
    }
    CompositionLocalProvider(LocalScreenMemory provides memory) { RecipeListScreen(unreachable, {}, {}, {}, {}) }
}
