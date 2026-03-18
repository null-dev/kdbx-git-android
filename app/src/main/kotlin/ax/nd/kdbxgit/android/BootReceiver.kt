package ax.nd.kdbxgit.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ax.nd.kdbxgit.android.sync.SyncWorker

/**
 * Schedules the periodic [SyncWorker] after the device boots, but only if the user has
 * already configured server credentials. Without credentials the worker has nothing to do.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as KdbxGitApplication
        if (app.settingsRepository.serverConfig.value != null) {
            SyncWorker.schedulePeriodicSync(context, app.settingsRepository.pollIntervalMinutes.value)
        }
    }
}
