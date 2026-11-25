package dev.tobins.mealplanner.app

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import dev.tobins.mealplanner.api.ShoppingAdvice
import kotlinx.coroutines.launch

/**
 * App settings in cards: the server address (first while not logged in, when the login screen's
 * "Change server" opened it; it can be tested before saving), the account, light or dark and (on
 * Android 12+) the wallpaper colours, which apply at once, and the app version.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ServerSettingsScreen(
    current: ServerSettings,
    /** Who is logged in, when known. */
    account: String?,
    loggedIn: Boolean,
    loginError: String?,
    /** A login is being started or its answer swapped for tokens: Log in waits, as on the login screen. */
    loggingIn: Boolean,
    /** Saves [ServerSettings] and opens the login page of that server's identity provider. */
    onLogin: (ServerSettings) -> Unit,
    onLogout: () -> Unit,
    updater: UpdateInstaller?,
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    language: AppLanguage,
    onLanguage: (AppLanguage) -> Unit,
    /** Whether the colours come from the wallpaper; the switch shows only where the device has them. */
    systemColors: Boolean,
    onSystemColors: (Boolean) -> Unit,
    productPictures: Boolean,
    onProductPictures: (Boolean) -> Unit,
    /** The stores the account shops at: the same state and save path as the per-store shopping list. */
    accountShops: AccountShops,
    /** Reads and saves whether the account's shopping list advises buying ahead and stocking up. */
    loadShoppingAdvice: suspend () -> ShoppingAdvice,
    saveShoppingAdvice: suspend (ShoppingAdvice) -> ShoppingAdvice,
    onSave: (ServerSettings) -> Unit,
    onBack: () -> Unit,
    /** The notifications' switches and the system's permission; null where the app posts none (the web). */
    notifications: NotificationSettings? = null,
    /** The account's "Not this ingredient" list, shown in the Shopping card while logged in. */
    exclusions: OfferExclusionQueue? = null,
    /** The requests for an account to decide on (issue 314): the owner's account only, null for every other. */
    accountRequests: AccountDecisions? = null,
) {
    val t = LocalStrings.current
    var url by remember { mutableStateOf(current.url) }
    var result by remember { mutableStateOf<ConnectionResult?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val draft = ServerSettings(url.trim().trimEnd('/'))
    val urlValid = draft.url.startsWith("http://") || draft.url.startsWith("https://")

    SubScreen(t(Msg.SETTINGS), onBack, snackbar) { padding ->
        // Without an account the server comes first: nothing else works until it is set.
        val server: @Composable () -> Unit = {
            SettingsCard(t(Msg.CARD_SERVER)) {
                OutlinedTextField(
                    url, { url = it; result = null },
                    label = { Text(t(Msg.SERVER_ADDRESS)) },
                    supportingText = { Text(t(Msg.SERVER_ADDRESS_HINT)) },
                    isError = url.isNotBlank() && !urlValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.BetweenCards)) {
                    OutlinedButton(
                        enabled = urlValid && !testing,
                        onClick = {
                            testing = true
                            scope.launch {
                                val client = ApiClient(draft)
                                result = when (val health = load { client.health() }) {
                                    is Load.Failed -> ConnectionResult(false, failureSentence(health))
                                    is Load.Ready -> ConnectionResult(true, t(Msg.CONNECTED, health.value.version))
                                    Load.Loading -> null
                                }
                                client.close()
                                testing = false
                            }
                        },
                    ) { Text(if (testing) t(Msg.TESTING) else t(Msg.TEST_CONNECTION)) }
                    Button(enabled = urlValid && draft != current, onClick = { onSave(draft) }) { Text(t(Msg.SAVE)) }
                }
                result?.let {
                    if (it.ok) Text(it.text, style = MaterialTheme.typography.bodyMedium)
                    else ErrorBanner(t(Msg.CANNOT_REACH), it.text)
                }
            }
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Opened from the login screen to change the server: that comes first.
            if (!loggedIn) server()
            SettingsCard(t(Msg.CARD_ACCOUNT)) {
                if (loggedIn) {
                    Text(if (account != null) t(Msg.LOGGED_IN_AS, account) else t(Msg.LOGGED_IN), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onLogout) { Text(t(Msg.LOG_OUT)) }
                } else {
                    Text(
                        t(Msg.LOGIN_EXPLAIN),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(enabled = urlValid && !loggingIn, onClick = { onLogin(draft) }) {
                        Text(if (loggingIn) t(Msg.LOGGING_IN) else t(Msg.LOG_IN))
                    }
                }
                loginError?.let { ErrorBanner(t(Msg.COULD_NOT_LOG_IN), it) }
            }
            // Near the top: the "Account requests" notification opens Settings for this card.
            if (loggedIn && accountRequests != null) AccountRequestsCard(accountRequests, snackbar)
            SettingsCard(t(Msg.CARD_APPEARANCE)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(selected = theme == mode, onClick = { onTheme(mode) }, label = { Text(t(mode.msg())) })
                    }
                }
                Text(t(Msg.LANGUAGE), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    AppLanguage.entries.forEach { option ->
                        FilterChip(selected = language == option, onClick = { onLanguage(option) }, label = { Text(t(option.msg())) })
                    }
                }
                if (systemColorsAvailable()) {
                    SwitchRow(t(Msg.SYSTEM_COLORS), t(Msg.SYSTEM_COLORS_HINT), systemColors, onSystemColors)
                }
                SwitchRow(t(Msg.PRODUCT_PICTURES), t(if (runsInBrowser()) Msg.PRODUCT_PICTURES_HINT_BROWSER else Msg.PRODUCT_PICTURES_HINT), productPictures, onProductPictures)
            }
            if (loggedIn) {
                var shopsError by remember { mutableStateOf<String?>(null) }
                SettingsCard(t(Msg.CARD_SHOPPING)) {
                    ShopsInUse(
                        accountShops.stores,
                        onChange = { chosen ->
                            scope.launch { shopsError = (load { accountShops.choose(chosen) } as? Load.Failed)?.let { failureSentence(it) } }
                        },
                        modifier = Modifier,
                    )
                    shopsError?.let { ErrorBanner(t(Msg.SHOPS_SAVE_FAILED_TITLE), it) }
                    ShoppingAdviceSwitches(loadShoppingAdvice, saveShoppingAdvice)
                    exclusions?.let { ExcludedOffers(it, snackbar) }
                }
            }
            notifications?.let { NotificationsCard(it, approvesRequests = accountRequests != null) }
            if (updater != null) AppVersionSection(updater)
            if (loggedIn) server()
        }
    }
}

/**
 * The account's two shopping-list advice switches. They show once the server has answered (a server
 * from before them has none to show); a switch flips at once and goes back if saving fails.
 */
@Composable
private fun ShoppingAdviceSwitches(read: suspend () -> ShoppingAdvice, save: suspend (ShoppingAdvice) -> ShoppingAdvice) {
    val t = LocalStrings.current
    val scope = rememberCoroutineScope()
    var advice by remember { mutableStateOf<ShoppingAdvice?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { advice = (load { read() } as? Load.Ready)?.value }

    fun change(chosen: ShoppingAdvice) {
        val before = advice
        advice = chosen
        scope.launch {
            when (val result = load { save(chosen) }) {
                is Load.Ready -> {
                    error = null
                    if (advice == chosen) advice = result.value
                }
                is Load.Failed -> {
                    error = failureSentence(result)
                    if (advice == chosen) advice = before
                }
                Load.Loading -> Unit
            }
        }
    }

    val current = advice ?: return
    SwitchRow(t(Msg.BUY_AHEAD_SETTING), t(Msg.BUY_AHEAD_SETTING_HINT), current.buyAhead) { change(current.copy(buyAhead = it)) }
    SwitchRow(t(Msg.STOCK_UP_SETTING), t(Msg.STOCK_UP_SETTING_HINT), current.stockUp) { change(current.copy(stockUp = it)) }
    error?.let { ErrorBanner(t(Msg.ADVICE_SAVE_FAILED_TITLE), it) }
}

/**
 * The account's "Not this ingredient" list (issue 316), the newest first, each with "Take back", which asks nothing: the
 * snackbar that follows has Undo. Read from the device, so it shows without a connection too, and asked for again when
 * shown, as another device on the account may have changed it. One made here that the server does not have yet says so.
 */
@Composable
internal fun ExcludedOffers(exclusions: OfferExclusionQueue, snackbar: SnackbarHostState) {
    val t = LocalStrings.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(exclusions) { exclusions.refresh() }
    val shown = exclusions.inEffect
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Text(t(Msg.NOT_THIS_INGREDIENT), style = MaterialTheme.typography.labelLarge)
        Text(
            t(if (shown.isEmpty()) Msg.EXCLUSIONS_NONE else Msg.EXCLUSIONS_HINT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        shown.forEach { exclusion ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(exclusion.title, style = MaterialTheme.typography.bodyMedium)
                    val counted = t(Msg.EXCLUDED_FROM, exclusion.store.shortName(), exclusion.ingredientName)
                    Text(
                        if (exclusions.isWaiting(exclusion)) "$counted · ${t(Msg.EXCLUSION_WAITING)}" else counted,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    exclusions.chooseWithUndo(scope, snackbar, t, exclusion.store, exclusion.title, exclusion.ingredientId, exclusion.ingredientName, excluded = false)
                }) { Text(t(Msg.TAKE_BACK)) }
            }
        }
    }
}

/**
 * One switch per kind of notification, kept on this device. Turning one on asks for the system's permission
 * where it needs asking; while a switch is on and the system does not let the app post, the card says so,
 * with the way to the system's settings, until the platform reports otherwise ([NotificationAccess.allowed]).
 * "Account requests" shows only to the account that [approvesRequests] (issue 314): no other gets them.
 */
@Composable
internal fun NotificationsCard(settings: NotificationSettings, approvesRequests: Boolean = false) {
    val t = LocalStrings.current
    val kinds = NotificationKind.shown(approvesRequests)
    var on by remember(kinds) { mutableStateOf(kinds.associateWith(settings.storage::notificationOn)) }
    val allowed by settings.access.allowed.collectAsState()
    SettingsCard(t(Msg.CARD_NOTIFICATIONS)) {
        Text(t(Msg.NOTIFICATIONS_INTRO), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!allowed && on.values.any { it }) {
            Text(t(Msg.NOTIFICATIONS_BLOCKED), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.warning)
            OutlinedButton(onClick = settings.access::openSettings) { Text(t(Msg.NOTIFICATIONS_OPEN_SETTINGS)) }
        }
        kinds.forEach { kind ->
            SwitchRow(t(kind.title), t(kind.hint), on.getValue(kind)) { checked ->
                settings.storage.saveNotificationOn(kind, checked)
                on = on + (kind to checked)
                if (checked && !allowed) settings.access.ask()
            }
        }
    }
}

/** The outcome of "Test connection": [text] is the server version, or the error. */
private class ConnectionResult(val ok: Boolean, val text: String)

/** A setting with an explanation and a switch; the whole row switches, so the label is as good a target as the switch itself. */
@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Related),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One group of settings: a card with its title. */
@Composable
internal fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** The installed version and a manual "Check for updates". */
@Composable
private fun AppVersionSection(updater: UpdateInstaller) {
    val t = LocalStrings.current
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<AppUpdate?>(null) }
    val scope = rememberCoroutineScope()
    SettingsCard(t(Msg.CARD_APP)) {
        Text(t(Msg.VERSION, updater.currentVersion), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(enabled = !checking, onClick = {
            checking = true
            message = null
            scope.launch {
                runCatching { AppUpdates.check(updater.currentVersion) }
                    .onSuccess { if (it == null) message = t(Msg.LATEST_VERSION) else found = it }
                    .onFailure { message = updateCheckFailure(t, it) }
                checking = false
            }
        }) { Text(if (checking) t(Msg.CHECKING) else t(Msg.CHECK_UPDATES)) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    found?.let { update ->
        UpdateDialog(update, updater.currentVersion, onUpdate = { updater.install(update); found = null }, onLater = { found = null })
    }
}
