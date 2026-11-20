package dev.tobins.mealplanner.app

import kotlinx.browser.document
import org.w3c.dom.events.EventTarget
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@JsFun("() => new EventTarget()")
private external fun newTarget(): EventTarget

/** Says an uncaught error, as the browser does; an empty [error] stands for an error event that carries none. */
@JsFun("(target, error) => target.dispatchEvent(new ErrorEvent('error', { message: 'x', error: error === '' ? null : new Error(error) }))")
private external fun uncaughtError(target: EventTarget, error: String)

@JsFun("(target, reason) => { const e = new Event('unhandledrejection'); e.reason = new Error(reason); target.dispatchEvent(e); }")
private external fun rejectedPromise(target: EventTarget, reason: String)

/** The last-resort page for what no screen caught: a message, Reload, and the technical text behind Details. */
class CrashPageTest {
    @AfterTest
    fun clear() {
        document.getElementById("crash-page")?.remove()
    }

    private fun page() = assertNotNull(document.getElementById("crash-page"))

    @Test
    fun anUncaughtErrorShowsTheMessageWithReloadAndTheDetails() {
        val target = newTarget()
        installCrashPage(target) { Language.ENGLISH }
        uncaughtError(target, "boom")
        val page = page()
        assertEquals("Something went wrong", page.querySelector("h1")?.textContent)
        assertEquals("Reload", page.querySelector("button")?.textContent)
        assertEquals("Details", page.querySelector("summary")?.textContent)
        assertTrue("boom" in page.querySelector("pre")?.textContent.orEmpty())
    }

    @Test
    fun aRejectedPromiseShowsItToo() {
        val target = newTarget()
        installCrashPage(target) { Language.ENGLISH }
        rejectedPromise(target, "refused")
        assertTrue("refused" in page().querySelector("pre")?.textContent.orEmpty())
    }

    @Test
    fun theMessageIsInDutchWhenTheAppIsDutch() {
        val target = newTarget()
        installCrashPage(target) { Language.DUTCH }
        uncaughtError(target, "boom")
        assertEquals("Er ging iets mis", page().querySelector("h1")?.textContent)
        assertEquals("Opnieuw laden", page().querySelector("button")?.textContent)
    }

    @Test
    fun aSecondFailureAddsItsTextAndNotAnotherMessage() {
        val target = newTarget()
        installCrashPage(target) { Language.ENGLISH }
        uncaughtError(target, "first")
        uncaughtError(target, "second")
        assertEquals(1, document.querySelectorAll("#crash-page").length)
        val details = page().querySelector("pre")?.textContent.orEmpty()
        assertTrue("first" in details && "second" in details)
    }

    @Test
    fun anErrorEventWithoutAnErrorBreaksNothingAndShowsNothing() {
        val target = newTarget()
        installCrashPage(target) { Language.ENGLISH }
        uncaughtError(target, "")
        assertNull(document.getElementById("crash-page"))
    }
}
