package dev.tobins.mealplanner.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/*
 * The Android side of the notifications (issue 225) that the screen needs: the permission, and the screen a
 * tapped notification asks for. Kept here rather than in the Android app so that Robolectric tests reach it.
 */

private const val EXTRA_OPENING = "dev.tobins.mealplanner.opening"
private const val EXTRA_OPENING_ID = "dev.tobins.mealplanner.openingId"
private const val HANDLED_OPENING_KEY = "dev.tobins.mealplanner.handledOpening"

/** Whether the system lets the app post: the permission (Android 13+), and the app's notifications not turned off in its settings. */
fun notificationsAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false
    return context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}

/**
 * The Notifications card's way to the permission. Made with [activity], before it starts, as Android requires
 * of a permission request; [allowed] is read again each time the activity resumes, which it does after the
 * system's question and after the system's settings, and when the app comes back to the front.
 */
class AndroidNotificationAccess(private val activity: ComponentActivity) : NotificationAccess {
    private val state = MutableStateFlow(notificationsAllowed(activity))
    override val allowed: StateFlow<Boolean> = state.asStateFlow()

    private val request = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { readAgain() }

    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) readAgain() })
    }

    private fun readAgain() {
        state.value = notificationsAllowed(activity)
    }

    override fun ask() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) return readAgain()
        request.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun openSettings() {
        activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
    }
}

/** Puts [opening] in this intent, with an [id] no other tap has, so that the screen tells this tap from any other ([NotificationInbox]). */
fun Intent.putOpening(opening: Opening, id: String = UUID.randomUUID().toString()): Intent =
    putExtra(EXTRA_OPENING, opening.encoded).putExtra(EXTRA_OPENING_ID, id)

/**
 * Which screen a tapped notification asks the activity for, once per tap. Android hands the activity an intent
 * it handled before when it re-creates the screen (a rotation, a language change) and when the task is opened
 * again from Recents; but after it killed the process with the task still in Recents, a notification tapped
 * then reaches the re-created screen together with its saved state. Whether there is saved state therefore
 * says nothing; the id of the last tap handled, kept in that saved state ([save]), does.
 */
class NotificationInbox(saved: Bundle?) {
    private var handled: String? = saved?.getString(HANDLED_OPENING_KEY)

    /** The screen [intent] asks for, when it is a tap this screen has not handled yet; null otherwise. */
    fun take(intent: Intent): Opening? {
        val id = intent.getStringExtra(EXTRA_OPENING_ID) ?: return null
        if (id == handled || (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        handled = id
        return intent.getStringExtra(EXTRA_OPENING)?.let(::parseOpening)
    }

    /** Keeps the last tap handled in the activity's [outState], for the screen Android re-creates from it. */
    fun save(outState: Bundle) {
        handled?.let { outState.putString(HANDLED_OPENING_KEY, it) }
    }
}
