package ax.nd.kdbxgit.android.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import ax.nd.kdbxgit.android.KdbxGitApplication
import ax.nd.kdbxgit.android.sync.SyncWorker
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as KdbxGitApplication).settingsRepository
    private val syncRepository = (application as KdbxGitApplication).syncRepository

    val serverConfig: StateFlow<ServerConfig?> = repository.serverConfig

    fun save(
        serverUrl: String,
        clientId: String,
        username: String,
        password: String,
        customCaCertPem: String?,
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
        }

        repository.save(
            serverUrl       = trimmedUrl,
            clientId        = trimmedClientId,
            username        = username.trim(),
            password        = password,
            customCaCertPem = customCaCertPem?.trim()?.takeIf { it.isNotBlank() },
        )
        SyncWorker.schedulePeriodicSync(getApplication())
    }
}
