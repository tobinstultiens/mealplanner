package dev.tobins.mealplanner.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Root of the app. For now it is the recipe browser: a list and a detail screen.
 * The other sections (Today, Plan, Shopping, Progress) arrive in later phases.
 */
@Composable
fun App(settings: ServerSettings) {
    val api = remember(settings) { ApiClient(settings) }
    DisposableEffect(api) { onDispose { api.close() } }

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        var openRecipeId by rememberSaveable { mutableStateOf<Long?>(null) }
        val recipeId = openRecipeId
        if (recipeId == null) {
            RecipeListScreen(api, onOpen = { openRecipeId = it })
        } else {
            BackHandler(enabled = true) { openRecipeId = null }
            RecipeDetailScreen(api, recipeId, onBack = { openRecipeId = null })
        }
    }
}
