package ax.nd.kdbxgit.android.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import ax.nd.kdbxgit.android.KdbxGitApplication
import ax.nd.kdbxgit.android.push.PushRegistrationWorker
import ax.nd.kdbxgit.android.sync.SyncWorker
import kotlinx.coroutines.flow.StateFlow
import org.unifiedpush.android.connector.UnifiedPush

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as KdbxGitApplication).settingsRepository
    private val syncRepository = (application as KdbxGitApplication).syncRepository

    val serverConfig: StateFlow<ServerConfig?> = repository.serverConfig
    val pushEndpoint: StateFlow<String?> = repository.pushEndpoint
    val pollIntervalMinutes: StateFlow<Long> = repository.pollIntervalMinutes

    fun save(
        serverUrl: String,
        clientId: String,
        password: String,
        customCaCertPem: String?,
        pollIntervalMinutes: Long,
    ) {
        val trimmedUrl      = serverUrl.trim()
        val trimmedClientId = clientId.trim()

        // Wipe local database and sync state if the server endpoint changed,
        // preventing stale data from a previous server being visible to the new one.
        val prev = repository.serverConfig.value
        val endpointChanged = prev == null ||
            prev.serverUrl != trimmedUrl ||
            prev.clientId  != trimmedClientId
        if (endpointChanged) {
            syncRepository.clearLocalData()
            // Clear the stored endpoint; onNewEndpoint from registerApp() below will
            // re-populate it and register with the new server.
            repository.clearPushEndpoint()
        }

        repository.save(
            serverUrl       = trimmedUrl,
            clientId        = trimmedClientId,
            password        = password,
            customCaCertPem = customCaCertPem?.trim()?.takeIf { it.isNotBlank() },
        )
        repository.savePollInterval(pollIntervalMinutes)
        SyncWorker.schedulePeriodicSync(getApplication(), pollIntervalMinutes)

        // (Re-)register with the UP distributor. If a distributor is installed this
        // triggers onNewEndpoint → PushRegistrationWorker, registering the endpoint
        // with the (possibly new) server. If no distributor is installed this is a no-op.
        UnifiedPush.registerApp(getApplication())
        PushRegistrationWorker.schedulePeriodicRefresh(getApplication())
    }
}
