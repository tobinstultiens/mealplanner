package dev.tobins.mealplanner.app.shopping

import dev.tobins.mealplanner.api.OfferExclusion
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.app.SettingsStorage
import dev.tobins.mealplanner.app.prices.ExclusionChange
import dev.tobins.mealplanner.app.shops
import dev.tobins.mealplanner.app.ui.mondayOf
import dev.tobins.mealplanner.app.ui.plusDays
import dev.tobins.mealplanner.app.ui.todayIso
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The last shopping list seen per week, and the ticks the server has not confirmed yet, so the
 * list works in a shop without reception and no tick is lost when the app is closed before it
 * was sent. The same for "Not this ingredient" (issue 316): the account's list as the server last
 * gave it, and the exclusions made or taken back here that wait to be sent.
 *
 * One cache belongs to one login: it writes nothing once the stored lists were cleared after it
 * was made ([clear], on logging out), so a request of the previous account that is answered just
 * after that never puts its list or ticks back for whoever logs in next.
 */
class ShoppingCache(private val storage: SettingsStorage) {
    // Lists stored by earlier versions of the app must keep loading: fields are only added, with defaults.
    @Serializable
    private data class Entry(
        val list: ShoppingList,
        val savedAt: String,
        val pending: Map<String, Boolean> = emptyMap(),
        /** Of the waiting ticks of buy-ahead and stock-up items: the grams the list advised buying when ticked. */
        val advised: Map<String, Double> = emptyMap(),
        /** Of the waiting ticks of rows of what the meals need: what the row showed when ticked, for the server to remember when it arrives late. */
        val shown: Map<String, ShoppingCheck.Shown> = emptyMap(),
    ) {
        /** This entry without the waiting tick of [key]: the server has it, or refused it. */
        fun settled(key: String) = copy(pending = pending - key, advised = advised - key, shown = shown - key)
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** How many times the stored lists had been cleared when this cache was made. */
    private val generation = storage.get(GENERATION_KEY).orEmpty()

    /** False once the stored lists were cleared after this cache was made: it belongs to an account that logged out. */
    private fun current() = storage.get(GENERATION_KEY).orEmpty() == generation

    private fun key(weekStart: String) = "shopping.$weekStart"

    private fun entry(weekStart: String): Entry? =
        storage.get(key(weekStart))?.let { runCatching { json.decodeFromString<Entry>(it) }.getOrNull() }

    private fun write(weekStart: String, entry: Entry) {
        if (!current()) return
        val weeks = weeks()
        if (weekStart !in weeks) storage.set(WEEKS_KEY, (weeks + weekStart).joinToString(","))
        storage.set(key(weekStart), json.encodeToString(entry))
    }

    /** The weeks with a stored list, so that [clear] and [tidy] can find them: the storage cannot list its keys. */
    private fun weeks(): List<String> = storage.get(WEEKS_KEY).orEmpty().split(',').filter { it.isNotBlank() }

    /** Weeks around [thisWeek] with a list stored by app versions from before the list of weeks. */
    private fun legacyWeeks(thisWeek: String) =
        (-LEGACY_WEEKS_BACK..LEGACY_WEEKS_AHEAD).map { plusDays(thisWeek, 7 * it) }.filter { !storage.get(key(it)).isNullOrEmpty() }

    /** Stores a list fresh from the server in place of the copy; ticks still waiting for the server stay. */
    fun save(list: ShoppingList, savedAt: String) =
        write(list.weekStart, entry(list.weekStart)?.copy(list = list, savedAt = savedAt) ?: Entry(list, savedAt))

    /** The stored list with the waiting ticks applied, and when it was saved; null if none. */
    fun load(weekStart: String): Pair<ShoppingList, String>? = entry(weekStart)?.let { entry ->
        entry.list.withTicks(entry.pending) to entry.savedAt
    }

    /**
     * Records a tick before it is sent, so it survives the app being closed or the connection
     * dropping. Only over a stored list: every list the user can tick was stored first, and after
     * [clear] (logging out) nothing of the previous account may be written back. Ticking off a
     * buy-ahead or stock-up item records the grams the list advises buying too ([advised]): the
     * answer to an earlier tick may replace the stored list with one that no longer advises it.
     * Ticking off a row of what the meals need records what the row shows on [today] too ([shown]),
     * with the offer's saving at the account's stores: a tick sent late has the offer's end behind it,
     * and the server could no longer tell what it saved.
     */
    fun choose(weekStart: String, key: String, checked: Boolean, today: String = todayIso()) {
        val entry = entry(weekStart) ?: return
        val grams = if (checked) (entry.list.buyAhead + entry.list.stockUp).firstOrNull { it.key == key }?.grams else null
        val advised = if (grams != null) entry.advised + (key to grams) else entry.advised - key
        val row = if (checked && !key.startsWith("x:")) entry.list.items.firstOrNull { it.key == key } else null
        val shown = if (row != null) entry.shown + (key to row.shownAtTick(storage.shops(), today)) else entry.shown - key
        write(weekStart, entry.copy(pending = entry.pending + (key to checked), advised = advised, shown = shown))
    }

    /**
     * Stores the server's [answer] to the tick of [key]: the list replaces the copy and the tick
     * no longer waits, unless the user has chosen differently since.
     */
    fun confirm(answer: ShoppingList, savedAt: String, key: String, checked: Boolean) {
        val entry = entry(answer.weekStart)?.copy(list = answer, savedAt = savedAt) ?: Entry(answer, savedAt)
        write(answer.weekStart, if (entry.pending[key] == checked) entry.settled(key) else entry)
    }

    /** Drops a tick the server refused, unless the user has chosen differently since. */
    fun forget(weekStart: String, key: String, checked: Boolean) {
        val entry = entry(weekStart) ?: return
        if (entry.pending[key] == checked) write(weekStart, entry.settled(key))
    }

    /** The grams the list advised buying of the buy-ahead or stock-up item [key] when its waiting tick was made; null if unknown. */
    fun advised(weekStart: String, key: String): Double? = entry(weekStart)?.advised?.get(key)

    /** What the row [key] showed when its waiting tick was made ([choose]); null if unknown, and for an un-tick. */
    fun shown(weekStart: String, key: String): ShoppingCheck.Shown? = entry(weekStart)?.shown?.get(key)

    /** The ticks of [weekStart] the server has not confirmed yet, by item key. */
    fun pending(weekStart: String): Map<String, Boolean> = entry(weekStart)?.pending.orEmpty()

    // An earlier version of the app stored none: no key reads as none of either.
    @Serializable
    private data class Exclusions(val known: List<OfferExclusion> = emptyList(), val waiting: List<ExclusionChange> = emptyList())

    private fun exclusions(): Exclusions =
        storage.get(EXCLUSIONS_KEY)?.let { runCatching { json.decodeFromString<Exclusions>(it) }.getOrNull() } ?: Exclusions()

    private fun writeExclusions(exclusions: Exclusions) {
        if (current()) storage.set(EXCLUSIONS_KEY, json.encodeToString(exclusions))
    }

    /** The account's exclusions as the server last listed them. */
    fun knownExclusions(): List<OfferExclusion> = exclusions().known

    /** The exclusions made or taken back on this device that the server has not confirmed yet, oldest first: one per [ExclusionKey]. */
    internal fun waitingExclusions(): List<ExclusionChange> = exclusions().waiting

    /** Records [change] before it is sent, in place of an earlier change of the same exclusion that has not gone out yet. */
    internal fun chooseExclusion(change: ExclusionChange) {
        val stored = exclusions()
        writeExclusions(stored.copy(waiting = stored.waiting.filter { it.key != change.key } + change))
    }

    /** Stores the account's list the server answered [change] with; the change no longer waits, unless the user has chosen differently since. */
    internal fun confirmExclusion(change: ExclusionChange, known: List<OfferExclusion>) =
        writeExclusions(Exclusions(known, exclusions().waiting - change))

    /** Drops [change], which the server refused, unless the user has chosen differently since. */
    internal fun forgetExclusion(change: ExclusionChange) {
        val stored = exclusions()
        writeExclusions(stored.copy(waiting = stored.waiting - change))
    }

    /** Stores the account's list as the server gave it, asked for on its own. */
    fun saveKnownExclusions(known: List<OfferExclusion>) = writeExclusions(exclusions().copy(known = known))

    /** The weeks with ticks the server has not confirmed yet, oldest first, so they are sent in the order they were made. */
    fun weeksWithTicks(): List<String> = weeks().sorted().filter { pending(it).isNotEmpty() }

    /**
     * Keeps the stored lists from growing for ever: drops those of weeks more than [KEEP_WEEKS]
     * weeks before [today]'s, unless ticks of theirs still wait for the server, and any that no
     * longer reads. Lists stored before the list of weeks existed are taken into it, so their
     * ticks are sent with the rest ([weeksWithTicks]).
     */
    fun tidy(today: String = todayIso()) {
        if (!current()) return
        val thisWeek = mondayOf(today)
        val oldest = plusDays(thisWeek, -7 * KEEP_WEEKS)
        val (keep, drop) = (weeks() + legacyWeeks(thisWeek)).distinct().partition { week ->
            entry(week) != null && (week >= oldest || pending(week).isNotEmpty())
        }
        drop.forEach { storage.remove(key(it)) }
        storage.set(WEEKS_KEY, keep.sorted().joinToString(","))
    }

    /**
     * Forgets every stored list and waiting tick, and the exclusions, for when another account may log in on this
     * device: its ticks must never reach that account's list. Every cache made before this one
     * writes nothing from now on. Lists stored by app versions from before the list of weeks are
     * found by looking at the weeks around [today].
     */
    fun clear(today: String = todayIso()) {
        storage.set(GENERATION_KEY, ((storage.get(GENERATION_KEY)?.toIntOrNull() ?: 0) + 1).toString())
        (weeks() + legacyWeeks(mondayOf(today))).toSet().forEach { storage.remove(key(it)) }
        storage.remove(WEEKS_KEY)
        storage.remove(EXCLUSIONS_KEY)
    }

    private companion object {
        const val WEEKS_KEY = "shopping.weeks"
        const val GENERATION_KEY = "shopping.generation"
        const val EXCLUSIONS_KEY = "shopping.exclusions"
        const val LEGACY_WEEKS_BACK = 52
        const val LEGACY_WEEKS_AHEAD = 8

        /** Stored lists of older weeks without waiting ticks are dropped; a month back is plenty to look up what was bought. */
        const val KEEP_WEEKS = 4
    }
}
