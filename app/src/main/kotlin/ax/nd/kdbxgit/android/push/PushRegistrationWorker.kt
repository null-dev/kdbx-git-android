package ax.nd.kdbxgit.android.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ax.nd.kdbxgit.android.KdbxGitApplication
import ax.nd.kdbxgit.android.sync.WebDavClient
import java.util.concurrent.TimeUnit

/**
 * Registers, refreshes, or deletes this client's UnifiedPush endpoint on the kdbx-git server.
 *
 * Two flavours:
 *  - **One-time expedited** ([enqueue] / [enqueueDelete]) — fires immediately after
 *    [PushReceiver.onNewEndpoint] or [PushReceiver.onUnregistered].
 *  - **Periodic** ([schedulePeriodicRefresh]) — re-POSTs the stored endpoint every 3 days
 *    while charging to keep [last_seen_at] fresh and prevent server-side expiry (14-day TTL).
 *
 * Retries up to 3 times on network failure before giving up.
 */
class PushRegistrationWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val action = inputData.getString(KEY_ACTION) ?: return Result.failure()
        val app = appContext.applicationContext as KdbxGitApplication
        val config = app.settingsRepository.serverConfig.value
            ?: return Result.success() // no server configured — nothing to do

        return try {
            val client = WebDavClient(config)
            when (action) {
                ACTION_REGISTER -> {
                    val endpoint = app.settingsRepository.pushEndpoint.value
                        ?: return Result.success() // no endpoint stored yet
                    client.registerPushEndpoint(endpoint)
                    Result.success()
                }
                ACTION_DELETE -> {
                    client.deletePushEndpoint()
                    Result.success()
                }
                else -> Result.failure()
            }
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_ACTION            = "action"
        private const val ACTION_REGISTER       = "register"
        private const val ACTION_DELETE         = "delete"
        private const val WORK_NAME_REGISTER    = "push_endpoint_register"
        private const val WORK_NAME_DELETE      = "push_endpoint_delete"
        private const val WORK_NAME_PERIODIC    = "push_endpoint_refresh"
        private const val MAX_RETRIES           = 3

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Enqueue an expedited one-time registration (or refresh) with the server. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(networkConstraint)
                .setInputData(workDataOf(KEY_ACTION to ACTION_REGISTER))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_REGISTER, ExistingWorkPolicy.REPLACE, request)
        }

        /** Enqueue an expedited one-time deletion of the server-side endpoint registration. */
        fun enqueueDelete(context: Context) {
            val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(networkConstraint)
                .setInputData(workDataOf(KEY_ACTION to ACTION_DELETE))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_DELETE, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Schedule (or reschedule) the periodic 3-day endpoint refresh.
         * The [Constraints.setRequiresCharging] constraint ensures this runs
         * opportunistically with zero active battery cost.
         * Safe to call multiple times — uses [ExistingPeriodicWorkPolicy.UPDATE].
         */
        fun schedulePeriodicRefresh(context: Context) {
            val request = PeriodicWorkRequestBuilder<PushRegistrationWorker>(3, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresCharging(true)
                        .build()
                )
                .setInputData(workDataOf(KEY_ACTION to ACTION_REGISTER))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME_PERIODIC,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
        }

        /** Cancel the periodic refresh (e.g. when credentials are cleared). */
        fun cancelPeriodicRefresh(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        }
    }
}
