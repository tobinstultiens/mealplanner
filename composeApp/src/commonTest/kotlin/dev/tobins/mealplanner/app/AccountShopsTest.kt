package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.Store
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccountShopsTest {
    private val all = Store.entries.toSet()

    /** The account's setting on the server; each request takes [latency] ms, and fails while [down]. */
    private class FakeServer(var setting: ShopsSetting = ShopsSetting(), var latency: Long = 0, var down: Boolean = false) {
        val sent = mutableListOf<List<Store>>()

        suspend fun get(): ShopsSetting {
            delay(latency)
            check(!down) { "offline" }
            return setting
        }

        suspend fun put(new: ShopsSetting): ShopsSetting {
            delay(latency)
            check(!down) { "offline" }
            sent += new.stores.orEmpty()
            setting = new
            return setting
        }
    }

    private fun shops(storage: SettingsStorage, server: FakeServer) = AccountShops(storage, server::get, server::put)

    @Test
    fun aDevicesEarlierChoiceGoesToAnAccountWithoutOneOnce() = runTest {
        val storage = MemorySettingsStorage().also { it.saveShops(setOf(Store.AH, Store.LIDL)) }
        val server = FakeServer()
        val shops = shops(storage, server)

        shops.sync()
        assertEquals(listOf(listOf(Store.AH, Store.LIDL)), server.sent)
        assertEquals(setOf(Store.AH, Store.LIDL), shops.stores)

        // Later the account has none again (another account on this device, say): nothing is sent.
        server.setting = ShopsSetting()
        shops(storage, server).sync()
        assertEquals(1, server.sent.size)
        assertEquals(all, storage.shops(), "an account that never chose shops everywhere")
    }

    @Test
    fun theAccountsChoiceWinsOverTheDevices() = runTest {
        val storage = MemorySettingsStorage().also { it.saveShops(setOf(Store.LIDL)) }
        val server = FakeServer(ShopsSetting(listOf(Store.JUMBO)))
        val shops = shops(storage, server)
        assertEquals(setOf(Store.LIDL), shops.stores, "the device's value until the server answers")

        shops.sync()
        assertEquals(setOf(Store.JUMBO), shops.stores)
        assertEquals(setOf(Store.JUMBO), storage.shops(), "kept for the list without a connection")
        assertEquals(emptyList(), server.sent)
    }

    @Test
    fun aDeviceThatNeverNarrowedTheStoresSendsNothing() = runTest {
        val storage = MemorySettingsStorage()
        val server = FakeServer()
        shops(storage, server).sync()
        assertEquals(emptyList(), server.sent)
        assertTrue(storage.shopsMoved())

        // Another device's choice made since then is what this one shows.
        server.setting = ShopsSetting(listOf(Store.AH))
        val shops = shops(storage, server)
        shops.sync()
        assertEquals(setOf(Store.AH), shops.stores)
    }

    @Test
    fun withoutAConnectionTheDevicesChoiceWaitsForTheNextStart() = runTest {
        val storage = MemorySettingsStorage().also { it.saveShops(setOf(Store.AH)) }
        val server = FakeServer(down = true)
        val shops = shops(storage, server)
        assertFailsWith<IllegalStateException> { shops.sync() }
        assertEquals(setOf(Store.AH), shops.stores, "the stored value stays in use")

        server.down = false
        shops.sync()
        assertEquals(listOf(listOf(Store.AH)), server.sent)
    }

    @Test
    fun aChoiceShowsAtOnceAndGoesBackWhenItCannotBeSaved() = runTest {
        val storage = MemorySettingsStorage()
        val server = FakeServer(latency = 1000)
        val shops = shops(storage, server)

        val saving = launch { shops.choose(setOf(Store.AH)) }
        runCurrent()
        assertEquals(setOf(Store.AH), shops.stores, "before the server answered")
        saving.join()
        assertEquals(ShopsSetting(listOf(Store.AH)), server.setting)
        assertEquals(setOf(Store.AH), storage.shops())

        server.down = true
        assertFailsWith<IllegalStateException> { shops.choose(setOf(Store.AH, Store.LIDL)) }
        assertEquals(setOf(Store.AH), shops.stores, "back to what the server has")
        assertEquals(setOf(Store.AH), storage.shops())
    }

    @Test
    fun anEarlierAnswerNeverUndoesALaterChoice() = runTest {
        val storage = MemorySettingsStorage()
        val server = FakeServer(latency = 1000)
        val shops = shops(storage, server)

        launch { shops.choose(setOf(Store.AH)) }
        runCurrent()
        launch { shops.choose(setOf(Store.AH, Store.JUMBO)) }
        launch { shops.choose(setOf(Store.JUMBO)) }
        runCurrent()
        assertEquals(setOf(Store.JUMBO), shops.stores)
        advanceUntilIdle()

        assertEquals(setOf(Store.JUMBO), shops.stores)
        // The middle choice was overtaken before its turn and never sent.
        assertEquals(listOf(listOf(Store.AH), listOf(Store.JUMBO)), server.sent)
        assertEquals(setOf(Store.JUMBO), storage.shops())
    }

    @Test
    fun anAccountWithoutAChoiceShopsEverywhere() {
        assertNull(ShopsSetting().stores)
        assertEquals(all, ShopsSetting().storesInUse())
        assertEquals(all, ShopsSetting(emptyList()).storesInUse())
        assertEquals(setOf(Store.LIDL), ShopsSetting(listOf(Store.LIDL)).storesInUse())
    }

    @Test
    fun theNextAccountOnTheDeviceNeverSeesTheLastOnesStores() = runTest {
        val storage = MemorySettingsStorage()
        val first = shops(storage, FakeServer(ShopsSetting(listOf(Store.AH))))
        first.sync()
        assertEquals(setOf(Store.AH), first.stores)

        storage.logOut()

        val second = shops(storage, FakeServer(latency = 100))
        assertEquals(all, second.stores, "before the second account's answer arrives")
        assertEquals(null, storage.session())
    }

    @Test
    fun theSettingsAndTheShoppingListShareOneChoice() = runTest {
        val server = FakeServer()
        val shared = shops(MemorySettingsStorage(), server)
        // Both screens are handed this one instance: a choice made in either shows in the other.
        shared.choose(setOf(Store.LIDL))
        assertEquals(setOf(Store.LIDL), shared.stores)
        assertEquals(listOf(listOf(Store.LIDL)), server.sent)
    }
}
