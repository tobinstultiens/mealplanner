package dev.tobins.mealplanner.app

/**
 * Which screen is open, as the address of the page (`#/recipes/12`), so that a reload or a
 * shared link lands on the same screen. Only the browser keeps it (see [RouteStore]); Android
 * navigates as before and never reads or writes a route.
 */
sealed interface Route {
    /** A tab of the bottom bar, with nothing opened over it. */
    data class TabRoute(val tab: Tab) : Route

    data class Recipe(val id: Long) : Route

    /** The recipe editor: [id] is the recipe being edited, null a new recipe. */
    data class RecipeEditor(val id: Long?) : Route

    /** The recipe bank: the cooking-site recipes to add to my recipes from. */
    data object Bank : Route

    data object Mapping : Route
    data object Profile : Route
    data object Pantry : Route
    data object Offers : Route
    data object Prices : Route
    data object Settings : Route

    /** The address fragment of this route, as [parseRoute] reads it back. */
    val hash: String
        get() = "#/" + when (this) {
            is TabRoute -> tab.slug
            is Recipe -> "recipes/$id"
            is RecipeEditor -> if (id == null) "recipes/new" else "recipes/$id/edit"
            Bank -> "recipes/bank"
            Mapping -> "mapping"
            Profile -> "profile"
            Pantry -> "pantry"
            Offers -> "offers"
            Prices -> "offers/prices"
            Settings -> "settings"
        }
}

/** The route of an address fragment; an empty, unknown or malformed one is the Today tab. */
fun parseRoute(hash: String): Route {
    val parts = hash.removePrefix("#").trim('/').split('/')
    val id = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }
    val route = when {
        parts.size == 1 -> Tab.entries.firstOrNull { it.slug == parts[0] }?.let { Route.TabRoute(it) }
            ?: when (parts[0]) {
                "mapping" -> Route.Mapping
                "profile" -> Route.Profile
                "pantry" -> Route.Pantry
                "offers" -> Route.Offers
                "settings" -> Route.Settings
                else -> null
            }
        parts[0] == "recipes" && parts.size == 2 && parts[1] == "new" -> Route.RecipeEditor(null)
        parts == listOf("recipes", "bank") -> Route.Bank
        parts[0] == "recipes" && parts.size == 2 && id != null -> Route.Recipe(id)
        parts[0] == "recipes" && parts.size == 3 && parts[2] == "edit" && id != null -> Route.RecipeEditor(id)
        parts == listOf("offers", "prices") -> Route.Prices
        else -> null
    }
    return route ?: Route.TabRoute(Tab.TODAY)
}

/**
 * Everything that can be open at once: the tab and the screens opened over it, as [App]
 * keeps them. Several can be set together (the editor over a recipe, prices over offers), so
 * that going back lands where it would have before a reload.
 */
data class OpenScreens(
    val tab: Tab = Tab.TODAY,
    val recipeId: Long? = null,
    /** Null: no editor open. -1: a new recipe. Otherwise the recipe being edited. */
    val editing: Long? = null,
    val mapping: Boolean = false,
    val profile: Boolean = false,
    val pantry: Boolean = false,
    val offers: Boolean = false,
    val prices: Boolean = false,
    val settings: Boolean = false,
    /** The recipe bank, opened from the Recipes tab; a recipe opened from it is in front of it. */
    val bank: Boolean = false,
) {
    /** The route of what is on screen: the same order of precedence as the screens are drawn in. */
    val route: Route
        get() = when {
            settings -> Route.Settings
            editing != null -> Route.RecipeEditor(editing.takeIf { it >= 0 })
            prices -> Route.Prices
            offers -> Route.Offers
            pantry -> Route.Pantry
            profile -> Route.Profile
            mapping -> Route.Mapping
            recipeId != null -> Route.Recipe(recipeId)
            bank -> Route.Bank
            else -> Route.TabRoute(tab)
        }
}

/** The screens a route opens, with the tab it was opened from underneath for going back. */
fun Route.openScreens(): OpenScreens = when (this) {
    is Route.TabRoute -> OpenScreens(tab = tab)
    is Route.Recipe -> OpenScreens(tab = Tab.RECIPES, recipeId = id)
    is Route.RecipeEditor -> OpenScreens(tab = Tab.RECIPES, recipeId = id, editing = id ?: -1)
    Route.Bank -> OpenScreens(tab = Tab.RECIPES, bank = true)
    Route.Mapping -> OpenScreens(tab = Tab.RECIPES, mapping = true)
    Route.Profile -> OpenScreens(tab = Tab.PROGRESS, profile = true)
    Route.Pantry -> OpenScreens(tab = Tab.SHOPPING, pantry = true)
    Route.Offers -> OpenScreens(tab = Tab.SHOPPING, offers = true)
    Route.Prices -> OpenScreens(tab = Tab.SHOPPING, offers = true, prices = true)
    Route.Settings -> OpenScreens(settings = true)
}

/**
 * The fragment to show in the address while [screens] is open, or null to leave the address
 * alone: without a login every screen is replaced by the login in Settings, and the address
 * keeps the route asked for so that it is still there once logged in.
 */
fun addressFor(screens: OpenScreens, loggedIn: Boolean): String? = screens.route.hash.takeIf { loggedIn }

/** Where the browser keeps the open screen; Android has none. */
interface RouteStore {
    /** The address fragment now, `#/recipes/12` or empty. */
    fun read(): String

    /** Shows [hash] in the address without adding a history entry. */
    fun write(hash: String)
}

/** For platforms without addresses: the app always starts on Today. */
object NoRoutes : RouteStore {
    override fun read(): String = ""

    override fun write(hash: String) = Unit
}
