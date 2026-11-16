package dev.tobins.mealplanner.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.tobins.mealplanner.app.LoginLauncher
import android.content.pm.PackageManager
import android.os.Build
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import dev.tobins.mealplanner.app.App
import dev.tobins.mealplanner.app.LoginExchange
import dev.tobins.mealplanner.app.ServerSettings
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

/** One storage for the screen and the login: Android keeps one SharedPreferences per name, so both see every change at once. */
private fun settingsStorage(context: Context): SettingsStorage =
    PreferencesStorage(context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE))

/**
 * Opens Authelia's login page in a Custom Tab over the app: it shares the browser's cookies (an existing
 * Authelia session logs in with one tap), passkeys, password managers and two-factor keep working, and the
 * app never sees the password (RFC 8252: no WebView). The page ends on the server's `/app/login`, which
 * Android hands to this app (App Link) or which forwards to the app's own scheme; the answer arrives in
 * [MainActivity.onNewIntent], and as that activity is singleTask the tab above it in the task closes.
 * Without a browser that offers Custom Tabs the page opens in the browser, as it did before.
 */
private class CustomTabLogin(private val activity: Activity) : LoginLauncher {
    override fun redirectUri(serverUrl: String) = serverUrl.trimEnd('/') + "/app/login"

    override fun open(url: String) {
        val page = Uri.parse(url)
        val browser = customTabsBrowser()
        if (browser != null) {
            CustomTabsIntent.Builder().build().apply { intent.setPackage(browser) }.launchUrl(activity, page)
        } else {
            activity.startActivity(Intent(Intent.ACTION_VIEW, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * A browser that offers Custom Tabs: the default one if it does, else any installed. Asked with every
     * browser listed, since the default alone is unknown until the user picked one.
     */
    private fun customTabsBrowser(): String? {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = activity.packageManager.queryIntentActivities(view, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        return CustomTabsClient.getPackageName(activity, browsers)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val storage = settingsStorage(this)
        val logins = logins(this)
        // Debug builds are signed with another key, so a release could not install over them.
        val updater = if (BuildConfig.DEBUG) null else AndroidUpdateInstaller(this)
        if (updater != null) {
            UpdateCheckWorker.schedule(this)
            askForNotifications()
        }
        intent?.newLoginAnswer(savedInstanceState)?.let(logins::deliver)
        setContent {
            App(
                ServerSettings(BuildConfig.SERVER_URL), storage, CustomTabLogin(this), logins, updater,
                defaultsBuiltIn = BuildConfig.SERVER_URL_BUILT_IN,
            )
        }
    }

    /** The login comes back here: an App Link to `<server>/app/login?code=…`, or its bounce `dev.tobins.mealplanner://login?code=…`. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { logins(this).deliver(it.toString()) }
    }

    /**
     * The login answer this intent brings, when it is new. The intent that started the screen comes
     * again when Android re-creates it ([savedInstanceState] set: a rotation, a dark mode or language
     * change, the app stopped in the background) and when the task is opened again from the recent
     * apps; an answer it holds was handled the first time, and its code works only once.
     */
    private fun Intent.newLoginAnswer(savedInstanceState: Bundle?): String? {
        if (savedInstanceState != null || (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        return data?.toString()
    }

    /** Android 13+ needs consent for the "update available" notification; asked once. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (prefs.getBoolean("askedForNotifications", false)) return
        prefs.edit().putBoolean("askedForNotifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    companion object {
        /**
         * The login for as long as the process lives rather than the screen: a token exchange under way
         * when Android re-creates the screen finishes, once, and reaches the new one (#176). Only used on
         * the main thread.
         */
        private var logins: LoginExchange? = null

        private fun logins(context: Context): LoginExchange =
            logins ?: LoginExchange(settingsStorage(context), MainScope()).also { logins = it }
    }
}
