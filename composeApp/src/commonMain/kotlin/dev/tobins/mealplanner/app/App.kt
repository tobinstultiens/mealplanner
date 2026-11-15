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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import coil3.compose.LocalPlatformContext
import dev.tobins.mealplanner.api.Me
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize

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
    /** Redirects back from the login page (URLs), as the platform receives them. */
    loginCallbacks: Flow<String>,
    updater: UpdateInstaller? = null,
    /** Where the open screen is kept between reloads (the browser's address); none on Android. */
    routes: RouteStore = NoRoutes,
) {
    var settings by remember { mutableStateOf(storage.serverSettings(defaults)) }
    val login = remember(storage) { Login(storage) }
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
    // When renewing fails the session is over, and the app asks to log in again.
    val api = remember(settings, session) {
        ApiClient(settings, session) {
            val current = storage.session() ?: return@ApiClient null
            login.refresh(settings.url, current) ?: run {
                if (storage.session() == null) session = null
                null
            }
        }
    }
    val shoppingCache = remember(storage) { ShoppingCache(storage) }
    DisposableEffect(api) { onDispose { api.close() } }

    // What was open when the page was loaded (a reload or a shared link); Today where nothing says.
    val start = remember { parseRoute(routes.read()).openScreens() }
    var tab by rememberSaveable { mutableStateOf(start.tab) }
    var openRecipeId by rememberSaveable { mutableStateOf(start.recipeId) }
    // Null: no editor open. -1: a new recipe. Otherwise the id of the recipe being edited.
    var editing by rememberSaveable { mutableStateOf(start.editing) }
    var mapping by rememberSaveable { mutableStateOf(start.mapping) }
    var profileOpen by rememberSaveable { mutableStateOf(start.profile) }
    var pantryOpen by rememberSaveable { mutableStateOf(start.pantry) }
    var offersOpen by rememberSaveable { mutableStateOf(start.offers) }
    var pricesOpen by rememberSaveable { mutableStateOf(start.prices) }
    // Not logged in, nothing works yet: start on the settings, where the login is.
    var serverOpen by rememberSaveable { mutableStateOf(session == null || start.settings) }
    // The address follows the open screen, so that a reload comes back to it.
    val open = OpenScreens(tab, openRecipeId, editing, mapping, profileOpen, pantryOpen, offersOpen, pricesOpen, serverOpen)
    LaunchedEffect(open, session != null) { addressFor(open, loggedIn = session != null)?.let(routes::write) }

    LaunchedEffect(Unit) {
        loginCallbacks.collect { url ->
            if (!login.isCallback(url)) return@collect
            runCatching { login.finish(url) }
                .onSuccess {
                    loginError = null
                    session = it
                    serverOpen = false
                }
                .onFailure {
                    loginError = it.message ?: Strings.current(Msg.LOGIN_FAILED)
                    serverOpen = true
                }
        }
    }
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
    ) {
    MealPlannerTheme(dark, systemColors) {
      // On a wide browser window the app keeps a phone-like column in the middle.
      Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).focusRing(), contentAlignment = Alignment.TopCenter) {
       Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
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
                    loginError = loginError,
                    onLogin = { draft ->
                        storage.saveServerSettings(draft)
                        settings = draft
                        loginError = null
                        scope.launch {
                            runCatching { login.start(draft.url, launcher.redirectUri(draft.url)) }
                                .onSuccess(launcher::open)
                                .onFailure { loginError = strings(Msg.LOGIN_START_FAILED, it.message) }
                        }
                    },
                    onLogout = {
                        storage.saveSession(null)
                        session = null
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
                    onSave = {
                        // Another server has its own accounts: the session doesn't carry over.
                        if (it.url != settings.url) {
                            storage.saveSession(null)
                            session = null
                        }
                        storage.saveServerSettings(it)
                        settings = it
                        if (session != null) serverOpen = false
                    },
                    onBack = { serverOpen = false },
                )
                return@CompositionLocalProvider
            }
            // A new server means new data: every screen below starts over.
            key(settings) {
                val recipeId = openRecipeId
                val editingId = editing
                when {
                    editingId != null -> {
                        BackHandler(enabled = true) { editing = null }
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
                    pricesOpen -> {
                        BackHandler(enabled = true) { pricesOpen = false }
                        PricesScreen(api, onBack = { pricesOpen = false })
                    }
                    offersOpen -> {
                        BackHandler(enabled = true) { offersOpen = false }
                        OffersScreen(api, onBack = { offersOpen = false }, onOpenPrices = { pricesOpen = true })
                    }
                    pantryOpen -> {
                        BackHandler(enabled = true) { pantryOpen = false }
                        PantryScreen(api, onBack = { pantryOpen = false })
                    }
                    profileOpen -> {
                        BackHandler(enabled = true) { profileOpen = false }
                        ProfileScreen(api, onBack = { profileOpen = false })
                    }
                    mapping -> {
                        BackHandler(enabled = true) { mapping = false }
                        MappingScreen(api, onBack = { mapping = false })
                    }
                    recipeId != null -> {
                        BackHandler(enabled = true) { openRecipeId = null }
                        RecipeDetailScreen(api, recipeId, nutritionSource, onBack = { openRecipeId = null }, onEdit = { editing = recipeId })
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
                            when (tab) {
                                Tab.TODAY -> TodayScreen(api, onOpenRecipe = { openRecipeId = it }, onOpenPlan = { tab = Tab.PLAN })
                                Tab.PLAN -> PlanScreen(api, onOpenRecipe = { openRecipeId = it }, onOpenProfile = { profileOpen = true })
                                Tab.SHOPPING -> ShoppingScreen(api, shoppingCache, storage, onOpenPantry = { pantryOpen = true }, onOpenOffers = { offersOpen = true })
                                Tab.RECIPES -> RecipeListScreen(
                                    api,
                                    onOpen = { openRecipeId = it },
                                    onOpenMapping = { mapping = true },
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
        val style = MaterialTheme.typography.labelSmall
        // A longer word ("Boodschappen") shrinks to fit its tab instead of being cut off.
        Text(text, maxLines = 1, softWrap = false, style = style, autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize))
    }
}

/** The font scale at which "Progress" still clears its tab's edges on a 360 dp wide screen. */
private const val MaxLabelFontScale = 1.15f
