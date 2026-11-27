package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.app.accounts.AccountsMsg
import dev.tobins.mealplanner.app.accounts.AccountsPlural
import dev.tobins.mealplanner.app.ingredients.IngredientsMsg
import dev.tobins.mealplanner.app.ingredients.IngredientsPlural
import dev.tobins.mealplanner.app.plan.PlanMsg
import dev.tobins.mealplanner.app.plan.PlanPlural
import dev.tobins.mealplanner.app.prices.PricesMsg
import dev.tobins.mealplanner.app.prices.PricesPlural
import dev.tobins.mealplanner.app.recipes.RecipesMsg
import dev.tobins.mealplanner.app.recipes.RecipesPlural
import dev.tobins.mealplanner.app.shopping.ShoppingMsg
import dev.tobins.mealplanner.app.shopping.ShoppingPlural
import dev.tobins.mealplanner.app.tracking.TrackingMsg
import dev.tobins.mealplanner.app.ui.CommonMsg
import dev.tobins.mealplanner.app.ui.CommonPlural

/*
 * Every text the app shows that is not made of data, in English and Dutch side by side, so a
 * translation is read (and fixed) in one place. The texts are in the package of the feature that shows
 * them, as one enum each (`PlanMsg`, `ShoppingMsg`, ...); what several features show is in `ui`
 * (`CommonMsg`), and what only the app's shell shows (the tabs, notifications, updates) in `AppMsg`.
 * Placeholders are `{0}`, `{1}`, ...; both languages must use the same ones, which StringsTest checks
 * for every enum in [messageTables] and [pluralTables]. Numbers, money and dates are formatted by the
 * caller and passed in. Use them through [Strings] (`LocalStrings.current`).
 *
 * Dutch is informal ("je") and uses the words of a Dutch supermarket app. "kcal" stays.
 */

/** One text in both languages; each feature's enum of texts implements it, and [Strings] reads it. */
interface Msg {
    /** The constant's name, which the checks of the tables report. */
    val name: String
    val english: String
    val dutch: String

    /** The text in [language]. */
    fun text(language: Language): String = if (language == Language.DUTCH) dutch else english
}

/**
 * A text that depends on a count, in English and Dutch. Both languages use the form for one
 * when the count is 1 and the other form otherwise, including for 0. `{0}` is the count.
 */
interface Plural {
    val name: String
    val englishOne: String
    val englishOther: String
    val dutchOne: String
    val dutchOther: String

    /** The text of [language] for [count]. */
    fun text(language: Language, count: Int): String = when (language) {
        Language.ENGLISH -> if (count == 1) englishOne else englishOther
        Language.DUTCH -> if (count == 1) dutchOne else dutchOther
    }
}

/**
 * Every enum of texts, which the check that both languages use the same placeholders walks. A new enum is
 * added here; `MessageTablesTest` fails for one that is not.
 */
val messageTables: List<List<Msg>> = listOf(
    AppMsg.entries, CommonMsg.entries, PlanMsg.entries, ShoppingMsg.entries, RecipesMsg.entries,
    IngredientsMsg.entries, PricesMsg.entries, TrackingMsg.entries, AccountsMsg.entries,
)

/** Every enum of count-dependent texts, as [messageTables] lists the plain ones. */
val pluralTables: List<List<Plural>> = listOf(
    AppPlural.entries, CommonPlural.entries, PlanPlural.entries, ShoppingPlural.entries, RecipesPlural.entries,
    IngredientsPlural.entries, PricesPlural.entries, AccountsPlural.entries,
)
