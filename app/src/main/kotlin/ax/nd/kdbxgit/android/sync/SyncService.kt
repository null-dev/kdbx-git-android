package ax.nd.kdbxgit.android.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ax.nd.kdbxgit.android.KdbxGitApplication
import ax.nd.kdbxgit.android.MainActivity
import ax.nd.kdbxgit.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that owns the sync coroutine scope and connectivity monitoring.
 *
 * Responsibilities:
 *  - Show a persistent notification reflecting the current [SyncStatus].
 *  - Register a [ConnectivityManager.NetworkCallback] to trigger a sync on
 *    network reconnection (offline → online transition only).
 *  - Handle [ACTION_SYNC] intents sent by [KdbxDocumentsProvider], [BootReceiver],
 *    [WorkManager][ax.nd.kdbxgit.android.sync.SyncWorker], and the UI "Sync now" button.
 *
 * [SyncRepository.sync] is already protected by a Mutex, so concurrent calls from
 * different trigger sources queue up and execute serially without duplication.
 */
class SyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val syncRepository get() = (application as KdbxGitApplication).syncRepository

    // Track the number of currently available networks so we can detect the
    // offline → online transition without mis-firing on network switches.
    private val availableNetworkCount = AtomicInteger(0)

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (availableNetworkCount.getAndIncrement() == 0) {
                // We were offline and a network just became available.
                serviceScope.launch { syncRepository.sync(SyncTrigger.CONNECTIVITY) }
            }
        }

        override fun onLost(network: Network) {
            availableNetworkCount.updateAndGet { maxOf(0, it - 1) }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(SyncStatus.Idle))
        registerConnectivityCallback()
        observeSyncStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SYNC) {
            val trigger = intent.getStringExtra(EXTRA_TRIGGER)
                ?.runCatching { SyncTrigger.valueOf(this) }
                ?.getOrNull()
                ?: SyncTrigger.MANUAL
            serviceScope.launch { syncRepository.sync(trigger) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterConnectivityCallback()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Connectivity ──────────────────────────────────────────────────────

    private fun registerConnectivityCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, connectivityCallback)
    }

    private fun unregisterConnectivityCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(connectivityCallback)
        } catch (_: IllegalArgumentException) {
            // Not registered — safe to ignore.
        }
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun observeSyncStatus() {
        serviceScope.launch {
            syncRepository.syncStatus.collect { status ->
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(status))
            }
        }
    }

    private fun buildNotification(status: SyncStatus): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, REQUEST_CODE_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val syncNowIntent = PendingIntent.getService(
            this, REQUEST_CODE_SYNC_NOW,
            syncIntent(this, SyncTrigger.MANUAL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val (statusText, isSyncing) = when (status) {
            SyncStatus.Idle        -> "Up to date" to false
            SyncStatus.Pulling     -> "Pulling…"   to true
            SyncStatus.Pushing     -> "Pushing…"   to true
            is SyncStatus.Error    -> "Error: ${status.message}" to false
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("KDBX Git")
            .setContentText(statusText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (isSyncing) setProgress(0, 0, /* indeterminate */ true)
                else addAction(0, "Sync now", syncNowIntent)
            }
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync Status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows database sync status; allows manual sync"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // ── Static helpers ────────────────────────────────────────────────────

    companion object {
        const val CHANNEL_ID = "kdbx_git_sync_status"
        private const val NOTIFICATION_ID      = 1
        private const val REQUEST_CODE_OPEN_APP = 0
        private const val REQUEST_CODE_SYNC_NOW = 1

        private const val ACTION_SYNC  = "ax.nd.kdbxgit.android.ACTION_SYNC"
        private const val EXTRA_TRIGGER = "trigger"

        /** Start the service (no-op if already running). */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncService::class.java))
        }

        /** Start the service (if not running) and enqueue a sync with [trigger]. */
        fun syncNow(context: Context, trigger: SyncTrigger = SyncTrigger.MANUAL) {
            context.startForegroundService(syncIntent(context, trigger))
        }

        private fun syncIntent(context: Context, trigger: SyncTrigger) =
            Intent(context, SyncService::class.java).apply {
                action = ACTION_SYNC
                putExtra(EXTRA_TRIGGER, trigger.name)
            }
    }
}
