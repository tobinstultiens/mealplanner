package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.DinnerPick
import dev.tobins.mealplanner.api.DinnerSuggestion
import dev.tobins.mealplanner.api.PackRestUse
import dev.tobins.mealplanner.api.PlanningTotals
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.SuggestionGround
import dev.tobins.mealplanner.api.SuggestionOffer
import dev.tobins.mealplanner.api.WeekPlanning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the planning screen says (issue 255): a suggestion's reasons, price and numbers, the strip, the dinners left. */
class PlanningTextTest {
    private val english = Strings.English

    /** [this] with the no-break spaces that keep a number and its unit together ([unbrokenParts]) as plain ones. */
    private fun String.plain() = replace('\u00A0', ' ')
    private val dutch = Strings.Dutch

    private fun suggestion(vararg grounds: SuggestionGround) =
        DinnerSuggestion(1, "Chicken traybake", grounds.toList(), 1.25, 812.5, 51.0, 6.0, costPerServing = 2.29, savingPerServing = 0.64)

    @Test
    fun eachGroundIsALineThatSaysWhy() {
        val all = suggestion(SuggestionGround.ON_OFFER, SuggestionGround.USES_WHAT_IS_THERE, SuggestionGround.FAVOURITE, SuggestionGround.NOT_TRIED_YET).copy(
            offers = listOf(SuggestionOffer("Chicken thigh fillet", Store.AH, "AH Kippendij", "25% korting", 0.5), SuggestionOffer("Lime", Store.LIDL, "Limoen", null, 0.14)),
            onHand = listOf("Feta"),
            packRests = listOf(PackRestUse("Spinach", "Lentil soup")),
            rating = 5,
            weeksSinceEaten = 4,
        )

        assertEquals(
            listOf(
                "On offer: chicken thigh fillet at AH (25% korting), lime at Lidl",
                "Uses what is there: feta, the rest of the spinach from Lentil soup",
                "Favourite: rated 5 of 5 · not eaten for 4 weeks",
                "Not tried yet",
            ),
            all.reasons(english),
        )
        assertEquals(
            listOf(
                "In de aanbieding: chicken thigh fillet bij AH (25% korting), lime bij Lidl",
                "Gebruikt wat er is: feta, de rest spinach van Lentil soup",
                "Favoriet: 5 van 5 sterren · 4 weken niet gegeten",
                "Nog niet geprobeerd",
            ),
            all.reasons(dutch),
        )
        assertEquals(listOf("Favourite: not eaten for 1 week"), suggestion(SuggestionGround.FAVOURITE).copy(weeksSinceEaten = 1).reasons(english))
    }

    @Test
    fun thePriceIsAServingsWithTheSavingWhenThereIsOne() {
        assertEquals("€2.29 a serving, €0.64 saved", suggestion().priceText(english))
        assertEquals("€2,29 per portie, €0,64 bespaard", suggestion().priceText(dutch))
        assertEquals("€2.29 a serving", suggestion().copy(savingPerServing = 0.0).priceText(english))
        assertNull(suggestion().copy(costPerServing = null).priceText(english))
        assertEquals("1.25 servings · 813 kcal · 51 g protein · 6 g fibre", suggestion().numbersText(english).plain())
    }

    private fun week(open: Int, chosen: Int, totals: PlanningTotals = PlanningTotals(0, null, null, 0.0, 0.0, 0.0, 0.0, 0.0)) = WeekPlanning(
        "2026-11-16", DailyTargets(2100, 130, 30), (0 until open).toList(),
        (1..chosen).map { DinnerPick(it.toLong(), "Dinner $it", 1.25, 800.0, 50.0) }, emptyList(), totals,
    )

    @Test
    fun theDinnersLeftToChooseAndWhetherTheWeekIsDoneOrToFill() {
        assertEquals("3 dinners still to choose", week(5, 2).toChooseText(english))
        assertEquals("Nog 1 avondmaaltijd te kiezen", week(5, 4).toChooseText(dutch))
        assertEquals("A dinner is chosen for every day that is open.", week(5, 5).toChooseText(english))
        assertEquals("Every day from today on has a dinner that stays.", week(0, 0).toChooseText(english))
        assertEquals(listOf(false, true, true), listOf(week(5, 4), week(5, 5), week(0, 0)).map { it.complete })
    }

    @Test
    fun theStripSaysWhatTheWeekCostsAndGivesAgainstWhatItShouldWithoutWarning() {
        val totals = PlanningTotals(7, 21.4, 24.0, 5330.2, 325.4, 44.0, 6174.0, 383.8, 67.8)

        assertEquals(
            listOf("≈ €21.40 for 7 meals, €2.60 saved by offers", "5330 of 6174 kcal · 325 of 384 g protein · 44 of 68 g fibre"),
            totals.lines(english).map { it.plain() },
        )
        assertEquals("≈ €21.40 for 7 meals", totals.copy(costWithoutOffers = 21.4).lines(english).first(), "no saving to tell")
        assertEquals("≈ €6,14 voor 2 maaltijden", totals.copy(meals = 2, cost = 6.14, costWithoutOffers = 6.14).lines(dutch).first(), "a dinner and its leftover lunch")
        assertEquals(1, totals.copy(cost = null, targetFibreG = null).lines(english).size, "without a cost, the numbers only")
        assertTrue(totals.copy(meals = 0).lines(english).isEmpty(), "nothing counted yet")
    }
}
