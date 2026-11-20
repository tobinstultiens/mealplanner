package dev.tobins.mealplanner.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.time.Duration

private val log = LoggerFactory.getLogger("dev.tobins.mealplanner.server.BackgroundJobs")

/**
 * Runs [block], and logs a failure as a warning instead of passing it on: the jobs of the server run
 * unattended, and one that throws out of its coroutine would end the schedule it is in (issue 285), or the server's
 * whole scope when it was started on its own. A cancellation is passed on, so that stopping the server still stops the job.
 */
suspend fun logged(what: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("$what failed: ${e.message}")
    }
}

/**
 * Runs [round] now and then again every [every], until cancelled. A round that fails is logged ([logged])
 * and the next one runs at its time as if it had not.
 */
suspend fun repeatEvery(every: Duration, what: String, round: suspend () -> Unit) {
    while (true) {
        logged(what, round)
        delay(every)
    }
}
