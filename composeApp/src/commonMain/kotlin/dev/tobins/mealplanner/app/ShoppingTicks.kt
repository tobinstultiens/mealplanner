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
 * What the user chose ([wanted]) is laid over the last list the server confirmed, so an answer to
 * an earlier tap never flips a row back while a later tap is still on its way (on a slow
 * connection the answers arrive well after the next tap). Requests go out one at a time, each
 * sending the latest choice for its row, so the server ends in the state the user last chose.
 * Without a connection the tick is kept for later ([offline]); a refusal puts the row back
 * ([rejected]).
 */
internal class ShoppingTicks(
    private val scope: CoroutineScope,
    private val send: suspend (key: String, checked: Boolean) -> Load<ShoppingList>,
    private val isOffline: () -> Boolean,
    /** Called with the list to display whenever it changes. */
    private val show: (ShoppingList) -> Unit,
    /** Called with a list the server just answered, to keep as the stored copy. */
    private val saved: (ShoppingList) -> Unit,
    /** Called for a tick that could not be sent for lack of a connection, with the list as shown. */
    private val offline: (list: ShoppingList, key: String, checked: Boolean) -> Unit,
    private val rejected: suspend (message: String) -> Unit,
) {
    private var base: ShoppingList? = null
    private val wanted = mutableMapOf<String, Boolean>()
    private val turn = Mutex()

    private fun overlay(list: ShoppingList) = list.withTicks(wanted)

    /** Takes a list from the server (or the stored copy); returns it with unsent ticks laid over. */
    fun reset(list: ShoppingList): ShoppingList {
        base = list
        return overlay(list)
    }

    /** The user ticks or un-ticks [key]: shown now, sent in turn. */
    fun choose(key: String, checked: Boolean) {
        val current = base ?: return
        wanted[key] = checked
        show(overlay(current))
        scope.launch { turn.withLock { flush(key) } }
    }

    private suspend fun flush(key: String) {
        // A tap that came later has already been sent with this one.
        val checked = wanted[key] ?: return
        val current = base ?: return
        if (isOffline()) return keepForLater(current, key, checked)
        when (val result = send(key, checked)) {
            is Load.Ready -> {
                base = result.value
                settle(key, checked)
                saved(result.value)
                show(overlay(result.value))
            }
            is Load.Failed -> if (result.status == null) {
                keepForLater(current, key, checked)
            } else {
                settle(key, checked)
                show(overlay(current))
                rejected(result.message)
            }
            Load.Loading -> Unit
        }
    }

    private fun keepForLater(current: ShoppingList, key: String, checked: Boolean) {
        offline(overlay(current), key, checked)
        base = current.withTicks(mapOf(key to checked))
        settle(key, checked)
    }

    /** The choice for [key] is dealt with, unless the user has changed their mind since. */
    private fun settle(key: String, checked: Boolean) {
        if (wanted[key] == checked) wanted.remove(key)
    }
}

/**
 * The list with [ticks] (checked or not, by item key) laid over every group that can be ticked:
 * this week's items and the buy-ahead and stock-up advice.
 */
internal fun ShoppingList.withTicks(ticks: Map<String, Boolean>): ShoppingList {
    if (ticks.isEmpty()) return this
    fun List<ShoppingItem>.ticked() = map { item -> ticks[item.key]?.let { item.copy(checked = it) } ?: item }
    return copy(items = items.ticked(), buyAhead = buyAhead.ticked(), stockUp = stockUp.ticked())
}
