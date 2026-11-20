package dev.tobins.mealplanner.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** A page like the app's: a host with a shadow root and a canvas in it, which records the wheel events it hears as `deltaY`. */
@JsFun(
    """() => {
        const host = document.createElement('div');
        const canvas = document.createElement('canvas');
        host.attachShadow({ mode: 'open' }).append(canvas);
        const field = document.createElement('input');
        document.body.append(host, field);
        window.heardWheel = [];
        canvas.addEventListener('wheel', (e) => window.heardWheel.push(Math.round(e.deltaY)));
        window.scrollTestParts = [host, field];
    }""",
)
private external fun buildPage()

/** Presses [key] on the canvas, or in the text field when [inField], with ctrl when [ctrl]. */
@JsFun(
    """(key, inField, ctrl) => {
        const [host, field] = window.scrollTestParts;
        const origin = inField ? field : host.shadowRoot.querySelector('canvas');
        origin.dispatchEvent(new KeyboardEvent('keydown', { key, ctrlKey: ctrl, bubbles: true, cancelable: true, composed: true }));
    }""",
)
private external fun press(key: String, inField: Boolean, ctrl: Boolean)

@JsFun("() => window.heardWheel.join(',')")
private external fun heardWheel(): String

@JsFun("() => Math.round(0.9 * window.innerHeight)")
private external fun aPage(): Int

@JsFun("() => { window.scrollTestParts.forEach((part) => part.remove()); delete window.scrollTestParts; delete window.heardWheel; }")
private external fun removePage()

/** Home, End, Page Up and Page Down scroll the open list as they do on a web page. */
class ScrollKeysTest {
    private fun heardAfter(key: String, inField: Boolean = false, ctrl: Boolean = false): String {
        buildPage()
        val stop = scrollWithKeys(kotlinx.browser.window)
        press(key, inField, ctrl)
        val result = heardWheel()
        stopScrollingWithKeys(stop)
        removePage()
        return result
    }

    @Test
    fun pageDownAndPageUpScrollAScreenfulMinusSome() {
        assertEquals("${aPage()}", heardAfter("PageDown"))
        assertEquals("-${aPage()}", heardAfter("PageUp"))
    }

    @Test
    fun homeAndEndScrollFarEnoughToReachTheEnds() {
        assertEquals("1000000", heardAfter("End"))
        assertEquals("-1000000", heardAfter("Home"))
    }

    @Test
    fun otherKeysAndKeysInATextFieldOrWithCtrlAreLeftAlone() {
        assertEquals("", heardAfter("a"))
        assertEquals("", heardAfter("End", inField = true))
        assertEquals("", heardAfter("Home", ctrl = true))
    }
}
