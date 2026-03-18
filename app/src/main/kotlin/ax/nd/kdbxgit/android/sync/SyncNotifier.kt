package ax.nd.kdbxgit.android.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ax.nd.kdbxgit.android.MainActivity
import ax.nd.kdbxgit.android.R

/**
 * Posts user-visible notifications for sync error conditions and server-side merges.
 *
 * Notifications shown:
 *  - **Repeated failure**: after [FAILURE_THRESHOLD] consecutive sync failures a persistent
 *    error notification is shown with the last error message. It is cleared automatically on
 *    the next successful sync.
 *  - **Merge**: when the server returns a database that differs from the locally uploaded
 *    version (i.e. the server performed a KeePass merge), a one-shot "changes merged"
 *    notification is posted.
 */
class SyncNotifier(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        createChannel()
    }

    fun onSuccess() {
        nm.cancel(NOTIFICATION_ID_ERROR)
    }

    fun onMerge() {
        val openIntent = openAppIntent()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("KDBX Git — Changes merged")
            .setContentText("Remote changes were merged into your database.")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID_MERGE, notification)
    }

    fun onFailure(consecutiveCount: Int, errorMessage: String) {
        if (consecutiveCount < FAILURE_THRESHOLD) return

        val openIntent = openAppIntent()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("KDBX Git — Sync failed")
            .setContentText("$consecutiveCount consecutive failures. Last: $errorMessage")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$consecutiveCount consecutive sync failures.\nLast error: $errorMessage"))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(consecutiveCount > FAILURE_THRESHOLD) // only alert on the threshold hit
            .build()
        nm.notify(NOTIFICATION_ID_ERROR, notification)
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync Alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alerts for repeated sync failures and database merges"
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID            = "kdbx_git_sync_alerts"
        private const val NOTIFICATION_ID_ERROR = 2
        private const val NOTIFICATION_ID_MERGE = 3
        const val FAILURE_THRESHOLD     = 3
    }
}
