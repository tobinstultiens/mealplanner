package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.ShoppingList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The last shopping list seen per week, and ticks made while the server was unreachable,
 * so the list works in a shop without reception.
 */
class ShoppingCache(private val storage: SettingsStorage) {
    @Serializable
    private data class Entry(val list: ShoppingList, val savedAt: String, val pending: Map<String, Boolean> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }

    private fun key(weekStart: String) = "shopping.$weekStart"

    private fun entry(weekStart: String): Entry? =
        storage.get(key(weekStart))?.let { runCatching { json.decodeFromString<Entry>(it) }.getOrNull() }

    private fun write(weekStart: String, entry: Entry) = storage.set(key(weekStart), json.encodeToString(entry))

    /** Stores a list fresh from the server; it replaces the copy, and its ticks are the truth. */
    fun save(list: ShoppingList, savedAt: String) = write(list.weekStart, Entry(list, savedAt))

    /** The stored list with offline ticks applied, and when it was saved; null if none. */
    fun load(weekStart: String): Pair<ShoppingList, String>? = entry(weekStart)?.let { entry ->
        entry.list.copy(items = entry.list.items.map { item -> entry.pending[item.key]?.let { item.copy(checked = it) } ?: item }) to entry.savedAt
    }

    /** Records a tick made while offline, to send once the server is reachable. */
    fun tickOffline(weekStart: String, key: String, checked: Boolean) {
        val entry = entry(weekStart) ?: return
        write(weekStart, entry.copy(pending = entry.pending + (key to checked)))
    }

    fun pending(weekStart: String): Map<String, Boolean> = entry(weekStart)?.pending.orEmpty()

    fun clearPending(weekStart: String) {
        entry(weekStart)?.let { write(weekStart, it.copy(pending = emptyMap())) }
    }
}
