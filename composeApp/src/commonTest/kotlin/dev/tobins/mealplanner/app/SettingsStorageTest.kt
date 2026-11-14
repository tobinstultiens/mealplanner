package dev.tobins.mealplanner.app

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
}
