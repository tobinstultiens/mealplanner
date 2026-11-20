package dev.tobins.mealplanner.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingList

/** What the shopping list needs from the server; failures come back as [Load.Failed], never thrown. */
internal interface ShoppingServer {
    suspend fun list(weekStart: String): Load<ShoppingList>

    /** [grams]: what the list advised buying, for ticking off a buy-ahead or stock-up item; null otherwise. [shown]: what the phone showed of the row, for a tick sent late. */
    suspend fun check(weekStart: String, key: String, checked: Boolean, grams: Double?, shown: ShoppingCheck.Shown? = null): Load<ShoppingList>

    suspend fun dismiss(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean): Load<ShoppingList>

    suspend fun addExtra(weekStart: String, name: String): Load<ShoppingList>

    suspend fun removeExtra(weekStart: String, id: Long): Load<ShoppingList>

    /** Ticks the ticked row [key] again for what it needs now, in one step; null from a server from before that. */
    suspend fun tickAgain(weekStart: String, key: String): Load<ShoppingList>?

    /** Puts what was bought of [key], which no meal needs any more, on hand and out of that group; null from a server from before that. */
    suspend fun useUp(weekStart: String, key: String): Load<ShoppingList>?

    /** Puts the ingredient on hand in the pantry, as set by hand: how the app used up a bought item before [useUp]. */
    suspend fun putOnHand(ingredientId: Long): Load<Unit>
}

internal fun ApiClient.shoppingServer(): ShoppingServer = object : ShoppingServer {
    override suspend fun list(weekStart: String) = load { shoppingList(weekStart) }

    override suspend fun check(weekStart: String, key: String, checked: Boolean, grams: Double?, shown: ShoppingCheck.Shown?) =
        load { setShoppingChecked(weekStart, key, checked, grams, shown) }

    override suspend fun dismiss(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean) =
        load { setShoppingDismissed(weekStart, group, dismissed) }

    override suspend fun addExtra(weekStart: String, name: String) = load { addShoppingExtra(weekStart, name) }

    override suspend fun removeExtra(weekStart: String, id: Long) = load { removeShoppingExtra(weekStart, id) }

    override suspend fun tickAgain(weekStart: String, key: String) = loadUnlessOld { tickShoppingAgain(weekStart, key) }

    override suspend fun useUp(weekStart: String, key: String) = loadUnlessOld { useUpShopping(weekStart, key) }

    override suspend fun putOnHand(ingredientId: Long) = load { setOnHand(ingredientId, true); Unit }
}

/** [load] of a call that answers null from a server from before its route; then null as well. */
private suspend fun <T : Any> loadUnlessOld(block: suspend () -> T?): Load<T>? = when (val result = load(block)) {
    is Load.Ready -> result.value?.let { Load.Ready(it) }
    is Load.Failed -> result
    Load.Loading -> Load.Loading
}

/**
 * Something to tell the user in a snackbar the next time the list shows: [msg] filled in with
 * [detail]. [technical] is an exception's own text that goes with it, kept behind "Details".
 */
internal class Notice(val msg: Msg, val detail: String, val technical: String? = null)

/** The notice that [msg] failed because of [failed]: a plain sentence, and the exception's text behind "Details". */
internal fun noticeFor(msg: Msg, failed: Load.Failed): Notice =
    if (failed.technical) Notice(msg, plainReason(failed), failed.message) else Notice(msg, failed.message)

/** What a technical failure means in a few plain words, such as "the server could not be reached". */
internal fun plainReason(failed: Load.Failed): String = Strings.current(
    when {
        failed.unreadable -> Msg.UNREADABLE_ANSWER
        failed.lostConnection -> Msg.NO_CONNECTION
        else -> Msg.WENT_WRONG
    },
)
