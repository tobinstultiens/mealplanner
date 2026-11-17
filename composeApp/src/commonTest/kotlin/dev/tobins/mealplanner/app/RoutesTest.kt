package dev.tobins.mealplanner.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutesTest {
    private val today = Route.TabRoute(Tab.TODAY)

    private val allRoutes = Tab.entries.map { Route.TabRoute(it) } + listOf(
        Route.Recipe(12), Route.RecipeEditor(12), Route.RecipeEditor(null), Route.Bank,
        Route.Mapping, Route.Profile, Route.Pantry, Route.Offers, Route.Prices, Route.Settings,
    )

    @Test
    fun everyRouteReadsBackFromItsAddress() {
        allRoutes.forEach { assertEquals(it, parseRoute(it.hash), it.hash) }
        assertEquals("#/recipes/12", Route.Recipe(12).hash)
        assertEquals("#/recipes/bank", Route.Bank.hash)
    }

    @Test
    fun theBankIsOpenedOverTheRecipesTabAndARecipeOpenedFromItIsInFrontOfIt() {
        assertEquals(OpenScreens(tab = Tab.RECIPES, bank = true), Route.Bank.openScreens())
        // A recipe opened from the bank, and the linking screen opened from either, are drawn above it.
        assertEquals(Route.Recipe(8), OpenScreens(tab = Tab.RECIPES, recipeId = 8, bank = true).route)
        assertEquals(Route.Mapping, OpenScreens(tab = Tab.RECIPES, mapping = true, bank = true).route)
        assertEquals(Route.Bank, OpenScreens(tab = Tab.RECIPES, bank = true).route)
        assertEquals("#/recipes/bank", addressFor(OpenScreens(tab = Tab.RECIPES, bank = true), loggedIn = true))
        // `bank` is no recipe number, and a recipe number is no bank.
        assertEquals(Route.Recipe(7), parseRoute("#/recipes/7"))
        assertEquals(today, parseRoute("#/recipes/bank/extra"))
    }

    @Test
    fun anEmptyUnknownOrMalformedAddressIsToday() {
        listOf(
            "", "#", "#/", "#/nowhere", "#/recipes/abc", "#/recipes/-3", "#/recipes/12/delete",
            "#/recipes/12/edit/more", "#/today/extra", "#/offers/prices/x", "recipes/12/%", "#//", "#/recipes/99999999999999999999",
        ).forEach { assertEquals(today, parseRoute(it), it) }
    }

    @Test
    fun aTrailingSlashAndAMissingHashAreTolerated() {
        assertEquals(Route.TabRoute(Tab.RECIPES), parseRoute("#/recipes"))
        assertEquals(Route.Recipe(7), parseRoute("#/recipes/7/"))
        assertEquals(Route.Recipe(7), parseRoute("/recipes/7"))
        assertEquals(Route.TabRoute(Tab.PLAN), parseRoute("plan"))
    }

    @Test
    fun aRouteOpensTheScreensThatShowItAndShowsAsTheSameRoute() {
        allRoutes.forEach { assertEquals(it, it.openScreens().route, it.hash) }
    }

    @Test
    fun goingBackFromAReloadedScreenFindsTheScreenItWasOpenedFrom() {
        // The editor sits over its recipe, and prices over offers, as when opened by hand.
        assertEquals(12L, Route.RecipeEditor(12).openScreens().recipeId)
        assertEquals(true, Route.Prices.openScreens().offers)
        assertNull(Route.RecipeEditor(null).openScreens().recipeId)
        assertEquals(-1L, Route.RecipeEditor(null).openScreens().editing)
        assertEquals(Tab.SHOPPING, Route.Offers.openScreens().tab)
    }

    @Test
    fun theAddressFollowsTheOpenScreenWhileLoggedIn() {
        assertEquals("#/recipes/5", addressFor(OpenScreens(tab = Tab.PLAN, recipeId = 5), loggedIn = true))
        assertEquals("#/plan", addressFor(OpenScreens(tab = Tab.PLAN), loggedIn = true))
        // Settings is on top of everything else that is open.
        assertEquals("#/settings", addressFor(OpenScreens(recipeId = 5, settings = true), loggedIn = true))
    }

    @Test
    fun withoutALoginTheAddressIsLeftAloneSoTheRouteSurvivesTheLogin() {
        // Not logged in the app shows the login whatever the address says; the address must survive for later.
        assertNull(addressFor(OpenScreens(settings = true), loggedIn = false))
        assertNull(addressFor(OpenScreens(recipeId = 5), loggedIn = false))
        // The route asked for is still read as that route, to be shown after logging in.
        assertEquals(Route.Recipe(5), parseRoute("#/recipes/5"))
    }

    @Test
    fun androidHasNoAddressAndStartsOnToday() {
        assertEquals(today, parseRoute(NoRoutes.read()))
        NoRoutes.write("#/plan")
        assertEquals("", NoRoutes.read())
    }
}
