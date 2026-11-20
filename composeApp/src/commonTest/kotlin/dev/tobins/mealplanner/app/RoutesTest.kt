package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.MealSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutesTest {
    private val today = Route.TabRoute(Tab.TODAY)

    private val allRoutes = Tab.entries.map { Route.TabRoute(it) } + listOf(
        Route.Recipe(12), Route.RecipeEditor(12), Route.RecipeEditor(null), Route.Bank,
        Route.Mapping, Route.Profile, Route.Pantry, Route.Offers, Route.Prices, Route.Settings,
        Route.AdjustMeal(MealPlace("2026-11-16", 4, MealSlot.DINNER)),
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

    @Test
    fun anAdjustedMealHasAnAddressOfItsOwnOverThePlan() {
        val place = MealPlace("2026-11-16", 4, MealSlot.DINNER)
        assertEquals("#/meals/2026-11-16/4/dinner", Route.AdjustMeal(place).hash)
        assertEquals(OpenScreens(tab = Tab.PLAN, adjusting = place), Route.AdjustMeal(place).openScreens())
        // Not a meal's place: a day outside the week, an unknown slot, no date, or a part missing.
        for (address in listOf("#/meals/2026-11-16/7/dinner", "#/meals/2026-11-16/4/supper", "#/meals/today/4/dinner", "#/meals/2026-11-16/4")) {
            assertEquals(today, parseRoute(address), address)
        }
    }

    // --- Screens asked for by a tapped notification (Android, issue 225)

    @Test
    fun anOpeningReadsBackFromItsString() {
        val dinner = MealToCook(12, 1.5, 2.25, skipped = false, MealPlace("2026-11-16", 3, MealSlot.DINNER), planned = true)
        val nextWeek = Opening(Route.TabRoute(Tab.PLAN), week = "2026-11-23")
        for (opening in listOf(Opening(Route.TabRoute(Tab.SHOPPING)), Opening(Route.Recipe(12), dinner), Opening(Route.Recipe(12)), nextWeek)) {
            assertEquals(opening, parseOpening(opening.encoded), opening.encoded)
        }
        assertEquals("#/recipes/12|2026-11-16|3|DINNER|1.5|2.25", Opening(Route.Recipe(12), dinner).encoded)
        assertEquals("#/plan|2026-11-23", nextWeek.encoded)
        // A week that is not a Monday's date is no week of the plan: the Plan opens as usual.
        assertEquals(Opening(Route.TabRoute(Tab.PLAN)), parseOpening("#/plan|2026-11-24"))
        assertEquals(Opening(Route.TabRoute(Tab.PLAN)), parseOpening("#/plan|soon"))
        // A meal that cannot be read leaves the recipe to open as written; an unknown route is Today, as an address.
        assertEquals(Opening(Route.Recipe(12)), parseOpening("#/recipes/12|2026-11-16|3|ELEVENSES|1.5|2.25"))
        assertEquals(Opening(today), parseOpening("#/nowhere|x"))
    }

    /** Issue 263: a dinner that makes a prepped batch opens its recipe with the batch, also from a tapped notification. */
    @Test
    fun anOpeningOfAPrepDayReadsBackWithTheDayTheBatchIsMade() {
        val prepDay = Opening(Route.Recipe(12), MealToCook(12, 1.5, 6.0, skipped = false, MealPlace("2026-11-16", 5, MealSlot.DINNER), planned = true, prepMadeOn = "2026-11-21"))
        assertEquals("#/recipes/12|2026-11-16|5|DINNER|1.5|6.0|2026-11-21", prepDay.encoded)
        assertEquals(prepDay, parseOpening(prepDay.encoded))
        // A day that is not a date leaves the recipe to open as written, as any meal that cannot be read does.
        assertEquals(Opening(Route.Recipe(12)), parseOpening("#/recipes/12|2026-11-16|5|DINNER|1.5|6.0|tonight"))
    }

    @Test
    fun anOpeningKeepsWhatHoldsUnsavedTypingInFront() {
        val shopping = Route.TabRoute(Tab.SHOPPING).openScreens()
        assertEquals(shopping, OpenScreens(tab = Tab.RECIPES, recipeId = 4, offers = true, settings = true).opening(shopping), "the rest closes")
        val editing = OpenScreens(tab = Tab.RECIPES, recipeId = 4, editing = 4)
        assertEquals(OpenScreens(tab = Tab.SHOPPING, editing = 4), editing.opening(shopping))
        assertEquals(Route.RecipeEditor(4), editing.opening(shopping).route, "the editor stays in front")
        val profile = OpenScreens(tab = Tab.PROGRESS, profile = true)
        assertEquals(OpenScreens(tab = Tab.TODAY, recipeId = 7, profile = true), profile.opening(Route.Recipe(7).openScreens().copy(tab = Tab.TODAY)))
    }

    @Test
    fun theLastOpeningAskedForIsTakenOnce() {
        val openings = Openings()
        assertNull(openings.take())
        openings.open(Opening(Route.TabRoute(Tab.PLAN)))
        openings.open(Opening(Route.TabRoute(Tab.SHOPPING)))
        assertEquals(Opening(Route.TabRoute(Tab.SHOPPING)), openings.take())
        assertNull(openings.take())
    }
}
