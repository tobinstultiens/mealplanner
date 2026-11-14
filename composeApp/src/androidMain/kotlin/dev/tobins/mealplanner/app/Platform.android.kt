package dev.tobins.mealplanner.app

import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable

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
