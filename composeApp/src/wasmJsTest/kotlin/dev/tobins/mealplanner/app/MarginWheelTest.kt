package dev.tobins.mealplanner.app

import org.w3c.dom.events.EventTarget
import kotlin.test.Test
import kotlin.test.assertEquals

/** A page element with an element inside it that records the wheel events it hears, as `clientX:deltaY`. */
@JsFun(
    """() => {
        const page = document.createElement('div');
        const inner = document.createElement('div');
        page.append(inner);
        document.body.append(page);
        page.inner = inner;
        window.heardWheel = [];
        inner.addEventListener('wheel', (e) => window.heardWheel.push(e.clientX + ':' + e.deltaY));
        return page;
    }""",
)
private external fun newPage(): EventTarget

@JsFun("(page, x) => page.inner.dispatchEvent(new WheelEvent('wheel', { clientX: x, clientY: 10, deltaY: 40, bubbles: true, cancelable: true }))")
private external fun turnWheel(page: EventTarget, x: Int)

@JsFun("() => window.heardWheel.join(',')")
private external fun heardWheel(): String

@JsFun("() => Math.floor(document.documentElement.clientWidth / 2)")
private external fun middleOfWindow(): Int

@JsFun("(page) => { page.remove(); delete window.heardWheel; }")
private external fun removePage(page: EventTarget)

/** The wheel scrolls the column from the margins of a wide window too. */
class MarginWheelTest {
    /** Turns the wheel at [x] with a column of 200 px (the test window is wider) and says what the page heard. */
    private fun turnAt(x: Int): String {
        val page = newPage()
        val stop = forwardWheelFromMargins(page, 200)
        turnWheel(page, x)
        val result = heardWheel()
        stopForwardingWheel(stop)
        removePage(page)
        return result
    }

    @Test
    fun aWheelOverAMarginReachesTheColumnOnceFromItsMiddle() {
        assertEquals("${middleOfWindow()}:40", turnAt(x = 2))
    }

    @Test
    fun aWheelOverTheColumnIsLeftAlone() {
        val inside = middleOfWindow() + 10
        assertEquals("$inside:40", turnAt(x = inside))
    }
}
