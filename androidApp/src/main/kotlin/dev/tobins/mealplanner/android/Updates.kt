package dev.tobins.mealplanner.android

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import dev.tobins.mealplanner.app.AppUpdate
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.UpdateInstaller
import java.io.File

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
        Toast.makeText(app, Strings.current(Msg.DOWNLOADING_VERSION, update.version), Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                val uri = downloads.getUriForDownloadedFile(id)
                if (uri == null) {
                    Toast.makeText(app, Strings.current(Msg.DOWNLOAD_FAILED), Toast.LENGTH_LONG).show()
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
