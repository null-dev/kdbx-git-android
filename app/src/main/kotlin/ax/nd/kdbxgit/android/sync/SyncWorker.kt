package ax.nd.kdbxgit.android.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ax.nd.kdbxgit.android.KdbxGitApplication
import ax.nd.kdbxgit.android.MainActivity
import ax.nd.kdbxgit.android.R
import java.util.concurrent.TimeUnit

/**
 * WorkManager [CoroutineWorker] that drives all sync operations.
 *
 * Replaces the old persistent [SyncService]. Two flavours are used:
 *  - **Expedited one-time** work for [SyncTrigger.WRITE] and [SyncTrigger.MANUAL] — runs
 *    as soon as possible, subject to the [NetworkType.CONNECTED] constraint.
 *  - **Periodic** work (≥ 15 min, [NetworkType.CONNECTED]) for [SyncTrigger.PERIODIC] —
 *    provides a backstop poll and automatically retries after network restoration.
 *
 * [setForeground] is called at the start of [doWork] so a brief "Syncing…" notification
 * is shown only while the worker is actively running — no permanent notification.
 */
class SyncWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val syncRepository: SyncRepository
        get() = (appContext.applicationContext as KdbxGitApplication).syncRepository

    override suspend fun doWork(): Result {
        val triggerName = inputData.getString(KEY_TRIGGER) ?: SyncTrigger.MANUAL.name
        val trigger = runCatching { SyncTrigger.valueOf(triggerName) }
            .getOrDefault(SyncTrigger.MANUAL)

        setForeground(buildForegroundInfo())
        syncRepository.sync(trigger)
        return Result.success()
    }

    // ── Foreground notification ────────────────────────────────────────────

    private fun buildForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        // Must pass the service type explicitly on API 29+; without it WorkManager calls
        // startForeground(id, notification, 0) which Android 14+ (targetSdk 34+) rejects
        // with InvalidForegroundServiceTypeException ("type none prohibited").
        return ForegroundInfo(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("KDBX Git")
            .setContentText("Syncing…")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, /* indeterminate */ true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync Status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows active sync progress; dismissed automatically when sync finishes"
            setShowBadge(false)
        }
        appContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    // ── Static helpers ────────────────────────────────────────────────────

    companion object {
        const val CHANNEL_ID = "kdbx_git_sync_status"
        private const val NOTIFICATION_ID       = 1
        private const val KEY_TRIGGER           = "trigger"
        private const val PERIODIC_WORK_NAME    = "kdbx_git_periodic_sync"
        private const val ONE_TIME_WORK_NAME    = "kdbx_git_one_time_sync"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Enqueue an expedited one-time sync (for [SyncTrigger.WRITE] / [SyncTrigger.MANUAL]).
         * Replaces any pending one-time request so rapid writes don't pile up.
         */
        fun enqueueSyncNow(context: Context, trigger: SyncTrigger = SyncTrigger.MANUAL) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(networkConstraints)
                .setInputData(workDataOf(KEY_TRIGGER to trigger.name))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Schedule (or reschedule) the periodic background sync with a 15-minute interval.
         * The [NetworkType.CONNECTED] constraint handles both periodic polling and the
         * offline → online reconnection trigger (WorkManager retries when network returns).
         *
         * Safe to call multiple times — uses [ExistingPeriodicWorkPolicy.UPDATE].
         */
        fun schedulePeriodicSync(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.PERIODIC.name))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
        }

        /**
         * Cancel the periodic sync job (e.g. when credentials are cleared).
         */
        fun cancelPeriodicSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }
}
