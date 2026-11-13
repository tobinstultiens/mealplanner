package dev.tobins.mealplanner.android

import android.content.Context
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
        setContent { App(ServerSettings(BuildConfig.SERVER_URL, BuildConfig.API_TOKEN), storage) }
    }
}
