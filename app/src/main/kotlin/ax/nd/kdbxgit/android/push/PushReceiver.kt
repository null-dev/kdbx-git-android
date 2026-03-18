package ax.nd.kdbxgit.android.push

import android.content.Context
import android.util.Log
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import ax.nd.kdbxgit.android.KdbxGitApplication
import ax.nd.kdbxgit.android.sync.SyncTrigger
import ax.nd.kdbxgit.android.sync.SyncWorker

/**
 * Receives UnifiedPush lifecycle events from the distributor app.
 *
 *  - [onNewEndpoint]: distributor assigned (or reassigned) an endpoint URL. Persist it
 *    locally and register it with the kdbx-git server via [PushRegistrationWorker].
 *  - [onMessage]: server sent a `branch-updated` wakeup. Trigger an expedited sync.
 *  - [onUnregistered]: distributor revoked the registration. Delete from the server and
 *    clear the locally stored endpoint.
 *  - [onRegistrationFailed]: UP registration could not complete. Clear any stale endpoint.
 */
class PushReceiver : MessagingReceiver() {

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        Log.i(TAG, "New UP endpoint received — enqueueing server registration")
        val repo = (context.applicationContext as KdbxGitApplication).settingsRepository
        repo.savePushEndpoint(endpoint.url)
        PushRegistrationWorker.enqueue(context)
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        Log.d(TAG, "Push message received — triggering sync")
        SyncWorker.enqueueSyncNow(context, SyncTrigger.PUSH)
    }

    override fun onUnregistered(context: Context, instance: String) {
        Log.i(TAG, "UP unregistered — enqueueing server endpoint deletion")
        val repo = (context.applicationContext as KdbxGitApplication).settingsRepository
        // Enqueue DELETE before clearing the local endpoint so the worker can still
        // read the server config (the endpoint URL itself isn't needed for the DELETE call).
        PushRegistrationWorker.enqueueDelete(context)
        repo.clearPushEndpoint()
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        Log.w(TAG, "UP registration failed: $reason")
        val repo = (context.applicationContext as KdbxGitApplication).settingsRepository
        repo.clearPushEndpoint()
    }

    companion object {
        private const val TAG = "PushReceiver"
    }
}
