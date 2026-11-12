package dev.tobins.mealplanner.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.tobins.mealplanner.app.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 10.0.2.2 is the host machine as seen from the Android emulator.
        setContent { App(defaultServerUrl = "http://10.0.2.2:8080") }
    }
}
