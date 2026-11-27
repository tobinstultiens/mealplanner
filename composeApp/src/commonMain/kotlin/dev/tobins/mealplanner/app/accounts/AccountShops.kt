package dev.tobins.mealplanner.app.accounts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.app.SettingsStorage
import dev.tobins.mealplanner.app.markShopsMoved
import dev.tobins.mealplanner.app.saveShops
import dev.tobins.mealplanner.app.shops
import dev.tobins.mealplanner.app.shopsMoved
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Stores I shop at": an account setting on the server, since the planner uses it too. The device
 * keeps the last value the server confirmed, for the per-store list without a connection.
 *
 * Before this was an account setting it was kept on each device. The first time a device reaches
 * the server after the update, its own choice goes to the account if the account has none yet;
 * from then on the server's value wins, also over what this device chose before. A device that
 * never narrowed the stores sends nothing, so another device's choice can still be the one kept.
 */
class AccountShops(
    private val storage: SettingsStorage,
    private val fetch: suspend () -> ShopsSetting,
    private val send: suspend (ShopsSetting) -> ShopsSetting,
) {
    /** The stores in use as shown: a choice still on its way to the server already counts. */
    var stores: Set<Store> by mutableStateOf(storage.shops())
        private set

    /** The user's latest choice while it is not saved yet. */
    private var wanted: Set<Store>? = null

    private var confirmed = stores

    // One request at a time, so an answer never overtakes a later one.
    private val turn = Mutex()

    /** Reads the account's stores, after moving this device's earlier choice there if it is the first. Throws when offline. */
    suspend fun sync() = turn.withLock {
        val setting = fetch().takeIf { it.stores != null } ?: deviceChoice()?.let { send(ShopsSetting(it.sortedBy(Store::ordinal))) }
        storage.markShopsMoved()
        confirm(setting?.storesInUse() ?: Store.entries.toSet())
    }

    /**
     * Shows [chosen] at once and saves it to the account. When saving fails the stores go back
     * to what the server last confirmed and the error is thrown.
     */
    suspend fun choose(chosen: Set<Store>) {
        wanted = chosen
        stores = chosen
        turn.withLock {
            // The user chose again meanwhile; that call sends the newer choice.
            if (wanted != chosen) return
            val saved = try {
                send(ShopsSetting(chosen.sortedBy(Store::ordinal))).storesInUse()
            } catch (e: Exception) {
                if (wanted == chosen) {
                    wanted = null
                    stores = confirmed
                }
                throw e
            }
            if (wanted == chosen) wanted = null
            confirm(saved)
        }
    }

    /** The server's answer; shown unless the user has chosen again since. */
    private fun confirm(value: Set<Store>) {
        confirmed = value
        storage.saveShops(value)
        if (wanted == null) stores = value
    }

    /** This device's choice from before the setting moved to the account; null when it chose none or it moved already. */
    private fun deviceChoice(): Set<Store>? =
        storage.shops().takeIf { !storage.shopsMoved() && it != Store.entries.toSet() }
}
