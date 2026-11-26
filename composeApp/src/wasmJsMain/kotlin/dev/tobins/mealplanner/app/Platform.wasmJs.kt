package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.ktor.client.engine.js.JsError
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.io.IOException
import org.w3c.dom.events.Event

/** A [BackHandler] that is on, with the order it was composed in: a handler inside another's screen is composed after it. */
private class BackEntry(val order: Int, val onBack: State<() -> Unit>)

private val backEntries = mutableListOf<BackEntry>()
private var nextBackOrder = 0
private var backListening = false

/**
 * One `popstate` listener for all handlers: a back goes to the innermost one (the last composed) only, so that
 * leaving a selection does not also close the screen it is on. Started with the page, since a Back after a
 * reload has no handler yet and must still be answered.
 */
internal fun listenForBack() {
    if (backListening) return
    backListening = true
    window.addEventListener("popstate") {
        // A typed address is not a back; [watchAddress] reports it.
        if (!onAppEntry()) return@addEventListener
        steppedToEntry {
            val handler = backEntries.maxByOrNull { it.order }?.onBack?.value
            handler?.invoke()
            handler != null
        }
    }
}

/**
 * The browser's back button closes the open screen: while [enabled], a history entry is
 * added and going back from it calls [onBack]. When several are enabled (a selection on a
 * screen), only the innermost is called. When the screen is closed from inside the
 * app instead, the entry stays; going back over it later does nothing visible. A change of
 * [rearm] adds a new entry, for a screen that stayed open after a back.
 */
@Composable
actual fun BackHandler(enabled: Boolean, rearm: Int, onBack: () -> Unit) {
    val currentOnBack = rememberUpdatedState(onBack)
    // Composition runs outside in, so this tells which handler is inside which.
    val order = remember { nextBackOrder++ }
    DisposableEffect(enabled, rearm) {
        if (!enabled) return@DisposableEffect onDispose {}
        pushBackEntry()
        val entry = BackEntry(order, currentOnBack)
        backEntries += entry
        onDispose { backEntries -= entry }
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

/**
 * Asks for the Screen Wake Lock and hands back what gives it up. The browser drops the lock when the
 * tab is hidden, so it is asked for again when the tab shows. A browser without the API (or one that
 * refuses) leaves the screen to its own timeout; the call then does nothing.
 */
@JsFun(
    "() => { if (!navigator.wakeLock) return () => {}; let lock = null, released = false;" +
        " const take = () => navigator.wakeLock.request('screen').then(l => { if (released) l.release(); else lock = l; }).catch(() => {});" +
        " const again = () => { if (document.visibilityState === 'visible' && !released) take(); };" +
        " document.addEventListener('visibilitychange', again); take();" +
        " return () => { released = true; document.removeEventListener('visibilitychange', again); if (lock) lock.release().catch(() => {}); }; }",
)
internal external fun holdWakeLock(): JsAny

@JsFun("(release) => release()")
internal external fun giveUpWakeLock(release: JsAny)

/** Holds the browser's wake lock while [enabled]; without the API the browser decides when to lock the screen. */
@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val release = holdWakeLock()
        onDispose { giveUpWakeLock(release) }
    }
}

/** The page has no window of its own to resize for a keyboard; a phone's browser makes room itself. */
@Composable
actual fun KeyboardMakesRoom() = Unit

internal actual fun fromOnScreenKeyboard(event: androidx.compose.ui.input.key.KeyEvent): Boolean = false

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

/** A text field's editable element has the field's left, right and bottom edges (its top leaves room for the label), within a few pixels. */
@JsFun(
    """(left, right, bottom) => {
        const host = Array.from(document.body.querySelectorAll('*')).find((element) => element.shadowRoot);
        if (!host) return false;
        const scale = window.devicePixelRatio;
        return Array.from(host.shadowRoot.querySelectorAll('[role=textbox]')).some((box) => {
            const r = box.getBoundingClientRect();
            return Math.abs(r.left * scale - left) < 4 && Math.abs(r.right * scale - right) < 4 && Math.abs(r.bottom * scale - bottom) < 4;
        });
    }""",
)
internal actual external fun isTextField(left: Float, right: Float, bottom: Float): Boolean

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

/** The browser has no scanner: the barcode is typed. */
@Composable
actual fun rememberBarcodeScanner(): BarcodeScanner? = null
