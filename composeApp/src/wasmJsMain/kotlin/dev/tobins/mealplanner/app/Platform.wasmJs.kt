package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * The browser's back button closes the open screen: while [enabled], a history entry is
 * added and going back from it calls [onBack]. When the screen is closed from inside the
 * app instead, the entry stays; going back over it later does nothing visible.
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val currentOnBack = rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        window.history.pushState(null, "")
        val listener: (Event) -> Unit = { currentOnBack.value() }
        window.addEventListener("popstate", listener)
        onDispose { window.removeEventListener("popstate", listener) }
    }
}
