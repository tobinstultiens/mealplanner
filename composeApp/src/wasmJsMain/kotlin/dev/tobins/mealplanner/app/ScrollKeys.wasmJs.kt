package dev.tobins.mealplanner.app

import org.w3c.dom.events.EventTarget

/**
 * Lets Home, End, Page Up and Page Down scroll the open list, as they do on any web page; the canvas is not a
 * scrollable page, so the browser does nothing with them. A press of one of these keys (alone, not in a text field,
 * and not already taken by someone) is answered like a wheel turn over the middle of the column: a page is nine
 * tenths of the window's height, Home and End a distance no list has. The list stops at its ends by itself.
 * Returns what stops it.
 */
@JsFun(
    """(target) => {
        const scrollBy = { PageDown: () => 0.9 * window.innerHeight, PageUp: () => -0.9 * window.innerHeight, End: () => 1e6, Home: () => -1e6 };
        const scroll = (e) => {
            const distance = scrollBy[e.key];
            if (!distance || e.defaultPrevented || e.ctrlKey || e.altKey || e.metaKey || e.shiftKey) return;
            const origin = e.composedPath()[0];
            if (origin && (origin.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(origin.tagName))) return;
            const host = Array.from(document.body.querySelectorAll('*')).find((element) => element.shadowRoot);
            const canvas = host && host.shadowRoot.querySelector('canvas');
            if (!canvas) return;
            e.preventDefault();
            const wheel = new WheelEvent('wheel', {
                deltaY: distance(), deltaMode: 0, clientX: window.innerWidth / 2, clientY: window.innerHeight / 2,
                bubbles: true, cancelable: true, composed: true,
            });
            // Already in the middle: the margin forwarding must leave it alone.
            wheel.forwardedFromMargin = true;
            canvas.dispatchEvent(wheel);
        };
        target.addEventListener('keydown', scroll);
        return () => target.removeEventListener('keydown', scroll);
    }""",
)
internal external fun scrollWithKeys(target: EventTarget): JsAny

@JsFun("(stop) => stop()")
internal external fun stopScrollingWithKeys(stop: JsAny)
