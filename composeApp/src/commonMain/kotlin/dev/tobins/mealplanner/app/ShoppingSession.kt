package dev.tobins.mealplanner.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** What the shopping list needs from the server; failures come back as [Load.Failed], never thrown. */
internal interface ShoppingServer {
    suspend fun list(weekStart: String): Load<ShoppingList>

    suspend fun check(weekStart: String, key: String, checked: Boolean): Load<ShoppingList>

    suspend fun dismiss(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean): Load<ShoppingList>
}

internal fun ApiClient.shoppingServer(): ShoppingServer = object : ShoppingServer {
    override suspend fun list(weekStart: String) = load { shoppingList(weekStart) }

    override suspend fun check(weekStart: String, key: String, checked: Boolean) = load { setShoppingChecked(weekStart, key, checked) }

    override suspend fun dismiss(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean) =
        load { setShoppingDismissed(weekStart, group, dismissed) }
}

/** Something to tell the user in a snackbar the next time the list shows: [msg] filled in with [detail]. */
internal class Notice(val msg: Msg, val detail: String)

/**
 * The Shopping screen's state, owned by the app for as long as one account is logged in, so that
 * leaving the screen (another tab, a recipe, the pantry, the offers) cancels nothing and forgets
 * nothing. The screen shows what is here and calls [resume] when it appears.
 *
 * It owns the week shown, the list as loaded ([state], [offlineSince]), the ticks of each week
 * with their requests, the opened groups and the scroll position, and [notices] not shown yet.
 * Every request to the server goes out in [scope], one at a time and in order ([turn]): an answer
 * that overtook a later request could otherwise put an older list on screen. Each gets
 * [TIME_LIMIT], so one that hangs never holds back the rest; no answer counts as no connection.
 *
 * Ticks are written to the [cache] before they are sent (see [ShoppingTicks]), and the ones left
 * from before go out when their week is opened. [close] ends it all, before logging out.
 */
internal class ShoppingSession(
    parent: CoroutineScope,
    private val server: ShoppingServer,
    private val cache: ShoppingCache,
    private val today: () -> String = ::todayIso,
    /** Where the user was, when Android recreates the app's screen ([place]). */
    place: Place = Place(),
) {
    /**
     * The user's place in the list, as plain values the app can keep in its saved state: Android
     * recreates the screen on rotation and after it stopped the app in the background. The list
     * and its ticks come back from the server and the device's storage.
     */
    data class Place(val chosenWeek: String? = null, val expanded: Set<String> = emptySet(), val scrollIndex: Int = 0, val scrollOffset: Int = 0)

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val turn = Mutex()
    private val ticks = mutableMapOf<String, ShoppingTicks>()

    /** The week the user stepped to; null while following the current week. */
    private var chosenWeek: String? = place.chosenWeek

    var weekStart: String by mutableStateOf(chosenWeek ?: thisWeek())
        private set

    /** The list of [weekStart], never another week's. */
    var state: Load<ShoppingList> by mutableStateOf(Load.Loading)
        private set

    /** When the stored copy shown was saved, while the server cannot be reached; null when online. */
    var offlineSince: String? by mutableStateOf(null)
        private set

    /** Finished sections and Done groups the user opened; everything else stays collapsed. */
    var expanded: Set<String> by mutableStateOf(place.expanded)
        private set

    /** Where the list was scrolled to when the screen was left: the first item shown and how far into it. */
    var scrollIndex = place.scrollIndex
    var scrollOffset = place.scrollOffset

    val place: Place get() = Place(chosenWeek, expanded, scrollIndex, scrollOffset)

    private val waitingNotices = mutableStateListOf<Notice>()

    /** Oldest first; each stays until [shown], so one is not lost when the screen is left while it shows. */
    val notices: List<Notice> get() = waitingNotices

    /**
     * The screen appears: the week from before, or the current one if the user did not step away
     * from it (the app may have stayed open into a new week). Fetched again, as another device may
     * have ticked; what is shown stays until the answer.
     */
    fun resume() = show(chosenWeek ?: thisWeek())

    /** The user steps to [weekStart]. */
    fun open(weekStart: String) {
        chosenWeek = weekStart.takeIf { it != thisWeek() }
        show(weekStart)
    }

    /** Tries the server again, after a failed load or while offline. */
    fun retry() = show(weekStart)

    /** The user ticks or un-ticks an item of the week shown: shown at once, kept, sent in turn. */
    fun tick(key: String, checked: Boolean) = ticksOf(weekStart).choose(key, checked)

    /** Hides one advice group for the week, or shows it again; the list comes back from the server. */
    fun dismiss(group: ShoppingAdviceGroup, dismissed: Boolean) {
        val week = weekStart
        scope.launch {
            turn.withLock {
                when (val result = timed { server.dismiss(week, group, dismissed) }) {
                    is Load.Ready -> {
                        cache.save(result.value, nowIso())
                        display(week, ticksOf(week).reset(result.value))
                    }
                    is Load.Failed -> waitingNotices += Notice(Msg.ADVICE_CHANGE_FAILED, result.message)
                    Load.Loading -> Unit
                }
            }
        }
    }

    /** Opens or closes a finished section or a Done group. */
    fun toggle(group: String) {
        expanded = if (group in expanded) expanded - group else expanded + group
    }

    fun shown(notice: Notice) {
        waitingNotices.remove(notice)
    }

    /** Cancels every request still queued or under way; nothing more is sent or stored. */
    fun close() = scope.cancel()

    /**
     * Shows [week] at once: the list already shown if it is that week's, else its stored copy,
     * else the loading state; never the rows of another week. Then loads it.
     */
    private fun show(week: String) {
        val sameWeek = week == weekStart
        weekStart = week
        if (!sameWeek) offlineSince = null
        if (!sameWeek || state !is Load.Ready) state = cache.load(week)?.let { Load.Ready(ticksOf(week).reset(it.first)) } ?: Load.Loading
        scope.launch { turn.withLock { if (week == weekStart) load(week) } }
    }

    /**
     * Sends the ticks left from before, then fetches the list. Without a connection the stored
     * copy is shown, with when it was saved; any other failure shows as such.
     */
    private suspend fun load(week: String) {
        sendWaiting(week)
        val result = timed { server.list(week) }
        val stored = cache.load(week)
        when {
            result is Load.Ready -> {
                cache.save(result.value, nowIso())
                display(week, ticksOf(week).reset(result.value))
                if (week == weekStart) offlineSince = null
            }
            result is Load.Failed && result.status == null && stored != null -> {
                display(week, ticksOf(week).reset(stored.first))
                if (week == weekStart) offlineSince = stored.second
            }
            week == weekStart -> {
                state = result
                offlineSince = null
            }
        }
    }

    /**
     * Sends the ticks of [week] that wait in the cache from an earlier visit, a lost connection or
     * an app that was closed, so that the list fetched next includes them. A tick the server
     * refuses is dropped and reported, and never stops the list from loading; when one gets no
     * answer the rest wait too, for the next time.
     */
    private suspend fun sendWaiting(week: String) {
        // Ticks of this session that are still to go are sent in their own turn.
        val waiting = cache.pending(week) - ticks[week]?.unsent.orEmpty()
        for ((key, checked) in waiting) {
            val result = timed { server.check(week, key, checked) }
            when {
                result is Load.Ready -> cache.confirm(result.value, nowIso(), key, checked)
                result is Load.Failed && result.refused -> {
                    cache.forget(week, key, checked)
                    waitingNotices += Notice(Msg.TICK_FAILED, result.message)
                }
                else -> return
            }
        }
    }

    /** One week's ticks, made once: their requests go in the same turn as everything else. */
    private fun ticksOf(week: String): ShoppingTicks = ticks.getOrPut(week) {
        ShoppingTicks(
            scope,
            week,
            cache,
            send = { key, checked -> timed { server.check(week, key, checked) } },
            isOffline = { offlineSince != null },
            show = { display(week, it) },
            offline = { _, _ ->
                // The first tick without an answer shows the line that says the list is offline.
                if (week == weekStart && offlineSince == null) offlineSince = cache.load(week)?.second ?: nowIso()
            },
            rejected = { waitingNotices += Notice(Msg.TICK_FAILED, it) },
            turn = turn,
        )
    }

    /** Shows [list] if [week] is still the one shown. */
    private fun display(week: String, list: ShoppingList) {
        if (week == weekStart) state = Load.Ready(list)
    }

    private suspend fun timed(request: suspend () -> Load<ShoppingList>): Load<ShoppingList> =
        withTimeoutOrNull(TIME_LIMIT) { request() } ?: Load.Failed(Strings.current(Msg.NO_ANSWER))

    private fun thisWeek() = mondayOf(today())

    companion object {
        /** How long one request may take; the answer to a tick is the whole list, so this allows a slow connection. */
        val TIME_LIMIT = 10.seconds
    }
}
