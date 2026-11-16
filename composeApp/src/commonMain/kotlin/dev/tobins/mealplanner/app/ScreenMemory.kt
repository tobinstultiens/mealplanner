package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * The last value each screen loaded, by key (`plan:2026-11-16`, `day:2026-11-16`, `recipes`, `recipe:4`,
 * `offers`, `pantry`, `weights`), so that a screen that comes back starts from what it showed and
 * refreshes behind it instead of showing a spinner. It lives in memory only: a cold start begins empty.
 * [App] makes a new one with every [ApiClient], so what one account loaded is never shown to the next.
 * Only the oldest entries go when more than [capacity] are kept (a plan or a day for every date looked at).
 */
class ScreenMemory(private val capacity: Int = 64) {
    private val values = LinkedHashMap<String, Any>()

    /** What was kept under [key], or null. The caller names the type it kept there. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> recall(key: String): T? = values[key] as T?

    fun keep(key: String, value: Any) {
        values.remove(key)
        values[key] = value
        while (values.size > capacity) values.remove(values.keys.first())
    }

    /** Drops [key], for a value the server says is gone. */
    fun forget(key: String) {
        values.remove(key)
    }
}

/** The memory [App] provides; null where a screen is shown on its own (tests), which then gets a memory of its own. */
internal val LocalScreenMemory = compositionLocalOf<ScreenMemory?> { null }

/**
 * What a screen has loaded: the state it draws, starting from what [ScreenMemory] kept under [key]
 * (ready at once) or [Load.Loading] when there is nothing yet. Every ready value set here is kept again.
 */
internal class LoadState<T : Any>(private val memory: ScreenMemory, private val key: String) : MutableState<Load<T>> {
    private val state = mutableStateOf<Load<T>>(memory.recall<T>(key)?.let { Load.Ready(it) } ?: Load.Loading)

    /** Whether the last [refresh] failed while content was kept: the screen shows it and offers a retry ([RefreshNotice]). */
    var refreshFailed by mutableStateOf(false)
        private set

    override var value: Load<T>
        get() = state.value
        set(new) {
            state.value = new
            if (new is Load.Ready) memory.keep(key, new.value)
        }

    override fun component1(): Load<T> = value
    override fun component2(): (Load<T>) -> Unit = { value = it }

    /**
     * Fetches the value again behind what is shown. A screen showing nothing yet shows the spinner and
     * then the result or the failure; one showing content keeps it when the fetch fails (a dropped
     * connection must not wipe a screen that was fine a minute ago), unless the server says the thing
     * is gone (404), which it then shows. Otherwise [refreshFailed] says so.
     */
    suspend fun refresh(fetch: suspend () -> T) {
        refreshFailed = false
        if (value !is Load.Ready) value = Load.Loading
        when (val result = load(fetch)) {
            is Load.Ready -> value = result
            is Load.Failed -> if (value !is Load.Ready) {
                value = result
            } else if (result.status == 404) {
                memory.forget(key)
                value = result
            } else {
                refreshFailed = true
            }
            Load.Loading -> Unit
        }
    }
}

/** The [LoadState] for [key]; a new key (another week, another recipe) starts from what was kept for that one. */
@Composable
internal fun <T : Any> rememberLoad(key: String): LoadState<T> {
    val memory = LocalScreenMemory.current ?: remember { ScreenMemory() }
    return remember(memory, key) { LoadState(memory, key) }
}

/**
 * The line a screen shows at its bottom when fetching again failed behind the content it kept: the
 * content stays, the user is told it may be out of date, and [onRetry] asks again. Nothing when all is well.
 */
@Composable
internal fun RefreshNotice(loaded: LoadState<*>, onRetry: () -> Unit) {
    if (!loaded.refreshFailed) return
    val t = LocalStrings.current
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            Modifier.padding(start = Spacing.ScreenEdge),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Related),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t(Msg.REFRESH_FAILED_KEPT), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRetry) { Text(t(Msg.RETRY)) }
        }
    }
}
