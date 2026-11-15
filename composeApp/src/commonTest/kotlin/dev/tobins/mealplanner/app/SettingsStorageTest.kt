package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Store
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStorageTest {
    @Test
    fun savedSettingsReplaceTheBuiltInDefaults() {
        val defaults = ServerSettings("http://10.0.2.2:8080")
        val storage = MemorySettingsStorage()
        assertEquals(defaults, storage.serverSettings(defaults))

        storage.saveServerSettings(ServerSettings(" https://meals.example.nl/ "))
        assertEquals(ServerSettings("https://meals.example.nl"), storage.serverSettings(defaults))
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
    }
}
