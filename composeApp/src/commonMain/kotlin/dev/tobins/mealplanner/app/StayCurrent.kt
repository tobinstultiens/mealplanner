package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The date (or week) a screen shows once "now" has moved from [oldNow] to [newNow]: it follows when
 * it was showing [oldNow], and stays where the user stepped to otherwise. A mere return to the
 * front on the same day has [oldNow] equal to [newNow], so nothing moves. The same rule serves
 * Today (dates) and the Plan (the Mondays of the weeks).
 */
internal fun followedDate(oldNow: String, shown: String, newNow: String): String = if (shown == oldNow) newNow else shown

/** How often the clock is read while the app is in front, to notice midnight with the app open. It only reads the clock and sends nothing. */
private val ClockCheck = 30.seconds

/**
 * Whether the app is in front, as the platform reports it ([rememberAppFront]), held outside the
 * composition so that a change takes effect at once. A hidden browser tab draws no frames: a value
 * read in composition was only looked at again once the tab showed, by which time it was "in front"
 * again, so going behind and coming back were never seen (#200). What must stop while the app is
 * behind (the shopping list's checks, the clock) follows [inFront]; a screen keys its load on [returns].
 */
internal class AppFront(inFront: Boolean) {
    private val state = MutableStateFlow(inFront)

    /** In front now; changes the moment the platform says so, drawn or not. */
    val inFront: StateFlow<Boolean> = state.asStateFlow()

    /** How many times the app came back to the front; the composition reads it once the app shows again. */
    var returns by mutableIntStateOf(0)
        private set

    /** The platform says whether the app is in front now; saying the same again changes nothing. */
    fun report(front: Boolean) {
        if (front && !state.value) returns++
        state.value = front
    }

    /** Runs [action] now and every [every] while the app is in front, and not while it is behind; until cancelled. */
    suspend fun whileInFront(every: Duration, action: () -> Unit) {
        inFront.collectLatest { front ->
            while (front) {
                action()
                delay(every)
            }
        }
    }
}

/**
 * Today's date, read again whenever the app returns to the front and every [ClockCheck] while
 * it is in front, so a screen left open past midnight notices.
 */
@Composable
internal fun rememberToday(): String {
    val front = rememberAppFront()
    var today by remember { mutableStateOf(todayIso()) }
    LaunchedEffect(front) { front.whileInFront(ClockCheck) { today = todayIso() } }
    return today
}

/**
 * How many times the app came back to the front since this screen was drawn. A screen takes it as
 * a key of its load, so it fetches once per return (not on the first draw, which loads anyway).
 */
@Composable
internal fun rememberFrontReturns(): Int = rememberAppFront().returns
