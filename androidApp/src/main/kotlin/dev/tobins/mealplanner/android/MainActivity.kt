package dev.tobins.mealplanner.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.tobins.mealplanner.app.App
import dev.tobins.mealplanner.app.ServerSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App(ServerSettings(BuildConfig.SERVER_URL, BuildConfig.API_TOKEN)) }
    }
}
