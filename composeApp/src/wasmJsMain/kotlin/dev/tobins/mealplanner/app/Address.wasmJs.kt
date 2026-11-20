package dev.tobins.mealplanner.app

import kotlinx.browser.window
import org.w3c.dom.events.Event

/*
 * The address bar and the browser's history.
 *
 * Every history entry the app makes (the one it starts on, those the back handlers push) carries the state
 * "mealplanner". An entry the user made by typing or pasting an address carries none, and that is how the two are
 * told apart: `popstate` and `hashchange` both come for either, but only the first kind is answered by a back
 * handler, and only the second is a new address to follow.
 */

/** Whether the history entry the page is on is one the app made. */
@JsFun("() => history.state === 'mealplanner'")
internal external fun onAppEntry(): Boolean

@JsFun("(url) => history.replaceState('mealplanner', '', url)")
private external fun replaceAppEntry(url: String)

@JsFun("(url) => history.pushState('mealplanner', '', url)")
private external fun pushAppEntry(url: String)

/** The address the screen on show has, as the app last wrote it; null before the first one. */
private var shownAddress: String? = null

/** Marks the entry the page started on as the app's own, before any back handler pushes one on top of it. */
internal fun claimStartEntry() = replaceAppEntry(window.location.href)

/** Pushes a history entry for a back handler, showing the screen's own address and not that of the entry it was reached from. */
internal fun pushBackEntry() = pushAppEntry(shownAddress ?: window.location.href)

/**
 * After a Back or Forward that no back handler answered (a stale entry left by a screen closed from inside the
 * app), the address of the entry stepped to is not the screen's: put the screen's back.
 */
internal fun restoreShownAddress() {
    val shown = shownAddress ?: return
    if (window.location.hash != shown) replaceAppEntry(shown)
}

/**
 * The open screen lives in the address fragment; changing it adds no history entry, so the back button is untouched.
 * A new fragment typed into the page is reported by [watch].
 */
internal object AddressRoutes : RouteStore {
    override fun read(): String = window.location.hash

    override fun write(hash: String) {
        shownAddress = hash
        replaceAppEntry(hash)
    }

    override fun watch(onChange: (String) -> Unit): () -> Unit = watchAddress(onChange)
}

/** Calls [onChange] with the fragment of each address the user typed or pasted; returns what stops it. */
internal fun watchAddress(onChange: (String) -> Unit): () -> Unit {
    val listener: (Event) -> Unit = { if (!onAppEntry()) onChange(window.location.hash) }
    window.addEventListener("hashchange", listener)
    return { window.removeEventListener("hashchange", listener) }
}
