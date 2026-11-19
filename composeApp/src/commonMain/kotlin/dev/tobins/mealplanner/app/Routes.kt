package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.MealSlot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Which screen is open, as the address of the page (`#/recipes/12`), so that a reload or a
 * shared link lands on the same screen. Only the browser keeps it (see [RouteStore]); Android
 * navigates as before and never writes a route, but opens one a tapped notification brings ([Opening]).
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

    /** What was in a planned meal (issue 226), by where it is in the plan. */
    data class AdjustMeal(val place: MealPlace) : Route

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
            is AdjustMeal -> "meals/${place.weekStart}/${place.day}/${place.slot.name.lowercase()}"
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
        parts[0] == "meals" && parts.size == 4 -> mealPlace(parts[1], parts[2], parts[3])?.let { Route.AdjustMeal(it) }
        else -> null
    }
    return route ?: Route.TabRoute(Tab.TODAY)
}

/** A meal's place as the address writes it (`2026-11-16`, `0`, `dinner`); null when any part is not one. */
private fun mealPlace(week: String, day: String, slot: String): MealPlace? {
    if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(week)) return null
    val index = day.toIntOrNull()?.takeIf { it in 0..6 } ?: return null
    return MealSlot.entries.firstOrNull { it.name.equals(slot, ignoreCase = true) }?.let { MealPlace(week, index, it) }
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
    /** The meal whose ingredients are being adjusted, opened from Today or the Plan. */
    val adjusting: MealPlace? = null,
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
            adjusting != null -> Route.AdjustMeal(adjusting)
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
    // Opened from Today or the Plan; the address does not say which, and the Plan has the meal's week.
    is Route.AdjustMeal -> OpenScreens(tab = Tab.PLAN, adjusting = place)
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

/**
 * A screen asked for from outside the app: a tapped notification (Android). [meal] is the planned meal a
 * recipe is opened from, so that it shows the servings to cook, as when it is opened from Today; [week] is
 * the week (its Monday) the Plan shows.
 */
data class Opening(val route: Route, val meal: MealToCook? = null, val week: String? = null) {
    /**
     * As one string, for an Android intent's extra: the route's address, and after it a [meal]'s place and
     * servings (`#/recipes/12|2026-11-16|3|DINNER|1.5|2.25`) or the [week] (`#/plan|2026-11-23`).
     * [parseOpening] reads it back.
     */
    val encoded: String
        get() = route.hash +
            (meal?.place?.let { "|${it.weekStart}|${it.day}|${it.slot.name}|${meal.portions}|${meal.cookPortions}" } ?: "") +
            (week?.let { "|$it" } ?: "")
}

/**
 * The [Opening] of an [Opening.encoded] string; a meal or week that cannot be read is left out, so the recipe
 * opens as written and the Plan on this week.
 */
fun parseOpening(text: String): Opening {
    val parts = text.split('|')
    val route = parseRoute(parts.first())
    if (route == Route.TabRoute(Tab.PLAN) && parts.size == 2) {
        return Opening(route, week = parts[1].takeIf { runCatching { mondayOf(it) == it }.getOrDefault(false) })
    }
    val recipe = route as? Route.Recipe ?: return Opening(route)
    if (parts.size != 6) return Opening(route)
    val meal = runCatching {
        val place = MealPlace(parts[1], parts[2].toInt(), MealSlot.valueOf(parts[3]))
        MealToCook(recipe.id, parts[4].toDouble(), parts[5].toDouble(), skipped = false, place = place, planned = true)
    }.getOrNull()
    return Opening(route, meal)
}

/**
 * What is open once [target] was asked for from outside the app: its screens, with the recipe editor and
 * the profile kept in front where they were open, since they hold what was typed and not saved yet; leaving
 * them shows [target]. The settings close, as on any route.
 */
fun OpenScreens.opening(target: OpenScreens): OpenScreens = target.copy(editing = editing ?: target.editing, profile = profile || target.profile)

/**
 * Screens asked for from outside the app while it starts or runs: a tapped notification on Android. The
 * platform keeps one as long as its process and hands it each; [App] takes the one waiting when it starts,
 * so that the first screen drawn is the one asked for, and the later ones as they come. Only the last
 * one asked for counts.
 */
class Openings {
    private val asked = Channel<Opening>(Channel.CONFLATED)

    fun open(opening: Opening) {
        asked.trySend(opening)
    }

    /** The one waiting, taken; null when none is. */
    fun take(): Opening? = asked.tryReceive().getOrNull()

    /** Each one from now on, taken as it comes. */
    val requests: Flow<Opening> = asked.receiveAsFlow()
}
