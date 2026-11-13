package dev.tobins.mealplanner.android

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.tobins.mealplanner.app.AppUpdate
import dev.tobins.mealplanner.app.AppUpdates
import dev.tobins.mealplanner.app.UpdateInstaller
import java.io.File
import java.util.concurrent.TimeUnit

private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * Downloads a release APK with the system download manager (which shows its progress) and
 * opens it in the package installer once it is there. Android asks once to allow installs
 * from this app. Every release is signed with the same key, so it installs over this one.
 */
class AndroidUpdateInstaller(private val context: Context) : UpdateInstaller {
    override val currentVersion: String = BuildConfig.VERSION_NAME

    override fun install(update: AppUpdate) {
        val app = context.applicationContext
        val name = "mealplanner-${update.version}.apk"
        File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name).delete()
        val downloads = app.getSystemService(DownloadManager::class.java)
        val id = downloads.enqueue(
            DownloadManager.Request(Uri.parse(update.apkUrl))
                .setTitle("Meal planner ${update.version}")
                .setMimeType(APK_MIME)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE),
        )
        Toast.makeText(app, "Downloading version ${update.version}…", Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                val uri = downloads.getUriForDownloadedFile(id)
                if (uri == null) {
                    Toast.makeText(app, "The download failed; try again from Settings.", Toast.LENGTH_LONG).show()
                    return
                }
                app.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        // The broadcast comes from the system's download manager, so it must be exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
    }
}

/**
 * Checks GitHub for a new release once a day and posts a notification when there is one,
 * once per version. Tapping it opens the app, which offers the update on start.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val update = runCatching { AppUpdates.check(BuildConfig.VERSION_NAME) }.getOrElse { return Result.retry() }
            ?: return Result.success()
        val prefs = applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (prefs.getString(NOTIFIED_KEY, null) == update.version) return Result.success()
        if (notify(applicationContext, update)) prefs.edit().putString(NOTIFIED_KEY, update.version).apply()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "update-check"
        private const val NOTIFIED_KEY = "notifiedVersion"
        private const val CHANNEL = "updates"

        /** Schedules the daily check; keeps an existing schedule. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Posts the "update available" notification; false when notifications are not allowed. */
        private fun notify(context: Context, update: AppUpdate): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Meal planner ${update.version} is available")
                .setContentText("Tap to update.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(1, notification)
            return true
        }
    }
}
