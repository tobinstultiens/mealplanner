package dev.tobins.mealplanner.android

import android.content.Context
import android.content.SharedPreferences
import dev.tobins.mealplanner.app.accounts.LoginExchange
import dev.tobins.mealplanner.app.Openings
import dev.tobins.mealplanner.app.SettingsStorage
import kotlinx.coroutines.MainScope

/** Settings in the app's private SharedPreferences. */
private class PreferencesStorage(private val prefs: SharedPreferences) : SettingsStorage {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/**
 * One storage for the screen, the login and the background check: Android keeps one SharedPreferences per
 * name, so all of them see every change at once.
 */
internal fun settingsStorage(context: Context): SettingsStorage =
    PreferencesStorage(context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE))

/** What lives as long as the process rather than the screen, shared by the screen and [NotificationWorker]. Only used on the main thread. */
internal object AppProcess {
    private var logins: LoginExchange? = null

    /**
     * The login: a token exchange under way when Android re-creates the screen finishes, once, and reaches
     * the new one (#176). The background check renews through it too, so that the screen and the check
     * never renew at the same time (see [LoginExchange.renew]).
     */
    fun logins(context: Context): LoginExchange =
        logins ?: LoginExchange(settingsStorage(context), MainScope()).also { logins = it }

    /** The screens tapped notifications ask for; the screen takes them (see [Openings]). */
    val openings = Openings()
}
