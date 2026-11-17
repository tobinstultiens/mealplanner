package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.ktor.client.engine.js.JsError
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.io.IOException
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

/**
 * The page is in front while its tab shows. The browser's `visibilitychange` goes straight to the
 * [AppFront], not through the composition: a hidden tab draws no frames, so the composition would
 * see the change only once the tab showed again, when there was no change left to see (#200).
 */
@Composable
internal actual fun rememberAppFront(): AppFront {
    val front = remember { AppFront(pageVisible()) }
    DisposableEffect(front) { onDispose(watchPageVisibility(front::report)) }
    return front
}

/**
 * Calls [onChange] with whether the page shows, at every `visibilitychange` and once now (it may
 * have changed since the caller last looked); the event comes whether or not a frame is drawn.
 * Returns what stops it.
 */
internal fun watchPageVisibility(onChange: (Boolean) -> Unit): () -> Unit {
    val listener: (Event) -> Unit = { onChange(pageVisible()) }
    document.addEventListener("visibilitychange", listener)
    onChange(pageVisible())
    return { document.removeEventListener("visibilitychange", listener) }
}

/** The browser keeps its own cache and sets the User-Agent itself, so there is nothing to configure. */
actual fun productImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
    coil3.ImageLoader.Builder(context)
        .components { add(coil3.network.ktor3.KtorNetworkFetcherFactory(io.ktor.client.HttpClient())) }
        .build()

actual fun runsInBrowser(): Boolean = true

/**
 * The browser rejects a fetch when no answer came (no connection, a refused or reset one), and
 * Ktor passes that on as a [JsError]: wrapped in `Error("Fail to fetch")` when the request failed, on
 * its own when reading the body did. The text keeps both, for "Details".
 */
internal actual fun connectionFailure(cause: Throwable): IOException? {
    val chain = generateSequence(cause) { it.cause }.toList()
    if (chain.none { it is JsError }) return null
    return IOException(chain.mapNotNull { it.message }.distinct().joinToString(": "), cause)
}
