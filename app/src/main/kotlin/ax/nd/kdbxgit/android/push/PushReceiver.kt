package ax.nd.kdbxgit.android.push

import android.content.Context
import org.unifiedpush.android.connector.MessagingReceiver
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

    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        val repo = (context.applicationContext as KdbxGitApplication).settingsRepository
        repo.savePushEndpoint(endpoint)
        PushRegistrationWorker.enqueue(context)
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        SyncWorker.enqueueSyncNow(context, SyncTrigger.PUSH)
    }

    override fun onUnregistered(context: Context, instance: String) {
        val repo = (context.applicationContext as KdbxGitApplication).settingsRepository
        // Enqueue DELETE before clearing the local endpoint so the worker can still
        // read the server config (the endpoint URL itself isn't needed for the DELETE call).
        PushRegistrationWorker.enqueueDelete(context)
        repo.clearPushEndpoint()
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        val repo = (context.applicationContext as KdbxGitApplication).settingsRepository
        repo.clearPushEndpoint()
    }
}
