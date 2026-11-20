package dev.tobins.mealplanner.app

import kotlinx.browser.document

/**
 * Tab out of a text field leaves the page's focus on `body`: the browser has nowhere else to put it, and key
 * events then go to `body`, not to the app, so Enter and Space do nothing on the button Compose has focused. This
 * hands the focus back to the app's element when it was lost to `body` while the page still has the focus (a Tab
 * out of the page to the browser's own controls leaves the page without it, and is not touched). With the
 * crash page up the frozen app must not get it: the crash page keeps its own focus.
 */
@JsFun(
    """() => {
        document.addEventListener('focusout', () => setTimeout(() => {
            if (!document.hasFocus() || document.activeElement !== document.body || document.getElementById('crash-page')) return;
            const host = Array.from(document.body.querySelectorAll('*')).find((element) => element.shadowRoot);
            const canvas = host && host.shadowRoot.querySelector('canvas');
            if (canvas) canvas.focus();
        }, 0), true);
    }""",
)
internal external fun keepFocusInPage()

/** Tells the browser which language the page is in, for screen readers, hyphenation and translation offers. */
internal fun showPageLanguage(language: Language) {
    document.documentElement?.setAttribute("lang", if (language == Language.DUTCH) "nl" else "en")
}
