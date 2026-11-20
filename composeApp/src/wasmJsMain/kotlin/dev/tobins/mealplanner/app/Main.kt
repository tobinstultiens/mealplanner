package dev.tobins.mealplanner.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import org.w3c.dom.events.Event

/** Settings in the browser's localStorage, per site. */
private object LocalStorageSettings : SettingsStorage {
    override fun get(key: String): String? = localStorage.getItem(key)

    override fun set(key: String, value: String) = localStorage.setItem(key, value)

    override fun remove(key: String) = localStorage.removeItem(key)

    // The browser tells a window only of changes made by the other windows of the site.
    override fun watch(onChange: () -> Unit): () -> Unit {
        val listener: (Event) -> Unit = { onChange() }
        window.addEventListener("storage", listener)
        return { window.removeEventListener("storage", listener) }
    }
}

private const val ROUTE_AFTER_LOGIN = "route.afterLogin"

/** Logs in in the same tab: the login page sends the browser back to the app's address. */
private object SameTabLogin : LoginLauncher {
    override fun redirectUri(serverUrl: String): String = window.location.origin + "/"

    override fun open(url: String) {
        // The trip through the login page drops the fragment; the app restores it on the way back.
        window.sessionStorage.setItem(ROUTE_AFTER_LOGIN, window.location.hash)
        window.location.href = url
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    installCrashPage { Strings.of(LocalStorageSettings.appLanguage(), deviceLocaleTag()).language }
    // The web client is served by the server itself, so the API lives at the same address.
    val defaults = ServerSettings(url = window.location.origin)
    // Lives as long as the page, which a login leaves and comes back to.
    val logins = LoginExchange(LocalStorageSettings, MainScope())
    // Coming back from the login page: hand the answer over, before the app could open the login page
    // by itself (a cancelled or failed login must show the login screen, not go round again), and tidy the address bar.
    val callback = window.location.href.takeIf { "code=" in it || "error=" in it }
    if (callback != null) {
        logins.deliver(callback)
        val route = window.sessionStorage.getItem(ROUTE_AFTER_LOGIN).orEmpty()
        window.sessionStorage.removeItem(ROUTE_AFTER_LOGIN)
        window.history.replaceState(null, "", window.location.pathname + route)
    }
    claimStartEntry()
    forwardWheelFromMargins(window, ContentColumnWidthDp)
    ComposeViewport(document.body!!) {
        App(defaults, LocalStorageSettings, SameTabLogin, logins, routes = AddressRoutes)
    }
}
