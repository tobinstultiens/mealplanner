package dev.tobins.mealplanner.app

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.PlatformContext
import kotlinx.io.IOException

/** Runs [onBack] instead of the platform's default back action while [enabled]. */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)

/** Makes the status and navigation bar icons readable on a [dark] or light app. */
@Composable
expect fun SystemBars(dark: Boolean)

/** Cryptographically secure random bytes, for the login's PKCE secret and state. */
expect fun secureRandomBytes(size: Int): ByteArray

/** The device's locale as a BCP-47 tag (`nl-NL`), for [FormatLocale]. */
expect fun deviceLocaleTag(): String

/** Whether the device can take the colour scheme from the wallpaper (Android 12 and up). */
expect fun systemColorsAvailable(): Boolean

/** The wallpaper-based colour scheme, or null where [systemColorsAvailable] is false. */
@Composable
expect fun systemColorScheme(dark: Boolean): ColorScheme?

/**
 * Keeps the screen from dimming and locking while [enabled] and this call is part of the
 * composition; leaving the composition (or the app) restores the normal behaviour. The browser
 * does nothing.
 */
@Composable
expect fun KeepScreenOn(enabled: Boolean)

/**
 * Whether the app is in front: on screen, not behind another app (Android) or in a hidden browser
 * tab. The shopping list keeps itself current only then, and is fetched again when it comes back.
 * The platform keeps the [AppFront] current for as long as this call is part of the composition.
 */
@Composable
internal expect fun rememberAppFront(): AppFront

/**
 * The loader of product pictures. It sends its own User-Agent (Jumbo resets connections that
 * carry OkHttp's default), keeps pictures on disk where the platform has room for that, and
 * ignores the stores' cache headers: AH's bonus pictures say `max-age=0`, yet the shopping list
 * should show them offline in the shop. Recipe photos from our own server use it too, with the
 * login token added per request (see [RecipeImages]).
 */
expect fun productImageLoader(context: PlatformContext): ImageLoader

/** True in the browser, where a picture can only be drawn when its host sends CORS headers. */
expect fun runsInBrowser(): Boolean

/**
 * [cause] as an [IOException], when it is this platform's HTTP engine saying that a request never
 * reached the server or was cut off; null for anything else. The app catches `Exception`s and counts
 * an [IOException] as a lost connection (see [load]); OkHttp throws those already, but Ktor's browser
 * engine throws a `kotlin.Error`, which is no `Exception`, so on the web it went past every catch (#200).
 */
internal expect fun connectionFailure(cause: Throwable): IOException?
