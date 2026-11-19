package dev.tobins.mealplanner.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.tobins.mealplanner.app.AppUpdates
import dev.tobins.mealplanner.app.DueNotification
import dev.tobins.mealplanner.app.NotificationKind
import dev.tobins.mealplanner.app.NotificationPlanner
import dev.tobins.mealplanner.app.ServerSettings
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.appLanguage
import dev.tobins.mealplanner.app.askWhatIsDue
import dev.tobins.mealplanner.app.deviceLocaleTag
import dev.tobins.mealplanner.app.notificationsAllowed
import dev.tobins.mealplanner.app.putOpening
import dev.tobins.mealplanner.app.serverSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The one background check (issue 225), every four hours on any network, as often as Android allows: the
 * latest release, as the daily update check did before (not in a debug build, which a release cannot
 * replace), and, while logged in, what the server says is due. [NotificationPlanner] decides what to post;
 * this only asks and posts. Without the system's permission it asks nothing; a failure tells nothing, shows
 * nothing and waits for the next run.
 */
class NotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!notificationsAllowed(app)) return Result.success()
        val storage = settingsStorage(app)
        val planner = NotificationPlanner(storage)
        takeOverUpdateCheck(app, planner)
        val update = if (!BuildConfig.DEBUG && planner.wanted(NotificationKind.UPDATE)) {
            runCatching { AppUpdates.check(BuildConfig.VERSION_NAME) }.getOrNull()
        } else {
            null
        }
        // On the main thread, where the login lives: a renewal the screen is making is waited for, not repeated.
        val facts = if (planner.wantsFacts()) {
            withContext(Dispatchers.Main) {
                askWhatIsDue(storage, AppProcess.logins(app), storage.serverSettings(ServerSettings(BuildConfig.SERVER_URL)))
            }
        } else {
            null
        }
        val strings = Strings.of(storage.appLanguage(), deviceLocaleTag())
        for (due in planner.due(facts, update, strings)) {
            if (post(app, due, strings)) planner.told(due)
        }
        return Result.success()
    }

    /**
     * The version the daily update check told, kept in its own preferences before this worker replaced it: taken
     * over once, so that an update told before the upgrade is not told again.
     */
    private fun takeOverUpdateCheck(context: Context, planner: NotificationPlanner) {
        val old = context.getSharedPreferences(UPDATE_CHECK_PREFERENCES, Context.MODE_PRIVATE)
        val version = old.getString(UPDATE_CHECK_TOLD_KEY, null) ?: return
        planner.toldBefore(NotificationKind.UPDATE, version)
        old.edit().remove(UPDATE_CHECK_TOLD_KEY).apply()
    }

    companion object {
        private const val WORK_NAME = "notifications"

        /** Where the daily update check kept the version it told, and under which key. */
        private const val UPDATE_CHECK_PREFERENCES = "updates"
        private const val UPDATE_CHECK_TOLD_KEY = "notifiedVersion"

        /** The daily update check's schedule, which this one replaces. */
        private const val UPDATE_CHECK_WORK_NAME = "update-check"

        /** Schedules the check every four hours, which reaches the afternoon's and evening's windows; keeps an existing schedule. */
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(UPDATE_CHECK_WORK_NAME)
            val request = PeriodicWorkRequestBuilder<NotificationWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/**
 * Posts [due] on its kind's channel; tapping it opens its screen (see [MainActivity]). False when the system
 * does not let the app post. The channel is made again each time, which renames it into the app's language
 * of now and keeps what the user set for it.
 */
private fun post(context: Context, due: DueNotification, strings: Strings): Boolean {
    if (!notificationsAllowed(context)) return false
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(due.kind.channel, strings(due.kind.title), NotificationManager.IMPORTANCE_DEFAULT))
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    due.opening?.let(intent::putOpening)
    // One request code per kind: Android would otherwise hand every notification the extras of the last one.
    val open = PendingIntent.getActivity(context, due.kind.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val notification = Notification.Builder(context, due.kind.channel)
        .setSmallIcon(R.drawable.ic_stat_notification)
        .setContentTitle(due.title)
        .setContentText(due.text)
        .setStyle(Notification.BigTextStyle().bigText(due.text))
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    // One notification per kind, the update's with the id it always had: a newer one of a kind replaces the older.
    manager.notify(due.kind.ordinal + 1, notification)
    return true
}
