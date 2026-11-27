package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.app.accounts.AccountShops
import dev.tobins.mealplanner.app.recipes.RecipesMsg
import dev.tobins.mealplanner.app.recipes.syncCookingSite
import dev.tobins.mealplanner.app.shopping.ShoppingMsg
import dev.tobins.mealplanner.app.ui.AppError
import dev.tobins.mealplanner.app.ui.CommonMsg
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.failureOf
import dev.tobins.mealplanner.app.ui.failureSentence
import dev.tobins.mealplanner.app.ui.load
import dev.tobins.mealplanner.app.ui.reasonOf
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Every screen that shows a failed request in a sentence, failing the way a lost connection does: the
 * sentence is the plain one, in English and in Dutch, and never the engine's text.
 */
class PlainSentencePerScreenTest {
    private val offline = ApiClient(ServerSettings("http://localhost"), engine = MockEngine { throw unreachableServer() })

    @AfterTest
    fun backToEnglish() {
        Strings.current = Strings.English
    }

    /** [call] failing, shown with [msg] as the screen does, in English and then in Dutch. */
    private suspend fun assertPlain(msg: Msg?, english: String, dutch: String, call: suspend () -> Any?) {
        val failed = load { call() } as Load.Failed
        assertEquals(english, failureSentence(failed, msg))
        Strings.current = Strings.Dutch
        assertEquals(dutch, failureSentence(failed, msg))
        Strings.current = Strings.English
    }

    @Test
    fun theRecipeEditorSaysItCouldNotSaveAddAFoodOrLookLinesUp() = runTest {
        val input = RecipeInput("Soup", 2, lines = listOf(RecipeLineInput(1.0, "piece", "leek")))
        assertPlain(CommonMsg.SAVE_FAILED, "Could not save: the server could not be reached.", "Opslaan is niet gelukt: de server is niet bereikbaar.") { offline.updateRecipe(1, input) }
        assertPlain(CommonMsg.SAVE_FAILED, "Could not save: the server could not be reached.", "Opslaan is niet gelukt: de server is niet bereikbaar.") { offline.createRecipe(input) }
        assertPlain(CommonMsg.EDITOR_ADD_FAILED, "Could not add the food: the server could not be reached.", "Het voedingsmiddel toevoegen is niet gelukt: de server is niet bereikbaar.") {
            offline.createIngredient(IngredientInput("Leek", nevoCode = 1))
        }
        assertPlain(RecipesMsg.EDITOR_LOOKUP_FAILED, "Could not look the pasted lines up: the server could not be reached.", "De geplakte regels opzoeken is niet gelukt: de server is niet bereikbaar.") {
            offline.readLines(input.lines!!)
        }
        assertPlain(RecipesMsg.EDITOR_SEARCH_FAILED, "Could not search the NEVO foods: the server could not be reached.", "Zoeken in de NEVO-voedingsmiddelen is niet gelukt: de server is niet bereikbaar.") {
            offline.searchFoods("leek")
        }
    }

    @Test
    fun theRecipeDetailSaysItCouldNotMarkTheMealEatenOrDelete() = runTest {
        assertPlain(RecipesMsg.COOK_MARK_EATEN_FAILED, "Could not mark the meal as eaten: the server could not be reached.", "Het gerecht markeren als gegeten is niet gelukt: de server is niet bereikbaar.") {
            offline.setMealStatus("2026-11-16", 0, MealSlot.DINNER, MealStatus.EATEN)
        }
        assertPlain(RecipesMsg.DELETE_FAILED, "Could not delete: the server could not be reached.", "Verwijderen is niet gelukt: de server is niet bereikbaar.") { offline.deleteRecipe(1) }
    }

    @Test
    fun theProfileSaysItCouldNotSave() = runTest {
        assertPlain(CommonMsg.SAVE_FAILED, "Could not save: the server could not be reached.", "Opslaan is niet gelukt: de server is niet bereikbaar.") {
            offline.saveProfile(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT))
        }
    }

    @Test
    fun theShoppingStoresSayTheyCouldNotBeSaved() = runTest {
        val shops = AccountShops(MemorySettingsStorage(), offline::shops, offline::saveShops)
        assertPlain(ShoppingMsg.SHOPS_SAVE_FAILED, "Could not save the stores: the server could not be reached.", "Winkels opslaan is niet gelukt: de server is niet bereikbaar.") { shops.choose(setOf(Store.AH)) }
        // The settings screen shows the same failure without a lead-in.
        assertPlain(null, "The server could not be reached.", "De server is niet bereikbaar.") { shops.choose(setOf(Store.AH)) }
    }

    @Test
    fun theUpdateCheckSaysItCouldNotCheckWithoutTheLibrariesText() = runTest {
        val raw = kotlinx.io.IOException("Unable to resolve host \"api.github.com\"")
        val library = IllegalStateException("Client already closed")
        assertEquals("Could not check: the server could not be reached.", updateCheckFailure(Strings.English, raw))
        // A library's IllegalStateException (a closed client) is no sentence of the app's either.
        assertEquals("Could not check: something went wrong.", updateCheckFailure(Strings.English, library))
        // GitHub's refusal is the app's own sentence and stays.
        assertEquals("Could not check: GitHub answered 403.", updateCheckFailure(Strings.English, AppError("GitHub answered 403")))
        Strings.current = Strings.Dutch
        assertEquals("Controleren is niet gelukt: de server is niet bereikbaar.", updateCheckFailure(Strings.Dutch, raw))
        assertEquals("Controleren is niet gelukt: er ging iets mis.", updateCheckFailure(Strings.Dutch, library))
    }

    @Test
    fun theRecipeBankSaysTheSyncOrImportFailed() = runTest {
        val sync = syncCookingSite(offline, Strings.English, bank = true).message
        assertEquals("Sync failed: the server could not be reached.", sync)
        assertEquals("Import failed: the server could not be reached.", syncCookingSite(offline, Strings.English, bank = true, imported = true).message)
        Strings.current = Strings.Dutch
        assertEquals("Synchroniseren is mislukt: de server is niet bereikbaar.", syncCookingSite(offline, Strings.Dutch, bank = true).message)
        assertEquals("Importeren is mislukt: de server is niet bereikbaar.", syncCookingSite(offline, Strings.Dutch, bank = true, imported = true).message)
    }

    @Test
    fun aLibrarysIllegalStateExceptionIsToldInPlainWordsButTheAppsOwnSentenceStays() = runTest {
        assertEquals("something went wrong", reasonOf(IllegalStateException("HttpClient is closed")))
        assertEquals("The login answer has no code", reasonOf(AppError("The login answer has no code")))
        Strings.current = Strings.Dutch
        assertEquals("er ging iets mis", reasonOf(IllegalStateException("HttpClient is closed")))
    }

    @Test
    fun thePickerShowsTheSentenceOfARefusalAndThePlainReasonOfAnythingElse() = runTest {
        assertEquals("This meal was eaten already.", failureOf { throw AppError("This meal was eaten already.") })
        assertEquals("The server could not be reached.", failureOf { throw kotlinx.io.IOException("lost") })
        Strings.current = Strings.Dutch
        assertEquals("De server is niet bereikbaar.", failureOf { throw kotlinx.io.IOException("unexpected end of stream on http://x") })
        assertFailsWith<kotlinx.coroutines.CancellationException> { failureOf { throw kotlinx.coroutines.CancellationException("left") } }
    }
}
