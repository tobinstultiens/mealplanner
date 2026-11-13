package dev.tobins.mealplanner.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window

/** Settings in the browser's localStorage, per site. */
private object LocalStorageSettings : SettingsStorage {
    override fun get(key: String): String? = localStorage.getItem(key)

    override fun set(key: String, value: String) = localStorage.setItem(key, value)
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // The web client is served by the server itself, so the API lives at the same address.
    val defaults = ServerSettings(url = window.location.origin, token = "")
    ComposeViewport(document.body!!) {
        App(defaults, LocalStorageSettings)
    }
}
