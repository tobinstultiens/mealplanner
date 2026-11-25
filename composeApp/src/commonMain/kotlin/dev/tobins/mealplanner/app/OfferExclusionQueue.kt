package dev.tobins.mealplanner.app

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tobins.mealplanner.api.OfferExclusion
import dev.tobins.mealplanner.api.OfferExclusions
import dev.tobins.mealplanner.api.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Not this ingredient" (issue 316) on this device, as the shopping session keeps it for one login: the account's list
 * as the server last gave it ([known]) and the changes made here that wait to be sent ([waiting]), both in the [cache],
 * so a change made in a shop without reception survives the app being closed. Laid together ([inEffect]) over every
 * list of offers the app shows, a deal leaves its ingredient the moment the user says so, and a list fetched before
 * that does not bring it back.
 *
 * A change goes out the way a waiting tick does: in the session's [turn], before the shopping list is fetched
 * ([sendWaiting]), so choosing one asks the session to fetch ([sendSoon]). Without an answer it waits for the next
 * time; a refusal (the ingredient is gone, a server from before it) drops it and says so ([rejected]).
 */
internal class OfferExclusionQueue(
    private val scope: CoroutineScope,
    private val cache: ShoppingCache,
    private val server: ShoppingServer,
    private val turn: Mutex,
    /** Asks the session to send what waits and fetch the list, in its turn. */
    private val sendSoon: () -> Unit,
    private val rejected: (Notice) -> Unit,
) {
    var known: List<OfferExclusion> by mutableStateOf(cache.knownExclusions())
        private set

    var waiting: List<ExclusionChange> by mutableStateOf(cache.waitingExclusions())
        private set

    /** The exclusions the screens go by, the newest first ([exclusionsInEffect]). */
    val inEffect: List<OfferExclusion> get() = exclusionsInEffect(known, waiting)

    /** Whether [exclusion] is one made here that the server does not have yet. */
    fun isWaiting(exclusion: OfferExclusion): Boolean = waiting.any { it.excluded && it.key == exclusion.key }

    /** The user says the offer [title] of [store] is not ingredient [ingredientId] ([excluded]), or takes that back: in effect now, sent in turn. */
    fun choose(store: Store, title: String, ingredientId: Long, ingredientName: String, excluded: Boolean) {
        cache.chooseExclusion(ExclusionChange(store, title, ingredientId, ingredientName, excluded, nowIso()))
        reread()
        sendSoon()
    }

    /** Asks the server for the account's list, as another device may have changed it; after what waits here, so the answer includes it. */
    fun refresh() {
        scope.launch {
            turn.withLock {
                if (sendWaiting() != null) return@withLock
                val listed = timed { server.exclusions() }
                if (listed is Load.Ready) cache.saveKnownExclusions(listed.value.exclusions)
                reread()
            }
        }
    }

    /**
     * Sends the waiting changes, oldest first; the caller holds the [turn]. Each answer is the account's list, which
     * takes the place of [known]. A refused change is dropped and reported; one the server failed on (a 5xx, an answer
     * the app cannot read) stays for the next time while the rest still go. When one gets no answer at all the rest
     * wait too, and that failure is returned: there is no connection.
     */
    suspend fun sendWaiting(): Load.Failed? {
        try {
            for (change in cache.waitingExclusions()) {
                when (val result = timed { server.setExcluded(change) }) {
                    is Load.Ready -> cache.confirmExclusion(change, result.value.exclusions)
                    is Load.Failed -> when {
                        result.refused -> {
                            cache.forgetExclusion(change)
                            rejected(noticeFor(Msg.EXCLUSION_FAILED, result))
                        }
                        result.noAnswer -> return result
                        else -> Unit
                    }
                    Load.Loading -> Unit
                }
            }
            return null
        } finally {
            reread()
        }
    }

    private fun reread() {
        known = cache.knownExclusions()
        waiting = cache.waitingExclusions()
    }

    private suspend fun timed(request: suspend () -> Load<OfferExclusions>): Load<OfferExclusions> =
        withTimeoutOrNull(ShoppingSession.TIME_LIMIT) { request() } ?: Load.Failed(Strings.current(Msg.NO_ANSWER))
}

/**
 * Makes the choice ([OfferExclusionQueue.choose]) and says what it did in a snackbar whose Undo makes the opposite one:
 * the choice asks nothing beforehand, so a mis-tap is one tap from undone. An Undo says nothing more; the screen shows it.
 */
internal fun OfferExclusionQueue.chooseWithUndo(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    strings: Strings,
    store: Store,
    title: String,
    ingredientId: Long,
    ingredientName: String,
    excluded: Boolean,
) {
    choose(store, title, ingredientId, ingredientName, excluded)
    scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        val answer = snackbar.showSnackbar(
            strings(if (excluded) Msg.EXCLUDED else Msg.TAKEN_BACK, title, store.shortName(), ingredientName),
            actionLabel = strings(Msg.UNDO),
            duration = SnackbarDuration.Long,
        )
        if (answer == SnackbarResult.ActionPerformed) choose(store, title, ingredientId, ingredientName, !excluded)
    }
}
