package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The ticks of one week's shopping list: a tap shows at once and the request follows in the
 * background.
 *
 * A tick is written to the [cache] as waiting before anything is sent, and taken off when the
 * server has dealt with it, so closing the app or losing the connection never loses it.
 * What the user chose is laid over the last list the server confirmed, so an answer to an earlier
 * tap never flips a row back while a later tap is still on its way (on a slow connection the
 * answers arrive well after the next tap). Requests go out one at a time ([turn], which the
 * owner may share with its other requests), each sending the latest choice for its row, so the
 * server ends in the state the user last chose.
 * Without an answer the tick stays waiting for later ([offline]); a refusal puts the row back
 * ([rejected]).
 */
internal class ShoppingTicks(
    private val scope: CoroutineScope,
    private val weekStart: String,
    private val cache: ShoppingCache,
    private val send: suspend (key: String, checked: Boolean) -> Load<ShoppingList>,
    private val isOffline: () -> Boolean,
    /** Called with the list to display whenever it changes. */
    private val show: (ShoppingList) -> Unit,
    /** Called for a tick that got no answer, or was not sent for lack of a connection; it stays waiting in the cache. */
    private val offline: (key: String, checked: Boolean) -> Unit,
    private val rejected: (message: String) -> Unit,
    private val turn: Mutex = Mutex(),
) {
    private var base: ShoppingList? = null

    /** Choices no request has dealt with yet; a request sends the latest one for its row. */
    private val wanted = mutableMapOf<String, Boolean>()

    /** The ticks waiting in the cache, as kept there; [wanted] ones among them. */
    private var waiting = mutableMapOf<String, Boolean>()

    /** The rows a request of this week is still to be sent for. */
    val unsent: Set<String> get() = wanted.keys

    private fun overlay(list: ShoppingList) = list.withTicks(waiting)

    /** Takes a list from the server (or the stored copy); returns it with the waiting ticks laid over. */
    fun reset(list: ShoppingList): ShoppingList {
        base = list
        waiting = cache.pending(weekStart).toMutableMap()
        return overlay(list)
    }

    /** The user ticks or un-ticks [key]: kept, shown now, sent in turn. */
    fun choose(key: String, checked: Boolean) {
        val current = base ?: return
        cache.choose(weekStart, key, checked)
        wanted[key] = checked
        waiting[key] = checked
        show(overlay(current))
        scope.launch { turn.withLock { flush(key) } }
    }

    private suspend fun flush(key: String) {
        // A tap that came later has already been sent with this one.
        val checked = wanted[key] ?: return
        val current = base ?: return
        if (isOffline()) return keepForLater(key, checked)
        when (val result = send(key, checked)) {
            is Load.Ready -> {
                base = result.value
                cache.confirm(result.value, nowIso(), key, checked)
                settle(key, checked)
                show(overlay(result.value))
            }
            is Load.Failed -> if (result.refused) {
                cache.forget(weekStart, key, checked)
                settle(key, checked)
                show(overlay(current))
                rejected(result.message)
            } else {
                keepForLater(key, checked)
            }
            Load.Loading -> Unit
        }
    }

    private fun keepForLater(key: String, checked: Boolean) {
        if (wanted[key] == checked) wanted.remove(key)
        offline(key, checked)
    }

    /** The server has dealt with the choice for [key], unless the user has changed their mind since. */
    private fun settle(key: String, checked: Boolean) {
        if (wanted[key] == checked) wanted.remove(key)
        if (waiting[key] == checked) waiting.remove(key)
    }
}

/**
 * Whether the server refused the request as such (a 4xx), so sending it again would not help.
 * No answer, a server error (5xx), an expired login (401), a timeout (408) or too many requests
 * (429) can pass, so those are tried again later.
 */
internal val Load.Failed.refused: Boolean
    get() = status != null && status in 400..499 && status !in setOf(401, 408, 429)

/**
 * The list with [ticks] (checked or not, by item key) laid over every group that can be ticked:
 * this week's items and the buy-ahead and stock-up advice; an un-ticked row leaves the group of
 * bought items no meal needs any more.
 */
internal fun ShoppingList.withTicks(ticks: Map<String, Boolean>): ShoppingList {
    if (ticks.isEmpty()) return this
    fun List<ShoppingItem>.ticked() = map { item -> ticks[item.key]?.let { item.copy(checked = it) } ?: item }
    // An un-ticked row of the bought-but-no-longer-needed group goes at once, as the server takes it off.
    val stillBought = noLongerNeeded.filter { ticks[it.key] != false }
    return copy(items = items.ticked(), buyAhead = buyAhead.ticked(), stockUp = stockUp.ticked(), noLongerNeeded = stillBought)
}
