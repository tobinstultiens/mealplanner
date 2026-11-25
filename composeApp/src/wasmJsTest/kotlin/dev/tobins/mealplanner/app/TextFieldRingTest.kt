package dev.tobins.mealplanner.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A page like the app's: a host with a shadow root holding a `[role=textbox]` element, as Compose makes for a text field, and a plain button. */
@JsFun(
    """() => {
        const host = document.createElement('div');
        const root = host.attachShadow({ mode: 'open' });
        const box = document.createElement('div');
        box.setAttribute('role', 'textbox');
        box.style.cssText = 'position:fixed;left:20px;top:30px;width:200px;height:40px';
        const button = document.createElement('div');
        button.setAttribute('role', 'button');
        button.style.cssText = 'position:fixed;left:20px;top:100px;width:200px;height:40px';
        root.append(box, button);
        document.body.append(host);
        window.textFieldTestHost = host;
    }""",
)
private external fun buildPage()

/** The [edge] ("left", "right" or "bottom") of the element with [role], in device pixels as the focus ring measures them. */
@JsFun(
    """(role, edge) => {
        const element = window.textFieldTestHost.shadowRoot.querySelector('[role=' + role + ']');
        return element.getBoundingClientRect()[edge] * window.devicePixelRatio;
    }""",
)
private external fun edge(role: String, edge: String): Float

@JsFun("() => { window.textFieldTestHost.remove(); delete window.textFieldTestHost; }")
private external fun removePage()

/** The keyboard ring is not drawn around a text field, so a Compose update that changes its element must fail here. */
class TextFieldRingTest {
    private fun isTextFieldAt(role: String): Boolean =
        isTextField(edge(role, "left"), edge(role, "right"), edge(role, "bottom"))

    @Test
    fun anElementWithTheRoleTextboxAndTheSameEdgesIsATextField() {
        buildPage()
        try {
            assertTrue(isTextFieldAt("textbox"))
        } finally {
            removePage()
        }
    }

    @Test
    fun anotherElementOrAnotherPlaceIsNot() {
        buildPage()
        try {
            assertFalse(isTextFieldAt("button"))
            assertFalse(isTextField(edge("textbox", "left") + 50f, edge("textbox", "right"), edge("textbox", "bottom")))
        } finally {
            removePage()
        }
    }
}
