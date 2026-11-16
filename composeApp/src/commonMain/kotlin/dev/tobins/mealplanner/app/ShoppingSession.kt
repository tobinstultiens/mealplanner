package dev.tobins.mealplanner.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the shopping list needs from the server; failures come back as [Load.Failed], never thrown. */
internal interface ShoppingServer {
    suspend fun list(weekStart: String): Load<ShoppingList>

    /** [grams]: what the list advised buying, for ticking off a buy-ahead or stock-up item; null otherwise. */
    suspend fun check(weekStart: String, key: String, checked: Boolean, grams: Double?): Load<ShoppingList>

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

    override suspend fun check(weekStart: String, key: String, checked: Boolean, grams: Double?) =
        load { setShoppingChecked(weekStart, key, checked, grams) }

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

/** The id of an own item, the number in its key `x:<id>`; null for a row that comes from the plan. */
internal val ShoppingItem.extraId: Long? get() = if (key.startsWith("x:")) key.removePrefix("x:").toLongOrNull() else null

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

/**
 * The Shopping screen's state, owned by the app for as long as one account is logged in, so that
 * leaving the screen (another tab, a recipe, the pantry, the offers) cancels nothing and forgets
 * nothing. The screen shows what is here, calls [resume] when it appears and [leave] when it goes;
 * the app says when it comes to the front or goes behind ([inFront]).
 *
 * It owns the week shown, the list as loaded ([state], [offlineSince]), the ticks of each week
 * with their requests, the opened groups and the scroll position, and [notices] not shown yet.
 * Every request to the server goes out in [scope], one at a time and in order ([turn]): an answer
 * that overtook a later request could otherwise put an older list on screen. Each gets
 * [TIME_LIMIT], so one that hangs never holds back the rest; no answer counts as no connection.
 *
 * Adding and deleting an own item need the server ([canChangeExtras]): they go through the same
 * queue, their answer is the list like a tick's, and without a connection nothing is queued and a
 * notice says so. Ticking an own item works offline like any row.
 *
 * Ticks are written to the [cache] before they are sent (see [ShoppingTicks]); the ones left from
 * before, of every week, go out before each list is fetched. [close] ends it all, before logging out.
 *
 * The list keeps itself current, as another device on the same login may tick too: it is fetched
 * when the app comes back to the front and, while the screen shows in front, every
 * [REFRESH_IN_SHOP] in shopping mode (two people in one shop) and every [REFRESH] otherwise.
 * Offline it is tried again every [RECONNECT]. Behind other apps or on another screen nothing is
 * asked, apart from the current week's list once on coming to the front ([prefetch]).
 */
internal class ShoppingSession(
    parent: CoroutineScope,
    private val server: ShoppingServer,
    private val cache: ShoppingCache,
    private val today: () -> String = ::todayIso,
    /** Where the user was, when Android recreates the app's screen ([place]). */
    place: Place = Place(),
    /** Whether the list is in shopping mode, asked at each check: then it is fetched more often. */
    private val shoppingMode: () -> Boolean = { false },
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

    /** The weeks whose list waits in the queue to be fetched, so that none is asked for twice in a row. */
    private val queued = mutableSetOf<String>()

    private var screenShown = false
    private var appInFront = false
    private var tidied = false

    /** Keeps the list shown current while the screen shows in front ([keepCurrent]). */
    private var keeper: Job? = null

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

    /**
     * How many stored ticks the server answered with an error or an answer the app could not read at
     * the last attempt, so they have not reached it yet; they stay stored and are tried again. Ticks
     * that got no answer at all are the offline line's business ([offlineSince]).
     */
    var stuckTicks: Int by mutableStateOf(0)
        private set

    /** Finished sections and Done groups the user opened; everything else stays collapsed. */
    var expanded: Set<String> by mutableStateOf(place.expanded)
        private set

    /** Where the list was scrolled to when the screen was left: the first item shown and how far into it. */
    var scrollIndex = place.scrollIndex
    var scrollOffset = place.scrollOffset

    val place: Place get() = Place(chosenWeek, expanded, scrollIndex, scrollOffset)

    /** True while an own item is being added, so a second tap or Enter does not add it twice. */
    var adding: Boolean by mutableStateOf(false)
        private set

    private val waitingNotices = mutableStateListOf<Notice>()

    /** Oldest first; each stays until [shown], so one is not lost when the screen is left while it shows. */
    val notices: List<Notice> get() = waitingNotices

    /**
     * The screen appears: the week from before, or the current one if the user did not step away
     * from it (the app may have stayed open into a new week). Fetched again, as another device may
     * have ticked; what is shown stays until the answer.
     */
    fun resume() {
        screenShown = true
        show(chosenWeek ?: thisWeek())
        keepCurrent()
    }

    /** The screen is gone (another tab or screen): the list is no longer fetched by itself. */
    fun leave() {
        screenShown = false
        keepCurrent()
    }

    /**
     * The app comes to the front ([front] true) or goes behind other apps or browser tabs. Coming
     * to the front the ticks left from before are sent and the list shown is fetched again, or,
     * with the screen not shown, the current week's for the shop. Behind, nothing is asked.
     */
    fun inFront(front: Boolean) {
        val cameBack = front && !appInFront
        appInFront = front
        if (cameBack) {
            if (!tidied) cache.tidy(today())
            tidied = true
            if (screenShown) fetch(weekStart) else prefetch()
        }
        keepCurrent()
    }

    /**
     * Fetches the current week's list into the device's storage, so it is there in a shop without
     * reception even if the list was not opened, and it follows a plan that just changed. Not
     * while the screen shows, which fetches its own week; a failure shows nowhere.
     */
    fun prefetch() {
        if (!screenShown) fetch(thisWeek(), onlyIfShown = false)
    }

    /** The user steps to [weekStart]. */
    fun open(weekStart: String) {
        chosenWeek = weekStart.takeIf { it != thisWeek() }
        show(weekStart)
    }

    /** Tries the server again, after a failed load or while offline. */
    fun retry() = show(weekStart)

    /** The user ticks or un-ticks an item of the week shown: shown at once, kept, sent in turn. */
    fun tick(key: String, checked: Boolean) = ticksOf(weekStart).choose(key, checked)

    /**
     * The user ticks a row again whose need grew since it was bought: the server keeps what a tick
     * said first, so it is asked to tick it again for what it needs now, in one step. Not shown at
     * once, as the amount comes from the server's answer; offline it is reported (without asking the
     * server, which cannot answer) and not kept for later, and says so, as the line about the stored
     * list promises only plain ticks are sent later.
     * A server from before that step gets what the app sent then: an un-tick and a tick
     * ([untickAndTick]). On a failure the list is fetched again, so the row shows what the server has.
     */
    fun tickAgain(key: String) {
        if (!online) {
            waitingNotices += Notice(Msg.TICK_AGAIN_NEEDS_CONNECTION, "")
            return
        }
        val week = weekStart
        scope.launch {
            turn.withLock {
                val result = timedUnlessOld { server.tickAgain(week, key) } ?: untickAndTick(week, key)
                showAnswer(week, result, Msg.TICK_FAILED, lostConnection = Msg.TICK_AGAIN_NEEDS_CONNECTION)
            }
        }
    }

    /**
     * How a server from before ticking again in one step is asked to: un-ticking forgets what the
     * row said, ticking stores what it says now. The answer to the last request sent, as the tick
     * is not sent when the un-tick failed. No advised grams go with it: only rows of the meals' needs
     * can need more (an advice row remembers no bought amount), and the server weighs those itself.
     */
    private suspend fun untickAndTick(week: String, key: String): Load<ShoppingList> {
        val unticked = timed { server.check(week, key, false, grams = null) }
        return if (unticked !is Load.Ready) unticked else timed { server.check(week, key, true, grams = null) }
    }

    /**
     * Puts a bought row that no meal needs any more on hand, so that the planner prefers meals that
     * use it: the server stocks what was bought of it and takes it out of the group, and answers
     * with the list. Only rows of catalogue ingredients (key `i<id>`) have one to put on hand. A
     * server from before that gets what the app did then ([putOnHand]).
     */
    fun useUp(key: String) {
        val id = key.takeIf { it.startsWith("i") }?.drop(1)?.toLongOrNull() ?: return
        val week = weekStart
        scope.launch {
            turn.withLock {
                val result = timedUnlessOld { server.useUp(week, key) }
                if (result == null) putOnHand(week, id) else showAnswer(week, result, Msg.USE_UP_FAILED)
            }
        }
    }

    /** How the app used up a bought row before the server did: the ingredient on hand as set by hand, without an amount, and the list fetched again. */
    private suspend fun putOnHand(week: String, id: Long) {
        when (val result = withTimeoutOrNull(TIME_LIMIT) { server.putOnHand(id) } ?: Load.Failed(Strings.current(Msg.NO_ANSWER))) {
            is Load.Ready -> load(week)
            is Load.Failed -> waitingNotices += noticeFor(Msg.USE_UP_FAILED, result)
            Load.Loading -> Unit
        }
    }

    /**
     * Shows the list the server answered with; or says why it failed ([failed], or [lostConnection]
     * when nothing answered) and fetches the list again, so that the screen shows what the server
     * has, whatever part of the change went through.
     */
    private suspend fun showAnswer(week: String, result: Load<ShoppingList>, failed: Msg, lostConnection: Msg = failed) {
        when (result) {
            is Load.Ready -> {
                cache.save(result.value, nowIso())
                display(week, ticksOf(week).reset(result.value))
            }
            is Load.Failed -> {
                waitingNotices += noticeFor(if (result.noAnswer) lostConnection else failed, result)
                load(week)
            }
            Load.Loading -> Unit
        }
    }

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
                    is Load.Failed -> waitingNotices += noticeFor(Msg.ADVICE_CHANGE_FAILED, result)
                    Load.Loading -> Unit
                }
            }
        }
    }

    /** False while the list shown is the stored copy of a lost connection. */
    private val online: Boolean get() = offlineSince == null

    /** Whether own items can be added or deleted: only with a connection. */
    val canChangeExtras: Boolean get() = online

    /**
     * Puts [name] on the list as an own item; [added] is called once the server has it, so the
     * screen can clear its field, and not on a failure, which is a notice and leaves the typed
     * text alone. Ignored while an earlier item is still on its way.
     */
    fun add(name: String, added: () -> Unit) {
        if (adding) return
        if (!canChangeExtras) return needsConnection()
        val week = weekStart
        adding = true
        scope.launch {
            try {
                turn.withLock {
                    when (val result = timed { server.addExtra(week, name.trim()) }) {
                        is Load.Ready -> {
                            cache.save(result.value, nowIso())
                            display(week, ticksOf(week).reset(result.value))
                            added()
                        }
                        is Load.Failed -> waitingNotices += noticeFor(Msg.EXTRA_ADD_FAILED, result)
                        Load.Loading -> Unit
                    }
                }
            } finally {
                adding = false
            }
        }
    }

    /** Takes the own item [id] off the list; a tick of it that still waits on the device is dropped with it. */
    fun remove(id: Long) {
        if (!canChangeExtras) return needsConnection()
        val week = weekStart
        scope.launch {
            turn.withLock {
                when (val result = timed { server.removeExtra(week, id) }) {
                    is Load.Ready -> {
                        cache.pending(week)["x:$id"]?.let { cache.forget(week, "x:$id", it) }
                        cache.save(result.value, nowIso())
                        display(week, ticksOf(week).reset(result.value))
                    }
                    is Load.Failed -> waitingNotices += noticeFor(Msg.EXTRA_REMOVE_FAILED, result)
                    Load.Loading -> Unit
                }
            }
        }
    }

    private fun needsConnection() {
        waitingNotices += Notice(Msg.EXTRA_NEEDS_CONNECTION, "")
    }

    /** Opens or closes a finished section or a Done group. */
    fun toggle(group: String) {
        expanded = if (group in expanded) expanded - group else expanded + group
    }

    fun shown(notice: Notice) {
        waitingNotices.remove(notice)
    }

    /** Cancels every request still queued or under way, and the checks; nothing more is sent or stored. */
    fun close() = scope.cancel()

    /**
     * Runs the checks while the screen shows in front, and stops them otherwise. Every
     * [RECONNECT] the list is tried again while offline or failed, and fetched when it is due:
     * every check in shopping mode, after [REFRESH] otherwise. Not while a request is under way,
     * as a tick's answer is a fresh list anyway. A list the server refused is not asked for by
     * itself, as asking again would not help; Retry and showing the screen again still ask.
     */
    private fun keepCurrent() {
        if (!(screenShown && appInFront)) {
            keeper?.cancel()
            keeper = null
            return
        }
        if (keeper?.isActive == true) return
        keeper = scope.launch {
            var waited = Duration.ZERO
            while (true) {
                delay(RECONNECT)
                waited += RECONNECT
                val failed = state as? Load.Failed
                val due = when {
                    failed != null -> !failed.refused
                    offlineSince != null -> true
                    else -> waited >= if (shoppingMode()) REFRESH_IN_SHOP else REFRESH
                }
                if (due && !turn.isLocked) {
                    waited = Duration.ZERO
                    fetch(weekStart)
                }
            }
        }
    }

    /**
     * Shows [week] at once: the list already shown if it is that week's, else its stored copy,
     * else the loading state; never the rows of another week. Then loads it.
     */
    private fun show(week: String) {
        val sameWeek = week == weekStart
        weekStart = week
        if (!sameWeek) offlineSince = null
        if (!sameWeek || state !is Load.Ready) state = cache.load(week)?.let { Load.Ready(ticksOf(week).reset(it.first)) } ?: Load.Loading
        fetch(week)
    }

    /**
     * Queues loading [week]'s list, unless that is queued already. [onlyIfShown]: dropped when
     * another week is shown by the time it is its turn.
     */
    private fun fetch(week: String, onlyIfShown: Boolean = true) {
        if (!queued.add(week)) return
        scope.launch {
            turn.withLock {
                queued.remove(week)
                if (!onlyIfShown || week == weekStart) load(week)
            }
        }
    }

    /**
     * Sends the ticks left from before, then fetches the list; the list is laid under the ticks
     * not sent yet, as a tick's answer is, so it never flips a row back. Without an answer or with
     * a server error the stored copy is shown, with when it was saved; once a tick got no answer
     * the list is not waited for as well, but a server error on a tick does not hold the list up.
     * A list on screen is never replaced by a refusal; with nothing to show, a failure shows as such.
     */
    private suspend fun load(week: String) {
        val result = sendWaiting() ?: timed { server.list(week) }
        val stored = cache.load(week)
        when {
            result is Load.Ready -> {
                cache.save(result.value, nowIso())
                display(week, ticksOf(week).reset(result.value))
                if (week == weekStart) offlineSince = null
            }
            result is Load.Failed && !result.refused && stored != null -> {
                display(week, ticksOf(week).reset(stored.first))
                if (week == weekStart) offlineSince = stored.second
            }
            week == weekStart && state !is Load.Ready -> {
                state = result
                offlineSince = null
            }
        }
    }

    /**
     * Sends the ticks of every week that wait in the cache from an earlier visit, a lost
     * connection or an app that was closed, oldest week first: the list fetched next includes
     * them, and next week's plan knows what was bought ahead. A tick the server refuses is dropped
     * and reported. One it fails on (a 5xx) stays stored for the next time and the rest still go:
     * the server answered, so the list can load, and one tick it keeps failing on must not stop
     * every list from loading. When one gets no answer at all the rest wait for the next time, and
     * that failure is returned. An answer the app cannot read is the server's answer too, so it
     * counts as an error and not as no answer. The ticks left stored are counted in [stuckTicks].
     */
    private suspend fun sendWaiting(): Load.Failed? {
        var stuck = 0
        for (week in cache.weeksWithTicks()) {
            // Ticks of this session that are still to go are sent in their own turn.
            val waiting = cache.pending(week) - ticks[week]?.unsent.orEmpty()
            for ((key, checked) in waiting) {
                when (val result = timed { server.check(week, key, checked, advisedGrams(week, key, checked)) }) {
                    is Load.Ready -> cache.confirm(result.value, nowIso(), key, checked)
                    is Load.Failed -> when {
                        result.refused -> {
                            cache.forget(week, key, checked)
                            waitingNotices += noticeFor(Msg.TICK_FAILED, result)
                        }
                        // Nothing answered: no connection, or the time limit ran out.
                        result.noAnswer -> {
                            stuckTicks = stuck
                            return result
                        }
                        else -> stuck++
                    }
                    Load.Loading -> Unit
                }
            }
        }
        stuckTicks = stuck
        return null
    }

    /**
     * For ticking off a buy-ahead or stock-up item: the grams the list advised buying when it was
     * ticked ([ShoppingCache.advised]). A tick made offline goes out later, when the server may have
     * new offers and no longer advise it; with this it still knows how much was bought. Null for
     * un-ticking and for other rows.
     */
    private fun advisedGrams(week: String, key: String, checked: Boolean): Double? =
        if (checked) cache.advised(week, key) else null

    /** One week's ticks, made once: their requests go in the same turn as everything else. */
    private fun ticksOf(week: String): ShoppingTicks = ticks.getOrPut(week) {
        ShoppingTicks(
            scope,
            week,
            cache,
            send = { key, checked -> timed { server.check(week, key, checked, advisedGrams(week, key, checked)) } },
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

    /** [request] to a route a server from before it lacks, with the time limit of [timed]; null when the server is from before it. */
    private suspend fun timedUnlessOld(request: suspend () -> Load<ShoppingList>?): Load<ShoppingList>? {
        var old = false
        val result = withTimeoutOrNull(TIME_LIMIT) { request().also { old = it == null } }
        return if (old) null else result ?: Load.Failed(Strings.current(Msg.NO_ANSWER))
    }

    private fun thisWeek() = mondayOf(today())

    companion object {
        /** How long one request may take; the answer to a tick is the whole list, so this allows a slow connection. */
        val TIME_LIMIT = 10.seconds

        /** How often the list shown is fetched in shopping mode, when another phone may be ticking in the same shop. */
        val REFRESH_IN_SHOP = 30.seconds

        /** How often the list shown is fetched otherwise: someone elsewhere may be changing it, but rarely by the minute. */
        val REFRESH = 2.minutes

        /** How often the server is tried again while offline or failed; also how often the checks run. */
        val RECONNECT = 30.seconds
    }
}
