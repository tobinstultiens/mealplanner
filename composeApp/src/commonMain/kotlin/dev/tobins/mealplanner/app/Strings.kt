package dev.tobins.mealplanner.app

import androidx.compose.runtime.staticCompositionLocalOf
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
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
                    prep != null && meal.leftover -> this(Msg.MEAL_PREPPED, name, dayLabel(prep.madeOn, locale))
                    prep != null -> this(Msg.MEAL_PREP_DAY, name)
                    meal.leftover -> this(Msg.MEAL_LEFTOVERS, name)
                    else -> name
                }
            }
            .let { sealed(it, meal.prep) }
            .let { if (meal.adjusted) this(Msg.MEAL_ADJUSTED, it) else it }

    /**
     * How the side next to the meal at [date]'s [slot] is labelled: "Side · prep day" where its batch is made, "Side · prepped
     * Mon 16 Nov" after, with `· vacuum sealed` for a batch kept so ([sealed]).
     */
    fun sideLabel(side: MealSide, date: String, slot: MealSlot): String =
        sealed(if (side.isPrepDay(date, slot)) this(Msg.SIDE_PREP_DAY) else this(Msg.SIDE_PREPPED, dayLabel(side.prep.madeOn, locale)), side.prep)

    /**
     * [label] with `· vacuum sealed` when [prep] is kept so (issue 263): on the day it is made, how to keep it; after, which
     * of the boxes it is, as a sealed batch keeps longer than one in a box.
     */
    private fun sealed(label: String, prep: MealPrep?): String = if (prep?.vacuum == true) this(Msg.PREP_LABEL_VACUUM, label) else label

    /**
     * What is wrong with a profile, as the shared calculator words it (`weightKg must be between 30 and 300`),
     * in this language. A problem in another form is shown as it came.
     */
    fun profileProblem(problem: String): String {
        if (problem == "minPortions must not be more than maxPortions") return this(Msg.PROBLEM_PORTIONS_ORDER)
        Regex("""^(\w+) must be in quarter servings$""").matchEntire(problem)?.let { quarters ->
            return profileField(quarters.groupValues[1])?.let { this(Msg.PROBLEM_QUARTERS, this(it)) } ?: problem
        }
        val match = Regex("""^(\w+) must be between ([\d.]+) and ([\d.]+)$""").matchEntire(problem) ?: return problem
        val (field, low, high) = match.destructured
        val name = profileField(field) ?: return problem
        return this(Msg.PROBLEM_RANGE, this(name), formatAmount(low.toDouble(), locale), formatAmount(high.toDouble(), locale))
    }

    /** The name the profile form gives the field the shared calculator calls [field]; null for one it does not know. */
    private fun profileField(field: String): Msg? = when (field) {
        "weightKg" -> Msg.WEIGHT
        "heightCm" -> Msg.HEIGHT
        "ageYears" -> Msg.AGE
        "goalKgPerWeek" -> Msg.GOAL
        "kcalOverride" -> Msg.CALORIES_PER_DAY
        "proteinOverrideG" -> Msg.PROTEIN_PER_DAY
        "fibreOverrideG" -> Msg.FIBRE_PER_DAY
        "minPortions" -> Msg.SMALLEST_PORTION
        "maxPortions" -> Msg.LARGEST_PORTION
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
    AppLanguage.SYSTEM -> Msg.LANGUAGE_SYSTEM
    AppLanguage.ENGLISH -> Msg.LANGUAGE_ENGLISH
    AppLanguage.DUTCH -> Msg.LANGUAGE_DUTCH
}

/** The label of a theme choice. */
internal fun ThemeMode.msg() = when (this) {
    ThemeMode.SYSTEM -> Msg.THEME_SYSTEM
    ThemeMode.LIGHT -> Msg.THEME_LIGHT
    ThemeMode.DARK -> Msg.THEME_DARK
}

/** The label of a shopping view choice. */
internal fun ShoppingView.msg() = when (this) {
    ShoppingView.SECTION -> Msg.VIEW_SECTION
    ShoppingView.STORE -> Msg.VIEW_STORE
}

private fun MealSlot.msg() = when (this) {
    MealSlot.BREAKFAST -> Msg.SLOT_BREAKFAST
    MealSlot.LUNCH -> Msg.SLOT_LUNCH
    MealSlot.DINNER -> Msg.SLOT_DINNER
    MealSlot.SNACK -> Msg.SLOT_SNACK
}

/** The message of a section's title; every section has one, which the compiler checks. */
internal fun ShopSection.msg() = when (this) {
    ShopSection.FRUIT_VEG -> Msg.SECTION_FRUIT_VEG
    ShopSection.BREAD -> Msg.SECTION_BREAD
    ShopSection.DELI -> Msg.SECTION_DELI
    ShopSection.SAUCES -> Msg.SECTION_SAUCES
    ShopSection.MEAT -> Msg.SECTION_MEAT
    ShopSection.FISH -> Msg.SECTION_FISH
    ShopSection.VEGETARIAN -> Msg.SECTION_VEGETARIAN
    ShopSection.DAIRY -> Msg.SECTION_DAIRY
    ShopSection.READY_MEALS -> Msg.SECTION_READY_MEALS
    ShopSection.WORLD -> Msg.SECTION_WORLD
    ShopSection.TINS -> Msg.SECTION_TINS
    ShopSection.BREAKFAST -> Msg.SECTION_BREAKFAST
    ShopSection.BAKING -> Msg.SECTION_BAKING
    ShopSection.SNACKS -> Msg.SECTION_SNACKS
    ShopSection.DRINKS -> Msg.SECTION_DRINKS
    ShopSection.FROZEN -> Msg.SECTION_FROZEN
    ShopSection.NON_FOOD -> Msg.SECTION_NON_FOOD
    ShopSection.OTHER -> Msg.SECTION_OTHER
}
