package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStorageTest {
    /** The page reads the choice straight from the browser's storage before the app runs. */
    @Test
    fun themeChoiceIsStoredWhereTheBrowserPageReadsIt() {
        val storage = MemorySettingsStorage()
        assertEquals(ThemeMode.SYSTEM, storage.themeMode())

        storage.saveThemeMode(ThemeMode.DARK)
        assertEquals("DARK", storage.get("theme"))
        storage.saveThemeMode(ThemeMode.LIGHT)
        assertEquals("LIGHT", storage.get("theme"))
        assertEquals(ThemeMode.LIGHT, storage.themeMode())
    }

    @Test
    fun savedSettingsReplaceTheBuiltInDefaults() {
        val defaults = ServerSettings("http://10.0.2.2:8080")
        val storage = MemorySettingsStorage()
        assertEquals(defaults, storage.serverSettings(defaults))

        storage.saveServerSettings(ServerSettings(" https://meals.example.nl/ "))
        assertEquals(ServerSettings("https://meals.example.nl"), storage.serverSettings(defaults))
    }

    @Test
    fun theBuiltInServerReadsAsTheSettingsScreenWritesIt() {
        // Otherwise saving the same server would compare as another one, and log out.
        val storage = MemorySettingsStorage()
        assertEquals(ServerSettings("https://meals.example.nl"), storage.serverSettings(ServerSettings("https://meals.example.nl/")))
    }

    @Test
    fun aRemovedKeyIsGone() {
        val storage = MemorySettingsStorage()
        storage.set("shopping.2026-11-16", "{}")
        storage.remove("shopping.2026-11-16")
        assertEquals(null, storage.get("shopping.2026-11-16"))
        storage.remove("never.set")
    }

    @Test
    fun shoppingModeIsOffUntilSwitchedOn() {
        val storage = MemorySettingsStorage()
        assertEquals(false, storage.shoppingMode())

        storage.saveShoppingMode(true)
        assertEquals(true, storage.shoppingMode())
        storage.saveShoppingMode(false)
        assertEquals(false, storage.shoppingMode())
    }

    @Test
    fun productPicturesAreOnUntilSwitchedOff() {
        val storage = MemorySettingsStorage()
        assertEquals(true, storage.productPictures())

        storage.saveProductPictures(false)
        assertEquals(false, storage.productPictures())
        storage.saveProductPictures(true)
        assertEquals(true, storage.productPictures())
    }

    @Test
    fun systemColoursAreOffUntilSwitchedOn() {
        val storage = MemorySettingsStorage()
        assertEquals(false, storage.systemColors())

        storage.saveSystemColors(true)
        assertEquals(true, storage.systemColors())
        storage.saveSystemColors(false)
        assertEquals(false, storage.systemColors())
    }

    @Test
    fun theShoppingViewIsBySectionUntilChanged() {
        val storage = MemorySettingsStorage()
        assertEquals(ShoppingView.SECTION, storage.shoppingView())

        storage.saveShoppingView(ShoppingView.STORE)
        assertEquals(ShoppingView.STORE, storage.shoppingView())
    }

    @Test
    fun theChosenStoreIsRememberedUntilCleared() {
        val storage = MemorySettingsStorage()
        assertEquals(null, storage.shoppingStore())

        storage.saveShoppingStore(Store.JUMBO)
        assertEquals(Store.JUMBO, storage.shoppingStore())
        storage.saveShoppingStore(null)
        assertEquals(null, storage.shoppingStore())
    }

    @Test
    fun allThreeStoresAreInUseUntilNarrowed() {
        val storage = MemorySettingsStorage()
        assertEquals(Store.entries.toSet(), storage.shops())

        storage.saveShops(setOf(Store.AH, Store.LIDL))
        assertEquals(setOf(Store.AH, Store.LIDL), storage.shops())
    }

    @Test
    fun anEmptyOrUnreadableStoreSettingMeansAllStores() {
        val storage = MemorySettingsStorage()
        storage.saveShops(emptySet())
        assertEquals(Store.entries.toSet(), storage.shops())
        storage.set("shops", "NOPE")
        assertEquals(Store.entries.toSet(), storage.shops())

        assertFalse(storage.shopsMoved(), "a device's own choice is not yet offered to the account")
        storage.markShopsMoved()
        assertTrue(storage.shopsMoved())
    }
}
