package dev.tobins.mealplanner.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.tobins.mealplanner.app.App
import dev.tobins.mealplanner.app.ServerSettings
import dev.tobins.mealplanner.app.SettingsStorage

/** Settings in the app's private SharedPreferences. */
private class PreferencesStorage(private val prefs: SharedPreferences) : SettingsStorage {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val storage = PreferencesStorage(getSharedPreferences("settings", Context.MODE_PRIVATE))
        // Debug builds are signed with another key, so a release could not install over them.
        val updater = if (BuildConfig.DEBUG) null else AndroidUpdateInstaller(this)
        if (updater != null) {
            UpdateCheckWorker.schedule(this)
            askForNotifications()
        }
        setContent { App(ServerSettings(BuildConfig.SERVER_URL, BuildConfig.API_TOKEN), storage, updater) }
    }

    /** Android 13+ needs consent for the "update available" notification; asked once. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (prefs.getBoolean("askedForNotifications", false)) return
        prefs.edit().putBoolean("askedForNotifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
