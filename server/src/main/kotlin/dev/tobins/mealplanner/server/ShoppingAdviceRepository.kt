package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.server.db.Database
import kotlinx.serialization.json.Json

/**
 * Which advice one account's shopping list gives (buy ahead, stock up), kept with its other small
 * settings in `userMeta`, so no table changes. An account that never chose gets both.
 */
class ShoppingAdviceRepository(private val db: Database, private val userId: Long) {
    // Choices are stored in full, so a later change of the defaults doesn't change what was chosen.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun setting(): ShoppingAdvice =
        db.userQueries.selectUserMeta(userId, KEY).executeAsOneOrNull()?.let { json.decodeFromString<ShoppingAdvice>(it) } ?: ShoppingAdvice()

    fun save(advice: ShoppingAdvice): ShoppingAdvice {
        db.writing { db.userQueries.upsertUserMeta(userId, KEY, json.encodeToString(advice)) }
        return setting()
    }

    private companion object {
        const val KEY = "shoppingAdvice"
    }
}
