package dev.tobins.mealplanner.app

import org.w3c.dom.events.EventTarget

/**
 * Lets the mouse wheel scroll from the empty margins of a wide window too. The app keeps a column of
 * [column] CSS pixels in the middle and the lists scroll under the pointer only, so a wheel turned over a
 * margin did nothing. A wheel event that lands in a margin of [target]'s window is stopped there and sent again
 * from the same place a little further in, as if the pointer were over the column, at the middle of its width
 * and the same height. The copy is marked so that it is not sent on again, and a pinch zoom (ctrl) is left to
 * the browser. Returns what stops it.
 */
@JsFun(
    """(target, column) => {
        const forward = (e) => {
            if (e.forwardedFromMargin || e.ctrlKey) return;
            const width = document.documentElement.clientWidth;
            const margin = (width - column) / 2;
            if (margin <= 0 || (e.clientX >= margin && e.clientX <= width - margin)) return;
            e.stopImmediatePropagation();
            e.preventDefault();
            const copy = new WheelEvent('wheel', {
                deltaX: e.deltaX, deltaY: e.deltaY, deltaZ: e.deltaZ, deltaMode: e.deltaMode,
                clientX: width / 2, clientY: e.clientY, screenX: e.screenX, screenY: e.screenY,
                shiftKey: e.shiftKey, altKey: e.altKey, metaKey: e.metaKey,
                bubbles: true, cancelable: true, composed: true,
            });
            copy.forwardedFromMargin = true;
            e.target.dispatchEvent(copy);
        };
        target.addEventListener('wheel', forward, { capture: true, passive: false });
        return () => target.removeEventListener('wheel', forward, { capture: true });
    }""",
)
internal external fun forwardWheelFromMargins(target: EventTarget, column: Int): JsAny

@JsFun("(stop) => stop()")
internal external fun stopForwardingWheel(stop: JsAny)
