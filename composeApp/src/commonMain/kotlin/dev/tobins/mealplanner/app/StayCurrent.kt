package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * The date (or week) a screen shows once "now" has moved from [oldNow] to [newNow]: it follows when
 * it was showing [oldNow], and stays where the user stepped to otherwise. A mere return to the
 * front on the same day has [oldNow] equal to [newNow], so nothing moves. The same rule serves
 * Today (dates) and the Plan (the Mondays of the weeks).
 */
internal fun followedDate(oldNow: String, shown: String, newNow: String): String = if (shown == oldNow) newNow else shown

/** How often the clock is read while the app is in front, to notice midnight with the app open. It only reads the clock and sends nothing. */
private const val ClockCheckMillis = 30_000L

/**
 * Today's date, read again whenever the app returns to the front and every [ClockCheckMillis] while
 * it is in front, so a screen left open past midnight notices.
 */
@Composable
internal fun rememberToday(): String {
    val inFront = appInFront()
    var today by remember { mutableStateOf(todayIso()) }
    LaunchedEffect(inFront) {
        while (inFront) {
            today = todayIso()
            delay(ClockCheckMillis)
        }
    }
    return today
}

/**
 * How many times the app came back to the front since this screen was drawn. A screen takes it as
 * a key of its load, so it fetches once per return (not on the first draw, which loads anyway).
 */
@Composable
internal fun rememberFrontReturns(): Int {
    val inFront = appInFront()
    var returns by remember { mutableIntStateOf(0) }
    val wasInFront = remember { booleanArrayOf(inFront) }
    LaunchedEffect(inFront) {
        if (inFront && !wasInFront[0]) returns++
        wasInFront[0] = inFront
    }
    return returns
}
