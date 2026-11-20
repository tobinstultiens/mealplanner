package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.planner.PortionRange
import dev.tobins.mealplanner.shopping.ShopSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StringsTest {
    private val placeholder = Regex("""\{(\d+)}""")

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.groupValues[1] }.toSet()

    /** Texts that are the same in both languages on purpose: names, units, examples and words both share. */
    private val sameInBothLanguages = setOf(
        Msg.DETAILS, Msg.SLOT_LUNCH, Msg.CARD_SERVER, Msg.CARD_ACCOUNT, Msg.CARD_APP, Msg.LATER,
        Msg.LANGUAGE_ENGLISH, Msg.LANGUAGE_DUTCH, Msg.OFFER_WAS, Msg.PRICE_PER_KG, Msg.WEIGHING_ROW,
        Msg.SERVER_ADDRESS_HINT, Msg.PACKS_TO_TAKE, Msg.APP_NAME, Msg.ADJUST_SHORT_MEAL, Msg.PREP_MEAL_NOTES,
        Msg.STANDARD_CHIP, Msg.PLANNING_COST,
    )

    @Test
    fun aRefusedRecipeNamesTheLinesTheServerCouldNotRead() {
        val server = "Unreadable ingredient line: 0,5x | kg | banaan\nUnreadable ingredient line: x | g | ei"
        assertEquals(
            "Dit is niet te lezen: \"0,5x | kg | banaan\" / \"x | g | ei\"\nElke regel heeft de vorm hoeveelheid | eenheid | product",
            checkLinesMessage(Strings.Dutch, server),
        )
        assertTrue(checkLinesMessage(Strings.English, server).startsWith("Cannot read: \"0,5x"))
        assertEquals(Strings.Dutch(Msg.CHECK_LINES), checkLinesMessage(Strings.Dutch, "title must not be blank"))
    }

    @Test
    fun everyMessageHasBothLanguagesAndTheSamePlaceholders() {
        for (msg in Msg.entries) {
            val english = msg.text(Language.ENGLISH)
            val dutch = msg.text(Language.DUTCH)
            assertTrue(english.isNotBlank(), "$msg has no English text")
            assertTrue(dutch.isNotBlank(), "$msg has no Dutch text")
            assertEquals(placeholders(english), placeholders(dutch), "$msg uses different placeholders in Dutch")
        }
    }

    @Test
    fun theBrowserWordingClicksWhereThePhoneTaps() {
        val browserTexts = Msg.entries.filter { it.name.endsWith("_BROWSER") }
        assertTrue(browserTexts.isNotEmpty())
        for (msg in browserTexts) {
            assertTrue(!Regex("\\btap", RegexOption.IGNORE_CASE).containsMatchIn(msg.text(Language.ENGLISH)), "$msg says tap in a browser")
            assertTrue(!Regex("\\btik\\b", RegexOption.IGNORE_CASE).containsMatchIn(msg.text(Language.DUTCH)), "$msg says tik in a browser")
            val phone = Msg.valueOf(msg.name.removeSuffix("_BROWSER"))
            assertEquals(placeholders(phone.text(Language.ENGLISH)), placeholders(msg.text(Language.ENGLISH)), "$msg and $phone differ in placeholders")
        }
    }

    @Test
    fun aTextForBothUsesTheWordingOfWhereTheAppRuns() {
        val expected = if (runsInBrowser()) "No offers fetched yet. Click Refresh." else "No offers fetched yet. Tap Refresh."
        assertEquals(expected, Strings.English.onWeb(Msg.NO_OFFERS_YET, Msg.NO_OFFERS_YET_BROWSER))
    }

    @Test
    fun aDutchTextThatEqualsTheEnglishOneIsOnPurpose() {
        // A translation that was never written would pass the test above as a copy of the English text.
        val untranslated = Msg.entries.filter { it.text(Language.ENGLISH) == it.text(Language.DUTCH) }
        assertEquals(emptyList(), untranslated - sameInBothLanguages, "Dutch equals English, so probably untranslated")
    }

    @Test
    fun everyPluralHasBothLanguagesAndFormsThatDiffer() {
        for (plural in Plural.entries) {
            for (language in Language.entries) {
                val one = plural.text(language, 1)
                val other = plural.text(language, 2)
                assertTrue(one.isNotBlank() && other.isNotBlank(), "$plural has an empty $language form")
                assertNotEquals(one, other, "$plural has the same text for 1 and 2 in $language")
                assertEquals(placeholders(one), placeholders(other), "$plural uses other placeholders for 1 and 2 in $language")
            }
            assertEquals(
                placeholders(plural.text(Language.ENGLISH, 1)), placeholders(plural.text(Language.DUTCH, 1)),
                "$plural uses different placeholders in Dutch",
            )
        }
    }

    @Test
    fun pluralFormsFollowTheCountForZeroOneAndTwo() {
        for ((strings, expected) in listOf(
            Strings.English to listOf("0 servings", "1 serving", "2 servings"),
            Strings.Dutch to listOf("0 porties", "1 portie", "2 porties"),
        )) {
            assertEquals(expected, listOf(0, 1, 2).map { strings.plural(Plural.SERVINGS, it) })
        }
        assertEquals("Elke winkel · 0 producten", Strings.Dutch.plural(Plural.ANY_STORE_ITEMS, 0))
        // The count is {0}, further arguments follow.
        assertEquals("AH · 1 item on offer", Strings.English.plural(Plural.STORE_ITEMS_ON_OFFER, 1, "AH"))
    }

    @Test
    fun placeholdersAreFilledInOrderAndUnknownOnesStay() {
        assertEquals("3 of 5 kcal", fill("{0} of {1} kcal", arrayOf(3, 5)))
        assertEquals("a {1} b", fill("{0} {1} b", arrayOf("a")))
        assertEquals("{x} 1", fill("{x} {0}", arrayOf(1)))
        assertEquals("no placeholders", fill("no placeholders", arrayOf(1)))
    }

    @Test
    fun theLanguageSettingDecidesTheTextsAndTheFormats() {
        // "System" follows the device: Dutch for any nl locale, English for the rest.
        for (tag in listOf("nl-NL", "nl-BE", "nl", "nl_NL")) {
            assertEquals(Language.DUTCH, Strings.of(AppLanguage.SYSTEM, tag).language, tag)
        }
        for (tag in listOf("en-GB", "de-DE", "fr-FR", "", "en-US")) {
            assertEquals(Language.ENGLISH, Strings.of(AppLanguage.SYSTEM, tag).language, tag)
        }
        // With the system language the device's own formats stay: German numbers, English words.
        val german = Strings.of(AppLanguage.SYSTEM, "de-DE")
        assertEquals(',', german.locale.decimalSeparator)
        assertEquals("Today", german(Msg.TAB_TODAY))
        // An explicit choice wins over the device, texts and formats alike.
        val dutchOnBritishPhone = Strings.of(AppLanguage.DUTCH, "en-GB")
        assertEquals("Vandaag", dutchOnBritishPhone(Msg.TAB_TODAY))
        assertEquals("€2,50", formatEuro(2.5, dutchOnBritishPhone.locale))
        val englishOnDutchPhone = Strings.of(AppLanguage.ENGLISH, "nl-NL")
        assertEquals("Today", englishOnDutchPhone(Msg.TAB_TODAY))
        assertEquals("€2.50", formatEuro(2.5, englishOnDutchPhone.locale))
    }

    @Test
    fun everyShopSectionHasADutchTitle() {
        for (section in ShopSection.entries) {
            val dutch = Strings.Dutch.sectionTitle(section.title)
            assertNotEquals(section.title, dutch, "${section.name} is not translated")
            assertEquals(section.title, Strings.English.sectionTitle(section.title))
            assertEquals(dutch, Strings.Dutch.sectionTitleOfKey(section.name))
        }
        assertEquals("Groente en fruit", Strings.Dutch.sectionTitle("Fruit and vegetables"))
    }

    @Test
    fun aSectionTheAppDoesNotKnowIsShownAsItCame() {
        assertEquals("Pet food", Strings.Dutch.sectionTitle("Pet food"))
        assertEquals("NEW_SECTION", Strings.Dutch.sectionTitleOfKey("NEW_SECTION"))
    }

    @Test
    fun slotsAreNamedInBothLanguages() {
        assertEquals(listOf("breakfast", "lunch", "dinner", "snack"), MealSlot.entries.map { Strings.English.slot(it) })
        assertEquals(listOf("ontbijt", "lunch", "avondeten", "tussendoortje"), MealSlot.entries.map { Strings.Dutch.slot(it) })
        assertEquals("Avondeten", Strings.Dutch.slot(MealSlot.DINNER, capitalised = true))
    }

    @Test
    fun profileProblemsAreWordedInTheAppsLanguage() {
        assertEquals("Weight must be between 30 and 300", Strings.English.profileProblem("weightKg must be between 30 and 300"))
        assertEquals("Leeftijd moet tussen 18 en 100 liggen", Strings.Dutch.profileProblem("ageYears must be between 18 and 100"))
        assertEquals("something else", Strings.Dutch.profileProblem("something else"))
    }

    @Test
    fun portionRangeProblemsAreWordedInTheAppsLanguageWithItsNumbers() {
        // Every problem the shared range check can give is translated.
        val all = listOf(PortionRange(0.0, 3.25), PortionRange(0.8, 0.6)).flatMap { it.problems() }
        assertEquals(5, all.toSet().size)
        for (problem in all) assertNotEquals(problem, Strings.Dutch.profileProblem(problem))

        assertEquals("Smallest portion must be between 0.25 and 3", Strings.English.profileProblem("minPortions must be between 0.25 and 3"))
        assertEquals("Grootste portie moet tussen 0,25 en 3 liggen", Strings.Dutch.profileProblem("maxPortions must be between 0.25 and 3"))
        assertEquals("Kleinste portie moet in kwarten, zoals 1,25", Strings.Dutch.profileProblem("minPortions must be in quarter servings"))
        assertEquals(
            "The smallest portion must not be larger than the largest",
            Strings.English.profileProblem("minPortions must not be more than maxPortions"),
        )
    }

    @Test
    fun theLanguageChoiceIsStoredLikeTheTheme() {
        val storage = MemorySettingsStorage()
        assertEquals(AppLanguage.SYSTEM, storage.appLanguage())
        storage.saveAppLanguage(AppLanguage.DUTCH)
        assertEquals(AppLanguage.DUTCH, storage.appLanguage())
        storage.set("language", "KLINGON")
        assertEquals(AppLanguage.SYSTEM, storage.appLanguage())
    }

    @Test
    fun datesAndFactsFollowTheChosenLanguage() {
        assertEquals("vandaag 12:40", whenLabel("2026-11-15", "12:40", "2026-11-15", Strings.Dutch))
        assertEquals("gisteren 12:40", whenLabel("2026-11-14", "12:40", "2026-11-15", Strings.Dutch))
        assertEquals("vr 13 nov 12:40", whenLabel("2026-11-13", "12:40", "2026-11-15", Strings.Dutch))
        assertEquals("2 porties", servingsText(2.0, Strings.Dutch))
        assertEquals("1,5 porties", servingsText(1.5, Strings.Dutch))
        assertEquals("1 portie", servingsText(1.0, Strings.Dutch))
    }
}
