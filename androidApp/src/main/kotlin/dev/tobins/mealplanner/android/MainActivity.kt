package dev.tobins.mealplanner.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.tobins.mealplanner.app.accounts.LoginLauncher
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import dev.tobins.mealplanner.app.App
import dev.tobins.mealplanner.app.ServerSettings
import dev.tobins.mealplanner.app.AndroidNotificationAccess
import dev.tobins.mealplanner.app.NotificationInbox

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
    /** Registered as the screen is made, which Android requires of a permission request. */
    private val notificationAccess = AndroidNotificationAccess(this)

    /** The taps on notifications this screen has handed on; made from the saved state in [onCreate]. */
    private lateinit var inbox: NotificationInbox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inbox = NotificationInbox(savedInstanceState)
        enableEdgeToEdge()
        val storage = settingsStorage(this)
        val logins = AppProcess.logins(this)
        // Debug builds are signed with another key, so a release could not install over them.
        val updater = if (BuildConfig.DEBUG) null else AndroidUpdateInstaller(this)
        // Debug builds check too, for what the server says is due; only the update check is left out there.
        NotificationWorker.schedule(this)
        if (updater != null) askForNotifications()
        intent?.let { started ->
            if (started.isNew(savedInstanceState)) deliverLogin(started)
            // Decided by the tap's own id, not by whether there is saved state: see NotificationInbox.
            takeOpening(started)
        }
        setContent {
            App(
                ServerSettings(BuildConfig.SERVER_URL), storage, CustomTabLogin(this), logins, updater,
                defaultsBuiltIn = BuildConfig.SERVER_URL_BUILT_IN,
                openings = AppProcess.openings,
                notificationAccess = notificationAccess,
            )
        }
    }

    /** The intents that reach the screen while it runs: the login coming back, or a tapped notification. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deliverLogin(intent)
        takeOpening(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        inbox.save(outState)
    }

    /** The login coming back: an App Link to `<server>/app/login?code=…`, or its bounce `dev.tobins.mealplanner://login?code=…`. */
    private fun deliverLogin(intent: Intent) {
        intent.data?.let { AppProcess.logins(this).deliver(it.toString()) }
    }

    /** The screen a tapped notification asks for, handed to the app once per tap. */
    private fun takeOpening(intent: Intent) {
        inbox.take(intent)?.let(AppProcess.openings::open)
    }

    /**
     * Whether this intent's login answer is new to the screen. The intent that started the screen comes again
     * when Android re-creates it ([savedInstanceState] set: a rotation, a dark mode or language change, the app
     * stopped in the background) and when the task is opened again from the recent apps; an answer it holds was
     * handled the first time, and its code works only once.
     */
    private fun Intent.isNew(savedInstanceState: Bundle?): Boolean =
        savedInstanceState == null && (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0

    /** Android 13+ needs consent for notifications, the update's among them, on by default; asked once. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (prefs.getBoolean("askedForNotifications", false)) return
        prefs.edit().putBoolean("askedForNotifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
