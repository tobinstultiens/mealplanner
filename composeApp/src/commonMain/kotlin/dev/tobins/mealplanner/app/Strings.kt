package dev.tobins.mealplanner.app

import androidx.compose.runtime.staticCompositionLocalOf
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.app.accounts.AccountsMsg
import dev.tobins.mealplanner.app.plan.isPrepDay
import dev.tobins.mealplanner.app.ui.FormatLocale
import dev.tobins.mealplanner.app.ui.dayLabel
import dev.tobins.mealplanner.app.ui.formatAmount
import dev.tobins.mealplanner.shopping.ShopSection

/** The languages the app's own texts exist in. */
enum class Language { ENGLISH, DUTCH }

/** What the language setting offers: follow the device, or fix one. */
enum class AppLanguage { SYSTEM, ENGLISH, DUTCH }

/** The language of a device or browser locale tag: Dutch for `nl`, `nl-NL`, `nl-BE`, English for everything else. */
fun languageOfTag(tag: String): Language =
    if (tag.split('-', '_').first().equals("nl", ignoreCase = true)) Language.DUTCH else Language.ENGLISH

/**
 * The app's texts and formats in one language. Texts come from the [Msg] and [Plural] tables;
 * [locale] says how numbers, money and dates are written, so Dutch texts never sit next to
 * English-looking numbers. Get one with [of]; screens read it from [LocalStrings].
 *
 * Text that comes from the server (error messages, deal texts, recipes) is not translated here.
 */
class Strings private constructor(val language: Language, val locale: FormatLocale) {
    /** The text of [msg]; `{0}`, `{1}` in it are replaced by [args] in order. */
    operator fun invoke(msg: Msg, vararg args: Any?): String = fill(msg.text(language), args)

    /** The text of [msg] on a phone and of [browser] in a browser, where a "tap" is a "click" and the wording differs. */
    fun onWeb(msg: Msg, browser: Msg, vararg args: Any?): String = this(if (runsInBrowser()) browser else msg, *args)

    /** The form of [plural] for [count], which is also `{0}` in the text; [args] fill `{1}` and up. */
    fun plural(plural: Plural, count: Int, vararg args: Any?): String =
        fill(plural.text(language, count), arrayOf<Any?>(count, *args))

    /** The name of a meal slot: `breakfast` (`ontbijt`), or capitalised for a title. */
    fun slot(slot: MealSlot, capitalised: Boolean = false): String =
        this(slot.msg()).let { if (capitalised) it.replaceFirstChar { c -> c.uppercase() } else it }

    /**
     * A planned meal's slot as a small heading: `Dinner`, `Dinner · leftovers`, `Lunch · leftovers · adjusted`; of a
     * prepped batch (issue 234) `Dinner · prep day` where it is made and `Lunch · prepped Tue 17 Nov` after, with
     * `· vacuum sealed` for a batch kept so ([sealed]).
     */
    fun mealLabel(meal: PlannedMeal): String =
        slot(meal.slot, capitalised = true)
            .let { name ->
                val prep = meal.prep
                when {
                    prep != null && meal.leftover -> this(AppMsg.MEAL_PREPPED, name, dayLabel(prep.madeOn, locale))
                    prep != null -> this(AppMsg.MEAL_PREP_DAY, name)
                    meal.leftover -> this(AppMsg.MEAL_LEFTOVERS, name)
                    else -> name
                }
            }
            .let { sealed(it, meal.prep) }
            .let { if (meal.adjusted) this(AppMsg.MEAL_ADJUSTED, it) else it }

    /**
     * How the side next to the meal at [date]'s [slot] is labelled: "Side · prep day" where its batch is made, "Side · prepped
     * Mon 16 Nov" after, with `· vacuum sealed` for a batch kept so ([sealed]).
     */
    fun sideLabel(side: MealSide, date: String, slot: MealSlot): String =
        sealed(if (side.isPrepDay(date, slot)) this(AppMsg.SIDE_PREP_DAY) else this(AppMsg.SIDE_PREPPED, dayLabel(side.prep.madeOn, locale)), side.prep)

    /**
     * [label] with `· vacuum sealed` when [prep] is kept so (issue 263): on the day it is made, how to keep it; after, which
     * of the boxes it is, as a sealed batch keeps longer than one in a box.
     */
    private fun sealed(label: String, prep: MealPrep?): String = if (prep?.vacuum == true) this(AppMsg.PREP_LABEL_VACUUM, label) else label

    /**
     * What is wrong with a profile, as the shared calculator words it (`weightKg must be between 30 and 300`),
     * in this language. A problem in another form is shown as it came.
     */
    fun profileProblem(problem: String): String {
        if (problem == "minPortions must not be more than maxPortions") return this(AppMsg.PROBLEM_PORTIONS_ORDER)
        Regex("""^(\w+) must be in quarter servings$""").matchEntire(problem)?.let { quarters ->
            return profileField(quarters.groupValues[1])?.let { this(AppMsg.PROBLEM_QUARTERS, this(it)) } ?: problem
        }
        val match = Regex("""^(\w+) must be between ([\d.]+) and ([\d.]+)$""").matchEntire(problem) ?: return problem
        val (field, low, high) = match.destructured
        val name = profileField(field) ?: return problem
        return this(AppMsg.PROBLEM_RANGE, this(name), formatAmount(low.toDouble(), locale), formatAmount(high.toDouble(), locale))
    }

    /** The name the profile form gives the field the shared calculator calls [field]; null for one it does not know. */
    private fun profileField(field: String): Msg? = when (field) {
        "weightKg" -> AppMsg.WEIGHT
        "heightCm" -> AppMsg.HEIGHT
        "ageYears" -> AccountsMsg.AGE
        "goalKgPerWeek" -> AppMsg.GOAL
        "kcalOverride" -> AccountsMsg.CALORIES_PER_DAY
        "proteinOverrideG" -> AccountsMsg.PROTEIN_PER_DAY
        "fibreOverrideG" -> AccountsMsg.FIBRE_PER_DAY
        "minPortions" -> AppMsg.SMALLEST_PORTION
        "maxPortions" -> AppMsg.LARGEST_PORTION
        else -> null
    }

    /**
     * A shopping section's title as shown. The server names sections in English (`ShopSection.title`);
     * known titles are translated, one this app does not know is shown as it came.
     */
    fun sectionTitle(serverTitle: String): String =
        ShopSection.entries.firstOrNull { it.title == serverTitle }?.let { this(it.msg()) } ?: serverTitle

    /** A shopping section's title by its key (`ShopSection.name`); a key from a newer server is shown as it is. */
    fun sectionTitleOfKey(key: String): String = ShopSection.fromKey(key)?.let { this(it.msg()) } ?: key

    companion object {
        /** English, with English number and date formats. */
        val English = Strings(Language.ENGLISH, FormatLocale.English)

        /** Dutch, with Dutch number and date formats. */
        val Dutch = Strings(Language.DUTCH, FormatLocale.Dutch)

        /**
         * The strings for the language [choice]. "System" picks the language from the device's
         * [deviceTag] and keeps the device's own number and date formats; an explicit language
         * brings its own formats.
         */
        fun of(choice: AppLanguage, deviceTag: String): Strings = when (choice) {
            AppLanguage.ENGLISH -> English
            AppLanguage.DUTCH -> Dutch
            AppLanguage.SYSTEM -> Strings(languageOfTag(deviceTag), FormatLocale.of(deviceTag))
        }

        /**
         * The strings in use right now, for the few texts made outside composition (a failed
         * request's fallback message). Set by [App] whenever the language changes; screens use [LocalStrings].
         */
        var current: Strings = English
    }
}

/** The strings of the app's language setting. Without an [App] around (a preview, a test) English. */
val LocalStrings = staticCompositionLocalOf { Strings.English }

/** Replaces `{0}`, `{1}`, ... in [template] with [args]; a placeholder without an argument stays as it is. */
internal fun fill(template: String, args: Array<out Any?>): String {
    if ('{' !in template) return template
    val out = StringBuilder()
    var i = 0
    while (i < template.length) {
        val close = template.indexOf('}', i)
        val index = if (template[i] == '{' && close > i + 1) template.substring(i + 1, close).toIntOrNull() else null
        if (index != null && index in args.indices) {
            out.append(args[index])
            i = close + 1
        } else {
            out.append(template[i])
            i++
        }
    }
    return out.toString()
}

/** The label of a language choice. */
internal fun AppLanguage.msg() = when (this) {
    AppLanguage.SYSTEM -> AppMsg.LANGUAGE_SYSTEM
    AppLanguage.ENGLISH -> AppMsg.LANGUAGE_ENGLISH
    AppLanguage.DUTCH -> AppMsg.LANGUAGE_DUTCH
}

/** The label of a theme choice. */
internal fun ThemeMode.msg() = when (this) {
    ThemeMode.SYSTEM -> AppMsg.THEME_SYSTEM
    ThemeMode.LIGHT -> AppMsg.THEME_LIGHT
    ThemeMode.DARK -> AppMsg.THEME_DARK
}

/** The label of a shopping view choice. */
internal fun ShoppingView.msg() = when (this) {
    ShoppingView.SECTION -> AppMsg.VIEW_SECTION
    ShoppingView.STORE -> AppMsg.VIEW_STORE
}

private fun MealSlot.msg() = when (this) {
    MealSlot.BREAKFAST -> AppMsg.SLOT_BREAKFAST
    MealSlot.LUNCH -> AppMsg.SLOT_LUNCH
    MealSlot.DINNER -> AppMsg.SLOT_DINNER
    MealSlot.SNACK -> AppMsg.SLOT_SNACK
}

/** The message of a section's title; every section has one, which the compiler checks. */
internal fun ShopSection.msg() = when (this) {
    ShopSection.FRUIT_VEG -> AppMsg.SECTION_FRUIT_VEG
    ShopSection.BREAD -> AppMsg.SECTION_BREAD
    ShopSection.DELI -> AppMsg.SECTION_DELI
    ShopSection.SAUCES -> AppMsg.SECTION_SAUCES
    ShopSection.MEAT -> AppMsg.SECTION_MEAT
    ShopSection.FISH -> AppMsg.SECTION_FISH
    ShopSection.VEGETARIAN -> AppMsg.SECTION_VEGETARIAN
    ShopSection.DAIRY -> AppMsg.SECTION_DAIRY
    ShopSection.READY_MEALS -> AppMsg.SECTION_READY_MEALS
    ShopSection.WORLD -> AppMsg.SECTION_WORLD
    ShopSection.TINS -> AppMsg.SECTION_TINS
    ShopSection.BREAKFAST -> AppMsg.SECTION_BREAKFAST
    ShopSection.BAKING -> AppMsg.SECTION_BAKING
    ShopSection.SNACKS -> AppMsg.SECTION_SNACKS
    ShopSection.DRINKS -> AppMsg.SECTION_DRINKS
    ShopSection.FROZEN -> AppMsg.SECTION_FROZEN
    ShopSection.NON_FOOD -> AppMsg.SECTION_NON_FOOD
    ShopSection.OTHER -> AppMsg.SECTION_OTHER
}
