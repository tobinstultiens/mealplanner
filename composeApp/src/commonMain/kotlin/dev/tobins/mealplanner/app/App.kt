package dev.tobins.mealplanner.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import coil3.compose.LocalPlatformContext
import dev.tobins.mealplanner.api.Me
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Root of the app: five tabs (Today, Plan, Shopping, Recipes, Progress) and the
 * screens opened from them. [defaults] are the server settings built into the app,
 * used until the user saves their own in [storage].
 */
@Composable
fun App(
    defaults: ServerSettings,
    storage: SettingsStorage,
    launcher: LoginLauncher,
    /** The login under way, kept by the platform longer than this screen; it hands it the redirects back from the login page. */
    logins: LoginExchange,
    updater: UpdateInstaller? = null,
    /** Where the open screen is kept between reloads (the browser's address); none on Android. */
    routes: RouteStore = NoRoutes,
    /**
     * Whether [defaults] is the server this build is made for (the web client's own address, a release
     * built with one), which the login may use without asking; false where it is only a suggestion.
     */
    defaultsBuiltIn: Boolean = true,
    /** Screens asked for from outside the app, by a tapped notification (Android); none on the web. */
    openings: Openings? = null,
    /** Whether the system lets the app post notifications, for the Notifications card; null where the app posts none (the web). */
    notificationAccess: NotificationAccess? = null,
) {
    var settings by remember { mutableStateOf(storage.serverSettings(defaults)) }
    val serverKnown = remember(settings) { (defaultsBuiltIn || storage.hasSavedServer()) && settings.isAddress() }
    var session by remember { mutableStateOf(storage.session()) }
    var account by remember { mutableStateOf<Me?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf(storage.themeMode()) }
    var language by remember { mutableStateOf(storage.appLanguage()) }
    // Changing the language in Settings applies at once: everything below reads its texts from here.
    val strings = remember(language) { Strings.of(language, deviceLocaleTag()) }
    SideEffect { Strings.current = strings }
    var systemColors by remember { mutableStateOf(storage.systemColors()) }
    var productPictures by remember { mutableStateOf(storage.productPictures()) }
    val platformContext = LocalPlatformContext.current
    // One loader for the whole app so its caches are shared. With product pictures off the rows get none and
    // show their letter; recipe photos come from our own server and are loaded either way.
    val imageLoader = remember { productImageLoader(platformContext) }
    DisposableEffect(imageLoader) { onDispose { imageLoader.shutdown() } }
    val recipeImages = remember(settings, imageLoader, storage) { RecipeImages(imageLoader, settings.url, { storage.session()?.accessToken }) }
    // A newer release found on start; "Later" hides it until the next start.
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    LaunchedEffect(updater) {
        if (updater != null) update = runCatching { AppUpdates.check(updater.currentVersion) }.getOrNull()
    }
    // Renewed tokens go to storage only: the client keeps using them, and the screens don't reload.
    // When renewing ended the session (see Login.refresh for when it does), the app asks to log in again.
    val api = remember(settings, session) {
        ApiClient(settings, session) { refused ->
            logins.renew(settings.url, refused) ?: run {
                // Whoever logs in next may be someone else: what was the account's goes, as on logging out.
                // The shopping session of this client is closed when the client is replaced; until then
                // its requests fail for want of a login, and one answered anyway stores nothing, as its
                // cache was made before the clear (ShoppingCache).
                if (storage.session() == null) {
                    storage.logOut()
                    session = null
                }
                null
            }
        }
    }
    // The shopping list's state lives as long as the client, so one login: leaving the Shopping
    // screen cancels none of its requests, and the next account starts with a session of its own.
    // Its place in the list is kept in the saved state, as the screen's own state was before.
    fun shoppingSession(place: ShoppingSession.Place = ShoppingSession.Place()) =
        ShoppingSession(scope, api.shoppingServer(), ShoppingCache(storage), place = place, shoppingMode = storage::shoppingMode)
    val shopping = rememberSaveable(api, storage, saver = shoppingPlaceSaver(::shoppingSession)) { shoppingSession() }
    DisposableEffect(shopping) { onDispose { shopping.close() } }
    // The list keeps itself current only while the app is in front; on start and on coming back
    // it sends what was ticked offline and fetches the list, also before the Shopping tab is opened.
    // Told the moment the app goes behind or comes back, also in a browser tab that draws nothing while
    // hidden. The first time at once, as before the Shopping screen's own effect resumes the list.
    val front = rememberAppFront()
    val loggedIn = session != null
    DisposableEffect(shopping, front, loggedIn) {
        val following = scope.launch(start = CoroutineStart.UNDISPATCHED) { shopping.follow(front, loggedIn) }
        onDispose { following.cancel() }
    }

    /** Logs out; the shopping requests still going stop first, so nothing of the account is written back afterwards. */
    fun endSession() {
        shopping.close()
        logins.logOut()
        session = null
    }
    val accountShops = remember(api, storage) { AccountShops(storage, api::shops, api::saveShops) }
    // On start, so a device's choice of stores from before they were an account setting reaches
    // the planner even before the shopping list is opened.
    LaunchedEffect(accountShops) { if (session != null) load { accountShops.sync() } }
    DisposableEffect(api) { onDispose { api.close() } }

    // What a tapped notification asked for when it started the app; else what was open when the page was
    // loaded (a reload or a shared link); Today where nothing says.
    val opened = remember { openings?.take() }
    val start = remember { opened?.route?.openScreens() ?: parseRoute(routes.read()).openScreens() }
    var tab by rememberSaveable { mutableStateOf(start.tab) }
    var openRecipeId by rememberSaveable { mutableStateOf(start.recipeId) }
    // The planned meal the open recipe was opened from, for how much to cook; none from the recipe list.
    var openMeal by rememberSaveable(stateSaver = MealToCook.Saver) { mutableStateOf(opened?.meal) }
    // The week a tapped notification asked the Plan to show, whether on its planning screen, and how often one asked:
    // each asking shows it afresh.
    var planWeek by rememberSaveable { mutableStateOf(opened?.week) }
    var planPlanning by rememberSaveable { mutableStateOf(opened?.planning == true) }
    var planAsked by rememberSaveable { mutableIntStateOf(0) }
    // Null: no editor open. -1: a new recipe. Otherwise the id of the recipe being edited.
    var editing by rememberSaveable { mutableStateOf(start.editing) }
    var mapping by rememberSaveable { mutableStateOf(start.mapping) }
    // The planned meal whose ingredients are being adjusted (issue 226), opened from Today or the Plan.
    var adjusting by rememberSaveable(stateSaver = MealPlace.Saver) { mutableStateOf(start.adjusting) }
    var bankOpen by rememberSaveable { mutableStateOf(start.bank) }
    var profileOpen by rememberSaveable { mutableStateOf(start.profile) }
    var pantryOpen by rememberSaveable { mutableStateOf(start.pantry) }
    var offersOpen by rememberSaveable { mutableStateOf(start.offers) }
    var pricesOpen by rememberSaveable { mutableStateOf(start.prices) }
    // Without a login the login screen is in front instead; a link on it opens the settings.
    var serverOpen by rememberSaveable { mutableStateOf(start.settings && session != null) }
    // What each screen keeps in its remembered state (scroll, search, the week or day looked at) outlives the
    // screen being drawn: a screen covered by another (a tab by a recipe, a recipe by its editor) is saved
    // under its key and restored when it is drawn again, also after Android re-created the activity.
    val screenStates = rememberSaveableStateHolder()
    // What the screens loaded last: made with the client, so it never outlives a login.
    val screenMemory = remember(api) { ScreenMemory() }
    // A screen that was closed (as opposed to covered) forgets its state: opening it again starts afresh.
    val openKeys = buildSet {
        editing?.let { add("editor:$it") }
        openRecipeId?.let { add("recipe:$it") }
        if (mapping) add("mapping")
        adjusting?.let { add("adjust:$it") }
        if (bankOpen) add("bank")
        if (profileOpen) add("profile")
        if (pantryOpen) add("pantry")
        if (offersOpen) add("offers")
        if (pricesOpen) add("prices")
    }
    var previousKeys by remember { mutableStateOf(openKeys) }
    LaunchedEffect(openKeys) {
        (previousKeys - openKeys).forEach(screenStates::removeState)
        previousKeys = openKeys
    }
    // Whoever logs in next may be someone else: once logged out, no screen keeps the place it had for the account before,
    // nor does the Plan the week a notification asked for (issue 263). The login screen keeps what was typed in it. Only
    // on logging out, not on a start without a login, which may be a notification's opening that the login comes before.
    var wasLoggedIn by remember { mutableStateOf(loggedIn) }
    LaunchedEffect(loggedIn) {
        if (wasLoggedIn && !loggedIn) {
            (Tab.entries.map { "tab:${it.slug}" } + openKeys).forEach(screenStates::removeState)
            planWeek = null
            planPlanning = false
        }
        wasLoggedIn = loggedIn
    }
    // The address follows the open screen, so that a reload comes back to it.
    val open = OpenScreens(tab, openRecipeId, editing, mapping, profileOpen, pantryOpen, offersOpen, pricesOpen, serverOpen, bankOpen, adjusting)

    /** Opens what a notification tapped while the app runs asks for, keeping what holds unsaved typing (see [OpenScreens.opening]). */
    fun openFromOutside(opening: Opening): OpenScreens {
        val now = OpenScreens(tab, openRecipeId, editing, mapping, profileOpen, pantryOpen, offersOpen, pricesOpen, serverOpen, bankOpen, adjusting)
            .opening(opening.route.openScreens())
        tab = now.tab
        openRecipeId = now.recipeId
        openMeal = opening.meal
        opening.week?.let {
            planWeek = it
            planPlanning = opening.planning
            planAsked++
        }
        editing = now.editing
        mapping = now.mapping
        profileOpen = now.profile
        pantryOpen = now.pantry
        offersOpen = now.offers
        pricesOpen = now.prices
        serverOpen = now.settings
        bankOpen = now.bank
        adjusting = now.adjusting
        return now
    }
    LaunchedEffect(openings) {
        // The one taken at the start is applied once more: where Android restored the screens' saved state (its
        // process was killed with the task in Recents), that state, not [start], says what is open.
        opened?.let(::openFromOutside)
        openings?.requests?.collect(::openFromOutside)
    }
    LaunchedEffect(open, session != null) { addressFor(open, loggedIn = session != null)?.let(routes::write) }
    // A new address typed or pasted into the open page: open it as a tapped notification would (what holds typing
    // stays in front), then show the address of what is open, which differs where that stayed. Without a login the
    // address is left alone, and what it asks for is shown again once the login is done.
    val followAddress = rememberUpdatedState { hash: String ->
        if (session != null) addressFor(openFromOutside(Opening(parseRoute(hash))), loggedIn = true)?.let(routes::write)
    }
    DisposableEffect(routes) { onDispose(routes.watch { followAddress.value(it) }) }
    // A login made in another tab of the browser is the login of this one too.
    DisposableEffect(storage) { onDispose(storage.watch { if (session == null) session = storage.session() }) }
    // Leaving the Plan tab the plan may have changed: the current week's list is fetched for the shop.
    var previousTab by remember { mutableStateOf(tab) }
    LaunchedEffect(tab) {
        if (previousTab == Tab.PLAN && tab != Tab.PLAN && session != null) shopping.prefetch()
        previousTab = tab
    }

    // What came of the login page's answer, whether it came while this screen or an earlier one was there.
    val progress by logins.progress.collectAsState()
    LaunchedEffect(progress) {
        val done = progress as? LoginProgress.Done ?: return@LaunchedEffect
        loginError = null
        session = storage.session()
        serverOpen = false
        logins.acknowledge(done)
    }
    var startingLogin by remember { mutableStateOf(false) }

    fun startLogin(server: ServerSettings) {
        loginError = null
        scope.launch {
            startingLogin = true
            // No page when the login was called off meanwhile (another server saved, or logged out).
            runCatching { logins.start(server.url, launcher.redirectUri(server.url))?.let(launcher::open) }
                .onFailure { loginError = sentence(strings(Msg.LOGIN_START_FAILED, reasonOf(it))) }
            startingLogin = false
        }
    }
    val loggingIn = startingLogin || progress == LoginProgress.Exchanging

    /** A login at a server the user typed or saved: kept as their choice, as the settings do. */
    fun logInAt(server: ServerSettings) {
        storage.saveServerSettings(server)
        settings = server
        startLogin(server)
    }
    // Logged out: straight to the login page, once per logged-out state (see LoginExchange.claimAutoOpen).
    LaunchedEffect(session == null) {
        if (session == null && logins.claimAutoOpen(serverKnown)) startLogin(settings)
    }
    val shownLoginError = loginError ?: (progress as? LoginProgress.Failed)?.message
    LaunchedEffect(api) { account = if (session == null) null else runCatching { api.me() }.getOrNull() }

    // NEVO must be credited wherever its values are shown.
    var nutritionSource by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(api) {
        nutritionSource = try {
            api.foodDataset().reference
        } catch (e: Exception) {
            null
        }
    }

    val dark = when (theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SystemBars(dark)
    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalProductImageLoader provides imageLoader.takeIf { productPictures },
        LocalRecipeImages provides recipeImages,
        LocalScreenMemory provides screenMemory,
    ) {
    MealPlannerTheme(dark, systemColors) {
      // On a wide browser window the app keeps a phone-like column in the middle.
      Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).focusRing(), contentAlignment = Alignment.TopCenter) {
       Box(Modifier.widthIn(max = ContentColumnWidthDp.dp).fillMaxSize()) {
        // Any error message can offer the server settings, since a wrong address is the usual cause.
        update?.let { found ->
            if (updater != null) {
                UpdateDialog(
                    found, updater.currentVersion,
                    onUpdate = {
                        updater.install(found)
                        update = null
                    },
                    onLater = { update = null },
                )
            }
        }
        CompositionLocalProvider(LocalOpenServerSettings provides { serverOpen = true }) {
            if (serverOpen) {
                BackHandler(enabled = true) { serverOpen = false }
                ServerSettingsScreen(
                    current = settings,
                    account = account?.let { it.displayName ?: it.username ?: strings(Msg.YOUR_ACCOUNT) }?.takeIf { session != null },
                    loggedIn = session != null,
                    loginError = shownLoginError,
                    loggingIn = loggingIn,
                    onLogin = ::logInAt,
                    onLogout = {
                        endSession()
                        serverOpen = false
                    },
                    updater = updater,
                    theme = theme,
                    onTheme = {
                        storage.saveThemeMode(it)
                        theme = it
                    },
                    language = language,
                    onLanguage = {
                        storage.saveAppLanguage(it)
                        language = it
                    },
                    systemColors = systemColors,
                    onSystemColors = {
                        storage.saveSystemColors(it)
                        systemColors = it
                    },
                    productPictures = productPictures,
                    onProductPictures = {
                        storage.saveProductPictures(it)
                        productPictures = it
                    },
                    accountShops = accountShops,
                    loadShoppingAdvice = api::shoppingAdvice,
                    saveShoppingAdvice = api::saveShoppingAdvice,
                    onSave = {
                        // Another server has its own accounts: the session doesn't carry over, nor does a
                        // login under way, which belongs to the old one.
                        if (it.url != settings.url) endSession()
                        storage.saveServerSettings(it)
                        settings = it
                        serverOpen = false
                    },
                    onBack = { serverOpen = false },
                    notifications = notificationAccess?.let { NotificationSettings(storage, it) },
                )
                return@CompositionLocalProvider
            }
            if (session == null) {
                // Kept while the settings are in front, so that an address typed here is still there after them.
                screenStates.SaveableStateProvider("login") {
                    LoginScreen(
                        server = settings,
                        serverKnown = serverKnown,
                        reason = storage.loggedOut(),
                        error = shownLoginError,
                        busy = loggingIn,
                        // A known server is not saved: that would pin a built-in address a later release may change.
                        onLogin = { if (serverKnown) startLogin(it) else logInAt(it) },
                        onOpenSettings = { serverOpen = true },
                    )
                }
                return@CompositionLocalProvider
            }
            // A new server means new data: every screen below starts over.
            key(settings) {
                val recipeId = openRecipeId
                val editingId = editing
                val adjustingPlace = adjusting
                when {
                    editingId != null -> screenStates.SaveableStateProvider("editor:$editingId") {
                        RecipeEditorScreen(
                            api = api,
                            recipeId = editingId.takeIf { it >= 0 },
                            onDone = { savedId ->
                                editing = null
                                openRecipeId = savedId
                            },
                            onCancel = { editing = null },
                        )
                    }
                    pricesOpen -> screenStates.SaveableStateProvider("prices") {
                        BackHandler(enabled = true) { pricesOpen = false }
                        PricesScreen(api, onBack = { pricesOpen = false })
                    }
                    offersOpen -> screenStates.SaveableStateProvider("offers") {
                        BackHandler(enabled = true) { offersOpen = false }
                        OffersScreen(api, onBack = { offersOpen = false }, onOpenPrices = { pricesOpen = true })
                    }
                    pantryOpen -> screenStates.SaveableStateProvider("pantry") {
                        BackHandler(enabled = true) { pantryOpen = false }
                        PantryScreen(api, onBack = { pantryOpen = false })
                    }
                    profileOpen -> screenStates.SaveableStateProvider("profile") {
                        ProfileScreen(api, onBack = { profileOpen = false })
                    }
                    mapping -> screenStates.SaveableStateProvider("mapping") {
                        BackHandler(enabled = true) { mapping = false }
                        MappingScreen(api, onBack = { mapping = false })
                    }
                    adjustingPlace != null -> screenStates.SaveableStateProvider("adjust:$adjustingPlace") {
                        BackHandler(enabled = true) { adjusting = null }
                        AdjustMealScreen(api, adjustingPlace, onBack = { adjusting = null })
                    }
                    recipeId != null -> screenStates.SaveableStateProvider("recipe:$recipeId") {
                        BackHandler(enabled = true) { openRecipeId = null }
                        RecipeDetailScreen(
                            api, recipeId, nutritionSource,
                            onBack = { openRecipeId = null },
                            onEdit = { editing = recipeId },
                            meal = openMeal,
                            onOpenMapping = { mapping = true },
                        )
                    }
                    bankOpen -> screenStates.SaveableStateProvider("bank") {
                        BackHandler(enabled = true) { bankOpen = false }
                        RecipeBankScreen(
                            api,
                            onBack = { bankOpen = false },
                            onOpen = { openRecipeId = it; openMeal = null },
                            onOpenMapping = { mapping = true },
                        )
                    }
                    else -> Scaffold(
                        bottomBar = {
                            NavigationBar {
                                Tab.entries.forEach { entry ->
                                    NavigationBarItem(
                                        selected = tab == entry,
                                        onClick = { tab = entry },
                                        icon = { Icon(if (tab == entry) entry.selectedIcon else entry.icon, contentDescription = null) },
                                        label = { NavigationLabel(strings(entry.label)) },
                                    )
                                }
                            }
                        },
                    ) { padding ->
                        // Only the bottom inset is consumed: the bottom bar already covers the
                        // navigation area, while each screen's own top bar still has to clear the status bar.
                        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
                        Box(Modifier.padding(bottom).consumeWindowInsets(bottom)) {
                            // Each tab keeps its own place while another tab or a screen opened from it is in front.
                            screenStates.SaveableStateProvider("tab:${tab.slug}") {
                              when (tab) {
                                Tab.TODAY -> TodayScreen(
                                    api,
                                    onOpenRecipe = { recipeId, meal -> openRecipeId = recipeId; openMeal = meal },
                                    onOpenPlan = { tab = Tab.PLAN },
                                    onAdjust = { adjusting = it },
                                )
                                // A week asked for by a notification starts the Plan afresh on it; otherwise it keeps its place.
                                Tab.PLAN -> key(planAsked) {
                                    PlanScreen(
                                        api,
                                        onOpenRecipe = { recipeId, meal -> openRecipeId = recipeId; openMeal = meal },
                                        onOpenProfile = { profileOpen = true },
                                        onOpenRecipes = { tab = Tab.RECIPES },
                                        onOpenBank = { tab = Tab.RECIPES; bankOpen = true },
                                        onAdjust = { adjusting = it },
                                        startWeek = planWeek,
                                        startPlanning = planPlanning,
                                    )
                                }
                                Tab.SHOPPING -> ShoppingScreen(shopping, storage, accountShops, onOpenPantry = { pantryOpen = true }, onOpenOffers = { offersOpen = true })
                                Tab.RECIPES -> RecipeListScreen(
                                    api,
                                    onOpen = { openRecipeId = it; openMeal = null },
                                    onOpenMapping = { mapping = true },
                                    onOpenBank = { bankOpen = true },
                                    onNewRecipe = { editing = -1 },
                                )
                                Tab.PROGRESS -> ProgressScreen(
                                    api,
                                    onOpenProfile = { profileOpen = true },
                                    onOpenServer = { serverOpen = true },
                                )
                              }
                            }
                        }
                    }
                }
            }
        }
       }
      }
    }
    }
}

/** Saves a shopping session's [ShoppingSession.Place] as values Android can keep, and makes a new session at that place. */
private fun shoppingPlaceSaver(create: (ShoppingSession.Place) -> ShoppingSession) = listSaver<ShoppingSession, Any?>(
    save = { with(it.place) { listOf(chosenWeek, ArrayList(expanded), scrollIndex, scrollOffset) } },
    restore = { (week, expanded, index, offset) ->
        create(ShoppingSession.Place(week as String?, (expanded as List<*>).map { it as String }.toSet(), index as Int, offset as Int))
    },
)

/** The five tabs; the icon is filled in for the one that is open. */
enum class Tab(val slug: String, val label: Msg, val icon: ImageVector, val selectedIcon: ImageVector) {
    TODAY("today", Msg.TAB_TODAY, AppIcons.Today, AppIcons.TodayFilled),
    PLAN("plan", Msg.TAB_PLAN, AppIcons.Plan, AppIcons.PlanFilled),
    SHOPPING("shopping", Msg.TAB_SHOPPING, AppIcons.Shopping, AppIcons.ShoppingFilled),
    RECIPES("recipes", Msg.TAB_RECIPES, AppIcons.Recipes, AppIcons.RecipesFilled),
    PROGRESS("progress", Msg.TAB_PROGRESS, AppIcons.Progress, AppIcons.ProgressFilled),
}

/**
 * A bottom bar label on one line (wrapping would make the bar taller on some tabs only). Five
 * labels share the width, so the label grows with the user's font size only up to
 * [MaxLabelFontScale]; beyond that the icon and the rest of the app still follow the setting.
 */
@Composable
private fun NavigationLabel(text: String) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = minOf(density.fontScale, MaxLabelFontScale))) {
        // All five share one size, a little under the theme's, and without letter spacing, so the longest word ("Boodschappen") fits its tab whole.
        Text(text, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall.copy(fontSize = LabelFontSize, letterSpacing = 0.sp))
    }
}

/** The width of the column the app keeps in the middle of a wide window; the web client also needs it to find the margins (wheel scrolling). */
internal const val ContentColumnWidthDp = 760

/** The size of the bottom bar labels before the font scale: "Boodschappen" in Dutch is the widest. */
private val LabelFontSize = 10.sp

/** The font scale at which "Progress" still clears its tab's edges on a 360 dp wide screen. */
private const val MaxLabelFontScale = 1.15f
