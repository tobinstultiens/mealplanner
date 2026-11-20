package dev.tobins.mealplanner.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/** The loops of the server's background jobs (issue 285): one failed round never ends the schedule. */
class BackgroundJobsTest {
    @Test
    fun aRoundThatFailsIsFollowedByTheNextOne() = runBlocking {
        val third = CompletableDeferred<Unit>()
        var rounds = 0
        val job = launch {
            repeatEvery(10.milliseconds, "Test round") {
                rounds++
                if (rounds == 1) error("the first round fails")
                if (rounds == 2) throw IllegalStateException("so does the second")
                third.complete(Unit)
            }
        }
        withTimeout(30_000) { third.await() }
        job.cancel()
        assertEquals(3, rounds)
    }

    @Test
    fun aCancellationStillStopsTheLoop() = runBlocking {
        val job = launch { repeatEvery(10.milliseconds, "Test round") { } }
        job.cancel()
        job.join()
        assertEquals(true, job.isCancelled)
        assertFailsWith<CancellationException> { logged("Test") { throw CancellationException("stop") } }
        Unit
    }

    @Test
    fun aFailedJobDoesNotKeepTheOneAfterItFromRunning() = runBlocking {
        var second = false
        logged("First") { error("down") }
        logged("Second") { second = true }
        assertEquals(true, second)
    }
}
