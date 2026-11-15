package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
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

/** The browser has no system bars to adjust; the page is drawn entirely by the app. */
@Composable
actual fun SystemBars(dark: Boolean) {}

@JsFun("(n) => { const a = new Uint8Array(n); crypto.getRandomValues(a); return Array.from(a).join(','); }")
private external fun randomBytesCsv(size: Int): String

actual fun secureRandomBytes(size: Int): ByteArray = randomBytesCsv(size).split(',').map { it.toInt().toByte() }.toByteArray()

/** The browser's preferred language, e.g. `nl-NL`. */
actual fun deviceLocaleTag(): String = window.navigator.language

/** The browser has no wallpaper colours. */
actual fun systemColorsAvailable(): Boolean = false

@Composable
actual fun systemColorScheme(dark: Boolean): androidx.compose.material3.ColorScheme? = null

/** The browser decides when to lock the screen. */
@Composable
actual fun KeepScreenOn(enabled: Boolean) {}

@JsFun("() => document.visibilityState === 'visible'")
private external fun pageVisible(): Boolean

/** The page is in front while its tab shows; the browser says when that changes (`visibilitychange`). */
@Composable
actual fun appInFront(): Boolean {
    val visible = remember { mutableStateOf(pageVisible()) }
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { visible.value = pageVisible() }
        document.addEventListener("visibilitychange", listener)
        onDispose { document.removeEventListener("visibilitychange", listener) }
    }
    return visible.value
}

/** The browser keeps its own cache and sets the User-Agent itself, so there is nothing to configure. */
actual fun productImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
    coil3.ImageLoader.Builder(context)
        .components { add(coil3.network.ktor3.KtorNetworkFetcherFactory(io.ktor.client.HttpClient())) }
        .build()

actual fun runsInBrowser(): Boolean = true
