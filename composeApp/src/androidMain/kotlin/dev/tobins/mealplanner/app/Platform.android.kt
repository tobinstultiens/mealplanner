package dev.tobins.mealplanner.app

import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled, onBack)

@Composable
actual fun SystemBars(dark: Boolean) {
    val activity = androidx.activity.compose.LocalActivity.current as? androidx.activity.ComponentActivity ?: return
    androidx.compose.runtime.DisposableEffect(activity, dark) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) {
            androidx.activity.SystemBarStyle.dark(transparent)
        } else {
            androidx.activity.SystemBarStyle.light(transparent, transparent)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose {}
    }
}

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { java.security.SecureRandom().nextBytes(it) }

actual fun deviceLocaleTag(): String = java.util.Locale.getDefault().toLanguageTag()

actual fun systemColorsAvailable(): Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

@Composable
actual fun systemColorScheme(dark: Boolean): androidx.compose.material3.ColorScheme? {
    if (!systemColorsAvailable()) return null
    val context = androidx.compose.ui.platform.LocalContext.current
    return if (dark) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
}

@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    val window = androidx.activity.compose.LocalActivity.current?.window ?: return
    androidx.compose.runtime.DisposableEffect(window, enabled) {
        if (enabled) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * Started means visible, also in split screen or behind a dialog; stopped means behind another app or
 * the screen is off. Read through the composition as before: Android recomposes on a lifecycle change.
 */
@Composable
internal actual fun rememberAppFront(): AppFront {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inFront = state.isAtLeast(Lifecycle.State.STARTED)
    val front = remember { AppFront(inFront) }
    SideEffect { front.report(inFront) }
    return front
}

actual fun productImageLoader(context: coil3.PlatformContext): coil3.ImageLoader {
    val client = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
        install(io.ktor.client.plugins.UserAgent) { agent = "MealPlanner (Android)" }
    }
    return coil3.ImageLoader.Builder(context)
        .components { add(coil3.network.ktor3.KtorNetworkFetcherFactory(client)) }
        .diskCache {
            coil3.disk.DiskCache.Builder()
                .directory(with(okio.Path) { context.cacheDir.resolve("product_pictures").absolutePath.toPath() })
                .maxSizeBytes(50L * 1024 * 1024)
                .build()
        }
        .build()
}

actual fun runsInBrowser(): Boolean = false

/** OkHttp reports a failed connection as an IOException already. */
internal actual fun connectionFailure(cause: Throwable): kotlinx.io.IOException? = null
