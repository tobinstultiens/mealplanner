package dev.tobins.mealplanner.app

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Puts a stand-in for `navigator.wakeLock` on the page: [mode] `ok` hands out locks that count their
 * release, `reject` refuses every request (as a headless browser may), `missing` takes the API away.
 * The counts are on `window.wakeLockLog`; the page's own listeners and promises do the rest.
 */
@JsFun(
    """(mode) => {
        window.wakeLockLog = { requests: 0, releases: 0 };
        const fake = mode === 'missing' ? undefined : {
            request: (type) => {
                window.wakeLockLog.requests++;
                if (mode === 'reject') return Promise.reject(new Error('not allowed'));
                return Promise.resolve({ release: () => { window.wakeLockLog.releases++; return Promise.resolve(); } });
            },
        };
        Object.defineProperty(navigator, 'wakeLock', { configurable: true, get: () => fake });
    }""",
)
private external fun fakeWakeLock(mode: String)

@JsFun("() => { delete navigator.wakeLock; delete document.visibilityState; delete window.wakeLockLog; }")
private external fun restoreBrowser()

@JsFun("() => window.wakeLockLog.requests")
private external fun requests(): Int

@JsFun("() => window.wakeLockLog.releases")
private external fun releases(): Int

@JsFun(
    """(hidden) => {
        Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => hidden ? 'hidden' : 'visible' });
        document.dispatchEvent(new Event('visibilitychange'));
    }""",
)
private external fun switchPage(hidden: Boolean)

/** Lets the promises the lock code waits on settle. */
@JsFun("() => Promise.resolve(null)")
private external fun settled(): Promise<JsAny?>

/** The Screen Wake Lock behind [KeepScreenOn]: asked for, given up, asked for again, and harmless where it cannot be had. */
class WakeLockTest {
    @AfterTest
    fun restore() = restoreBrowser()

    @Test
    fun asksForTheLockAndReleasesItWhenGivenUp() = runTest {
        fakeWakeLock("ok")
        val hold = holdWakeLock()
        settled().await<JsAny?>()
        assertEquals(1, requests())
        assertEquals(0, releases())

        giveUpWakeLock(hold)
        assertEquals(1, releases())
    }

    @Test
    fun givingUpBeforeTheLockArrivedStillReleasesIt() = runTest {
        fakeWakeLock("ok")
        giveUpWakeLock(holdWakeLock())
        settled().await<JsAny?>()
        assertEquals(1, releases())
    }

    @Test
    fun asksAgainWhenTheTabShowsAgain() = runTest {
        fakeWakeLock("ok")
        val hold = holdWakeLock()
        settled().await<JsAny?>()
        // The browser drops the lock of a hidden tab by itself: hiding asks for nothing, showing asks again.
        switchPage(hidden = true)
        assertEquals(1, requests())
        switchPage(hidden = false)
        assertEquals(2, requests())

        giveUpWakeLock(hold)
        switchPage(hidden = false)
        assertEquals(2, requests(), "no longer listening")
    }

    @Test
    fun doesNothingWhereTheApiIsMissing() = runTest {
        fakeWakeLock("missing")
        val hold = holdWakeLock()
        switchPage(hidden = false)
        giveUpWakeLock(hold)
        settled().await<JsAny?>()
        assertEquals(0, requests())
    }

    @Test
    fun aRefusedRequestIsHandled() = runTest {
        fakeWakeLock("reject")
        val hold = holdWakeLock()
        settled().await<JsAny?>()
        settled().await<JsAny?>()
        giveUpWakeLock(hold)
        assertEquals(1, requests())
        assertEquals(0, releases())
    }
}
