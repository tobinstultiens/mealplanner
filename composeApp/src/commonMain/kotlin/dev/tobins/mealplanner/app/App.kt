package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
        // NEVO must be credited wherever its values are shown.
        var nutritionSource by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(api) {
            nutritionSource = try { api.foodDataset().reference } catch (e: Exception) { null }
        }
        var mapping by rememberSaveable { mutableStateOf(false) }
        // Null: no editor open. -1: a new recipe. Otherwise the id of the recipe being edited.
        var editing by rememberSaveable { mutableStateOf<Long?>(null) }
        var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
        var profileOpen by rememberSaveable { mutableStateOf(false) }
        var pantryOpen by rememberSaveable { mutableStateOf(false) }
        var offersOpen by rememberSaveable { mutableStateOf(false) }
        val recipeId = openRecipeId
        val editingId = editing
        when {
            editingId != null -> {
                BackHandler(enabled = true) { editing = null }
                RecipeEditorScreen(
                    api = api,
                    recipeId = editingId.takeIf { it >= 0 },
                    onDone = { savedId ->
                        editing = null
                        openRecipeId = savedId
                    },
                    onCancel = { editing = null },
                )
            }
            offersOpen -> {
                BackHandler(enabled = true) { offersOpen = false }
                OffersScreen(api, onBack = { offersOpen = false })
            }
            pantryOpen -> {
                BackHandler(enabled = true) { pantryOpen = false }
                PantryScreen(api, onBack = { pantryOpen = false })
            }
            profileOpen -> {
                BackHandler(enabled = true) { profileOpen = false }
                ProfileScreen(api, onBack = { profileOpen = false })
            }
            mapping -> {
                BackHandler(enabled = true) { mapping = false }
                MappingScreen(api, onBack = { mapping = false })
            }
            recipeId != null -> {
                BackHandler(enabled = true) { openRecipeId = null }
                RecipeDetailScreen(api, recipeId, nutritionSource, onBack = { openRecipeId = null }, onEdit = { editing = recipeId })
            }
            else -> Scaffold(
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = tab == entry,
                                onClick = { tab = entry },
                                icon = { Text(entry.symbol) },
                                label = { Text(entry.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                // Each screen has its own top bar; only the bottom bar's space is reserved here.
                // Only the bottom inset is consumed: the bottom bar already covers the navigation
                // area, while each screen's own top bar still has to clear the status bar.
                val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
                Box(Modifier.padding(bottom).consumeWindowInsets(bottom)) {
                    when (tab) {
                        Tab.TODAY -> TodayScreen(api, onOpenRecipe = { openRecipeId = it }, onOpenPlan = { tab = Tab.PLAN })
                        Tab.PLAN -> PlanScreen(api, onOpenRecipe = { openRecipeId = it }, onOpenProfile = { profileOpen = true })
                        Tab.SHOPPING -> ShoppingScreen(api, onOpenPantry = { pantryOpen = true }, onOpenOffers = { offersOpen = true })
                        Tab.RECIPES -> RecipeListScreen(
                            api,
                            onOpen = { openRecipeId = it },
                            onOpenMapping = { mapping = true },
                            onNewRecipe = { editing = -1 },
                        )
                        Tab.PROGRESS -> ProgressScreen(api, onOpenProfile = { profileOpen = true })
                    }
                }
            }
        }
    }
}

private enum class Tab(val label: String, val symbol: String) {
    TODAY("Today", "☀"),
    PLAN("Plan", "▦"),
    SHOPPING("Shopping", "✓"),
    RECIPES("Recipes", "☰"),
    PROGRESS("Progress", "◉"),
}
