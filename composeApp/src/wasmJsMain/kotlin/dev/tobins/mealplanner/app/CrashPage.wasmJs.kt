package dev.tobins.mealplanner.app

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget

private const val CRASH_PAGE_ID = "crash-page"

/**
 * The text behind a page-level failure, for "Details": the stack of the error (or of what a promise was rejected
 * with) when there is one, else its message. Empty for an `error` event that carries no error at all, such as
 * the browser's own "ResizeObserver loop completed with undelivered notifications", which breaks nothing.
 */
@JsFun(
    """(e) => {
        const cause = e.type === 'error' ? e.error : e.reason;
        if (cause == null) return e.type === 'error' ? '' : 'The promise was rejected with nothing.';
        return String(cause.stack || cause.message || cause);
    }""",
)
private external fun describeFailure(event: JsAny): String

/**
 * Last resort for what no screen caught. A throwable that escapes a launched coroutine fails Compose's recomposer
 * on the web, after which the page draws nothing new and ignores every click until it is reloaded. This listens on
 * [target] (the page) for `error` and `unhandledrejection` and puts [showCrashPage] over the frozen page instead.
 * Nothing is sent anywhere: the technical text stays on the page. [language] is asked when a failure comes, since
 * the language can be changed while the page is open.
 */
internal fun installCrashPage(target: EventTarget = window, language: () -> Language) {
    for (type in listOf("error", "unhandledrejection")) {
        target.addEventListener(type) { event: Event ->
            val details = describeFailure(event)
            if (details.isNotEmpty()) showCrashPage(details, language())
        }
    }
}

/**
 * Shows the plain message with a Reload button over the page, and the technical [details] behind a "Details"
 * disclosure. It is made of plain page elements, not of Compose, which is what has stopped. A second failure
 * adds its text under the first and does not stack another message.
 */
internal fun showCrashPage(details: String, language: Language) {
    val existing = document.getElementById(CRASH_PAGE_ID)
    if (existing != null) {
        existing.querySelector("pre")?.let { it.textContent = it.textContent + "\n\n" + details }
        return
    }
    followChosenTheme()
    val page = element("div", CRASH_PAGE_ID)
    page.setAttribute("role", "alert")
    page.style.cssText = "position:fixed;inset:0;z-index:2147483647;display:flex;align-items:center;justify-content:center;" +
        "padding:16px;box-sizing:border-box;background:var(--page);color:var(--ink);font-family:system-ui,sans-serif;"
    val card = element("div")
    card.style.cssText = "max-width:480px;width:100%;"
    val title = element("h1")
    title.style.cssText = "font-size:1.5rem;margin:0 0 8px;"
    title.textContent = Msg.CRASH_TITLE.text(language)
    val text = element("p")
    text.style.cssText = "margin:0 0 16px;line-height:1.4;"
    text.textContent = Msg.CRASH_TEXT.text(language)
    val reload = element("button")
    reload.style.cssText = "font:inherit;padding:10px 24px;border:0;border-radius:20px;background:#3b6a2a;color:#fff;cursor:pointer;"
    reload.textContent = Msg.CRASH_RELOAD.text(language)
    reload.addEventListener("click") { window.location.reload() }
    val more = element("details")
    more.style.cssText = "margin-top:24px;"
    val summary = element("summary")
    summary.textContent = Msg.DETAILS.text(language)
    val pre = element("pre")
    pre.style.cssText = "white-space:pre-wrap;word-break:break-word;max-height:40vh;overflow:auto;font-size:0.75rem;"
    pre.textContent = details
    more.append(summary, pre)
    card.append(title, text, reload, more)
    page.append(card)
    document.body?.append(page)
    reload.focus()
}

private fun element(tag: String, id: String? = null): HTMLElement {
    val created = document.createElement(tag) as HTMLElement
    if (id != null) created.id = id
    return created
}

/**
 * The page's colours come from `data-theme`, which `index.html` sets from the Light/Dark choice once when the page
 * loads; a choice made since then is read here, so that the page is not light in a dark app.
 */
private fun followChosenTheme() {
    val root = document.documentElement ?: return
    when (val chosen = localStorage.getItem("theme")) {
        "LIGHT", "DARK" -> root.setAttribute("data-theme", chosen.lowercase())
        else -> root.removeAttribute("data-theme")
    }
}
