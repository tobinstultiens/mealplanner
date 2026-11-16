package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.RecipeCost
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.units.Dimension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val en = FormatLocale.English
private val nl = FormatLocale.Dutch

/** English texts with the decimal comma of a German device, as the "System" language gives. */
private val germanFormats = Strings.of(AppLanguage.SYSTEM, "de-DE")

class FormattingTest {
    @Test
    fun amountsDropTrailingZerosAndRoundToHundredths() {
        assertEquals("400", formatAmount(400.0, en))
        assertEquals("1.5", formatAmount(1.5, en))
        assertEquals("0.33", formatAmount(1.0 / 3, en))
        assertEquals("0.05", formatAmount(0.05, en))
        assertEquals("2", formatAmount(1.999, en))
    }

    @Test
    fun ingredientLinesReadNaturally() {
        fun line(amount: Double?, unit: String?, dimension: Dimension?, base: Double?, item: String) =
            IngredientLine(amount, unit, dimension, base, item, group = null)

        assertEquals("400 g beef", line(400.0, "g", Dimension.WEIGHT, 400.0, "beef").displayText(en))
        assertEquals("1 onion, large", line(1.0, "onion", Dimension.COUNT, null, "large").displayText(en))
        assertEquals("2 onion", line(2.0, "onion", Dimension.COUNT, null, "").displayText(en))
        assertEquals("3 clove garlic", line(3.0, "clove", Dimension.COUNT, null, "garlic").displayText(en))
        assertEquals("salt to taste", line(null, null, null, null, "salt to taste").displayText(en))
    }

    @Test
    fun gramsAreWholeAboveTenAndOneDecimalBelow() {
        assertEquals("28 g", formatGrams(28.06, en))
        assertEquals("302 g", formatGrams(301.65, en))
        assertEquals("4.6 g", formatGrams(4.59, en))
        assertEquals("5 g", formatGrams(5.0, en))
        assertEquals("0 g", formatGrams(null, en))
    }

    @Test
    fun shoppingQuantitiesRoundPiecesUpAndSwitchToKilograms() {
        fun item(grams: Double?, pieces: Double?) =
            dev.tobins.mealplanner.api.ShoppingItem("k", "x", grams, pieces, "c", emptyList(), onHand = false, checked = false)

        assertEquals("450 g", item(450.2, null).quantityText(en))
        assertEquals("1.25 kg", item(1250.0, null).quantityText(en))
        assertEquals("2 × · 165 g", item(165.0, 1.5).quantityText(en))
        assertEquals("3 ×", item(null, 3.0).quantityText(en))
        assertEquals("", item(null, null).quantityText(en))
    }

    @Test
    fun shoppingQuantitiesKeepWhatCannotBeWeighed() {
        fun item(knownGrams: Double?, pieces: Double?, vararg other: String) =
            dev.tobins.mealplanner.api.ShoppingItem(
                "k", "x", null, pieces, "c", emptyList(), onHand = false, checked = false,
                knownGrams = knownGrams, otherAmounts = other.toList(),
            )

        assertEquals("200 g + 1 bag", item(200.0, 1.0, "1 bag").quantityText(en))
        assertEquals("1.25 kg + 2 can + 3 clove", item(1250.0, 5.0, "2 can", "3 clove").quantityText(en))
        assertEquals("1,25 kg + 1 bag", item(1250.0, 1.0, "1 bag").quantityText(nl))
        assertEquals("250 ml", item(null, null, "250 ml").quantityText(en))
        assertEquals("2 can", item(null, 2.0, "2 can").quantityText(nl))
        // 2 onions and a bag of them: the count of the ingredient itself is not lost behind the bag.
        assertEquals("2 × + 1 bag", item(null, 3.0, "1 bag").copy(ownPieces = 2.0).quantityText(en))
        assertEquals("200 g + 2 × + 1 bag", item(200.0, 2.5, "1 bag").copy(ownPieces = 1.5).quantityText(en), "rounded up")
        // Weight known, and a piece count that has no unit of its own.
        assertEquals("2 × · 200 g", item(200.0, 2.0).quantityText(en))
        assertEquals("", item(null, null).quantityText(en))
    }

    @Test
    fun offersAreSummarisedPerStore() {
        fun offer(store: dev.tobins.mealplanner.api.Store, deal: String?, price: Double? = null, was: Double? = null) =
            dev.tobins.mealplanner.api.Offer(store, "x", deal, price, was, validFrom = "a", validUntil = "b")

        assertEquals("AH: €3.49 (was €4.59)", offer(dev.tobins.mealplanner.api.Store.AH, "VOOR 3.49", 3.49, 4.59).shortText(Strings.English))
        assertEquals("AH: 25% korting", offer(dev.tobins.mealplanner.api.Store.AH, "25% KORTING").shortText(Strings.English))
        assertEquals("Jumbo: 1+1 gratis", offer(dev.tobins.mealplanner.api.Store.JUMBO, "1+1 gratis").shortText(Strings.English))
        assertEquals("Lidl: €4.29", offer(dev.tobins.mealplanner.api.Store.LIDL, null, 4.29).shortText(Strings.English))
        assertEquals("AH: 2 voor 1.99, €1.00", offer(dev.tobins.mealplanner.api.Store.AH, "2 voor 1.99", 1.0).shortText(Strings.English))
        assertEquals("Jumbo: Alleen online, €1.09", offer(dev.tobins.mealplanner.api.Store.JUMBO, "Voor 1,09 · alleen online", 1.09).shortText(Strings.English))
        assertEquals("€0.05", formatEuro(0.049, en))
    }

    @Test
    fun moneyAndAmountsFollowTheDecimalSeparator() {
        assertEquals("€2,11", formatEuro(2.11, nl))
        assertEquals("1,5", formatAmount(1.5, nl))
        assertEquals("1,25 kg", item(1250.0).quantityText(nl))
        assertEquals("AH: €3,49 (was €4,59)", offer("VOOR 3.49", 3.49, 4.59).shortText(germanFormats))
        assertEquals("1,75 servings · 461 kcal · 46 g protein", meal(1.75).macrosText(germanFormats))
    }

    @Test
    fun theLocaleComesFromTheDeviceTag() {
        assertEquals(',', FormatLocale.of("nl-NL").decimalSeparator)
        assertEquals("ma", FormatLocale.of("nl_NL").dayNames[0])
        assertEquals('.', FormatLocale.of("en-GB").decimalSeparator)
        assertEquals(false, FormatLocale.of("en-GB").monthFirst)
        assertEquals(true, FormatLocale.of("en-US").monthFirst)
        // Without names of its own, a language still gets its decimal comma.
        assertEquals(',', FormatLocale.of("sv-SE").decimalSeparator)
        assertEquals("Mon", FormatLocale.of("sv-SE").dayNames[0])
        assertEquals('.', FormatLocale.of("").decimalSeparator)
    }

    @Test
    fun mealNumbersAreServingsKcalAndProtein() {
        assertEquals("1 serving · 461 kcal · 46 g protein", meal(1.0).macrosText(Strings.English))
        assertEquals("1.5 servings · 461 kcal · 46 g protein", meal(1.5).macrosText(Strings.English))
    }

    @Test
    fun aRecipeRowKeepsNutritionAndCostApart() {
        val full = recipe(nutrition = nutrition(complete = true), cost = RecipeCost(3.0, 2.1)).rowText(Strings.English)
        assertEquals("387 kcal · 43 g protein", full.nutrition)
        assertEquals("≈ €2.10", full.cost)
        assertEquals("4 servings · ★★★★ · vegan · quick", full.details)
        assertNull(full.warning)

        val missing = recipe(nutrition = nutrition(complete = true, unquantified = listOf("chicken")), cost = null).rowText(Strings.English)
        assertEquals("387 kcal · 43 g protein", missing.nutrition)
        assertNull(missing.cost)
        assertEquals("Missing amounts", missing.warning)

        val toLink = recipe(nutrition = nutrition(complete = false, uncounted = listOf("a", "b")), cost = null).rowText(Strings.English)
        assertNull(toLink.nutrition)
        assertEquals("2 ingredients to link", toLink.warning)

        val none = recipe(nutrition = null, plannable = false, servings = 1, rating = null, tags = emptyList()).rowText(Strings.English)
        assertEquals("No amounts yet", none.warning)
        assertEquals("1 serving", none.details)
    }

    private fun item(grams: Double) =
        dev.tobins.mealplanner.api.ShoppingItem("k", "x", grams, null, "c", emptyList(), onHand = false, checked = false)

    private fun offer(deal: String, price: Double, was: Double) =
        dev.tobins.mealplanner.api.Offer(dev.tobins.mealplanner.api.Store.AH, "x", deal, price, was, validFrom = "a", validUntil = "b")

    private fun meal(portions: Double) = PlannedMeal(0, MealSlot.DINNER, 1, "Stew", portions, 461.2, 45.6)

    private fun nutrition(complete: Boolean, uncounted: List<String> = emptyList(), unquantified: List<String> = emptyList()) =
        RecipeNutrition(
            Nutrition(387.2, 42.6, null, null, null, null, null, null),
            complete = complete,
            approximate = false,
            uncounted = uncounted,
            unquantified = unquantified,
        )

    private fun recipe(
        nutrition: RecipeNutrition?,
        cost: RecipeCost? = null,
        plannable: Boolean = true,
        servings: Int? = 4,
        rating: Int? = 4,
        tags: List<String> = listOf("vegan", "quick"),
    ) = RecipeSummary(
        1, "Stew", "dinner", tags, servings, RecipeSource.MANUAL, RecipeKind.MEAL, plannable,
        nutrition = nutrition, rating = rating, cost = cost,
    )
}
