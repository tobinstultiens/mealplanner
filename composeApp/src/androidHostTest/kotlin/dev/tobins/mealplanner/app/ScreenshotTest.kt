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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
import dev.tobins.mealplanner.api.BatchToMake
import dev.tobins.mealplanner.api.IngredientSection
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PrepMeal
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.PlanPriority
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.StandardMeal
import dev.tobins.mealplanner.api.StandardSwitch
import dev.tobins.mealplanner.api.Store
import kotlinx.coroutines.flow.MutableStateFlow
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

/** A saved profile with a portion range, a priority and a fibre target other than the defaults, for the profile screen. */
private val sampleProfile = Profile(
    82.5, 180.0, 40, Sex.MALE, ActivityLevel.LIGHT, likes = listOf("Korean", "spicy"), dislikes = listOf("mushroom"),
    minPortions = 1.0, maxPortions = 1.75, priority = PlanPriority.FIBRE, fibreOverrideG = 35,
)

/** Controls of three kinds; the test gives the one tagged [FOCUSED] keyboard focus, to see the ring on each. */
/** The recipe picker's body for a dinner, over the fake backend's recipes: a dialog is a window the picture does not include. */
/** Monday's dinner of the fake week, which "Another recipe" suggests others for (issue 255). */
private val sampleDinner = PlannedMeal(0, MealSlot.DINNER, 4, "Slow-cooked chicken thighs with coconut, lemon and spinach", 1.0, 810.0, 55.0)

/** The chicken thighs as the Prep dialog offers them: three days in a box, six vacuum sealed. */
private val keepingRecipe = RecipeSummary(
    4, "Slow-cooked chicken thighs with coconut, lemon and spinach", "Dinner", emptyList(), 2, RecipeSource.COOKING_SITE, RecipeKind.MEAL,
    plannable = true, keepsDays = 3, keepsVacuumDays = 6,
)

/** A prep being made today (Wednesday) at dinner, eaten on Thursday's and Friday's lunches and Saturday's dinner. */
private val samplePrep = PrepChoice(keepingRecipe, FakeBackend.TODAY, firstDay = FakeBackend.TODAY).let { choice ->
    listOf(0 to MealSlot.DINNER, 1 to MealSlot.LUNCH, 2 to MealSlot.LUNCH, 3 to MealSlot.DINNER)
        .fold(choice) { prep, (day, slot) -> prep.toggled(plusDays(FakeBackend.TODAY, day), slot) }
}

@Composable
private fun prepChoices() {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(LocalStrings.current(Msg.PREP_TITLE), style = MaterialTheme.typography.headlineSmall)
        // The days from today on are offered: Monday and Tuesday have passed.
        PrepChoices(samplePrep, prepDays(FakeBackend.WEEK_START, FakeBackend.TODAY), listOf(keepingRecipe), hosts = emptyMap()) {}
    }
}

/**
 * The same recipe eaten as a side of half a serving, next to today's dinner and Friday's lunch: today's lunch was eaten,
 * Thursday's dinner is skipped and Friday's has a side already, so none of those can be picked.
 */
@Composable
private fun prepSideChoices() {
    val hosts = listOf(0 to MealSlot.DINNER, 1 to MealSlot.LUNCH, 2 to MealSlot.LUNCH, 3 to MealSlot.LUNCH, 3 to MealSlot.DINNER)
        .associate { (day, slot) -> PrepSlot(plusDays(FakeBackend.TODAY, day), slot) to 5L }
    val choice = PrepChoice(keepingRecipe, FakeBackend.TODAY, firstDay = FakeBackend.TODAY)
        .eatenAs(0.5).toggled(FakeBackend.TODAY, MealSlot.DINNER).toggled(plusDays(FakeBackend.TODAY, 2), MealSlot.LUNCH)
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(LocalStrings.current(Msg.PREP_TITLE), style = MaterialTheme.typography.headlineSmall)
        PrepChoices(choice, prepDays(FakeBackend.WEEK_START, FakeBackend.TODAY), listOf(keepingRecipe), hosts) {}
    }
}

/**
 * One batch eaten both ways (issue 269): today's and Saturday's dinner as the meal, a quarter of a serving next to Thursday's
 * lunch and half a serving next to Friday's; half a serving is chosen for the next one picked, so only the meals a side can
 * go next to (and those picked already) can be tapped.
 */
@Composable
private fun prepMixedChoices() {
    val hosts = listOf(1 to MealSlot.LUNCH, 1 to MealSlot.DINNER, 2 to MealSlot.LUNCH, 3 to MealSlot.LUNCH)
        .associate { (day, slot) -> PrepSlot(plusDays(FakeBackend.TODAY, day), slot) to 5L }
    val choice = PrepChoice(keepingRecipe, FakeBackend.TODAY, firstDay = FakeBackend.TODAY)
        .toggled(FakeBackend.TODAY, MealSlot.DINNER).toggled(plusDays(FakeBackend.TODAY, 3), MealSlot.DINNER)
        .eatenAs(0.25).toggled(plusDays(FakeBackend.TODAY, 1), MealSlot.LUNCH)
        .eatenAs(0.5).toggled(plusDays(FakeBackend.TODAY, 2), MealSlot.LUNCH)
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(LocalStrings.current(Msg.PREP_TITLE), style = MaterialTheme.typography.headlineSmall)
        PrepChoices(choice, prepDays(FakeBackend.WEEK_START, FakeBackend.TODAY), listOf(keepingRecipe), hosts) {}
    }
}

/**
 * "Add a prepped side…" on Friday's dinner: the chicken thighs made today and the chickpea salad made on Monday can still take
 * a side, the first chosen, of half a serving.
 */
@Composable
private fun addSideChoices() {
    val batches = listOf(
        BatchToMake(keepingRecipe.id, keepingRecipe.title, MealPrep(1, FakeBackend.TODAY, plusDays(FakeBackend.TODAY, 3), false, emptyList())),
        BatchToMake(2, "Chickpea salad with feta and roasted peppers", MealPrep(2, FakeBackend.WEEK_START, plusDays(FakeBackend.WEEK_START, 4), false, emptyList())),
    )
    val slot = PrepSlot(plusDays(FakeBackend.TODAY, 2), MealSlot.DINNER)
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(LocalStrings.current(Msg.ADD_SIDE_TITLE, slotLabel(slot, LocalStrings.current)), style = MaterialTheme.typography.headlineSmall)
        AddSideChoices(batches, batches.first(), 0.5, onBatch = {}, onPortions = {}, onNewPrep = {})
    }
}

/** Wednesday's skipped lunch of a prep that keeps until today, with today's dinner free for it. */
@Composable
private fun spareOnItsLastDay() {
    val spare = SparePortion(
        1, 4, keepingRecipe.title, 1.0, PrepSlot(plusDays(FakeBackend.TODAY, -1), MealSlot.LUNCH), lastDay = FakeBackend.TODAY,
        room = listOf(PrepSlot(FakeBackend.TODAY, MealSlot.DINNER)), proposed = PrepSlot(FakeBackend.TODAY, MealSlot.DINNER),
    )
    // Today's dinner is empty and the only meal with room, so the card proposes it and has no other.
    Column(Modifier.padding(16.dp)) { SpareCard(spare, FakeBackend.TODAY, enabled = true) {} }
}

/**
 * The question that removes [samplePrep], whose Friday lunch is skipped already; once [made] (today's dinner eaten), what
 * the dialog says instead, of a batch eaten as the meals, as [sides], or [mixed]: Thursday's and Friday's lunches as sides.
 */
@Composable
private fun removePrepQuestion(made: Boolean = false, sides: Boolean = false, mixed: Boolean = false) {
    val prep = MealPrep(
        1, FakeBackend.TODAY, plusDays(FakeBackend.TODAY, 3), false,
        samplePrep.input().meals.mapIndexed { i, meal ->
            val status = when {
                made && i == 0 -> MealStatus.EATEN
                i == 2 -> MealStatus.SKIPPED
                else -> MealStatus.PLANNED
            }
            PrepMeal(meal.date, meal.slot, 1.0, status, side = sides || (mixed && meal.slot == MealSlot.LUNCH))
        },
    )
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(LocalStrings.current(if (made) Msg.REMOVE_PREP_MADE_TITLE else Msg.REMOVE_PREP_TITLE, keepingRecipe.title), style = MaterialTheme.typography.headlineSmall)
        RemovePrepText(prep)
    }
}

@Composable
private fun recipePicker(api: ApiClient, standard: Boolean = false) {
    val recipes by produceState<List<RecipeSummary>?>(null) { value = api.recipes() }
    val t = LocalStrings.current
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(t(Msg.PLAN_CHOOSE_FOR, "dinner", "Friday"), style = MaterialTheme.typography.headlineSmall)
        // Two recipes of each group: the dialog lists 480 dp at most, and both headings are to be seen. With [standard] the two out
        // of the rotation are standard meals, first under their own heading, for a later meal of a prep still to make (issue 265).
        recipes?.filter { it.id in setOf(2L, 3L, 5L, 6L) }?.let {
            if (standard) {
                RecipePickerBody(it, MealSlot.DINNER, standard = setOf(3L, 6L), note = t(Msg.PICKER_SHRINKS_PREP), onBrowseBank = {}, onPick = {})
            } else {
                RecipePickerBody(it, MealSlot.DINNER, onBrowseBank = {}, onPick = {})
            }
        }
    }
}

/** Two standard meals for lunch (issue 265), the second with an ingredient that is out. */
private val sampleStandardMeals = listOf(
    StandardMeal(3, "Tuna pasta salad", MealSlot.LUNCH, 1.0, 610.0, 38.0, 4.0),
    StandardMeal(6, "Shakshuka with feta and crusty bread", MealSlot.LUNCH, 1.25, 700.0, 36.0, 6.0, missing = listOf("Eggs", "Feta")),
)

/** The skip sheet's body, as the Plan shows it when a lunch is skipped: a dialog is a window the picture does not include. */
@Composable
private fun skipSheet() {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(LocalStrings.current(Msg.SKIP_SHEET_TITLE, "Chickpea salad with feta and roasted peppers"), style = MaterialTheme.typography.headlineSmall)
        SkipSheetBody(sampleStandardMeals, enabled = true) {}
    }
}

/** A recipe's standard-meal switch just turned on: what it made staples, and a line linked to nothing. */
@Composable
private fun standardSwitch() {
    val marked = StandardSwitch(
        RecipeDetail(keepingRecipe.copy(standardMeal = true), emptyList(), "", emptyList()),
        addedStaples = listOf("Chicken thigh fillet", "Coconut milk", "Spinach"), unlinked = listOf("lemon, juiced"),
    )
    Column(Modifier.padding(16.dp)) { StandardMealRow(standard = true, report = marked, enabled = true) {} }
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
private fun removedWeighIn() {
    val t = LocalStrings.current
    val host = remember { SnackbarHostState() }
    LaunchedEffect(host) { host.showSnackbar(t(Msg.WEIGHING_REMOVED, dayLabel("2026-11-12", t.locale)), actionLabel = t(Msg.UNDO)) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) { KeptWeightNote {} }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { AppSnackbarHost(host) }
    }
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
private fun shell(settings: ServerSettings, language: AppLanguage = AppLanguage.ENGLISH) {
    val storage = remember {
        MemorySettingsStorage().also {
            it.saveSession(Session("token", expiresAt = Long.MAX_VALUE))
            it.saveAppLanguage(language)
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
    /** The fake backend's week with a prep (issue 234). */
    val prepped: Boolean = false,
    /** The fake backend's week with a side prepped next to three meals (issue 234, step 2). */
    val sides: Boolean = false,
    /** The fake backend's week with that batch eaten as a side next to two meals and as the meal at a third (issue 269). */
    val mixed: Boolean = false,
    /** The fake backend's week holding only a lunch of last week's batch (issue 263). */
    val tailOnly: Boolean = false,
    /** The fake backend's week leaving five fresh ingredients over (issue 244). */
    val weekRest: Boolean = false,
    /** The prep of [prepped] kept vacuum sealed (issue 263). */
    val vacuum: Boolean = false,
    /** The fake backend's week with standard meals and today's lunch skipped (issue 265). */
    val standardMeals: Boolean = false,
    /** The fake backend's planning of the week (issue 255): two dinners chosen, a recipe hidden, suggestions on every ground. */
    val planning: Boolean = false,
    /** The screen in Dutch (the others are English), for a screen whose texts are longer there. */
    val dutch: Boolean = false,
    val heightDp: Int = 640,
    val profile: Profile? = null,
    /** A font scale of its own instead of the look's, for a size a screen must survive that the looks do not cover. */
    val fontScale: Float? = null,
    val content: @Composable (ApiClient, ServerSettings) -> Unit,
) {
    TODAY(heightDp = 1300, content = { api, _ -> TodayScreen(api, { _, _ -> }, {}) }),
    PLAN(weekRest = true, heightDp = 4400, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    // The planning screen (issue 255): the dinners left to choose, the strip, the two chosen, six suggestions with their
    // reasons, "Show others", the recipe hidden and "Fill the rest"; in Dutch, and at the largest font in both languages.
    PLANNING(planning = true, heightDp = 2900, content = { api, _ -> PlanningScreen(api, FakeBackend.WEEK_START, {}, {}) }),
    PLANNING_NL(planning = true, dutch = true, heightDp = 3000, content = { api, _ -> PlanningScreen(api, FakeBackend.WEEK_START, {}, {}) }),
    PLANNING_X2(planning = true, heightDp = 5600, fontScale = 2.0f, content = { api, _ -> PlanningScreen(api, FakeBackend.WEEK_START, {}, {}) }),
    PLANNING_X2_NL(planning = true, dutch = true, heightDp = 6000, fontScale = 2.0f, content = { api, _ -> PlanningScreen(api, FakeBackend.WEEK_START, {}, {}) }),
    // "Another recipe" for Monday's dinner: the same suggestions to choose from, and the planner's own choice.
    ANOTHER_DINNER(planning = true, heightDp = 2500, content = { api, _ -> AnotherDinnerScreen(api, FakeBackend.WEEK_START, sampleDinner, {}, {}) }),
    ANOTHER_DINNER_NL(planning = true, dutch = true, heightDp = 2600, content = { api, _ -> AnotherDinnerScreen(api, FakeBackend.WEEK_START, sampleDinner, {}, {}) }),
    // The top of the Plan of a week with dinners chosen and not on days yet: "Continue planning", in both languages.
    PLAN_CONTINUE(planning = true, heightDp = 700, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    PLAN_CONTINUE_NL(planning = true, dutch = true, heightDp = 700, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    // Fibre next to kcal and protein in Dutch, where the totals are longest (#223).
    TODAY_NL(dutch = true, heightDp = 1300, content = { api, _ -> TodayScreen(api, { _, _ -> }, {}) }),
    PLAN_NL(weekRest = true, dutch = true, heightDp = 4400, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    // A prep made on Monday's dinner, eaten until Thursday, with Wednesday's skipped lunch still in the box (issue 234).
    PLAN_PREP(prepped = true, heightDp = 4600, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    PLAN_PREP_NL(prepped = true, dutch = true, heightDp = 4600, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    // The same prep kept vacuum sealed, which its labels say next to the meal's buttons (issue 263): in both languages, and
    // at the largest font, where the label is longest for its room.
    PLAN_PREP_VACUUM(prepped = true, vacuum = true, heightDp = 4600, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    PLAN_PREP_VACUUM_NL(prepped = true, vacuum = true, dutch = true, heightDp = 4600, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    PLAN_PREP_VACUUM_X2(prepped = true, vacuum = true, heightDp = 4600, fontScale = 2.0f, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    // The Prep dialog's body, as a dialog is a window of its own: in both languages, and at the largest font.
    PREP_CHOICES(heightDp = 1300, content = { _, _ -> prepChoices() }),
    PREP_CHOICES_NL(dutch = true, heightDp = 1550, content = { _, _ -> prepChoices() }),
    PREP_CHOICES_X2(heightDp = 2000, fontScale = 2.0f, content = { _, _ -> prepChoices() }),
    // Half a serving of a salad as a side next to three meals: the side under each, what the meal comes to with it, and the
    // days it takes over the calories (issue 234, step 2); in both languages, and on Today, where the batch is made.
    PLAN_SIDES(sides = true, heightDp = 4800, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    PLAN_SIDES_NL(sides = true, dutch = true, heightDp = 4800, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    TODAY_SIDES(sides = true, heightDp = 2000, content = { api, _ -> TodayScreen(api, { _, _ -> }, {}) }),
    TODAY_SIDES_NL(sides = true, dutch = true, heightDp = 2000, content = { api, _ -> TodayScreen(api, { _, _ -> }, {}) }),
    // The Prep dialog eaten as a side: only the meals a side can go next to can be picked.
    PREP_SIDE_CHOICES(heightDp = 1500, content = { _, _ -> prepSideChoices() }),
    PREP_SIDE_CHOICES_NL(dutch = true, heightDp = 1650, content = { _, _ -> prepSideChoices() }),
    PREP_SIDE_CHOICES_X2(heightDp = 2000, fontScale = 2.0f, content = { _, _ -> prepSideChoices() }),
    // One batch as a side next to two meals and as the meal of a third: each labelled as what it is (issue 269).
    PLAN_MIXED(mixed = true, heightDp = 4800, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    PLAN_MIXED_NL(mixed = true, dutch = true, heightDp = 4800, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    // The Prep dialog with meals picked as the meal and as sides of both sizes (issue 269).
    PREP_MIXED_CHOICES(heightDp = 1400, content = { _, _ -> prepMixedChoices() }),
    PREP_MIXED_CHOICES_NL(dutch = true, heightDp = 1600, content = { _, _ -> prepMixedChoices() }),
    PREP_MIXED_CHOICES_X2(heightDp = 2200, fontScale = 2.0f, content = { _, _ -> prepMixedChoices() }),
    // "Add a prepped side…" with two batches still to make that can take it (issue 269).
    ADD_SIDE_CHOICES(heightDp = 700, content = { _, _ -> addSideChoices() }),
    ADD_SIDE_CHOICES_NL(dutch = true, heightDp = 760, content = { _, _ -> addSideChoices() }),
    ADD_SIDE_CHOICES_X2(heightDp = 1200, fontScale = 2.0f, content = { _, _ -> addSideChoices() }),
    // Removing a prep names every meal that goes, and which were skipped; once made, it says beforehand that it stays (issue 263).
    REMOVE_PREP(heightDp = 420, content = { _, _ -> removePrepQuestion() }),
    REMOVE_PREP_NL(dutch = true, heightDp = 420, content = { _, _ -> removePrepQuestion() }),
    REMOVE_PREP_MADE(heightDp = 500, content = { _, _ -> removePrepQuestion(made = true) }),
    REMOVE_PREP_MADE_NL(dutch = true, heightDp = 560, content = { _, _ -> removePrepQuestion(made = true) }),
    REMOVE_SIDE_PREP_MADE(heightDp = 560, content = { _, _ -> removePrepQuestion(made = true, sides = true) }),
    REMOVE_SIDE_PREP_MADE_NL(dutch = true, heightDp = 640, content = { _, _ -> removePrepQuestion(made = true, sides = true) }),
    // Of a batch eaten as meals and as sides, in words of both (issue 269).
    REMOVE_MIXED_PREP(heightDp = 500, content = { _, _ -> removePrepQuestion(mixed = true) }),
    REMOVE_MIXED_PREP_NL(dutch = true, heightDp = 560, content = { _, _ -> removePrepQuestion(mixed = true) }),
    REMOVE_MIXED_PREP_MADE(heightDp = 640, content = { _, _ -> removePrepQuestion(made = true, mixed = true) }),
    REMOVE_MIXED_PREP_MADE_NL(dutch = true, heightDp = 720, content = { _, _ -> removePrepQuestion(made = true, mixed = true) }),
    // A week whose only meal is of last week's batch: still to plan, no recipes missing, and nothing to buy because last week's list bought it.
    PLAN_PREPPED_TAIL(tailOnly = true, heightDp = 2400, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    SHOPPING_PREPPED_TAIL(tailOnly = true, content = { api, _ -> shopping(api, shoppingMode = false) }),
    SHOPPING_PREPPED_TAIL_NL(tailOnly = true, dutch = true, content = { api, _ -> shopping(api, shoppingMode = false) }),
    // A share in the box on its last day: eat it today or throw it away; with the meals it can still go to.
    SPARE_LAST_DAY(heightDp = 400, content = { _, _ -> spareOnItsLastDay() }),
    SPARE_LAST_DAY_NL(dutch = true, heightDp = 400, content = { _, _ -> spareOnItsLastDay() }),
    // What was in Wednesday's dinner: a line changed, one left out, one added in pieces, one linked to nothing; the
    // later dinner the extra chicken leaves short, and the quick add-ons (#226).
    ADJUST_MEAL(heightDp = 1400, content = { api, _ -> AdjustMealScreen(api, MealPlace(FakeBackend.WEEK_START, 2, MealSlot.DINNER), {}) }),
    ADJUST_MEAL_NL(dutch = true, heightDp = 1400, content = { api, _ -> AdjustMealScreen(api, MealPlace(FakeBackend.WEEK_START, 2, MealSlot.DINNER), {}) }),
    // No recipe of mine is in the rotation, so the week stays empty and says why, with a way to the Recipes tab.
    PLAN_EMPTY_ROTATION(emptyWeek = true, emptyRecipes = true, content = { api, _ -> PlanScreen(api, { _, _ -> }, {}, {}, {}) }),
    RECIPE_PICKER(heightDp = 1300, content = { api, _ -> recipePicker(api) }),
    // Standard meals (issue 265): Today with a skipped lunch and its chips, the Plan's skip sheet, the recipe's switch and the
    // picker with them first; in Dutch, and the sheet, switch and picker at font scale 2.
    TODAY_STANDARD(standardMeals = true, heightDp = 1500, content = { api, _ -> TodayScreen(api, { _, _ -> }, {}) }),
    TODAY_STANDARD_NL(standardMeals = true, dutch = true, heightDp = 1600, content = { api, _ -> TodayScreen(api, { _, _ -> }, {}) }),
    SKIP_SHEET(heightDp = 420, content = { _, _ -> skipSheet() }),
    SKIP_SHEET_NL(dutch = true, heightDp = 460, content = { _, _ -> skipSheet() }),
    SKIP_SHEET_X2(heightDp = 760, fontScale = 2.0f, content = { _, _ -> skipSheet() }),
    STANDARD_SWITCH(heightDp = 340, content = { _, _ -> standardSwitch() }),
    STANDARD_SWITCH_NL(dutch = true, heightDp = 400, content = { _, _ -> standardSwitch() }),
    STANDARD_SWITCH_X2(heightDp = 560, fontScale = 2.0f, content = { _, _ -> standardSwitch() }),
    RECIPE_PICKER_STANDARD(heightDp = 1400, content = { api, _ -> recipePicker(api, standard = true) }),
    RECIPE_PICKER_STANDARD_NL(dutch = true, heightDp = 1400, content = { api, _ -> recipePicker(api, standard = true) }),
    RECIPE_PICKER_STANDARD_X2(heightDp = 2200, fontScale = 2.0f, content = { api, _ -> recipePicker(api, standard = true) }),
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
    // With its optional toppings, one counted in pieces (#226).
    RECIPE_DETAIL(heightDp = 1900, content = { api, _ -> RecipeDetailScreen(api, 4, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}) }),
    RECIPE_DETAIL_NL(dutch = true, heightDp = 1900, content = { api, _ -> RecipeDetailScreen(api, 4, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}) }),
    // Opened from a planned meal: the amounts of 1.5 of the recipe's 2 servings, and nothing to cook for leftovers.
    RECIPE_FROM_MEAL(heightDp = 1900, content = { api, _ ->
        RecipeDetailScreen(api, 4, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}, meal = MealToCook(4, 0.75, 1.5, skipped = false))
    }),
    RECIPE_FROM_LEFTOVERS(heightDp = 1900, content = { api, _ ->
        RecipeDetailScreen(api, 4, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}, meal = MealToCook(4, 0.75, 0.0, skipped = false))
    }),
    // A later meal of a batch made on Monday: nothing to cook, and what is in its box of 1.25 servings (issue 263).
    RECIPE_FROM_PREPPED_DAY(heightDp = 1900, content = { api, _ ->
        RecipeDetailScreen(api, 4, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}, meal = MealToCook(4, 1.25, 0.0, skipped = false, prepMadeOn = FakeBackend.WEEK_START))
    }),
    RECIPE_FROM_PREPPED_DAY_NL(dutch = true, heightDp = 1900, content = { api, _ ->
        RecipeDetailScreen(api, 4, "NEVO online version 2025/9.0, RIVM, Bilthoven", {}, {}, meal = MealToCook(4, 1.25, 0.0, skipped = false, prepMadeOn = FakeBackend.WEEK_START))
    }),
    // Cook mode on the day a batch of 4 servings is made: what it cooks for whom, as the recipe says it (issue 263).
    COOK_MODE_PREP_DAY(heightDp = 1500, content = { api, _ ->
        RecipeDetailScreen(api, 4, null, {}, {}, meal = MealToCook(4, 1.5, 4.0, skipped = false, prepMadeOn = FakeBackend.TODAY), initiallyCooking = true)
    }),
    COOK_MODE_PREP_DAY_NL(dutch = true, heightDp = 1500, content = { api, _ ->
        RecipeDetailScreen(api, 4, null, {}, {}, meal = MealToCook(4, 1.5, 4.0, skipped = false, prepMadeOn = FakeBackend.TODAY), initiallyCooking = true)
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
    OFFERS(heightDp = 800, content = { api, _ -> OffersScreen(api, {}, {}) }),
    OFFERS_EMPTY(emptyOffers = true, content = { api, _ -> OffersScreen(api, {}, {}) }),
    PRICES(heightDp = 800, content = { api, _ -> PricesScreen(api, {}) }),
    PRICES_EMPTY(emptyPrices = true, content = { api, _ -> PricesScreen(api, {}) }),
    PROGRESS(heightDp = 1800, content = { api, _ -> ProgressScreen(api, {}, {}) }),
    // After the only weigh-in was removed (issue 196): the note that the profile keeps its weight, and the undo snackbar, in both languages.
    PROGRESS_KEPT(heightDp = 360, content = { _, _ -> removedWeighIn() }),
    PROGRESS_KEPT_NL(dutch = true, heightDp = 400, content = { _, _ -> removedWeighIn() }),
    PROGRESS_KEPT_X2(heightDp = 800, fontScale = 2.0f, content = { _, _ -> removedWeighIn() }),
    // The profile with a portion range, a priority and a fibre target of its own, in both languages: the range's fields side by side.
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
    // In Dutch, where "Boodschappen" and "Voortgang" are the longest labels of the bottom bar (#190).
    SHELL_NL(dutch = true, content = { _, settings -> shell(settings, AppLanguage.DUTCH) }),
    // The week of the shopping list must stay readable at the largest font setting (#190).
    SHOPPING_HEADER_X2(heightDp = 700, fontScale = 2.0f, content = { api, _ -> shopping(api, shoppingMode = false) }),
    SHOPPING_HEADER_X2_NL(dutch = true, heightDp = 700, fontScale = 2.0f, content = { api, _ -> shopping(api, shoppingMode = false) }),
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
            error = "Could not start the login: Connection refused", busy = false, onLogin = {}, onOpenSettings = {},
        )
    }),
    SETTINGS(heightDp = 1350, content = { _, _ ->
        ServerSettingsScreen(
            current = ServerSettings("https://mealplanner.example.nl"),
            account = "Anna",
            loggedIn = true,
            loginError = null,
            loggingIn = false,
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
    // The Notifications card (Android only): the defaults with weigh-in turned on, in both languages, with notifications refused
    // by Android, and at font scale 2 in both languages, where the hints are longest (prepped food to throw away, issue 264).
    NOTIFICATIONS(heightDp = 860, content = { _, _ -> notificationsCard(allowed = true) }),
    NOTIFICATIONS_NL(dutch = true, heightDp = 920, content = { _, _ -> notificationsCard(allowed = true) }),
    NOTIFICATIONS_REFUSED(heightDp = 960, content = { _, _ -> notificationsCard(allowed = false) }),
    NOTIFICATIONS_X2(heightDp = 1600, fontScale = 2.0f, content = { _, _ -> notificationsCard(allowed = true) }),
    NOTIFICATIONS_X2_NL(dutch = true, heightDp = 1800, fontScale = 2.0f, content = { _, _ -> notificationsCard(allowed = true) }),
}

/** The Notifications card with the default switches and the weigh-in on; [allowed] is whether Android lets the app post. */
@Composable
private fun notificationsCard(allowed: Boolean) {
    val storage = remember { MemorySettingsStorage().also { it.saveNotificationOn(NotificationKind.WEIGH_IN, true) } }
    val access = object : NotificationAccess {
        override val allowed = MutableStateFlow(allowed)

        override fun ask() = Unit

        override fun openSettings() = Unit
    }
    Column(Modifier.padding(16.dp)) { NotificationsCard(NotificationSettings(storage, access)) }
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

    private val backend = FakeBackend(
        shot.emptyWeek, shot.emptyOffers, shot.emptyPrices, shot.emptyRecipes, prepped = shot.prepped, sides = shot.sides, mixed = shot.mixed, tailOnly = shot.tailOnly, weekRest = shot.weekRest,
        vacuum = shot.vacuum,
        standardMeals = shot.standardMeals,
        planning = shot.planning,
    )
        .also { it.profile = shot.profile }
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
                // The ring shows for a keyboard user only; a key press (a harmless one) says this is one.
                compose.onNodeWithTag(FOCUSED).performKeyInput { pressKey(Key.ShiftLeft) }
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
            LocalDensity provides Density(density.density, shot.fontScale ?: look.fontScale),
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
