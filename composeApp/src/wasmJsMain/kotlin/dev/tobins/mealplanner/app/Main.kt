package dev.tobins.mealplanner.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.flow.asFlow

/** Settings in the browser's localStorage, per site. */
private object LocalStorageSettings : SettingsStorage {
    override fun get(key: String): String? = localStorage.getItem(key)

    override fun set(key: String, value: String) = localStorage.setItem(key, value)
}

/** Logs in in the same tab: the login page sends the browser back to the app's address. */
private object SameTabLogin : LoginLauncher {
    override fun redirectUri(serverUrl: String): String = window.location.origin + "/"

    override fun open(url: String) {
        window.location.href = url
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // The web client is served by the server itself, so the API lives at the same address.
    val defaults = ServerSettings(url = window.location.origin)
    // Coming back from the login page: hand the answer to the app and tidy the address bar.
    val callback = window.location.href.takeIf { "code=" in it || "error=" in it }
    if (callback != null) window.history.replaceState(null, "", window.location.pathname)
    ComposeViewport(document.body!!) {
        App(defaults, LocalStorageSettings, SameTabLogin, listOfNotNull(callback).asFlow())
    }
}
