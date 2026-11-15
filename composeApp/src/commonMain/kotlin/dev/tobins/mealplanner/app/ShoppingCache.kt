package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.ShoppingList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The last shopping list seen per week, and the ticks the server has not confirmed yet, so the
 * list works in a shop without reception and no tick is lost when the app is closed before it
 * was sent.
 */
class ShoppingCache(private val storage: SettingsStorage) {
    // Lists stored by earlier versions of the app must keep loading: fields are only added, with defaults.
    @Serializable
    private data class Entry(val list: ShoppingList, val savedAt: String, val pending: Map<String, Boolean> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }

    private fun key(weekStart: String) = "shopping.$weekStart"

    private fun entry(weekStart: String): Entry? =
        storage.get(key(weekStart))?.let { runCatching { json.decodeFromString<Entry>(it) }.getOrNull() }

    private fun write(weekStart: String, entry: Entry) {
        val weeks = weeks()
        if (weekStart !in weeks) storage.set(WEEKS_KEY, (weeks + weekStart).joinToString(","))
        storage.set(key(weekStart), json.encodeToString(entry))
    }

    /** The weeks with a stored list, so that [clear] can find them: the storage cannot list its keys. */
    private fun weeks(): List<String> = storage.get(WEEKS_KEY).orEmpty().split(',').filter { it.isNotBlank() }

    /** Stores a list fresh from the server in place of the copy; ticks still waiting for the server stay. */
    fun save(list: ShoppingList, savedAt: String) =
        write(list.weekStart, Entry(list, savedAt, pending(list.weekStart)))

    /** The stored list with the waiting ticks applied, and when it was saved; null if none. */
    fun load(weekStart: String): Pair<ShoppingList, String>? = entry(weekStart)?.let { entry ->
        entry.list.withTicks(entry.pending) to entry.savedAt
    }

    /**
     * Records a tick before it is sent, so it survives the app being closed or the connection
     * dropping. Only over a stored list: every list the user can tick was stored first, and after
     * [clear] (logging out) nothing of the previous account may be written back.
     */
    fun choose(weekStart: String, key: String, checked: Boolean) {
        val entry = entry(weekStart) ?: return
        write(weekStart, entry.copy(pending = entry.pending + (key to checked)))
    }

    /**
     * Stores the server's [answer] to the tick of [key]: the list replaces the copy and the tick
     * no longer waits, unless the user has chosen differently since.
     */
    fun confirm(answer: ShoppingList, savedAt: String, key: String, checked: Boolean) {
        val pending = pending(answer.weekStart)
        write(answer.weekStart, Entry(answer, savedAt, if (pending[key] == checked) pending - key else pending))
    }

    /** Drops a tick the server refused, unless the user has chosen differently since. */
    fun forget(weekStart: String, key: String, checked: Boolean) {
        val entry = entry(weekStart) ?: return
        if (entry.pending[key] == checked) write(weekStart, entry.copy(pending = entry.pending - key))
    }

    /** The ticks of [weekStart] the server has not confirmed yet, by item key. */
    fun pending(weekStart: String): Map<String, Boolean> = entry(weekStart)?.pending.orEmpty()

    /**
     * Forgets every stored list and waiting tick, for when another account may log in on this
     * device: its ticks must never reach that account's list. Lists stored by app versions from
     * before the list of weeks are found by looking at the weeks around [today].
     */
    fun clear(today: String = todayIso()) {
        val thisWeek = mondayOf(today)
        val earlier = (-LEGACY_WEEKS_BACK..LEGACY_WEEKS_AHEAD).map { plusDays(thisWeek, 7 * it) }.filter { !storage.get(key(it)).isNullOrEmpty() }
        (weeks() + earlier).toSet().forEach { storage.set(key(it), "") }
        storage.set(WEEKS_KEY, "")
    }

    private companion object {
        const val WEEKS_KEY = "shopping.weeks"
        const val LEGACY_WEEKS_BACK = 52
        const val LEGACY_WEEKS_AHEAD = 8
    }
}
