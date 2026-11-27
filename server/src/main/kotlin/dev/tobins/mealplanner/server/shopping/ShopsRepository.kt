package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.writing
import kotlinx.serialization.json.Json

/**
 * The stores one account shops at, kept with its other small settings in `userMeta`, so no table
 * changes. Stored as a JSON list of store names; an account without one shops everywhere.
 */
class ShopsRepository(private val db: Database, private val userId: Long) {
    fun setting(): ShopsSetting = ShopsSetting(stored())

    /** The stores whose offers count for this account's plans and costs. */
    fun inUse(): Set<Store> = setting().storesInUse()

    /** Saves the account's stores; null goes back to every store. An empty list is refused. */
    fun save(setting: ShopsSetting): ShopsSetting {
        val stores = setting.stores
        require(stores == null || stores.isNotEmpty()) { "Choose at least one store" }
        db.writing {
            if (stores == null) {
                db.userQueries.deleteUserMeta(userId, KEY)
            } else {
                db.userQueries.upsertUserMeta(userId, KEY, Json.encodeToString(stores.distinct().sortedBy { it.ordinal }.map { it.name }))
            }
        }
        return setting()
    }

    /** Null when never chosen. A name this version doesn't know (a store dropped later) is skipped. */
    private fun stored(): List<Store>? {
        val names = db.userQueries.selectUserMeta(userId, KEY).executeAsOneOrNull()?.let { Json.decodeFromString<List<String>>(it) }
            ?: return null
        return Store.entries.filter { it.name in names }.ifEmpty { null }
    }

    private companion object {
        const val KEY = "shops"
    }
}
