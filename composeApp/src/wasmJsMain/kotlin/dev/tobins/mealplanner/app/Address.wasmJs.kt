package dev.tobins.mealplanner.app

import kotlinx.browser.window
import org.w3c.dom.events.Event

/*
 * The address bar and the browser's history.
 *
 * Every history entry the app makes (the one it starts on, those the back handlers push) carries a state
 * "mealplanner:<place>": its place in the history, one more than the entry it follows, so that a step to another
 * entry shows whether it was a Back or a Forward. An entry the user made by typing or pasting an address carries
 * none, and that is how the two are told apart: `popstate` and `hashchange` both come for either, but only the
 * first kind is answered by a back handler or followed as a step, and only the second is a new address.
 *
 * The address of an entry is the screen that was open on it, so a Back that no back handler answers, and every
 * Forward, go to that screen. The entry the app returns to from the login page is the "base" (":base" after its
 * place): the entry before it is the login page, which Back must not reach, so a guard entry is pushed on top
 * and a Back onto the base stays on the screen.
 */

private const val ENTRY = "mealplanner"

/** The state of the history entry the page is on, or empty when it carries none that is a text. */
@JsFun("() => typeof history.state === 'string' ? history.state : ''")
private external fun entryState(): String

@JsFun("(state, url) => history.replaceState(state, '', url)")
private external fun replaceEntryWith(state: String, url: String)

@JsFun("(state, url) => history.pushState(state, '', url)")
private external fun pushEntryWith(state: String, url: String)

/** Whether the history entry the page is on is one the app made. */
internal fun onAppEntry(): Boolean = entryState().startsWith(ENTRY)

private fun placeOf(state: String): Int? = state.removePrefix(ENTRY).trimStart(':').substringBefore(':').toIntOrNull()

private fun stateAt(place: Int, base: Boolean) = "$ENTRY:$place" + if (base) ":base" else ""

/** Where in the history the page is. */
private var place = 0

/** Whether the entry the page is on is the base entry (see above). */
private var onBase = false

/** The address the screen on show has, as the app last wrote it; null before the first one. */
private var shownAddress: String? = null

/** Hears an address the app should open; set while [AddressRoutes.watch] listens. */
private var openAddress: ((String) -> Unit)? = null

/** Takes the place and the base mark from the entry the page is on, as a step to another entry or a reload lands on it. */
private fun readEntry() {
    val state = entryState()
    place = placeOf(state) ?: place
    onBase = state.endsWith(":base")
}

/** Rewrites the current entry's address, keeping its place and mark. */
private fun replaceAppEntry(url: String) = replaceEntryWith(stateAt(place, onBase), url)

private fun pushAppEntry(url: String) {
    place++
    onBase = false
    pushEntryWith(stateAt(place, false), url)
}

/**
 * Marks the entry the page started on as the app's own, before any back handler pushes one on top of it. A
 * reloaded page keeps the place its entry had. Coming back from the login page ([afterLogin]) it is the base
 * entry, with a guard entry on top (see above).
 */
internal fun claimStartEntry(afterLogin: Boolean) {
    readEntry()
    onBase = afterLogin
    replaceAppEntry(window.location.href)
    if (afterLogin) pushAppEntry(window.location.href)
}

/** The address a step has just opened, and when: the screen it opens is already on the entry stepped to. */
private var steppedTo: String? = null
private var steppedToAt = 0.0

/** How long after a step the screen it opens is still taken to be the one on the entry stepped to (it draws within a frame or two). */
private const val STEP_SETTLES_MS = 1000

/**
 * Pushes a history entry for a back handler, showing the screen's own address and not that of the entry it was
 * reached from. Not when a Back or Forward has just opened that screen (all its handlers, as a screen can have
 * several): the entry stepped to is its entry, and a new one would cut off the entries ahead of it.
 */
internal fun pushBackEntry() {
    val address = shownAddress ?: window.location.href
    val justOpened = steppedTo == address && window.performance.now() - steppedToAt < STEP_SETTLES_MS
    if (!justOpened) pushAppEntry(address)
}

/**
 * A Back or Forward to an entry of the app. A Back goes to the innermost back handler when there is one ([answered]
 * runs it and says whether there was). Otherwise the screen that was open on that entry opens, as its address says;
 * a Back onto the base entry stays on the screen and puts a new guard entry on top.
 */
internal fun steppedToEntry(answered: () -> Boolean) {
    val before = place
    readEntry()
    val back = place < before
    if (back && answered()) return
    if (back && onBase) return pushBackEntry()
    val hash = window.location.hash
    if (hash == shownAddress) return
    steppedTo = hash
    steppedToAt = window.performance.now()
    openAddress?.invoke(hash)
}

/**
 * The open screen lives in the address fragment; changing it adds no history entry, so the back button is untouched.
 * A new fragment typed into the page, or a step to another entry of the history, is reported by [watch].
 */
internal object AddressRoutes : RouteStore {
    override fun read(): String = window.location.hash

    override fun write(hash: String) {
        shownAddress = hash
        replaceAppEntry(hash)
    }

    override fun watch(onChange: (String) -> Unit): () -> Unit {
        openAddress = onChange
        val stop = watchAddress(onChange)
        return {
            stop()
            openAddress = null
        }
    }
}

/** Calls [onChange] with the fragment of each address the user typed or pasted; returns what stops it. */
internal fun watchAddress(onChange: (String) -> Unit): () -> Unit {
    val listener: (Event) -> Unit = {
        if (!onAppEntry()) {
            // A new entry, after the one the user was on.
            place++
            onBase = false
            onChange(window.location.hash)
        }
    }
    window.addEventListener("hashchange", listener)
    return { window.removeEventListener("hashchange", listener) }
}
